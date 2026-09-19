package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EinkFlowTest {
    @get:Rule val permission=androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui=createEmptyComposeRule()
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val repo get()=context.repository()
    private val store get()=repo.store(repo.active()!!.key)
    private var scenario: ActivityScenario<MainActivity>?=null
    @Before fun setup() {
        require(repo.active()==null || repo.active()!!.server=="https://demo.invalid" || repo.active()!!.server.startsWith("http://localhost:"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.voicePlayback().stop() }
        WorkManager.getInstance(context).cancelAllWork().result.get();repo.vault.clear();Demo.install(context)
        context.getSharedPreferences("display",0).edit().clear().commit()
    }
    @After fun finish() { scenario?.close();context.getSharedPreferences("display",0).edit().clear().commit() }
    private fun launch(mode: DisplayMode=DisplayMode.MONO) {
        context.getSharedPreferences("display",0).edit().putString("mode",mode.name).commit()
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.waitUntil(10000) { ui.onAllNodesWithText("Descargas").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    }
    private fun open(id: Int) { ui.onNodeWithText("Descargas").performClick();ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-$id"));ui.onNodeWithTag("read-series-$id").performClick();awaitBook() }
    private fun awaitBook() { ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("reader-content") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Página lista")).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun controls() { if(ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("reader-content").performTouchInput { click(center) };ui.waitUntil(4000) { ui.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isNotEmpty() } }
    private fun next() { ui.onNodeWithTag("reader-content").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.9f,height*.5f)) } }
    private fun web(): WebView {
        fun find(v: View): WebView? = if(v is WebView) v else if(v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
        var result: WebView?=null;scenario!!.onActivity { result=find(it.window.decorView) };return result!!
    }
    private fun js(script: String): String {
        val w=web();var result="";val latch=CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { w.evaluateJavascript(script) { result=it;latch.countDown() } }
        assertTrue(latch.await(6,TimeUnit.SECONDS));return result
    }
    private fun cleaned(): Int { var value=0;scenario!!.onActivity { value=it.einkRefresh.completed };return value }
    private fun assertFullWindow() {
        ui.waitForIdle()
        if(android.os.Build.VERSION.SDK_INT>=29) {
            // Rotation/Compose idle can precede WindowManager focus delivery.
            // Wait for the last visible window, then measure that actual window.
            ui.waitUntil(5000) {
                var focused=false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    focused=android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .lastOrNull { it.isAttachedToWindow && it.isShown }?.hasWindowFocus()==true
                }
                focused
            }
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val root=android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                    val origin=IntArray(2);root.getLocationOnScreen(origin)
                    assertEquals("Window must cover the screen, including the cutout",0,origin[0])
                    assertEquals("Window must not leave the old toolbar above it",0,origin[1])
                    assertTrue(kotlin.math.abs(root.width-bitmap.width)<=2)
                    assertTrue(kotlin.math.abs(root.height-bitmap.height)<=2)
                }
            } finally { bitmap.recycle() }
        }
    }
    private fun shot(name: String) {
        ui.waitForIdle();Thread.sleep(2200)
        val dir=java.io.File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(dir,"alpha20-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun monochromePageChangesCleanAndKeepZoomWithoutChangingStoredPreferences() {
        store.readerSettings(2) { it.copy(pageTurnEffect="curl",imageMode="continuous",theme="dark") }
        val saved=store.get().readerSettings(2)
        launch();open(2)
        assertEquals("\"grayscale(1)\"",js("getComputedStyle(document.querySelector('img')).filter"))
        assertEquals("\"rgb(255, 255, 255)\"",js("getComputedStyle(document.body).backgroundColor"))
        assertEquals(saved,store.get().readerSettings(2))
        val w=web();InstrumentationRegistry.getInstrumentation().runOnMainSync { w.zoomBy(2f) };Thread.sleep(700)
        var scale=0f;InstrumentationRegistry.getInstrumentation().runOnMainSync { @Suppress("DEPRECATION")
            scale=w.scale }
        Thread.sleep(1200);val before=cleaned();next();awaitBook()
        ui.waitUntil(10000) { store.progress(200)?.pageNum==1 && cleaned()>before }
        var after=0f;InstrumentationRegistry.getInstrumentation().runOnMainSync { @Suppress("DEPRECATION")
            after=w.scale }
        assertEquals(scale,after,.04f);ui.onNodeWithTag("page-turn-effect").assertDoesNotExist()
        shot("mono-manga");scenario!!.recreate();awaitBook()
        assertEquals(saved,store.get().readerSettings(2))
    }
    @Test fun epubTurnsByViewportWithEdgesAndVolumeAndReturnsWithoutSkippingText() {
        store.readerSettings(1) { it.copy(volumeKeys=true) }
        launch();open(1);val chapter=store.progress(100)!!.pageNum
        assertEquals("0",js("Math.round(window.scrollY)"));next()
        ui.waitUntil(6000) { js("Math.round(window.scrollY)").toInt()>50 }
        assertEquals(chapter,store.progress(100)!!.pageNum)
        val first=js("Math.round(window.scrollY)").toInt()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ui.waitUntil(6000) { js("Math.round(window.scrollY)").toInt()>first }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_UP)
        ui.waitUntil(6000) { js("Math.round(window.scrollY)").toInt()<=first+3 }
        shot("mono-epub")
    }
    @Test fun menusAreOpaqueCleanOnOpenCloseAndControlsDoNotAutoHide() {
        launch();open(2);controls();Thread.sleep(6100)
        ui.onNodeWithContentDescription("Opciones de lectura").assertExists()
        val before=cleaned();ui.onNodeWithContentDescription("Opciones de lectura").performClick()
        ui.onNodeWithText("Tipo de pantalla").assertExists();assertFullWindow();shot("settings")
        assertTrue(cleaned()>before)
        ui.onNodeWithText("Tinta electrónica color").performClick()
        shot("color-settings");ui.onNodeWithText("Listo").performClick();awaitBook()
        assertEquals("\"none\"",js("getComputedStyle(document.querySelector('img')).filter"))
        controls();ui.onNodeWithContentDescription("Tomos y páginas").performClick();shot("selector")
        ui.onNodeWithContentDescription("Cerrar selector").performClick();awaitBook()
        scenario!!.recreate();awaitBook();scenario!!.onActivity { assertEquals(DisplayMode.COLOR,it.displayPreferences.mode) }
    }
    @Test fun disablingCleaningAndReturningToNormalIsPersistentAndNeverChangesReadingData() {
        launch();val before=store.get().progress;val marks=store.get().bookmarks
        ui.onNodeWithContentDescription("Ajustes").performClick();shot("mono-home-settings")
        ui.onNodeWithText("Pantalla normal").performClick();ui.onNodeWithText("Listo").performClick()
        assertEquals(before,store.get().progress);assertEquals(marks,store.get().bookmarks)
        scenario!!.onActivity { assertEquals(DisplayMode.NORMAL,it.displayPreferences.mode) }
        scenario!!.recreate();ui.waitUntil(10000) { ui.onAllNodesWithText("Descargas").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        scenario!!.onActivity { assertEquals(DisplayMode.NORMAL,it.displayPreferences.mode) }
        shot("normal-restored")
    }
    @Test fun landscapeSettingsRemainClosableWithLargeTextAndOpaqueWindow() {
        fun shell(cmd: String) { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        try {
            shell("settings put system font_scale 1.3")
            launch(DisplayMode.COLOR)
            scenario!!.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Ajustes").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            ui.onNodeWithContentDescription("Ajustes").performClick()
            ui.onNodeWithText("Listo").assertIsDisplayed();assertFullWindow();shot("color-landscape-large")
            ui.onNodeWithText("Listo").performClick()
            ui.onNodeWithText("Descargas").assertExists()
        } finally { shell("settings put system font_scale 1.0");shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 0") }
    }
    @Test fun idleAndBackgroundDoNotProduceContinuousRefreshesAndResumeCleans() {
        launch();Thread.sleep(4000);val stable=cleaned();Thread.sleep(5000);assertEquals(stable,cleaned())
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);Thread.sleep(1000)
        val paused=cleaned();Thread.sleep(1500);assertEquals(paused,cleaned())
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.waitUntil(5000) { cleaned()>paused };shot("mono-library")
    }
}
