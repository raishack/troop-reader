package es.gamingtroop.reader

import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BigmeRefreshTest {
    private val context get()=org.robolectric.RuntimeEnvironment.getApplication()
    class Modes { companion object { const val EINK_CLEAN_MODE=7341 } }
    class Manager { companion object {
        var calls=0;var received=0;var fail=false
        @JvmStatic fun forceGlobalRefresh(mode: Int) {
            calls++;received=mode
            if(fail) throw SecurityException("device denied request")
        }
    } }
    class WrongManager { fun forceGlobalRefresh(mode: Int) = mode }
    class WrongModes { companion object { const val EINK_CLEAN_MODE="7341" } }
    private fun probe(manager: Class<*> = Manager::class.java,modes: Class<*> = Modes::class.java) =
        BigmeFullRefresh.detect { if(it.endsWith("XrzEinkManager")) manager else modes }
    @Before fun reset() {
        context.getSharedPreferences("display",0).edit().clear().commit()
        Manager.calls=0;Manager.received=0;Manager.fail=false
    }
    @Test fun probeNeverRefreshesAndUsesOnlyTheRuntimeNamedCleaningConstant() {
        val p=probe();assertEquals(BigmeApiStatus.AVAILABLE,p.status);assertEquals(0,Manager.calls)
        assertTrue(p.driver!!.request());assertEquals(1,Manager.calls);assertEquals(7341,Manager.received)
    }
    @Test fun absentBlockedAndIncompatibleContractsCannotOfferNativeRefresh() {
        assertEquals(BigmeApiStatus.ABSENT,BigmeFullRefresh.detect { throw ClassNotFoundException() }.status)
        assertEquals(BigmeApiStatus.BLOCKED,BigmeFullRefresh.detect { throw SecurityException() }.status)
        assertEquals(BigmeApiStatus.UNSUPPORTED,probe(WrongManager::class.java).status)
        assertNull(probe(modes=WrongModes::class.java).driver)
        assertNull(BigmeFullRefresh.detect().driver)
    }
    @Test fun permissionOrLinkageFailureIsNotReportedAsSuccess() {
        val p=probe();Manager.fail=true
        assertFalse(p.driver!!.request());assertEquals(1,Manager.calls)
        assertEquals(BigmeApiStatus.UNSUPPORTED,BigmeFullRefresh.detect { throw NoClassDefFoundError() }.status)
    }
    @Test fun nativeSettingRequiresOptInAndFirmwareMatchWithoutChangingOtherSettings() {
        val first=DisplayPreferences(context,"14:1.7.0");assertFalse(first.bigmeNative)
        first.mode(DisplayMode.COLOR);first.bigmeNative(true)
        assertTrue(DisplayPreferences(context,"14:1.7.0").bigmeNative)
        assertFalse(DisplayPreferences(context,"14:1.7.1").bigmeNative)
        first.bigmeNative(false);assertFalse(DisplayPreferences(context,"14:1.7.0").bigmeNative)
        assertEquals(DisplayMode.COLOR,DisplayPreferences(context).mode)
    }
    private fun withRefresh(action: (DisplayPreferences,EinkRefresh)->Unit) {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val a=controller.get();val view=android.widget.FrameLayout(a);a.setContentView(view)
        val prefs=DisplayPreferences(context);prefs.mode(DisplayMode.COLOR)
        val refresh=EinkRefresh(prefs,probe());refresh.attach(view);refresh.foreground(true)
        try { action(prefs,refresh) } finally { refresh.dispose();controller.pause().stop().destroy() }
    }
    private fun advance(ms: Long=1200) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    @Test fun availableApiIsNotCalledUntilOptInAndStopsWhenNormalOrBackground() = withRefresh { prefs,r ->
        advance();assertEquals(0,Manager.calls);assertEquals(1,r.completed)
        prefs.bigmeNative(true);r.changed();advance()
        assertEquals(1,Manager.calls);assertEquals(1,r.nativeRequests);assertEquals(2,r.completed)
        r.foreground(false);r.request();advance(3000);assertEquals(1,Manager.calls)
        prefs.mode(DisplayMode.NORMAL);r.foreground(true);advance();assertEquals(1,Manager.calls)
        prefs.mode(DisplayMode.MONO);prefs.cleaning(false);r.changed();advance();assertEquals(1,Manager.calls)
    }
    @Test fun failedRequestDisablesPersistentlyFallsBackAndDoesNotRetryInALoop() = withRefresh { prefs,r ->
        prefs.bigmeNative(true);Manager.fail=true;r.changed();advance()
        assertEquals(1,Manager.calls);assertEquals(0,r.nativeRequests);assertEquals(1,r.completed)
        assertFalse(prefs.bigmeNative);assertFalse(DisplayPreferences(context).bigmeNative)
        assertEquals(BigmeApiStatus.FAILED,r.bigmeStatus)
        r.request();advance();assertEquals(1,Manager.calls);assertEquals(2,r.completed)
        advance(20000);assertEquals(2,r.completed)
    }
    @Test fun diagnosticIsAllowlistedAndDoesNotTreatSubmissionAsPanelConfirmation() {
        val d=DisplayDiagnostic(DisplayMode.COLOR,true,BigmeApiStatus.AVAILABLE,true,2)
        val json=Json.parseToJsonElement(Diagnostics.report(LocalState(),34,"alpha21",d)).jsonObject
        assertEquals(setOf("mode","cleaning","bigmeApi","bigmeEnabled","nativeRequests"),json["display"]!!.jsonObject.keys)
        val raw=json.toString()
        assertFalse(raw.contains("serial",true));assertFalse(raw.contains("firmware",true));assertFalse(raw.contains("confirmed",true))
    }
}
