package es.gamingtroop.reader

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.File

/** Kavita may prepend the EPUB package folder to a CSS-relative font path.
 * Try the supplied key first, then normalized aliases within the SAME chapter.
 * Never retry authorization, transport or server failures with another path. */
internal fun epubResourceCandidates(file: String): List<String> {
    val segments = mutableListOf<String>()
    file.replace('\\', '/').split('/').forEach { segment -> when(segment) {
        "", "." -> Unit
        ".." -> if(segments.isNotEmpty() && segments.last() != "..") segments.removeAt(segments.lastIndex) else segments.add(segment)
        else -> segments.add(segment)
    } }
    val normalized = segments.joinToString("/")
    val packageRelative = if(segments.firstOrNull()?.lowercase() in setOf("oebps", "ops", "epub"))
        segments.drop(1).joinToString("/") else normalized
    return listOf(file, normalized, packageRelative).filter { it.isNotBlank() }.distinct()
}

/** Kavita has already expanded EPUB and numbered its spine. Keep that numbering and DOM ids. */
class OfflineHtml(private val server: String, private val chapterId: Int, private val dir: File,
    private val fetch: (String, File) -> Unit) {
    private val base = server.toHttpUrl()
    private val visited = mutableMapOf<String, String>()
    private var depth = 0
    private val urlPattern = Regex("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", RegexOption.IGNORE_CASE)
    private val importPattern = Regex("@import\\s+(['\"])(.*?)\\1", RegexOption.IGNORE_CASE)
    fun resourcePath(raw: String): String = resourcePath(raw, null)
    private fun resourcePath(raw: String, stylesheet: String?): String {
        if (raw.startsWith("#")) return raw
        if (Regex("^data:(image/|font/|application/(font-woff|vnd.ms-fontobject|x-font-))", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return raw
        // CSS returned as an EPUB resource can still contain package-relative URLs.
        // Resolve them within the book, never as arbitrary authenticated API paths.
        val relative = stylesheet != null && !raw.startsWith('/') && !Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val resolved = if (relative) {
            val packageBase = "https://epub.invalid/".toHttpUrl().newBuilder().addPathSegments(stylesheet!!).build()
            val resource = packageBase.resolve(raw) ?: error(tr(R.string.tr_271))
            require(resource.host == "epub.invalid" && resource.query == null) { tr(R.string.tr_272) }
            base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/Book/$chapterId/book-resources")
                .query(null).addQueryParameter("file", resource.pathSegments.joinToString("/"))
                .fragment(resource.fragment).build()
        } else base.resolve(raw) ?: error(tr(R.string.tr_271))
        val expected = base.encodedPath.trimEnd('/') + "/api/Book/$chapterId/book-resources"
        require(resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port && resolved.encodedPath.equals(expected, true)) { tr(R.string.tr_273) }
        val file = resolved.queryParameter("file") ?: error(tr(R.string.tr_274))
        val request = "api/Book/$chapterId/book-resources?file=" + java.net.URLEncoder.encode(file, "UTF-8")
        // Never persist Kavita's embedded apiKey, and never forward it to another origin.
        val fragment = resolved.encodedFragment?.let { "#$it" }.orEmpty()
        visited[request]?.let { return it + fragment }
        require(visited.size < 5000 && depth < 12) { tr(R.string.tr_275) }
        val css = file.substringBefore('?').endsWith(".css", true)
        val suffix = if (css) ".css" else when (file.substringAfterLast('.').lowercase()) {
            "woff", "woff2", "ttf", "otf", "jpg", "jpeg", "png", "gif", "webp", "svg" -> "." + file.substringAfterLast('.').lowercase()
            else -> ".bin"
        }
        val name = "resources/" + hash(request) + suffix
        visited[request] = name
        val target = File(dir, name)
        if (target.isFile && target.length() > 0L) return name + fragment
        // Do not mark raw/partially rewritten CSS as ready if a nested resource fails.
        val staging = File(dir, "$name.pending")
        try {
            val candidates = epubResourceCandidates(file)
            var fetchedPath = file
            for((index, candidate) in candidates.withIndex()) {
                try {
                    fetch("api/Book/$chapterId/book-resources?file=" + java.net.URLEncoder.encode(candidate, "UTF-8"), staging)
                    fetchedPath = candidate
                    break
                } catch(e: ApiError) {
                    if(e.status !in listOf(400,404) || index == candidates.lastIndex) throw e
                    staging.delete()
                }
            }
            if (css) {
                depth++
                try { staging.writeText(rewriteCss(staging.readText(), fetchedPath)) }
                finally { depth-- }
            }
            check(staging.renameTo(target)) { tr(R.string.tr_276) }
        } catch (e: Exception) {
            visited.remove(request)
            throw e
        } finally { staging.delete() }
        return name + fragment
    }
    fun rewriteCss(css: String): String = rewriteCss(css, null)
    private fun rewriteCss(css: String, stylesheet: String?): String {
        val imports = importPattern.replace(css) { m -> "@import url(${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[1]})" }
        return urlPattern.replace(imports) { m ->
            val path = resourcePath(m.groupValues[2].trim(), stylesheet)
            "url(\"${if (stylesheet != null) path.removePrefix("resources/") else path}\")"
        }
    }
    fun sanitize(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.outputSettings().prettyPrint(false)
        doc.select("script,iframe,object,embed,form,input,button,textarea,base,meta,audio,video").remove()
        doc.allElements.forEach { el ->
            el.attributes().asList().filter { it.key.startsWith("on", true) || it.key in listOf("srcdoc", "srcset", "formaction") }.forEach { el.removeAttr(it.key) }
            if (el.hasAttr("style")) el.attr("style", rewriteCss(el.attr("style")))
            if (el.tagName() == "style") el.html(rewriteCss(el.data()))
            for (attr in listOf("src", "poster", "xlink:href")) if (el.hasAttr(attr)) el.attr(attr, resourcePath(el.attr(attr)))
            if (el.hasAttr("href")) {
                when {
                    el.tagName() == "link" -> el.attr("href", resourcePath(el.attr("href")))
                    el.tagName() == "image" || el.tagName() == "use" -> el.attr("href", resourcePath(el.attr("href")))
                    el.attr("href").startsWith("#") -> Unit
                    else -> el.removeAttr("href")
                }
            }
        }
        return doc.body().html()
    }
}
