package es.gamingtroop.reader

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.runtime.*

/** Device preference, deliberately outside account/backup and per-book settings. */
enum class DisplayMode(val label: String) {
    NORMAL("Pantalla normal"), MONO("Tinta electrónica B/N"), COLOR("Tinta electrónica color");
    val eink get() = this != NORMAL
}
class DisplayPreferences(context: Context, private val firmware: String = "${Build.VERSION.SDK_INT}:${Build.DISPLAY}") {
    private val prefs = context.applicationContext.getSharedPreferences("display",Context.MODE_PRIVATE)
    var mode by mutableStateOf(runCatching { DisplayMode.valueOf(prefs.getString("mode","NORMAL")!!) }.getOrDefault(DisplayMode.NORMAL))
        private set
    var cleaning by mutableStateOf(prefs.getBoolean("cleaning",true)); private set
    // Local-only, re-enable after firmware changes. Never exported in a reading backup.
    var bigmeNative by mutableStateOf(prefs.getString("bigmeFirmware",null)==firmware); private set
    fun mode(value: DisplayMode) { mode=value; prefs.edit().putString("mode",value.name).apply() }
    fun cleaning(value: Boolean) { cleaning=value; prefs.edit().putBoolean("cleaning",value).apply() }
    fun bigmeNative(value: Boolean) {
        bigmeNative=value
        prefs.edit().apply { if(value) putString("bigmeFirmware",firmware) else remove("bigmeFirmware") }.apply()
    }
}
val LocalDisplayMode = staticCompositionLocalOf { DisplayMode.NORMAL }
val LocalDisplayPreferences = staticCompositionLocalOf<DisplayPreferences?> { null }
val LocalEinkRefresh = staticCompositionLocalOf<EinkRefresh?> { null }

object EinkPolicy {
    fun reading(settings: ReadingSettings, mode: DisplayMode) = if(!mode.eink) settings else settings.copy(
        theme=if(mode==DisplayMode.MONO) "eink-mono" else "eink-color", pageTurnEffect="none",
        imageMode=if(settings.imageMode=="continuous") "fit" else settings.imageMode,
        fontSize=settings.fontSize.coerceAtLeast(18))
    fun isOnyx(manufacturer: String, brand: String) = listOf(manufacturer,brand).any { it.equals("onyx",true) || it.equals("boox",true) }
}

/** Only public vendor extension signatures observed in Onyx's device SDK 1.1.11.
 * No guessed numeric waveforms, private-API bypass, shell or system setting changes.
 * Reflection may be blocked/absent on other firmware: report that and fall back.
 * A successful request is NOT proof of a physical refresh (no panel acknowledgement).
 */
class OnyxFullRefresh private constructor(private val repaint: java.lang.reflect.Method, private val mode: Int) {
    fun request(): Boolean = runCatching { repaint.invoke(null,mode) != false }.getOrDefault(false)
    companion object {
        fun detect(): OnyxFullRefresh? {
            if(!EinkPolicy.isOnyx(Build.MANUFACTURER,Build.BRAND)) return null
            return runCatching {
                val helper=Class.forName("android.onyx.ViewUpdateHelper")
                val flags=listOf("EINK_AUTO_MODE_REGIONAL","EINK_WAIT_MODE_WAIT","EINK_WAVEFORM_MODE_GC16","EINK_UPDATE_MODE_FULL")
                    .map { helper.getField(it).getInt(null) }.reduce { a,b -> a or b }
                val method=View::class.java.getMethod("repaintEverything",Int::class.javaPrimitiveType)
                check(java.lang.reflect.Modifier.isStatic(method.modifiers))
                OnyxFullRefresh(method,flags)
            }.getOrNull()
        }
    }
}

/** Event-driven, not a redraw loop. Input ends, content-ready, overlays and resume
 * request a cleaning after the last drawing opportunity. Draws alone never arm it.
 * Jobs/decoders/scrolls coalesce; nothing runs with the app backgrounded.
 */
