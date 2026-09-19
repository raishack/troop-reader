package es.gamingtroop.reader

import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateFeedTest {
    @get:Rule val temp = TemporaryFolder()
    private fun release(bytes: ByteArray = "signed fixture".toByteArray()): AppRelease {
        val file = temp.newFile().apply { writeBytes(bytes) }
        return AppRelease(1, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE + 1, "0.1.0-next", 26,
            "https://claw.raishack.es/troop-reader/troop-reader-next.apk", bytes.size.toLong(), UpdatePolicy.hash(file), "Nueva versión")
    }
    private fun rejects(release: AppRelease) { assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.validate(release) } }
    @Test fun acceptsPublishedSchemaWithFutureFields() {
        val r = release(); assertEquals(r, UpdatePolicy.parse(codec.encodeToString(r).dropLast(1) + ",\"future\":true}"))
    }
    @Test fun rejectsWrongAppAndSchema() { val r = release(); rejects(r.copy(schemaVersion = 2)); rejects(r.copy(packageName = "other.app")) }
    @Test fun rejectsForeignOrInsecureUrls() {
        val r = release()
        for(url in listOf("http://claw.raishack.es/troop-reader/a.apk", "https://evil.invalid/troop-reader/a.apk", "https://claw.raishack.es.evil.invalid/a.apk", "https://claw.raishack.es:8080/troop-reader/a.apk")) rejects(r.copy(apkUrl=url))
    }
    @Test fun rejectsQueriesCredentialsFragmentsAndTraversal() {
        val r=release()
        for(url in listOf(r.apkUrl+"?token=fixture",r.apkUrl+"#test", r.apkUrl.replace("https://","https://user@"), r.apkUrl.replace("troop-reader-next.apk","%2e%2e/next.apk"),r.apkUrl.replace("troop-reader-next.apk","troop-reader-%2fnext.apk"))) rejects(r.copy(apkUrl=url))
    }
    @Test fun rejectsBadSizeHashSdkAndVersion() {
        val r = release()
        listOf(r.copy(sizeBytes=0),r.copy(sizeBytes=UpdatePolicy.MAX_APK_BYTES+1),r.copy(sha256="bad"),r.copy(minSdk=0),r.copy(versionCode=0),r.copy(versionName="<script>"),r.copy(notes="x".repeat(8001))).forEach(::rejects)
    }
    @Test fun checkThrottleHandlesFirstLaunchAndClockRollback() {
        assertTrue(UpdatePolicy.shouldCheck(0, 10)); assertFalse(UpdatePolicy.shouldCheck(100, 101))
        assertTrue(UpdatePolicy.shouldCheck(100, 100+UpdatePolicy.CHECK_INTERVAL_MS)); assertTrue(UpdatePolicy.shouldCheck(100,99))
    }
    @Test fun feedNeverSendsAccountCredentialsAndIgnoresCaches() {
        MockWebServer().use { server ->
            val r=release(); server.enqueue(MockResponse().setBody(codec.encodeToString(r)))
            assertEquals(r,UpdateFeed(feedUrl=server.url("/latest.json")).latest())
            val request=server.takeRequest(); assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"));assertEquals("no-cache",request.getHeader("Cache-Control"))
        }
    }
    @Test fun feedRejectsRedirectWithoutFollowing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            assertThrows(java.io.IOException::class.java) { UpdateFeed(feedUrl=server.url("/latest.json")).latest() }; assertEquals(1,server.requestCount)
        }
    }
    @Test fun feedRejectsOversizedChunkedContent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(33000),1000))
            assertThrows(IllegalArgumentException::class.java) { UpdateFeed(feedUrl=server.url("/latest.json")).latest() }
        }
    }
    private fun downloader(server: MockWebServer): UpdateFeed = UpdateFeed(client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/apk")).build()) }.build())
    @Test fun completeDownloadIsAtomicAndReportsProgress() {
        MockWebServer().use { server ->
            val bytes="the apk bytes".repeat(20000).toByteArray();val r=release(bytes)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val target=File(temp.root,"complete.apk");val progress=mutableListOf<Int>()
            downloader(server).download(r,target,progress={ progress+=it })
            assertArrayEquals(bytes,target.readBytes());assertEquals(100,progress.last());assertFalse(File(target.path+".part").exists())
        }
    }
    @Test fun hashMismatchNeverReplacesExistingFile() {
        MockWebServer().use { server ->
            val r=release("aaaa".toByteArray());server.enqueue(MockResponse().setBody("bbbb"))
            val target=temp.newFile().apply { writeText("previous good file") }
            assertThrows(IllegalArgumentException::class.java) { downloader(server).download(r,target) }
            assertEquals("previous good file",target.readText());assertFalse(File(target.path+".part").exists())
        }
    }
    @Test fun shortOrOversizedBodiesNeverBecomeApks() {
        for(body in listOf("a","a".repeat(10))) MockWebServer().use { server ->
            val r=release("aaaa".toByteArray());server.enqueue(MockResponse().setChunkedBody(body,1));val target=File(temp.root,"invalid.apk")
            assertThrows(IllegalArgumentException::class.java) { downloader(server).download(r,target) };assertFalse(target.exists());assertFalse(File(target.path+".part").exists())
        }
    }
    @Test fun cancelledDownloadRemovesPartial() {
        MockWebServer().use { server ->
            val bytes="x".repeat(100000).toByteArray();val r=release(bytes);server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val target=File(temp.root,"cancelled.apk");var reads=0
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { downloader(server).download(r,target,active={ if(++reads>1) throw kotlinx.coroutines.CancellationException() }) }
            assertFalse(target.exists());assertFalse(File(target.path+".part").exists())
        }
    }
    @Test fun apkRedirectIsNotFollowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            assertThrows(java.io.IOException::class.java) { downloader(server).download(release(),File(temp.root,"redirect.apk")) };assertEquals(1,server.requestCount)
        }
    }
}
