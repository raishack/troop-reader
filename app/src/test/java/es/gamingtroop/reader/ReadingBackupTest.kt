package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class ReadingBackupTest {
    private val account=Account("https://fixture.invalid",1,"fixture","private-auth-value","private-refresh-value")
    private val c=Chapter(10,1,pages=20,format=3,lastModifiedUtc="edition1")
    private fun state() = LocalState(chapters=mapOf(10 to SavedChapter(c,Series(1,"Libro",1,3),true,true,totalBytes=3000)),
        progress=mapOf(10 to Progress(1,1,1,10,5)),profiles=mapOf(1 to ReaderProfile(fontSize=24)),
        favorites=setOf(1),collections=listOf(PersonalCollection("list","Pendientes",setOf(1))),
        notes=listOf(BookNote("note",10,c,0,0,0,5,"Texto","Nota",1)),smartDownloads=SmartDownloads(enabled=true))
    private fun roundTrip(s: LocalState): ReadingBackup {
        val out=java.io.ByteArrayOutputStream();Backups.write(account,s,out)
        assertFalse(out.toString().contains("private-auth"));assertFalse(out.toString().contains("private-refresh"))
        return Backups.read(account,out.toByteArray().inputStream())
    }
    @Test fun backupContainsReadingDataButNoCredentialsOrReadyPayload() {
        val backup=roundTrip(state());assertEquals(state().notes,backup.state.notes)
        assertFalse(backup.state.chapters.getValue(10).ready);assertFalse(backup.state.smartDownloads.enabled)
        assertEquals(0,backup.state.chapters.getValue(10).totalBytes)
    }
    @Test fun restoredPositionsRequireReviewEvenWithLocalPriorityEnabled() {
        val backup=roundTrip(state());val current=LocalState(settings=ReadingSettings(preferLocalChanges=true))
        val merged=Backups.merge(current,backup);val pending=merged.pending.getValue(10)
        assertEquals(5,pending.local.pageNum);assertTrue(pending.restored)
        assertEquals(SyncDecision.CONFLICT,SyncPolicy.decide(pending,pending.local.copy(pageNum=12),true))
        assertEquals(SyncDecision.ACKNOWLEDGE,SyncPolicy.decide(pending,pending.local,true))
    }
    @Test fun cleanRestoredMarkersRemainPendingUntilComparedWithTheServer() {
        val marker=Bookmark("old",10,"Marca",2,null,1,c,remote=RemoteBookmark(20,2,"Marca"),dirty=false)
        val merged=Backups.merge(LocalState(),roundTrip(state().copy(bookmarks=listOf(marker))))
        assertTrue(merged.bookmarks.single().dirty);assertTrue(merged.bookmarks.single().restored)
    }
    @Test fun repeatedRestoreNeverOverwritesExistingProgressNotesOrDownloads() {
        val backup=roundTrip(state())
        val current=state().copy(progress=mapOf(10 to Progress(1,1,1,10,9)),notes=state().notes.map { it.copy(comment="Actual") })
        val merged=Backups.merge(current,backup)
        assertEquals(9,merged.progress.getValue(10).pageNum);assertTrue(merged.chapters.getValue(10).ready)
        assertEquals("Actual",merged.notes.single().comment);assertTrue(merged.pending.isEmpty())
        assertEquals(merged,Backups.merge(merged,backup))
    }
    @Test fun wrongAccountAndDamagedBackupAreRejectedBeforeMerge() {
        val raw=codec.encodeToString(ReadingBackup(accountKey=account.key,state=state()))
        assertThrows(IllegalArgumentException::class.java) { Backups.read(account.copy(id=2),raw.byteInputStream()) }
        assertThrows(Exception::class.java) { Backups.read(account,"{broken".byteInputStream()) }
        val damaged=state().copy(progress=mapOf(10 to Progress(chapterId=10,pageNum=999)))
        assertThrows(IllegalArgumentException::class.java) { Backups.read(account,codec.encodeToString(ReadingBackup(accountKey=account.key,state=damaged)).byteInputStream()) }
    }
    @Test fun newEditionKeepsOldNotesOutAndRejectsUnsafeProfileValues() {
        val current=state().copy(chapters=mapOf(10 to state().chapters.getValue(10).copy(chapter=c.copy(lastModifiedUtc="edition2"))),notes=emptyList(),progress=emptyMap())
        assertTrue(Backups.merge(current,roundTrip(state())).notes.isEmpty())
        val bad=state().copy(profiles=mapOf(1 to ReaderProfile(fontSize=999)))
        assertThrows(IllegalArgumentException::class.java) { roundTrip(bad) }
    }
}
