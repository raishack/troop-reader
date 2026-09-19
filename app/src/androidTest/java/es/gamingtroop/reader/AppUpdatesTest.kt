package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.NetworkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdatesTest {
    @get:Rule val permission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val fixture: ByteArray get() = InstrumentationRegistry.getInstrumentation().context.assets.open("update-fixture.apk").use { it.readBytes() }
    private fun release(bytes: ByteArray = fixture): AppRelease {
        val f=File(context.cacheDir,"update-test.apk").apply { writeBytes(bytes) }
        return AppRelease(1,BuildConfig.APPLICATION_ID,100000,"0.1.0-fixture100000",26,
            "https://claw.raishack.es/troop-reader/troop-reader-fixture100000.apk",bytes.size.toLong(),UpdatePolicy.hash(f),"Mejoras de prueba")
    }
    private fun client(server: MockWebServer): UpdateFeed = UpdateFeed(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build(),server.url("/latest.json"))
    @Before fun setup() {
        check(!ReaderApp.updateAutomationEnabled)
        check(context.repository().active()?.server?.let { it == "https://demo.invalid" || it.startsWith("http://localhost:") } != false)
        WorkManager.getInstance(context).cancelAllWork().result.get()
        prefs.edit().clear().putBoolean("autoDownload",false).commit()
        File(context.filesDir,"app-updates").deleteRecursively()
    }
    @After fun cleanup() {
        scenario?.close();WorkManager.getInstance(context).cancelAllWork().result.get()
        prefs.edit().clear().commit(); File(context.filesDir,"app-updates").deleteRecursively()
    }
    @Test fun detectsNewVersionPersistsMetadataAndDoesNotOfferDowngrades() = runBlocking {
        MockWebServer().use { server ->
            val r=release();server.enqueue(MockResponse().setBody(codec.encodeToString(r)))
            val updates=AppUpdates(context,client(server));updates.checkNow()
            assertEquals(r,updates.states.value.release);assertEquals(r,AppUpdates(context).states.value.release)
            assertTrue(updates.states.value.lastChecked>0);assertFalse(updates.states.value.ready)
            server.enqueue(MockResponse().setBody(codec.encodeToString(r.copy(versionCode=BuildConfig.VERSION_CODE))))
            updates.checkNow();assertNull(updates.states.value.release)
            server.enqueue(MockResponse().setBody(codec.encodeToString(r.copy(versionCode=BuildConfig.VERSION_CODE-1))))
            updates.checkNow();assertNull(updates.states.value.release)
        }
    }
    @Test fun downloadsVerifiesRealSignedApkAndPersistsReadyState() = runBlocking {
        MockWebServer().use { server ->
            val r=release();server.enqueue(MockResponse().setBody(codec.encodeToString(r)));server.enqueue(MockResponse().setBody(okio.Buffer().write(fixture)))
            val updates=AppUpdates(context,client(server));updates.checkNow();updates.download(r)
            assertTrue(updates.states.value.ready);assertTrue(AppUpdates(context).states.value.ready)
            val intent=updates.installerIntent();assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("content",intent.data!!.scheme)
            assertEquals("${context.packageName}.updates",intent.data!!.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertArrayEquals(fixture,context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
            // A cancelled system install doesn't consume the download.
            assertEquals(intent.data,updates.installerIntent().data)
        }
    }
    @Test fun editingReleaseNotesKeepsVerifiedPackageWithoutAnotherDownload() = runBlocking {
        MockWebServer().use { server ->
            val r = release()
            server.enqueue(MockResponse().setBody(codec.encodeToString(r)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(fixture)))
            val updates = AppUpdates(context, client(server)); updates.checkNow(); updates.download(r)
            val edited = r.copy(notes = "Notas ampliadas sin cambiar la APK")
            server.enqueue(MockResponse().setBody(codec.encodeToString(edited)))
            updates.checkNow()
            assertTrue(updates.states.value.ready)
            assertEquals(edited.notes, updates.states.value.release!!.notes)
            // A retried/duplicate worker must not fetch an already verified package again.
            server.enqueue(MockResponse().setResponseCode(503))
            updates.download(edited)
            assertEquals(3, server.requestCount)
            assertTrue(AppUpdates(context).states.value.ready)
        }
    }
    @Test fun interruptedReadyStateRecoversVerifiedApkWithoutNetwork() = runBlocking {
        val r = release()
        prefs.edit().putString("release", codec.encodeToString(r)).commit()
        AppUpdates(context).file(r).apply { parentFile!!.mkdirs(); writeBytes(fixture) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val updates = AppUpdates(context, client(server))
            updates.download(r)
            assertTrue(updates.states.value.ready)
            assertEquals(0, server.requestCount)
            assertNotNull(updates.installerIntent().data)
        }
    }
    @Test fun releaseNoteRefreshDuringDownloadStillProducesReadyPackage() = runBlocking {
        MockWebServer().use { server ->
            val original = release(); val edited = original.copy(notes = "Notas actualizadas")
            val started = CompletableDeferred<Unit>(); val finish = java.util.concurrent.CountDownLatch(1)
            var checks = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.path == "/latest.json") return MockResponse().setBody(codec.encodeToString(if(checks++ == 0) original else edited))
                    started.complete(Unit); check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody(okio.Buffer().write(fixture))
                }
            }
            val updates = AppUpdates(context, client(server)); updates.checkNow()
            val job = launch(Dispatchers.IO) { updates.download(original) }
            try { withTimeout(5000) { started.await() }; updates.checkNow() }
            finally { finish.countDown(); job.join() }
            assertEquals(edited, updates.states.value.release)
            assertTrue(updates.states.value.ready); assertTrue(AppUpdates(context).states.value.ready)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun supersededDownloadFailureCannotOverwriteNewReleaseState() = runBlocking {
        MockWebServer().use { server ->
            val old = release()
            val next = old.copy(versionCode = old.versionCode + 1, sha256 = "b".repeat(64))
            val started = CompletableDeferred<Unit>()
            val finish = java.util.concurrent.CountDownLatch(1)
            var checks = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.path == "/latest.json") return MockResponse().setBody(codec.encodeToString(if(checks++ == 0) old else next))
                    started.complete(Unit)
                    check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503)
                }
            }
            val updates = AppUpdates(context, client(server)); updates.checkNow()
            val download = launch(Dispatchers.IO) { try { updates.download(old) } catch(_: Exception) { } }
            try {
                withTimeout(5000) { started.await() }
                updates.checkNow()
                assertEquals(next, updates.states.value.release)
                assertFalse(updates.states.value.downloading)
            } finally { finish.countDown(); download.join() }
            assertNull(updates.states.value.error)
            assertEquals(0, updates.states.value.percent)
            assertEquals(next, AppUpdates(context).states.value.release)
        }
    }
    @Test fun corruptStagedApkIsRevalidatedBeforeInstallation() = runBlocking {
        MockWebServer().use { server ->
            val r=release();server.enqueue(MockResponse().setBody(codec.encodeToString(r)));server.enqueue(MockResponse().setBody(okio.Buffer().write(fixture)))
            val updates=AppUpdates(context,client(server));updates.checkNow();updates.download(r)
            updates.file(r).writeBytes(ByteArray(fixture.size))
            var failed=false;try { updates.installerIntent() } catch(_: Exception) { failed=true }
            assertTrue(failed);assertFalse(updates.states.value.ready);assertFalse(AppUpdates(context).states.value.ready)
        }
    }
    @Test fun htmlWithMatchingHashIsNotAnInstallableApk() = runBlocking {
        MockWebServer().use { server ->
            val bytes="<html>server error</html>".toByteArray();val r=release(bytes)
            server.enqueue(MockResponse().setBody(codec.encodeToString(r)));server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val updates=AppUpdates(context,client(server));updates.checkNow()
            var failed=false;try { updates.download(r) } catch(_: Exception) { failed=true }
            assertTrue(failed);assertFalse(updates.states.value.ready);assertFalse(updates.file(r).exists())
        }
    }
    @Test fun versionInsideApkMustMatchFeed() {
        val r=release();val updates=AppUpdates(context);val f=updates.file(r).apply { parentFile!!.mkdirs();writeBytes(fixture) }
        assertThrows(IllegalArgumentException::class.java) { updates.verifyApk(f,r.copy(versionCode=999)) }
        assertThrows(IllegalArgumentException::class.java) { updates.verifyApk(f,r.copy(versionName="other")) }
    }
    @Test fun missingApkSignatureCannotBeInstalledEvenWithMatchingHash() {
        val raw=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(raw).use { output -> java.util.zip.ZipInputStream(fixture.inputStream()).use { input ->
            while(true) {
                val entry=input.nextEntry ?: break
                if(entry.name.startsWith("META-INF/")) continue
                output.putNextEntry(java.util.zip.ZipEntry(entry.name));input.copyTo(output);output.closeEntry()
            }
        } }
        val bytes=raw.toByteArray();val r=release(bytes);val updates=AppUpdates(context)
        val f=updates.file(r).apply { parentFile!!.mkdirs();writeBytes(bytes) }
        assertThrows(Exception::class.java) { updates.verifyApk(f,r) }
    }
    @Test fun fileProviderNeverExposesReadingFiles() {
        val private=File(context.filesDir,"accounts/private-reading.json")
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",private)
        }
    }
    @Test fun connectionFailurePreservesKnownUpdateAndReadingData() = runBlocking {
        Demo.install(context)
        val store=context.repository().store(context.repository().active()!!.key)
        val before=store.get()
        MockWebServer().use { server ->
            val r=release();server.enqueue(MockResponse().setBody(codec.encodeToString(r)))
            val updates=AppUpdates(context,client(server));updates.checkNow()
            server.enqueue(MockResponse().setResponseCode(503))
            try { updates.checkNow() } catch(_: Exception) { }
            assertEquals(r,updates.states.value.release);assertNotNull(updates.states.value.error);assertFalse(updates.states.value.checking)
            assertEquals(before,store.get())
        }
    }
    @Test fun wifiQueueCanBeChangedToManualWithoutTouchingBookDownloads() {
        val r=release();prefs.edit().putString("release",codec.encodeToString(r)).commit()
        val updates=AppUpdates(context);val wm=WorkManager.getInstance(context)
        updates.autoDownload(true)
        val first=wm.getWorkInfosForUniqueWork("app-update-download-${r.sha256}").get().last()
        assertEquals(NetworkType.UNMETERED,first.constraints.requiredNetworkType)
        updates.autoDownload(false)
        assertFalse(AppUpdates(context).states.value.autoDownload)
        updates.enqueueDownload(true)
        val all=wm.getWorkInfosForUniqueWork("app-update-download-${r.sha256}").get()
        assertTrue(all.any { it.id!=first.id && it.constraints.requiredNetworkType==NetworkType.CONNECTED })
    }
    @Test fun availableUpdatePanelShowsNotesAndMobileDataConfirmation() {
        val r=release();prefs.edit().putString("release",codec.encodeToString(r)).commit()
        val updates=AppUpdates(context)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        scenario!!.onActivity { it.setContent { MaterialTheme { UpdateDialog(updates) {} } } }
        ui.onNodeWithText("Nueva versión · 0.1.0-fixture100000").assertExists()
        ui.onNodeWithText("Mejoras de prueba").assertExists()
        ui.onNodeWithTag("download-update").performScrollTo().performClick()
        ui.onNodeWithText("incluidos datos móviles",substring=true).assertExists()
        ui.onNodeWithText("Cancelar").performClick()
        screenshot("update-available")
        assertFalse(updates.states.value.downloading)
    }
    @Test fun readyPanelOffersAndroidInstallerAndDoesNotAlterProgress() = runBlocking {
        val r=release();prefs.edit().putString("release",codec.encodeToString(r)).putString("ready",r.sha256).commit()
        AppUpdates(context).file(r).apply { parentFile!!.mkdirs();writeBytes(fixture) }
        val updates=AppUpdates(context)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        scenario!!.onActivity { it.setContent { MaterialTheme { UpdateDialog(updates) {} } } }
        ui.onNodeWithTag("install-update").assertIsEnabled()
        ui.onNodeWithText("Se conservarán tus descargas",substring=true).assertExists()
        screenshot("update-ready")
        assertTrue(updates.states.value.ready)
    }
    @Test fun unsupportedAndroidShowsExplanationAndNeverDownloads() = runBlocking {
        MockWebServer().use { server ->
            val r=release().copy(minSdk=1000);server.enqueue(MockResponse().setBody(codec.encodeToString(r)))
            val updates=AppUpdates(context,client(server));updates.checkNow();updates.download(r)
            assertEquals(1,server.requestCount);assertFalse(updates.states.value.ready)
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            scenario!!.onActivity { it.setContent { MaterialTheme { UpdateDialog(updates) {} } } }
            ui.onNodeWithText("Esta versión necesita un Android más reciente.",substring=true).assertExists()
            ui.onNodeWithTag("download-update").assertDoesNotExist()
        }
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output=File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        File(output,"alpha15-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
}