class EinkRefresh internal constructor(private val prefs: DisplayPreferences,
    private val bigme: BigmeProbe = BigmeFullRefresh.detect()) {
    private val handler=Handler(Looper.getMainLooper())
    private val windows=mutableListOf<ViewGroup>()
    private val draws=mutableMapOf<ViewGroup,ViewTreeObserver.OnDrawListener>()
    private var driver=OnyxFullRefresh.detect()
    var driverAvailable by mutableStateOf(driver!=null); private set
    var bigmeStatus by mutableStateOf(bigme.status); private set
    var nativeRequests by mutableStateOf(0); private set
    fun diagnostic() = DisplayDiagnostic(prefs.mode,prefs.cleaning,bigmeStatus,prefs.bigmeNative,nativeRequests)
    var requests=0; private set
    var completed=0; private set
    private var foreground=false
    private var touching=false
    private var pending=false
    private var active=false
    private var firstRequest=0L
    private var lastData=0L
    private var drawable: ColorDrawable?=null
    private var paintedRoot: ViewGroup?=null
    private val execute=Runnable { clean() }
    private val white=Runnable {
        val root=paintedRoot
        if(root==null || !eligible()) { cancel(); return@Runnable }
        drawable?.color=android.graphics.Color.WHITE
        root.invalidate()
        handler.postDelayed(finish, if(prefs.mode==DisplayMode.COLOR) 180 else 120)
    }
    private val finish=Runnable {
        paintedRoot?.let { root -> drawable?.let { root.overlay.remove(it) }; root.invalidate() }
        paintedRoot=null; drawable=null; active=false; completed++
        if(pending) { handler.removeCallbacks(execute); handler.postDelayed(execute,220) }
    }
    private fun eligible() = prefs.mode.eink && prefs.cleaning && foreground
    fun foreground(value: Boolean) { foreground=value; if(value) request() else cancel() }
    fun beginTouch() { touching=true; handler.removeCallbacks(execute) }
    fun endTouch() { touching=false; request() }
    fun request(data: Boolean=false) {
        if(!eligible()) return
        val now=SystemClock.uptimeMillis()
        if(data && now-lastData<1500) {
            // Keep a trailing request rather than losing the final async cover.
            handler.removeCallbacks(dataReady);handler.postDelayed(dataReady,1500);return
        }
        if(data) lastData=now
        requests++
        if(!pending) firstRequest=now
        pending=true
        if(!active && !touching) { handler.removeCallbacks(execute);handler.postDelayed(execute,220) }
    }
    private val dataReady=Runnable { request(data=true) }
    fun attach(view: View): () -> Unit {
        val root=view.rootView as? ViewGroup ?: return {}
        if(root in windows) return {}
        windows.add(root)
        val listener=ViewTreeObserver.OnDrawListener {
            // Wait for a stable frame, but bound background loading starvation.
            if(pending && !active && !touching && SystemClock.uptimeMillis()-firstRequest<1500) {
                handler.removeCallbacks(execute);handler.postDelayed(execute,180)
            }
        }
        draws[root]=listener;root.viewTreeObserver.addOnDrawListener(listener)
        request()
        return {
            draws.remove(root)?.let { if(root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(it) }
            windows.remove(root)
            if(paintedRoot===root) cancel()
            request()
        }
    }
    private fun clean() {
        if(!eligible() || touching || active) return
        val root=windows.lastOrNull { it.isAttachedToWindow && it.isShown } ?: return
        pending=false
        if(prefs.bigmeNative && bigmeStatus==BigmeApiStatus.AVAILABLE) {
            if(bigme.driver?.request()==true) { nativeRequests++;completed++;return }
            bigmeStatus=BigmeApiStatus.FAILED; prefs.bigmeNative(false)
        }
        if(driver?.request()==true) { nativeRequests++;completed++;return }
        if(driver!=null) { driver=null;driverAvailable=false }
        // Compatibility repaint, NOT a guaranteed hardware waveform. No animation,
        // bitmap copying or changes to WebView zoom/progress. Controls stay interactive.
        active=true;paintedRoot=root
        drawable=ColorDrawable(android.graphics.Color.BLACK).apply { setBounds(0,0,root.width,root.height) }
        root.overlay.add(drawable!!);root.invalidate()
        handler.postDelayed(white,if(prefs.mode==DisplayMode.COLOR) 180 else 120)
    }
    fun changed() { cancel();request() }
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        paintedRoot?.let { root -> drawable?.let { root.overlay.remove(it) };root.invalidate() }
        paintedRoot=null;drawable=null;active=false;pending=false;touching=false
    }
    fun dispose() {
        cancel();draws.forEach { (v,l) -> if(v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnDrawListener(l) }
        draws.clear();windows.clear()
    }
}
