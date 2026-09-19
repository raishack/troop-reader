package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Run only in the disposable test emulator: uses generated fixtures, never real accounts. */
@RunWith(AndroidJUnit4::class)
class OfflineFlowTest {
    @get:Rule val notificationPermission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo get() = context.repository()
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun prepare() {
        val active = repo.active()
        require(active == null || active.server == "https://demo.invalid" || active.server.startsWith("http://localhost:")) { "Tests must not replace a real account" }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        repo.vault.clear()
        Demo.install(context)
    }
    @After fun cleanup() { scenario?.close(); WorkManager.getInstance(context).cancelAllWork().result.get() }
    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        scenario!!.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Descargas").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        ui.waitForIdle()
    }
    private fun downloads() { ui.onNodeWithText("Descargas").performClick() }
    private fun openSeries(id: Int) {
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-$id"))
        ui.onNodeWithTag("read-series-$id").performClick()
    }
    private fun revealControls() {
        awaitReaderScript()
        if(ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isEmpty()) {
            ui.onNodeWithTag("reader-content").performTouchInput { click(center) }
            ui.waitUntil(3000) { ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun freshLoginAndSettingsKeepManualDefaultAndPersistTheOptionalPriority() {
        repo.vault.clear()
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.onNodeWithText("Servidor Kavita").assertExists()
        ui.onNodeWithText("Usuario").assertExists()
        ui.onNodeWithText("Ver demostración sin cuenta").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Descargas").fetchSemanticsNodes().isNotEmpty() }
        val store=repo.store(repo.active()!!.key)
        assertFalse(store.get().settings.preferLocalChanges)
        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithContentDescription("Prioridad de este móvil").performScrollTo().assertIsOff().performClick()
        assertTrue(store.get().settings.preferLocalChanges)
        scenario!!.recreate()
        ui.onNodeWithContentDescription("Prioridad de este móvil").performScrollTo().assertIsOn()
        ui.onNodeWithText("Listo").performClick()
        ui.onNodeWithText("Progreso").performClick()
        ui.onNodeWithText("Prioridad: cambios de este móvil").assertIsDisplayed()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithContentDescription("Prioridad de este móvil").performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick()
        ui.onNodeWithText("Prioridad: revisar conflictos").assertIsDisplayed()
        assertFalse(Store(store.root).get().settings.preferLocalChanges)
    }
    @Test fun comicDoublePageAndCompletedReadingSurviveRecreation() {
        val a = repo.active()!!; val store = repo.store(a.key)
        store.update { it.copy(settings = it.settings.copy(imageMode = "double")) }
        launch(); downloads()
        openSeries(2)
        revealControls(); ui.onNodeWithText("1–2 / 6").assertExists()
        awaitReaderScript()
        ui.waitUntil(10000) { js("Array.from(document.images).filter(i => i.complete && i.naturalWidth === 600 && i.getBoundingClientRect().height > 200 && i.getBoundingClientRect().width > 100).length") == "2" }
        ui.onNodeWithText("Siguiente →").performClick()
        revealControls(); ui.onNodeWithText("3–4 / 6").assertExists()
        scenario!!.recreate(); ui.waitForIdle()
        revealControls(); ui.onNodeWithText("3–4 / 6").assertExists()
        ui.onNodeWithText("Siguiente →").performClick()
        revealControls(); ui.onNodeWithText("5–6 / 6").assertExists()
        ui.onNodeWithText("Terminado").performClick()
        ui.onNodeWithText("Volver a la biblioteca").performClick()
        assertEquals(6, store.progress(200)!!.pageNum)
        openSeries(2) // Reopening completed volume keeps completion.
        revealControls(); ui.onNodeWithContentDescription("Volver").performClick()
        assertEquals(6, store.progress(200)!!.pageNum)
    }
    private fun js(script: String): String {
        val result = AtomicReference<String>()
        val latch = java.util.concurrent.CountDownLatch(1)
        scenario!!.onActivity { activity ->
            fun find(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val web = find(activity.window.decorView)
            if (web == null) { result.set("null"); latch.countDown() }
            else web.evaluateJavascript(script) { result.set(it); latch.countDown() }
        }
        assertTrue("WebView result timed out", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return result.get()
    }
    private fun awaitReaderScript() {
        var last = "not queried"
        try { ui.waitUntil(10000) {
            last = js("typeof window.troopPosition")
            last.contains("function") && ui.onAllNodes(hasTestTag("reader-content") and
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Página lista")).fetchSemanticsNodes().isNotEmpty()
        } }
        catch (e: Exception) {
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "reader-test-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            throw AssertionError("Reader script unavailable: $last; DOM=" + js("document.body.innerText.substring(0,180)"), e)
        }
    }
    @Test fun epubAnchorSurvivesReopenAndChangingTypography() {
        val a = repo.active()!!; val store = repo.store(a.key)
        // Nested containers reproduce books whose wrapper stays visible while paragraphs scroll.
        val file = File(store.chapterDir(100), "1.html")
        file.writeText(file.readText().replace("<div>", "<div><section><div>").replace("</div>", "</div></section></div>"))
        launch(); downloads(); openSeries(1)
        awaitReaderScript()
        js("window.troopRestore('id(\"p-8\")')")
        ui.waitUntil(6000) { js("window.troopPosition()").contains("p-8") }
        revealControls(); ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Sepia").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Sepia").performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick()
        awaitReaderScript()
        ui.waitUntil(6000) { js("window.troopPosition()").contains("p-8") }
        revealControls(); ui.onNodeWithContentDescription("Volver").performClick()
        ui.waitUntil(5000) { store.progress(100)?.bookScrollId?.contains("p-8") == true }
        openSeries(1)
        awaitReaderScript()
        ui.waitUntil(6000) { js("window.troopPosition()").contains("p-8") }
        assertTrue(js("window.scrollY").toDouble() > 0)
    }
    @Test fun deletingOfflinePayloadKeepsReadingQueue() {
        val a = repo.active()!!; val store = repo.store(a.key)
        store.record(100, 2, "id(\"p-4\")")
        launch(); downloads()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-1")); ui.onAllNodesWithText("Eliminar del móvil")[0].performClick()
        ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(10000) { !store.get().chapters.getValue(100).ready }
        assertFalse(store.chapterDir(100).exists())
        val reopened = Store(store.root)
        assertEquals(2, reopened.get().pending.getValue(100).local.pageNum)
        assertEquals("id(\"p-4\")", reopened.get().pending.getValue(100).local.bookScrollId)
        ui.onNodeWithText("Progreso").performClick()
        ui.onNodeWithText("El jardín de las mareas").assertExists()
    }
    @Test fun incompleteBookShowsReasonAndCanBeRetriedInsideTheWork() {
        val account=repo.active()!!; val store=repo.store(account.key)
        store.record(100,2,"id(\"p-4\")")
        val pending=store.get().pending.getValue(100)
        val message="No se pudo guardar un recurso del libro. Reintenta la descarga."
        store.update { state -> state.copy(chapters=state.chapters + (100 to state.chapters.getValue(100).copy(
            ready=false,downloadedPages=1,state=message,downloadRequested=false,downloadRequestId="paused"))) }
        val original=File(store.chapterDir(100),"0.html").readBytes()
        launch(); downloads()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-1"))
        ui.onAllNodesWithText("Ver libros")[0].performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("download-status-100"))
        ui.onNodeWithText(message).assertIsDisplayed()
        ui.onNodeWithText("Reintentar descarga").performScrollTo().performClick()
        ui.waitUntil(10000) { store.get().chapters.getValue(100).downloadRequestId != "paused" }
        assertEquals(pending,store.get().pending.getValue(100))
        assertArrayEquals(original,File(store.chapterDir(100),"0.html").readBytes())
    }

    @Test fun realAndroidDownloadsBothReadersThenSyncsAfterPayloadDeletion() = runBlocking {
        MockWebServer().use { server ->
            val remote = AtomicReference(Progress(1, 9, 8, 700, 0, null, "original"))
            val chapter = Chapter(700, 8, titleName="Fixture EPUB", pages=2, format=3, lastModifiedUtc="edition1")
            val comic = Chapter(701, 8, titleName="Fixture comic", pages=2, format=1, lastModifiedUtc="edition1")
            val png = repo.store(repo.active()!!.key).coverFile(1).readBytes()
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/api/Download/chapter-size") -> MockResponse().setBody("1000")
                        path == "/api/Series/chapter?chapterId=700" -> MockResponse().setBody(codec.encodeToString(chapter))
                        path == "/api/Series/chapter?chapterId=701" -> MockResponse().setBody(codec.encodeToString(comic))
                        path == "/api/Book/700/chapters" -> MockResponse().setBody("[{\"title\":\"Part 1\",\"page\":0}]")
                        path.startsWith("/api/Book/700/book-page") -> MockResponse().setBody("<div><p id='para'>Offline book</p><img src='/api/Book/700/book-resources?file=cover.png'></div>")
                        path.startsWith("/api/Book/700/book-resources") || path.startsWith("/api/Reader/image") -> MockResponse().setBody(okio.Buffer().write(png)).setHeader("Content-Type", "image/png")
                        path == "/api/Reader/get-progress?chapterId=700" -> MockResponse().setBody(codec.encodeToString(remote.get()))
                        path == "/api/Reader/get-progress?chapterId=701" -> MockResponse().setBody(codec.encodeToString(Progress(1,9,8,701)))
                        path == "/api/Reader/progress" && request.method == "POST" -> {
                            remote.set(codec.decodeFromString<Progress>(request.body.readUtf8()).copy(lastModifiedUtc="confirmed")); MockResponse().setBody("true")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val account = Account(server.url("/").toString().trimEnd('/'), 888, "Test fixture", "fixture-token", roles=listOf("Download"))
            repo.vault.save(account)
            val store = repo.store(account.key)
            store.update { LocalState(chapters=mapOf(700 to SavedChapter(chapter,Series(9,"EPUB",1,3),true),701 to SavedChapter(comic,Series(9,"Comic",1,1),false)),progress=mapOf(700 to remote.get())) }
            for (id in listOf(700,701)) {
                val worker = TestListenableWorkerBuilder<DownloadWorker>(context, inputData=workDataOf("account" to account.key,"chapter" to id)).build()
                worker.doWork()
                assertTrue(store.get().chapters.getValue(id).state, store.get().chapters.getValue(id).ready)
                assertEquals(2, store.get().chapters.getValue(id).downloadedPages)
            }
            assertTrue(File(store.chapterDir(700),"0.html").readText().contains("resources/"))
            assertTrue(File(store.chapterDir(701),"1.img").length()>0)
            // Offline phase: read and delete payload; neither operation invokes the server.
            val before = server.requestCount
            store.record(700,1,"id(\"para\")")
            Jobs.delete(context,account.key,700)
            assertEquals(before,server.requestCount)
            assertTrue(Store(store.root).get().pending.containsKey(700))
            repo.sync(account.key, includeBookmarks = false)
            assertFalse(store.get().pending.containsKey(700))
            assertEquals(1,remote.get().pageNum)
            assertEquals("id(\"para\")",remote.get().bookScrollId)
            assertFalse(store.chapterDir(700).exists())
            assertEquals("Todo sincronizado",store.get().syncMessage)
        }
    }

    @Test fun offlineLibraryOpensDownloadedBookWithoutRemoteCatalogue() {
        launch()
        ui.onNodeWithText("Demostración · datos de ejemplo").assertExists()
        ui.onNodeWithText("Horizonte de papel").performClick()
        ui.onNodeWithTag("reader-surface").assertExists()
        awaitReaderScript()
        ui.waitUntil(10000) { js("document.images[0].naturalWidth") == "600" }
    }
    @Test fun searchEmptyStateExplainsNoMatchesInsteadOfConnectionFailure() {
        launch()
        ui.onNodeWithText("Buscar en la biblioteca").performTextInput("un-titulo-que-no-existe")
        ui.onNodeWithText("Sin resultados").assertExists()
    }
    @Test fun missingOfflinePageDoesNotCrashOrEraseProgress() {
        val store=repo.store(repo.active()!!.key)
        File(store.chapterDir(100),"1.html").delete()
        launch();downloads();openSeries(1)
        ui.onNodeWithText("No se encuentra esta página").assertExists()
        ui.onNodeWithText("Volver a Descargas").performClick()
        assertEquals(1,store.progress(100)!!.pageNum)
    }
    @Test fun epubWithoutIdsRestoresItsXPathAfterRecreation() {
        val store=repo.store(repo.active()!!.key);val file=File(store.chapterDir(100),"1.html")
        file.writeText(file.readText().replace(Regex(" id='[^']*'"),""))
        launch();downloads();openSeries(1);awaitReaderScript()
        js("window.troopRestore('//body/p[9]')")
        ui.waitUntil(8000) { js("window.troopPosition()").contains("p[9]") }
        // Save through the same action used when changing typography, then rotate/recreate.
        revealControls(); ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Listo").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Listo").performClick()
        scenario!!.recreate();awaitReaderScript()
        ui.waitUntil(8000) { js("window.troopPosition()").contains("p[9]") }
    }
    @Test fun pdfResumesCompletedPagesAndRejectsHtmlAsImage()=runBlocking {
        MockWebServer().use { server ->
            val demoStore=repo.store(repo.active()!!.key);val png=File(demoStore.chapterDir(200),"0.img").readBytes()
            server.start()
            val account=Account(server.url("/").toString().trimEnd('/'),889,"Fixture","test",roles=listOf("Download"))
            repo.vault.save(account);val store=repo.store(account.key)
            val chapter=Chapter(900,9,titleName="PDF de prueba",pages=3,format=4,lastModifiedUtc="one")
            store.update { LocalState(chapters=mapOf(900 to SavedChapter(chapter,Series(9,"PDF",1,4),false))) }
            val dir=store.chapterDir(900).apply { mkdirs() };File(dir,"0.img").writeBytes(png)
            var invalid=true
            val pages=java.util.Collections.synchronizedList(mutableListOf<Int>())
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse=when {
                    r.path!!.startsWith("/api/Download/chapter-size") -> MockResponse().setBody("1234")
                    r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(chapter))
                    r.path!!.startsWith("/api/Reader/image") -> {
                        val page=r.requestUrl!!.queryParameter("page")!!.toInt();pages.add(page)
                        assertEquals("true",r.requestUrl!!.queryParameter("extractPdf"))
                        if(invalid) MockResponse().setBody("<html>Proxy error</html>") else MockResponse().setBody(okio.Buffer().write(png))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            suspend fun download()=TestListenableWorkerBuilder<DownloadWorker>(context,inputData=workDataOf("account" to account.key,"chapter" to 900)).build().doWork()
            download()
            assertFalse(store.get().chapters.getValue(900).ready);assertFalse(File(dir,"1.img").exists())
            invalid=false;download()
            assertTrue(store.get().chapters.getValue(900).ready)
            assertEquals(listOf(1,1,2),pages.toList()) // Completed page 0 was reused, invalid page 1 was fetched again.
        }
    }
    @Test fun deniedDownloadPermissionNeverRequestsPageContent()=runBlocking {
        MockWebServer().use { server ->
            server.start();val account=Account(server.url("/").toString().trimEnd('/'),890,"Fixture","test",roles=listOf("Download"))
            repo.vault.save(account);val store=repo.store(account.key)
            store.update { LocalState(chapters=mapOf(901 to SavedChapter(Chapter(901,pages=1),Series(1,"Denied"),false))) }
            server.enqueue(MockResponse().setResponseCode(403))
            TestListenableWorkerBuilder<DownloadWorker>(context,inputData=workDataOf("account" to account.key,"chapter" to 901)).build().doWork()
            assertEquals(1,server.requestCount);assertFalse(store.get().chapters.getValue(901).ready)
            assertFalse(store.chapterDir(901).exists())
        }
    }

    @Test fun changingWifiPolicyReplacesExistingQueueWithoutLosingPartialPagesOrResumingPaused() = runBlocking {
        val a=repo.active()!!; val store=repo.store(a.key); val manager=WorkManager.getInstance(context)
        store.update { s -> s.copy(chapters=s.chapters.mapValues { (id,v) ->
            if(id==100) v.copy(ready=false,state="Esperando Wi‑Fi",downloadRequested=true)
            else if(id==200) v.copy(ready=false,state="Descarga pausada",downloadRequested=false) else v }) }
        val bytes=File(store.chapterDir(100),"0.html").readBytes()
        repo.transferLock.lock()
        try {
            Jobs.download(context,a.key,100)
            val old=manager.getWorkInfosForUniqueWork(Jobs.downloadTag(a.key,100)).get().last { !it.state.isFinished }
            assertEquals(androidx.work.NetworkType.UNMETERED,old.constraints.requiredNetworkType)
            Jobs.setWifiOnly(context,a.key,false)
            val new=manager.getWorkInfosForUniqueWork(Jobs.downloadTag(a.key,100)).get().last { !it.state.isFinished }
            assertNotEquals(old.id,new.id)
            assertEquals(androidx.work.NetworkType.CONNECTED,new.constraints.requiredNetworkType)
            assertFalse(store.get().settings.wifiOnly)
            assertFalse(store.get().chapters.getValue(200).downloadRequested!!)
            assertTrue(manager.getWorkInfosForUniqueWork(Jobs.downloadTag(a.key,200)).get().isEmpty())
            assertArrayEquals(bytes,File(store.chapterDir(100),"0.html").readBytes())
            Jobs.setWifiOnly(context,a.key,true)
            val again=manager.getWorkInfosForUniqueWork(Jobs.downloadTag(a.key,100)).get().last { !it.state.isFinished }
            assertEquals(androidx.work.NetworkType.UNMETERED,again.constraints.requiredNetworkType)
            Jobs.remove(context,a.key,setOf(100))
        } finally { repo.transferLock.unlock() }
    }
    @Test fun staleWorkerCannotReviveAReplacedOrRemovedDownload() = runBlocking {
        val a=repo.active()!!;val store=repo.store(a.key)
        store.update { s -> s.copy(chapters=s.chapters.mapValues { (id,v) ->
            if(id==100)v.copy(ready=false,downloadRequested=true,downloadRequestId="newer-request",state="En cola") else v }) }
        TestListenableWorkerBuilder<DownloadWorker>(context,inputData=workDataOf("account" to a.key,"chapter" to 100)).build().doWork()
        assertEquals("En cola",store.get().chapters.getValue(100).state)
        assertFalse(store.get().chapters.getValue(100).ready)
    }
    @Test fun queueCanBeSelectedAndClearedWithoutDeletingCompletedBooksOrProgress() {
        val a=repo.active()!!;val store=repo.store(a.key)
        store.record(100,2,"id(\"p-4\")")
        store.addBookmark(100,1,"id(\"p-3\")","Conservar")
        val extra=store.get().chapters.getValue(200).copy(chapter=Chapter(201,pages=2),ready=false,state="Error del servidor (HTTP 400)")
        store.update { s -> s.copy(chapters=s.chapters.mapValues { (id,v) ->
            if(id==100)v.copy(ready=false,state="Esperando Wi‑Fi") else v } + (201 to extra)) }
        launch();downloads()
        ui.onNodeWithText("Seleccionar toda la cola").performClick()
        ui.onNodeWithText("Quitar (2)").assertExists().performClick()
        ui.onNodeWithText("Cancelar").performClick()
        assertTrue(store.chapterDir(100).exists())
        ui.onNodeWithText("Vaciar cola").performClick()
        ui.onNodeWithText("Quitar de la cola").performClick()
        ui.waitUntil(10000) { store.get().chapters.values.none { it.inQueue } }
        assertFalse(store.chapterDir(100).exists())
        assertTrue(store.chapterDir(200).exists());assertTrue(store.get().chapters.getValue(200).ready)
        assertTrue(store.get().pending.containsKey(100));assertTrue(store.get().bookmarks.any { it.title=="Conservar" })
    }
    @Test fun pausingAndDeletingOneBookDoesNotWaitForOtherTransfers()=runBlocking {
        val account=repo.active()!!;val store=repo.store(account.key)
        store.update { s -> s.copy(chapters=s.chapters.mapValues { (id,c) -> if(id==100)c.copy(ready=false,state="En cola") else c }) }
        repo.transferLock.lock()
        try {
            kotlinx.coroutines.withTimeout(5000) { Jobs.pause(context,account.key,100) }
            assertTrue(File(store.chapterDir(100),"0.html").exists())
            assertTrue(store.get().chapters.getValue(100).state.contains("pausada"))
            kotlinx.coroutines.withTimeout(5000) { Jobs.delete(context,account.key,100) }
            assertFalse(store.chapterDir(100).exists())
            assertTrue(store.chapterDir(200).exists())
        } finally { repo.transferLock.unlock() }
    }
    @Test fun readerOptionsRemainUsableInLandscapeAfterRecreation() {
        launch();downloads();openSeries(1);awaitReaderScript()
        revealControls(); ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        ui.onNodeWithText("Márgenes").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Listo").assertIsDisplayed().performClick()
        awaitReaderScript()
        assertTrue(js("document.body.innerText").contains("El viaje"))
        scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT }
    }
    @Test fun epubBookmarksCaptureExactAnchorAndSurviveRecreationAndDeletePayload() {
        val store=repo.store(repo.active()!!.key)
        launch();downloads();openSeries(1);awaitReaderScript()
        js("window.troopRestore('id(\"p-8\")')")
        ui.waitUntil(6000) { js("window.troopPosition()").contains("p-8") }
        revealControls(); ui.onNodeWithContentDescription("Marcadores").performClick()
        ui.onNodeWithText("Marcar esta posición").performClick()
        ui.onNodeWithText("Nombre del marcador").performTextReplacement("Mi pasaje")
        ui.onNodeWithText("Guardar").performClick()
        ui.waitUntil(6000) { store.get().bookmarks.any { it.title=="Mi pasaje" } }
        val marker=store.get().bookmarks.first { it.title=="Mi pasaje" }
        assertTrue(marker.scroll!!.contains("p-8"));assertTrue(marker.dirty)
        scenario!!.recreate();ui.onNodeWithText("Mi pasaje").assertExists()
        ui.onNodeWithText("Ir").performClick();awaitReaderScript()
        ui.waitUntil(6000) { js("window.troopPosition()").contains("p-8") }
        revealControls(); ui.onNodeWithContentDescription("Volver").performClick()
        // EPUB exit waits for WebView to capture the current paragraph asynchronously.
        ui.waitUntil(5000) { ui.onAllNodesWithTag("download-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-1")); ui.onAllNodesWithText("Eliminar del móvil")[0].performClick();ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(6000) { !store.chapterDir(100).exists() }
        assertEquals(marker.id,Store(store.root).get().bookmarks.single().id)
    }
    @Test fun longBookmarkListRemainsAccessibleInLandscape() {
        val store=repo.store(repo.active()!!.key)
        val edition=store.get().chapters.getValue(100).chapter
        store.update { it.copy(bookmarks=(1..30).map { n ->
            Bookmark("marker-$n",100,"Pasaje $n",0,"id(\"p-$n\")",n.toLong(),edition)
        }) }
        launch();downloads();openSeries(1);awaitReaderScript()
        revealControls(); ui.onNodeWithContentDescription("Marcadores").performClick()
        scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        ui.onNodeWithTag("bookmark-list").performScrollToNode(hasText("Pasaje 30"))
        ui.onNodeWithText("Pasaje 30").assertIsDisplayed()
        ui.onNodeWithContentDescription("Eliminar marcador Pasaje 30").performClick()
        ui.waitUntil(6000) { store.get().bookmarks.first { it.id=="marker-30" }.deleted }
        ui.onNodeWithTag("bookmark-list").performScrollToIndex(0)
        ui.onNodeWithTag("bookmark-list").performScrollToNode(hasText("Marcar esta posición"))
        ui.onNodeWithText("Marcar esta posición").assertIsDisplayed()
    }
    @Test fun markerConflictCanKeepBothWithoutReplacingOnlineBaseline() {
        val store=repo.store(repo.active()!!.key);val edition=store.get().chapters.getValue(100).chapter
        store.update { it.copy(bookmarks=listOf(
            Bookmark("local",100,"El mismo punto",1,"old",10,edition,conflict=true),
            Bookmark("remote",100,"El mismo punto",1,"new",0,edition,remote=RemoteBookmark(5,1,"El mismo punto","new"),dirty=false))) }
        launch();ui.onNodeWithText("Progreso").performClick()
        ui.onNodeWithText("Conservar ambos").performScrollTo().performClick()
        ui.waitUntil(6000) { store.get().bookmarks.none { it.conflict } }
        assertEquals(2,store.get().bookmarks.size)
        assertEquals("new",store.get().bookmarks.first { it.id=="remote" }.scroll)
        assertTrue(store.get().bookmarks.any { it.dirty && it.scroll=="old" && it.title.contains("móvil") })
    }
    @Test fun epubFootnoteLinkWithHashNavigatesToTheCorrectAnchor() {
        val store=repo.store(repo.active()!!.key)
        val file=File(store.chapterDir(100),"1.html")
        file.writeText(file.readText().replace("<div>","<div><a id='test-link' kavita-page='2' kavita-part='#p-6'>Nota</a>"))
        launch();downloads();openSeries(1);awaitReaderScript()
        js("document.getElementById('test-link').click()")
        ui.waitUntil(5000) { store.progress(100)?.pageNum == 2 };awaitReaderScript()
        ui.waitUntil(6000) { js("window.troopPosition()").contains("p-6") }
    }
    @Test fun selectingChaptersQueuesOnlyTheChosenBatch() {
        MockWebServer().use { server ->
            val png=File(repo.store(repo.active()!!.key).chapterDir(200),"0.img").readBytes()
            val chapters=listOf(Chapter(1200,12,titleName="Capítulo A",pages=1,format=1),Chapter(1201,12,titleName="Capítulo B",pages=1,format=1))
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse = when {
                    r.path!!.startsWith("/api/Series/volumes") -> MockResponse().setBody(codec.encodeToString(listOf(Volume(12,"1",listOf(chapters[0])),Volume(13,"2",listOf(chapters[1])))))
                    r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(chapters.first { it.id==r.requestUrl!!.queryParameter("chapterId")!!.toInt() }))
                    r.path!!.startsWith("/api/Reader/get-progress") -> MockResponse().setBody(codec.encodeToString(Progress(chapterId=r.requestUrl!!.queryParameter("chapterId")!!.toInt())))
                    r.path!!.startsWith("/api/Reader/chapter-bookmarks") -> MockResponse().setBody("[]")
                    r.path!!.startsWith("/api/Download/chapter-size") -> MockResponse().setBody("1000")
                    r.path!!.startsWith("/api/Reader/image") -> MockResponse().setBody(okio.Buffer().write(png))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val a=Account(server.url("/").toString().trimEnd('/'),991,"Batch fixture","fixture",roles=listOf("Download"))
            repo.vault.save(a);val store=repo.store(a.key)
            store.update { LocalState(series=listOf(Series(12,"Serie para descargar",1,1)),settings=ReadingSettings(wifiOnly=false)) }
            launch();ui.onNodeWithText("Serie para descargar").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Tomo 2").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Seleccionar todos los tomos").performClick()
            ui.onNodeWithText("Descargar (2)").performClick()
            ui.onNodeWithText("Añadir a descargas").performClick()
            ui.waitUntil(10000) { store.get().chapters.size==2 }
            assertEquals(setOf(1200,1201),store.get().chapters.keys)
            ui.waitUntil(10000) { ui.onAllNodesWithText("2 tomos preparados · 2 archivos en cola").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun androidOptInSendsLocalProgressAndReplacesOnlyTheMatchingMarker()=runBlocking {
        MockWebServer().use { server ->
            val chapter=Chapter(1500,15,pages=10,format=3,lastModifiedUtc="one")
            val progress=AtomicReference(Progress(1,15,15,1500,9,"web"))
            val remote=java.util.concurrent.CopyOnWriteArrayList<RemoteBookmark>()
            remote+=RemoteBookmark(21,2,"Mi marca","web");remote+=RemoteBookmark(22,2,"Solo web","keep")
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse {
                    val path=r.requestUrl!!.encodedPath
                    return when {
                        path=="/api/Series/chapter" -> MockResponse().setBody(codec.encodeToString(chapter))
                        path=="/api/Reader/get-progress" -> MockResponse().setBody(codec.encodeToString(progress.get()))
                        path=="/api/Reader/progress" -> {
                            progress.set(codec.decodeFromString<Progress>(r.body.readUtf8()));MockResponse().setBody("true")
                        }
                        path=="/api/Reader/ptoc" && r.method=="DELETE" -> {
                            remote.removeAll { it.page==r.requestUrl!!.queryParameter("pageNum")!!.toInt() && it.title==r.requestUrl!!.queryParameter("title") }
                            MockResponse().setBody("true")
                        }
                        path=="/api/Reader/ptoc" -> MockResponse().setBody(kotlinx.serialization.json.buildJsonArray {
                            for(b in remote) add(kotlinx.serialization.json.buildJsonObject {
                                put("id",kotlinx.serialization.json.JsonPrimitive(b.id));put("pageNumber",kotlinx.serialization.json.JsonPrimitive(b.page))
                                put("title",kotlinx.serialization.json.JsonPrimitive(b.title));put("bookScrollId",kotlinx.serialization.json.JsonPrimitive(b.scroll))
                            })
                        }.toString())
                        path=="/api/Reader/create-ptoc" -> {
                            val j=codec.parseToJsonElement(r.body.readUtf8()) as kotlinx.serialization.json.JsonObject
                            fun value(k:String)=(j.getValue(k) as kotlinx.serialization.json.JsonPrimitive).content
                            remote+=RemoteBookmark(23,value("pageNumber").toInt(),value("title"),value("bookScrollId"));MockResponse().setBody("true")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start();val a=Account(server.url("/").toString().trimEnd('/'),1500,"Priority fixture","fixture",roles=listOf("Download"))
            repo.vault.save(a);val store=repo.store(a.key)
            store.update { LocalState(chapters=mapOf(1500 to SavedChapter(chapter,Series(15,"Fixture EPUB",1,3),true)),
                progress=mapOf(1500 to Progress(1,15,15,1500,0))) }
            store.record(1500,3,"local-read");store.addBookmark(1500,2,"local-marker","Mi marca")
            repo.sync(a.key)
            assertNotNull(store.get().pending[1500]!!.conflict);assertTrue(store.get().bookmarks.any { it.conflict })
            assertEquals(9,progress.get().pageNum);assertEquals("web",remote.first { it.id==21 }.scroll)
            store.update { it.copy(settings=it.settings.copy(preferLocalChanges=true)) }
            repo.sync(a.key)
            assertEquals(3,progress.get().pageNum);assertEquals("local-read",progress.get().bookScrollId)
            assertEquals("local-marker",remote.first { it.title=="Mi marca" }.scroll)
            assertEquals("keep",remote.first { it.id==22 }.scroll)
            assertTrue(store.get().pending.isEmpty());assertFalse(store.get().bookmarks.any { it.dirty || it.conflict })
            assertEquals("Todo sincronizado",store.get().syncMessage)
        }
    }
    @Test fun androidSyncUploadsOfflineEpubMarkerAndImportsWebMarkerAfterDeletingPayload()=runBlocking {
        MockWebServer().use { server ->
            val chapter=Chapter(1400,14,pages=3,format=3,lastModifiedUtc="one")
            val remote=java.util.concurrent.CopyOnWriteArrayList<RemoteBookmark>()
            remote+=RemoteBookmark(21,0,"Desde la web","id(\"web\")")
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse = when {
                    r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(chapter))
                    r.path!!.startsWith("/api/Reader/get-progress") -> MockResponse().setBody(codec.encodeToString(Progress(1,14,14,1400)))
                    r.path!!.startsWith("/api/Reader/ptoc") -> MockResponse().setBody(kotlinx.serialization.json.buildJsonArray {
                        for(b in remote) add(kotlinx.serialization.json.buildJsonObject {
                            put("id",kotlinx.serialization.json.JsonPrimitive(b.id));put("chapterId",kotlinx.serialization.json.JsonPrimitive(1400))
                            put("pageNumber",kotlinx.serialization.json.JsonPrimitive(b.page));put("title",kotlinx.serialization.json.JsonPrimitive(b.title))
                            put("bookScrollId",kotlinx.serialization.json.JsonPrimitive(b.scroll))
                        })
                    }.toString())
                    r.path=="/api/Reader/create-ptoc" -> {
                        val payload=codec.parseToJsonElement(r.body.readUtf8()) as kotlinx.serialization.json.JsonObject
                        fun value(k:String)=(payload.getValue(k) as kotlinx.serialization.json.JsonPrimitive).content
                        assertEquals("id(\"offline\")",value("bookScrollId"))
                        remote+=RemoteBookmark(22,value("pageNumber").toInt(),value("title"),value("bookScrollId"))
                        MockResponse().setBody("true")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start();val a=Account(server.url("/").toString().trimEnd('/'),1400,"Marker fixture","fixture",roles=listOf("Download"))
            repo.vault.save(a);val store=repo.store(a.key)
            store.update { LocalState(chapters=mapOf(1400 to SavedChapter(chapter,Series(14,"Fixture EPUB",1,3),true))) }
            store.addBookmark(1400,2,"id(\"offline\")","Desde el móvil")
            store.deletePayload(1400)
            repo.sync(a.key)
            assertEquals(2,remote.size);assertEquals(2,store.get().bookmarks.size)
            assertTrue(store.get().bookmarks.none { it.dirty });assertTrue(store.get().bookmarkIssues.isEmpty())
            assertEquals("Todo sincronizado",store.get().syncMessage)
            assertFalse(store.chapterDir(1400).exists())
        }
    }

    private fun serialManga(missingMiddle: Boolean = false): Store {
        val store=repo.store(repo.active()!!.key)
        val original=store.get().chapters.getValue(200)
        val books=(0..2).map { n ->
            val id=200+n
            val chapter=original.chapter.copy(id=id,volumeId=20+n,pages=2,titleName="",displayTitle="Tomo ${n+1}")
            if(n>0) {
                val dir=store.chapterDir(id).apply { mkdirs() }
                for(page in 0..1) File(store.chapterDir(200),"$page.img").copyTo(File(dir,"$page.img"),overwrite=true)
            }
            original.copy(chapter=chapter,ready= !(missingMiddle && n==1),state=if(missingMiddle && n==1) "Eliminado del dispositivo" else "Disponible sin conexión",downloadedPages=2)
        }
        store.update { s -> s.copy(chapters=s.chapters+books.associateBy { it.chapter.id },
            progress=s.progress.filterKeys { it !in 200..202 },pending=s.pending.filterKeys { it !in 200..202 },
            catalog=s.catalog+(2 to books.mapIndexed { index,b -> Volume(b.chapter.volumeId,"${index+1}",listOf(b.chapter)) }),
            settings=s.settings.copy(imageMode="fit",rtl=false)) }
        return store
    }
    private fun swipeForward(rtl: Boolean = false) {
        awaitReaderScript()
        ui.onNodeWithTag("reader-content").performTouchInput {
            val y=centerY
            swipe(androidx.compose.ui.geometry.Offset(width * if(rtl) .2f else .8f,y),
                androidx.compose.ui.geometry.Offset(width * if(rtl) .8f else .2f,y),durationMillis=350)
        }
        ui.waitForIdle()
    }
    @Test fun seriesTapEntersImmersiveReaderAndGesturesContinueToNextVolumeMarkingRead() {
        val store=serialManga()
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="swipe")) }
        launch()
        ui.onNodeWithText("Horizonte de papel").performClick()
        awaitReaderScript()
        ui.onNodeWithContentDescription("Opciones de lectura").assertDoesNotExist()
        assertTrue(js("innerHeight").toDouble()>600)
        swipeForward()
        ui.waitUntil(6000) { store.progress(200)?.pageNum==1 }
        swipeForward()
        ui.waitUntil(6000) { store.progress(200)?.pageNum==2 }
        awaitReaderScript();revealControls()
        ui.onNodeWithText("Tomo 2").assertIsDisplayed()
        assertEquals(2,store.get().pending.getValue(200).local.pageNum)
        ui.onNodeWithText("1 / 2").assertIsDisplayed()
        val height=js("innerHeight")
        ui.mainClock.advanceTimeBy(6000);ui.waitForIdle()
        ui.onNodeWithContentDescription("Opciones de lectura").assertDoesNotExist()
        assertEquals(height,js("innerHeight"))
        scenario!!.recreate();awaitReaderScript();revealControls()
        ui.onNodeWithText("Tomo 2").assertIsDisplayed()
        assertEquals(2,store.progress(200)!!.pageNum)
    }
    @Test fun completedVolumeStopsAtMissingDownloadInsteadOfSkippingToThird() {
        val store=serialManga(true)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="swipe")) }
        store.record(200,1)
        launch();downloads();openSeries(2)
        swipeForward()
        ui.onNodeWithText("Lectura completada").assertIsDisplayed()
        ui.onNodeWithText("Tomo 2 no está descargado",substring=true).assertIsDisplayed()
        assertEquals(2,store.progress(200)!!.pageNum)
        assertNull(store.progress(202))
        ui.onNodeWithText("Quedarme aquí").performClick()
        scenario!!.recreate();awaitReaderScript()
        assertEquals(2,store.progress(200)!!.pageNum)
    }
    @Test fun rightToLeftSwipeAndDisabledAutoAdvanceRespectReaderPreference() {
        val store=serialManga()
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="swipe")) }
        store.update { it.copy(settings=it.settings.copy(rtl=true,autoAdvance=false)) }
        store.record(200,1)
        launch();downloads();openSeries(2)
        swipeForward(true)
        ui.onNodeWithText("Continuar lectura").assertIsDisplayed()
        assertEquals(2,store.progress(200)!!.pageNum)
        ui.onNodeWithText("Continuar lectura").performClick()
        awaitReaderScript();revealControls()
        ui.onNodeWithText("Tomo 2").assertIsDisplayed()
    }
    @Test fun offlineWorksGroupVolumesAndBulkDeleteSelectionWithoutLosingProgressOrBookmarks() {
        val store=serialManga()
        store.record(200,2);store.addBookmark(200,0,null,"Guardado")
        launch();downloads()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-2"))
        ui.onNodeWithText("Horizonte de papel").performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasContentDescription("Seleccionar Tomo 1"))
        ui.onNodeWithText("Leído").assertIsDisplayed()
        ui.onNodeWithContentDescription("Seleccionar Tomo 1").performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasContentDescription("Seleccionar Tomo 3"))
        ui.onNodeWithContentDescription("Seleccionar Tomo 3").performClick()
        ui.onNodeWithTag("download-list").performScrollToIndex(0)
        ui.onNodeWithText("Eliminar selección (2)").performClick()
        ui.onNodeWithText("Cancelar").performClick()
        assertTrue(store.chapterDir(200).exists())
        ui.onNodeWithText("Eliminar selección (2)").performClick()
        ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(10000) { !store.chapterDir(200).exists() && !store.chapterDir(202).exists() }
        assertTrue(store.chapterDir(201).exists());assertTrue(store.chapterDir(100).exists())
        assertEquals(2,store.progress(200)!!.pageNum)
        assertTrue(store.get().bookmarks.any { it.title=="Guardado" })
        assertTrue(store.get().pending.containsKey(200))
        ui.onNodeWithText("Eliminar toda la obra").performClick()
        ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(10000) { !store.chapterDir(201).exists() }
        assertTrue(store.chapterDir(100).exists())
        assertEquals(2,Store(store.root).progress(200)!!.pageNum)
    }

    @Test fun selectingSeveralOfflineWorksAndClearAllKeepTheirReadingState() {
        val store=repo.store(repo.active()!!.key)
        store.record(100,2,"id(\"p-4\")");store.record(200,3)
        store.addBookmark(100,1,"id(\"p-2\")","Conservar siempre")
        launch();downloads()
        for((id,title) in listOf(1 to "El jardín de las mareas",2 to "Horizonte de papel")) {
            ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-$id"))
            ui.onNodeWithContentDescription("Seleccionar obra $title").performClick()
        }
        ui.onNodeWithTag("download-list").performScrollToIndex(0)
        ui.onNodeWithText("Eliminar obras (2)").performClick()
        ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(10000) { !store.chapterDir(100).exists() && !store.chapterDir(200).exists() }
        assertTrue(store.chapterDir(300).exists());assertTrue(store.chapterDir(400).exists())
        assertEquals(2,store.get().pending.size)
        ui.onNodeWithText("Eliminar todas las descargas").performClick()
        ui.onNodeWithText("Cancelar").performClick()
        assertTrue(store.chapterDir(300).exists())
        ui.onNodeWithText("Eliminar todas las descargas").performClick()
        ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(10000) { store.get().chapters.values.none { it.ready || it.inQueue } }
        val restored=Store(store.root)
        assertEquals(2,restored.get().pending.size)
        assertEquals(1,restored.get().bookmarks.size)
        assertEquals(2,restored.progress(100)!!.pageNum)
    }

    private fun withReaderWeb(block: (android.webkit.WebView) -> Unit) {
        scenario!!.onActivity { activity ->
            fun find(v: android.view.View): android.webkit.WebView? {
                if(v is android.webkit.WebView) return v
                if(v is android.view.ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            block(requireNotNull(find(activity.window.decorView)))
        }
    }
    private fun zoomReader() {
        ui.waitForIdle(); awaitReaderScript()
        val expected=repo.store(repo.active()!!.key).progress(200)?.pageNum ?: 0
        ui.waitUntil(5000) { js("document.images[0] && document.images[0].getAttribute('src')") == "\"$expected.img\"" }
        ui.waitUntil(5000) { js("Array.from(document.images).every(i => i.complete && i.naturalWidth > 0 && i.getBoundingClientRect().height > 0)") == "true" }
        val painted=java.util.concurrent.CountDownLatch(1)
        withReaderWeb { it.postVisualStateCallback(1,object:android.webkit.WebView.VisualStateCallback() {
            override fun onComplete(requestId:Long) { painted.countDown() }
        }) }
        assertTrue(painted.await(5,java.util.concurrent.TimeUnit.SECONDS))
        // Page turns retain zoom. Do not multiply it again on every assertion.
        if(js("window.visualViewport.scale").toDouble() > 1.5) return
        ui.onNodeWithTag("reader-content").performTouchInput {
            down(0,androidx.compose.ui.geometry.Offset(width*.4f,centerY))
            down(1,androidx.compose.ui.geometry.Offset(width*.6f,centerY))
            for(i in 1..10) {
                updatePointerTo(0,androidx.compose.ui.geometry.Offset(width*(.4f-i*.025f),centerY))
                updatePointerTo(1,androidx.compose.ui.geometry.Offset(width*(.6f+i*.025f),centerY))
                move(20)
            }
            up(1);up(0)
        }
        try { ui.waitUntil(5000) { js("window.visualViewport.scale").toDouble() > 1.5 } }
        catch(e:Exception) { throw AssertionError("Zoom failed: " + js("JSON.stringify({scale:visualViewport.scale,w:innerWidth,h:innerHeight,ready:document.readyState,src:document.images[0].src})"),e) }
    }
    private fun tapEdge(right: Boolean) {
        awaitReaderScript()
        ui.onNodeWithTag("reader-content").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * if(right) .9f else .1f,centerY))
        }
        ui.waitForIdle()
    }

    private fun traceZoom(): Double {
        Thread.sleep(250) // Finish the finger's native pinch before sampling the turn.
        return js("""(() => {
            window.zoomTurnSamples = [];
            window.traceZoomTurn = true;
            function sample() {
                if (!window.traceZoomTurn) return;
                window.zoomTurnSamples.push(visualViewport.scale);
                requestAnimationFrame(sample);
            }
            sample(); return visualViewport.scale;
        })()""").toDouble()
    }
    private fun assertZoomUnchanged(expected: Double) {
        Thread.sleep(350) // Include delayed native zoom animation, not only DOM commit.
        val samples = js("window.traceZoomTurn = false; window.zoomTurnSamples").removeSurrounding("[","]")
            .split(',').filter { it.isNotBlank() }.map { it.toDouble() }
        assertTrue("No rendered frames recorded",samples.size >= 3)
        assertTrue("Scale changed during page turn: expected=$expected samples=$samples",
            samples.all { kotlin.math.abs(it-expected) < .04 })
        assertEquals(expected,js("visualViewport.scale").toDouble(),.04)
    }

    @Test fun zoomedPageTurnsKeepScaleOnEveryFrameAcrossDifferentImageSizes() {
        val store=repo.store(repo.active()!!.key)
        val output=File(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: context.getExternalFilesDir(null)!!.path,"zoom-turn").apply { mkdirs() }
        for(index in 0..1) {
            val fixture=android.graphics.Bitmap.createBitmap(if(index==0) 1600 else 2000,if(index==0) 2400 else 1200,android.graphics.Bitmap.Config.ARGB_8888)
            fixture.eraseColor(if(index==0) 0xffffc080.toInt() else 0xff80c0ff.toInt())
            val canvas=android.graphics.Canvas(fixture)
            val paint=android.graphics.Paint().apply { color=android.graphics.Color.WHITE;strokeWidth=8f }
            for(x in 100 until fixture.width step 200) canvas.drawLine(x.toFloat(),0f,x.toFloat(),fixture.height.toFloat(),paint)
            for(y in 100 until fixture.height step 200) canvas.drawLine(0f,y.toFloat(),fixture.width.toFloat(),y.toFloat(),paint)
            File(store.chapterDir(200),"$index.img").outputStream().use { fixture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            fixture.recycle()
        }
        store.update { it.copy(settings=it.settings.copy(pageTurnEffect="none",pageTurnMode="edges")) }
        launch();downloads();openSeries(2);zoomReader()
        val first=AtomicReference<android.webkit.WebView>()
        withReaderWeb { first.set(it) }
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(4) { turn ->
            val expected=traceZoom()
            assertTrue(expected>1.5)
            automation.takeScreenshot().also { shot ->
                File(output,"$turn-before.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
            }
            tapEdge(turn%2==0);awaitPageTurn(page=if(turn%2==0) 1 else 0)
            assertZoomUnchanged(expected)
            ui.onNodeWithTag("page-turn-hold").assertDoesNotExist()
            withReaderWeb { assertSame(first.get(),it) }
            automation.takeScreenshot().also { shot ->
                val pixel=shot.getPixel(shot.width/2,shot.height/2)
                assertTrue("No page painted",android.graphics.Color.red(pixel)+android.graphics.Color.blue(pixel)>250)
                File(output,"$turn-after.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };shot.recycle()
            }
        }
    }
    @Test fun pageTurnChoicePersistsAndEdgeTapsWorkWhileZoomedInBothDirections() {
        val store=repo.store(repo.active()!!.key)
        launch();downloads();openSeries(2);revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Toque en los bordes").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Arrastrando").performScrollTo().performClick()
        assertEquals("swipe",Store(store.root).get().settings.pageTurnMode)
        ui.onNodeWithText("Toque en los bordes").performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick()
        assertEquals("edges",Store(store.root).get().settings.pageTurnMode)
        zoomReader();tapEdge(true)
        ui.waitUntil(6000) { store.progress(200)?.pageNum==1 }
        zoomReader();tapEdge(false)
        ui.waitUntil(6000) { store.progress(200)?.pageNum==0 }
        scenario!!.recreate();awaitReaderScript();revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Toque en los bordes").performScrollTo().assertIsSelected()
    }
    @Test fun zoomedImagePansThenChangesPageFromBoundaryWithoutLeavingReader() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="swipe")) }
        launch();downloads();openSeries(2);zoomReader()
        fun boundary(right: Boolean) {
            // Reach the actual edge with fingers, never by scrollTo(100000), which
            // leaves native scrollX outside Chromium's visual viewport.
            var canPan=true
            repeat(8) {
                Thread.sleep(350) // Let native fling/pinch frames settle (outside Compose's clock).
                withReaderWeb { canPan=(it as ReaderWebView).canPanForPageTurn(if(right) 1 else -1) }
                if(!canPan) return
                val before=store.progress(200)?.pageNum
                swipeForward(!right)
                assertEquals("Panning inside the image must not turn a page",before,store.progress(200)?.pageNum)
            }
            error("Could not reach the image boundary using physical swipes")
        }
        boundary(false)
        var canPan=false
        ui.waitUntil(5000) { withReaderWeb { canPan=it.canScrollHorizontally(1) }; canPan }
        swipeForward() // This starts inside the image: pan, NOT a page turn.
        assertEquals(0,store.progress(200)!!.pageNum)
        assertTrue(js("window.visualViewport.scale").toDouble()>1.5)
        boundary(true)
        val beforeForward=traceZoom()
        swipeForward()
        ui.waitUntil(6000) { store.progress(200)?.pageNum==1 }
        assertZoomUnchanged(beforeForward)
        zoomReader();boundary(false)
        val beforeBackward=traceZoom()
        swipeForward(true) // Physical right swipe: previous in LTR.
        ui.waitUntil(6000) { store.progress(200)?.pageNum==0 }
        assertZoomUnchanged(beforeBackward)
    }
    @Test fun rightToLeftEdgeTapRespectsDoublePagesAndZoomAndSwipeDoesNotTurn() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="edges",rtl=true,imageMode="double")) }
        launch();downloads();openSeries(2);zoomReader()
        swipeForward(true)
        assertEquals(0,store.progress(200)!!.pageNum)
        val beforeForward=traceZoom()
        tapEdge(false)
        ui.waitUntil(6000) { store.progress(200)?.pageNum==2 }
        assertZoomUnchanged(beforeForward)
        zoomReader();tapEdge(true)
        ui.waitUntil(6000) { store.progress(200)?.pageNum==0 }
        assertEquals(beforeForward,js("visualViewport.scale").toDouble(),.04)
    }
    @Test fun pinchAndDoubleTapDoNotTurnPagesAndCentreStillShowsControls() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="edges")) }
        launch();downloads();openSeries(2);awaitReaderScript()
        ui.onNodeWithTag("reader-content").performTouchInput {
            down(0,androidx.compose.ui.geometry.Offset(width*.4f,centerY))
            down(1,androidx.compose.ui.geometry.Offset(width*.6f,centerY))
            for(i in 1..10) {
                updatePointerTo(0,androidx.compose.ui.geometry.Offset(width*(.4f-i*.025f),centerY))
                updatePointerTo(1,androidx.compose.ui.geometry.Offset(width*(.6f+i*.025f),centerY))
                move(20)
            }
            up(1);up(0)
        }
        ui.waitUntil(5000) { js("window.visualViewport.scale").toDouble()>1.2 }
        assertEquals(0,store.progress(200)!!.pageNum)
        ui.onNodeWithTag("reader-content").performTouchInput { doubleClick(androidx.compose.ui.geometry.Offset(width*.5f,centerY)) }
        ui.waitUntil(3000) { js("document.readyState")=="\"complete\"" }
        // Wait beyond single tap confirmation as well.
        Thread.sleep(500)
        assertEquals(0,store.progress(200)!!.pageNum)
        revealControls();ui.onNodeWithContentDescription("Opciones de lectura").assertIsDisplayed()
    }
    @Test fun edgeTapsNavigateEpubButVerticalScrollStillReadsText() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="edges")) }
        launch();downloads();openSeries(1);awaitReaderScript()
        ui.onNodeWithTag("reader-content").performTouchInput { swipeUp() }
        assertEquals(1,store.progress(100)!!.pageNum)
        ui.waitUntil(5000) { js("window.scrollY").toDouble()>0 }
        tapEdge(true);ui.waitUntil(6000) { store.progress(100)?.pageNum==2 }
        tapEdge(false);ui.waitUntil(6000) { store.progress(100)?.pageNum==1 }
    }
    private fun awaitPageTurn(id: Int = 200, page: Int) {
        ui.waitUntil(6000) { repo.store(repo.active()!!.key).progress(id)?.pageNum == page }
        ui.waitUntil(6000) { ui.onAllNodesWithTag("page-turn-effect").fetchSemanticsNodes().isEmpty() }
        awaitReaderScript()
    }
    @Test fun optionalPageCurlPersistsAndZoomedEdgeTapsNavigateWithNoExtraProgress() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="edges")) }
        launch();downloads();openSeries(2);revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Sin efecto").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Hoja",substring=false).performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick()
        assertEquals("curl",Store(store.root).get().settings.pageTurnEffect)
        zoomReader();val beforeForward=traceZoom();tapEdge(true);awaitPageTurn(page=1)
        assertZoomUnchanged(beforeForward)
        assertEquals(1,store.progress(200)!!.pageNum)
        ui.waitUntil(5000) { js("document.images[0].getAttribute('src')")=="\"1.img\"" }
        zoomReader();val beforeBackward=traceZoom();tapEdge(false);awaitPageTurn(page=0)
        assertZoomUnchanged(beforeBackward)
        assertEquals(0,store.progress(200)!!.pageNum)
        scenario!!.recreate();awaitReaderScript();revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Hoja",substring=false).performScrollTo().assertIsSelected()
        ui.onNodeWithText("Sin efecto").performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick()
        tapEdge(true);awaitPageTurn(page=1)
        assertEquals(1,store.progress(200)!!.pageNum)
        assertEquals("none",Store(store.root).get().settings.pageTurnEffect)
    }
    @Test fun pageCurlSwipeWithZoomStillPansAndRtlDoublePageKeepsOrder() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="swipe")) }
        store.update { it.copy(settings=it.settings.copy(pageTurnEffect="curl",rtl=true,imageMode="double")) }
        launch();downloads();openSeries(2);zoomReader()
        // Reach the native zoom boundary with real input. scrollTo() changes the
        // Android scroll offset, but not Chromium's visual viewport, and could
        // manufacture an unreachable position (100000px) in the previous test.
        var pans=0
        repeat(8) {
            Thread.sleep(300)
            var canPan=false
            withReaderWeb { canPan=(it as ReaderWebView).canPanForPageTurn(-1) }
            if(canPan) { swipeForward(true);pans++;assertEquals(0,store.progress(200)!!.pageNum) }
        }
        assertTrue("The zoomed image must pan before changing spread",pans>0)
        withReaderWeb { assertFalse((it as ReaderWebView).canPanForPageTurn(-1)) }
        swipeForward(true);awaitPageTurn(page=2)
        assertEquals(2,store.progress(200)!!.pageNum)
        ui.waitUntil(5000) { js("document.images.length")=="2" }
        // The next spread remains zoomed now: pan to its right edge first.
        repeat(8) {
            Thread.sleep(300)
            var canPan=false
            withReaderWeb { canPan=(it as ReaderWebView).canPanForPageTurn(1) }
            if(canPan) { swipeForward(false);assertEquals(2,store.progress(200)!!.pageNum) }
        }
        withReaderWeb { assertFalse((it as ReaderWebView).canPanForPageTurn(1)) }
        swipeForward(false);awaitPageTurn(page=0)
        assertEquals(0,store.progress(200)!!.pageNum)
    }
    @Test fun pageCurlEpubRetainsTextAndRecreationCannotCoverTheReader() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnEffect="curl",pageTurnMode="edges")) }
        launch();downloads();openSeries(1);awaitReaderScript()
        ui.onNodeWithTag("reader-content").performTouchInput { swipeUp() }
        ui.waitUntil(5000) { js("window.scrollY").toDouble()>0 }
        tapEdge(true);awaitPageTurn(100,2)
        assertEquals(2,store.progress(100)!!.pageNum)
        assertTrue(js("document.body.innerText.length").toInt()>100)
        scenario!!.recreate();awaitReaderScript()
        ui.onNodeWithTag("page-turn-effect").assertDoesNotExist()
        assertEquals(2,store.progress(100)!!.pageNum)
        tapEdge(false);awaitPageTurn(100,1)
        assertEquals(1,store.progress(100)!!.pageNum)
    }
    @Test fun curlDrawingPreservesPrintAndRevealsIncomingSheetInBothDirections() {
        // Actual Android Canvas, not a no-op JVM graphics mock.
        val bmp=android.graphics.Bitmap.createBitmap(200,300,android.graphics.Bitmap.Config.ARGB_8888)
        val c=android.graphics.Canvas(bmp);c.drawColor(android.graphics.Color.RED)
        c.drawRect(100f,0f,200f,300f,android.graphics.Paint().apply { color=android.graphics.Color.BLUE })
        launch()
        scenario!!.onActivity { activity ->
            val view=PageCurlView(activity)
            for(left in listOf(true,false)) {
                val f=PageTurnFrame(bmp,left,android.graphics.Color.WHITE)
                val result=android.graphics.Bitmap.createBitmap(200,300,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(result)
                canvas.drawColor(android.graphics.Color.GREEN);view.drawPage(canvas,f,200f,300f,0f)
                assertEquals(android.graphics.Color.RED,result.getPixel(10,150))
                assertEquals(android.graphics.Color.BLUE,result.getPixel(190,150))
                canvas.drawColor(android.graphics.Color.GREEN);view.drawPage(canvas,f,200f,300f,.5f)
                assertEquals(android.graphics.Color.GREEN,result.getPixel(if(left) 190 else 10,150))
                assertEquals(if(left) android.graphics.Color.RED else android.graphics.Color.BLUE,result.getPixel(if(left) 10 else 190,150))
                assertNotEquals(android.graphics.Color.GREEN,result.getPixel(100,150))
                canvas.drawColor(android.graphics.Color.GREEN);view.drawPage(canvas,f,200f,300f,1f)
                assertEquals(android.graphics.Color.GREEN,result.getPixel(10,150))
                assertEquals(android.graphics.Color.GREEN,result.getPixel(190,150))
                result.recycle()
            }
        }
        bmp.recycle()
    }

    @Test fun curledSnapshotMatchesTheVisibleZoomedAndPannedViewport() {
        launch();downloads();openSeries(2);zoomReader()
        // Non-zero scroll is essential: an unzoomed origin can hide a bad snapshot.
        swipeForward()
        ui.onNodeWithTag("reader-content").performTouchInput { swipeUp() }
        Thread.sleep(700)
        val output=File(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        val screen=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val frame=AtomicReference<PageTurnFrame>()
        val location=IntArray(2);var w=0;var h=0
        withReaderWeb {
            it.getLocationOnScreen(location);w=it.width;h=it.height
            frame.set(it.pageTurnFrame(true,"light")!!)
        }
        val bitmap=frame.get().bitmap
        File(output,"curl-viewport-screen.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        File(output,"curl-viewport-capture.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        var matching=0;var samples=0
        for(x in 2..17) for(y in 2..17) {
            val actual=bitmap.getPixel(x*(bitmap.width-1)/20,y*(bitmap.height-1)/20)
            val expected=screen.getPixel(location[0]+x*(w-1)/20,location[1]+y*(h-1)/20)
            val difference=kotlin.math.abs(android.graphics.Color.red(actual)-android.graphics.Color.red(expected))+
                kotlin.math.abs(android.graphics.Color.green(actual)-android.graphics.Color.green(expected))+
                kotlin.math.abs(android.graphics.Color.blue(actual)-android.graphics.Color.blue(expected))
            if(difference<70) matching++
            samples++
        }
        screen.recycle();bitmap.recycle()
        assertTrue("Curl must capture the visible crop, not an unscrolled page: $matching/$samples",matching.toDouble()/samples>.9)
    }

    @Test fun curlStoryboardShowsCurvedPaperWithoutChangingPrintedOrientation() {
        val output=File(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        val store=repo.store(repo.active()!!.key)
        val paper=android.graphics.BitmapFactory.decodeFile(File(store.chapterDir(200),"0.img").path)
        val incoming=android.graphics.BitmapFactory.decodeFile(File(store.chapterDir(200),"1.img").path)
        val width=300;val height=450
        launch()
        scenario!!.onActivity { activity ->
            val renderer=PageCurlView(activity)
            for(left in listOf(true,false)) {
                val storyboard=android.graphics.Bitmap.createBitmap(width*3,height*2,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(storyboard)
                for((index,fraction) in listOf(0f,.18f,.38f,.58f,.78f,1f).withIndex()) {
                    val sc=canvas.save();canvas.translate((index%3*width).toFloat(),(index/3*height).toFloat())
                    canvas.clipRect(0f,0f,width.toFloat(),height.toFloat())
                    canvas.drawBitmap(incoming,null,android.graphics.Rect(0,0,width,height),null)
                    renderer.drawPage(canvas,PageTurnFrame(paper,left,0xfff9f7ef.toInt()),width.toFloat(),height.toFloat(),fraction)
                    canvas.restoreToCount(sc)
                }
                File(output,"curl-storyboard-${if(left) "left" else "right"}.png").outputStream().use {
                    storyboard.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                assertTrue("Story has no painted content",(10 until width step 20).any { x ->
                    storyboard.getPixel(x,100)!=storyboard.getPixel(x+width,100)
                })
                storyboard.recycle()
            }
        }
        paper.recycle();incoming.recycle()
    }

    @Test fun realViewportIsAnimatedAndRapidTouchesCannotSkipTheIncomingPage() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(pageTurnEffect="curl",pageTurnMode="edges")) }
        launch();downloads();openSeries(2);zoomReader()
        android.os.SystemClock.sleep(android.view.ViewConfiguration.getDoubleTapTimeout().toLong()+100)
        val sampled=java.util.concurrent.atomic.AtomicBoolean(false)
        val consumed=java.util.concurrent.atomic.AtomicBoolean(false)
        val turning=AtomicReference<PageCurlView>()
        val fractions=java.util.concurrent.CopyOnWriteArrayList<Float>()
        var observer: android.view.ViewTreeObserver.OnDrawListener?=null
        scenario!!.onActivity { activity ->
            fun find(v: android.view.View): PageCurlView? {
                if(v is PageCurlView) return v
                if(v is android.view.ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            observer=android.view.ViewTreeObserver.OnDrawListener {
                val view=find(activity.window.decorView)
                if(view!=null) {
                    fractions.add(view.fraction)
                    if(view.fraction in .2f.. .7f && sampled.compareAndSet(false,true)) {
                        turning.set(view)
                        val event=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_UP,view.width*.9f,view.height*.5f,0)
                        consumed.set(view.dispatchTouchEvent(event));event.recycle()
                        val screen=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
                        activity.window.decorView.draw(android.graphics.Canvas(screen))
                        val output=File(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                            ?: context.filesDir.path).apply { mkdirs() }
                        File(output,"qa-curl-mid.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        screen.recycle()
                    }
                }
            }
            activity.window.decorView.viewTreeObserver.addOnDrawListener(observer)
        }
        withReaderWeb { web ->
            val capture=web.pageTurnFrame(true,"dark")!!
            assertTrue(capture.bitmap.width.toLong()*capture.bitmap.height<=1_500_000)
            val colors=(0..19).flatMap { x -> (0..19).map { y ->
                capture.bitmap.getPixel(x*(capture.bitmap.width-1)/19,y*(capture.bitmap.height-1)/19)
            } }.toSet()
            assertTrue("Snapshot must contain the actual page, not an empty surface",colors.size>3)
            capture.bitmap.recycle()
            val now=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val e=android.view.MotionEvent.obtain(now,now+20,action,web.width*.9f,web.height*.5f,0)
                web.dispatchTouchEvent(e);e.recycle()
            }
        }
        try {
            ui.waitUntil(5000) { sampled.get() }
        } catch(e:Exception) {
            throw AssertionError("No animated fold: page=${store.progress(200)?.pageNum}, fractions=$fractions",e)
        } finally {
            scenario!!.onActivity { it.window.decorView.viewTreeObserver.removeOnDrawListener(observer) }
        }
        assertTrue(consumed.get())
        awaitPageTurn(page=1)
        assertEquals(1,store.progress(200)!!.pageNum)
        assertNull("Bitmap released after the animation",turning.get()!!.frame)
        assertTrue(js("Array.from(document.images).every(i=>i.complete && i.naturalWidth>0)")=="true")
    }

    @Test fun readerAndOfflineSeriesKeepAccessToFullCatalogueWithoutResettingReading() {
        val store=serialManga(true)
        store.record(200,1)
        launch();downloads()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("download-more-2"))
        ui.onNodeWithTag("download-more-2").performClick()
        ui.onNodeWithTag("series-catalog").assertIsDisplayed()
        ui.onNodeWithText("3 tomos").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitForIdle();openSeries(2);revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Ver tomos / Descargar más").performScrollTo().performClick()
        ui.onNodeWithTag("series-catalog").assertIsDisplayed()
        assertEquals(1,store.progress(200)!!.pageNum)
    }

    @Test fun defaultEdgesAndVolumeSelectorJumpWithoutCompletingSkippedVolumes() {
        val store=serialManga()
        assertEquals("edges",store.get().settings.pageTurnMode)
        launch();downloads();openSeries(2)
        tapEdge(true);awaitPageTurn(page=1)
        revealControls();ui.onNodeWithTag("reader-current-unit").performClick()
        ui.onNodeWithTag("navigator-unit-v20").assertIsSelected()
        val before=store.get().pending
        ui.onNodeWithContentDescription("Cerrar selector").performClick()
        assertEquals(before,store.get().pending)
        ui.onNodeWithTag("reader-current-unit").performClick()
        ui.onNodeWithTag("navigator-unit-list").performScrollToNode(hasTestTag("navigator-unit-v22"))
        ui.onNodeWithTag("navigator-unit-v22").performClick()
        awaitReaderScript();revealControls()
        ui.onNodeWithText("Tomo 3").assertIsDisplayed()
        assertEquals(1,store.progress(200)!!.pageNum)
        assertNull(store.progress(201))
        ui.onNodeWithContentDescription("Tomos y páginas").performClick()
        ui.onNodeWithTag("navigator-unit-list").performScrollToNode(hasTestTag("navigator-unit-v20"))
        ui.onNodeWithTag("navigator-unit-v20").performClick()
        awaitReaderScript();revealControls();ui.onNodeWithText("2 / 2").assertIsDisplayed()
    }
    @Test fun selectorShowsUnavailableVolumeWithoutOpeningItAndKeepsDownloadAccess() {
        val store=serialManga(true)
        launch();downloads();openSeries(2);revealControls()
        ui.onNodeWithContentDescription("Tomos y páginas").performClick()
        ui.onNodeWithTag("navigator-unit-v21").assertIsNotEnabled()
        ui.onNodeWithText("Sin leer · Sin descargar").assertIsDisplayed()
        assertNull(store.progress(201))
        ui.onNodeWithText("Ver tomos / Descargar más").performClick()
        ui.onNodeWithTag("series-catalog").assertIsDisplayed()
        ui.onNodeWithText("3 tomos").assertIsDisplayed()
        ui.onNodeWithTag("series-catalog").performScrollToNode(hasText("Tomo 2"))
        ui.onNodeWithText("Tomo 2").assertIsDisplayed()
        assertFalse(store.get().chapters.getValue(201).ready)
    }
    @Test fun pagePreviewSelectorJumpsAndReopensAtSelectedPageWithoutMarkingRead() {
        val store=repo.store(repo.active()!!.key)
        launch();downloads();openSeries(2);revealControls()
        ui.onNodeWithTag("reader-page-selector").performClick()
        ui.onNodeWithTag("navigator-page-0").assertIsSelected()
        ui.onNodeWithTag("navigator-page-grid").performScrollToIndex(4)
        ui.onNodeWithContentDescription("Vista previa de página 5").assertIsDisplayed()
        ui.onNodeWithTag("navigator-page-4").performClick()
        awaitPageTurn(page=4)
        assertFalse(store.get().isRead(store.get().chapters.getValue(200).chapter))
        scenario!!.recreate();awaitReaderScript();revealControls()
        ui.onNodeWithText("5 / 6").assertIsDisplayed()
        ui.onNodeWithTag("reader-page-selector").performClick()
        ui.onNodeWithTag("navigator-page-4").assertIsSelected()
        ui.onNodeWithContentDescription("Cerrar selector").performClick()
        assertEquals(4,store.progress(200)!!.pageNum)
    }
    @Test fun epubSelectorPreservesParagraphWhenSwitchingBooksAndNavigatesIndex() {
        val store=repo.store(repo.active()!!.key)
        val first=store.get().chapters.getValue(100)
        val second=first.copy(chapter=first.chapter.copy(id=101,titleName="Libro segundo"))
        store.chapterDir(100).copyRecursively(store.chapterDir(101),overwrite=true)
        store.update { it.copy(chapters=it.chapters+(101 to second),
            catalog=it.catalog+(1 to listOf(Volume(1,"",listOf(first.chapter,second.chapter))))) }
        launch();downloads();openSeries(1);awaitReaderScript()
        js("document.getElementById('p-12').scrollIntoView(); true")
        val position=js("window.troopPosition()")
        revealControls();ui.onNodeWithTag("reader-current-unit").performClick()
        ui.onNodeWithTag("navigator-unit-c101").performClick()
        awaitReaderScript()
        assertEquals(codec.decodeFromString<String>(position),store.progress(100)!!.bookScrollId)
        assertEquals(1,store.progress(100)!!.pageNum)
        revealControls();ui.onNodeWithContentDescription("Índice").performClick()
        ui.onNodeWithTag("navigator-toc-2").performClick()
        ui.waitUntil(6000) { store.progress(101)?.pageNum==2 }
        awaitReaderScript()
        // The requested href is normalized to Kavita's id(...) form by the live
        // scroll-position callback. Either persisted form names this exact anchor.
        assertTrue(store.progress(101)!!.bookScrollId in setOf("#chapter-2", "id(\"chapter-2\")"))
        assertEquals("id(\"chapter-2\")",codec.decodeFromString<String>(js("window.troopPosition()")))
        assertEquals("true",js("document.body.innerText.includes('3. El viaje')"))
    }
    @Test fun epubWithoutIndexHasSelectableSections() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(chapters=it.chapters+(100 to it.chapters.getValue(100).copy(toc=emptyList()))) }
        launch();downloads();openSeries(1);revealControls()
        ui.onNodeWithContentDescription("Índice").performClick()
        ui.onNodeWithTag("navigator-section-3").performClick()
        ui.waitUntil(6000) { store.progress(100)?.pageNum==3 }
        awaitReaderScript();assertEquals("true",js("document.body.innerText.includes('4. El viaje')"))
        assertFalse(store.get().isRead(store.get().chapters.getValue(100).chapter))
    }

    @Test fun multipartSelectorCanChooseReadyPartWithoutOpeningMissingPart() {
        val store=serialManga(true)
        store.update { s -> s.copy(catalog=s.catalog+(2 to listOf(Volume(20,"1",(200..202).map { s.chapters.getValue(it).chapter })))) }
        launch();downloads();openSeries(2);revealControls()
        ui.onNodeWithContentDescription("Tomos y páginas").performClick()
        ui.onNodeWithTag("navigator-part-201").assertIsNotEnabled()
        ui.onNodeWithTag("navigator-unit-list").performScrollToNode(hasTestTag("navigator-part-202"))
        ui.onNodeWithTag("navigator-part-202").performClick()
        awaitReaderScript();revealControls()
        ui.onNodeWithText("Tomo 3").assertIsDisplayed()
        assertNull(store.progress(201))
        assertEquals(0,store.progress(200)?.pageNum ?: 0)
    }

    @Test fun selectorRemainsUsableInLandscapeWithLargeTextAndCanSearchVolumes() {
        serialManga();launch();downloads();openSeries(2);revealControls()
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun command(text:String) { automation.executeShellCommand(text).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        try {
            command("settings put system font_scale 1.5")
            scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(6000) { context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            revealControls();ui.onNodeWithContentDescription("Tomos y páginas").performClick()
            val height=ui.onNodeWithTag("navigator-unit-list").fetchSemanticsNode().size.height
            assertTrue("The list must keep usable height", height>context.resources.displayMetrics.density*100)
            ui.onNodeWithContentDescription("Descargar más").assertIsDisplayed()
            ui.onNodeWithContentDescription("Buscar tomos o libros").performClick()
            ui.onNode(hasSetTextAction()).performTextInput("3")
            ui.onNode(hasSetTextAction()).performImeAction()
            ui.waitForIdle()
            ui.onNodeWithTag("navigator-unit-v20").assertDoesNotExist()
            ui.onNodeWithTag("navigator-unit-list").performScrollToNode(hasTestTag("navigator-unit-v22"))
            ui.waitUntil(6000) { ui.onNodeWithTag("navigator-unit-list").fetchSemanticsNode().size.height > context.resources.displayMetrics.density*100 }
            ui.onNodeWithTag("navigator-unit-list").performScrollToNode(hasTestTag("navigator-unit-v22"))
            ui.onNodeWithTag("navigator-unit-v22").assertIsDisplayed().performClick()
            awaitReaderScript();revealControls();ui.onNodeWithText("Tomo 3").assertIsDisplayed()
        } finally {
            command("settings put system font_scale 1.0")
            scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }


    @Test fun openingFirstMangaPageAddsContinueWithoutCreatingServerProgress() {
        val store=repo.store(repo.active()!!.key)
        val before=store.get().pending
        launch();downloads();openSeries(2);awaitReaderScript()
        ui.waitUntil(5000) { store.get().chapters.getValue(200).lastReadAt>0 }
        revealControls();ui.onNodeWithContentDescription("Volver").performClick()
        ui.onNodeWithText("Biblioteca").performClick()
        ui.onNodeWithTag("library-grid").performScrollToNode(hasText("Continúa donde lo dejaste"))
        ui.onNodeWithTag("continue-200").assertExists()
        assertEquals(before,store.get().pending);assertEquals(0,store.progress(200)!!.pageNum)
    }
    @Test fun numericPageJumpRejectsOutOfRangeAndPersistsChosenPosition() {
        val store=repo.store(repo.active()!!.key)
        launch();downloads();openSeries(2);revealControls()
        ui.onNodeWithTag("reader-page-selector").performClick()
        ui.onNodeWithContentDescription("Ir a página").performClick()
        ui.onNodeWithTag("jump-number").performTextReplacement("99999")
        ui.onNodeWithTag("jump-confirm").assertIsNotEnabled()
        ui.onNodeWithTag("jump-number").performTextReplacement("0")
        ui.onNodeWithTag("jump-confirm").assertIsNotEnabled()
        ui.onNodeWithTag("jump-number").performTextReplacement("5")
        ui.onNodeWithTag("jump-number").performImeAction()
        awaitPageTurn(page=4)
        assertEquals(4,store.progress(200)!!.pageNum)
        assertFalse(store.get().isRead(store.get().chapters.getValue(200).chapter))
        scenario!!.recreate();revealControls();ui.onNodeWithText("5 / 6").assertIsDisplayed()
    }
    @Test fun offlineReadFilterAndCleanupPreservePendingMarkersAndUnreadDownloads() {
        val store=repo.store(repo.active()!!.key)
        store.record(200,6);store.addBookmark(200,2,null,"Conservar después de liberar")
        val pending=store.get().pending
        launch();downloads()
        ui.onNodeWithTag("offline-filter").performClick();ui.onNodeWithText("Descargas leídas").performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-2"))
        ui.onNodeWithTag("offline-series-2").assertIsDisplayed()
        ui.onNodeWithTag("offline-series-1").assertDoesNotExist()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("clear-read-downloads"))
        ui.onNodeWithTag("clear-read-downloads").performClick();ui.onNodeWithText("Cancelar").performClick()
        assertTrue(store.chapterDir(200).exists())
        ui.onNodeWithTag("clear-read-downloads").performClick();ui.onNodeWithText("Eliminar descarga").performClick()
        ui.waitUntil(10000) { !store.get().chapters.getValue(200).ready }
        assertFalse(store.chapterDir(200).exists());assertTrue(store.chapterDir(100).exists())
        assertEquals(pending,store.get().pending)
        assertTrue(store.get().bookmarks.any { it.title=="Conservar después de liberar" })
        ui.onNodeWithTag("download-list").performScrollToNode(hasText("Limpiar filtros"))
        ui.onNodeWithText("Limpiar filtros").performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-1"))
        ui.onNodeWithTag("offline-series-1").assertIsDisplayed()
    }
    @Test fun pausingSelectionAndEntireQueueRetainsPayloadsAndSurvivesPolicyChange() = runBlocking {
        val a=repo.active()!!;val store=repo.store(a.key)
        val before=File(store.chapterDir(200),"0.img").readBytes()
        store.update { s -> s.copy(chapters=s.chapters.mapValues { (id,b) ->
            if(id in setOf(100,200)) b.copy(ready=false,state="En cola",downloadRequested=true) else b }) }
        repo.transferLock.lock()
        try {
            Jobs.download(context,a.key,100);Jobs.download(context,a.key,200)
            Jobs.pauseQueue(context,a.key,setOf(100,300)) // completed book must be ignored
            assertFalse(store.get().chapters.getValue(100).wantsDownload)
            assertTrue(store.get().chapters.getValue(200).wantsDownload)
            assertTrue(store.get().chapters.getValue(300).ready)
            launch();downloads()
            ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("pause-queue"))
            ui.onNodeWithTag("pause-queue").performClick()
            ui.waitUntil(10000) { store.get().chapters.values.none { it.wantsDownload } }
            Jobs.setWifiOnly(context,a.key,false)
            assertTrue(store.get().chapters.values.none { it.wantsDownload })
            assertArrayEquals(before,File(store.chapterDir(200),"0.img").readBytes())
            Jobs.resumeQueue(context,a.key,setOf(200))
            assertTrue(store.get().chapters.getValue(200).wantsDownload)
            assertFalse(store.get().chapters.getValue(100).wantsDownload)
            Jobs.pauseQueue(context,a.key,setOf(100,200))
        } finally { repo.transferLock.unlock() }
    }

    @Test fun numericEpubSectionJumpCanReturnToStartOfCurrentSection() {
        launch();downloads();openSeries(1);awaitReaderScript()
        js("document.getElementById('p-12').scrollIntoView(); true")
        assertTrue(js("window.scrollY").toDouble()>100)
        revealControls();ui.onNodeWithContentDescription("Índice").performClick()
        ui.onNodeWithContentDescription("Ir a sección").performClick()
        ui.onNodeWithTag("jump-number").performTextReplacement("2")
        ui.onNodeWithTag("jump-confirm").performClick()
        awaitReaderScript()
        ui.waitUntil(5000) { js("window.scrollY").toDouble()<100 }
        revealControls();ui.onNodeWithText("2 / 4").assertIsDisplayed()
    }
    private fun awaitContinuous() {
        ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("continuous-pages") and
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Lectura continua lista")).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun continuousControls() {
        awaitContinuous()
        if(ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isEmpty())
            ui.onNodeWithTag("continuous-pages").performTouchInput { click(center) }
    }
    @Test fun continuousScrollRestoresImageFractionAfterRecreationAndKeepsRemotePageOnly() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(imageMode="continuous")) }
        launch();downloads();openSeries(2);awaitContinuous()
        ui.onNodeWithTag("continuous-pages").performTouchInput { swipeUp(durationMillis=600) }
        ui.waitUntil(5000) { store.imagePosition(200)?.let { it.page>0 && it.fraction>.02f } == true }
        val before=store.imagePosition(200)!!
        assertNull(store.progress(200)!!.bookScrollId)
        scenario!!.recreate();awaitContinuous()
        ui.waitUntil(5000) { store.imagePosition(200)?.let { it.page==before.page && kotlin.math.abs(it.fraction-before.fraction)<.02f }==true }
        continuousControls();ui.onNodeWithContentDescription("Volver").performClick()
        openSeries(2);awaitContinuous()
        assertEquals(before.page,store.imagePosition(200)!!.page)
    }
    @Test fun continuousSelectorJumpsAndReturnsToStartOfCurrentImage() {
        val store=repo.store(repo.active()!!.key)
        store.update { it.copy(settings=it.settings.copy(imageMode="continuous")) }
        launch();downloads();openSeries(2);awaitContinuous()
        ui.onNodeWithTag("continuous-pages").performTouchInput { swipeUp(durationMillis=600) }
        ui.waitUntil(5000) { store.imagePosition(200)?.page ?: 0 > 0 }
        continuousControls();ui.onNodeWithTag("reader-page-selector").performClick()
        ui.onNodeWithContentDescription("Ir a página").performClick()
        ui.onNodeWithTag("jump-number").performTextReplacement("5")
        ui.onNodeWithTag("jump-confirm").performClick()
        awaitContinuous();ui.waitUntil(5000) { store.imagePosition(200)?.page==4 }
        assertEquals(0f,store.imagePosition(200)!!.fraction,.01f)
        assertFalse(store.get().isRead(store.get().chapters.getValue(200).chapter))
    }
    @Test fun continuousEndRequiresExplicitCompletionThenContinuesWithoutSkippingVolumes() {
        val store=serialManga()
        store.update { it.copy(settings=it.settings.copy(imageMode="continuous")) }
        launch();downloads();openSeries(2);awaitContinuous()
        ui.onNodeWithTag("continuous-pages").performScrollToIndex(2)
        ui.waitUntil(5000) { store.imagePosition(200)?.page==1 }
        assertFalse(store.get().isRead(store.get().chapters.getValue(200).chapter))
        ui.onNodeWithTag("continuous-finish").performClick()
        awaitContinuous()
        ui.waitUntil(5000) { store.get().chapters.getValue(201).lastReadAt>0 }
        assertEquals(2,store.progress(200)!!.pageNum)
        assertFalse(store.get().isRead(store.get().chapters.getValue(201).chapter))
    }
    @Test fun switchingBetweenPagedAndContinuousKeepsCurrentPageAndNavigationPreference() {
        val store=repo.store(repo.active()!!.key)
        launch();downloads();openSeries(2);awaitReaderScript()
        revealControls();ui.onNodeWithText("Siguiente →").performClick();awaitReaderScript()
        revealControls();ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Vertical continuo").performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick();awaitContinuous()
        ui.waitUntil(5000) { store.imagePosition(200)?.page==1 }
        continuousControls();ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Página completa").performScrollTo().performClick()
        ui.onNodeWithText("Listo").performClick();awaitReaderScript()
        revealControls();ui.onNodeWithText("2 / 6").assertIsDisplayed()
        assertEquals("edges",store.get().settings.pageTurnMode)
    }
    @Test fun directNextAndPreviousVolumeDoNotMarkSkippedReadingComplete() {
        val store=serialManga()
        launch();downloads();openSeries(2);awaitReaderScript();revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Tomo anterior").assertIsNotEnabled()
        ui.onNodeWithText("Tomo siguiente").performScrollTo().performClick()
        awaitReaderScript();revealControls()
        ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Tomo anterior").performScrollTo().performClick()
        awaitReaderScript()
        assertFalse(store.get().isRead(store.get().chapters.getValue(200).chapter))
        assertFalse(store.get().isRead(store.get().chapters.getValue(201).chapter))
    }

    @Test fun edgeTapLatencyAndPaintedPageAreMeasuredWithoutDoubleTapDelay() {
        val store=repo.store(repo.active()!!.key)
        val fixture=android.graphics.Bitmap.createBitmap(1600,2400,android.graphics.Bitmap.Config.ARGB_8888)
        val colors=listOf(0xffffc080.toInt(),0xff80c0ff.toInt())
        for(index in 0..5) {
            fixture.eraseColor(colors[index%2])
            File(store.chapterDir(200),"$index.img").outputStream().use { fixture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        fixture.recycle()
        store.update { it.copy(settings=it.settings.copy(pageTurnMode="edges",pageTurnEffect="none")) }
        launch();downloads();openSeries(2);awaitReaderScript()
        val baseline=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("latencyBaseline")=="true"
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val accepted=mutableListOf<Long>();val painted=mutableListOf<Long>()
        val first=AtomicReference<android.webkit.WebView>()
        withReaderWeb { first.set(it) }
        repeat(8) { turn ->
            val right=turn%2==0;val expected=if(right) 1 else 0
            // A settled page with adjacent content available, as during normal reading.
            Thread.sleep(400)
            var released=0L
            withReaderWeb { view ->
                val now=android.os.SystemClock.uptimeMillis()
                val x=view.width*(if(right) .9f else .1f);val y=view.height*.5f
                android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0).also { view.dispatchTouchEvent(it);it.recycle() }
                released=android.os.SystemClock.uptimeMillis()
                android.view.MotionEvent.obtain(now,released,android.view.MotionEvent.ACTION_UP,x,y,0).also { view.dispatchTouchEvent(it);it.recycle() }
            }
            val deadline=released+4000
            while(store.progress(200)?.pageNum!=expected && android.os.SystemClock.uptimeMillis()<deadline) {
                ui.mainClock.advanceTimeByFrame();Thread.sleep(2)
            }
            assertEquals(expected,store.progress(200)!!.pageNum)
            accepted+=android.os.SystemClock.uptimeMillis()-released
            var found=false;var lastColor=0
            while(!found && android.os.SystemClock.uptimeMillis()<deadline) {
                // Compose tests control their own frame clock; explicitly allow the
                // outgoing overlay/recomposition to draw while sampling real pixels.
                ui.mainClock.advanceTimeByFrame()
                val shot=automation.takeScreenshot()
                val pixel=shot.getPixel(shot.width/2,shot.height/2);lastColor=pixel
                if(android.os.SystemClock.uptimeMillis()>deadline-300) File(context.getExternalFilesDir(null),"latency-failure.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                shot.recycle()
                val red=android.graphics.Color.red(pixel);val blue=android.graphics.Color.blue(pixel)
                found=if(right) blue-red>70 else red-blue>70
            }
            assertTrue("The new page never reached the screen: turn=$turn pixel=${Integer.toHexString(lastColor)} DOM="+js("document.images[0].src"),found)
            painted+=android.os.SystemClock.uptimeMillis()-released
            awaitReaderScript()
            if(!baseline) withReaderWeb { assertSame("Do not recreate the image renderer per page",first.get(),it) }
        }
        val output=File(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        File(output,"page-latency.txt").writeText("baseline=$baseline; releaseToAcceptedMs=$accepted; releaseToPaintedMs=$painted")
        if(!baseline) {
            assertTrue("Tap still waits for double tap: $accepted",accepted.sorted()[accepted.size/2]<150)
            assertTrue("Warm pages remain slow to appear: $painted",painted.sorted()[painted.size/2]<250)
        }
    }

    @Test fun corruptUpcomingImageKeepsPaintedPageAndProgressAndCanRetry() {
        val store=repo.store(repo.active()!!.key)
        val file=File(store.chapterDir(200),"1.img");val original=file.readBytes()
        file.writeText("incomplete image")
        launch();downloads();openSeries(2);awaitReaderScript()
        zoomReader();val beforeFailure=traceZoom()
        tapEdge(true)
        ui.waitUntil(6000) { ui.onAllNodesWithText("No se pudo abrir esta página").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,store.progress(200)!!.pageNum)
        assertEquals("\"0.img\"",js("document.images[0].getAttribute('src')"))
        assertEquals("true",js("document.images[0].naturalWidth > 0"))
        assertZoomUnchanged(beforeFailure)
        file.writeBytes(original)
        ui.onNodeWithText("Aceptar").performClick();tapEdge(true);awaitPageTurn(page=1)
        assertEquals("\"1.img\"",js("document.images[0].getAttribute('src')"))
        assertEquals(beforeFailure,js("visualViewport.scale").toDouble(),.04)
    }

    @Test fun edgeDragLongPressAndCancelDoNotTurnButQuickReleasedTapsDo() {
        val store=repo.store(repo.active()!!.key)
        launch();downloads();openSeries(2);awaitReaderScript()
        withReaderWeb { view ->
            fun gesture(actions: List<Triple<Int,Float,Long>>) {
                val now=android.os.SystemClock.uptimeMillis()
                for((action,x,delay) in actions) android.view.MotionEvent.obtain(now,now+delay,action,view.width*x,view.height*.5f,0).also { view.dispatchTouchEvent(it);it.recycle() }
            }
            gesture(listOf(Triple(0,.9f,0L),Triple(2,.5f,100L),Triple(2,.9f,150L),Triple(1,.9f,200L)))
            gesture(listOf(Triple(0,.9f,0L),Triple(1,.9f,700L)))
            gesture(listOf(Triple(0,.9f,0L),Triple(3,.9f,10L)))
        }
        Thread.sleep(400);assertEquals(0,store.progress(200)!!.pageNum)
        // Two independent releases do not wait for double tap or zoom the page.
        repeat(2) { expected ->
            withReaderWeb { view ->
                val now=android.os.SystemClock.uptimeMillis()
                for(action in listOf(0,1)) android.view.MotionEvent.obtain(now,now+action*20L,action,view.width*.9f,view.height*.5f,0).also { view.dispatchTouchEvent(it);it.recycle() }
            }
            ui.waitUntil(2000) { store.progress(200)?.pageNum==expected+1 }
            awaitReaderScript()
        }
        assertEquals(2,store.progress(200)!!.pageNum)
        assertTrue(js("window.visualViewport.scale").toDouble()<1.1)
    }

    @Test fun noEffectPageChangesNeverExposeABlackViewport() {
        val store=repo.store(repo.active()!!.key)
        val fixture=android.graphics.Bitmap.createBitmap(1200,1800,android.graphics.Bitmap.Config.ARGB_8888)
        for(index in 0..5) {
            fixture.eraseColor(android.graphics.Color.rgb(230-index*8,180+index*6,120+index*10))
            File(store.chapterDir(200),"$index.img").outputStream().use { fixture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        fixture.recycle()
        store.update { it.copy(settings=it.settings.copy(pageTurnEffect="none",pageTurnMode="edges")) }
        launch();downloads();openSeries(2);awaitReaderScript()
        // Establish that the first page reached the display, not just WebView's DOM.
        ui.waitUntil(5000) {
            val first=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val color=first.getPixel(first.width/2,first.height/2);first.recycle()
            android.graphics.Color.red(color)+android.graphics.Color.green(color)+android.graphics.Color.blue(color)>400
        }
        val stop=java.util.concurrent.atomic.AtomicBoolean(false)
        val fractions=java.util.Collections.synchronizedList(mutableListOf<Double>())
        val error=AtomicReference<Throwable>()
        val started=java.util.concurrent.CountDownLatch(1)
        val recorder=Thread {
            try {
                val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                while(!stop.get()) {
                    val shot=automation.takeScreenshot() ?: error("No screenshot")
                    var dark=0
                    for(x in 0..19) for(y in 0..19) {
                        val pixel=shot.getPixel(shot.width*(30+x*2)/100,shot.height*(30+y*2)/100)
                        if(android.graphics.Color.red(pixel)+android.graphics.Color.green(pixel)+android.graphics.Color.blue(pixel)<100) dark++
                    }
                    fractions.add(dark/400.0)
                    if(dark>20) File(context.getExternalFilesDir(null),"black-frame-regression.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    shot.recycle();started.countDown();Thread.sleep(16)
                }
            } catch(t:Throwable) { error.set(t);started.countDown() }
        }
        recorder.start()
        try {
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
            var expectedPage=0
            repeat(12) { turn ->
                val right = turn < 5 || turn >= 10
                ui.onNodeWithTag("reader-content").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*(if(right) .92f else .08f),height*.5f)) }
                expectedPage += if(right) 1 else -1
                ui.waitUntil(5000) { store.progress(200)?.pageNum==expectedPage }
                awaitReaderScript();Thread.sleep(120)
            }
        } finally { stop.set(true);recorder.join(10000) }
        error.get()?.let { throw AssertionError("Frame recording failed",it) }
        assertTrue("Insufficient captured frames: ${fractions.size}",fractions.size>=8)
        assertTrue("Black viewport fractions: $fractions",fractions.all { it<.05 })
        assertEquals(2,store.progress(200)!!.pageNum)
        ui.onNodeWithTag("page-turn-effect").assertDoesNotExist()
        File(context.getExternalFilesDir(null),"no-effect-frames.txt").writeText("frames=${fractions.size}; worstDarkFraction=${fractions.maxOrNull()}")
    }
    @Test fun continuousReaderDoesNotSkipMissingNextVolume() {
        val store=serialManga(missingMiddle=true)
        store.update { it.copy(settings=it.settings.copy(imageMode="continuous")) }
        launch();downloads();openSeries(2);awaitContinuous()
        ui.onNodeWithTag("continuous-pages").performScrollToIndex(2)
        ui.onNodeWithTag("continuous-finish").performClick()
        ui.onNodeWithText("Lectura completada").assertExists()
        assertEquals(2,store.progress(200)!!.pageNum)
        assertEquals(0L,store.get().chapters.getValue(202).lastReadAt)
    }

}
