package es.gamingtroop.reader
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28], application=android.app.Application::class)
class Alpha19Test {
    private val chapter=Chapter(1,pages=5,format=3)
    private val series=Series(1,"Private title",format=3)
    @Test fun partialAvailabilityDoesNotPretendTheWholeBookIsPresent() {
        val b=SavedChapter(chapter,series,true,downloadedPages=2,downloadRequested=false,state="Pausada")
        assertTrue(b.readable);assertTrue(b.hasPage(1));assertFalse(b.hasPage(2));assertFalse(b.ready)
        assertFalse(b.copy(state="Eliminado del dispositivo").readable)
        assertFalse(b.copy(downloadedPages=0).readable)
        assertEquals(5,b.copy(downloadedPages=999).readablePages)
    }
    @Test fun diagnosticHasNoFreeFormOrIdentifyingContent() {
        val b=SavedChapter(chapter,series,true,state="Error HTTP 400 · private-title token=sentinel path=/private/file")
        val s=LocalState(chapters=mapOf(1 to b),syncMessage="sensitive-session",coverIssues=mapOf("secret" to "not-for-export"))
        val report=Diagnostics.report(s,35,"alpha19")
        assertTrue(report.contains("HTTP 400"))
        for(secret in listOf("Private","private","sentinel","sensitive","not-for-export","secret","title")) assertFalse(report.contains(secret))
    }
    @Test fun partialEpubSearchDoesNotRequireMissingSections() = runBlocking {
        val root=kotlin.io.path.createTempDirectory().toFile();val store=Store(root)
        val b=SavedChapter(chapter,series,true,downloadedPages=1)
        File(store.chapterDir(1).apply { mkdirs() },"0.html").writeText("<p>Un tesoro disponible.</p>")
        assertEquals(1,EpubText.search(store,b,"tesoro").size)
        root.deleteRecursively();Unit
    }
    @Test fun legacyStateDefaultsAndRoundTripKeepHardwareControls() {
        val old=codec.decodeFromString<LocalState>("{}")
        assertFalse(old.settings.volumeKeys);assertEquals("auto",old.settings.orientation)
        val updated=old.copy(settings=old.settings.copy(volumeKeys=true,orientation="landscape"))
        assertEquals(updated,codec.decodeFromString<LocalState>(codec.encodeToString(updated)))
    }
    @Test fun shelfDtosAcceptNullableMetadataAndKeepOrder() {
        val entries=codec.decodeFromString<List<ServerListItem>>("""[{"id":2,"order":2,"chapterId":22,"seriesId":3,"seriesName":null,"chapter":null},{"id":1,"order":1,"chapterId":21,"seriesId":3}]""")
        assertEquals(listOf(1,2),entries.sortedBy { it.order }.map { it.id })
        assertEquals("",entries.first().seriesName)
    }
}
