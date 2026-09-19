package es.gamingtroop.reader

import java.text.Normalizer
import java.util.Locale

fun String.searchKey(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

enum class OfflineSort(val label: String) { RECENT("Lectura reciente"), TITLE("Título A–Z"), SIZE("Mayor tamaño") }
enum class OfflineFilter(val label: String) { ALL("Todas"), READING("En lectura"), UNREAD("Pendientes"), READ("Descargas leídas") }
data class OfflineWork(val series: Series, val downloads: List<SavedChapter>, val bytes: Long,
    val lastRead: Long, val started: Boolean, val read: Boolean)

/** Group once instead of scanning every download again for each series comparison. */
fun LocalState.offlineWorks(): List<OfflineWork> {
    val known = series.associateBy { it.id }
    return chapters.values.filter { it.ready || it.inQueue }.groupBy { it.series.id }.map { (id, books) ->
        OfflineWork(known[id] ?: books.first().series, books, books.sumOf { it.totalBytes },
            books.maxOf { it.lastReadAt }, books.any { hasStarted(it.chapter) && !isRead(it.chapter) },
            books.all { isRead(it.chapter) })
    }
}
fun List<OfflineWork>.browse(query: String, category: String, libraries: List<Library>,
    filter: OfflineFilter, sort: OfflineSort): List<OfflineWork> {
    val needle = query.searchKey()
    val result = this.filter { work -> work.series.name.searchKey().contains(needle) &&
        (category.isBlank() || work.series.category(libraries).name == category) && when(filter) {
            OfflineFilter.ALL -> true
            OfflineFilter.READING -> work.started
            OfflineFilter.UNREAD -> !work.read
            OfflineFilter.READ -> work.read
        }
    }
    val title = compareBy<OfflineWork> { it.series.name.searchKey() }.thenBy { it.series.id }
    return result.sortedWith(when(sort) {
        OfflineSort.TITLE -> title
        OfflineSort.RECENT -> compareByDescending<OfflineWork> { it.lastRead }.then(title)
        OfflineSort.SIZE -> compareByDescending<OfflineWork> { it.bytes }.then(title)
    })
}
/** Never include queued or partially downloaded content in read-content cleanup. */
fun LocalState.readDownloads(seriesId: Int? = null): Set<Int> = chapters.values.filter {
    it.ready && (seriesId == null || it.series.id == seriesId) && isRead(it.chapter)
}.map { it.chapter.id }.toSet()
