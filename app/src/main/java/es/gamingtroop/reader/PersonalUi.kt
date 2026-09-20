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
        DisplayChip(series.id in state.favorites, { store.favorite(series.id,series.id !in state.favorites) }, { Text(tr(R.string.tr_333)) },
            leadingIcon={ Icon(Icons.Outlined.FavoriteBorder,null) })
        DisplayChip(series.id in state.followed, { store.follow(series,series.id !in state.followed) }, { Text(if(series.id in state.followed) tr(R.string.tr_334) else tr(R.string.tr_335)) })
        DisplayOutlinedButton(onClick={ collections=true }) { Text(tr(R.string.tr_336)) }
    }
    if(collections) CollectionPicker(store,series,state) { collections=false }
}
@Composable private fun CollectionPicker(store: Store, series: Series, state: LocalState, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    DisplayAlertDialog(onDismissRequest=close,title={ Text(tr(R.string.tr_337, series.name)) },text={ Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
        for(c in state.collections) Row(verticalAlignment=Alignment.CenterVertically) {
            DisplayCheckbox(series.id in c.seriesIds,{ store.membership(c.id,series.id,it) });Text(c.name)
        }
        OutlinedTextField(name,{ name=it.take(60) },label={ Text(tr(R.string.tr_338)) },singleLine=true)
        DisplayTextButton(enabled=name.isNotBlank(),onClick={ val id=store.collection(name);store.membership(id,series.id,true);name="" }) { Text(tr(R.string.tr_339)) }
    } },confirmButton={ DisplayTextButton(onClick=close) { Text(tr(R.string.tr_260)) } })
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
        catch(e: Exception) { notice=if(e is IllegalArgumentException || e is IllegalStateException) e.message else tr(R.string.tr_340) }
        finally { filesBusy=false }
    } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) operation {
        withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use { Backups.write(account,store.get(),it) } }
        notice=tr(R.string.tr_341)
    } }
    val restoreFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) operation {
        restore=withContext(Dispatchers.IO) { repo.context.contentResolver.openInputStream(uri)!!.use { Backups.read(account,it) } }
    } }
    val exportNotes=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if(uri!=null) operation {
        withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(notesMarkdown(store.get()).toByteArray()) } }
        notice=tr(R.string.tr_342)
    } }
    val refresh=LocalEinkRefresh.current
    val diagnostic=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) operation {
        val report=Diagnostics.report(store.get(),android.os.Build.VERSION.SDK_INT,BuildConfig.VERSION_NAME,refresh?.diagnostic())
        withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(report.toByteArray()) } }
        notice=tr(R.string.tr_343)
    } }
    LazyColumn(Modifier.fillMaxSize().testTag("personal-hub"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp), flingBehavior=displayFling()) {
        item { Text(tr(R.string.tr_183),style=MaterialTheme.typography.headlineMedium) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("favorites" to tr(R.string.tr_344), "collections" to tr(R.string.tr_345), "news" to tr(R.string.tr_346), "stats" to tr(R.string.tr_347), "backup" to tr(R.string.tr_348), "server" to tr(R.string.tr_349), "diagnostics" to tr(R.string.tr_350)).forEach { (value,label) ->
                DisplayChip(section==value,{ section=value },{ Text(label) })
            }
        } }
        notice?.let { item { Text(it,color=Green) } }
        if(filesBusy) item { DisplayProgress(Modifier.fillMaxWidth()) }
        when(section) {
            "server" -> item { ServerShelvesPanel(repo,account,state,open) }
            "diagnostics" -> {
                item { Text(tr(R.string.tr_351),style=MaterialTheme.typography.titleLarge) }
                item { Text(tr(R.string.tr_352)) }
                item { DisplayButton(enabled=!filesBusy,onClick={ diagnostic.launch("troop-reader-diagnostico.json") }) { Text(tr(R.string.tr_353)) } }
            }
            "favorites" -> {
                val series=allSeries.filter { it.id in state.favorites }
                if(series.isEmpty()) item { Text(tr(R.string.tr_354)) }
                items(series,key={ it.id }) { s -> DisplayCard(onClick={ open(s) }) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(s.name);Text(tr(R.string.tr_355),color=Green) } } }
            }
            "collections" -> {
                item { OutlinedTextField(newName,{ newName=it.take(60) },label={ Text(tr(R.string.tr_356)) },singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { DisplayButton(enabled=newName.isNotBlank(),onClick={ store.collection(newName);newName="" }) { Text(tr(R.string.tr_357)) } }
                items(state.collections,key={ it.id }) { c -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(c.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);DisplayIconButton(onClick={ deleteCollection=c }) { Icon(Icons.Outlined.DeleteOutline,tr(R.string.tr_358, c.name)) } }
                    val books=allSeries.filter { it.id in c.seriesIds }
                    if(books.isEmpty()) Text(tr(R.string.tr_359))
                    books.forEach { s -> Row(verticalAlignment=Alignment.CenterVertically) {
                        DisplayTextButton(onClick={ open(s) },modifier=Modifier.weight(1f)) { Text(s.name) }
                        DisplayIconButton(onClick={ store.membership(c.id,s.id,false) }) { Icon(Icons.Outlined.Close,tr(R.string.tr_360, s.name)) }
                    } }
                } } }
            }
            "news" -> {
                item { Text(tr(R.string.tr_361)) }
                item { DisplayButton(enabled=!checking && account.server!="https://demo.invalid",onClick={ scope.launch {
                    checking=true
                    try { val added=DiscoveryJobs.check(repo,account.key);notice=if(added>0) tr(R.string.tr_362, added) else tr(R.string.tr_363) }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { notice=tr(R.string.tr_364) }
                    finally { checking=false }
                } }) { Text(if(checking) "Comprobando…" else tr(R.string.tr_365)) } }
                if(state.followed.isEmpty()) item { Text(tr(R.string.tr_366)) }
                items(state.followed.values.toList(),key={ it.series.id }) { f -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(f.series.name,style=MaterialTheme.typography.titleMedium)
                    Text(if(!f.initialized) tr(R.string.tr_367) else tr(R.string.tr_368, f.unread.size))
                    f.unread.take(10).forEach { Text("• ${it.labelFor(f.series,state.libraries)}") }
                    if(f.unread.size>10) Text(tr(R.string.tr_370, f.unread.size-10))
                    f.error?.let { Text(localizedStatus(it),color=MaterialTheme.colorScheme.error) }
                    FlowRow { DisplayTextButton(onClick={ open(f.series) }) { Text(tr(R.string.tr_371)) }
                        DisplayTextButton(enabled=f.unread.isNotEmpty(),onClick={ store.update { s -> s.followed[f.series.id]?.let { s.copy(followed=s.followed+(f.series.id to it.copy(unread=emptyList()))) } ?: s } }) { Text(tr(R.string.tr_372)) }
                        DisplayTextButton(onClick={ store.follow(f.series,false) }) { Text(tr(R.string.tr_373)) }
                    }
                } } }
            }
            "stats" -> {
                val days=state.statistics.days
                item { Row(verticalAlignment=Alignment.CenterVertically) { Text(tr(R.string.tr_374),Modifier.weight(1f));DisplaySwitch(state.statistics.enabled,{ on -> store.update { it.copy(statistics=it.statistics.copy(enabled=on)) } }) } }
                item { Card { Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.tr_375, days.values.sumOf { it.millis }/60000),style=MaterialTheme.typography.headlineSmall)
                    Text(tr(R.string.tr_376, days.values.flatMap { it.visited }.distinct().size))
                    Text(tr(R.string.tr_377, days.values.flatMap { it.completed }.distinct().size, days.size))
                    Text(tr(R.string.tr_378),style=MaterialTheme.typography.bodySmall)
                } } }
                items(days.toSortedMap(reverseOrder()).entries.toList(),key={ it.key }) { (day,d) -> Text(tr(R.string.tr_379, day, d.millis/60000, d.visited.size)) }
                item { DisplayTextButton(enabled=days.isNotEmpty(),onClick={ clearStats=true }) { Text(tr(R.string.tr_380)) } }
            }
            "backup" -> {
                item { Text(tr(R.string.tr_381),style=MaterialTheme.typography.titleLarge) }
                item { Text(tr(R.string.tr_382)) }
                item { DisplayButton(enabled=!filesBusy,onClick={ export.launch("troop-reader-${java.time.LocalDate.now()}.json") }) { Text(tr(R.string.tr_383)) } }
                item { DisplayOutlinedButton(enabled=!filesBusy,onClick={ restoreFile.launch(arrayOf("application/json","application/octet-stream","text/plain")) }) { Text(tr(R.string.tr_384)) } }
                item { HorizontalDivider();Text(tr(R.string.tr_385, state.notes.size),Modifier.padding(top=12.dp)) }
                item { DisplayOutlinedButton(enabled=!filesBusy && state.notes.isNotEmpty(),onClick={ exportNotes.launch("troop-reader-notas.md") }) { Text(tr(R.string.tr_386)) } }
                item { Text(tr(R.string.tr_387),style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
    deleteCollection?.let { c -> DisplayAlertDialog(onDismissRequest={ deleteCollection=null },title={ Text(tr(R.string.tr_388)) },text={ Text(tr(R.string.tr_389, c.name)) },confirmButton={ DisplayTextButton(onClick={ store.update { it.copy(collections=it.collections.filterNot { it.id==c.id }) };deleteCollection=null }) { Text(tr(R.string.tr_143)) } },dismissButton={ DisplayTextButton(onClick={ deleteCollection=null }) { Text(tr(R.string.tr_161)) } }) }
    if(clearStats) DisplayAlertDialog(onDismissRequest={ clearStats=false },title={ Text(tr(R.string.tr_380)) },text={ Text(tr(R.string.tr_390)) },confirmButton={ DisplayTextButton(onClick={ store.update { it.copy(statistics=it.statistics.copy(days=emptyMap())) };clearStats=false }) { Text(tr(R.string.tr_391)) } },dismissButton={ DisplayTextButton(onClick={ clearStats=false }) { Text(tr(R.string.tr_161)) } })
    restore?.let { backup -> DisplayAlertDialog(onDismissRequest={ restore=null },title={ Text(tr(R.string.tr_392)) },text={ Text(tr(R.string.tr_393, backup.state.chapters.size, backup.state.bookmarks.size, backup.state.notes.size)) },
        confirmButton={ DisplayTextButton(enabled=!filesBusy,onClick={ operation { withContext(Dispatchers.IO) { store.update { Backups.merge(it,backup) } };restore=null;notice=tr(R.string.tr_394) } }) { Text(tr(R.string.tr_395)) } },
        dismissButton={ DisplayTextButton(onClick={ restore=null }) { Text(tr(R.string.tr_161)) } }) }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun SmartDownloadSettings(repo: Repository, account: Account, state: LocalState) {
    val scope=rememberCoroutineScope { Dispatchers.Main.immediate };var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    fun change(value: SmartDownloads) { scope.launch { busy=true;try { DiscoveryJobs.configure(repo.context,account.key,value) } catch(e: CancellationException) { throw e } catch(_: Exception) { error=tr(R.string.tr_396) } finally { busy=false } } }
    val value=state.smartDownloads
    Text(tr(R.string.tr_397),style=MaterialTheme.typography.titleMedium)
    Row(verticalAlignment=Alignment.CenterVertically) { Text(tr(R.string.tr_398),Modifier.weight(1f));DisplaySwitch(value.enabled,{ change(value.copy(enabled=it)) },enabled=!busy) }
    Row(verticalAlignment=Alignment.CenterVertically) { Text(tr(R.string.tr_399),Modifier.weight(1f));DisplaySwitch(value.wifiOnly,{ change(value.copy(wifiOnly=it)) },enabled=!busy) }
    Text(tr(R.string.tr_400))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(128,256,512,1024,2048,4096).forEach { limit -> DisplayChip(value.limitMiB==limit,{ change(value.copy(limitMiB=limit)) },{ Text("$limit MiB") },enabled=!busy) } }
    Text(tr(R.string.tr_402),style=MaterialTheme.typography.bodySmall)
    if(state.smartMessage.isNotBlank()) Text(localizedStatus(state.smartMessage),color=Green)
    error?.let { Text(localizedStatus(it),color=MaterialTheme.colorScheme.error) }
}
