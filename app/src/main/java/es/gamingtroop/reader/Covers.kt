package es.gamingtroop.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** No remote URL or auth material is used as an image-loader cache key. */
data class CoverRef(val kind: String, val id: Int) {
    init { require(kind in setOf("series", "volume", "chapter") && id > 0) }
    val path get() = "api/Image/$kind-cover?${kind}Id=$id"
    fun file(store: Store) = if(kind == "series") store.coverFile(id) else File(store.root, "covers/$kind-$id.img").apply { parentFile?.mkdirs() }
}
fun validCover(file: File): Boolean {
    if(!file.isFile || file.length() == 0L) return false
    val header = file.inputStream().use { val bytes = ByteArray(16); val count = it.read(bytes); bytes.copyOf(count.coerceAtLeast(0)) }
    val imageHeader = header.take(3) == listOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()) ||
        header.take(4) == listOf(0x89.toByte(),0x50.toByte(),0x4e.toByte(),0x47.toByte()) ||
        String(header, Charsets.ISO_8859_1).let { it.startsWith("GIF8") || (it.startsWith("RIFF") && it.contains("WEBP")) || it.contains("ftypavif") || it.contains("ftypavis") }
    if(!imageHeader) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000
}
suspend fun Repository.ensureCover(key: String, ref: CoverRef, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
    coverLocks.getOrPut("$key:${ref.kind}:${ref.id}") { kotlinx.coroutines.sync.Mutex() }.withLock {
        val store = store(key); val file = ref.file(store)
        if(!force && validCover(file) && System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000L) return@withLock true
        val fresh = File(file.parentFile, file.name + ".fresh")
        try {
            coverSlots.withPermit { runInterruptible { api(key).download(ref.path, fresh, extendedTimeout = false) } }
            check(validCover(fresh)) { "Kavita no devolvió una carátula válida" }
            check(fresh.renameTo(file)) { "No se pudo guardar la carátula" }
            store.update { it.copy(coverRevision = it.coverRevision + 1, coverIssues = it.coverIssues - "${ref.kind}:${ref.id}") }
            true
        } catch(e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = if(e is ApiError) e.message.orEmpty() else "No se pudo cargar la carátula. Comprueba la conexión."
            store.update { it.copy(coverIssues = it.coverIssues + ("${ref.kind}:${ref.id}" to message)) }
            false // Keep the last good cover. Retry on next foreground/refresh.
        }
        finally { fresh.delete() }
    }
}
@Composable fun Cover(repo: Repository, account: Account, ref: CoverRef, title: String, modifier: Modifier = Modifier,
    online: Boolean = false, refresh: Int = 0, fallback: CoverRef? = null) {
    val store = remember(account.key) { repo.store(account.key) }
    val state by store.states.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate)
    LaunchedEffect(account.key, ref, online, refresh) {
        if(online && account.server != "https://demo.invalid") {
            repo.ensureCover(account.key, ref)
            fallback?.let { if(!ref.file(store).exists()) repo.ensureCover(account.key, it) }
        }
    }
    val file = remember(ref, state.coverRevision) { ref.file(store).takeIf { it.exists() } ?: fallback?.file(store) }
    val stamp = remember(file, state.coverRevision) { file?.lastModified() }
    Box(modifier.background(Surface), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MenuBook, "Sin carátula · $title", Modifier.size(30.dp), tint = Green)
        if(file != null) DisplayImage(ImageRequest.Builder(LocalContext.current).data(file)
            .memoryCacheKey("${file.path}:$stamp").build(), "Carátula · $title", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}
