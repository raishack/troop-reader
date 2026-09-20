package es.gamingtroop.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal const val TEXT_BLOCKS = "p,h1,h2,h3,h4,li,blockquote"
data class BookTextBlock(val page: Int, val index: Int, val text: String)
data class BookSearchHit(val page: Int, val block: Int, val start: Int, val end: Int, val excerpt: String) {
    val anchor get() = "@text:$block:$start:$end"
}
object EpubText {
    fun blocks(html: String, page: Int): List<BookTextBlock> {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script,style,noscript").remove()
        var elements = body.select(TEXT_BLOCKS).filter { it.select(TEXT_BLOCKS).size == 1 }
        if(elements.isEmpty()) elements = body.select("div").filter { it.select("div").size == 1 }
        if(elements.isEmpty()) elements = listOf(body)
        fun text(node: Node): String = when(node) {
            is TextNode -> node.wholeText
            is Element -> node.childNodes().joinToString("") { text(it) }
            else -> ""
        }
        return elements.mapIndexed { index, el -> BookTextBlock(page,index,text(el)) }
    }
    suspend fun search(store: Store, saved: SavedChapter, query: String, limit: Int = 200): List<BookSearchHit> {
        require(saved.epub && saved.readable)
        val needle = query.trim().take(200); if(needle.length < 2) return emptyList()
        val hits = mutableListOf<BookSearchHit>()
        for(page in 0 until saved.readablePages) {
            currentCoroutineContext().ensureActive()
            val file = File(store.chapterDir(saved.chapter.id),"$page.html")
            if(!file.isFile || file.length() > 8L * 1024 * 1024) continue
            for(block in blocks(file.readText(),page)) {
                var offset = 0
                while(offset < block.text.length) {
                    currentCoroutineContext().ensureActive()
                    val at = block.text.indexOf(needle,offset,ignoreCase=true); if(at < 0) break
                    val end = at + needle.length
                    hits += BookSearchHit(page,block.index,at,end,block.text.substring((at-55).coerceAtLeast(0),(end+100).coerceAtMost(block.text.length)).replace(Regex("\\s+")," "))
                    if(hits.size >= limit) return hits
                    offset = end
                }
            }
        }
        return hits
    }
    fun speechChunks(text: String, max: Int = 3000): List<String> {
        require(max > 0)
        val result = mutableListOf<String>(); var rest = text.trim()
        while(rest.isNotEmpty()) {
            var end = minOf(rest.length,max)
            if(end < rest.length) { val boundary = rest.lastIndexOf(' ',end); if(boundary > max / 2) end = boundary }
            // Never split a UTF-16 surrogate pair.
            if(end < rest.length && end > 0 && Character.isHighSurrogate(rest[end-1])) end--
            if(end == 0) end = minOf(2,rest.length)
            result += rest.substring(0,end); rest = rest.substring(end).trimStart()
        }
        return result
    }
}
fun Store.saveNote(note: BookNote) = update { s ->
    val book = s.chapters[note.chapterId] ?: error(tr(R.string.tr_119))
    require(book.epub && note.edition.sameEdition(book.chapter) && note.page in 0 until book.chapter.pages)
    require(note.block >= 0 && note.start >= 0 && note.end > note.start && note.quote.isNotBlank() && note.quote.length <= 10000 && note.comment.length <= 4000)
    s.copy(notes = s.notes.filterNot { it.id == note.id } + note)
}
fun notesMarkdown(state: LocalState): String = buildString {
    append(tr(R.string.tr_120))
    for(n in state.notes.sortedBy { it.createdAt }) {
        append("## ").append(state.chapters[n.chapterId]?.series?.name.orEmpty().replace('\n',' ')).append(tr(R.string.tr_121)).append(n.page+1).append("\n\n")
        append(n.quote.lineSequence().joinToString("\n") { "> $it" }).append("\n\n")
        if(n.comment.isNotBlank()) append(n.comment).append("\n\n")
    }
}
