package es.gamingtroop.reader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MemoryVault(var account: Account?): SessionVault {
    override fun read()=account
    override fun save(account: Account){ this.account=account }
    override fun clear(){account=null}
}
class ApiTest {
    @Test fun byteBudgetRejectsOversizedDownloadWithoutReplacingLocalFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100)))
            val a=Account(server.url("/").toString().trimEnd('/'),1,"fixture","fixture")
            val file=kotlin.io.path.createTempFile().toFile().apply { writeText("existing") }
            try {
                assertThrows(IllegalStateException::class.java) { KavitaApi(MemoryVault(a),a.key).download("api/image",file,maxBytes=10) }
                assertEquals("existing",file.readText());assertFalse(java.io.File(file.path+".part").exists())
            } finally { file.delete() }
        }
    }
    @Test fun apiInstancesReuseConnectionsWithoutReusingAccountHeaders() {
        MockWebServer().use { server ->
            val a = Account(server.url("/").toString().trimEnd('/'), 1, "fixture-a", "fixture-a-token")
            val b = a.copy(id = 2, username = "fixture-b", token = "fixture-b-token")
            repeat(2) { server.enqueue(MockResponse().setBody("{\"chapterId\":4,\"pageNum\":2}")) }
            KavitaApi(MemoryVault(a), a.key).remoteProgress(4)
            KavitaApi(MemoryVault(b), b.key).remoteProgress(4)
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals(0, first.sequenceNumber); assertEquals(1, second.sequenceNumber)
            assertEquals("Bearer fixture-a-token", first.getHeader("Authorization"))
            assertEquals("Bearer fixture-b-token", second.getHeader("Authorization"))
            assertNull(second.getHeader("Cookie"))
        }
    }
    @Test fun unsafeServerInputsAreRejected() {
        for(url in listOf("http://books.test","https://user:pass@books.test","https://books.test?key=secret","https://books.test/#token"))
            assertThrows(IllegalArgumentException::class.java) { KavitaApi.normalizeServer(url) }
        assertEquals("https://books.test/base",KavitaApi.normalizeServer("https://books.test/base/"))
    }
    @Test fun authenticatedCallsUseHeaderAndNoQuerySecret() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"chapterId\":4,\"pageNum\":2}"))
            val account=Account(server.url("/").toString().trimEnd('/'),1,"user","test-token")
            val progress=KavitaApi(MemoryVault(account),account.key).remoteProgress(4)
            assertEquals(2,progress.pageNum)
            val req=server.takeRequest();assertEquals("Bearer test-token",req.getHeader("Authorization"));assertEquals("/api/Reader/get-progress?chapterId=4",req.path)
        }
    }
    @Test fun redirectDoesNotForwardCredentials() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",other.url("/collect")))
            val account=Account(server.url("/").toString().trimEnd('/'),1,"user","test-token")
            assertThrows(ApiError::class.java) { KavitaApi(MemoryVault(account),account.key).remoteProgress(4) }
            assertEquals(0,other.requestCount)
        } }
    }
    @Test fun changingAccountPreventsQueuedRequestsUsingOtherIdentity() {
        val a=Account("https://books.test",1,"a","token-a");val vault=MemoryVault(a);val api=KavitaApi(vault,a.key)
        vault.save(a.copy(id=2,token="token-b"));assertThrows(ApiError::class.java) { api.remoteProgress(4) }
    }
    @Test fun expiredSessionRefreshesThenRetriesOnce() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("{\"token\":\"new-token\",\"refreshToken\":\"new-refresh\"}"))
            server.enqueue(MockResponse().setBody("{\"chapterId\":4,\"pageNum\":6}"))
            val a=Account(server.url("/").toString().trimEnd('/'),1,"user","old-token","old-refresh");val vault=MemoryVault(a)
            assertEquals(6,KavitaApi(vault,a.key).remoteProgress(4).pageNum)
            server.takeRequest();assertEquals("/api/Account/refresh-token",server.takeRequest().path)
            assertEquals("Bearer new-token",server.takeRequest().getHeader("Authorization"));assertEquals("new-token",vault.read()!!.token)
        }
    }
    @Test fun bookmarkDeletionKeepsItsMethodAfterTokenRefreshAndAllowsPunctuationInTitle() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("{\"token\":\"new-token\",\"refreshToken\":\"new-refresh\"}"))
            server.enqueue(MockResponse().setResponseCode(204))
            val a=Account(server.url("/").toString().trimEnd('/'),1,"fixture","old","refresh")
            KavitaApi(MemoryVault(a),a.key).response("api/Reader/ptoc?chapterId=4&pageNum=2&title=Una+nota...",method="DELETE").close()
            assertEquals("DELETE",server.takeRequest().method)
            assertEquals("POST",server.takeRequest().method)
            val retry=server.takeRequest();assertEquals("DELETE",retry.method)
            assertEquals("Una nota...",retry.requestUrl!!.queryParameter("title"))
        }
    }
    @Test fun legacyMediaValidationIsIdentifiedWithoutLeakingTheResponseOrRetryLoop() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":{"apiKey":["The apiKey field is required."]},"debug":"private-server-value"}"""))
            val a=Account(server.url("/").toString().trimEnd('/'),1,"fixture","fixture")
            val error=assertThrows(ApiError::class.java) {
                KavitaApi(MemoryVault(a),a.key).response("api/Reader/image?chapterId=4&page=0")
            }
            assertEquals(400,error.status);assertTrue(error.message!!.contains("imágenes"))
            assertFalse(error.message!!.contains("private-server-value"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun forbiddenCredentialQueriesAreNeverSent() {
        MockWebServer().use { server ->
            val a=Account(server.url("/").toString().trimEnd('/'),1,"fixture","fixture")
            assertThrows(IllegalArgumentException::class.java) {
                KavitaApi(MemoryVault(a),a.key).response("api/Image/series-cover?seriesId=4&apiKey=fixture")
            }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun mediaHeaderFallbackRefreshesTheEncryptedAccountWithoutReplacingTheJwt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("""{"id":1,"authKeys":[{"name":"image-only","key":"fixture-image"}]}"""))
            server.enqueue(MockResponse().setBody("image"))
            val a=Account(server.url("/").toString().trimEnd('/'),1,"fixture","fixture-jwt")
            val vault=MemoryVault(a)
            KavitaApi(vault,a.key).response("api/Image/series-cover?seriesId=4").use { assertEquals("image",it.body!!.string()) }
            server.takeRequest();assertEquals("/api/Account",server.takeRequest().path)
            val req=server.takeRequest();assertEquals("fixture-image",req.getHeader("x-api-key"))
            assertEquals("Bearer fixture-jwt",req.getHeader("Authorization"))
            assertFalse(req.path!!.contains("fixture"));assertEquals("fixture-image",vault.read()!!.imageAuthKey)
        }
    }

}
