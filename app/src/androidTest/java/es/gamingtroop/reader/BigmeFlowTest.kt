package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Fake driver verifies app routing/UI only, never physical Bigme compatibility. */
@RunWith(AndroidJUnit4::class)
class BigmeFlowTest {
    @get:Rule val permission=androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui=createEmptyComposeRule()
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>?=null
    private var calls=0
    private lateinit var refresh: EinkRefresh
    @Before fun reset() { context.getSharedPreferences("display",0).edit().clear().commit() }
    @After fun close() { scenario?.close();context.getSharedPreferences("display",0).edit().clear().commit() }
    private fun launch(status: BigmeApiStatus=BigmeApiStatus.AVAILABLE, succeeds: Boolean=true) {
        context.getSharedPreferences("display",0).edit().putString("mode","COLOR").commit()
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
        scenario!!.onActivity { a ->
            a.einkRefresh.dispose()
            refresh=EinkRefresh(a.displayPreferences,BigmeProbe(status,if(status==BigmeApiStatus.AVAILABLE) RefreshRequest { calls++;succeeds } else null))
            a.einkRefresh=refresh;refresh.foreground(true)
            a.setContent { DisplayTheme(a.displayPreferences,refresh) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(),flingBehavior=displayFling())) { DisplaySettings() }
            } }
        }
        ui.waitForIdle()
    }
    private fun toggle() = ui.onNodeWithContentDescription("Refresco Bigme experimental")
    @Test fun nativeIsOptInAndCanBeDisabledWithoutLosingTheColorProfile() {
        launch();toggle().performScrollTo().assertIsOff();assertEquals(0,calls)
        toggle().performClick();ui.waitUntil(6000) { calls>0 }
        toggle().assertIsOn()
        val before=calls
        ui.onNodeWithText("Limpiar pantalla ahora").performScrollTo().performClick()
        ui.waitUntil(6000) { calls>before }
        toggle().performScrollTo().performClick();ui.waitForIdle()
        val stopped=calls;Thread.sleep(1200);assertEquals(stopped,calls)
        scenario!!.onActivity { assertFalse(it.displayPreferences.bigmeNative);assertEquals(DisplayMode.COLOR,it.displayPreferences.mode) }
    }
    @Test fun deniedNativeRefreshReportsFailureAndRestoresCompatibility() {
        launch(succeeds=false);toggle().performScrollTo().performClick()
        ui.waitUntil(6000) { refresh.bigmeStatus==BigmeApiStatus.FAILED }
        ui.onNodeWithText(BigmeApiStatus.FAILED.label).performScrollTo().assertIsDisplayed()
        ui.waitUntil(6000) { refresh.completed>0 }
        assertEquals(0,refresh.nativeRequests);assertEquals(1,calls)
        ui.onNodeWithText("Limpiar pantalla ahora").performScrollTo().performClick();Thread.sleep(1500)
        assertEquals(1,calls);assertFalse(DisplayPreferences(context).bigmeNative)
    }
    @Test fun absentApiDoesNotOfferTheExperimentalSwitchAndHelpIsHonest() {
        launch(BigmeApiStatus.ABSENT)
        ui.onNodeWithText("Refresco Bigme experimental").assertDoesNotExist()
        ui.onNodeWithText("Compatibilidad y refresco").performScrollTo().performClick()
        ui.onNodeWithText(BigmeApiStatus.ABSENT.label).assertExists()
        ui.onNodeWithText("Entendido").assertIsDisplayed()
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=java.io.File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir(null)!!.path).apply { mkdirs() }
        java.io.File(dir,"alpha21-compatibility.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };screenshot.recycle()
        ui.onNodeWithText("Entendido").performClick();assertEquals(0,calls)
    }
}
