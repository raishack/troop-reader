package es.gamingtroop.reader

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineHtmlTest {
    @get:Rule val tmp=TemporaryFolder()
    @Test fun resourcesBecomeLocalAndSecretQueriesAreNotPersistedOrForwarded() {
        val calls=mutableListOf<String>(); val dir=tmp.newFolder()
        val html=OfflineHtml("https://books.test",7,dir) { path,file -> calls+=path; file.parentFile!!.mkdirs();file.writeText("image") }
        val result=html.sanitize("<div><img src='//books.test/api/Book/7/book-resources?apiKey=fixture-secret&amp;file=cover.png'><p id='original'>Text</p></div>")
        assertTrue(result.contains("resources/")); assertTrue(result.contains("id=\"original\"")); assertFalse(result.contains("fixture-secret")); assertFalse(calls.single().contains("apiKey"))
    }
    @Test fun scriptsEventsAndEmbeddedFramesAreRemoved() {
        val result=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,_ -> error("No network") }.sanitize("<div onclick='bad()'><script>bad()</script><iframe src='https://evil.test'></iframe><p>Hello</p></div>")
        assertFalse(result.contains("script"));assertFalse(result.contains("onclick"));assertFalse(result.contains("iframe"));assertTrue(result.contains("Hello"))
    }
    @Test fun externalImageCannotReceiveAccountAuthorization() {
        var called=false
        val html=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,_ -> called=true }
        assertThrows(IllegalArgumentException::class.java) { html.sanitize("<img src='https://evil.test/api/Book/7/book-resources?file=x'>") }
        assertFalse(called)
    }
    @Test fun resourcesForDifferentChapterAreRejected() {
        val html=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,_ -> error("No network") }
        assertThrows(IllegalArgumentException::class.java) { html.resourcePath("https://books.test/api/Book/8/book-resources?file=x") }
    }
    @Test fun cssFontsDownloadedAndResumeDoesNotReprocessRewrittenCss() {
        val dir=tmp.newFolder(); val calls=mutableListOf<String>()
        val html=OfflineHtml("https://books.test",7,dir) { path,file ->
            calls+=path;file.parentFile!!.mkdirs(); file.writeText(if(path.endsWith("book.css")) "body{src:url('//books.test/api/Book/7/book-resources?file=font.woff2')}" else "font")
        }
        val css=html.resourcePath("//books.test/api/Book/7/book-resources?file=book.css")
        assertEquals(2,calls.size);assertFalse(File(dir,css).readText().contains("books.test"))
        val resumed=OfflineHtml("https://books.test",7,dir) { _,_ -> error("Already cached") }
        assertEquals(css,resumed.resourcePath("//books.test/api/Book/7/book-resources?file=book.css"))
    }
    @Test fun serverBasePathIsSupported() {
        val html=OfflineHtml("https://books.test/kavita",7,tmp.newFolder()) { _,file -> file.parentFile!!.mkdirs();file.writeText("x") }
        assertTrue(html.resourcePath("//books.test/kavita/api/Book/7/book-resources?file=a.png").startsWith("resources/"))
    }
    @Test fun readerDisallowsRemoteNetworkingAndHasNoNativeBridge() {
        val html=ReaderHtml.document("<p>Reading</p>", ReadingSettings(),true)
        assertTrue(html.contains("connect-src 'none'"));assertTrue(html.contains("frame-src 'none'"));assertTrue(html.contains("book-content"))
    }
    @Test fun interruptedCssResourceIsRetriedInsteadOfCachedAsComplete() {
        val dir=tmp.newFolder();var fail=true;var cssCalls=0
        fun bundle()=OfflineHtml("https://books.test",7,dir) { path,file ->
            file.parentFile!!.mkdirs()
            if(path.endsWith("book.css")) { cssCalls++;file.writeText("a{background:url('//books.test/api/Book/7/book-resources?file=a.png')}") }
            else { if(fail) throw java.io.IOException("Connection interrupted");file.writeText("image") }
        }
        assertThrows(java.io.IOException::class.java) { bundle().resourcePath("/api/Book/7/book-resources?file=book.css") }
        fail=false
        val path=bundle().resourcePath("/api/Book/7/book-resources?file=book.css")
        assertEquals(2,cssCalls);assertFalse(File(dir,path).readText().contains("books.test"))
    }
    @Test fun quotedCssImportsAndRelativeFontsRemainAvailableOffline() {
        val dir=tmp.newFolder(); val calls=mutableListOf<String>()
        val html=OfflineHtml("https://books.test",7,dir) { path,file ->
            calls+=path; file.parentFile!!.mkdirs()
            val name=java.net.URLDecoder.decode(path.substringAfter("file="),"UTF-8")
            file.writeText(when(name) {
                "Styles/main.css" -> "@import 'nested/type.css' screen; p{background:url('../Images/paper.png')}"
                "Styles/nested/type.css" -> "@font-face{src:url('../../Fonts/book.woff2')}"
                "Fonts/book.woff2" -> "font"
                "Images/paper.png" -> "image"
                else -> error("Unexpected resource")
            })
        }
        val result=html.sanitize("<style>@import \"//books.test/api/Book/7/book-resources?file=Styles/main.css\" screen;</style><p>Book</p>")
        assertEquals(4,calls.size)
        assertTrue(result.contains("screen")); assertFalse(result.contains("books.test"))
        val styles=File(dir,"resources").listFiles()!!.filter { it.extension=="css" }
        assertEquals(2,styles.size)
        styles.forEach { assertFalse(it.readText().contains("../")); assertFalse(it.readText().contains("resources/")) }
    }
    @Test fun embeddedFontsAndCssFragmentReferencesNeedNoNetwork() {
        val html=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,_ -> error("No network") }
        val result=html.sanitize("<style>@font-face{src:url('data:font/woff2;base64,AAAA')} p{filter:url(#shadow)}</style><p>Text</p>")
        assertTrue(result.contains("data:font/woff2")); assertTrue(result.contains("#shadow"))
    }
    @Test fun relativeCssCannotEscapeToAnotherOrigin() {
        val html=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,file ->
            file.parentFile!!.mkdirs(); file.writeText("@import 'https://evil.test/book.css';")
        }
        assertThrows(IllegalArgumentException::class.java) { html.resourcePath("/api/Book/7/book-resources?file=main.css") }
    }

    @Test fun kavitaPackagePrefixedFontFallsBackToTheRealResource() {
        val calls=mutableListOf<String>();val dir=tmp.newFolder()
        val bundle=OfflineHtml("https://books.test",7,dir) { path,file ->
            val name=java.net.URLDecoder.decode(path.substringAfter("file="),"UTF-8");calls+=name
            if(name!="Fonts/Book.ttf") throw ApiError(400,"Missing resource")
            file.parentFile!!.mkdirs();file.writeText("font")
        }
        val result=bundle.sanitize("<style>@font-face{src:url('//books.test/api/book/7/book-resources?file=OEBPS/Styles/../Fonts/Book.ttf')}</style><p>Text</p>")
        assertEquals(listOf("OEBPS/Styles/../Fonts/Book.ttf","OEBPS/Fonts/Book.ttf","Fonts/Book.ttf"),calls)
        assertTrue(result.contains("resources/")); assertFalse(result.contains("books.test"))
        val resumed=OfflineHtml("https://books.test",7,dir) { _,_ -> error("A complete font must be reused") }
        assertEquals(result,resumed.sanitize("<style>@font-face{src:url('//books.test/api/book/7/book-resources?file=OEBPS/Styles/../Fonts/Book.ttf')}</style><p>Text</p>"))
    }
    @Test fun resourceFallbackDoesNotHideAuthenticationNetworkOrServerFailures() {
        for(status in listOf(401,403,429,500)) {
            var count=0
            val bundle=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,_ -> count++;throw ApiError(status,"Failure") }
            assertEquals(status,assertThrows(ApiError::class.java) { bundle.resourcePath("/api/Book/7/book-resources?file=OEBPS/Styles/../Fonts/Book.ttf") }.status)
            assertEquals(1,count)
        }
        var count=0
        val bundle=OfflineHtml("https://books.test",7,tmp.newFolder()) { _,_ -> count++;throw java.io.IOException("Disconnected") }
        assertThrows(java.io.IOException::class.java) { bundle.resourcePath("/api/Book/7/book-resources?file=OEBPS/Styles/../Fonts/Book.ttf") }
        assertEquals(1,count)
    }
    @Test fun fallbackIsBoundedAndKeepsDistinctBookPaths() {
        assertEquals(listOf("Images/cover.png"),epubResourceCandidates("Images/cover.png"))
        assertEquals(listOf("../Images/cover.png"),epubResourceCandidates("../Images/cover.png"))
        assertEquals(listOf("Part1/Styles/../Fonts/book.ttf","Part1/Fonts/book.ttf"),epubResourceCandidates("Part1/Styles/../Fonts/book.ttf"))
        var count=0;val dir=tmp.newFolder()
        val bundle=OfflineHtml("https://books.test",7,dir) { _,file -> count++;file.parentFile!!.mkdirs();file.writeText("partial");throw ApiError(404,"Not found") }
        assertThrows(ApiError::class.java) { bundle.resourcePath("/api/Book/7/book-resources?file=OEBPS/Styles/../Fonts/Book.ttf") }
        assertEquals(3,count);assertTrue(dir.walkTopDown().none { it.isFile })
    }

}
