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
        item { DisplayChip(selected.isBlank(), { select("") }, { Text(tr(R.string.tr_285)) }) }
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
        catch(_: Exception) { error = tr(R.string.tr_286) }
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
            if(seriesId != null) DisplayTextButton(onClick = { seriesId = null; selection = emptySet() }) { Text(tr(R.string.tr_287)) }
            Text(series?.name ?: tr(R.string.tr_288), style = MaterialTheme.typography.headlineSmall)
            Text(if(series == null) tr(R.string.tr_289, works.size, sizeText(local.sumOf { it.totalBytes }))
                else tr(R.string.tr_290, units.size, series.unitsName(state.libraries).let { if(units.size == 1) it.dropLast(1) else it }, units.count { it.isRead(state) }), color = Green)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if(busy) DisplayProgress(Modifier.fillMaxWidth())
        }
        if(series == null) {
            item {
                OutlinedTextField(query, { query = it }, label = { Text(tr(R.string.tr_291)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                if(filter == OfflineFilter.READ) Text(tr(R.string.tr_292),style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    if(readIds.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(readIds) },modifier = Modifier.testTag("clear-read-downloads")) { Text(tr(R.string.tr_293, sizeText(readBytes))) }
                    DisplayTextButton(enabled = local.isNotEmpty() && !busy, onClick = { remove(local.map { it.chapter.id }.toSet()) }) { Text(tr(R.string.tr_294)) }
                    if(workSelection.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(local.filter { it.series.id in workSelection }.map { it.chapter.id }.toSet()) }) { Text(tr(R.string.tr_295, workSelection.size)) }
                }
            }
            if(queue.isNotEmpty()) {
                item {
                    Text(tr(R.string.tr_296, queue.size), fontWeight = FontWeight.SemiBold)
                    DisplayTextButton(onClick = { showQueue = !showQueue }) { Text(if(showQueue) tr(R.string.tr_297) else tr(R.string.tr_298)) }
                    FlowRow {
                        DisplayTextButton(enabled = !busy, onClick = { queueSelection = if(queueSelection.containsAll(queueIds)) emptySet() else queueIds }) { Text(if(queueSelection.containsAll(queueIds)) tr(R.string.tr_224) else tr(R.string.tr_299)) }
                        DisplayTextButton(enabled = !busy, onClick = { remove(queueIds, true) }) { Text(tr(R.string.tr_300)) }
                        val pauseIds = queueSelection.ifEmpty { queueIds }.filter { state.chapters[it]?.wantsDownload == true }.toSet()
                        DisplayTextButton(enabled = !busy && pauseIds.isNotEmpty(), onClick = { action { Jobs.pauseQueue(repo.context,account.key,pauseIds) } }, modifier = Modifier.testTag("pause-queue")) { Text(if(queueSelection.isEmpty()) tr(R.string.tr_301) else tr(R.string.tr_302)) }
                        DisplayTextButton(enabled = !busy, onClick = { action { Jobs.resumeQueue(repo.context, account.key, queueSelection.ifEmpty { queueIds }) } }) { Text(if(queueSelection.isEmpty()) tr(R.string.tr_303) else tr(R.string.tr_304, queueSelection.size)) }
                        if(queueSelection.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(queueSelection,true) }) { Text(tr(R.string.tr_305, queueSelection.size)) }
                    }
                }
                if(showQueue) items(queue, key = { "queue-${it.chapter.id}" }) { book ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DisplayCheckbox(book.chapter.id in queueSelection, { checked -> queueSelection = if(checked) queueSelection + book.chapter.id else queueSelection - book.chapter.id }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = tr(R.string.tr_306, book.series.name, book.chapter.labelFor(book.series,state.libraries)) })
                            Column(Modifier.weight(1f)) { Text(book.series.name); Text(book.chapter.labelFor(book.series,state.libraries), style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(localizedStatus(book.state), style = MaterialTheme.typography.bodySmall)
                        DisplayProgress(progress = { book.downloadedPages.toFloat() / book.chapter.pages.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        val running = work.any { Jobs.downloadTag(account.key,book.chapter.id) in it.tags && !it.state.isFinished }
                        DisplayTextButton(enabled = !busy, onClick = { action { if(running) Jobs.pause(repo.context,account.key,book.chapter.id) else Jobs.download(repo.context,account.key,book.chapter.id) } }) { Text(if(running) tr(R.string.tr_150) else tr(R.string.tr_151)) }
                    } }
                }
            }
            if(filtered.isEmpty()) item {
                EmptyState(if(works.isEmpty()) tr(R.string.tr_307) else tr(R.string.tr_308),
                    if(works.isEmpty()) tr(R.string.tr_309) else tr(R.string.tr_310))
                if(works.isNotEmpty()) DisplayTextButton(onClick = { query = ""; category = ""; filter = OfflineFilter.ALL }) { Text(tr(R.string.tr_311)) }
            }
            items(filtered, key = { "series-${it.series.id}" }) { indexedWork ->
                val s = indexedWork.series
                val available = s.localUnits(state)
                DisplayCard(Modifier.fillMaxWidth().testTag("offline-series-${s.id}")) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { seriesId = s.id; selection = emptySet() }, verticalAlignment = Alignment.CenterVertically) {
                            DisplayCheckbox(s.id in workSelection,{ checked -> workSelection = if(checked) workSelection + s.id else workSelection - s.id },enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = tr(R.string.tr_312, s.name) })
                            Cover(repo,account,CoverRef("series",s.id),s.name,Modifier.size(64.dp,90.dp),online,generation)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(s.name,fontWeight = FontWeight.Bold)
                                Text(tr(R.string.tr_290, available.size, s.unitsName(state.libraries).let { if(available.size == 1) it.dropLast(1) else it }, available.count { it.isRead(state) }),style = MaterialTheme.typography.bodySmall)
                                Text("${s.category(state.libraries).label} · ${sizeText(indexedWork.bytes)}", color = Green)
                            }
                        }
                        FlowRow {
                            val first = s.readingStart(state)
                            DisplayTextButton(enabled = first != null, onClick = { first?.let(open) }, modifier = Modifier.testTag("read-series-${s.id}")) { Text(tr(R.string.tr_233)) }
                            DisplayTextButton(onClick = { seriesId = s.id; selection = emptySet() }) { Text(tr(R.string.tr_197, s.unitsName(state.libraries))) }
                            DisplayTextButton(onClick = { downloadMore(s) }, modifier = Modifier.testTag("download-more-${s.id}")) { Text(tr(R.string.tr_198)) }
                            DisplayTextButton(enabled = !busy, onClick = { remove(available.flatMap { it.localIds(state) }.toSet()) }) { Text(tr(R.string.tr_314)) }
                        }
                    }
                }
            }
        } else {
            item {
                FlowRow {
                    val start = series.readingStart(state)
                    DisplayButton(enabled = start != null, onClick = { start?.let(open) }) { Text(tr(R.string.tr_315)) }
                    DisplayTextButton(onClick = { downloadMore(series) }, modifier = Modifier.testTag("download-more-${series.id}")) { Text(tr(R.string.tr_198)) }
                    DisplayTextButton(enabled = !busy, onClick = { selection = if(selection.size == units.size) emptySet() else units.map { it.key }.toSet() }) { Text(if(selection.size == units.size) tr(R.string.tr_224) else tr(R.string.tr_316)) }
                    DisplayTextButton(enabled = !busy && selection.isNotEmpty(), onClick = { remove(units.filter { it.key in selection }.flatMap { it.localIds(state) }.toSet()) }) { Text(tr(R.string.tr_317, selection.size)) }
                    DisplayTextButton(enabled = !busy, onClick = { remove(units.flatMap { it.localIds(state) }.toSet()) }) { Text(tr(R.string.tr_318)) }
                }
                DisplayChip(onlyUnread, { onlyUnread = !onlyUnread }, { Text(tr(R.string.tr_319)) })
                if(readIds.isNotEmpty()) DisplayTextButton(enabled = !busy, onClick = { remove(readIds) }, modifier = Modifier.testTag("clear-read-downloads")) { Text(tr(R.string.tr_293, sizeText(readBytes))) }
                if(onlyUnread && units.all { it.isRead(state) }) Text(tr(R.string.tr_320),style = MaterialTheme.typography.bodySmall)
            }
            items(units.filter { !onlyUnread || !it.isRead(state) }, key = { it.key }) { unit ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DisplayCheckbox(unit.key in selection,{ checked -> selection = if(checked) selection + unit.key else selection - unit.key }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = tr(R.string.tr_235, unit.title) })
                            Cover(repo,account,unit.cover,unit.title,Modifier.size(52.dp,76.dp),online,generation,CoverRef("series",series.id))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(unit.title,fontWeight = FontWeight.Bold)
                                Text(unit.readingLabel(state),color = Green)
                                Text(if(unit.ready(state)) tr(R.string.tr_189) else tr(R.string.tr_321),style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val incomplete = unit.chapters.mapNotNull { state.chapters[it.id] }.filter { it.inQueue }
                        incomplete.forEach { book ->
                            Text(if(incomplete.size == 1) localizedStatus(book.state) else "${book.chapter.label}: ${localizedStatus(book.state)}",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-status-${book.chapter.id}"))
                            DisplayProgress(progress = { book.downloadedPages.toFloat() / book.chapter.pages.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        }
                        FlowRow {
                            DisplayTextButton(enabled = unit.readable(state),onClick = {
                                unit.next(state)?.let { id ->
                                    if(unit.isRead(state)) repo.store(account.key).record(id,0,null)
                                    open(id)
                                }
                            }) { Text(if(unit.isRead(state)) tr(R.string.tr_322) else if(unit.ready(state)) tr(R.string.tr_233) else tr(R.string.tr_234)) }
                            if(incomplete.isNotEmpty()) {
                                val ids = incomplete.map { it.chapter.id }.toSet()
                                val running = work.any { job -> !job.state.isFinished && ids.any { Jobs.downloadTag(account.key,it) in job.tags } }
                                DisplayTextButton(enabled = !busy, modifier = Modifier.testTag("retry-download-${unit.key}"), onClick = {
                                    action { if(running) Jobs.pauseQueue(repo.context,account.key,ids) else Jobs.resumeQueue(repo.context,account.key,ids) }
                                }) { Text(if(running) tr(R.string.tr_323) else tr(R.string.tr_324)) }
                            }
                            DisplayTextButton(enabled = !busy, onClick = { remove(unit.localIds(state)) }) { Text(tr(R.string.tr_314)) }
                            DisplayTextButton(enabled = !busy, onClick = {
                                val read = unit.isRead(state)
                                repo.store(account.key).markRead(unit.chapters.map { it.id },!read)
                                Jobs.sync(repo.context,account.key)
                            }) { Text(if(unit.isRead(state)) tr(R.string.tr_325) else tr(R.string.tr_326)) }
                        }
                    }
                }
            }
        }
    }
    if(removal != null) DisplayAlertDialog(onDismissRequest = { if(!busy) removal = null },
        title = { Text(if(queueOnly) tr(R.string.tr_327, removal!!.size) else tr(R.string.tr_328)) },
        text = { Text(if(queueOnly) tr(R.string.tr_329)
            else tr(R.string.tr_330, removal!!.size)) },
        confirmButton = { DisplayTextButton(enabled = !busy, onClick = {
            val ids = removal!!; val onlyQueue = queueOnly; removal = null
            action { Jobs.remove(repo.context,account.key,ids,onlyQueue) }
        }) { Text(if(queueOnly) tr(R.string.tr_331) else tr(R.string.tr_332)) } },
        dismissButton = { DisplayTextButton(enabled = !busy,onClick = { removal = null }) { Text(tr(R.string.tr_161)) } })
}
