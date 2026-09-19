package es.gamingtroop.reader

import java.lang.reflect.Modifier

enum class BigmeApiStatus(val label: String) {
    ABSENT("API Bigme no encontrada"), UNSUPPORTED("API Bigme no compatible"),
    BLOCKED("Acceso al refresco Bigme bloqueado"), AVAILABLE("API Bigme detectada · experimental"),
    FAILED("La solicitud Bigme falló; se usa repintado compatible")
}
internal fun interface RefreshRequest { fun request(): Boolean }
internal data class BigmeProbe(val status: BigmeApiStatus, val driver: RefreshRequest? = null)

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
    val bigmeEnabled: Boolean, val nativeRequests: Int)
