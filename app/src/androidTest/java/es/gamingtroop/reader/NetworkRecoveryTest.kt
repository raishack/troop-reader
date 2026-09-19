package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NetworkRecoveryTest {
    @get:Rule val permission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createEmptyComposeRule()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val repo get()=context.repository()
    private var scenario:ActivityScenario<MainActivity>?=null
    private val source=object:NetworkSource {
        @Volatile var connected=false
        override fun available()=connected
        override fun subscribe(changed:()->Unit):()->Unit = {} // Simulate missed vendor callbacks.
    }
    @Before fun setup() {
        require(repo.active()?.server?.let { it=="https://demo.invalid" || it.startsWith("http://localhost:") } != false)
        WorkManager.getInstance(context).cancelAllWork().result.get();repo.vault.clear();Demo.install(context)
    }
    @After fun cleanup() { scenario?.close();WorkManager.getInstance(context).cancelAllWork().result.get() }
    @Test fun booksHaveIndividualArtworkAndSelectAllBooksEvenInsideOneVolume() {
        MockWebServer().use { server ->
            val png=repo.store(repo.active()!!.key).coverFile(1).readBytes()
            val series=Series(44,"Colección de libros",2,3)
            val volumes=listOf(Volume(50,"0",listOf(Chapter(91,titleName="Libro del mar",pages=4,format=3),Chapter(92,titleName="Libro del bosque",pages=8,format=3))))
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse = when {
                    r.path=="/api/Library/libraries" -> MockResponse().setBody("[{\"id\":2,\"name\":\"Libros\",\"type\":2}]")
                    r.path!!.startsWith("/api/Series/all-v2") -> MockResponse().setBody(codec.encodeToString(listOf(series)))
                    r.path!!.startsWith("/api/Series/volumes") -> MockResponse().setBody(codec.encodeToString(volumes))
                    r.path!!.startsWith("/api/Image/") -> MockResponse().setBody(okio.Buffer().write(png))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val a=Account(server.url("/").toString().trimEnd('/'),995,"Book fixture","fixture")
            repo.vault.save(a);val store=repo.store(a.key)
            store.update { LocalState(series=listOf(series),libraries=listOf(Library(2,"Libros",2))) }
            launchOnline()
            ui.onNodeWithText("Colección de libros").performClick()
            ui.waitUntil(12000) { ui.onAllNodesWithText("Libro del bosque").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Seleccionar todos los libros").performClick()
            ui.onNodeWithText("Descargar (2)").assertIsEnabled()
            ui.waitUntil(10000) { validCover(CoverRef("chapter",92).file(store)) }
            val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "alpha5-libros.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithText("Descargar (2)").performClick()
            ui.onNodeWithText("2 libros ·",substring=true).assertExists()
            ui.onNodeWithText("Cancelar").performClick()
            assertTrue(store.get().pending.isEmpty())
        }
    }
    private fun launchOnline() {
        source.connected=true
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        scenario!!.onActivity { it.setContent { CompositionLocalProvider(LocalNetworkSource provides source) { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Green, background = androidx.compose.ui.graphics.Color(0xFF101419), surface = Surface)) { App(repo) } } } }
    }
    @Test fun resumeRecoversMissedConnectionReloadsOpenTomesAndRefreshesExpiredSession() {
        MockWebServer().use { server ->
            val png=repo.store(repo.active()!!.key).coverFile(1).readBytes()
            val libraryReads=AtomicInteger();val volumeReads=AtomicInteger();val refreshes=AtomicInteger()
            val series=Series(41,"Manga de reconexión",1,1)
            val volumes=listOf(Volume(51,"1",listOf(Chapter(81,pages=3))),Volume(52,"2",listOf(Chapter(82,pages=4))))
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse {
                    if(r.path=="/api/Account/refresh-token") { refreshes.incrementAndGet();return MockResponse().setBody("{\"token\":\"new-fixture\",\"refreshToken\":\"refresh-fixture\"}") }
                    if(r.getHeader("Authorization")=="Bearer expired-fixture") return MockResponse().setResponseCode(401)
                    return when {
                        r.path=="/api/Library/libraries" -> { libraryReads.incrementAndGet();MockResponse().setBody("[{\"id\":1,\"name\":\"Manga\",\"type\":0}]") }
                        r.path!!.startsWith("/api/Series/all-v2") -> MockResponse().setBody(codec.encodeToString(listOf(series)))
                        r.path!!.startsWith("/api/Series/volumes") -> { volumeReads.incrementAndGet();MockResponse().setBody(codec.encodeToString(volumes)) }
                        r.path!!.startsWith("/api/Image/") -> MockResponse().setBody(okio.Buffer().write(png))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val a=Account(server.url("/").toString().trimEnd('/'),992,"Resume fixture","expired-fixture","refresh-fixture")
            repo.vault.save(a);val store=repo.store(a.key);store.update { LocalState(series=listOf(series)) }
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            scenario!!.onActivity { it.setContent { CompositionLocalProvider(LocalNetworkSource provides source) { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Green, background = androidx.compose.ui.graphics.Color(0xFF101419), surface = Surface)) { App(repo) } } } }
            ui.onNodeWithText("Manga de reconexión").performClick()
            ui.onNodeWithText("Catálogo guardado · abre los tomos descargados").assertExists()
            val current=scenario
            scenario!!.moveToState(Lifecycle.State.CREATED)
            source.connected=true // No network callback, same activity, no kill/recreate.
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            ui.waitUntil(12000) { ui.onAllNodesWithText("Tomo 2").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Seleccionar todos los tomos").performClick()
            ui.onNodeWithText("Descargar (2)").assertIsEnabled()
            val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "alpha5-tomos.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            assertSame(current,scenario);assertTrue(libraryReads.get()>0);assertTrue(volumeReads.get()>0)
            assertTrue(refreshes.get()>0);assertEquals("new-fixture",repo.active()!!.token)
            ui.waitUntil(10000) { validCover(CoverRef("volume",52).file(store)) }
            // Background losing network then return: keep cached covers/tomes, disable online actions.
            scenario!!.moveToState(Lifecycle.State.CREATED);source.connected=false;scenario!!.moveToState(Lifecycle.State.RESUMED)
            ui.onNodeWithText("Catálogo guardado · abre los tomos descargados").assertExists()
            ui.onNodeWithText("Tomo 2").assertExists()
            assertTrue(validCover(CoverRef("volume",52).file(store)))
            // Foreground handover with a dropped callback is healed by periodic snapshot.
            val before=volumeReads.get();source.connected=true
            ui.waitUntil(12000) { volumeReads.get()>before }
            ui.onNodeWithText("Quitar selección").assertExists()
        }
    }
    @Test fun partiallyDownloadedSeriesStillOpensFullOnlineCatalogueAndSelectsOnlyMissingVolumes() {
        MockWebServer().use { server ->
            val demoStore=repo.store(repo.active()!!.key)
            val png=demoStore.coverFile(1).readBytes()
            val series=Series(48,"Serie a medias",1,1)
            val first=Chapter(88,58,pages=2,format=1)
            val second=Chapter(89,59,pages=4,format=1)
            val volumes=listOf(Volume(58,"1",listOf(first)),Volume(59,"2",listOf(second)))
            val volumeReads=AtomicInteger()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse=when {
                    r.path=="/api/Library/libraries" -> MockResponse().setBody(codec.encodeToString(listOf(Library(1,"Manga",0))))
                    r.path!!.startsWith("/api/Series/all-v2") -> MockResponse().setBody(codec.encodeToString(listOf(series)))
                    r.path!!.startsWith("/api/Series/volumes") -> { volumeReads.incrementAndGet();MockResponse().setBody(codec.encodeToString(volumes)) }
                    r.path!!.startsWith("/api/Image/") -> MockResponse().setBody(okio.Buffer().write(png))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val a=Account(server.url("/").toString().trimEnd('/'),998,"Partial fixture","fixture")
            repo.vault.save(a);val store=repo.store(a.key)
            val dir=store.chapterDir(88).apply { mkdirs() }
            for(n in 0..1) java.io.File(dir,"$n.img").writeBytes(png)
            store.update { LocalState(series=listOf(series),libraries=listOf(Library(1,"Manga",0)),
                chapters=mapOf(88 to SavedChapter(first,series,false,ready=true,downloadedPages=2)),
                progress=mapOf(88 to Progress(1,48,58,88,1))) }
            launchOnline()
            ui.onNodeWithTag("library-grid").performScrollToNode(hasTestTag("library-series-48"))
            ui.onNodeWithTag("library-series-48").performClick()
            ui.onNodeWithTag("reader-surface").assertExists()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(5000) { ui.onAllNodesWithTag("library-grid").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("library-grid").performScrollToNode(hasTestTag("catalog-series-48"))
            ui.onNodeWithTag("catalog-series-48").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Seleccionar todos los tomos").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Tomo 1").assertExists();ui.onNodeWithText("Tomo 2").assertExists()
            ui.onNodeWithContentDescription("Seleccionar Tomo 1").assertDoesNotExist()
            ui.onNodeWithContentDescription("Seleccionar Tomo 2").assertIsOff()
            ui.onNodeWithText("Seleccionar todos los tomos").performClick()
            ui.onNodeWithText("Descargar (1)").performClick()
            ui.onNodeWithText("1 tomos · 4 páginas/secciones.",substring=true).assertExists()
            ui.onNodeWithText("Cancelar").performClick()
            assertTrue(volumeReads.get()>0);assertEquals(1,store.progress(88)!!.pageNum)
            assertTrue(store.get().chapters.getValue(88).ready)
        }
    }

}
