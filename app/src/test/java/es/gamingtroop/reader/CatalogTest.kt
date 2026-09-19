package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private val manga = Series(1,"Manga",1,1)
    @Test fun actualVolumesAreNotFlattenedAndSelectingAllIncludesEveryPart() {
        val volumes = listOf(Volume(10,"1",listOf(Chapter(1,pages=8),Chapter(2,pages=9))),Volume(20,"2",listOf(Chapter(3,pages=10))))
        val units = manga.readingUnits(volumes,emptyList())
        assertEquals(listOf("Tomo 1","Tomo 2"),units.map { it.title })
        assertEquals(17L,units[0].pages)
        assertEquals(listOf(1,2,3),selectedParts(units,units.map { it.key }.toSet()).map { it.id })
        assertEquals("Tomo 1 · Parte 2",units[0].chapters[1].label)
        assertEquals(10,units[0].chapters[0].volumeId)
        assertEquals(CoverRef("volume",10),units[0].cover)
    }
    @Test fun booksAreIndividualEvenWhenKavitaGroupsThemInOneVolume() {
        val series=Series(2,"Libros",4,4)
        val volumes=listOf(Volume(11,"1",listOf(Chapter(1,titleName="El libro",pages=5),Chapter(2,pages=7))))
        val units=series.readingUnits(volumes,listOf(Library(4,"Lecturas",2)))
        assertEquals(2,units.size);assertEquals("El libro",units[0].title)
        assertTrue(units[1].title.startsWith("Libro"));assertEquals("libros",series.unitsName(listOf(Library(4,"Lecturas",2))))
        assertEquals(CoverRef("chapter",1),units[0].cover)
    }
    @Test fun pdfComicsUseLibraryTypeNotFileExtension() {
        val series=Series(2,"PDF Comic",4,4)
        assertEquals("tomos",series.unitsName(listOf(Library(4,"Comics",1))))
        assertEquals("libros",series.unitsName(listOf(Library(4,"Novelas",4))))
    }
    @Test fun looseFilesAndSpecialsAreNotCombinedIntoFictitiousVolumeZero() {
        val units=manga.readingUnits(listOf(Volume(11,"0",listOf(Chapter(1,range="1"),Chapter(2,titleName="Especial")))),emptyList())
        assertEquals(2,units.size);assertEquals("Especial",units[1].title)
        assertEquals(CoverRef("chapter",1),units[0].cover)
    }
    @Test fun selectingMoreThanTwoHundredVolumesNeverSilentlyTruncates() {
        val units=manga.readingUnits((1..251).map { Volume(it,"$it",listOf(Chapter(it,pages=1))) },emptyList())
        assertEquals(251,selectedParts(units,units.map { it.key }.toSet()).size)
    }
    @Test fun partiallyDownloadedVolumeRemainsSelectableAndOpensFirstUnreadPart() {
        val parts=listOf(Chapter(1,pages=8),Chapter(2,pages=9))
        val unit=manga.readingUnits(listOf(Volume(5,"1",parts)),emptyList()).single()
        val saved=parts.associate { it.id to SavedChapter(it,manga,false,ready=it.id==1) }
        val state=LocalState(chapters=saved,progress=mapOf(1 to Progress(chapterId=1,pageNum=8)))
        assertFalse(unit.ready(state));assertEquals(2,unit.next(state))
        assertTrue(unit.ready(state.copy(chapters=saved.mapValues { it.value.copy(ready=true) })))
    }
    @Test fun olderDownloadsWithoutChapterFormatUseTheLibraryForTheirLabel() {
        assertEquals("Libro 2", Chapter(3,range="2").labelFor(Series(1,"Series",1,3),emptyList()))
        assertEquals("Tomo 2", Chapter(3,range="2").labelFor(Series(1,"Series",1,4),listOf(Library(1,"Comics",1))))
    }
    @Test fun oldStateWithoutCatalogStillOpensSavedBooksAndPreservesPending() {
        val s=codec.decodeFromString<LocalState>("""{"chapters":{"1":{"chapter":{"id":1,"pages":5},"series":{"id":1,"name":"Manga"},"epub":false}}}""")
        assertEquals(1,manga.cachedVolumes(s).size)
        assertFalse(s.settings.preferLocalChanges)
    }
}
