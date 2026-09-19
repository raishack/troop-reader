package es.gamingtroop.reader

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],application=android.app.Application::class)
class CoversTest {
    private fun png():ByteArray = java.io.ByteArrayOutputStream().also {
        Bitmap.createBitmap(10,15,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it)
    }.toByteArray()
    @Test fun coverEndpointsMatchKavitaUseHeaderAuthAndRemainAvailableOffline()=runBlocking {
        MockWebServer().use { server ->
            val a=Account(server.url("/").toString().trimEnd('/'),41,"fixture","fixture")
            val repo=Repository(RuntimeEnvironment.getApplication(),MemoryVault(a));val store=repo.store(a.key)
            for(ref in listOf(CoverRef("series",1),CoverRef("volume",1),CoverRef("chapter",1))) {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(png())))
                assertTrue(repo.ensureCover(a.key,ref))
                val req=server.takeRequest()
                assertEquals("/${ref.path}",req.path);assertEquals("Bearer fixture",req.getHeader("Authorization"))
                assertTrue(validCover(ref.file(store)))
                assertTrue(repo.ensureCover(a.key,ref)) // cache, no second HTTP call
            }
            assertEquals(3,server.requestCount);assertEquals(3L,store.get().coverRevision)
        }
    }
    @Test fun invalidImageIsRetriedAndBadRefreshNeverOverwritesGoodCover()=runBlocking {
        MockWebServer().use { server ->
            val a=Account(server.url("/").toString().trimEnd('/'),41,"fixture","fixture")
            val repo=Repository(RuntimeEnvironment.getApplication(),MemoryVault(a));val ref=CoverRef("chapter",5)
            val file=ref.file(repo.store(a.key));file.writeText("<html>bad old cache</html>")
            server.enqueue(MockResponse().setBody("<html>login</html>"))
            assertFalse(repo.ensureCover(a.key,ref));assertFalse(validCover(file))
            val image=png();server.enqueue(MockResponse().setBody(okio.Buffer().write(image)))
            assertTrue(repo.ensureCover(a.key,ref));assertArrayEquals(image,file.readBytes())
            server.enqueue(MockResponse().setResponseCode(500))
            assertFalse(repo.ensureCover(a.key,ref,force=true));assertArrayEquals(image,file.readBytes())
            assertFalse(java.io.File(file.parent,file.name+".fresh").exists())
        }
    }
}
