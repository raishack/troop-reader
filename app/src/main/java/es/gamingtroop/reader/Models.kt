package es.gamingtroop.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

@Serializable data class Account(val server: String, val id: Int, val username: String, val token: String,
    val refreshToken: String = "", val roles: List<String> = emptyList(), val version: String = "", val imageAuthKey: String = "") {
    val key: String get() = hash("$server|$id")
    val canDownload: Boolean get() = roles.any { it.equals("Admin", true) || it.equals("Download", true) }
}
@Serializable data class Library(val id: Int, val name: String, val type: Int? = null)
@Serializable data class Series(val id: Int, val name: String, val libraryId: Int = 0, val format: Int = 0,
    val pages: Int = 0, val pagesRead: Int = 0)
@Serializable data class Chapter(val id: Int, val volumeId: Int = 0, val titleName: String = "",
    val title: String = "", val range: String = "", val pages: Int = 0, val pagesRead: Int = 0,
    val format: Int? = null, val lastModifiedUtc: String = "", val displayTitle: String = "") {
    val label get() = displayTitle.ifBlank { titleName.ifBlank { title.ifBlank { "${if(format == 3 || format == 4) "Libro" else "Tomo"} ${range.ifBlank { id.toString() }}" } } }
}
@Serializable data class Volume(val id: Int, val name: String = "", val chapters: List<Chapter> = emptyList())
@Serializable data class Toc(val title: String, val part: String = "", val page: Int, val children: List<Toc> = emptyList())
@Serializable data class Progress(val libraryId: Int = 0, val seriesId: Int = 0, val volumeId: Int = 0,
    val chapterId: Int, val pageNum: Int = 0, val bookScrollId: String? = null, val lastModifiedUtc: String = "") {
    fun samePosition(other: Progress) = pageNum == other.pageNum && bookScrollId.orEmpty() == other.bookScrollId.orEmpty()
}
@Serializable data class Pending(val base: Progress, val local: Progress, val revision: Long = 1,
    val conflict: Progress? = null, val error: String? = null, val restored: Boolean = false)
@Serializable data class SavedChapter(val chapter: Chapter, val series: Series, val epub: Boolean,
    val ready: Boolean = false, val downloadedPages: Int = 0, val totalBytes: Long = 0,
    val state: String = "Pendiente", val toc: List<Toc> = emptyList(), val lastReadAt: Long = 0,
    val downloadRequested: Boolean? = null, val downloadRequestId: String = "", val automatic: Boolean = false) {
    val inQueue get() = !ready && state != "Eliminado del dispositivo"
    val readablePages get() = if(ready) chapter.pages else downloadedPages.coerceIn(0,chapter.pages)
    val readable get() = readablePages > 0 && (ready || inQueue)
    fun hasPage(page: Int) = readable && page in 0 until readablePages
    val wantsDownload get() = inQueue && (downloadRequested ?: !state.contains("pausada", true))
}
@Serializable data class ReadingSettings(val fontSize: Int = 19, val lineHeight: Float = 1.6f,
    val margin: Int = 20, val theme: String = "dark", val serif: Boolean = true,
    val rtl: Boolean = false, val imageMode: String = "fit", val wifiOnly: Boolean = true,
    val preferLocalChanges: Boolean = false, val autoAdvance: Boolean = true,
    val pageTurnMode: String = "edges", val pageTurnEffect: String = "none",
    val pageTurnDefaultVersion: Int = 1, val volumeKeys: Boolean = false,
    val orientation: String = "auto")
@Serializable data class Bookmark(val id: String, val chapterId: Int, val title: String,
    val page: Int, val scroll: String? = null, val createdAt: Long,
    val edition: Chapter, val remote: RemoteBookmark? = null, val dirty: Boolean = true,
    val deleted: Boolean = false, val revision: Long = 1, val conflict: Boolean = false,
    val error: String? = null, val restored: Boolean = false)
@Serializable data class RemoteBookmark(val id: Int, val page: Int, val title: String = "",
    val scroll: String? = null, val selectedText: String? = null, val imageOffset: Int = 0)
/** Local-only viewport anchor. Kavita receives the page, never this image fraction. */
@Serializable data class ImagePosition(val page: Int, val fraction: Float, val edition: Chapter)
@Serializable data class LocalState(val libraries: List<Library> = emptyList(), val series: List<Series> = emptyList(),
    val chapters: Map<Int, SavedChapter> = emptyMap(), val progress: Map<Int, Progress> = emptyMap(),
    val pending: Map<Int, Pending> = emptyMap(), val settings: ReadingSettings = ReadingSettings(),
    val syncIssues: Map<Int, String> = emptyMap(), val coverRevision: Long = 0,
    val coverIssues: Map<String, String> = emptyMap(),
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarkIssues: Map<Int, String> = emptyMap(),
    val catalog: Map<Int, List<Volume>> = emptyMap(),
    val lastSync: Long = 0, val syncMessage: String = "Sin sincronizar",
    val imagePositions: Map<Int, ImagePosition> = emptyMap(),
    val profiles: Map<Int, ReaderProfile> = emptyMap(), val favorites: Set<Int> = emptySet(),
    val collections: List<PersonalCollection> = emptyList(), val followed: Map<Int, FollowedSeries> = emptyMap(),
    val smartDownloads: SmartDownloads = SmartDownloads(), val smartMessage: String = "",
    val serverShelves: ServerShelves = ServerShelves(),
    val notes: List<BookNote> = emptyList(), val statistics: ReadingStats = ReadingStats())

enum class SyncDecision { SEND, ACKNOWLEDGE, CONFLICT }
object SyncPolicy {
    fun decide(pending: Pending, remote: Progress, preferLocalChanges: Boolean = false): SyncDecision = when {
        remote.samePosition(pending.local) -> SyncDecision.ACKNOWLEDGE
        pending.restored -> SyncDecision.CONFLICT
        preferLocalChanges -> SyncDecision.SEND
        remote.samePosition(pending.base) && (remote.lastModifiedUtc.isBlank() || pending.base.lastModifiedUtc.isBlank() || remote.lastModifiedUtc == pending.base.lastModifiedUtc) -> SyncDecision.SEND
        else -> SyncDecision.CONFLICT
    }
    fun queue(existing: Pending?, base: Progress, local: Progress) = Pending(existing?.base ?: base, local,
        (existing?.revision ?: 0) + 1, existing?.conflict, restored = existing?.restored ?: false)
    fun acknowledge(state: LocalState, chapterId: Int, revision: Long, confirmed: Progress): LocalState {
        val current = state.pending[chapterId] ?: return state
        return if (current.revision == revision) state.copy(pending = state.pending - chapterId,
            progress = state.progress + (chapterId to confirmed))
        else state.copy(pending = state.pending + (chapterId to current.copy(base = confirmed, conflict = null)))
    }
}

/** Server editions are compared before resuming payloads or moving a saved position. */
fun Chapter.sameEdition(other: Chapter): Boolean = id == other.id && pages == other.pages &&
    (format == null || other.format == null || format == other.format) &&
    (lastModifiedUtc.isBlank() || lastModifiedUtc == other.lastModifiedUtc)
fun SavedChapter.progressFraction(progress: Progress?): Float =
    if (chapter.pages <= 0) 0f else ((progress?.pageNum ?: chapter.pagesRead).toFloat() / chapter.pages).coerceIn(0f, 1f)
