package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],application=android.app.Application::class)
class PersonalFeaturesTest {
    @get:Rule val temp=TemporaryFolder()
    private val series=Series(1,"Prueba",1,3)
    private val chapter=Chapter(10,1,pages=3,format=3,lastModifiedUtc="edition1")
    private fun store(): Store = Store(temp.newFolder()).apply { update { LocalState(chapters=mapOf(10 to SavedChapter(chapter,series,true,ready=true))) } }
    @Test fun profilesNeverCaptureNetworkOrSyncPolicyAndCanResetToGlobal() {
        val s=store();s.update { it.copy(settings=it.settings.copy(wifiOnly=false,preferLocalChanges=true)) }
        s.useSeriesProfile(1,true);s.readerSettings(1) { it.copy(fontSize=26,theme="sepia",pageTurnEffect="curl") }
        assertEquals(19,s.get().readerSettings(2).fontSize);assertEquals(26,s.get().readerSettings(1).fontSize)
        s.update { it.copy(settings=it.settings.copy(wifiOnly=true,preferLocalChanges=false)) }
        assertTrue(s.get().readerSettings(1).wifiOnly);assertFalse(s.get().readerSettings(1).preferLocalChanges)
        assertEquals("sepia",Store(s.root).get().readerSettings(1).theme)
        s.useSeriesProfile(1,false);assertEquals(19,s.get().readerSettings(1).fontSize)
    }
    @Test fun followingEstablishesBaselineThenOnlyAnnouncesNewIdsOnce() {
        val first=FollowedSeries(series).observed(listOf(chapter),1)
        assertTrue(first.unread.isEmpty())
        val next=first.observed(listOf(chapter,chapter.copy(id=11)),2)
        assertEquals(listOf(11),next.unread.map { it.id })
        assertEquals(1,next.observed(listOf(chapter,chapter.copy(id=11)),3).unread.size)
        val seen=next.copy(unread=emptyList()).observed(listOf(chapter.copy(id=11)),4)
        assertTrue(seen.observed(listOf(chapter,chapter.copy(id=11)),5).unread.isEmpty())
    }
    @Test fun collectionsPersistMembershipWithoutDuplicatingNames() {
        val s=store();val id=s.collection(" Pendientes ");assertEquals(id,s.collection("pendientes"))
        s.membership(id,1,true);s.favorite(1,true)
        val reloaded=Store(s.root).get();assertEquals(setOf(1),reloaded.collections.single().seriesIds);assertEquals(setOf(1),reloaded.favorites)
        s.membership(id,1,false);assertTrue(s.get().collections.single().seriesIds.isEmpty());assertTrue(s.get().chapters.getValue(10).ready)
    }
    @Test fun searchReadsAllLocalSectionsAndOffsetsMatchNestedTextNodes() = runBlocking {
        val s=store();val saved=s.get().chapters.getValue(10)
        s.chapterDir(10).mkdirs()
        File(s.chapterDir(10),"0.html").writeText("<div><h1>Inicio</h1><p>El <b>mar</b> azul &amp; el mar.</p></div>")
        File(s.chapterDir(10),"1.html").writeText("<div><p>Un MAR lejano</p></div>")
        File(s.chapterDir(10),"2.html").writeText("<div><p>Nada</p></div>")
        val hits=EpubText.search(s,saved,"mar")
        assertEquals(listOf(0,0,1),hits.map { it.page });assertEquals(3,hits[0].start);assertEquals("@text:1:3:6",hits[0].anchor)
        assertEquals("El mar azul & el mar.",EpubText.blocks(File(s.chapterDir(10),"0.html").readText(),0)[1].text)
        assertEquals(2,EpubText.search(s,saved,"mar",2).size)
        assertTrue(EpubText.search(s,saved,"m").isEmpty())
    }
    @Test fun notePersistsExactQuoteAndCanExportWithoutHtmlExecution() {
        val s=store();val n=BookNote("n",10,chapter,0,0,0,5,"Texto","<script>comentario</script>",1)
        s.saveNote(n);assertEquals(n,Store(s.root).get().notes.single())
        s.saveNote(n.copy(comment="corregido"));assertEquals(1,s.get().notes.size)
        assertTrue(notesMarkdown(s.get()).contains("> Texto\n\ncorregido"))
        assertThrows(IllegalArgumentException::class.java) { s.saveNote(n.copy(edition=chapter.copy(lastModifiedUtc="edition2"))) }
    }
    @Test fun speechChunksKeepSurrogatesIntactAndAllText() {
        val text="El mar. ".repeat(1500)+"😀".repeat(1500)
        val chunks=EpubText.speechChunks(text)
        assertTrue(chunks.all { it.length<=3000 && !Character.isLowSurrogate(it.first()) && !Character.isHighSurrogate(it.last()) })
        assertEquals(text.filterNot { it.isWhitespace() },chunks.joinToString("").filterNot { it.isWhitespace() })
    }
    @Test fun statisticsStopInBackgroundAndRemainIdempotentPerPageAndCompletion() {
        val s=store();var clock=0L;val tracker=ReadingTracker(s,10) { clock }
        tracker.start(0);clock=5000;tracker.sample();clock=8000;tracker.stop();clock=90000;tracker.sample()
        assertEquals(8000,s.get().statistics.days.values.sumOf { it.millis })
        tracker.start(1);clock=95000;tracker.stop();s.readingCompleted(10);s.readingCompleted(10)
        assertEquals(2,s.get().statistics.days.values.flatMap { it.visited }.distinct().size)
        assertEquals(1,s.get().statistics.days.values.flatMap { it.completed }.distinct().size)
        s.update { it.copy(statistics=it.statistics.copy(enabled=false)) };val before=s.get().statistics
        s.readingTime(10,2,1000);assertEquals(before,s.get().statistics)
    }
    @Test fun smartNextDoesNotSkipMissingPartOrVolume() {
        val c1=chapter.copy(format=1);val c2=c1.copy(id=11);val c3=c1.copy(id=12,volumeId=2)
        val manga=series.copy(format=1)
        val s=LocalState(chapters=mapOf(10 to SavedChapter(c1,manga,false,true)),
            catalog=mapOf(1 to listOf(Volume(1,"1",listOf(c1,c2)),Volume(2,"2",listOf(c3)))))
        assertEquals(listOf(11),smartNextParts(s,10).map { it.id })
        val ready=s.copy(chapters=s.chapters+(11 to SavedChapter(c2,manga,false,true)))
        assertEquals(listOf(12),smartNextParts(ready,10).map { it.id })
    }
}
