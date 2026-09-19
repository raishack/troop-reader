package es.gamingtroop.reader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

class ReaderApp: Application() {
    val repository by lazy { Repository(this) }
    val updates by lazy { AppUpdates(this) }
    companion object { var updateAutomationEnabled = true }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "Descargas de lectura", NotificationManager.IMPORTANCE_LOW))
        repository.active()?.let { Jobs.scheduleSync(this, it.key); DiscoveryJobs.schedule(this,it.key) }
        if(updateAutomationEnabled) updates.initialize()
    }
}
fun Context.repository() = (applicationContext as ReaderApp).repository
object Jobs {
    private fun network(wifi: Boolean = false) = Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    fun scheduleSync(context: Context, key: String) {
        if (BuildConfig.DEBUG && context.repository().active()?.server == "https://demo.invalid") return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic-$key", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network()).setInputData(workDataOf("account" to key)).addTag(key).build())
        sync(context, key)
    }
    fun sync(context: Context, key: String) {
        if(BuildConfig.DEBUG && context.repository().active()?.server == "https://demo.invalid") return
        WorkManager.getInstance(context).enqueueUniqueWork("sync-$key", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network()).setInputData(workDataOf("account" to key)).addTag(key).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun downloadTag(key: String, id: Int) = "download-$key-$id"
    private val queueLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun enqueue(context: Context, key: String, id: Int, wifi: Boolean) {
        val store = context.repository().store(key)
        val saved = store.get().chapters[id] ?: return
        if(saved.ready) return
        if(saved.automatic && !store.get().smartDownloads.enabled) return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(network(wifi || (saved.automatic && store.get().smartDownloads.wifiOnly)))
            .setInputData(workDataOf("account" to key, "chapter" to id)).addTag(key).addTag(downloadTag(key,id)).build()
        store.update { state -> state.copy(chapters = state.chapters.mapValues { (k,v) ->
            if(k == id) v.copy(downloadRequested = true, downloadRequestId = request.id.toString(),
                state = if(wifi) "Esperando Wi‑Fi" else "En cola") else v }) }
        // REPLACE is essential: KEEP silently keeps the old Wi-Fi constraint/backoff.
        WorkManager.getInstance(context).enqueueUniqueWork(downloadTag(key,id),ExistingWorkPolicy.REPLACE,request).result.get()
    }
    suspend fun download(context: Context, key: String, id: Int, automatic: Boolean = false) = withContext(Dispatchers.IO) {
        // Read the current policy under the same lock as policy changes: a long batch
        // must not enqueue later items with the old Compose snapshot's constraints.
        queueLock.withLock {
            val store=context.repository().store(key)
            store.update { s -> s.copy(chapters=s.chapters.mapValues { (chapter,b) -> if(chapter==id) b.copy(automatic=automatic) else b }) }
            enqueue(context,key,id,store.get().settings.wifiOnly)
        }
    }
    suspend fun setWifiOnly(context: Context, key: String, wifi: Boolean) = withContext(Dispatchers.IO) {
        queueLock.withLock {
            val store = context.repository().store(key)
            store.update { it.copy(settings = it.settings.copy(wifiOnly = wifi)) }
            for(book in store.get().chapters.values.filter { it.wantsDownload }) enqueue(context,key,book.chapter.id,wifi)
        }
    }
    suspend fun resumeQueue(context: Context, key: String, ids: Set<Int>) = withContext(Dispatchers.IO) {
        queueLock.withLock {
            val store=context.repository().store(key)
            // An explicit resume is a manual download, so it can override an exhausted automatic budget.
            store.update { s -> s.copy(chapters=s.chapters.mapValues { (id,b) -> if(id in ids && b.inQueue) b.copy(automatic=false) else b }) }
            val state=store.get()
            for(id in ids) if(state.chapters[id]?.inQueue == true) enqueue(context,key,id,state.settings.wifiOnly)
        }
    }
    suspend fun pause(context: Context, key: String, id: Int) = pauseQueue(context, key, setOf(id))
    suspend fun pauseQueue(context: Context, key: String, ids: Set<Int>) = withContext(Dispatchers.IO) {
        queueLock.withLock {
            val repo = context.repository(); val store = repo.store(key)
            val selected = ids.filter { store.get().chapters[it]?.inQueue == true }.toSet()
            // Invalidate the ENTIRE selection first, before waiting for any writer.
            store.update { s -> s.copy(chapters = s.chapters.mapValues { (k,v) ->
                if(k in selected) v.copy(downloadRequested = false, downloadRequestId = "paused",
                    state = "Descarga pausada. Puedes reanudarla.") else v }) }
            for(id in selected) WorkManager.getInstance(context).cancelUniqueWork(downloadTag(key,id)).result.get()
            for(id in selected) repo.payloadLock(key,id).withLock { /* Retain resumable pages. */ }
        }
    }
    suspend fun delete(context: Context, key: String, id: Int) = remove(context,key,setOf(id),false)
    suspend fun remove(context: Context, key: String, ids: Set<Int>, queueOnly: Boolean = true) = withContext(Dispatchers.IO) {
        queueLock.withLock {
            val repo = context.repository(); val store = repo.store(key)
            val selected = ids.filter { store.get().chapters[it]?.let { book -> !queueOnly || book.inQueue } == true }
            // Invalidate all writers before cancelling them, so late callbacks cannot revive removed entries.
            store.update { s -> s.copy(chapters = s.chapters.mapValues { (id,v) ->
                if(id in selected) v.copy(downloadRequested = false, downloadRequestId = "removed") else v }) }
            for(id in selected) WorkManager.getInstance(context).cancelUniqueWork(downloadTag(key,id)).result.get()
            for(id in selected) repo.payloadLock(key,id).withLock { store.deletePayload(id) }
        }
    }

}
class SyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("account") ?: return Result.failure()
        val repo = applicationContext.repository()
        if (repo.active()?.key != key || (BuildConfig.DEBUG && repo.active()?.server == "https://demo.invalid")) return Result.success()
        return try { repo.sync(key); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiError) { if (e.status == 401 || e.status == 403) Result.failure() else Result.retry() }
        catch (_: Exception) { Result.retry() }
    }
}
class DownloadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    private fun foreground(title: String, page: Int, count: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText("Guardando para leer sin conexión · $page/$count")
            .setProgress(count, page, count == 0).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val key = inputData.getString("account") ?: return@withContext Result.failure()
        val chapterId = inputData.getInt("chapter", 0)
        val repo = applicationContext.repository()
        if (repo.active()?.key != key) return@withContext Result.failure()
        val store = repo.store(key)
        fun owns(book: SavedChapter) = book.downloadRequestId.isEmpty() || book.downloadRequestId == id.toString()
        fun updateBook(transform: (SavedChapter) -> SavedChapter) = store.update { s -> s.copy(chapters = s.chapters.mapValues { (k,v) ->
            if(k == chapterId && owns(v)) transform(v) else v }) }
        fun state(message: String) = updateBook { it.copy(state = message) }
        try {
            setForeground(foreground("Troop Reader", 0, 0))
            repo.transferLock.withLock { repo.payloadLock(key, chapterId).withLock payload@{
                val saved = store.get().chapters[chapterId] ?: return@payload Result.failure()
                if (saved.ready || !owns(saved) || saved.downloadRequested == false) return@payload Result.success()
                check(repo.active()?.canDownload == true) { "Tu usuario necesita permiso de descarga" }
                val api = repo.api(key)
                // Ask Download API too: do not use reading endpoints to bypass server download permissions.
                runInterruptible { api.response("api/Download/chapter-size?chapterId=$chapterId").close() }
                val current = runInterruptible { api.get<Chapter>("api/Series/chapter?chapterId=$chapterId") }
                require(saved.chapter.sameEdition(current)) { "El archivo cambió. Elimina la descarga parcial y vuelve a seleccionarlo." }
                require(saved.chapter.pages in 1..20000) { "Número de páginas no compatible" }
                val dir = store.chapterDir(chapterId).apply { mkdirs() }
                fun allowance(): Long {
                    if(!saved.automatic) return 100L * 1024 * 1024
                    val policy = store.get().smartDownloads
                    check(policy.enabled) { "Descarga automática desactivada" }
                    val available = policy.limitMiB * 1024L * 1024 - automaticBytes(store)
                    check(available > 0) { "Límite de descargas automáticas alcanzado. Puedes ampliar el límite o descargar manualmente." }
                    return available.coerceAtMost(100L * 1024 * 1024)
                }
                val bundle = OfflineHtml(repo.active()!!.server, chapterId, dir) { path, file -> api.download(path, file, maxBytes = allowance()); Unit }
                var bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val toc = if (saved.epub) runInterruptible { api.get<List<Toc>>("api/Book/$chapterId/chapters") } else emptyList()
                updateBook { it.copy(toc = toc) }
                var lastNotification = 0L
                for (page in 0 until saved.chapter.pages) {
                    ensureActive()
                    check(repo.active()?.key == key) { "La sesión cambió" }
                    if(store.get().chapters[chapterId]?.let { !owns(it) || it.downloadRequested == false } != false) return@payload Result.success()
                    val file = File(dir, if (saved.epub) "$page.html" else "$page.img")
                    val previousBytes = file.length()
                    if (!file.exists() || file.length() == 0L) runInterruptible {
                        if (saved.epub) {
                            val raw = api.text("api/Book/$chapterId/book-page?page=$page")
                            val html = if (raw.trimStart().startsWith('"')) codec.decodeFromString<String>(raw) else raw
                            val safe = bundle.sanitize(html)
                            val partial = File(dir, "$page.html.part"); check(safe.toByteArray().size <= allowance()) { "Límite de descargas automáticas alcanzado" }; partial.writeText(safe); check(partial.renameTo(file))
                        } else api.download("api/Reader/image?chapterId=$chapterId&page=$page&extractPdf=true", file, maxBytes = allowance())
                    }
                    if (!saved.epub) {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            file.delete()
                            error("Kavita no entregó una imagen válida. Reintenta la descarga.")
                        }
                    }
                    bytes += file.length() - previousBytes
                    updateBook { it.copy(totalBytes = bytes, downloadedPages = maxOf(it.downloadedPages, page + 1), state = "Descargando ${page+1}/${saved.chapter.pages}") }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if(now - lastNotification >= 1000 || page + 1 == saved.chapter.pages) {
                        setForeground(foreground(saved.series.name, page + 1, saved.chapter.pages)); lastNotification = now
                    }
                }
                // Cache artwork even for queued tomes never scrolled into view.
                repo.ensureCover(key, CoverRef("series",saved.series.id))
                repo.ensureCover(key, CoverRef("chapter",saved.chapter.id))
                if(saved.chapter.volumeId > 0) repo.ensureCover(key, CoverRef("volume",saved.chapter.volumeId))
                ensureActive()
                bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                updateBook { it.copy(ready = true, downloadRequested = false, totalBytes = bytes, toc = toc, state = "Disponible sin conexión") }
                Result.success()
            } }
        } catch (e: CancellationException) { state(if(store.get().settings.wifiOnly) "Esperando Wi‑Fi" else "Esperando conexión"); throw e }
        catch (e: Exception) {
            state(when(e) { is ApiError -> e.message.orEmpty(); is IllegalArgumentException, is IllegalStateException -> e.message.orEmpty(); else -> "Descarga interrumpida. Comprueba la conexión y reintenta." })
            if (e is ApiError && e.status in listOf(400,401,403,404) || e is IllegalArgumentException || e is IllegalStateException) Result.failure() else Result.retry()
        }
    }
}
