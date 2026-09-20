package es.gamingtroop.reader

/** Library classification is supplied by Kavita; PDF is a format, not a library type. */
enum class ReadingCategory(private val labelId: Int) {
    MANGA(0), COMIC((R.string.tr_529)), BOOK((R.string.tr_530)), IMAGES((R.string.tr_531)),
    NOVEL((R.string.tr_532)), COMIC_VINE((R.string.tr_533)), OTHER((R.string.tr_534));
    val label get() = if(labelId == 0) "Manga" else tr(labelId)
}
fun Series.category(libraries: List<Library>): ReadingCategory = when(libraries.find { it.id == libraryId }?.type) {
    0 -> ReadingCategory.MANGA; 1 -> ReadingCategory.COMIC; 2 -> ReadingCategory.BOOK
    3 -> ReadingCategory.IMAGES; 4 -> ReadingCategory.NOVEL; 5 -> ReadingCategory.COMIC_VINE
    null -> if(format == 3 || format == 4) ReadingCategory.BOOK else ReadingCategory.MANGA
    else -> ReadingCategory.OTHER
}
fun LocalState.position(chapter: Chapter): Int = (pending[chapter.id]?.local?.pageNum
    ?: progress[chapter.id]?.pageNum ?: chapter.pagesRead).coerceIn(0, chapter.pages.coerceAtLeast(0))
fun LocalState.isRead(chapter: Chapter) = chapter.pages > 0 && position(chapter) >= chapter.pages
fun ReadingUnit.isRead(state: LocalState) = chapters.isNotEmpty() && chapters.all { state.isRead(it) }
fun LocalState.playable(chapter: Chapter) = chapters[chapter.id]?.let { it.readable && it.chapter.sameEdition(chapter) } == true
fun Series.orderedParts(state: LocalState): List<Chapter> = readingUnits(cachedVolumes(state), state.libraries).flatMap { it.chapters }
/** Resume the most recently touched unfinished download, then the first available unread part. */
fun Series.readingStart(state: LocalState): Int? {
    val available = orderedParts(state).filter { state.playable(it) }
    val recent = available.filter { !state.isRead(it) && (state.chapters[it.id]?.lastReadAt ?: 0) > 0 }
        .maxByOrNull { state.chapters[it.id]?.lastReadAt ?: 0 }
    return recent?.id ?: available.firstOrNull { !state.isRead(it) }?.id ?: available.firstOrNull()?.id
}
data class ReadingSuccessor(val chapter: Chapter?, val available: Boolean)
fun Series.successor(state: LocalState, currentId: Int): ReadingSuccessor {
    return adjacent(state,currentId,1)
}
fun Series.adjacent(state: LocalState, currentId: Int, direction: Int): ReadingSuccessor {
    require(direction == -1 || direction == 1)
    val parts = orderedParts(state)
    val index = parts.indexOfFirst { it.id == currentId }
    val next = if(index >= 0) parts.getOrNull(index + direction) else null
    return ReadingSuccessor(next, next?.let { state.playable(it) } == true)
}
fun Series.localUnits(state: LocalState): List<ReadingUnit> = readingUnits(cachedVolumes(state),state.libraries).filter { unit ->
    unit.chapters.any { state.chapters[it.id]?.let { s -> s.ready || s.inQueue } == true }
}
fun ReadingUnit.localIds(state: LocalState): Set<Int> = chapters.filter {
    state.chapters[it.id]?.let { s -> s.ready || s.inQueue } == true
}.map { it.id }.toSet()
fun LocalState.hasStarted(chapter: Chapter): Boolean = position(chapter) > 0 ||
    !(pending[chapter.id]?.local ?: progress[chapter.id])?.bookScrollId.isNullOrBlank() ||
    (chapters[chapter.id]?.lastReadAt ?: 0) > 0
fun ReadingUnit.readingLabel(state: LocalState): String = when {
    isRead(state) -> tr(R.string.tr_418)
    chapters.any { state.hasStarted(it) } -> tr(R.string.tr_282)
    else -> tr(R.string.tr_535)
}
