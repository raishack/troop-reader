package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PersonalFlowTest {
    @get:Rule val permission=androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui=createEmptyComposeRule()
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val repo get()=context.repository()
    private val store get()=repo.store(repo.active()!!.key)
    private var scenario: ActivityScenario<MainActivity>?=null
    @Before fun setup() {
        val a=repo.active();require(a==null || a.server=="https://demo.invalid" || a.server.startsWith("http://localhost:"))
        WorkManager.getInstance(context).cancelAllWork().result.get();repo.vault.clear();Demo.install(context)
    }
    @After fun cleanup() { scenario?.close();WorkManager.getInstance(context).cancelAllWork().result.get() }
    private fun launch() {
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.waitUntil(10000) { ui.onAllNodesWithText("Descargas").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    }
    private fun open(id: Int) {
        ui.onNodeWithText("Descargas").performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-$id"))
        ui.onNodeWithTag("read-series-$id").performClick();awaitBook()
    }
    private fun js(code: String): String {
        val result=AtomicReference<String>();val done=java.util.concurrent.CountDownLatch(1)
        scenario!!.onActivity { activity ->
            fun find(v: android.view.View): WebView? {
                if(v is WebView)return v
                if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let { return it }
                return null
            }
            val web=find(activity.window.decorView)
            if(web==null) { result.set("null");done.countDown() } else web.evaluateJavascript(code) { result.set(it);done.countDown() }
        }
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));return result.get()
    }
    private fun awaitBook() { ui.waitUntil(10000) { js("typeof window.troopRestore")=="\"function\"" && ui.onAllNodes(hasTestTag("reader-content") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Página lista")).fetchSemanticsNodes().isNotEmpty() } }
    private fun controls() {
        if(ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("reader-content").performTouchInput { click(center) }
        ui.waitUntil(4000) { ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun tool(label: String) { controls();ui.onNodeWithContentDescription("Herramientas del libro").performClick();ui.onNodeWithText(label).performClick() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);ui.waitForIdle() }
    private fun screenshot(name: String) {
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        File(dir,"alpha17-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun favoritesCollectionsAndFollowingAreUsableAndPersist() {
        launch();ui.onNodeWithTag("library-grid").performScrollToNode(hasTestTag("catalog-series-1"));ui.onNodeWithTag("catalog-series-1").performClick()
        ui.onNodeWithText("Favorita").performClick();ui.onNodeWithText("Seguir novedades").performClick()
        ui.onNodeWithText("Añadir a colección").performClick();ui.onNodeWithText("Nueva colección").performTextInput("Mis aventuras")
        ui.onNodeWithText("Crear y añadir").performClick();ui.onNodeWithText("Listo").performClick();back()
        ui.onNodeWithText("Mi espacio").performClick();ui.onNode(hasText("El jardín de las mareas") and hasAnyAncestor(hasTestTag("personal-hub"))).assertExists()
        ui.onNodeWithText("Colecciones").performClick();ui.onNodeWithText("Mis aventuras").assertExists();screenshot("collections")
        scenario!!.recreate();ui.onNodeWithText("Mis aventuras").assertExists()
        assertTrue(Store(store.root).get().favorites.contains(1));assertTrue(store.get().followed.containsKey(1))
    }
    @Test fun seriesProfileDoesNotChangeAnotherSeriesOrGlobalPreferences() {
        launch();open(2);controls();ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithTag("series-reader-profile").performClick();ui.onNodeWithText("Ajustar al ancho").performClick()
        ui.onNodeWithText("Listo").performClick();awaitBook()
        assertEquals("width",store.get().readerSettings(2).imageMode);assertEquals("fit",store.get().readerSettings(4).imageMode)
        scenario!!.recreate();awaitBook();assertEquals("width",Store(store.root).get().readerSettings(2).imageMode)
    }
    @Test fun epubSearchJumpsAcrossSectionsAndNotesHighlightAfterReopening() {
        File(store.chapterDir(100),"2.html").writeText("<div><p>Un tesoro invisible entre las páginas.</p><p>El final.</p></div>")
        launch();open(1);tool("Buscar en el libro")
        ui.onNodeWithTag("book-search-query").performTextInput("tesoro invisible")
        ui.waitUntil(5000) { ui.onAllNodesWithText("Un tesoro invisible entre las páginas.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Un tesoro invisible entre las páginas.").performClick();awaitBook()
        assertEquals(2,store.progress(100)!!.pageNum)
        ui.waitUntil(3000) { js("getSelection().toString()").contains("tesoro invisible") }
        tool("Subrayar / Nota")
        ui.waitUntil(5000) { ui.onAllNodesWithTag("note-comment").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("note-comment").performTextInput("Mi anotación")
        ui.onNodeWithText("Guardar").performClick()
        ui.waitUntil(5000) { js("document.querySelectorAll('mark[data-troop-note]').length")=="1" }
        assertEquals("tesoro invisible",store.get().notes.single().quote)
        scenario!!.recreate();awaitBook()
        ui.waitUntil(5000) { js("document.querySelectorAll('mark[data-troop-note]').length")=="1" }
        tool("Subrayados y notas");ui.onNodeWithText("Mi anotación").assertExists();screenshot("notes")
    }
    @Test fun statisticsAndBackupPanelsRemainUsableInLandscape() {
        store.readingTime(100,1,10000);store.readingCompleted(100)
        launch();ui.onNodeWithText("Mi espacio").performClick();ui.onNodeWithText("Estadísticas").performClick()
        ui.onNodeWithText("1 páginas/secciones visitadas").assertExists()
        scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.onNodeWithText("Copias y notas").performClick()
        ui.onNodeWithTag("personal-hub").performScrollToNode(hasText("Restaurar copia"));ui.onNodeWithText("Restaurar copia").assertIsDisplayed();screenshot("backup-landscape")
    }
    @Test fun voiceInitializesOfflineOrGivesActionableMessageAndStopsOnPause() = runBlocking {
        launch()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val ref=AtomicReference<BookSpeaker>()
        val saved=store.get().chapters.getValue(100)
        withContext(Dispatchers.Main) { ref.set(BookSpeaker(context,store,saved,scope) { _,_ -> }) }
        try {
            withTimeout(20000) { while(withContext(Dispatchers.Main) { ref.get().message=="Preparando voz…" }) delay(100) }
            withContext(Dispatchers.Main) {
                val voice=ref.get()
                if(voice.available) {
                    voice.speed(1.25f);voice.setTimer(15);voice.play(1)
                    assertTrue(voice.speaking)
                    assertEquals(1.25f,voice.rate);assertEquals(15,voice.timerMinutes)
                } else assertTrue(voice.message.contains("voz") || voice.message.contains("motor"))
            }
            if(withContext(Dispatchers.Main) { ref.get().available }) {
                withTimeout(15000) { while(withContext(Dispatchers.Main) { ref.get().utterancesStarted==0 }) delay(100) }
                withContext(Dispatchers.Main) { ref.get().pause();assertFalse(ref.get().speaking) }
            }
        } finally { withContext(Dispatchers.Main) { ref.get().close() };scope.cancel() }
        open(1);tool("Lectura en voz alta");ui.onNodeWithText("Temporizador").assertExists();screenshot("voice")
    }
    @Test fun followedCheckDetectsNewChaptersWithoutChangingReading() = runBlocking {
        MockWebServer().use { server ->
            val account=Account(server.url("/").toString().trimEnd('/'),7,"fixture","fixture",roles=listOf("Download"))
            repo.vault.save(account);val s=repo.store(account.key);val series=Series(81,"Seguida",2,1);val chapter=Chapter(810,1,pages=4)
            s.update { LocalState(series=listOf(series),catalog=mapOf(81 to listOf(Volume(1,"1",listOf(chapter))))) };s.follow(series,true)
            server.enqueue(MockResponse().setBody(codec.encodeToString(listOf(Volume(1,"1",listOf(chapter)),Volume(2,"2",listOf(chapter.copy(id=811,volumeId=2)))))))
            assertEquals(1,DiscoveryJobs.check(repo,account.key));assertEquals(listOf(811),s.get().followed.getValue(81).unread.map { it.id })
            assertTrue(s.get().progress.isEmpty());assertTrue(s.get().chapters.isEmpty())
        }
    }
    @Test fun smartDownloadQueuesNextPartAndNeverResumesPausedBook() = runBlocking {
        MockWebServer().use { server ->
            val account=Account(server.url("/").toString().trimEnd('/'),8,"fixture","fixture",roles=listOf("Download"));repo.vault.save(account)
            val s=repo.store(account.key);val series=Series(91,"Siguiente",2,1);val c=Chapter(910,1,pages=2,format=1);val next=c.copy(id=911,volumeId=2)
            val volumes=listOf(Volume(1,"1",listOf(c)),Volume(2,"2",listOf(next)))
            s.update { LocalState(series=listOf(series),chapters=mapOf(c.id to SavedChapter(c,series,false,true)),smartDownloads=SmartDownloads(enabled=true),catalog=mapOf(91 to volumes)) }
            server.dispatcher=object: Dispatcher() { override fun dispatch(r: RecordedRequest):MockResponse=when {
                r.path!!.startsWith("/api/Series/volumes") -> MockResponse().setBody(codec.encodeToString(volumes))
                r.path!!.startsWith("/api/Download/chapter-size") -> MockResponse().setBody("1000")
                r.path!!.startsWith("/api/Reader/get-progress") -> MockResponse().setBody(codec.encodeToString(Progress(chapterId=911)))
                else -> MockResponse().setResponseCode(503)
            } }
            assertEquals(listOf(911),DiscoveryJobs.prepareNext(repo,account.key,910))
            assertTrue(s.get().chapters.getValue(911).automatic)
            Jobs.pause(context,account.key,911)
            assertTrue(DiscoveryJobs.prepareNext(repo,account.key,910).isEmpty())
            assertFalse(s.get().chapters.getValue(911).downloadRequested!!)
        }
    }
    @Test fun backupRoundTripOnAndroidPreservesDownloadsAndQueuesRestoredPositionForReview() {
        val account=repo.active()!!;val before=store.get();val out=java.io.ByteArrayOutputStream()
        Backups.write(account,before,out);val backup=Backups.read(account,out.toByteArray().inputStream())
        val restored=Backups.merge(LocalState(),backup)
        assertFalse(restored.chapters.getValue(100).ready);assertTrue(restored.pending.getValue(100).restored)
        store.update { Backups.merge(it,backup) }
        assertTrue(store.get().chapters.getValue(100).ready)
        assertEquals(before.progress,store.get().progress)
        assertTrue(File(store.chapterDir(100),"1.html").isFile)
    }
}
