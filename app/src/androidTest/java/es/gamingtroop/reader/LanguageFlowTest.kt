package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
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

@RunWith(AndroidJUnit4::class)
class LanguageFlowTest {
    @get:Rule val permission=androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui=createEmptyComposeRule()
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val repo get()=context.repository()
    private var scenario: ActivityScenario<MainActivity>?=null
    @Before fun setup() {
        require(repo.active()==null || repo.active()!!.server=="https://demo.invalid" || repo.active()!!.server.startsWith("http://localhost:"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.voicePlayback().stop();AppLanguage.select(context,"es") }
        WorkManager.getInstance(context).cancelAllWork().result.get();repo.vault.clear();Demo.install(context)
        context.getSharedPreferences("display",0).edit().clear().commit()
    }
    @After fun finish() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.select(context,"es") }
        context.getSharedPreferences("display",0).edit().clear().commit()
    }
    private fun launch() { scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
    private fun awaitText(value: String) = ui.waitUntil(10000) { ui.onAllNodesWithText(value).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    private fun shot(name: String) {
        ui.waitForIdle()
        val dir=java.io.File(context.getExternalFilesDir(null),"language-checks").apply { mkdirs() }
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun switchInSettingsPersistsAndDoesNotRewriteReadingData() {
        val store=repo.store(repo.active()!!.key)
        val before=codec.encodeToString(LocalState.serializer(),store.get())
        launch();awaitText("Descargas")
        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNodeWithTag("language-en").performScrollTo().performClick()
        awaitText("Settings");ui.onNodeWithText("App language").assertExists();shot("settings-en")
        assertEquals("en",AppLanguage.selected(context))
        assertEquals(before,codec.encodeToString(LocalState.serializer(),store.get()))
        scenario!!.close();scenario=null;launch();awaitText("Downloads")
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithTag("language-es").performScrollTo().performClick()
        awaitText("Ajustes");assertEquals("es",AppLanguage.selected(context))
        assertEquals(before,codec.encodeToString(LocalState.serializer(),store.get()))
    }
    @Test fun englishReaderOptionsAndBookContentsKeepTheirOwnLanguage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.select(context,"en") }
        launch();awaitText("Downloads");ui.onNodeWithText("Downloads").performClick()
        ui.onNodeWithTag("download-list").performScrollToNode(hasTestTag("offline-series-2"))
        ui.onNodeWithTag("read-series-2").performClick()
        ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("reader-content") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Page ready")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("reader-content").performTouchInput { click(center) }
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Reading options").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Reading options").performClick()
        ui.onNodeWithText("Reading mode").assertExists();shot("reader-en")
    }
    @Test fun englishEinkSettingsAreStaticAndLocalized() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.select(context,"en") }
        context.getSharedPreferences("display",0).edit().putString("mode","COLOR").putBoolean("clean",false).commit()
        launch();awaitText("Downloads");ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Screen type").performScrollTo().assertExists()
        ui.onNodeWithText("Colour e-ink").assertExists();shot("eink-en")
    }
    @Test fun languageCanBeChangedBeforeSigningIn() {
        repo.vault.clear();launch();awaitText("Entrar en mi biblioteca")
        ui.onNodeWithTag("language-en").performScrollTo().performClick()
        awaitText("App language");ui.onNodeWithText("Open my library").performScrollTo().assertExists();shot("login-en")
        assertNull(repo.active())
    }
}
