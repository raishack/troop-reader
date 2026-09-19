package es.gamingtroop.reader

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReaderNavigatorTest {
    @Test fun legacyMigrationOnlyChangesNavigationAndRunsOnce() {
        val root = kotlin.io.path.createTempDirectory("reader-default").toFile()
        try {
            val old = LocalState(settings = ReadingSettings(pageTurnMode="swipe",pageTurnEffect="curl",rtl=true,preferLocalChanges=true),
                progress=mapOf(7 to Progress(chapterId=7,pageNum=3)),lastSync=42)
            val json = codec.encodeToJsonElement(old).jsonObject
            val settings = JsonObject(json.getValue("settings").jsonObject - "pageTurnDefaultVersion")
            File(root,"state.json").writeText(JsonObject(json + ("settings" to settings)).toString())
            File(root,"page.img").writeText("untouched")
            val store=Store(root)
            assertEquals(old.copy(settings=old.settings.copy(pageTurnMode="edges")),store.get())
            assertEquals("untouched",File(root,"page.img").readText())
            assertEquals(store.get(),Store(root).get())
            store.update { it.copy(settings=it.settings.copy(pageTurnMode="swipe")) }
            assertEquals("swipe",Store(root).get().settings.pageTurnMode)
            assertEquals("curl",Store(root).get().settings.pageTurnEffect)
        } finally { root.deleteRecursively() }
    }
    @Test fun tocFiltersInvalidTargetsWithoutLosingValidChildren() {
        val index=readerToc(listOf(Toc("Heading",page=-1,children=listOf(Toc("Valid","#c",2))),Toc("Beyond",page=99)),4)
        assertEquals(1,index.size);assertEquals(1,index[0].depth);assertEquals(2,index[0].entry.page)
        assertEquals("#c",index[0].entry.anchor())
        assertEquals("#next",Toc("Next","chapter.xhtml#next",1).anchor())
    }
    @Test fun activeChapterRecognizesAnchorAndDoesNotInventLastChapterOnSamePage() {
        val index=readerToc(listOf(Toc("First","#one",0),Toc("Second","#two",0),Toc("Third","#three",2)),4)
        assertEquals(1,activeToc(index,0,"id(\"two\")"))
        assertEquals(1,activeToc(index,0,"#two"))
        assertEquals(0,activeToc(index,0,"id(\"paragraph\")"))
        assertEquals(2,activeToc(index,3,""))
    }
}
