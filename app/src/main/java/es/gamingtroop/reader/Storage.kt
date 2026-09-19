package es.gamingtroop.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

interface SessionVault { fun read(): Account?; fun save(account: Account); fun clear() }

class Vault(context: Context): SessionVault {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("troop-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("troop-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun read(): Account? {
        val blob = prefs.getString("encrypted", null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            codec.decodeFromString<Account>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
        } catch (_: Exception) { null } // Keep files/queue; require re-authentication if Keystore invalidates.
    }
    @Synchronized override fun save(account: Account) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("encrypted", Base64.encodeToString(cipher.iv + cipher.doFinal(codec.encodeToString(account).toByteArray()), Base64.NO_WRAP)).commit())
    }
    @Synchronized override fun clear() { prefs.edit().clear().commit() }
}

class Store(val root: File) {
    private val atomic = AtomicFile(File(root.apply { mkdirs() }, "state.json"))
    private val flow = MutableStateFlow(read())
    val states = flow.asStateFlow()
    private fun read(): LocalState {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return LocalState()
        val text = atomic.openRead().use { it.readBytes().decodeToString() }
        val state = codec.decodeFromString<LocalState>(text)
        // Older builds persisted defaults too, so legacy "swipe" cannot be distinguished
        // from an explicit choice. Apply the requested new default ONCE, preserving all
        // other data. Choices made in alpha10+ (including swipe) survive future openings.
        if (codec.parseToJsonElement(text).jsonObject["settings"]?.jsonObject?.containsKey("pageTurnDefaultVersion") == true) return state
        val migrated = state.copy(settings = state.settings.copy(pageTurnMode = "edges", pageTurnDefaultVersion = 1))
        val output = atomic.startWrite()
        try { output.write(codec.encodeToString(migrated).toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
        return migrated
    }
    @Synchronized fun get() = flow.value
    @Synchronized fun update(block: (LocalState) -> LocalState) {
        val next = block(flow.value)
        if (next == flow.value) return // Polling, duplicate callbacks and stale workers must not rewrite the whole library.
        val output = atomic.startWrite()
        try { output.write(codec.encodeToString(next).toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
        flow.value = next
    }
    fun chapterDir(id: Int) = File(root, "downloads/$id").apply { require(id > 0) }
    fun coverFile(id: Int) = File(root, "covers/$id.jpg").apply { require(id > 0); parentFile?.mkdirs() }
    fun progress(id: Int): Progress? = get().let { it.pending[id]?.local ?: it.progress[id] }
    // Opening a downloaded title is local history, NOT a new position to upload.
    fun opened(id: Int) = update { s ->
        val saved = s.chapters[id]?.takeIf { it.readable } ?: return@update s
        s.copy(chapters = s.chapters + (id to saved.copy(lastReadAt = System.currentTimeMillis())))
    }
    fun addBookmark(id: Int, page: Int, scroll: String?, title: String) {
        update { s ->
            val chapter = s.chapters[id]?.chapter ?: return@update s
            require(page in 0 until chapter.pages)
            val label = title.trim().take(100).ifBlank { "Página ${page + 1}" }
            val existing = s.bookmarks.find { !it.deleted && it.chapterId == id && it.page == page &&
                it.scroll.orEmpty() == scroll.orEmpty() && it.edition.sameEdition(chapter) }
            if (existing != null) return@update s // Saving a position twice is not an edit of the remote marker.
            val bookmark = Bookmark(existing?.id ?: java.util.UUID.randomUUID().toString(), id,
                label, page, scroll, existing?.createdAt ?: System.currentTimeMillis(), chapter)
            s.copy(bookmarks = s.bookmarks.filterNot { it.id == bookmark.id } + bookmark)
        }
    }
    fun removeBookmark(id: String) = update { s -> s.copy(bookmarks = s.bookmarks.map {
        if (it.id == id) it.copy(deleted = true, dirty = true, revision = it.revision + 1, conflict = false, error = null) else it
    }) }
    fun undoBookmarkDelete(id: String) = update { s -> s.copy(bookmarks = s.bookmarks.map {
        if (it.id == id) it.copy(deleted = false, dirty = true, revision = it.revision + 1, conflict = false, error = null) else it
    }) }
    fun record(id: Int, page: Int, scroll: String? = null) = update {
        position(it,id,page,scroll).copy(imagePositions = it.imagePositions - id)
    }
    fun imagePosition(id: Int): ImagePosition? = get().let { s ->
        val book = s.chapters[id] ?: return@let null
        s.imagePositions[id]?.takeIf { it.edition.sameEdition(book.chapter) &&
            it.page == s.position(book.chapter).coerceAtMost((book.chapter.pages - 1).coerceAtLeast(0)) &&
            it.fraction.isFinite() && it.fraction in 0f..1f }
    }
    fun recordImagePosition(id: Int, page: Int, fraction: Float) = update { s ->
        val book = s.chapters[id]?.takeIf { it.readable && !it.epub } ?: return@update s
        if(page !in 0 until book.chapter.pages || !fraction.isFinite()) return@update s
        // Reading the last page of an already completed title doesn't unmark it.
        val next = if(s.isRead(book.chapter) && page == book.chapter.pages - 1) s else position(s,id,page,null)
        next.copy(imagePositions = next.imagePositions + (id to ImagePosition(page,fraction.coerceIn(0f, .9999f),book.chapter)))
    }
    // Explicit read/unread differs from opening page zero to read. Persist a whole
    // selection atomically and clear local history when the user marks it unread.
    fun markRead(ids: Collection<Int>, read: Boolean) = update { original ->
        ids.fold(original) { state,id ->
            val book = state.chapters[id] ?: return@fold state
            val next = position(state,id,if(read) book.chapter.pages else 0,null).copy(imagePositions = state.imagePositions - id)
            if(read) next else next.copy(chapters = next.chapters + (id to next.chapters.getValue(id).copy(lastReadAt = 0)))
        }
    }
    private fun position(state: LocalState, id: Int, page: Int, scroll: String?): LocalState {
        val saved = state.chapters[id] ?: return state
        val base = state.pending[id]?.local ?: state.progress[id] ?: Progress(saved.series.libraryId, saved.series.id, saved.chapter.volumeId, id)
        if (base.pageNum == page.coerceIn(0, saved.chapter.pages) && base.bookScrollId.orEmpty() == scroll.orEmpty()) return state
        // Kavita returns zero series/library/volume IDs when no progress exists yet.
        val local = base.copy(libraryId = saved.series.libraryId, seriesId = saved.series.id,
            volumeId = saved.chapter.volumeId, chapterId = id,
            pageNum = page.coerceIn(0, saved.chapter.pages), bookScrollId = scroll,
            lastModifiedUtc = java.time.Instant.now().toString())
        return state.copy(progress = state.progress + (id to local), pending = state.pending + (id to SyncPolicy.queue(state.pending[id], base, local)),
            chapters = state.chapters + (id to saved.copy(lastReadAt = System.currentTimeMillis())))
    }
    // Progress and queued writes are deliberately independent of the downloaded payload.
    fun deletePayload(id: Int) {
        check(chapterDir(id).let { !it.exists() || it.deleteRecursively() }) { "No se pudo eliminar la descarga" }
        update { s -> s.copy(chapters = s.chapters.mapValues { (key, value) -> if (key == id) value.copy(ready = false, downloadRequested = false, downloadRequestId = "removed", downloadedPages = 0, totalBytes = 0, state = "Eliminado del dispositivo") else value }) }
    }
}
