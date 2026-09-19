package es.gamingtroop.reader

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class ReaderProfile(
    val fontSize: Int = 19, val lineHeight: Float = 1.6f, val margin: Int = 20,
    val theme: String = "dark", val serif: Boolean = true, val rtl: Boolean = false,
    val imageMode: String = "fit", val autoAdvance: Boolean = true,
    val pageTurnMode: String = "edges", val pageTurnEffect: String = "none",
    val volumeKeys: Boolean = false, val orientation: String = "auto"
) {
    fun applyTo(global: ReadingSettings) = global.copy(fontSize=fontSize, lineHeight=lineHeight, margin=margin,
        theme=theme, serif=serif, rtl=rtl, imageMode=imageMode, autoAdvance=autoAdvance,
        pageTurnMode=pageTurnMode, pageTurnEffect=pageTurnEffect, volumeKeys=volumeKeys, orientation=orientation)
    companion object {
        fun from(s: ReadingSettings) = ReaderProfile(s.fontSize,s.lineHeight,s.margin,s.theme,s.serif,s.rtl,
            s.imageMode,s.autoAdvance,s.pageTurnMode,s.pageTurnEffect,s.volumeKeys,s.orientation)
    }
}
@Serializable data class PersonalCollection(val id: String, val name: String, val seriesIds: Set<Int> = emptySet())
@Serializable data class FollowedSeries(val series: Series, val known: Set<Int> = emptySet(),
    val initialized: Boolean = false, val unread: List<Chapter> = emptyList(), val checkedAt: Long = 0,
    val error: String? = null)
@Serializable data class SmartDownloads(val enabled: Boolean = false, val wifiOnly: Boolean = true, val limitMiB: Int = 512)
@Serializable data class BookNote(val id: String, val chapterId: Int, val edition: Chapter, val page: Int,
    val block: Int, val start: Int, val end: Int, val quote: String, val comment: String = "",
    val createdAt: Long = System.currentTimeMillis())
@Serializable data class ReadingDay(val millis: Long = 0, val visited: Set<String> = emptySet(),
    val completed: Set<Int> = emptySet())
@Serializable data class ReadingStats(val enabled: Boolean = true, val days: Map<String, ReadingDay> = emptyMap())

fun LocalState.readerSettings(seriesId: Int) = profiles[seriesId]?.applyTo(settings) ?: settings
fun Store.readerSettings(seriesId: Int, transform: (ReadingSettings) -> ReadingSettings) = update { s ->
    val next = transform(s.readerSettings(seriesId))
    if(seriesId in s.profiles) s.copy(profiles = s.profiles + (seriesId to ReaderProfile.from(next))) else s.copy(settings = next)
}
fun Store.useSeriesProfile(seriesId: Int, enabled: Boolean) = update { s ->
    s.copy(profiles = if(enabled) s.profiles + (seriesId to ReaderProfile.from(s.readerSettings(seriesId))) else s.profiles - seriesId)
}
fun Store.favorite(seriesId: Int, enabled: Boolean) = update { it.copy(favorites = if(enabled) it.favorites + seriesId else it.favorites - seriesId) }
fun Store.collection(name: String): String {
    val label = name.trim().take(60); require(label.isNotEmpty())
    var id = ""
    update { s ->
        val existing = s.collections.find { it.name.equals(label,true) }
        if(existing != null) { id = existing.id; s }
        else { id = java.util.UUID.randomUUID().toString(); s.copy(collections = s.collections + PersonalCollection(id,label)) }
    }
    return id
}
fun Store.membership(collectionId: String, seriesId: Int, enabled: Boolean) = update { s ->
    s.copy(collections = s.collections.map { c -> if(c.id != collectionId) c else c.copy(seriesIds =
        if(enabled) c.seriesIds + seriesId else c.seriesIds - seriesId) })
}
fun Store.follow(series: Series, enabled: Boolean) = update { s ->
    if(!enabled) s.copy(followed = s.followed - series.id)
    else if(series.id in s.followed) s
    else s.copy(followed = s.followed + (series.id to FollowedSeries(series,
        s.catalog[series.id].orEmpty().flatMap { it.chapters }.map { it.id }.toSet(), s.catalog.containsKey(series.id))))
}
fun FollowedSeries.observed(chapters: List<Chapter>, now: Long): FollowedSeries {
    val ids = chapters.map { it.id }.toSet()
    val added = if(initialized) chapters.filter { it.id !in known } else emptyList()
    return copy(known = known + ids, initialized = true, unread = (unread + added).distinctBy { it.id }, checkedAt = now, error = null)
}
fun Store.readingTime(chapterId: Int, page: Int, millis: Long, day: String = LocalDate.now().toString()) = update { s ->
    if(!s.statistics.enabled || chapterId !in s.chapters || millis <= 0) return@update s
    val old = s.statistics.days[day] ?: ReadingDay()
    val next = old.copy(millis = old.millis + millis.coerceAtMost(30000), visited = old.visited + "$chapterId:$page")
    s.copy(statistics = s.statistics.copy(days = s.statistics.days + (day to next)))
}
fun Store.readingCompleted(chapterId: Int) = update { s ->
    if(!s.statistics.enabled) s else {
        val day = LocalDate.now().toString(); val old = s.statistics.days[day] ?: ReadingDay()
        s.copy(statistics = s.statistics.copy(days = s.statistics.days + (day to old.copy(completed = old.completed + chapterId))))
    }
}
