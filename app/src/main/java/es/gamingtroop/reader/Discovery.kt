package es.gamingtroop.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object DiscoveryJobs {
    private val lock = Mutex()
    fun schedule(context: Context, key: String) {
        if(!ReaderApp.updateAutomationEnabled || context.repository().active()?.server == "https://demo.invalid") return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("followed-$key", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FollowedWorker>(12,TimeUnit.HOURS).setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()).setInputData(workDataOf("account" to key)).addTag(key).build())
    }
    fun next(context: Context, key: String, chapterId: Int) {
        val s = context.repository().store(key).get()
        if(!s.smartDownloads.enabled || context.repository().active()?.server == "https://demo.invalid") return
        WorkManager.getInstance(context).enqueueUniqueWork("smart-$key-$chapterId",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SmartNextWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(
                if(s.smartDownloads.wifiOnly || s.settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setInputData(workDataOf("account" to key,"chapter" to chapterId)).addTag(key).addTag("smart-$key").build())
    }
    suspend fun configure(context: Context, key: String, settings: SmartDownloads) {
        require(settings.limitMiB in 128..4096)
        val store = context.repository().store(key)
        store.update { it.copy(smartDownloads = settings) }
        WorkManager.getInstance(context).cancelAllWorkByTag("smart-$key")
        val auto = store.get().chapters.values.filter { it.automatic && it.wantsDownload }.map { it.chapter.id }.toSet()
        if(!settings.enabled) Jobs.pauseQueue(context,key,auto)
        else Jobs.setWifiOnly(context,key,store.get().settings.wifiOnly)
    }
    suspend fun check(repo: Repository, key: String): Int = lock.withLock { withContext(Dispatchers.IO) {
        val store = repo.store(key); var added = 0
        for((id, followed) in store.get().followed) {
            currentCoroutineContext().ensureActive()
            if(repo.active()?.key != key) return@withContext added
            try {
                val chapters = repo.volumes(key,id).flatMap { it.chapters }
                store.update { s ->
                    val current = s.followed[id] ?: return@update s
                    // A newly followed item establishes a baseline, never announces its whole catalogue.
                    val next = current.observed(chapters,System.currentTimeMillis())
                    added += next.unread.count { c -> current.unread.none { it.id == c.id } }
                    s.copy(followed = s.followed + (id to next))
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { store.update { s -> s.followed[id]?.let {
                s.copy(followed = s.followed + (id to it.copy(error = tr(R.string.tr_086))))
            } ?: s } }
        }
        if(added > 0) notify(repo.context,store.get().followed.values.sumOf { it.unread.size })
        added
    } }
    private fun notify(context: Context, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("new-chapters",tr(R.string.tr_087),NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context,MainActivity::class.java).putExtra("show_personal",true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context,1700,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { nm.notify(1700,NotificationCompat.Builder(context,"new-chapters").setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(tr(R.string.tr_088)).setContentText(tr(R.string.tr_089, count))
            .setContentIntent(pending).setAutoCancel(true).build()) } catch(_: SecurityException) { }
    }
    suspend fun prepareNext(repo: Repository, key: String, currentId: Int): List<Int> = lock.withLock { withContext(Dispatchers.IO) {
        val store = repo.store(key)
        if(repo.active()?.key != key || repo.active()?.canDownload != true || !store.get().smartDownloads.enabled) return@withContext emptyList()
        val current = store.get().chapters[currentId] ?: return@withContext emptyList()
        repo.volumes(key,current.series.id)
        val snapshot = store.get()
        val parts = smartNextParts(snapshot,currentId)
        if(parts.isEmpty()) return@withContext emptyList()
        val limit = snapshot.smartDownloads.limitMiB * 1024L * 1024
        var remaining = limit - automaticBytes(store)
        val selected = mutableListOf<Int>()
        for(part in parts) {
            currentCoroutineContext().ensureActive()
            if(!store.get().smartDownloads.enabled) break
            val existing = store.get().chapters[part.id]
            if(existing?.ready == true || existing?.inQueue == true || existing?.downloadRequestId == "removed") continue // Never revive an explicitly paused download.
            val size = cancellableApiCall { repo.api(key).text("api/Download/chapter-size?chapterId=${part.id}").trim().trim('"').toLongOrNull() }
            if(size == null || size <= 0 || size > remaining || size + 32L*1024*1024 > store.root.usableSpace) {
                store.update { it.copy(smartMessage = tr(R.string.tr_090)) }
                break
            }
            repo.prepare(key,current.series,part)
            store.update { s -> s.copy(chapters = s.chapters + (part.id to s.chapters.getValue(part.id).copy(automatic=true))) }
            Jobs.download(repo.context,key,part.id,automatic=true); selected += part.id; remaining -= size
        }
        if(selected.isNotEmpty()) store.update { it.copy(smartMessage = tr(R.string.tr_091, selected.size)) }
        selected
    } }
}
fun smartNextParts(s: LocalState, currentId: Int): List<Chapter> {
    val book = s.chapters[currentId] ?: return emptyList()
    val units = book.series.readingUnits(book.series.cachedVolumes(s),s.libraries)
    val index = units.indexOfFirst { u -> u.chapters.any { it.id == currentId } }
    if(index < 0) return emptyList()
    val unit = units[index]; val partIndex = unit.chapters.indexOfFirst { it.id == currentId }
    // Finish downloading the current multi-part volume before preparing the following volume.
    val remaining = unit.chapters.drop(partIndex+1)
    return if(remaining.any { s.chapters[it.id]?.let { b -> b.ready && b.chapter.sameEdition(it) } != true }) remaining else units.getOrNull(index+1)?.chapters.orEmpty()
}
fun automaticBytes(store: Store): Long = store.get().chapters.values.filter { it.automatic }.sumOf { book ->
    store.chapterDir(book.chapter.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
class FollowedWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("account") ?: return Result.failure()
        if(applicationContext.repository().active()?.key != key) return Result.success()
        DiscoveryJobs.check(applicationContext.repository(),key)
        return Result.success()
    }
}
class SmartNextWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("account") ?: return Result.failure()
        return try { DiscoveryJobs.prepareNext(applicationContext.repository(),key,inputData.getInt("chapter",0)); Result.success() }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { if(runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}
