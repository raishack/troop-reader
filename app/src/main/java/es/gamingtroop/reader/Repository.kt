package es.gamingtroop.reader

import android.content.Context
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class BatchResult(val queued: Int, val skipped: Int, val errors: Map<Int, String>)

class Repository(val context: Context, val vault: SessionVault = Vault(context)) {
    private val stores = mutableMapOf<String, Store>()
    val coverLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    val coverSlots = kotlinx.coroutines.sync.Semaphore(4)
    val transferLock = Mutex() // At most one payload transfer at a time.
    private val payloadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    fun payloadLock(key: String, id: Int): Mutex = payloadLocks.getOrPut("$key:$id") { Mutex() }
    private val readers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun reading(key: String, id: Int, open: Boolean) { if (open) readers.add("$key:$id") else readers.remove("$key:$id") }
    private val syncLock = Mutex()
    @Synchronized fun store(key: String): Store { require(key.matches(Regex("[a-f0-9]{64}"))); return stores.getOrPut(key) { Store(File(context.filesDir, "accounts/$key")) } }
    fun active() = vault.read()
    fun api(key: String) = KavitaApi(vault, key)
    suspend fun login(server: String, user: String, password: String): Account = withContext(Dispatchers.IO) { KavitaApi.login(vault, server, user, password) }
    suspend fun refreshLibrary(key: String) = withContext(Dispatchers.IO) {
        val api = api(key); val store = store(key)
        val libs = kotlinx.coroutines.runInterruptible { api.get<List<Library>>("api/Library/libraries") }
        val all = mutableListOf<Series>(); var page = 1
        while (true) {
            val batch = kotlinx.coroutines.runInterruptible { codec.decodeFromString<List<Series>>(api.text("api/Series/all-v2?pageNumber=$page&pageSize=100", "{}")) }
            all += batch
            if (batch.size < 100) break
            require(++page <= 1000) { "Biblioteca demasiado grande para esta versión" }
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        store.update { it.copy(libraries = libs, series = all) }
    }
    suspend fun volumes(key: String, id: Int): List<Volume> = withContext(Dispatchers.IO) {
        val volumes = kotlinx.coroutines.runInterruptible { api(key).get<List<Volume>>("api/Series/volumes?seriesId=$id") }
        kotlin.coroutines.coroutineContext.ensureActive()
        store(key).update { it.copy(catalog = it.catalog + (id to volumes)) }
        volumes
    }
    suspend fun prepareBatch(key: String, series: Series, chapters: List<Chapter>,
        enqueue: suspend (Int) -> Unit): BatchResult {
        val unique = chapters.distinctBy { it.id }
        require(unique.size in 1..20000) { "Selecciona entre 1 y 20.000 unidades de lectura por lote" }
        var queued = 0; var skipped = 0
        val errors = linkedMapOf<Int, String>()
        for (chapter in unique) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val saved = store(key).get().chapters[chapter.id]
            if (saved?.ready == true && saved.chapter.sameEdition(chapter)) { skipped++; continue }
            try { prepare(key, series, chapter); enqueue(chapter.id); queued++ }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                errors[chapter.id] = if (e is ApiError || e is IllegalArgumentException || e is IllegalStateException)
                    e.message.orEmpty() else "No se pudo preparar esta descarga. Comprueba la conexión."
                if (e is ApiError && e.status == 401) break
            }
        }
        return BatchResult(queued, skipped, errors)
    }
    suspend fun prepare(key: String, series: Series, chapter: Chapter) = payloadLock(key, chapter.id).withLock { withContext(Dispatchers.IO) {
        val account = active()?.takeIf { it.key == key } ?: error("Inicia sesión")
        check(account.canDownload) { "Activa el permiso Download de tu usuario en Kavita" }
        require(chapter.pages in 1..20000) { "Esta lectura no tiene páginas compatibles" }
        val store = store(key)
        val old = store.get().chapters[chapter.id]
        if (old != null && !old.chapter.sameEdition(chapter)) {
            check(!store.get().pending.containsKey(chapter.id)) { "La edición cambió y tienes progreso pendiente. Resuélvelo antes de descargar la nueva edición." }
            check(!readers.contains("$key:${chapter.id}")) { "Cierra esta lectura antes de cambiar de edición" }
        }
        val progress = kotlinx.coroutines.runInterruptible { api(key).remoteProgress(chapter.id) }
        // Preserve the original download if refreshing from the server fails.
        if (old != null && !old.chapter.sameEdition(chapter)) store.deletePayload(chapter.id)
        val enriched = progress.copy(chapterId = chapter.id, seriesId = series.id, libraryId = series.libraryId, volumeId = chapter.volumeId)
        store.update { s ->
            val previous = s.chapters[chapter.id]?.takeIf { it.chapter.sameEdition(chapter) }
            s.copy(chapters = s.chapters + (chapter.id to (previous?.copy(chapter = chapter, series = series)
                ?: SavedChapter(chapter, series, (chapter.format ?: series.format) == 3))),
                syncIssues = s.syncIssues - chapter.id,
                progress = if (s.pending.containsKey(chapter.id) || readers.contains("$key:${chapter.id}")) s.progress else s.progress + (chapter.id to enriched))
        }
    } }
    suspend fun sync(key: String, includeBookmarks: Boolean = true) = syncLock.withLock { withContext(Dispatchers.IO) {
        val store = store(key); val api = api(key)
        try {
            val snapshot = store.get().pending.toMap()
            for ((id, pending) in snapshot) {
                try {
                    val saved = store.get().chapters[id] ?: continue
                    val currentChapter = cancellableApiCall { api.get<Chapter>("api/Series/chapter?chapterId=$id") }
                    if (!saved.chapter.sameEdition(currentChapter)) {
                        store.update { s -> s.copy(pending = s.pending.mapValues { (k,v) -> if (k == id) v.copy(error = "El archivo cambió en el servidor. Revisa la edición antes de sincronizar.") else v }) }
                        continue
                    }
                    val remote = cancellableApiCall { api.remoteProgress(id) }
                    when (SyncPolicy.decide(pending, remote, store.get().settings.preferLocalChanges)) {
                        SyncDecision.ACKNOWLEDGE -> store.update { SyncPolicy.acknowledge(it, id, pending.revision, remote) }
                        SyncDecision.CONFLICT -> store.update { s -> s.copy(pending = s.pending.mapValues { (k,v) -> if(k == id) v.copy(conflict = remote, error = null) else v }) }
                        SyncDecision.SEND -> {
                            // Recheck revision before network write; new changes remain queued even if made during POST.
                            if (store.get().pending[id]?.revision != pending.revision) continue
                            cancellableApiCall { api.save(pending.local) }
                            val confirmed = cancellableApiCall { api.remoteProgress(id) }
                            if (!confirmed.samePosition(pending.local)) throw IllegalStateException("Kavita no confirmó la posición enviada")
                            store.update { SyncPolicy.acknowledge(it, id, pending.revision, confirmed) }
                        }
                    }
                } catch (e: ApiError) {
                    if (e.status == 401) throw e
                    store.update { s -> s.copy(pending = s.pending.mapValues { (k,v) -> if(k == id) v.copy(error = e.message) else v }) }
                } catch (e: IllegalStateException) {
                    // CancellationException is an IllegalStateException too: never swallow it.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    store.update { s -> s.copy(pending = s.pending.mapValues { (k,v) -> if(k == id) v.copy(error = e.message) else v }) }
                }
            }
            // A missing title must not stop other titles. Never replace progress while its reader is open.
            for ((id, saved) in store.get().chapters) if (!store.get().pending.containsKey(id) && !readers.contains("$key:$id")) {
                try {
                    val current = cancellableApiCall { api.get<Chapter>("api/Series/chapter?chapterId=$id") }
                    if (!saved.chapter.sameEdition(current)) {
                        store.update { it.copy(syncIssues = it.syncIssues + (id to "El archivo cambió en Kavita. La copia offline conserva la edición anterior.")) }
                        continue
                    }
                    val remote = cancellableApiCall { api.remoteProgress(id) }
                    store.update { s -> if (s.pending.containsKey(id) || readers.contains("$key:$id")) s
                        else s.copy(progress = s.progress + (id to remote), syncIssues = s.syncIssues - id) }
                } catch (e: ApiError) {
                    if (e.status !in listOf(403, 404)) throw e
                    store.update { it.copy(syncIssues = it.syncIssues + (id to e.message.orEmpty())) }
                }
            }
            if (includeBookmarks) BookmarkSync(store, api).sync()
            store.update { it.copy(lastSync = System.currentTimeMillis(), syncMessage =
                if (it.pending.isEmpty() && it.syncIssues.isEmpty() && it.bookmarkIssues.isEmpty() && it.bookmarks.none { b -> b.dirty }) "Todo sincronizado" else "Hay progreso pendiente o avisos por revisar") }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            store.update { it.copy(syncMessage = if (e is ApiError) e.message.orEmpty() else if (e is IllegalStateException) e.message.orEmpty() else "Sin conexión con Kavita. Progreso guardado en este dispositivo.") }
            throw e
        }
    } }
    suspend fun resolve(key: String, id: Int, useLocal: Boolean) = syncLock.withLock { withContext(Dispatchers.IO) {
        val store = store(key); val pending = store.get().pending[id] ?: return@withContext
        val observed = pending.conflict ?: return@withContext
        val remote = cancellableApiCall { api(key).remoteProgress(id) }
        if (!remote.samePosition(observed) || remote.lastModifiedUtc != observed.lastModifiedUtc) {
            store.update { s -> s.copy(pending = s.pending.mapValues { (k,v) -> if(k == id) v.copy(conflict = remote) else v }) }
            error("La posición del servidor volvió a cambiar. Revisa el conflicto de nuevo.")
        }
        store.update { s ->
            if (s.pending[id]?.revision != pending.revision) s
            else if (useLocal) s.copy(pending = s.pending + (id to pending.copy(base = remote, conflict = null, revision = pending.revision + 1, restored = false)))
            else s.copy(pending = s.pending - id, progress = s.progress + (id to remote))
        }
    } }
    suspend fun resolveBookmark(key: String, id: String, keepLocalCopy: Boolean) = syncLock.withLock {
        withContext(Dispatchers.IO) {
            store(key).update { s ->
                val b = s.bookmarks.find { it.id == id && it.conflict } ?: return@update s
                if (keepLocalCopy && !b.deleted && s.chapters[b.chapterId]?.epub == true) {
                    val copy = b.copy(id = java.util.UUID.randomUUID().toString(),
                        title = b.title.take(65) + " (móvil ${java.util.UUID.randomUUID().toString().take(8)})",
                        remote = null, conflict = false, error = null, revision = b.revision + 1, restored = false)
                    s.copy(bookmarks = s.bookmarks.filterNot { it.id == id } + copy)
                } else s.copy(bookmarks = s.bookmarks.filterNot { it.id == id })
            }
        }
    }
}
