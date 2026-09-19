package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test

class OfflineIndexTest {
    private fun state(): LocalState {
        val names=listOf("Álbum","Noche","Islas")
        val series=names.mapIndexed { i,n -> Series(i+1,n,1,1) }
        val books=series.mapIndexed { i,s -> SavedChapter(Chapter(s.id*100,pages=10),s,false,ready=true,
            totalBytes=(i+1)*100L,lastReadAt=(3-i)*10L) }
        return LocalState(series=series,chapters=books.associateBy { it.chapter.id },progress=mapOf(
            100 to Progress(chapterId=100,pageNum=10),200 to Progress(chapterId=200,pageNum=3)))
    }
    @Test fun browseOrdersSizeTitleAndHistoryWithoutLosingWorks() {
        val state=state();val index=state.offlineWorks()
        fun ids(sort:OfflineSort)=index.browse("","",emptyList(),OfflineFilter.ALL,sort).map { it.series.id }
        assertEquals(listOf(1,2,3),ids(OfflineSort.RECENT))
        assertEquals(listOf(1,3,2),ids(OfflineSort.TITLE))
        assertEquals(listOf(3,2,1),ids(OfflineSort.SIZE))
        assertEquals(1,index.browse("album","",emptyList(),OfflineFilter.ALL,OfflineSort.TITLE).single().series.id)
    }
    @Test fun localReadingFiltersUsePendingPositionsAndOnlyStoredContents() {
        val s=state();val index=s.offlineWorks()
        assertEquals(listOf(1),index.browse("","",emptyList(),OfflineFilter.READ,OfflineSort.TITLE).map { it.series.id })
        assertEquals(setOf(2,3),index.browse("","",emptyList(),OfflineFilter.READING,OfflineSort.TITLE).map { it.series.id }.toSet())
        val old=s.progress.getValue(100)
        val changed=s.copy(pending=mapOf(100 to Pending(old,old.copy(pageNum=0))))
        assertTrue(changed.offlineWorks().browse("","",emptyList(),OfflineFilter.READ,OfflineSort.TITLE).isEmpty())
    }
    @Test fun cleanupExcludesPartialsUnreadAndOtherWorksButIncludesPendingCompletion() {
        val s=state();val old=s.progress.getValue(200)
        val pending=s.copy(pending=mapOf(200 to Pending(old,old.copy(pageNum=10))))
        assertEquals(setOf(100,200),pending.readDownloads())
        assertEquals(setOf(200),pending.readDownloads(2))
        val partial=pending.copy(chapters=pending.chapters.mapValues { (id,b) -> if(id==200)b.copy(ready=false,state="En cola") else b })
        assertEquals(setOf(100),partial.readDownloads())
    }
    @Test fun removedPayloadsDoNotRemainInIndexAndMetadataUsesLatestTitle() {
        val s=state();val changed=s.copy(series=s.series.map { if(it.id==1)it.copy(name="Título nuevo") else it },
            chapters=s.chapters.mapValues { (id,b) -> if(id==300)b.copy(ready=false,state="Eliminado del dispositivo") else b })
        assertEquals(2,changed.offlineWorks().size)
        assertEquals("Título nuevo",changed.offlineWorks().first { it.series.id==1 }.series.name)
    }
}
