package es.gamingtroop.reader

import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    private val base = Progress(1,2,3,4,10,"//body/p[1]","2026-09-10T10:00:00Z")
    private val local = base.copy(pageNum = 20, bookScrollId = "//body/p[4]", lastModifiedUtc = "2026-09-10T12:00:00Z")
    @Test fun unchangedServerAcceptsOfflineProgress() { assertEquals(SyncDecision.SEND, SyncPolicy.decide(Pending(base,local),base)) }
    @Test fun alreadySentPositionIsAcknowledgedAfterCrash() { assertEquals(SyncDecision.ACKNOWLEDGE, SyncPolicy.decide(Pending(base,local),local)) }
    @Test fun simultaneousReadingCreatesConflictNotMaximumPage() { assertEquals(SyncDecision.CONFLICT, SyncPolicy.decide(Pending(base,local),base.copy(pageNum=30))) }
    @Test fun rereadIsNotSilentlyRejected() { assertEquals(SyncDecision.SEND, SyncPolicy.decide(Pending(base,base.copy(pageNum=2)),base)) }
    @Test fun differentEpubAnchorsOnSamePageConflict() { assertEquals(SyncDecision.CONFLICT, SyncPolicy.decide(Pending(base,local),base.copy(bookScrollId="id(\"other\")"))) }
    @Test fun externalRereadAtSamePositionStillConflicts() { assertEquals(SyncDecision.CONFLICT, SyncPolicy.decide(Pending(base,local),base.copy(lastModifiedUtc="2026-09-10T13:00:00Z"))) }
    @Test fun unknownTimestampCanStillComparePosition() { assertEquals(SyncDecision.SEND, SyncPolicy.decide(Pending(base.copy(lastModifiedUtc=""),local),base)) }
    @Test fun localPriorityIsOptInAndSendsEvenOverLaterServerProgress() {
        val remote=base.copy(pageNum=30,lastModifiedUtc="2026-09-11T13:00:00Z")
        assertFalse(ReadingSettings().preferLocalChanges)
        assertEquals(SyncDecision.CONFLICT,SyncPolicy.decide(Pending(base,local),remote))
        assertEquals(SyncDecision.SEND,SyncPolicy.decide(Pending(base,local),remote,true))
    }
    @Test fun localPriorityAlsoPreservesAnIntentionalRereadAndEpubAnchor() {
        val reread=local.copy(pageNum=1,bookScrollId="id(\"start\")")
        assertEquals(SyncDecision.SEND,SyncPolicy.decide(Pending(base,reread),local,true))
        assertEquals(SyncDecision.SEND,SyncPolicy.decide(Pending(base,local),local.copy(bookScrollId="id(\"other\")"),true))
    }
    @Test fun repeatedLocalSavesPreserveOriginalBaseline() {
        val queued = SyncPolicy.queue(Pending(base,local,5),local,local.copy(pageNum=21))
        assertEquals(base,queued.base); assertEquals(6,queued.revision); assertEquals(21,queued.local.pageNum)
    }
    @Test fun newerProgressSurvivesAcknowledgementOfOlderRequest() {
        val pending=Pending(base,local.copy(pageNum=21),6)
        val state=LocalState(pending=mapOf(4 to pending),progress=mapOf(4 to pending.local))
        val result=SyncPolicy.acknowledge(state,4,5,local)
        assertEquals(21,result.pending[4]!!.local.pageNum); assertEquals(local,result.pending[4]!!.base)
        assertEquals(21,result.progress[4]!!.pageNum)
    }
    @Test fun confirmedWriteRemovesOnlyItsOwnQueueEntry() {
        val pending=Pending(base,local,5)
        val state=LocalState(pending=mapOf(4 to pending,5 to pending.copy(local=local.copy(chapterId=5))))
        val result=SyncPolicy.acknowledge(state,4,5,local)
        assertFalse(result.pending.containsKey(4)); assertTrue(result.pending.containsKey(5)); assertEquals(local,result.progress[4])
    }
    @Test fun accountIdentitySeparatesServersAndUsers() {
        val a=Account("https://a.test",1,"user","token")
        assertNotEquals(a.key,a.copy(id=2).key); assertNotEquals(a.key,a.copy(server="https://b.test").key)
        assertEquals(a.key,a.copy(token="new").key)
    }
}
