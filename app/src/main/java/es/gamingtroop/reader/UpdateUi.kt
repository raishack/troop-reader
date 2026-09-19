package es.gamingtroop.reader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val LocalOpenUpdates = staticCompositionLocalOf<() -> Unit> { {} }

@Composable fun UpdateIcon(updates: AppUpdates) {
    val state by updates.states.collectAsState(context = Dispatchers.Main.immediate)
    val open = LocalOpenUpdates.current
    DisplayIconButton(onClick = open) {
        BadgedBox(badge = { if(state.release != null) Badge() }) {
            Icon(Icons.Outlined.SystemUpdate, if(state.release == null) "Actualizaciones de la app" else "Nueva versión disponible")
        }
    }
}

@Composable fun UpdateDialog(updates: AppUpdates, close: () -> Unit) {
    val state by updates.states.collectAsState(context = Dispatchers.Main.immediate)
    val context = LocalContext.current
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    var installing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmData by rememberSaveable { mutableStateOf(false) }
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    fun install() {
        installing = true; notice = null
        scope.launch {
            try { context.startActivity(updates.installerIntent()) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { notice = "No se pudo abrir el instalador. Comprueba el permiso o vuelve a descargar la actualización." }
            finally { installing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        if(canInstall && state.ready) install()
        else notice = "Puedes permitir la instalación y volver a intentarlo cuando quieras."
    }
    DisplayAlertDialog(onDismissRequest = close, title = { Text("Actualizaciones") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling()).testTag("app-updates-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Versión instalada · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            val release = state.release
            when {
                release != null -> {
                    Text("Nueva versión · ${release.versionName}", style = MaterialTheme.typography.titleMedium)
                    Text(sizeText(release.sizeBytes), style = MaterialTheme.typography.bodySmall)
                    if(release.notes.isNotBlank()) Text(release.notes)
                    when {
                        release.minSdk > Build.VERSION.SDK_INT -> Text("Esta versión necesita un Android más reciente. Puedes seguir usando la versión instalada.")
                        state.ready -> {
                            Text("Lista para instalar. Se conservarán tus descargas, progreso y ajustes.")
                            if(!canInstall) Text("Android te pedirá permitir instalaciones desde Troop Reader. Después confirma la actualización en el instalador.", style = MaterialTheme.typography.bodySmall)
                            DisplayButton(enabled = !installing, onClick = {
                                canInstall = context.packageManager.canRequestPackageInstalls()
                                if(canInstall) install() else try {
                                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                } catch(_: Exception) { notice = "Abre los ajustes de Android y permite instalar aplicaciones desde Troop Reader." }
                            }, modifier = Modifier.fillMaxWidth().testTag("install-update")) {
                                Text(if(installing) "Verificando…" else if(canInstall) "Instalar actualización" else "Permitir e instalar")
                            }
                        }
                        state.downloading -> {
                            DisplayProgress(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("Descargando actualización · ${state.percent} %")
                        }
                        else -> {
                            if(state.autoDownload && state.error == null) Text("Se descargará automáticamente en una red Wi‑Fi sin límite de datos.")
                            DisplayButton(onClick = { confirmData = true }, modifier = Modifier.fillMaxWidth().testTag("download-update")) { Text("Descargar ahora") }
                        }
                    }
                }
                state.lastChecked > 0 && state.error == null && !state.checking -> Text("Tienes la última versión publicada.")
                else -> Text("Consulta si hay una nueva versión de Troop Reader.")
            }
            if(state.checking) { DisplayProgress(Modifier.fillMaxWidth()); Text("Comprobando · necesita conexión") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it) }
            if(state.lastChecked > 0) Text("Última comprobación: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(state.lastChecked)), style = MaterialTheme.typography.bodySmall)
            DisplayOutlinedButton(onClick = { updates.check(force = true) }, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) { Text("Buscar actualizaciones") }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Descargar actualizaciones por Wi‑Fi", Modifier.weight(1f))
                DisplaySwitch(state.autoDownload, updates::autoDownload, modifier = Modifier.semantics { contentDescription = "Descarga automática de actualizaciones" })
            }
            Text("Se buscan al abrir la app y periódicamente en segundo plano. Android puede retrasar la comprobación. La instalación siempre requiere tu confirmación y no interrumpe la lectura.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { DisplayTextButton(onClick = close) { Text("Cerrar") } })
    if(confirmData) DisplayAlertDialog(onDismissRequest = { confirmData = false }, title = { Text("Descargar actualización") },
        text = { Text("Se descargarán ${sizeText(state.release?.sizeBytes ?: 0)} usando la conexión actual, incluidos datos móviles si no estás en Wi‑Fi.") },
        confirmButton = { DisplayTextButton(onClick = { confirmData = false; updates.enqueueDownload(manual = true) }) { Text("Descargar") } },
        dismissButton = { DisplayTextButton(onClick = { confirmData = false }) { Text("Cancelar") } })
}
