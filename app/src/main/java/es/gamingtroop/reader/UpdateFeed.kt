package es.gamingtroop.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class AppRelease(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String = ""
)

// Editorial changes do not invalidate a verified APK. All install-relevant
// metadata must still agree; matching the version number alone is insufficient.
internal fun AppRelease.samePackage(other: AppRelease?): Boolean =
    other != null && copy(notes = "") == other.copy(notes = "")

object UpdatePolicy {
    const val FEED = "https://claw.raishack.es/troop-reader/latest.json"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_FEED_BYTES = 32L * 1024
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): AppRelease = json.decodeFromString<AppRelease>(text).also(::validate)
    fun validate(release: AppRelease) {
        require(release.schemaVersion == 1) { tr(R.string.tr_560) }
        require(release.packageName == BuildConfig.APPLICATION_ID && release.versionCode > 0)
        require(release.versionName.matches(Regex("[0-9A-Za-z.+_-]{1,64}")))
        require(release.minSdk in 26..1000 && release.sizeBytes in 1..MAX_APK_BYTES)
        require(release.sha256.matches(Regex("[0-9a-f]{64}")) && release.notes.length <= 8000)
        val url = release.apkUrl.toHttpUrl()
        require(url.scheme == "https" && url.host == "claw.raishack.es" && url.port == 443)
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(url.encodedPath.matches(Regex("/troop-reader/troop-reader-[0-9A-Za-z.+_-]+\\.apk")))
    }
    fun shouldCheck(lastAttempt: Long, now: Long): Boolean =
        lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= CHECK_INTERVAL_MS
    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verifyBytes(file: File, release: AppRelease) {
        require(file.length() == release.sizeBytes && hash(file) == release.sha256) {
            tr(R.string.tr_561)
        }
    }
}

// Separate client: never inherits Kavita credentials, cookies or redirects.
class UpdateFeed(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(4, TimeUnit.MINUTES).build(),
    private val feedUrl: HttpUrl = UpdatePolicy.FEED.toHttpUrl()
) {
    private fun response(url: HttpUrl) = client.newCall(Request.Builder().url(url)
        .header("Cache-Control", "no-cache").build()).execute().also {
        if (it.code != 200) { it.close(); throw IOException(tr(R.string.tr_562)) }
    }
    fun latest(): AppRelease = response(feedUrl).use { response ->
        val body = response.body ?: throw IOException(tr(R.string.tr_563))
        require(body.contentLength() <= UpdatePolicy.MAX_FEED_BYTES)
        val bytes = body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if(count < 0) break
                require(output.size() + count <= UpdatePolicy.MAX_FEED_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= UpdatePolicy.MAX_FEED_BYTES)
        UpdatePolicy.parse(bytes.toString(Charsets.UTF_8))
    }
    fun download(release: AppRelease, target: File, active: () -> Unit = {}, progress: (Int) -> Unit = {}) {
        UpdatePolicy.validate(release)
        val part = File(target.parentFile, target.name + ".part")
        try {
            target.parentFile!!.mkdirs()
            response(release.apkUrl.toHttpUrl()).use { response ->
                val body = response.body ?: throw IOException(tr(R.string.tr_564))
                require(body.contentLength() == -1L || body.contentLength() == release.sizeBytes)
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var bytes = 0L; var lastPercent = -1
                    while (true) {
                        active()
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count
                        require(bytes <= release.sizeBytes) { tr(R.string.tr_565) }
                        output.write(buffer, 0, count)
                        val percent = (100 * bytes / release.sizeBytes).toInt()
                        if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                    }
                    output.fd.sync()
                } }
            }
            active(); UpdatePolicy.verifyBytes(part, release)
            check(part.renameTo(target)) { tr(R.string.tr_566) }
        } finally { part.delete() }
    }
}
