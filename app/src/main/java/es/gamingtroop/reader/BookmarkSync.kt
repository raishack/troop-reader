package es.gamingtroop.reader

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.net.URLEncoder

/** Apply only pending local intentions, never stale clean copies or a whole list.
 * Three-way conflicts are the default; local priority must be explicitly enabled. */
class BookmarkSync(private val store: Store, private val api: KavitaApi) {
    private suspend fun list(saved: SavedChapter): List<RemoteBookmark> = cancellableApiCall {
        val id = saved.chapter.id
        val path = if (saved.epub) "api/Reader/ptoc?chapterId=$id" else "api/Reader/chapter-bookmarks?chapterId=$id"
        api.get<JsonArray>(path).map { item ->
            val j = item.jsonObject
            fun str(k: String) = j[k]?.jsonPrimitive?.contentOrNull
            val remote = RemoteBookmark(j.getValue("id").jsonPrimitive.int,
                j.getValue(if(saved.epub) "pageNumber" else "page").jsonPrimitive.int,
                if(saved.epub) str("title").orEmpty() else "",
                str(if(saved.epub) "bookScrollId" else "xPath"), str("selectedText"),
                j["imageOffset"]?.jsonPrimitive?.intOrNull ?: 0)
            require(remote.id > 0 && remote.page >= 0) { tr(R.string.tr_054) }
            remote
        }
    }
    private fun sameKey(local: Bookmark, remote: RemoteBookmark, epub: Boolean) =
        local.page == remote.page && if(epub) local.title == remote.title else remote.imageOffset == 0
    private fun sameContent(local: Bookmark, remote: RemoteBookmark, epub: Boolean) =
        sameKey(local, remote, epub) && (!epub || local.scroll.orEmpty() == remote.scroll.orEmpty())
    private fun sameRemoteKey(a: RemoteBookmark, b: RemoteBookmark, epub: Boolean) =
        a.page == b.page && if(epub) a.title == b.title else a.imageOffset == b.imageOffset
    private fun isCurrent(b: Bookmark) = store.get().bookmarks.find { it.id == b.id }?.revision == b.revision
    private fun update(id: String, block: (Bookmark) -> Bookmark) = store.update { s ->
        s.copy(bookmarks = s.bookmarks.map { if(it.id == id) block(it) else it })
    }
    private fun conflict(id: String) = update(id) { it.copy(conflict = true,
        error = tr(R.string.tr_055)) }
    private fun acknowledge(before: Bookmark, remote: RemoteBookmark?) = store.update { s ->
        s.copy(bookmarks = s.bookmarks.mapNotNull { current ->
            if(current.id != before.id) current
            else if(current.revision != before.revision) current.copy(remote = remote) // preserve a newer local intent
            else if(before.deleted) null
            else current.copy(remote = remote, dirty = false, error = null, conflict = false, restored = false)
        })
    }
    private suspend fun create(saved: SavedChapter, b: Bookmark) = cancellableApiCall {
        val payload = buildJsonObject {
            put("chapterId", b.chapterId); put("seriesId", saved.series.id)
            put("volumeId", saved.chapter.volumeId); put("libraryId", saved.series.libraryId)
            if(saved.epub) {
                put("pageNumber", b.page); put("title", b.title); put("bookScrollId", b.scroll?.let(::JsonPrimitive) ?: JsonNull)
                put("selectedText", JsonNull)
            } else { put("page", b.page); put("imageOffset", 0) }
        }
        api.response(if(saved.epub) "api/Reader/create-ptoc" else "api/Reader/bookmark", payload.toString()).close()
    }
    private suspend fun delete(saved: SavedChapter, remote: RemoteBookmark) = cancellableApiCall {
        if(saved.epub) api.response("api/Reader/ptoc?chapterId=${saved.chapter.id}&pageNum=${remote.page}&title=${URLEncoder.encode(remote.title,"UTF-8")}", method = "DELETE").close()
        else api.response("api/Reader/unbookmark", buildJsonObject {
            put("chapterId", saved.chapter.id); put("seriesId", saved.series.id)
            put("volumeId", saved.chapter.volumeId); put("page", remote.page); put("imageOffset", remote.imageOffset)
        }.toString()).close()
    }
    private suspend fun applyLocal(saved: SavedChapter, b: Bookmark, remote: List<RemoteBookmark>) {
        // Find the original marker even if Kavita recreated its id. Other markers stay intact.
        val targets = remote.filter { r ->
            b.remote?.let { it.id == r.id || sameRemoteKey(it, r, saved.epub) } == true ||
                (!b.deleted && sameKey(b, r, saved.epub))
        }
        if(!b.deleted && targets.size == 1 && sameContent(b, targets.single(), saved.epub)) {
            acknowledge(b, targets.single()); return
        }
        // PTOC has no update endpoint: replace this one entry with delete/create.
        // Keep the complete local intent until confirmation, including on failure after delete.
        for(target in targets) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if(!isCurrent(b) || !store.get().settings.preferLocalChanges) return
            delete(saved, target)
            check(list(saved).none { sameRemoteKey(it, target, saved.epub) }) {
                tr(R.string.tr_056)
            }
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        if(!isCurrent(b) || !store.get().settings.preferLocalChanges) return
        if(b.deleted) { acknowledge(b, null); return }
        create(saved, b)
        val confirmed = list(saved).find { sameContent(b, it, saved.epub) }
        check(confirmed != null) { tr(R.string.tr_057) }
        acknowledge(b, confirmed)
    }
    suspend fun sync() {
        for ((id, saved) in store.get().chapters) {
            kotlin.coroutines.coroutineContext.ensureActive()
            try {
                val chapter = cancellableApiCall { api.get<Chapter>("api/Series/chapter?chapterId=$id") }
                check(saved.chapter.sameEdition(chapter)) { tr(R.string.tr_058) }
                var remote = list(saved)
                val pending = store.get().bookmarks.filter { it.chapterId == id && it.dirty }
                for (b in pending) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if(store.get().bookmarks.find { it.id == b.id }?.revision != b.revision) continue
                    if(!b.edition.sameEdition(chapter)) { update(b.id) { it.copy(error = tr(R.string.tr_059)) }; continue }
                    // Re-read immediately before changing a single remote marker.
                    remote = list(saved)
                    if(store.get().bookmarks.find { it.id == b.id }?.revision != b.revision) continue
                    if(store.get().settings.preferLocalChanges && !b.restored) {
                        applyLocal(saved, b, remote)
                        continue
                    }
                    val baseline = b.remote
                    val current = baseline?.let { base -> remote.find { it.id == base.id } }
                    if (b.deleted) {
                        if (baseline == null || current == null) { acknowledge(b, null); continue }
                        // An old backup is not a fresh instruction to delete from the server.
                        if(b.restored) { conflict(b.id); continue }
                        if(current != baseline) { conflict(b.id); continue }
                        delete(saved, current)
                        remote = list(saved)
                        check(remote.none { it.id == current.id }) { tr(R.string.tr_060) }
                        acknowledge(b, null)
                    } else if (baseline != null) {
                        // A local undo must not resurrect a marker deleted/edited from the web.
                        if (current != baseline) conflict(b.id) else acknowledge(b, current)
                    } else {
                        val existing = remote.find { sameKey(b, it, saved.epub) }
                        if(existing != null) {
                            if(sameContent(b, existing, saved.epub)) acknowledge(b, existing) else conflict(b.id)
                            continue
                        }
                        create(saved, b)
                        remote = list(saved)
                        val confirmed = remote.find { sameContent(b, it, saved.epub) }
                        check(confirmed != null) { tr(R.string.tr_061) }
                        acknowledge(b, confirmed)
                    }
                }
                if (pending.isNotEmpty()) remote = list(saved)
                val observed = remote
                store.update { s ->
                    val merged = s.bookmarks.mapNotNull { b ->
                        if (b.chapterId != id || b.dirty || !b.edition.sameEdition(chapter)) b
                        else {
                            val server = observed.find { it.id == b.remote?.id }
                            server?.let { b.copy(remote = it, page = it.page, scroll = if(saved.epub) it.scroll else null,
                                title = if(saved.epub) it.title else b.title, error = null, conflict = false) }
                        }
                    }.toMutableList()
                    for (r in observed) {
                        if (r.page >= chapter.pages || merged.any { it.chapterId == id && it.remote?.id == r.id }) continue
                        // An unacknowledged local creation with the same contents will be reconciled next sync.
                        if(merged.any { it.chapterId == id && it.dirty && !it.deleted && sameContent(it, r, saved.epub) }) continue
                        merged += Bookmark("kavita-$id-${r.id}", id, if(saved.epub) r.title else tr(R.string.tr_062, r.page+1),
                            r.page, if(saved.epub) r.scroll else null, 0, chapter, remote = r, dirty = false)
                    }
                    s.copy(bookmarks = merged, bookmarkIssues = s.bookmarkIssues - id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                store.update { it.copy(bookmarkIssues = it.bookmarkIssues + (id to
                    (if(e is ApiError || e is IllegalStateException || e is IllegalArgumentException) e.message.orEmpty()
                    else tr(R.string.tr_063)))) }
                if(e is ApiError && e.status == 401) throw e
            }
        }
    }
}
