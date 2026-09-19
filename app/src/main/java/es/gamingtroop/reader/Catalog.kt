package es.gamingtroop.reader

/** Kavita's Chapter DTO is an API/storage unit, not the label shown to a reader. */
data class ReadingUnit(val key: String, val title: String, val chapters: List<Chapter>, val cover: CoverRef) {
    val pages: Long get() = chapters.sumOf { it.pages.toLong() }
    fun ready(state: LocalState) = chapters.isNotEmpty() && chapters.all { state.chapters[it.id]?.let { s -> s.ready && s.chapter.sameEdition(it) } == true }
    fun readable(state: LocalState) = next(state)?.let { id -> chapters.firstOrNull { it.id == id }?.let { state.playable(it) } } == true
    fun next(state: LocalState): Int? = chapters.firstOrNull { !state.isRead(it) }?.id ?: chapters.firstOrNull()?.id
}
fun Series.isBook(libraries: List<Library>): Boolean = when(libraries.find { it.id == libraryId }?.type) {
    2, 4 -> true
    0, 1, 3, 5 -> false
    else -> format == 3 || format == 4
}
fun Series.unitsName(libraries: List<Library>) = if(isBook(libraries)) "libros" else "tomos"
fun Series.readingUnits(volumes: List<Volume>, libraries: List<Library>): List<ReadingUnit> {
    val book = isBook(libraries)
    val seen = mutableSetOf<Int>()
    return volumes.flatMap { v ->
        val parts = v.chapters.filter { it.id > 0 && seen.add(it.id) }.map { c -> c.copy(volumeId = c.volumeId.takeIf { it > 0 } ?: v.id, format = c.format ?: format) }
        val namedVolume = v.id > 0 && v.name.isNotBlank() && v.name.toDoubleOrNull()?.let { it > 0 } != false
        if (!book && namedVolume && parts.isNotEmpty()) {
            val name = if(v.name.startsWith("Tomo", true)) v.name else "Tomo ${v.name}"
            val labelled = parts.mapIndexed { i, c -> c.copy(displayTitle = if(parts.size == 1) name else "$name · Parte ${i + 1}") }
            listOf(ReadingUnit("v${v.id}", name, labelled, CoverRef("volume", v.id)))
        } else parts.map { c ->
            val title = c.titleName.ifBlank { c.title }.ifBlank { "${if(book) "Libro" else "Tomo"} ${c.range.takeIf { it.isNotBlank() && it != "0" } ?: name}" }
            ReadingUnit("c${c.id}", title, listOf(c.copy(displayTitle = title)), CoverRef("chapter", c.id))
        }
    }
}
fun Series.cachedVolumes(state: LocalState): List<Volume> {
    val known = state.catalog[id].orEmpty()
    val ids = known.flatMap { it.chapters }.map { it.id }.toSet()
    val missing = state.chapters.values.filter { it.series.id == id && it.chapter.id !in ids }
        .sortedWith(compareBy({ it.chapter.range.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.chapter.volumeId }, { it.chapter.id }))
        .groupBy { it.chapter.volumeId }.toMutableMap()
    // A saved part absent in a refreshed catalogue must not create a duplicate volume key.
    val merged = known.map { volume -> volume.copy(chapters = volume.chapters + missing.remove(volume.id).orEmpty().map { it.chapter }) }
    return merged + missing.map { (volume, books) -> Volume(volume,
        books.first().chapter.displayTitle.substringBefore(" · Parte").takeIf { it.startsWith("Tomo ") }.orEmpty(), books.map { it.chapter }) }
}
fun selectedParts(units: List<ReadingUnit>, keys: Set<String>): List<Chapter> = units.filter { it.key in keys }.flatMap { it.chapters }.distinctBy { it.id }

fun Chapter.labelFor(series: Series, libraries: List<Library>): String = displayTitle.ifBlank {
    titleName.ifBlank { title.ifBlank {
        "${if(series.isBook(libraries)) "Libro" else "Tomo"} ${range.takeIf { it.isNotBlank() && it != "0" } ?: series.name}"
    } }
}
