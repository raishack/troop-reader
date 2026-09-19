package es.gamingtroop.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateState(
    val release: AppRelease? = null, val ready: Boolean = false, val checking: Boolean = false,
    val downloading: Boolean = false, val percent: Int = 0, val error: String? = null,
    val lastChecked: Long = 0, val autoDownload: Boolean = true
)

class AppUpdates(private val context: Context, internal val feed: UpdateFeed = UpdateFeed()) {
    private val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "app-updates")
    private val checkLock = Mutex()
    private val downloadLock = Mutex()
    private val mutable = MutableStateFlow(load())
    val states = mutable.asStateFlow()
    private fun load(): UpdateState {
        val release = runCatching { UpdatePolicy.parse(prefs.getString("release", "")!!) }.getOrNull()
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        return UpdateState(release, release?.let { prefs.getString("ready", "") == it.sha256 && file(it).length() == it.sizeBytes } == true,
            lastChecked = prefs.getLong("checked", 0), autoDownload = prefs.getBoolean("autoDownload", true))
    }
    internal fun file(release: AppRelease) = File(folder, "update-${release.versionCode}-${release.sha256}.apk")
    @Synchronized private fun change(block: (UpdateState) -> UpdateState) { mutable.value = block(mutable.value) }
    private fun changeFor(release: AppRelease, block: (UpdateState) -> UpdateState) = change {
        if (release.samePackage(it.release)) block(it) else it
    }
    fun initialize() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("app-updates", "Actualizaciones de la app", NotificationManager.IMPORTANCE_DEFAULT))
        if (mutable.value.release == null) {
            // Only updater-owned packages; never touches reading accounts or downloads.
            folder.listFiles()?.filter { it.name.matches(Regex("update-.*\\.apk(\\.part)?")) }?.forEach { it.delete() }
            prefs.edit().remove("release").remove("ready").apply()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("app-update-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(network(false)).setInitialDelay(12, TimeUnit.HOURS).build())
    }
    fun check(force: Boolean = false) {
        if (!force && !UpdatePolicy.shouldCheck(prefs.getLong("attempt", 0), System.currentTimeMillis())) return
        change { it.copy(checking = true, error = null) }
        WorkManager.getInstance(context).enqueueUniqueWork("app-update-check", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(network(false)).build())
    }
    suspend fun checkNow() = checkLock.withLock {
        change { it.copy(checking = true, error = null) }
        prefs.edit().putLong("attempt", System.currentTimeMillis()).apply()
        try {
            val release = runInterruptible(Dispatchers.IO) { feed.latest() }.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            // Persist latest before enqueueing; a cancelled/replaced old download can't become current.
            val now = System.currentTimeMillis()
            var previous: AppRelease? = null
            change { old ->
                val same = release?.samePackage(old.release) == true
                previous = old.release.takeUnless { same }
                prefs.edit().putString("release", release?.let { codec.encodeToString(it) })
                    .putLong("checked", now).apply { if (!same) remove("ready") }.apply()
                old.copy(release = release, lastChecked = now, ready = same && old.ready,
                    downloading = same && old.downloading, percent = if (same) old.percent else 0,
                    checking = false, error = null)
            }
            previous?.let { WorkManager.getInstance(context).cancelUniqueWork("app-update-download-${it.sha256}") }
            if (release != null && release.minSdk <= Build.VERSION.SDK_INT) {
                if (!mutable.value.ready && mutable.value.autoDownload) enqueueDownload(manual = false)
                notifyUpdate()
            } else context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { change { it.copy(error = "No se pudo comprobar la nueva versión. Comprueba la conexión y vuelve a intentarlo.") }; throw e }
        finally { change { it.copy(checking = false) } }
    }
    fun autoDownload(enabled: Boolean) {
        prefs.edit().putBoolean("autoDownload", enabled).apply()
        change { it.copy(autoDownload = enabled) }
        if (enabled) enqueueDownload(false)
        else WorkManager.getInstance(context).cancelAllWorkByTag("app-update-auto")
    }
    fun enqueueDownload(manual: Boolean) {
        val state = mutable.value; val release = state.release ?: return
        if (state.ready || release.minSdk > Build.VERSION.SDK_INT) return
        change { it.copy(error = null) }
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>().setConstraints(network(!manual))
            .setInputData(workDataOf("releaseHash" to release.sha256))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (!manual) request.addTag("app-update-auto")
        // Explicit download allows data and replaces the Wi-Fi wait, never an active download.
        WorkManager.getInstance(context).enqueueUniqueWork("app-update-download-${release.sha256}",
            if (manual && !state.downloading) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request.build())
    }
    suspend fun download(release: AppRelease) = downloadLock.withLock {
        if (!release.samePackage(mutable.value.release) || release.versionCode <= BuildConfig.VERSION_CODE || release.minSdk > Build.VERSION.SDK_INT) return@withLock
        changeFor(release) { it.copy(downloading = true, percent = 0, error = null) }
        val target = file(release)
        try {
            withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                fun active() {
                    job.ensureActive()
                    if (!release.samePackage(mutable.value.release)) throw CancellationException("Actualización sustituida")
                }
                runInterruptible {
                    active()
                    // Recover if the process stopped after the atomic rename but before
                    // saving "ready", and avoid re-downloading on duplicate workers.
                    val reusable = target.isFile && runCatching { verifyApk(target, release) }.isSuccess
                    if (!reusable) {
                        check(folder.apply { mkdirs() }.usableSpace > release.sizeBytes + 8 * 1024 * 1024) { "No hay espacio suficiente para la actualización" }
                        feed.download(release, target, ::active) { percent -> changeFor(release) { it.copy(percent = percent) } }
                        verifyApk(target, release)
                    }
                    active()
                }
            }
            var accepted = false
            changeFor(release) {
                prefs.edit().putString("ready", release.sha256).apply(); accepted = true
                it.copy(ready = true, percent = 100)
            }
            if (accepted) {
                folder.listFiles()?.filter { it != target }?.forEach { it.delete() }
                notifyUpdate()
            } else target.delete()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            target.delete()
            changeFor(release) {
                prefs.edit().remove("ready").apply()
                it.copy(ready = false, error = if(e is IllegalArgumentException || e is IllegalStateException) e.message
                    else "Descarga interrumpida. Puedes reintentar; tus lecturas siguen intactas.")
            }
            throw e
        } finally { changeFor(release) { it.copy(downloading = false) } }
    }
    @Suppress("DEPRECATION")
    internal fun verifyApk(file: File, release: AppRelease) {
        UpdatePolicy.validate(release); UpdatePolicy.verifyBytes(file, release)
        require(release.versionCode > BuildConfig.VERSION_CODE && release.minSdk <= Build.VERSION.SDK_INT)
        val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val incoming = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: error("El archivo no es una APK válida")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if(Build.VERSION.SDK_INT >= 28) incoming.longVersionCode else incoming.versionCode.toLong()
        require(incoming.packageName == context.packageName && version == release.versionCode.toLong() && incoming.versionName == release.versionName) {
            "La APK no corresponde a la actualización publicada"
        }
        require((incoming.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Esta versión requiere un Android más reciente" }
        fun signers(info: PackageInfo): Set<String> = (if(Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { it.toCharsString() }.toSet()
        val signatures = signers(installed)
        require(signatures.isNotEmpty() && signatures == signers(incoming)) { "La firma de esta APK no coincide con la app instalada" }
    }
    suspend fun installerIntent(): Intent = withContext(Dispatchers.IO) {
        val release = mutable.value.release ?: error("No hay una actualización disponible")
        val file = file(release)
        try { verifyApk(file, release) }
        catch (e: Exception) {
            changeFor(release) {
                prefs.edit().remove("ready").apply()
                it.copy(ready = false, error = "La APK ya no está disponible o no es válida. Vuelve a descargarla.")
            }
            throw e
        }
        check(release.samePackage(mutable.value.release)) { "Hay una actualización más reciente. Vuelve a abrir el instalador." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun notifyUpdate() {
        val state = mutable.value; val release = state.release ?: return
        if(release.minSdk > Build.VERSION.SDK_INT) return
        val intent = Intent(context, MainActivity::class.java).putExtra("show_updates", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 1500, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "app-updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Troop Reader ${release.versionName}")
            .setContentText(if(state.ready) "Actualización lista · toca para instalar" else "Nueva versión disponible")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
        catch (_: SecurityException) { /* Still visible inside the app without notification permission. */ }
    }
    companion object {
        private const val NOTIFICATION_ID = 1500
        private fun network(wifi: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if(wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true).build()
    }
}
fun Context.appUpdates() = (applicationContext as ReaderApp).updates
class UpdateCheckWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { applicationContext.appUpdates().checkNow(); Result.success() }
    catch(e: CancellationException) { throw e }
    catch(_: Exception) { if(runAttemptCount < 2) Result.retry() else Result.failure() }
}
class AppUpdateDownloadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release = applicationContext.appUpdates().states.value.release
            ?.takeIf { it.sha256 == inputData.getString("releaseHash") } ?: return Result.success()
        return try { applicationContext.appUpdates().download(release); Result.success() }
        catch(e: CancellationException) { throw e }
        catch(_: IllegalArgumentException) { Result.failure() }
        catch(_: IllegalStateException) { Result.failure() }
        catch(_: Exception) { if(runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}
