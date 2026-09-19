package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class EinkTest {
    private val context get()=org.robolectric.RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("display",0).edit().clear().commit() }
    @Test fun deviceProfileIsPersistentButDoesNotRewriteBookOrGlobalPreferences() {
        val prefs=DisplayPreferences(context)
        assertEquals(DisplayMode.NORMAL,prefs.mode)
        val normal=ReadingSettings(theme="sepia",imageMode="continuous",pageTurnEffect="curl",fontSize=14,rtl=true,volumeKeys=true)
        prefs.mode(DisplayMode.MONO)
        assertEquals(DisplayMode.MONO,DisplayPreferences(context).mode)
        val ink=EinkPolicy.reading(normal,prefs.mode)
        assertEquals("eink-mono",ink.theme);assertEquals("none",ink.pageTurnEffect);assertEquals("fit",ink.imageMode)
        assertEquals(18,ink.fontSize);assertTrue(ink.rtl);assertTrue(ink.volumeKeys)
        assertEquals(normal,EinkPolicy.reading(normal,DisplayMode.NORMAL))
        prefs.cleaning(false);assertFalse(DisplayPreferences(context).cleaning)
    }
    @Test fun colorRetainsArtworkAndMonoDoesNotBinarizeOrRemoveIt() {
        val html="<p>A link <a href='#p'>here</a></p><img src='a.png'><mark data-troop-note='1'>note</mark>"
        val mono=ReaderHtml.document(html,EinkPolicy.reading(ReadingSettings(),DisplayMode.MONO),true)
        val color=ReaderHtml.document(html,EinkPolicy.reading(ReadingSettings(),DisplayMode.COLOR),true)
        assertTrue(mono.contains("filter:grayscale(1)"));assertFalse(color.contains("filter:grayscale(1)"))
        for(doc in listOf(mono,color)) {
            assertTrue(doc.contains("background:#fff!important"));assertTrue(doc.contains("color:#000!important"))
            assertTrue(doc.contains("animation:none!important"));assertTrue(doc.contains("border-bottom:2px solid #000"))
            assertTrue(doc.contains(html));assertTrue(doc.contains("connect-src 'none'"))
        }
    }
    @Test fun vendorProbeIsConservativeAndMissingApiDoesNotPretendHardwareSuccess() {
        assertTrue(EinkPolicy.isOnyx("ONYX","unknown"));assertTrue(EinkPolicy.isOnyx("unknown","BOOX"))
        assertFalse(EinkPolicy.isOnyx("unknown","Bigme"));assertNull(OnyxFullRefresh.detect())
    }
    @Test fun repaintCoalescesAndStopsCompletelyWhenIdleOrBackgrounded() {
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root=android.widget.FrameLayout(activity);activity.setContentView(root)
        val prefs=DisplayPreferences(context);prefs.mode(DisplayMode.MONO)
        val refresh=EinkRefresh(prefs);val detach=refresh.attach(root)
        refresh.foreground(true)
        repeat(25) { refresh.request() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        assertEquals(1,refresh.completed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals("No perpetual refresh loop",1,refresh.completed)
        refresh.beginTouch();refresh.request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(1,refresh.completed)
        refresh.endTouch();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        refresh.foreground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertEquals(1,refresh.completed)
        refresh.foreground(true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(2,refresh.completed)
        detach();refresh.dispose();activity.finish()
    }
    @Test fun disablingCleaningCancelsAnInFlightPulseWithoutDisablingTheTheme() {
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view=android.widget.FrameLayout(activity);activity.setContentView(view)
        val prefs=DisplayPreferences(context);prefs.mode(DisplayMode.COLOR)
        val refresh=EinkRefresh(prefs);refresh.attach(view);refresh.foreground(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        prefs.cleaning(false);refresh.changed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(0,refresh.completed);assertEquals(DisplayMode.COLOR,prefs.mode)
        refresh.dispose();activity.finish()
    }
}
