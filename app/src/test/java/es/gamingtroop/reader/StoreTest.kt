package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],application=android.app.Application::class)
class StoreTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun populated(): Store {
        val store=Store(tmp.newFolder()); val chapter=Chapter(42,7,pages=50)
        store.update { it.copy(chapters=mapOf(42 to SavedChapter(chapter,Series(2,"Demo",3),false,ready=true)), progress=mapOf(42 to Progress(3,2,7,42,1))) }
        return store
    }
    @Test fun deleteOfflineKeepsPendingProgressAcrossRestart() {
        val store=populated();store.chapterDir(42).mkdirs();File(store.chapterDir(42),"0.img").writeText("fixture")
        store.record(42,25);store.deletePayload(42)
        val reopened=Store(store.root)
        assertEquals(25,reopened.get().pending[42]!!.local.pageNum);assertEquals(25,reopened.progress(42)!!.pageNum)
        assertFalse(reopened.get().chapters[42]!!.ready);assertFalse(reopened.chapterDir(42).exists())
    }
    @Test fun pageAndAnchorSurviveProcessRestart() {
        val store=populated();store.record(42,12,"//body/p[4]")
        val reopened=Store(store.root);assertEquals("//body/p[4]",reopened.progress(42)!!.bookScrollId);assertEquals(12,reopened.progress(42)!!.pageNum)
    }
    @Test fun corruptedStateIsNotSilentlyOverwritten() {
        val root=tmp.newFolder();File(root,"state.json").writeText("broken")
        assertThrows(Exception::class.java) { Store(root) };assertEquals("broken",File(root,"state.json").readText())
    }
    @Test fun deletingOneBookDoesNotDeleteAnother() {
        val store=populated();store.chapterDir(43).mkdirs();File(store.chapterDir(43),"0.img").writeText("other")
        store.deletePayload(42);assertTrue(File(store.chapterDir(43),"0.img").exists())
    }
    @Test fun unreadServerPositionStillSendsCorrectBookIdentity() {
        val store=populated()
        store.update { it.copy(progress=mapOf(42 to Progress(chapterId=42))) }
        store.record(42,2)
        val sent=store.get().pending.getValue(42).local
        assertEquals(3,sent.libraryId);assertEquals(2,sent.seriesId);assertEquals(7,sent.volumeId)
    }
    @Test fun samePositionDoesNotCreateAnotherPendingWrite() {
        val store=populated();store.record(42,1)
        assertTrue(store.get().pending.isEmpty())
    }

    @Test fun alphaOneStateRemainsReadableWithNewFieldsAbsent() {
        val root=tmp.newFolder()
        File(root,"state.json").writeText("""{"progress":{"42":{"chapterId":42,"pageNum":12}},"settings":{"fontSize":24},"lastSync":123}""")
        val store=Store(root)
        assertEquals(12,store.progress(42)!!.pageNum);assertEquals(24,store.get().settings.fontSize)
        assertFalse(store.get().settings.preferLocalChanges)
        assertTrue(store.get().syncIssues.isEmpty());assertEquals(0L,store.get().coverRevision)
        store.update { it.copy(syncMessage="Updated") }
        assertEquals(12,Store(root).progress(42)!!.pageNum)
    }
    @Test fun synchronizationPreferenceIsPersistentAndDefaultsToManual() {
        val store=populated();assertFalse(store.get().settings.preferLocalChanges)
        store.update { it.copy(settings=it.settings.copy(preferLocalChanges=true)) }
        assertTrue(Store(store.root).get().settings.preferLocalChanges)
        store.update { it.copy(settings=it.settings.copy(preferLocalChanges=false)) }
        assertFalse(Store(store.root).get().settings.preferLocalChanges)
    }

    @Test fun unchangedStateDoesNotRewriteDisk() {
        val store=populated();val file=File(store.root,"state.json")
        assertTrue(file.setLastModified(1000000L))
        store.record(42,1);store.update { it.copy(progress=it.progress.toMap()) }
        assertEquals(1000000L,file.lastModified())
        store.record(42,2);assertNotEquals(1000000L,file.lastModified())
        assertEquals(2,Store(store.root).progress(42)!!.pageNum)
    }
    @Test fun openingOnlyRecordsLocalHistoryAndNeverUploadsOrUnreads() {
        val store=populated();val before=store.get()
        store.opened(42)
        assertTrue(store.get().chapters.getValue(42).lastReadAt>0)
        assertEquals(before.progress,store.get().progress);assertTrue(store.get().pending.isEmpty())
        store.record(42,50);val pending=store.get().pending
        store.opened(42)
        assertEquals(pending,store.get().pending);assertEquals(50,store.progress(42)!!.pageNum)
        store.deletePayload(42);val deleted=store.get();store.opened(42)
        assertEquals(deleted,store.get())
    }
    @Test fun pendingPositionTakesPrecedenceWhenOpeningReader() {
        val store=populated();val remote=store.progress(42)!!
        store.update { it.copy(pending=mapOf(42 to Pending(remote,remote.copy(pageNum=8)))) }
        assertEquals(8,Store(store.root).progress(42)!!.pageNum)
    }

    @Test fun explicitUnreadClearsHistoryButKeepsMarkersAndQueuesTheIntent() {
        val store=populated();store.opened(42);store.addBookmark(42,1,null,"Mantener")
        store.markRead(listOf(42),true);assertTrue(store.get().isRead(store.get().chapters.getValue(42).chapter))
        store.markRead(listOf(42,999),false)
        val state=Store(store.root).get()
        assertFalse(state.hasStarted(state.chapters.getValue(42).chapter))
        assertFalse(state.isRead(state.chapters.getValue(42).chapter))
        assertEquals(0,state.pending.getValue(42).local.pageNum)
        assertEquals("Mantener",state.bookmarks.single().title)
    }
    @Test fun continuousPositionKeepsFractionLocalAndSurvivesRestartAndPayloadDeletion() {
        val store=populated();store.recordImagePosition(42,8,.42f)
        val restored=Store(store.root)
        assertEquals(.42f,restored.imagePosition(42)!!.fraction,.001f)
        assertEquals(8,restored.get().pending.getValue(42).local.pageNum)
        assertNull(restored.get().pending.getValue(42).local.bookScrollId)
        store.deletePayload(42)
        assertEquals(8,Store(store.root).imagePosition(42)!!.page)
    }
    @Test fun continuousAnchorCannotOverrideRemotePageOrAnotherEdition() {
        val store=populated();store.recordImagePosition(42,8,.42f)
        store.update { it.copy(pending=emptyMap(),progress=mapOf(42 to Progress(chapterId=42,pageNum=20))) }
        assertNull(store.imagePosition(42))
        store.recordImagePosition(42,20,.3f)
        store.update { it.copy(chapters=it.chapters.mapValues { (_,s) -> s.copy(chapter=s.chapter.copy(pages=51)) }) }
        assertNull(store.imagePosition(42))
    }
    @Test fun explicitPageNavigationAndMarkUnreadClearOldImageAnchor() {
        val store=populated();store.recordImagePosition(42,1,.4f);store.record(42,1)
        assertNull(store.imagePosition(42))
        store.recordImagePosition(42,8,.3f);store.markRead(listOf(42),false)
        assertNull(store.imagePosition(42));assertEquals(0,store.progress(42)!!.pageNum)
    }
    @Test fun continuousRejectsInvalidValuesAndDoesNotUnmarkCompletedLastPage() {
        val store=populated();val before=store.get()
        store.recordImagePosition(42,-1,.5f);store.recordImagePosition(42,5,Float.NaN)
        assertEquals(before,store.get())
        store.markRead(listOf(42),true);store.recordImagePosition(42,49,.4f)
        assertEquals(50,store.progress(42)!!.pageNum)
        assertEquals(49,store.imagePosition(42)!!.page)
    }

}
