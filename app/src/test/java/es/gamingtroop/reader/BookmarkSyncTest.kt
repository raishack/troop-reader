package es.gamingtroop.reader

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28], application=android.app.Application::class)
class BookmarkSyncTest {
    @Test fun cancellationWhileCheckingMarkerLeavesCreationPending() = runBlocking {
        MockWebServer().use { server ->
            val account = Account(server.url("/").toString().trimEnd('/'), 1, "fixture", "fixture")
            val repo = Repository(RuntimeEnvironment.getApplication(), MemoryVault(account))
            val store = repo.store(account.key)
            store.update { LocalState(chapters = mapOf(42 to SavedChapter(chapter, Series(5, "Fixture", 2), true))) }
            store.addBookmark(42, 2, "anchor", "Pendiente")
            val before = store.get()
            val task = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job>()
            val writes = java.util.concurrent.atomic.AtomicInteger()
            var lists = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/api/Series/chapter") -> MockResponse().setBody(codec.encodeToString(chapter))
                    request.method == "GET" -> {
                        if(++lists == 2) task.get().cancel()
                        MockResponse().setBody("[]")
                    }
                    else -> { writes.incrementAndGet(); MockResponse().setBody("true") }
                }
            }
            val job = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                BookmarkSync(store, repo.api(account.key)).sync()
            }
            task.set(job); job.start()
            kotlinx.coroutines.withTimeout(5000) { job.join() }
            assertEquals(0, writes.get())
            assertEquals(before.bookmarks, store.get().bookmarks)
            assertTrue(store.get().bookmarkIssues.isEmpty())
        }
    }
    private val chapter = Chapter(42,7,pages=10,format=3,lastModifiedUtc="edition")
    private var duringWrite: ((Store, String) -> Unit)? = null
    private var failNextCreate = false
    private fun fixture(epub: Boolean = true, preferLocal: Boolean = false, action: suspend (Store, MutableList<RemoteBookmark>, MutableList<String>, BookmarkSync) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val account = Account(server.url("/").toString().trimEnd('/'),1,"fixture","fixture")
            val repo = Repository(RuntimeEnvironment.getApplication(), MemoryVault(account))
            val store = repo.store(account.key)
            val c = if(epub) chapter else chapter.copy(format=1)
            store.update { LocalState(chapters=mapOf(42 to SavedChapter(c,Series(5,"Fixture",2),epub)),settings=ReadingSettings(preferLocalChanges=preferLocal)) }
            val remote: MutableList<RemoteBookmark> = CopyOnWriteArrayList()
            val writes: MutableList<String> = CopyOnWriteArrayList()
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    val path = r.requestUrl!!.encodedPath
                    if(path == "/api/Series/chapter") return MockResponse().setBody(codec.encodeToString(c))
                    if(r.method == "GET") return MockResponse().setBody(buildJsonArray {
                        for(b in remote) add(buildJsonObject {
                            put("id",b.id);put("chapterId",42);put(if(epub) "pageNumber" else "page",b.page)
                            put("title",b.title);put(if(epub) "bookScrollId" else "xPath",b.scroll?.let(::JsonPrimitive) ?: JsonNull)
                            put("selectedText",b.selectedText?.let(::JsonPrimitive) ?: JsonNull);put("imageOffset",b.imageOffset)
                        })
                    }.toString())
                    writes += r.method + " " + path
                    if(path.endsWith("/create-ptoc") && failNextCreate) {
                        failNextCreate=false
                        return MockResponse().setResponseCode(503)
                    }
                    duringWrite?.invoke(store, r.method.orEmpty())
                    if(r.method == "DELETE" || path.endsWith("/unbookmark")) {
                        val body = if(r.method == "POST") codec.parseToJsonElement(r.body.readUtf8()).jsonObject else null
                        val page = body?.getValue("page")?.jsonPrimitive?.int ?: r.requestUrl!!.queryParameter("pageNum")!!.toInt()
                        val title = r.requestUrl!!.queryParameter("title")
                        val imageOffset = body?.get("imageOffset")?.jsonPrimitive?.int ?: 0
                        remote.removeAll { it.page == page && if(epub) it.title==title else it.imageOffset==imageOffset }
                    } else {
                        val j = codec.parseToJsonElement(r.body.readUtf8()).jsonObject
                        val page = j.getValue(if(epub) "pageNumber" else "page").jsonPrimitive.int
                        remote += RemoteBookmark((remote.maxOfOrNull { it.id } ?: 100)+1,page,
                            j["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),j["bookScrollId"]?.jsonPrimitive?.contentOrNull)
                    }
                    return MockResponse().setBody("true")
                }
            }
            action(store,remote,writes,BookmarkSync(store,repo.api(account.key)))
        }
    }
    @Test fun importOnlineMarkersWithoutWritingOrQueuingReadingProgress() = fixture { store, remote, writes, sync ->
        remote += RemoteBookmark(20,3,"Web", "id(\"web\")", "selected text")
        sync.sync()
        val b=store.get().bookmarks.single()
        assertEquals("Web",b.title);assertFalse(b.dirty);assertEquals(20,b.remote!!.id)
        assertTrue(writes.isEmpty());assertTrue(store.get().pending.isEmpty())
    }
    @Test fun localMarkerRoundTripsAndDeletionIsIndividual() = fixture { store,remote,writes,sync ->
        remote += RemoteBookmark(20,2,"Otro")
        store.addBookmark(42,4,"id(\"here\")","Local & spaces")
        sync.sync()
        val b=store.get().bookmarks.first { it.title.startsWith("Local") }
        assertFalse(b.dirty);assertEquals(2,remote.size)
        store.removeBookmark(b.id);sync.sync()
        assertEquals(listOf("Otro"),remote.map { it.title });assertFalse(store.get().bookmarks.any { it.id==b.id })
        assertEquals(listOf("POST /api/Reader/create-ptoc","DELETE /api/Reader/ptoc"),writes.toList())
    }
    @Test fun mangaUsesPageBookmarksNotEpubPersonalToc() = fixture(false) { store,remote,writes,sync ->
        store.addBookmark(42,5,null,"Mi escena");sync.sync()
        assertEquals(5,remote.single().page);assertEquals("Mi escena",store.get().bookmarks.single().title)
        store.removeBookmark(store.get().bookmarks.single().id);sync.sync()
        assertEquals(listOf("POST /api/Reader/bookmark","POST /api/Reader/unbookmark"),writes.toList())
    }
    @Test fun staleOfflineDeleteCannotDeleteAModifiedOnlineMarker() = fixture { store,remote,writes,sync ->
        remote += RemoteBookmark(20,3,"Web","old")
        sync.sync();val b=store.get().bookmarks.single();store.removeBookmark(b.id)
        remote[0]=remote[0].copy(scroll="new",selectedText="new text")
        sync.sync()
        assertTrue(writes.isEmpty());assertEquals("new",remote.single().scroll)
        assertTrue(store.get().bookmarks.first { it.id==b.id }.conflict)
    }
    @Test fun staleOfflineCreationCannotOverwriteSameOnlineKey() = fixture { store,remote,writes,sync ->
        store.addBookmark(42,3,"old","Same title")
        remote += RemoteBookmark(20,3,"Same title","new")
        sync.sync()
        assertTrue(writes.isEmpty());assertEquals("new",remote.single().scroll)
        assertTrue(store.get().bookmarks.any { it.conflict && it.scroll=="old" })
        assertTrue(store.get().bookmarks.any { it.remote?.id==20 && it.scroll=="new" })
    }
    @Test fun remoteDeletionDoesNotResurrectOldLocalCopy() = fixture { store,remote,writes,sync ->
        remote += RemoteBookmark(20,3,"Web");sync.sync();remote.clear();sync.sync()
        assertTrue(store.get().bookmarks.isEmpty());assertTrue(writes.isEmpty())
    }
    @Test fun remoteRecreationIsNotRemovedByOfflineTombstone() = fixture { store,remote,writes,sync ->
        remote += RemoteBookmark(20,3,"Web");sync.sync();store.removeBookmark(store.get().bookmarks.single().id)
        remote[0]=remote[0].copy(id=21)
        sync.sync()
        assertTrue(writes.isEmpty());assertEquals(21,store.get().bookmarks.single().remote!!.id)
    }
    @Test fun deletingPayloadRetainsMarkersAndTombstonesAcrossRestart() = fixture { store,_,_,_ ->
        store.addBookmark(42,2,"anchor","Preserved");store.removeBookmark(store.get().bookmarks.single().id)
        store.deletePayload(42)
        val reloaded=Store(store.root)
        assertTrue(reloaded.get().bookmarks.single().deleted);assertTrue(reloaded.get().bookmarks.single().dirty)
    }
    @Test fun saveSamePositionTwiceDoesNotRenameExistingRemoteMarker() = fixture { store,remote,writes,sync ->
        remote += RemoteBookmark(20,3,"Original","anchor");sync.sync()
        store.addBookmark(42,3,"anchor","New title");sync.sync()
        assertEquals("Original",remote.single().title);assertEquals(1,store.get().bookmarks.size);assertTrue(writes.isEmpty())
    }
    @Test fun markerFromAnOldEditionNeverGoesToTheNewOne() = fixture { store,_,writes,sync ->
        store.addBookmark(42,3,"anchor","Old")
        store.update { s -> s.copy(bookmarks=s.bookmarks.map { it.copy(edition=it.edition.copy(lastModifiedUtc="old")) }) }
        sync.sync();assertTrue(writes.isEmpty());assertNotNull(store.get().bookmarks.single().error)
    }
    @Test fun creatingThenDeletingOfflineDoesNotTouchServer() = fixture { store,_,writes,sync ->
        store.addBookmark(42,3,null,"Offline");store.removeBookmark(store.get().bookmarks.single().id)
        sync.sync();assertTrue(writes.isEmpty());assertTrue(store.get().bookmarks.isEmpty())
    }
    @Test fun localDeletionDuringCreationIsNotLostByLateAcknowledgement() = fixture { store,remote,_,sync ->
        store.addBookmark(42,4,null,"New")
        val id=store.get().bookmarks.single().id
        duringWrite={ s,method -> if(method=="POST") s.removeBookmark(id) }
        sync.sync()
        val b=store.get().bookmarks.single()
        assertTrue(b.dirty);assertTrue(b.deleted);assertNotNull(b.remote);assertEquals(1,remote.size)
        duringWrite=null;sync.sync()
        assertTrue(remote.isEmpty());assertTrue(store.get().bookmarks.isEmpty())
    }
    @Test fun undoWhileDeleteIsInFlightPreservesTheNewerLocalIntent() = fixture { store,remote,_,sync ->
        remote+=RemoteBookmark(20,4,"Web");sync.sync()
        val id=store.get().bookmarks.single().id;store.removeBookmark(id)
        duringWrite={ s,method -> if(method=="DELETE") s.undoBookmarkDelete(id) }
        sync.sync()
        assertTrue(remote.isEmpty());val b=store.get().bookmarks.single()
        assertTrue(b.dirty);assertFalse(b.deleted);assertNull(b.remote)
        duringWrite=null;sync.sync()
        assertEquals(1,remote.size);assertFalse(store.get().bookmarks.single().dirty)
    }

    @Test fun localPriorityReplacesOnlyTheCollidingEpubMarker() = fixture(preferLocal=true) { store,remote,writes,sync ->
        remote+=RemoteBookmark(20,3,"Misma clave","online")
        remote+=RemoteBookmark(21,3,"Otro marcador","keep")
        store.addBookmark(42,3,"offline","Misma clave")
        sync.sync()
        assertEquals(2,remote.size);assertEquals("offline",remote.first { it.title=="Misma clave" }.scroll)
        assertEquals("keep",remote.first { it.id==21 }.scroll)
        assertFalse(store.get().bookmarks.any { it.dirty || it.conflict })
        assertEquals(listOf("DELETE /api/Reader/ptoc","POST /api/Reader/create-ptoc"),writes.toList())
    }
    @Test fun restoredMarkerNeverOverridesServerEvenWithLocalPriority() = fixture(preferLocal=true) { store,remote,writes,sync ->
        remote += RemoteBookmark(20,2,"Nota", "id(\"web\")")
        store.addBookmark(42,2,"id(\"backup\")","Nota")
        store.update { s -> s.copy(bookmarks=s.bookmarks.map { it.copy(restored=true) }) }
        sync.sync()
        assertTrue(writes.isEmpty())
        assertEquals("id(\"web\")",remote.single().scroll)
        assertTrue(store.get().bookmarks.first { it.dirty }.conflict)
    }
    @Test fun localDeleteWinsOverOnlineChangesAndRecreatedId() = fixture(preferLocal=true) { store,remote,_,sync ->
        remote+=RemoteBookmark(20,3,"Web","old");sync.sync()
        store.removeBookmark(store.get().bookmarks.single().id)
        remote[0]=remote[0].copy(id=21,scroll="new")
        sync.sync();assertTrue(remote.isEmpty());assertTrue(store.get().bookmarks.isEmpty())
    }
    @Test fun restoredDeletionNeverDeletesAnUnchangedServerMarker() = fixture(preferLocal=true) { store,remote,writes,sync ->
        remote+=RemoteBookmark(20,3,"Web","old");sync.sync()
        store.removeBookmark(store.get().bookmarks.single().id)
        store.update { s -> s.copy(bookmarks=s.bookmarks.map { it.copy(restored=true) }) }
        sync.sync();assertTrue(writes.isEmpty());assertEquals(20,remote.single().id)
        assertTrue(store.get().bookmarks.single().conflict)
    }
    @Test fun untouchedOnlineChangesAreStillImportedWithLocalPriority() = fixture(preferLocal=true) { store,remote,writes,sync ->
        remote+=RemoteBookmark(20,3,"Web","old");sync.sync()
        remote[0]=remote[0].copy(scroll="new");sync.sync()
        assertEquals("new",store.get().bookmarks.single().scroll);assertTrue(writes.isEmpty())
        remote.clear();sync.sync();assertTrue(store.get().bookmarks.isEmpty())
    }
    @Test fun localUndoRecreatesMarkerRemovedOnWeb() = fixture(preferLocal=true) { store,remote,_,sync ->
        remote+=RemoteBookmark(20,3,"Web","old");sync.sync()
        val id=store.get().bookmarks.single().id
        store.removeBookmark(id);store.undoBookmarkDelete(id);remote.clear()
        sync.sync();assertEquals("old",remote.single().scroll);assertFalse(store.get().bookmarks.single().dirty)
    }
    @Test fun newerDeletionDuringLocalPriorityUploadSurvivesAcknowledgement() = fixture(preferLocal=true) { store,remote,_,sync ->
        remote+=RemoteBookmark(20,3,"Same","online")
        store.addBookmark(42,3,"offline","Same");val id=store.get().bookmarks.single().id
        duringWrite={ s,method -> if(method=="POST") s.removeBookmark(id) }
        sync.sync();assertTrue(store.get().bookmarks.single().deleted);assertTrue(store.get().bookmarks.single().dirty)
        duringWrite=null;sync.sync();assertTrue(remote.isEmpty());assertTrue(store.get().bookmarks.isEmpty())
    }
    @Test fun newerDeletionDuringReplacementStopsTheOldCreation() = fixture(preferLocal=true) { store,remote,writes,sync ->
        remote+=RemoteBookmark(20,3,"Same","online")
        store.addBookmark(42,3,"offline","Same");val id=store.get().bookmarks.single().id
        duringWrite={ s,method -> if(method=="DELETE") s.removeBookmark(id) }
        sync.sync();assertTrue(remote.isEmpty());assertTrue(store.get().bookmarks.single().deleted)
        assertEquals(listOf("DELETE /api/Reader/ptoc"),writes.toList())
        duringWrite=null;sync.sync();assertTrue(store.get().bookmarks.isEmpty())
    }
    @Test fun localMangaDeleteAffectsOnlyTheMatchingImageOffset() = fixture(epub=false,preferLocal=true) { store,remote,writes,sync ->
        remote+=RemoteBookmark(20,3,imageOffset=0);remote+=RemoteBookmark(21,3,imageOffset=1)
        sync.sync();store.removeBookmark(store.get().bookmarks.first { it.remote!!.id==20 }.id)
        remote[0]=remote[0].copy(id=22)
        sync.sync();assertEquals(21,remote.single().id);assertEquals(1,store.get().bookmarks.size)
        assertEquals(listOf("POST /api/Reader/unbookmark"),writes.toList())
    }
    @Test fun failedReplacementKeepsTheLocalMarkerUntilARetryConfirmsIt() = fixture(preferLocal=true) { store,remote,_,sync ->
        remote+=RemoteBookmark(20,3,"Same","online")
        store.addBookmark(42,3,"offline","Same");failNextCreate=true
        sync.sync()
        assertTrue(remote.isEmpty());assertTrue(store.get().bookmarks.single().dirty)
        assertEquals("offline",Store(store.root).get().bookmarks.single().scroll)
        assertTrue(store.get().bookmarkIssues.containsKey(42))
        sync.sync();assertEquals("offline",remote.single().scroll)
        assertFalse(store.get().bookmarks.single().dirty);assertTrue(store.get().bookmarkIssues.isEmpty())
    }
    @Test fun disablingPriorityStopsFollowingWritesOfAnInFlightReplacement() = fixture(preferLocal=true) { store,remote,writes,sync ->
        remote+=RemoteBookmark(20,3,"Same","online")
        store.addBookmark(42,3,"offline","Same")
        duringWrite={ s,method -> if(method=="DELETE") s.update { it.copy(settings=it.settings.copy(preferLocalChanges=false)) } }
        sync.sync()
        assertEquals(listOf("DELETE /api/Reader/ptoc"),writes.toList())
        assertTrue(store.get().bookmarks.single().dirty);assertEquals("offline",store.get().bookmarks.single().scroll)
    }

}
