package es.gamingtroop.reader

import android.content.*
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Alpha19FlowTest {
    @get:Rule val permission=androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui=createEmptyComposeRule()
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val repo get()=context.repository()
    private val store get()=repo.store(repo.active()!!.key)
    private var scenario: ActivityScenario<MainActivity>?=null
    @Before fun setup() {
        val a=repo.active();require(a==null || a.server=="https://demo.invalid" || a.server.startsWith("http://localhost:"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.voicePlayback().stop() }
        WorkManager.getInstance(context).cancelAllWork().result.get();repo.vault.clear();Demo.install(context)
    }
    @After fun cleanup() { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 224").close(); InstrumentationRegistry.getInstrumentation().runOnMainSync { context.voicePlayback().stop() };scenario?.close();WorkManager.getInstance(context).cancelAllWork().result.get() }
    private fun launch() {
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.waitUntil(10000) { ui.onAllNodesWithText("Descargas").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    }
    private fun open(id: Int) { ui.onNodeWithText("Descargas").performClick();ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-$id"));ui.onNodeWithTag("read-series-$id").performClick();awaitBook() }
    private fun awaitBook() { ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("reader-content") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Página lista")).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun controls() { if(ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("reader-content").performTouchInput { click(center) };ui.waitUntil(4000) { ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isNotEmpty() } }
    private fun next() { ui.onNodeWithTag("reader-content").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.9f,height*.5f)) } }
    private fun capture(name: String) {
        ui.waitForIdle();Thread.sleep(400)
        val dir=java.io.File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(dir,"alpha19-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun resumingDownloadKeepsExistingPagesReadableThroughoutTheWorker() = runBlocking {
        MockWebServer().use { server ->
            val png=java.io.File(store.chapterDir(200),"0.img").readBytes()
            val a=Account(server.url("/").toString().trimEnd('/'),989,"fixture","fixture",roles=listOf("Download"))
            repo.vault.save(a);val local=repo.store(a.key)
            val c=Chapter(990,pages=3,format=1)
            local.update { LocalState(chapters=mapOf(c.id to SavedChapter(c,Series(99,"Ensayo"),false,downloadedPages=2))) }
            val dir=local.chapterDir(c.id).apply { mkdirs() }
            (0..1).forEach { java.io.File(dir,"$it.img").writeBytes(png) }
            val observed=mutableListOf<Int>()
            val monitor=launch(Dispatchers.Unconfined) { local.states.collect { observed.add(it.chapters.getValue(c.id).readablePages) } }
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(r: RecordedRequest)=when {
                    r.path!!.startsWith("/api/Download/chapter-size") -> MockResponse().setBody("1000")
                    r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(c))
                    r.path!!.startsWith("/api/Reader/image") -> MockResponse().setBody(okio.Buffer().write(png))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                androidx.work.testing.TestListenableWorkerBuilder<DownloadWorker>(context,inputData=androidx.work.workDataOf("account" to a.key,"chapter" to c.id)).build().doWork()
                assertTrue(local.get().chapters.getValue(c.id).ready)
                assertTrue("Previously downloaded prefix disappeared: $observed",observed.all { it>=2 })
                assertTrue(observed.contains(3))
            } finally { monitor.cancelAndJoin() }
        }
    }
    @Test fun partialMangaWaitsWithoutChangingProgressAndContinuesWhenPageArrives() {
        store.update { s -> s.copy(chapters=s.chapters+(200 to s.chapters.getValue(200).copy(ready=false,downloadedPages=1,downloadRequested=false,state="Descarga pausada"))) }
        launch();open(2);next();ui.onNodeWithText("Esperando descarga").assertExists();assertEquals(0,store.progress(200)!!.pageNum);capture("waiting")
        ui.onNodeWithText("Continuar aquí").assertIsNotEnabled()
        store.update { s -> s.copy(chapters=s.chapters+(200 to s.chapters.getValue(200).copy(downloadedPages=2))) }
        ui.onNodeWithText("Continuar aquí").assertIsEnabled().performClick();awaitBook()
        ui.waitUntil(5000) { store.progress(200)?.pageNum==1 }
        assertFalse(store.get().isRead(store.get().chapters.getValue(200).chapter))
    }
    @Test fun partialEpubCanReadSearchAndWaitWithoutCompletingBook() {
        store.update { s -> s.copy(chapters=s.chapters+(100 to s.chapters.getValue(100).copy(ready=false,downloadedPages=2,downloadRequested=false,state="Pausada"))) }
        launch();open(1);next();ui.onNodeWithText("Esperando descarga").assertExists();assertEquals(1,store.progress(100)!!.pageNum)
        ui.onNodeWithText("Seguir en esta página").performClick();controls();ui.onNodeWithContentDescription("Herramientas del libro").performClick();ui.onNodeWithText("Buscar en el libro").performClick()
        ui.onNodeWithTag("book-search-query").performTextInput("ciudad");ui.waitUntil(5000) { ui.onAllNodesWithText("36 coincidencias").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun physicalVolumeAndOrientationAreOptionalAndPersist() {
        store.readerSettings(2) { it.copy(volumeKeys=true,orientation="portrait") }
        launch();open(2)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ui.waitUntil(5000) { store.progress(200)?.pageNum==1 }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_UP)
        ui.waitUntil(5000) { store.progress(200)?.pageNum==0 }
        scenario!!.recreate();awaitBook();assertTrue(store.get().readerSettings(2).volumeKeys)
        scenario!!.onActivity { assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,it.requestedOrientation) }
    }
    @Test fun serverListsAreReadOnlyOrderedCachedAndDoNotReplaceOnFailure() = runBlocking {
        MockWebServer().use { server ->
            val account=Account(server.url("/").toString().trimEnd('/'),987,"fixture","fixture")
            repo.vault.save(account)
            server.enqueue(MockResponse().setBody("""[{"id":5,"title":"Lista de prueba"}]"""));server.enqueue(MockResponse().setBody("""[{"id":8,"title":"Colección remota"}]"""))
            Shelves.refresh(repo,account.key)
            server.enqueue(MockResponse().setBody("""[{"id":2,"order":2,"chapterId":20,"seriesId":2,"seriesName":"Obra dos"},{"id":1,"order":1,"chapterId":10,"seriesId":1,"seriesName":"Obra uno"}]"""))
            Shelves.load(repo,account.key,5,false)
            assertEquals(listOf(10,20),store.get().serverShelves.items.getValue(5).map { it.chapterId })
            server.enqueue(MockResponse().setBody("""[{"id":1,"name":"Obra uno"}]"""));Shelves.load(repo,account.key,8,true)
            assertEquals("Obra uno",Store(store.root).get().serverShelves.members.getValue(8).first().name)
            val before=store.get().serverShelves
            server.enqueue(MockResponse().setResponseCode(500));try { Shelves.refresh(repo,account.key);fail() } catch(_: ApiError) { }
            assertEquals(before,store.get().serverShelves)
            val requests=(0 until server.requestCount).map { server.takeRequest() }
            assertTrue(requests.all { it.method=="GET" || it.path!!.startsWith("/api/ReadingList/lists?") && it.method=="POST" })
        }
    }
    @Test fun dictionaryChooserAndDiagnosticsHaveClearLocalControls() {
        launch();ui.onNodeWithText("Mi espacio").performClick();ui.onNodeWithText("Diagnóstico").performClick();ui.onNodeWithText("Exportar diagnóstico").assertExists();capture("diagnostic")
        open(1);controls();ui.onNodeWithContentDescription("Herramientas del libro").performClick();ui.onNodeWithText("Diccionario").performClick()
        ui.onNodeWithText("Palabra o texto seleccionado").assertExists();capture("dictionary")
        val target=DictionaryTarget("Ensayo","es.example.dictionary","es.example.dictionary.Main")
        val intent=DictionaryLookup.intent(target,"palabra")
        assertEquals("palabra",intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT));assertTrue(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,false));assertNull(intent.data)
    }
    @Test fun voiceKeepsPlayingScreenOffAndNotificationPausePreservesProgress() = runBlocking {
        launch();open(1)
        val v=context.voicePlayback();val sp=withContext(Dispatchers.Main) { v.prepare(repo.active()!!.key,store.get().chapters.getValue(100)) }
        withTimeout(15000) { while(!withContext(Dispatchers.Main) { sp.available || !sp.message.contains("Preparando") }) delay(100) }
        assertTrue("Offline test voice must be installed: ${sp.message}",sp.available)
        withContext(Dispatchers.Main) { v.play(1,2) }
        withTimeout(10000) { while(!withContext(Dispatchers.Main) { sp.utterancesStarted>0 })delay(100) }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 223").close()
        delay(1600)
        assertTrue(sp.speaking)
        val nm=context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(nm.activeNotifications.any { it.id==1901 })
        nm.activeNotifications.first { it.id==1901 }.notification.actions[0].actionIntent.send()
        withTimeout(5000) { while(withContext(Dispatchers.Main) { sp.speaking })delay(100) }
        assertTrue(store.progress(100)!!.bookScrollId!!.startsWith("@text:"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 224").close()
        val held=store.progress(100)!!
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        delay(2000)
        assertEquals(held,store.progress(100))
        val total=store.get().chapters.getValue(100).chapter.pages
        store.record(100,total,null)
        val previousRevision=v.revision
        withContext(Dispatchers.Main) { v.play(total-1) }
        withTimeout(5000) { while(v.revision==previousRevision) delay(50) }
        delay(800)
        assertEquals("Listening at the end must not mark a completed book unread",total,store.progress(100)!!.pageNum)
        Unit
    }
}
