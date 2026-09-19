package es.gamingtroop.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun CategoryFilters(categories: List<ReadingCategory>, selected: String, select: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), flingBehavior=displayFling()) {
        item { DisplayChip(selected.isBlank(), { select("") }, { Text("Todos los tipos") }) }
        items(categories) { category -> DisplayChip(selected == category.name, { select(category.name) }, { Text(category.label) }) }
    }
}

/** Downloaded works and their volumes; payload deletion never touches reading/bookmark state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun OfflineLibrary(repo: Repository, account: Account, state: LocalState, work: List<WorkInfo>,
    online: Boolean, generation: Int, downloadMore: (Series) -> Unit, open: (Int) -> Unit) {
    val scope = rememberCoroutineScope { kotlinx.coroutines.Dispatchers.Main.immediate }
    var seriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var onlyUnread by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(OfflineSort.RECENT) }
    var filter by rememberSaveable { mutableStateOf(OfflineFilter.ALL) }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var workSelection by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var queueSelection by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var removal by remember { mutableStateOf<Set<Int>?>(null) }
    var queueOnly by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val local = state.chapters.values.filter { it.ready || it.inQueue }
    val indexed = remember(state.chapters, state.series, state.progress, state.pending) { state.offlineWorks() }
    val works = indexed.map { it.series }
    val filtered = indexed.browse(query,category,state.libraries,filter,sort)
    val readIds = state.readDownloads(seriesId)
    val readBytes = readIds.sumOf { state.chapters[it]?.totalBytes ?: 0 }
    val series = works.find { it.id == seriesId }
    val units = series?.localUnits(state).orEmpty()
    val queue = local.filter { it.inQueue }
    val queueIds = queue.map { it.chapter.id }.toSet()
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true; error = null
        try { block() } catch(e: CancellationException) { throw e }
        catch(_: Exception) { error = "No se pudo completar la operación. Las demás descargas y tu progreso se conservan." }
        finally { busy = false }
    } }
    fun remove(ids: Set<Int>, onlyQueue: Boolean = false) { removal = ids; queueOnly = onlyQueue }
    LaunchedEffect(seriesId, units, queueIds) {
        selection = selection.intersect(units.map { it.key }.toSet())
        queueSelection = queueSelection.intersect(queueIds)
        workSelection = workSelection.intersect(works.map { it.id }.toSet())
        if(seriesId != null && series == null) seriesId = null
    }
    BackHandler(seriesId != null && removal == null) { seriesId = null; selection = emptySet() }
    LazyColumn(Modifier.fillMaxSize().testTag("download-list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), flingBehavior=displayFling()) {
        item {
            if(seriesId != null) DisplayTextButton(onClick = { seriesId = null; selection = emptySet() }) { Text("← Todas las obras") }
            Text(series?.name ?: "Tu biblioteca offline", style = MaterialTheme.typography.headlineSmall)
            Text(if(series == null) "${works.size} obras · ${sizeText(local.sumOf { it.totalBytes })}"
                else "${units.size} ${series.unitsName(state.libraries).let { if(units.size == 1) it.dropLast(1) else it }} · ${units.count { it.isRead(state) }} leídos", color = Green)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if(busy) DisplayProgress(Modifier.fillMaxWidth())
        }
        if(series == null) {
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Buscar en descargas") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                CategoryFilters(works.map { it.category(state.libraries) }.distinct(), category) { category = it }
                FlowRow {
                    Box {
                        DisplayTextButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("offline-sort")) { Icon(Icons.Outlined.Sort,null); Text(sort.label) }
                        DisplayDropdownMenu(sortMenu,{ sortMenu = false }) {
                            OfflineSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) },onClick = { sort = option; sortMenu = false }) }
                        }
                    }
                    Box {
                        DisplayTextButton(onClick = { filterMenu = true }, modifier = Modifier.testTag("offline-filter")) { Icon(Icons.Outlined.FilterList,null); Text(filter.label) }
                        DisplayDropdownMenu(filterMenu,{ filterMenu = false }) {
                            OfflineFilter.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) },onClick = { filter = option; filterMenu = false }) }
                        }
                    }
                }
                if(filter == OfflineFilter.READ) Text("Se refiere a lo descargado, no a toda la serie en Kavita.",style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    if(readIds.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(readIds) },modifier = Modifier.testTag("clear-read-downloads")) { Text("Liberar leídos · ${sizeText(readBytes)}") }
                    DisplayTextButton(enabled = local.isNotEmpty() && !busy, onClick = { remove(local.map { it.chapter.id }.toSet()) }) { Text("Eliminar todas las descargas") }
                    if(workSelection.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(local.filter { it.series.id in workSelection }.map { it.chapter.id }.toSet()) }) { Text("Eliminar obras (${workSelection.size})") }
                }
            }
            if(queue.isNotEmpty()) {
                item {
                    Text("Cola · ${queue.size} pendientes", fontWeight = FontWeight.SemiBold)
                    DisplayTextButton(onClick = { showQueue = !showQueue }) { Text(if(showQueue) "Ocultar cola" else "Ver cola") }
                    FlowRow {
                        DisplayTextButton(enabled = !busy, onClick = { queueSelection = if(queueSelection.containsAll(queueIds)) emptySet() else queueIds }) { Text(if(queueSelection.containsAll(queueIds)) "Quitar selección" else "Seleccionar toda la cola") }
                        DisplayTextButton(enabled = !busy, onClick = { remove(queueIds, true) }) { Text("Vaciar cola") }
                        val pauseIds = queueSelection.ifEmpty { queueIds }.filter { state.chapters[it]?.wantsDownload == true }.toSet()
                        DisplayTextButton(enabled = !busy && pauseIds.isNotEmpty(), onClick = { action { Jobs.pauseQueue(repo.context,account.key,pauseIds) } }, modifier = Modifier.testTag("pause-queue")) { Text(if(queueSelection.isEmpty()) "Pausar toda la cola" else "Pausar selección") }
                        DisplayTextButton(enabled = !busy, onClick = { action { Jobs.resumeQueue(repo.context, account.key, queueSelection.ifEmpty { queueIds }) } }) { Text(if(queueSelection.isEmpty()) "Reintentar pendientes" else "Reanudar (${queueSelection.size})") }
                        if(queueSelection.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(queueSelection,true) }) { Text("Quitar (${queueSelection.size})") }
                    }
                }
                if(showQueue) items(queue, key = { "queue-${it.chapter.id}" }) { book ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DisplayCheckbox(book.chapter.id in queueSelection, { checked -> queueSelection = if(checked) queueSelection + book.chapter.id else queueSelection - book.chapter.id }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Seleccionar descarga ${book.series.name} · ${book.chapter.labelFor(book.series,state.libraries)}" })
                            Column(Modifier.weight(1f)) { Text(book.series.name); Text(book.chapter.labelFor(book.series,state.libraries), style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(book.state, style = MaterialTheme.typography.bodySmall)
                        DisplayProgress(progress = { book.downloadedPages.toFloat() / book.chapter.pages.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        val running = work.any { Jobs.downloadTag(account.key,book.chapter.id) in it.tags && !it.state.isFinished }
                        DisplayTextButton(enabled = !busy, onClick = { action { if(running) Jobs.pause(repo.context,account.key,book.chapter.id) else Jobs.download(repo.context,account.key,book.chapter.id) } }) { Text(if(running) "Pausar" else "Reanudar") }
                    } }
                }
            }
            if(filtered.isEmpty()) item {
                EmptyState(if(works.isEmpty()) "Sin obras descargadas" else "No hay coincidencias",
                    if(works.isEmpty()) "Descarga desde Biblioteca. Los originales permanecen en Kavita." else "Prueba otro título, tipo o estado de lectura.")
                if(works.isNotEmpty()) DisplayTextButton(onClick = { query = ""; category = ""; filter = OfflineFilter.ALL }) { Text("Limpiar filtros") }
            }
            items(filtered, key = { "series-${it.series.id}" }) { indexedWork ->
                val s = indexedWork.series
                val available = s.localUnits(state)
                DisplayCard(Modifier.fillMaxWidth().testTag("offline-series-${s.id}")) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { seriesId = s.id; selection = emptySet() }, verticalAlignment = Alignment.CenterVertically) {
                            DisplayCheckbox(s.id in workSelection,{ checked -> workSelection = if(checked) workSelection + s.id else workSelection - s.id },enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Seleccionar obra ${s.name}" })
                            Cover(repo,account,CoverRef("series",s.id),s.name,Modifier.size(64.dp,90.dp),online,generation)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(s.name,fontWeight = FontWeight.Bold)
                                Text("${available.size} ${s.unitsName(state.libraries).let { if(available.size == 1) it.dropLast(1) else it }} · ${available.count { it.isRead(state) }} leídos",style = MaterialTheme.typography.bodySmall)
                                Text("${s.category(state.libraries).label} · ${sizeText(indexedWork.bytes)}", color = Green)
                            }
                        }
                        FlowRow {
                            val first = s.readingStart(state)
                            DisplayTextButton(enabled = first != null, onClick = { first?.let(open) }, modifier = Modifier.testTag("read-series-${s.id}")) { Text("Leer") }
                            DisplayTextButton(onClick = { seriesId = s.id; selection = emptySet() }) { Text("Ver ${s.unitsName(state.libraries)}") }
                            DisplayTextButton(onClick = { downloadMore(s) }, modifier = Modifier.testTag("download-more-${s.id}")) { Text("Descargar más") }
                            DisplayTextButton(enabled = !busy, onClick = { remove(available.flatMap { it.localIds(state) }.toSet()) }) { Text("Eliminar del móvil") }
                        }
                    }
                }
            }
        } else {
            item {
                FlowRow {
                    val start = series.readingStart(state)
                    DisplayButton(enabled = start != null, onClick = { start?.let(open) }) { Text("Leer / continuar obra") }
                    DisplayTextButton(onClick = { downloadMore(series) }, modifier = Modifier.testTag("download-more-${series.id}")) { Text("Descargar más") }
                    DisplayTextButton(enabled = !busy, onClick = { selection = if(selection.size == units.size) emptySet() else units.map { it.key }.toSet() }) { Text(if(selection.size == units.size) "Quitar selección" else "Seleccionar todos") }
                    DisplayTextButton(enabled = !busy && selection.isNotEmpty(), onClick = { remove(units.filter { it.key in selection }.flatMap { it.localIds(state) }.toSet()) }) { Text("Eliminar selección (${selection.size})") }
                    DisplayTextButton(enabled = !busy, onClick = { remove(units.flatMap { it.localIds(state) }.toSet()) }) { Text("Eliminar toda la obra") }
                }
                DisplayChip(onlyUnread, { onlyUnread = !onlyUnread }, { Text("Solo pendientes de leer") })
                if(readIds.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(readIds) }, modifier = Modifier.testTag("clear-read-downloads")) { Text("Liberar leídos · ${sizeText(readBytes)}") }
                if(onlyUnread && units.all { it.isRead(state) }) Text("Todos los tomos o libros descargados están leídos.",style = MaterialTheme.typography.bodySmall)
            }
            items(units.filter { !onlyUnread || !it.isRead(state) }, key = { it.key }) { unit ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DisplayCheckbox(unit.key in selection,{ checked -> selection = if(checked) selection + unit.key else selection - unit.key }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Seleccionar ${unit.title}" })
                            Cover(repo,account,unit.cover,unit.title,Modifier.size(52.dp,76.dp),online,generation,CoverRef("series",series.id))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(unit.title,fontWeight = FontWeight.Bold)
                                Text(unit.readingLabel(state),color = Green)
                                Text(if(unit.ready(state)) "Disponible sin conexión" else "Descarga incompleta",style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val incomplete = unit.chapters.mapNotNull { state.chapters[it.id] }.filter { it.inQueue }
                        incomplete.forEach { book ->
                            Text(if(incomplete.size == 1) book.state else "${book.chapter.label}: ${book.state}",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-status-${book.chapter.id}"))
                            DisplayProgress(progress = { book.downloadedPages.toFloat() / book.chapter.pages.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        }
                        FlowRow {
                            DisplayTextButton(enabled = unit.readable(state),onClick = {
                                unit.next(state)?.let { id ->
                                    if(unit.isRead(state)) repo.store(account.key).record(id,0,null)
                                    open(id)
                                }
                            }) { Text(if(unit.isRead(state)) "Releer" else if(unit.ready(state)) "Leer" else "Leer disponible") }
                            if(incomplete.isNotEmpty()) {
                                val ids = incomplete.map { it.chapter.id }.toSet()
                                val running = work.any { job -> !job.state.isFinished && ids.any { Jobs.downloadTag(account.key,it) in job.tags } }
                                DisplayTextButton(enabled = !busy, modifier = Modifier.testTag("retry-download-${unit.key}"), onClick = {
                                    action { if(running) Jobs.pauseQueue(repo.context,account.key,ids) else Jobs.resumeQueue(repo.context,account.key,ids) }
                                }) { Text(if(running) "Pausar descarga" else "Reintentar descarga") }
                            }
                            DisplayTextButton(enabled = !busy, onClick = { remove(unit.localIds(state)) }) { Text("Eliminar del móvil") }
                            DisplayTextButton(enabled = !busy, onClick = {
                                val read = unit.isRead(state)
                                repo.store(account.key).markRead(unit.chapters.map { it.id },!read)
                                Jobs.sync(repo.context,account.key)
                            }) { Text(if(unit.isRead(state)) "Marcar sin leer" else "Marcar leído") }
                        }
                    }
                }
            }
        }
    }
    if(removal != null) DisplayAlertDialog(onDismissRequest = { if(!busy) removal = null },
        title = { Text(if(queueOnly) "¿Quitar ${removal!!.size} descargas de la cola?" else "¿Eliminar solo la descarga?") },
        text = { Text(if(queueOnly) "Se borrarán los archivos parciales seleccionados, no las descargas completas. El progreso y los marcadores se conservan."
            else "Se eliminarán ${removal!!.size} archivos de lectura del móvil, incluidas las descargas completas seleccionadas. Los originales de Kavita, tu progreso y tus marcadores se conservan.") },
        confirmButton = { DisplayTextButton(enabled = !busy, onClick = {
            val ids = removal!!; val onlyQueue = queueOnly; removal = null
            action { Jobs.remove(repo.context,account.key,ids,onlyQueue) }
        }) { Text(if(queueOnly) "Quitar de la cola" else "Eliminar descarga") } },
        dismissButton = { DisplayTextButton(enabled = !busy,onClick = { removal = null }) { Text("Cancelar") } })
}
