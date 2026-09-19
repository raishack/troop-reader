package es.gamingtroop.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SeriesPersonalControls(store: Store, series: Series, state: LocalState) {
    var collections by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayChip(series.id in state.favorites, { store.favorite(series.id,series.id !in state.favorites) }, { Text("Favorita") },
            leadingIcon={ Icon(Icons.Outlined.FavoriteBorder,null) })
        DisplayChip(series.id in state.followed, { store.follow(series,series.id !in state.followed) }, { Text(if(series.id in state.followed) "Siguiendo" else "Seguir novedades") })
        DisplayOutlinedButton(onClick={ collections=true }) { Text("Añadir a colección") }
    }
    if(collections) CollectionPicker(store,series,state) { collections=false }
}
@Composable private fun CollectionPicker(store: Store, series: Series, state: LocalState, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    DisplayAlertDialog(onDismissRequest=close,title={ Text("Colecciones · ${series.name}") },text={ Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
        for(c in state.collections) Row(verticalAlignment=Alignment.CenterVertically) {
            DisplayCheckbox(series.id in c.seriesIds,{ store.membership(c.id,series.id,it) });Text(c.name)
        }
        OutlinedTextField(name,{ name=it.take(60) },label={ Text("Nueva colección") },singleLine=true)
        DisplayTextButton(enabled=name.isNotBlank(),onClick={ val id=store.collection(name);store.membership(id,series.id,true);name="" }) { Text("Crear y añadir") }
    } },confirmButton={ DisplayTextButton(onClick=close) { Text("Listo") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PersonalHub(repo: Repository, account: Account, state: LocalState, initialSection: String = "favorites", open: (Series) -> Unit) {
    val store=remember(account.key) { repo.store(account.key) }
    val scope=rememberCoroutineScope { Dispatchers.Main.immediate }
    var notice by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(initialSection.substringBefore(':')) }
    var lastNavigation by rememberSaveable { mutableStateOf(initialSection) }
    LaunchedEffect(initialSection) { if(lastNavigation!=initialSection) { section=initialSection.substringBefore(':');lastNavigation=initialSection } }
    var newName by rememberSaveable { mutableStateOf("") }
    var deleteCollection by remember { mutableStateOf<PersonalCollection?>(null) }
    var clearStats by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<ReadingBackup?>(null) }
    var filesBusy by remember { mutableStateOf(false) }
    val allSeries=(state.series+state.chapters.values.map { it.series }+state.followed.values.map { it.series }).distinctBy { it.id }
    fun operation(block: suspend () -> Unit) { scope.launch {
        filesBusy=true
        try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { notice=if(e is IllegalArgumentException || e is IllegalStateException) e.message else "No se pudo abrir o guardar el archivo. Tus datos siguen intactos." }
        finally { filesBusy=false }
    } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) operation {
        withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use { Backups.write(account,store.get(),it) } }
        notice="Copia guardada. No contiene contraseña ni archivos de libros."
    } }
    val restoreFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) operation {
        restore=withContext(Dispatchers.IO) { repo.context.contentResolver.openInputStream(uri)!!.use { Backups.read(account,it) } }
    } }
    val exportNotes=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if(uri!=null) operation {
        withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(notesMarkdown(store.get()).toByteArray()) } }
        notice="Notas exportadas en Markdown."
    } }
    val refresh=LocalEinkRefresh.current
    val diagnostic=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) operation {
        val report=Diagnostics.report(store.get(),android.os.Build.VERSION.SDK_INT,BuildConfig.VERSION_NAME,refresh?.diagnostic())
        withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(report.toByteArray()) } }
        notice="Diagnóstico exportado. No se ha enviado automáticamente."
    } }
    LazyColumn(Modifier.fillMaxSize().testTag("personal-hub"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp), flingBehavior=displayFling()) {
        item { Text("Mi espacio",style=MaterialTheme.typography.headlineMedium) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("favorites" to "Favoritos", "collections" to "Colecciones", "news" to "Novedades", "stats" to "Estadísticas", "backup" to "Copias y notas", "server" to "Kavita", "diagnostics" to "Diagnóstico").forEach { (value,label) ->
                DisplayChip(section==value,{ section=value },{ Text(label) })
            }
        } }
        notice?.let { item { Text(it,color=Green) } }
        if(filesBusy) item { DisplayProgress(Modifier.fillMaxWidth()) }
        when(section) {
            "server" -> item { ServerShelvesPanel(repo,account,state,open) }
            "diagnostics" -> {
                item { Text("Diagnóstico técnico",style=MaterialTheme.typography.titleLarge) }
                item { Text("Versión, Android, recuentos de descargas y códigos HTTP. No incluye cuenta, servidor, títulos, textos, notas, claves ni registros completos.") }
                item { DisplayButton(enabled=!filesBusy,onClick={ diagnostic.launch("troop-reader-diagnostico.json") }) { Text("Exportar diagnóstico") } }
            }
            "favorites" -> {
                val series=allSeries.filter { it.id in state.favorites }
                if(series.isEmpty()) item { Text("Marca una obra como favorita desde Ver tomos/libros. Aquí tendrás acceso directo a ella.") }
                items(series,key={ it.id }) { s -> DisplayCard(onClick={ open(s) }) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(s.name);Text("Abrir obra",color=Green) } } }
            }
            "collections" -> {
                item { OutlinedTextField(newName,{ newName=it.take(60) },label={ Text("Nombre de la colección") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { DisplayButton(enabled=newName.isNotBlank(),onClick={ store.collection(newName);newName="" }) { Text("Crear colección") } }
                items(state.collections,key={ it.id }) { c -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(c.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);DisplayIconButton(onClick={ deleteCollection=c }) { Icon(Icons.Outlined.DeleteOutline,"Eliminar colección ${c.name}") } }
                    val books=allSeries.filter { it.id in c.seriesIds }
                    if(books.isEmpty()) Text("Añade obras desde su catálogo.")
                    books.forEach { s -> Row(verticalAlignment=Alignment.CenterVertically) {
                        DisplayTextButton(onClick={ open(s) },modifier=Modifier.weight(1f)) { Text(s.name) }
                        DisplayIconButton(onClick={ store.membership(c.id,s.id,false) }) { Icon(Icons.Outlined.Close,"Quitar ${s.name} de la colección") }
                    } }
                } } }
            }
            "news" -> {
                item { Text("Sigue una obra desde su catálogo. Se comprueba al abrir y aproximadamente cada 12 horas; Android puede aplazarlo.") }
                item { DisplayButton(enabled=!checking && account.server!="https://demo.invalid",onClick={ scope.launch {
                    checking=true
                    try { val added=DiscoveryJobs.check(repo,account.key);notice=if(added>0) "$added novedades detectadas" else "Comprobación terminada. Revisa los avisos de cada obra." }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { notice="No se pudo comprobar Kavita. Reintenta cuando tengas conexión." }
                    finally { checking=false }
                } }) { Text(if(checking) "Comprobando…" else "Buscar novedades") } }
                if(state.followed.isEmpty()) item { Text("Todavía no sigues ninguna obra.") }
                items(state.followed.values.toList(),key={ it.series.id }) { f -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(f.series.name,style=MaterialTheme.typography.titleMedium)
                    Text(if(!f.initialized) "Pendiente de primera comprobación" else "${f.unread.size} archivos nuevos sin revisar")
                    f.unread.take(10).forEach { Text("• ${it.labelFor(f.series,state.libraries)}") }
                    if(f.unread.size>10) Text("Y ${f.unread.size-10} más…")
                    f.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    FlowRow { DisplayTextButton(onClick={ open(f.series) }) { Text("Ver obra") }
                        DisplayTextButton(enabled=f.unread.isNotEmpty(),onClick={ store.update { s -> s.followed[f.series.id]?.let { s.copy(followed=s.followed+(f.series.id to it.copy(unread=emptyList()))) } ?: s } }) { Text("Marcar novedades vistas") }
                        DisplayTextButton(onClick={ store.follow(f.series,false) }) { Text("Dejar de seguir") }
                    }
                } } }
            }
            "stats" -> {
                val days=state.statistics.days
                item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Guardar estadísticas en este móvil",Modifier.weight(1f));DisplaySwitch(state.statistics.enabled,{ on -> store.update { it.copy(statistics=it.statistics.copy(enabled=on)) } }) } }
                item { Card { Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${days.values.sumOf { it.millis }/60000} min de lectura",style=MaterialTheme.typography.headlineSmall)
                    Text("${days.values.flatMap { it.visited }.distinct().size} páginas/secciones visitadas")
                    Text("${days.values.flatMap { it.completed }.distinct().size} lecturas completadas · ${days.size} días con actividad")
                    Text("Cuenta desde esta versión, solo con el lector abierto y visible. Visitar una página no certifica haberla leído.",style=MaterialTheme.typography.bodySmall)
                } } }
                items(days.toSortedMap(reverseOrder()).entries.toList(),key={ it.key }) { (day,d) -> Text("$day · ${d.millis/60000} min · ${d.visited.size} páginas/secciones") }
                item { DisplayTextButton(enabled=days.isNotEmpty(),onClick={ clearStats=true }) { Text("Borrar estadísticas") } }
            }
            "backup" -> {
                item { Text("Copia de lectura",style=MaterialTheme.typography.titleLarge) }
                item { Text("Guarda progreso, marcadores, notas, favoritos, colecciones y ajustes. Para restaurar en otro móvil, inicia sesión con la misma cuenta. No incluye contraseñas ni descargas: los libros se descargan de nuevo.") }
                item { DisplayButton(enabled=!filesBusy,onClick={ export.launch("troop-reader-${java.time.LocalDate.now()}.json") }) { Text("Exportar copia") } }
                item { DisplayOutlinedButton(enabled=!filesBusy,onClick={ restoreFile.launch(arrayOf("application/json","application/octet-stream","text/plain")) }) { Text("Restaurar copia") } }
                item { HorizontalDivider();Text("${state.notes.size} subrayados y notas guardados",Modifier.padding(top=12.dp)) }
                item { DisplayOutlinedButton(enabled=!filesBusy && state.notes.isNotEmpty(),onClick={ exportNotes.launch("troop-reader-notas.md") }) { Text("Exportar notas en Markdown") } }
                item { Text("La copia contiene tus títulos y anotaciones. Elige dónde guardarla con el selector de archivos de Android.",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
    deleteCollection?.let { c -> DisplayAlertDialog(onDismissRequest={ deleteCollection=null },title={ Text("Eliminar colección") },text={ Text("Se elimina «${c.name}», no sus libros ni su progreso.") },confirmButton={ DisplayTextButton(onClick={ store.update { it.copy(collections=it.collections.filterNot { it.id==c.id }) };deleteCollection=null }) { Text("Eliminar") } },dismissButton={ DisplayTextButton(onClick={ deleteCollection=null }) { Text("Cancelar") } }) }
    if(clearStats) DisplayAlertDialog(onDismissRequest={ clearStats=false },title={ Text("Borrar estadísticas") },text={ Text("Se elimina el historial estadístico local. Tu progreso y marcadores no cambian.") },confirmButton={ DisplayTextButton(onClick={ store.update { it.copy(statistics=it.statistics.copy(days=emptyMap())) };clearStats=false }) { Text("Borrar") } },dismissButton={ DisplayTextButton(onClick={ clearStats=false }) { Text("Cancelar") } })
    restore?.let { backup -> DisplayAlertDialog(onDismissRequest={ restore=null },title={ Text("Restaurar datos de lectura") },text={ Text("${backup.state.chapters.size} archivos de lectura, ${backup.state.bookmarks.size} marcadores y ${backup.state.notes.size} notas. Se añadirán los datos que falten; se conservan tus posiciones, notas y descargas actuales. El progreso recuperado queda pendiente de comprobar con Kavita.") },
        confirmButton={ DisplayTextButton(enabled=!filesBusy,onClick={ operation { withContext(Dispatchers.IO) { store.update { Backups.merge(it,backup) } };restore=null;notice="Copia restaurada. Revisa Progreso; las descargas actuales se conservan." } }) { Text("Restaurar") } },
        dismissButton={ DisplayTextButton(onClick={ restore=null }) { Text("Cancelar") } }) }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun SmartDownloadSettings(repo: Repository, account: Account, state: LocalState) {
    val scope=rememberCoroutineScope { Dispatchers.Main.immediate };var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    fun change(value: SmartDownloads) { scope.launch { busy=true;try { DiscoveryJobs.configure(repo.context,account.key,value) } catch(e: CancellationException) { throw e } catch(_: Exception) { error="No se pudo aplicar el ajuste" } finally { busy=false } } }
    val value=state.smartDownloads
    Text("Siguiente tomo sin esperas",style=MaterialTheme.typography.titleMedium)
    Row(verticalAlignment=Alignment.CenterVertically) { Text("Preparar el siguiente tomo al leer",Modifier.weight(1f));DisplaySwitch(value.enabled,{ change(value.copy(enabled=it)) },enabled=!busy) }
    Row(verticalAlignment=Alignment.CenterVertically) { Text("Automáticas solo por Wi‑Fi",Modifier.weight(1f));DisplaySwitch(value.wifiOnly,{ change(value.copy(wifiOnly=it)) },enabled=!busy) }
    Text("Espacio máximo para descargas automáticas")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(128,256,512,1024,2048,4096).forEach { limit -> DisplayChip(value.limitMiB==limit,{ change(value.copy(limitMiB=limit)) },{ Text("$limit MiB") },enabled=!busy) } }
    Text("No borra libros ni reanuda tomos pausados. El límite solo cuenta archivos descargados automáticamente; al alcanzarlo se detiene. Si el ajuste general exige Wi‑Fi, también se respeta aquí.",style=MaterialTheme.typography.bodySmall)
    if(state.smartMessage.isNotBlank()) Text(state.smartMessage,color=Green)
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}
