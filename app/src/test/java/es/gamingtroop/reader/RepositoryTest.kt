package es.gamingtroop.reader

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],application=android.app.Application::class)
class RepositoryTest {
    @Test fun cancellingSyncDuringRemoteReadNeverUploadsAfterwards() = runBlocking {
        MockWebServer().use { server ->
            val (repo, a) = setup(server)
            val store = repo.store(a.key)
            val before = store.get()
            val task = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job>()
            val posts = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(before.chapters.getValue(4).chapter))
                    request.path!!.startsWith("/api/Reader/get-progress") -> {
                        task.get().cancel()
                        MockResponse().setBody(codec.encodeToString(base))
                    }
                    else -> { posts.incrementAndGet(); MockResponse().setBody("true") }
                }
            }
            val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                repo.sync(a.key, includeBookmarks = false)
            }
            task.set(job); job.start()
            kotlinx.coroutines.withTimeout(5000) { job.join() }
            assertEquals(0, posts.get())
            assertEquals(before.pending, store.get().pending)
            assertEquals(before.lastSync, store.get().lastSync)
        }
    }
    private val base=Progress(1,2,3,4,1,null,"2026-09-01T00:00:00Z")
    private fun setup(server:MockWebServer): Pair<Repository,Account> {
        val context=RuntimeEnvironment.getApplication()
        val a=Account(server.url("/").toString().trimEnd('/'),1,"fixture","test-token",roles=listOf("Download"))
        val repo=Repository(context,MemoryVault(a));val store=repo.store(a.key)
        store.update { LocalState(chapters=mapOf(4 to SavedChapter(Chapter(4,3,pages=10,lastModifiedUtc="edition1"),Series(2,"Fixture",1),false)),progress=mapOf(4 to base)) }
        store.record(4,6)
        return repo to a
    }
    @Test fun syncPostsExactIdsAndReadsBackBeforeAcknowledgement()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server); val desired=repo.store(a.key).get().pending.getValue(4).local
            server.enqueue(MockResponse().setBody("{\"id\":4,\"pages\":10,\"lastModifiedUtc\":\"edition1\"}"))
            server.enqueue(MockResponse().setBody(codec.encodeToString(base)))
            server.enqueue(MockResponse().setBody("true"))
            server.enqueue(MockResponse().setBody(codec.encodeToString(desired)))
            server.enqueue(MockResponse().setBody("{\"id\":4,\"pages\":10,\"lastModifiedUtc\":\"edition1\"}"))
            server.enqueue(MockResponse().setBody(codec.encodeToString(desired)))
            repo.sync(a.key, includeBookmarks = false)
            server.takeRequest();server.takeRequest();val post=server.takeRequest()
            assertEquals("/api/Reader/progress",post.path);assertEquals("POST",post.method)
            assertEquals(desired,codec.decodeFromString<Progress>(post.body.readUtf8()))
            assertTrue(repo.store(a.key).get().pending.isEmpty())
        }
    }
    @Test fun remoteConflictNeverPostsUntilUserChooses()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server)
            server.enqueue(MockResponse().setBody("{\"id\":4,\"pages\":10,\"lastModifiedUtc\":\"edition1\"}"))
            server.enqueue(MockResponse().setBody(codec.encodeToString(base.copy(pageNum=9))))
            repo.sync(a.key, includeBookmarks = false)
            assertEquals(2,server.requestCount);assertEquals(9,repo.store(a.key).get().pending[4]!!.conflict!!.pageNum)
        }
    }
    @Test fun enablingLocalPriorityResolvesOldConflictButStillPullsWebOnlyChanges()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key)
            val remote=java.util.concurrent.atomic.AtomicReference(base.copy(pageNum=9,lastModifiedUtc="later"))
            val posts=java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse=when {
                    r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(store.get().chapters.getValue(4).chapter))
                    r.path!!.startsWith("/api/Reader/get-progress") -> MockResponse().setBody(codec.encodeToString(remote.get()))
                    r.path=="/api/Reader/progress" && r.method=="POST" -> {
                        remote.set(codec.decodeFromString<Progress>(r.body.readUtf8()));posts.incrementAndGet();MockResponse().setBody("true")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            repo.sync(a.key,includeBookmarks=false)
            assertEquals(0,posts.get());assertNotNull(store.get().pending[4]!!.conflict)
            store.update { it.copy(settings=it.settings.copy(preferLocalChanges=true)) }
            repo.sync(a.key,includeBookmarks=false)
            assertEquals(1,posts.get());assertEquals(6,remote.get().pageNum);assertTrue(store.get().pending.isEmpty())
            remote.set(base.copy(pageNum=8))
            repo.sync(a.key,includeBookmarks=false)
            assertEquals(1,posts.get());assertEquals(8,store.progress(4)!!.pageNum)
            store.update { it.copy(settings=it.settings.copy(preferLocalChanges=false)) }
            store.record(4,7);remote.set(base.copy(pageNum=2))
            repo.sync(a.key,includeBookmarks=false)
            assertEquals(1,posts.get());assertNotNull(store.get().pending[4]!!.conflict)
        }
    }
    @Test fun newerReadingDuringPriorityUploadStaysPendingInsteadOfBeingLost()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key)
            store.update { it.copy(settings=it.settings.copy(preferLocalChanges=true)) }
            val remote=java.util.concurrent.atomic.AtomicReference(base.copy(pageNum=9))
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse=when {
                    r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(store.get().chapters.getValue(4).chapter))
                    r.path!!.startsWith("/api/Reader/get-progress") -> MockResponse().setBody(codec.encodeToString(remote.get()))
                    r.path=="/api/Reader/progress" -> {
                        remote.set(codec.decodeFromString<Progress>(r.body.readUtf8()));store.record(4,7);MockResponse().setBody("true")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            repo.sync(a.key,includeBookmarks=false)
            assertEquals(6,remote.get().pageNum);assertEquals(7,store.progress(4)!!.pageNum)
            assertEquals(7,store.get().pending[4]!!.local.pageNum)
        }
    }
    @Test fun changedBookEditionStopsProgressWrite()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server)
            server.enqueue(MockResponse().setBody("{\"id\":4,\"pages\":12,\"lastModifiedUtc\":\"edition2\"}"))
            repo.sync(a.key, includeBookmarks = false)
            assertEquals(1,server.requestCount);assertNotNull(repo.store(a.key).get().pending[4]!!.error)
        }
    }
    @Test fun noServerConfirmationLeavesQueueIntact()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server)
            server.enqueue(MockResponse().setBody("{\"id\":4,\"pages\":10,\"lastModifiedUtc\":\"edition1\"}"))
            server.enqueue(MockResponse().setBody(codec.encodeToString(base)))
            server.enqueue(MockResponse().setBody("true"))
            server.enqueue(MockResponse().setBody(codec.encodeToString(base)))
            repo.sync(a.key, includeBookmarks = false)
            assertEquals(6,repo.store(a.key).get().pending[4]!!.local.pageNum)
        }
    }
    @Test fun libraryUsesUserAccessibleLibrariesEndpoint()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server)
            server.enqueue(MockResponse().setBody("[{\"id\":1,\"name\":\"Libros\"}]"));server.enqueue(MockResponse().setBody("[]"))
            repo.refreshLibrary(a.key)
            assertEquals("/api/Library/libraries",server.takeRequest().path)
            assertEquals("POST",server.takeRequest().method)
            assertEquals("Libros",repo.store(a.key).get().libraries.single().name)
        }
    }

    @Test fun removedTitleDoesNotBlockProgressForOtherBooks()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server); val store=repo.store(a.key)
            val other=store.get().chapters.getValue(4).let { it.copy(chapter=it.chapter.copy(id=5)) }
            store.update { it.copy(pending=emptyMap(), chapters=it.chapters+(5 to other)) }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest)=when(r.path) {
                    "/api/Series/chapter?chapterId=4" -> MockResponse().setResponseCode(404)
                    "/api/Series/chapter?chapterId=5" -> MockResponse().setBody(codec.encodeToString(other.chapter))
                    "/api/Reader/get-progress?chapterId=5" -> MockResponse().setBody(codec.encodeToString(base.copy(chapterId=5,pageNum=8)))
                    else -> MockResponse().setResponseCode(500)
                }
            }
            repo.sync(a.key, includeBookmarks = false)
            assertEquals(8,store.progress(5)!!.pageNum)
            assertTrue(store.get().syncIssues.containsKey(4))
            assertNotEquals("Todo sincronizado",store.get().syncMessage)
        }
    }
    @Test fun openReaderIsNotMovedByRemotePull()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server); val store=repo.store(a.key)
            store.update { it.copy(pending=emptyMap()) }; repo.reading(a.key,4,true)
            repo.sync(a.key, includeBookmarks = false)
            assertEquals(0,server.requestCount); assertEquals(6,store.progress(4)!!.pageNum)
        }
    }
    @Test fun remoteEditionChangeDoesNotReplaceOldOfflineProgress()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server); val store=repo.store(a.key)
            store.update { it.copy(pending=emptyMap()) }
            server.enqueue(MockResponse().setBody("{\"id\":4,\"pages\":20,\"lastModifiedUtc\":\"edition2\"}"))
            repo.sync(a.key, includeBookmarks = false)
            assertEquals(1,server.requestCount);assertEquals(6,store.progress(4)!!.pageNum)
            assertTrue(store.get().syncIssues.containsKey(4))
        }
    }
    @Test fun newEditionReplacesMetadataAndNeverReusesOldPages()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server); val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            val edition=saved.chapter.copy(pages=12,lastModifiedUtc="edition2")
            store.chapterDir(4).mkdirs();java.io.File(store.chapterDir(4),"0.img").writeText("old")
            store.update { it.copy(pending=emptyMap()) }
            server.enqueue(MockResponse().setBody(codec.encodeToString(base)))
            repo.prepare(a.key,saved.series,edition)
            assertFalse(java.io.File(store.chapterDir(4),"0.img").exists())
            assertEquals(edition,store.get().chapters.getValue(4).chapter)
            assertFalse(store.get().chapters.getValue(4).ready)
        }
    }
    @Test fun changedEditionCannotDiscardUnsyncedReading()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            store.chapterDir(4).mkdirs();val file=java.io.File(store.chapterDir(4),"0.img");file.writeText("old")
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.prepare(a.key,saved.series,saved.chapter.copy(pages=12)) } }
            assertTrue(file.exists());assertTrue(store.get().pending.containsKey(4));assertEquals(0,server.requestCount)
        }
    }
    @Test fun sameEditionRedownloadPreservesPendingProgress()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            store.deletePayload(4)
            server.enqueue(MockResponse().setBody(codec.encodeToString(base.copy(pageNum=9))))
            repo.prepare(a.key,saved.series,saved.chapter)
            assertEquals(6,store.progress(4)!!.pageNum);assertEquals(6,store.get().pending.getValue(4).local.pageNum)
        }
    }

    @Test fun failedRefreshPreservesPayloadFromPreviousEdition()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            store.update { it.copy(pending=emptyMap()) }
            store.chapterDir(4).mkdirs();val file=java.io.File(store.chapterDir(4),"0.img");file.writeText("old")
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(ApiError::class.java) { runBlocking { repo.prepare(a.key,saved.series,saved.chapter.copy(pages=12)) } }
            assertEquals("old",file.readText());assertEquals(10,store.get().chapters.getValue(4).chapter.pages)
        }
    }
    @Test fun conflictRefreshCannotOverwriteANewerLocalReading()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key)
            store.update { s -> s.copy(pending=s.pending.mapValues { (_,p)->p.copy(conflict=base.copy(pageNum=8)) }) }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse {
                    store.record(4,7)
                    return MockResponse().setBody(codec.encodeToString(base.copy(pageNum=9)))
                }
            }
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.resolve(a.key,4,true) } }
            assertEquals(7,store.get().pending.getValue(4).local.pageNum)
            assertEquals(9,store.get().pending.getValue(4).conflict!!.pageNum)
        }
    }
    @Test fun newCoverNotifiesVisibleCatalogue()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server)
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("[{\"id\":91,\"name\":\"Fixture\"}]"))
            val bitmap = android.graphics.Bitmap.createBitmap(8,12,android.graphics.Bitmap.Config.ARGB_8888)
            val bytes=java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            repo.refreshLibrary(a.key)
            assertEquals(2,server.requestCount) // Catalogue no longer waits for every cover.
            assertTrue(repo.ensureCover(a.key,CoverRef("series",91)))
            assertEquals(1L,repo.store(a.key).get().coverRevision)
            assertTrue(repo.store(a.key).coverFile(91).exists())
        }
    }

    @Test fun preparingAnotherBookDoesNotWaitForTheWholeDownloadQueue()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val saved=repo.store(a.key).get().chapters.getValue(4)
            server.enqueue(MockResponse().setBody(codec.encodeToString(base)))
            repo.transferLock.lock()
            try { kotlinx.coroutines.withTimeout(2000) { repo.prepare(a.key,saved.series,saved.chapter) } }
            finally { repo.transferLock.unlock() }
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun batchSkipsReadyTitlesDeduplicatesAndContinuesAfterOneFailure()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            store.update { it.copy(chapters=it.chapters+(4 to saved.copy(ready=true))) }
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest)=if(r.path!!.contains("chapterId=5")) MockResponse().setResponseCode(404)
                    else MockResponse().setBody(codec.encodeToString(Progress(chapterId=6)))
            }
            val enqueued=mutableListOf<Int>()
            val result=repo.prepareBatch(a.key,saved.series,listOf(saved.chapter,saved.chapter.copy(id=5),saved.chapter.copy(id=6),saved.chapter.copy(id=6))) { enqueued+=it }
            assertEquals(1,result.skipped);assertEquals(1,result.queued);assertEquals(setOf(5),result.errors.keys)
            assertEquals(listOf(6),enqueued);assertEquals(2,server.requestCount)
        }
    }
    @Test fun reprepareCannotPullServerProgressOverAnOpenReader()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            store.update { it.copy(pending=emptyMap()) };repo.reading(a.key,4,true)
            server.enqueue(MockResponse().setBody(codec.encodeToString(base.copy(pageNum=1))))
            repo.prepare(a.key,saved.series,saved.chapter)
            assertEquals(6,store.progress(4)!!.pageNum)
        }
    }
    @Test fun failedProgressConfirmationDoesNotBlockTheNextTitle()=runBlocking {
        MockWebServer().use { server ->
            val (repo,a)=setup(server);val store=repo.store(a.key);val saved=store.get().chapters.getValue(4)
            store.update { it.copy(chapters=it.chapters+(5 to saved.copy(chapter=saved.chapter.copy(id=5))),progress=it.progress+(5 to base.copy(chapterId=5))) }
            store.record(5,7)
            var remoteFive=base.copy(chapterId=5)
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(r:RecordedRequest):MockResponse {
                    val id=r.requestUrl!!.queryParameter("chapterId")?.toInt()
                    return when {
                        r.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(saved.chapter.copy(id=id!!)))
                        r.path!!.startsWith("/api/Reader/get-progress") -> MockResponse().setBody(codec.encodeToString(if(id==5)remoteFive else base))
                        r.method=="POST" -> {
                            val sent=codec.decodeFromString<Progress>(r.body.readUtf8());if(sent.chapterId==5)remoteFive=sent
                            MockResponse().setBody("true")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            repo.sync(a.key,includeBookmarks=false)
            assertNotNull(store.get().pending[4]?.error);assertFalse(store.get().pending.containsKey(5));assertEquals(7,remoteFive.pageNum)
        }
    }

}
