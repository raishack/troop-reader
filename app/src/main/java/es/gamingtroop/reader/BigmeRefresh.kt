package es.gamingtroop.reader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import java.lang.reflect.Modifier

enum class BigmeApiStatus(private val labelId: Int) {
    ABSENT(R.string.tr_037), UNSUPPORTED(R.string.tr_038),
    BLOCKED(R.string.tr_039), AVAILABLE(R.string.tr_040), FAILED(R.string.tr_041);
    val label get() = tr(labelId)
}
internal fun interface RefreshRequest { fun request(): Boolean }
enum class BigmeRoute { FRAMEWORK, SYSTEM_PROVIDER }
internal data class BigmeProbe(val status: BigmeApiStatus, val driver: RefreshRequest? = null,
    val settings: (() -> Boolean)? = null, val route: BigmeRoute = BigmeRoute.FRAMEWORK)

/** Closed IPC allowlist reconstructed from the user's system APK. No arbitrary
 * provider methods, waveform integers, hidden-API bypass or shell privileges.
 * Detection only reads isColorDevice; opening the native UI is an explicit action.
 * This provider returns status=-1 even on accepted operations: only a non-null
 * response is submission evidence, never acknowledgement from the EPD/panel/UI.
 */
internal object BigmeSystemBridge {
    private const val AUTHORITY = "com.xrz.SettingProvider"
    internal enum class Operation(val method: String) {
        COLOR("isColorDevice"), CLEAN("setting_globalrefresh"), SETTINGS("setting_einkcenter")
    }
    internal data class Identity(val owner: String, val component: String, val system: Boolean,
        val enabled: Boolean = true, val exported: Boolean = true)

    fun detect(context: Context): BigmeProbe? = try {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0)
        val app = provider?.applicationInfo
        val identity = if(provider == null || app == null) null else Identity(provider.packageName,
            provider.name, app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            provider.enabled && app.enabled, provider.exported)
        detect(identity) { operation ->
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), operation.method, null, null)
        }
    } catch(_: RuntimeException) { null } catch(_: LinkageError) { null }

    internal fun detect(identity: Identity?, call: (Operation) -> Bundle?): BigmeProbe? {
        if(identity == null || identity.owner != "com.xrz.settings" ||
            identity.component != "com.xrz.settings.provider.SettingProvider" ||
            !identity.system || !identity.enabled || !identity.exported) return null
        fun submit(operation: Operation): Boolean = try { call(operation) != null }
            catch(_: RuntimeException) { false } catch(_: LinkageError) { false }
        val color = try { call(Operation.COLOR) } catch(_: RuntimeException) { null } catch(_: LinkageError) { null }
        // False is a valid monochrome device. A missing/malformed response is not
        // evidence of this contract; never invoke a setter to test availability.
        @Suppress("DEPRECATION")
        if(color?.get("isColorDevice") !is Boolean) return null
        return BigmeProbe(BigmeApiStatus.AVAILABLE, RefreshRequest { submit(Operation.CLEAN) },
            { submit(Operation.SETTINGS) }, BigmeRoute.SYSTEM_PROVIDER)
    }
}

/** Community-observed xrz contract, NOT certified on B751C S firmware 1.7.0.
 * Probe does not invoke any refresh. No numeric modes, private-access bypass,
 * shell, SDK binary or global settings. Explicit opt-in required by the caller.
 * A void return is only a submitted request, never panel acknowledgement.
 */
internal object BigmeFullRefresh {
    fun detect(load: (String) -> Class<*> = {
        Class.forName(it, false, BigmeFullRefresh::class.java.classLoader)
    }): BigmeProbe = try {
        val manager = load("xrz.framework.manager.XrzEinkManager")
        val modes = load("xrz.framework.manager.EinkRefreshMode")
        val method = manager.getMethod("forceGlobalRefresh", Int::class.javaPrimitiveType)
        val clean = modes.getField("EINK_CLEAN_MODE")
        if(!Modifier.isPublic(manager.modifiers) || !Modifier.isPublic(modes.modifiers) ||
            !Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE ||
            !Modifier.isStatic(clean.modifiers) || !Modifier.isFinal(clean.modifiers) || clean.type != Integer.TYPE) {
            BigmeProbe(BigmeApiStatus.UNSUPPORTED)
        } else {
            val mode = clean.getInt(null)
            BigmeProbe(BigmeApiStatus.AVAILABLE, RefreshRequest {
                try { method.invoke(null, mode); true }
                catch(_: ReflectiveOperationException) { false }
                catch(_: RuntimeException) { false }
                catch(_: LinkageError) { false }
            })
        }
    } catch(_: ClassNotFoundException) { BigmeProbe(BigmeApiStatus.ABSENT) }
      catch(_: SecurityException) { BigmeProbe(BigmeApiStatus.BLOCKED) }
      catch(_: ReflectiveOperationException) { BigmeProbe(BigmeApiStatus.UNSUPPORTED) }
      catch(_: RuntimeException) { BigmeProbe(BigmeApiStatus.BLOCKED) }
      catch(_: LinkageError) { BigmeProbe(BigmeApiStatus.UNSUPPORTED) }
}

/** Only enumerations, booleans and counts. No model, serial, build ID or account. */
data class DisplayDiagnostic(val mode: DisplayMode, val cleaning: Boolean, val bigmeApi: BigmeApiStatus,
    val bigmeEnabled: Boolean, val nativeRequests: Int, val bigmeRoute: BigmeRoute? = null,
    val nativeSettingsAvailable: Boolean = false)
