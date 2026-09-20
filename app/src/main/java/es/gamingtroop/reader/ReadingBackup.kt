package es.gamingtroop.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.io.OutputStream

@Serializable data class ReadingBackup(val schema: Int = 1, val accountKey: String,
    val createdAt: Long = System.currentTimeMillis(), val state: LocalState)
object Backups {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun portable(s: LocalState) = s.copy(chapters = s.chapters.mapValues { (_,c) -> c.copy(ready=false,
        downloadedPages=0,totalBytes=0,downloadRequested=false,downloadRequestId="removed",automatic=false,state=tr(R.string.tr_269)) },
        serverShelves=ServerShelves(),coverIssues=emptyMap(),coverRevision=0,syncIssues=emptyMap(),bookmarkIssues=emptyMap(),lastSync=0,
        syncMessage=tr(R.string.tr_523),smartMessage="",
        smartDownloads=s.smartDownloads.copy(enabled=false))
    fun write(account: Account, state: LocalState, output: OutputStream) {
        val bytes = codec.encodeToString(ReadingBackup(accountKey=account.key,state=portable(state))).toByteArray()
        require(bytes.size <= MAX_BYTES) { tr(R.string.tr_524) }
        output.write(bytes); output.flush()
    }
    fun read(account: Account, input: InputStream): ReadingBackup {
        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(16384)
        while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BYTES) { tr(R.string.tr_525) };out.write(buffer,0,n) }
        val backup=codec.decodeFromString<ReadingBackup>(out.toString("UTF-8"))
        require(backup.schema==1) { tr(R.string.tr_526) }
        require(backup.accountKey==account.key) { tr(R.string.tr_527) }
        validate(backup.state)
        return backup.copy(state=portable(backup.state))
    }
    private fun profile(p: ReaderProfile) {
        require(p.fontSize in 12..32 && p.lineHeight.isFinite() && p.lineHeight in 1.2f..2.2f && p.margin in 8..48)
        require(p.theme in setOf("dark","light","sepia") && p.imageMode in setOf("fit","width","double","continuous"))
        require(p.orientation in setOf("auto","portrait","landscape"))
        require(p.pageTurnMode in setOf("edges","swipe") && p.pageTurnEffect in setOf("none","curl"))
    }
    private fun validate(s: LocalState) {
        require(s.chapters.size<=20000 && s.notes.size<=50000 && s.bookmarks.size<=50000 && s.collections.size<=500)
        profile(ReaderProfile.from(s.settings));s.profiles.values.forEach(::profile)
        require(s.profiles.keys.all { it>0 } && s.favorites.all { it>0 })
        require(s.smartDownloads.limitMiB in 128..4096)
        require(s.collections.all { it.id.length in 1..100 && it.name.length in 1..60 && it.seriesIds.all { id -> id>0 } })
        require(s.collections.map { it.id }.distinct().size==s.collections.size)
        require(s.notes.map { it.id }.distinct().size==s.notes.size && s.bookmarks.map { it.id }.distinct().size==s.bookmarks.size)
        for((id,c) in s.chapters) require(id>0 && id==c.chapter.id && c.chapter.pages in 1..20000 && c.series.id>0)
        for((id,p) in s.progress) require(s.chapters[id]?.let { p.chapterId==id && p.pageNum in 0..it.chapter.pages }==true)
        for((id,p) in s.pending) require(s.chapters[id]?.let { p.local.chapterId==id && p.base.chapterId==id && p.local.pageNum in 0..it.chapter.pages && p.base.pageNum in 0..it.chapter.pages && p.revision>0 }==true)
        for(b in s.bookmarks) require(b.id.length in 1..200 && b.title.length<=200 && b.page in 0 until b.edition.pages && b.edition.id==b.chapterId)
        for(n in s.notes) require(n.id.length in 1..100 && n.page in 0 until n.edition.pages && n.edition.id==n.chapterId &&
            n.block in 0..1000000 && n.start>=0 && n.end>n.start && n.end-n.start==n.quote.length && n.quote.length in 1..10000 && n.comment.length<=4000)
        require(s.followed.size<=20000 && s.followed.all { (id,f) -> id>0 && f.series.id==id && f.known.all { it>0 } })
        for((day,d) in s.statistics.days) {
            java.time.LocalDate.parse(day)
            require(d.millis in 0..86400000 && d.visited.size<=20000 && d.completed.all { it>0 })
        }
    }
    /** Existing mobile positions, pending changes, notes and payload always win. */
    fun merge(current: LocalState, backup: ReadingBackup): LocalState {
        validate(backup.state)
        val restored=portable(backup.state)
        val fresh = current.chapters.isEmpty()
        val compatible: (Int) -> Boolean = { id -> current.chapters[id]?.let { c -> restored.chapters[id]?.chapter?.sameEdition(c.chapter)==true } ?: true }
        val positions=restored.progress.filter { (id,_) -> id !in current.progress && id !in current.pending && compatible(id) }
        val pending=restored.pending.filter { (id,_) -> id !in current.progress && id !in current.pending && compatible(id) }
        val markers=restored.bookmarks.filter { b -> current.bookmarks.none { it.id==b.id } && compatible(b.chapterId) }.map { it.copy(restored=true,dirty=true) }
        val notes=restored.notes.filter { n -> current.notes.none { it.id==n.id } && compatible(n.chapterId) }
        val collections=restored.collections.associateBy { it.id }.toMutableMap()
        for(c in current.collections) collections[c.id]=c.copy(seriesIds=c.seriesIds+collections[c.id]?.seriesIds.orEmpty())
        val days=restored.statistics.days.toMutableMap()
        for((day,d) in current.statistics.days) {
            val old=days[day] ?: ReadingDay()
            days[day]=d.copy(millis=maxOf(d.millis,old.millis),visited=d.visited+old.visited,completed=d.completed+old.completed)
        }
        return current.copy(chapters=restored.chapters+current.chapters,
            series=(current.series+restored.series).distinctBy { it.id },libraries=(current.libraries+restored.libraries).distinctBy { it.id },
            catalog=restored.catalog+current.catalog,progress=positions+current.progress,pending=positions.mapValues { (_,p) -> Pending(p,p,restored=true) }+pending.mapValues { (_,p) -> p.copy(restored=true) }+current.pending,
            bookmarks=current.bookmarks+markers,notes=current.notes+notes,settings=if(fresh) restored.settings else current.settings,
            profiles=restored.profiles+current.profiles,favorites=current.favorites+restored.favorites,collections=collections.values.toList(),
            followed=restored.followed+current.followed,statistics=(if(fresh) restored.statistics else current.statistics).copy(days=days),
            smartDownloads=if(fresh) restored.smartDownloads else current.smartDownloads,
            imagePositions=restored.imagePositions.filter { (id,_) -> id !in current.progress && compatible(id) }+current.imagePositions)
    }
}
