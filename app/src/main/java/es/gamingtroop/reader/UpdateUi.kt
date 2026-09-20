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
            Icon(Icons.Outlined.SystemUpdate, if(state.release == null) tr(R.string.tr_022) else tr(R.string.tr_036))
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
            catch(_: Exception) { notice = tr(R.string.tr_567) }
            finally { installing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        if(canInstall && state.ready) install()
        else notice = tr(R.string.tr_568)
    }
    DisplayAlertDialog(onDismissRequest = close, title = { Text(tr(R.string.tr_569)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling()).testTag("app-updates-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(R.string.tr_570, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
            val release = state.release
            when {
                release != null -> {
                    Text(tr(R.string.tr_571, release.versionName), style = MaterialTheme.typography.titleMedium)
                    Text(sizeText(release.sizeBytes), style = MaterialTheme.typography.bodySmall)
                    if(release.notes.isNotBlank()) Text(release.notes)
                    when {
                        release.minSdk > Build.VERSION.SDK_INT -> Text(tr(R.string.tr_572))
                        state.ready -> {
                            Text(tr(R.string.tr_573))
                            if(!canInstall) Text(tr(R.string.tr_574), style = MaterialTheme.typography.bodySmall)
                            DisplayButton(enabled = !installing, onClick = {
                                canInstall = context.packageManager.canRequestPackageInstalls()
                                if(canInstall) install() else try {
                                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                } catch(_: Exception) { notice = tr(R.string.tr_575) }
                            }, modifier = Modifier.fillMaxWidth().testTag("install-update")) {
                                Text(if(installing) "Verificando…" else if(canInstall) tr(R.string.tr_576) else tr(R.string.tr_577))
                            }
                        }
                        state.downloading -> {
                            DisplayProgress(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(tr(R.string.tr_578, state.percent))
                        }
                        else -> {
                            if(state.autoDownload && state.error == null) Text(tr(R.string.tr_579))
                            DisplayButton(onClick = { confirmData = true }, modifier = Modifier.fillMaxWidth().testTag("download-update")) { Text(tr(R.string.tr_580)) }
                        }
                    }
                }
                state.lastChecked > 0 && state.error == null && !state.checking -> Text(tr(R.string.tr_581))
                else -> Text(tr(R.string.tr_582))
            }
            if(state.checking) { DisplayProgress(Modifier.fillMaxWidth()); Text(tr(R.string.tr_583)) }
            state.error?.let { Text(localizedStatus(it), color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it) }
            if(state.lastChecked > 0) Text(tr(R.string.tr_584) + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, AppLanguage.locale).format(java.util.Date(state.lastChecked)), style = MaterialTheme.typography.bodySmall)
            DisplayOutlinedButton(onClick = { updates.check(force = true) }, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.tr_585)) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.tr_586), Modifier.weight(1f))
                DisplaySwitch(state.autoDownload, updates::autoDownload, modifier = Modifier.semantics { contentDescription = tr(R.string.tr_587) })
            }
            Text(tr(R.string.tr_588), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { DisplayTextButton(onClick = close) { Text(tr(R.string.tr_115)) } })
    if(confirmData) DisplayAlertDialog(onDismissRequest = { confirmData = false }, title = { Text(tr(R.string.tr_589)) },
        text = { Text(tr(R.string.tr_590, sizeText(state.release?.sizeBytes ?: 0))) },
        confirmButton = { DisplayTextButton(onClick = { confirmData = false; updates.enqueueDownload(manual = true) }) { Text(tr(R.string.tr_591)) } },
        dismissButton = { DisplayTextButton(onClick = { confirmData = false }) { Text(tr(R.string.tr_161)) } })
}
