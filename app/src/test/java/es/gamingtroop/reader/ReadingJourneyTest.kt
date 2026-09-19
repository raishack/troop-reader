package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingJourneyTest {
    private val series = Series(1,"Obra",1,1)
    private val parts = listOf(Chapter(101,10,pages=2), Chapter(102,10,pages=2), Chapter(201,20,pages=3))
    private fun state() = LocalState(series = listOf(series), libraries = listOf(Library(1,"Manga",0)),
        catalog = mapOf(1 to listOf(Volume(10,"1",parts.take(2)),Volume(20,"2",parts.takeLast(1)))),
        chapters = parts.associate { it.id to SavedChapter(it,series,false,ready=true) })
    @Test fun seriesStartsWithoutOpeningVolumeAndResumesMostRecentUnread() {
        val s = state(); assertEquals(101,series.readingStart(s))
        val readFirst = s.copy(progress = mapOf(101 to Progress(chapterId=101,pageNum=2)))
        assertEquals(102,series.readingStart(readFirst))
        val resume = readFirst.copy(chapters=readFirst.chapters.mapValues { (id,v) -> if(id == 201) v.copy(lastReadAt=100) else v })
        assertEquals(201,series.readingStart(resume))
    }
    @Test fun continuousReadingKeepsCatalogOrderAndDoesNotSkipGapsOrDifferentEditions() {
        val s=state()
        assertEquals(102,series.successor(s,101).chapter!!.id)
        val incomplete=s.copy(chapters=s.chapters - 102)
        assertFalse(series.successor(incomplete,101).available)
        assertEquals(102,series.successor(incomplete,101).chapter!!.id)
        val changed=s.copy(chapters=s.chapters + (102 to s.chapters.getValue(102).copy(chapter=parts[1].copy(pages=20))))
        assertFalse(series.successor(changed,101).available)
        assertNull(series.successor(s,201).chapter)
        assertNull(series.successor(s,999).chapter)
    }
    @Test fun volumeIsReadOnlyWhenEveryPartIsCompletedNotJustLastPageVisible() {
        val s=state();val unit=series.localUnits(s).first()
        assertFalse(unit.isRead(s.copy(progress=mapOf(101 to Progress(chapterId=101,pageNum=2),102 to Progress(chapterId=102,pageNum=1)))))
        assertTrue(unit.isRead(s.copy(progress=mapOf(101 to Progress(chapterId=101,pageNum=2),102 to Progress(chapterId=102,pageNum=2)))))
    }
    @Test fun pendingLocalReadingWinsForDisplayUntilConflictResolved() {
        val s=state();val base=Progress(chapterId=101,pageNum=2)
        val pending=s.copy(progress=mapOf(101 to base),pending=mapOf(101 to Pending(base,base.copy(pageNum=0))))
        assertFalse(pending.isRead(parts.first()))
    }
    @Test fun removalSelectionIncludesPartsNotUnrelatedWorksAndKeepsDeletedHistoryOut() {
        val s=state();assertEquals(setOf(101,102),series.localUnits(s).first().localIds(s))
        val gone=s.copy(chapters=s.chapters.mapValues { (id,v) -> if(id in setOf(101,102)) v.copy(ready=false,state="Eliminado del dispositivo") else v })
        assertEquals(1,series.localUnits(gone).size)
        assertEquals(201,series.readingStart(gone))
    }
    @Test fun eachServerLibraryTypeHasDistinctCategoryAndNewDefaultsPreserveConflictPolicy() {
        val expected=listOf(ReadingCategory.MANGA,ReadingCategory.COMIC,ReadingCategory.BOOK,ReadingCategory.IMAGES,ReadingCategory.NOVEL,ReadingCategory.COMIC_VINE)
        for(i in 0..5) assertEquals(expected[i],series.category(listOf(Library(1,"Tipo",i))))
        assertEquals(ReadingCategory.OTHER,series.category(listOf(Library(1,"Futuro",22))))
        val old=codec.decodeFromString<ReadingSettings>("""{"wifiOnly":false,"preferLocalChanges":true}""")
        assertTrue(old.autoAdvance);assertTrue(old.preferLocalChanges);assertFalse(old.wifiOnly)
        assertFalse(ReadingSettings().preferLocalChanges)
    }
    @Test fun legacyDownloadsKeepKnownVolumeGroupingAndSupplementStaleCatalogWithoutLosingPayloads() {
        val s=state();val old=s.copy(catalog=emptyMap(),chapters=s.chapters.mapValues { (id,v) ->
            v.copy(chapter=v.chapter.copy(displayTitle=if(id < 200) "Tomo 1 · Parte $id" else "Tomo 2")) })
        assertEquals(2,series.localUnits(old).size)
        assertEquals(setOf(101,102),series.localUnits(old).first().localIds(old))
        val stale=old.copy(catalog=mapOf(1 to listOf(Volume(10,"1",parts.take(2)))))
        assertEquals(3,series.orderedParts(stale).size)
    }
    @Test fun staleCatalogMergesSavedPartsOfExistingVolumeWithoutDuplicateKeys() {
        val s=state().copy(catalog=mapOf(1 to listOf(Volume(10,"1",parts.take(1)))))
        val units=series.localUnits(s)
        assertEquals(units.size,units.map { it.key }.toSet().size)
        assertEquals(listOf(101,102),units.first().chapters.map { it.id })
    }

    @Test fun previousPartDoesNotSkipMissingDownloadsOrWrapAround() {
        val s=state()
        assertNull(series.adjacent(s,101,-1).chapter)
        assertEquals(102,series.adjacent(s,201,-1).chapter!!.id)
        assertFalse(series.adjacent(s.copy(chapters=s.chapters-102),201,-1).available)
        assertNull(series.adjacent(s,999,-1).chapter)
    }
    @Test fun continuousImageDimensionsAreBoundedForVeryTallStrips() {
        val b=ImageBounds(100,20000)
        assertEquals(30000,b.displayHeight(1080))
        val size=b.decodeSize(1080)
        assertTrue(size.first in 1..2048);assertTrue(size.second in 1..4096)
        assertFalse(ImageBounds(0,3).valid)
    }

}
