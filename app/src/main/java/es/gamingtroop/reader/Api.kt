package es.gamingtroop.reader

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

// OkHttp may report an interrupted socket as IOException, not InterruptedException.
// Preserve coroutine cancellation so it cannot become a sync error or retry.
internal suspend fun <T> cancellableApiCall(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (e: Exception) {
    kotlin.coroutines.coroutineContext.ensureActive()
    throw e
}

class ApiError(val status: Int, message: String): IOException(message)
class KavitaApi(private val vault: SessionVault, private val accountKey: String) {
    companion object {
        // Share sockets/threads, not identity: Authorization is added per request
        // from the vault. No cookies or account-specific interceptors are stored.
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        private fun imageKey(user: JsonObject): String = user["authKeys"]?.jsonArray?.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "image-only"
        }?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull.orEmpty()
        fun normalizeServer(value: String): String {
            val url = value.trim().trimEnd('/').toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { tr(R.string.tr_000) }
            return url.toString().trimEnd('/')
        }
        fun login(vault: SessionVault, server: String, username: String, password: String): Account {
            val base = normalizeServer(server)
            val payload = buildJsonObject { put("username", username.trim()); put("password", password) }
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("$base/api/Account/login").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiError(r.code, if (r.code == 401 || r.code == 400) tr(R.string.tr_001) else tr(R.string.tr_002, r.code))
                val j = codec.parseToJsonElement(r.body!!.string()).jsonObject
                fun value(k: String) = j[k]?.jsonPrimitive?.contentOrNull.orEmpty()
                val a = Account(base, j["id"]!!.jsonPrimitive.int, value("username"), value("token"), value("refreshToken"),
                    j["roles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), value("kavitaVersion"), imageKey(j))
                require(a.token.isNotBlank() && a.id > 0) { tr(R.string.tr_003) }
                vault.save(a)
                return a
            }
        }
    }
    private fun account() = vault.read()?.takeIf { it.key == accountKey } ?: throw ApiError(401, tr(R.string.tr_004))
    private fun refresh(previous: Account): Boolean = synchronized(refreshLock) {
        val current = account()
        if (current.token != previous.token) return@synchronized true
        if (current.refreshToken.isBlank()) return@synchronized false
        val payload = buildJsonObject { put("token", current.token); put("refreshToken", current.refreshToken) }
        val req = Request.Builder().url("${current.server}/api/Account/refresh-token")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@synchronized false
            val j = codec.parseToJsonElement(r.body!!.string()).jsonObject
            val token = j["token"]?.jsonPrimitive?.contentOrNull ?: return@synchronized false
            if (vault.read()?.key != accountKey) return@synchronized false
            vault.save(current.copy(token = token, refreshToken = j["refreshToken"]?.jsonPrimitive?.contentOrNull ?: current.refreshToken))
            true
        }
    }
    private fun imageAccount(): Account = synchronized(refreshLock) {
        val current = account()
        if(current.imageAuthKey.isNotBlank()) return@synchronized current
        val user = codec.parseToJsonElement(text("api/Account")).jsonObject
        require(user["id"]?.jsonPrimitive?.intOrNull == current.id) { tr(R.string.tr_005) }
        val key = imageKey(user)
        if(key.isBlank()) throw ApiError(401, tr(R.string.tr_006))
        val latest = account()
        latest.copy(imageAuthKey = key).also(vault::save)
    }
    fun response(path: String, json: String? = null, retry: Boolean = true, method: String? = null, download: Boolean = false,
        imageAuth: Boolean = false): Response {
        require(path.startsWith("api/") && !path.substringBefore('?').contains(".."))
        val account = if(imageAuth) imageAccount() else account()
        val url = "${account.server}/$path".toHttpUrl()
        require(url.queryParameterNames.none { it.equals("apiKey",true) || it.equals("access_token",true) })
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${account.token}")
            .header("User-Agent", "TroopReader/0.1 Android")
        if(imageAuth) req.header("x-api-key", account.imageAuthKey)
        if (json != null) req.post(json.toRequestBody("application/json".toMediaType()))
        if (method == "DELETE") req.delete()
        val call = client.newCall(req.build())
        call.timeout().timeout(if(download) 120 else 30, TimeUnit.SECONDS)
        val res = call.execute()
        if (res.code == 401 && retry) { res.close(); if (refresh(account)) return response(path, json, false, method, download, imageAuth); throw ApiError(401, tr(R.string.tr_010)) }
        if (!res.isSuccessful) {
            val code = res.code
            val fieldErrors = if(code == 400) runCatching {
                codec.parseToJsonElement(res.peekBody(8192).string()).jsonObject["errors"]?.jsonObject?.keys.orEmpty()
            }.getOrDefault(emptySet()) else emptySet()
            res.close()
            val media = path.substringBefore('?').let { it.startsWith("api/Image/",true) || it.equals("api/Reader/image",true) }
            if(code == 400 && fieldErrors.any { it.equals("apiKey",true) } && media) {
                throw ApiError(400, tr(R.string.tr_011, path.substringBefore('?')))
            }
            if(code == 403 && media && !imageAuth) return response(path,json,retry,method,download,true)
            throw ApiError(code, when(code) { 401 -> tr(R.string.tr_012); 403 -> tr(R.string.tr_013); 404 -> tr(R.string.tr_014); 400 -> tr(R.string.tr_015, path.substringBefore('?')); else -> tr(R.string.tr_016, code) })
        }
        return res
    }
    fun text(path: String, json: String? = null): String = response(path, json).use { it.body!!.string() }
    inline fun <reified T> get(path: String): T = codec.decodeFromString(text(path))
    fun save(progress: Progress) { response("api/Reader/progress", codec.encodeToString(progress)).close() }
    fun remoteProgress(id: Int) = get<Progress>("api/Reader/get-progress?chapterId=$id")
    fun download(path: String, file: File, extendedTimeout: Boolean = true, maxBytes: Long = 100L * 1024 * 1024): Long {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        try {
            response(path, download = extendedTimeout).use { r ->
                r.body!!.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException(tr(R.string.tr_017))
                        val n = input.read(buffer); if (n < 0) break
                        size += n; if (size > maxBytes) throw IllegalStateException(tr(R.string.tr_018))
                        if (part.usableSpace < 32L * 1024 * 1024) throw IOException(tr(R.string.tr_019))
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                } }
            }
            check(part.length() > 0) { tr(R.string.tr_020) }
            check(part.renameTo(file)) { tr(R.string.tr_021) }
            return file.length()
        } finally { part.delete() }
    }
}
private val refreshLock = Any()
