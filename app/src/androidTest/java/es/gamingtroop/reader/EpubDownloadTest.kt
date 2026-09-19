package es.gamingtroop.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic content matching Kavita's package-prefixed stylesheet resources. */
@RunWith(AndroidJUnit4::class)
class EpubDownloadTest {
    @Test fun missingFontAliasAndInterruptedRetryPreserveCompletedSectionsAndReadingData() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext(); val repo=context.repository()
        require(repo.active()==null || repo.active()!!.server=="https://demo.invalid" || repo.active()!!.server.startsWith("http://localhost:"))
        WorkManager.getInstance(context).cancelAllWork().result.get();repo.vault.clear();Demo.install(context)
        val png=repo.store(repo.active()!!.key).coverFile(1).readBytes()
        MockWebServer().use { server ->
            server.start()
            val chapter=Chapter(700,8,titleName="Synthetic book",pages=2,format=3,lastModifiedUtc="edition1")
            val interrupted=AtomicBoolean(true);val firstPageRequests=AtomicInteger();val fontRequests=AtomicInteger()
            val writes=AtomicInteger()
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.method!="GET") { writes.incrementAndGet();return MockResponse().setResponseCode(405) }
                    val url=request.requestUrl!!
                    return when(url.encodedPath) {
                        "/api/Download/chapter-size" -> MockResponse().setBody("1000")
                        "/api/Series/chapter" -> MockResponse().setBody(codec.encodeToString(chapter))
                        "/api/Book/700/chapters" -> MockResponse().setBody("[{\"title\":\"Start\",\"page\":0}]")
                        "/api/Book/700/book-page" -> if(url.queryParameter("page")=="0") {
                            firstPageRequests.incrementAndGet()
                            MockResponse().setBody("<p id='start'>Synthetic book</p><img src='/api/Book/700/book-resources?file=../Images/cover.png'>")
                        } else MockResponse().setBody("<style>@font-face{font-family:Book;src:url('/api/Book/700/book-resources?file=OEBPS/Styles/../Fonts/Book.ttf')}</style><p id='text'>Next section</p>")
                        "/api/Book/700/book-resources" -> when(url.queryParameter("file")) {
                            "OEBPS/Styles/../Fonts/Book.ttf", "OEBPS/Fonts/Book.ttf" -> MockResponse().setResponseCode(400).setBody("Resource missing")
                            "Fonts/Book.ttf" -> { fontRequests.incrementAndGet()
                                if(interrupted.get()) MockResponse().setResponseCode(503) else MockResponse().setBody("synthetic-font") }
                            "../Images/cover.png" -> MockResponse().setBody(okio.Buffer().write(png))
                            else -> MockResponse().setResponseCode(404)
                        }
                        else -> if(url.encodedPath.startsWith("/api/Image/")) MockResponse().setBody(okio.Buffer().write(png)).setHeader("Content-Type","image/png") else MockResponse().setResponseCode(404)
                    }
                }
            }
            val account=Account(server.url("/").toString().trimEnd('/'),888,"Test", "fixture-token",roles=listOf("Download"))
            repo.vault.save(account);val store=repo.store(account.key)
            store.update { LocalState(chapters=mapOf(700 to SavedChapter(chapter,Series(9,"Synthetic",1,3),true)),progress=mapOf(700 to Progress(1,9,8,700))) }
            store.record(700,1,"id(\"text\")");store.addBookmark(700,1,"id(\"text\")","Keep this")
            val pending=store.get().pending; val bookmarks=store.get().bookmarks
            suspend fun download()=TestListenableWorkerBuilder<DownloadWorker>(context,inputData=workDataOf("account" to account.key,"chapter" to 700)).build().doWork()
            download()
            assertFalse(store.get().chapters.getValue(700).ready)
            assertEquals(1,store.get().chapters.getValue(700).downloadedPages)
            val first=File(store.chapterDir(700),"0.html").readBytes()
            assertFalse(File(store.chapterDir(700),"1.html").exists())
            assertTrue(store.chapterDir(700).walkTopDown().none { it.name.endsWith(".pending") || it.name.endsWith(".part") })
            interrupted.set(false);download()
            assertTrue(store.get().chapters.getValue(700).state, store.get().chapters.getValue(700).ready)
            assertEquals(2,store.get().chapters.getValue(700).downloadedPages)
            assertArrayEquals(first,File(store.chapterDir(700),"0.html").readBytes());assertEquals(1,firstPageRequests.get())
            assertEquals(2,fontRequests.get());assertEquals(0,writes.get())
            val reopened=Store(store.root)
            assertEquals(pending,reopened.get().pending);assertEquals(bookmarks,reopened.get().bookmarks)
            assertTrue(reopened.get().chapters.getValue(700).ready)
        }
    }
}
