package es.gamingtroop.reader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.serialization.json.*

/** Explicit allowlist. Never serialize exceptions, filenames, paths, book IDs, URLs or account data. */
object Diagnostics {
    fun report(s: LocalState,api: Int,version: String,display: DisplayDiagnostic? = null)=buildJsonObject {
        put("schema",1);put("appVersion",version);put("androidApi",api)
        display?.let { d -> putJsonObject("display") {
            put("mode",d.mode.name);put("cleaning",d.cleaning);put("bigmeApi",d.bigmeApi.name)
            put("bigmeEnabled",d.bigmeEnabled);put("nativeRequests",d.nativeRequests)
        } }
        put("downloadCount",s.chapters.size);put("complete",s.chapters.values.count { it.ready })
        put("partial",s.chapters.values.count { !it.ready && it.downloadedPages>0 })
        put("queued",s.chapters.values.count { it.wantsDownload });put("pages",s.chapters.values.sumOf { it.readablePages.toLong() })
        put("pendingProgress",s.pending.size);put("pendingBookmarks",s.bookmarks.count { it.dirty })
        put("conflicts",s.pending.values.count { it.conflict!=null });put("wifiOnly",s.settings.wifiOnly)
        putJsonArray("downloadIssues") { s.chapters.values.mapNotNull { issue(it.state) }.groupingBy { it }.eachCount().forEach { (kind,count) ->
            add(buildJsonObject { put("kind",kind);put("count",count) })
        } }
    }.toString()
    fun issue(value: String): String? = when {
        Regex("(?i)HTTP [45][0-9]{2}").containsMatchIn(value) -> Regex("(?i)HTTP [45][0-9]{2}").find(value)!!.value.uppercase()
        value.contains("error",true) || value.contains("fall",true) -> "DOWNLOAD_ERROR"
        else -> null
    }
}
data class DictionaryTarget(val label: String,val packageName: String,val activity: String)
object DictionaryLookup {
    fun targets(context: Context): List<DictionaryTarget> {
        val intent=Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        return context.packageManager.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY).filter {
            it.activityInfo.exported && (it.activityInfo.permission==null || context.checkSelfPermission(it.activityInfo.permission)==PackageManager.PERMISSION_GRANTED)
        }.map { DictionaryTarget(it.loadLabel(context.packageManager).toString(),it.activityInfo.packageName,it.activityInfo.name) }
    }
    fun intent(target: DictionaryTarget,selected: String): Intent {
        require(selected.isNotBlank() && selected.length<=200)
        return Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setClassName(target.packageName,target.activity)
            .putExtra(Intent.EXTRA_PROCESS_TEXT,selected).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
