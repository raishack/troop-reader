package es.gamingtroop.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

data class ReaderTocEntry(val entry: Toc, val depth: Int)
fun readerToc(items: List<Toc>, total: Int, depth: Int = 0): List<ReaderTocEntry> = items.flatMap {
    (if(it.page in 0 until total) listOf(ReaderTocEntry(it, depth)) else emptyList()) + readerToc(it.children, total, depth + 1)
}
fun Toc.anchor(): String = part.substringAfter('#', part).trim().let { if(it.isBlank()) "" else "#${it.removePrefix("#")}" }
fun activeToc(entries: List<ReaderTocEntry>, page: Int, position: String): Int {
    val anchor = if(position.startsWith("#")) position else Regex("^id\\([\"'](.*)[\"']\\)$").matchEntire(position)?.groupValues?.get(1)?.let { "#$it" }
    val exact = entries.indexOfLast { it.entry.page == page && anchor != null && it.entry.anchor() == anchor }
    // With several anchors on one HTML page, do not claim the last one is current.
    return if(exact >= 0) exact else entries.indexOfFirst { it.entry.page == page }.takeIf { it >= 0 }
        ?: entries.indexOfLast { it.entry.page < page }
}

/** Browse only the cached catalogue/payload. Opening the picker never alters progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReaderNavigator(repo: Repository, account: Account, saved: SavedChapter,
    state: LocalState, page: Int, position: String, initialSection: String,
    onDismiss: () -> Unit, onChapter: (Int) -> Unit, onPage: (Int, String) -> Unit, onDownloadMore: () -> Unit) {
    val units = remember(state.catalog, state.chapters, state.libraries, saved.series) {
        saved.series.readingUnits(saved.series.cachedVolumes(state), state.libraries)
    }
    val currentUnit = units.indexOfFirst { unit -> unit.chapters.any { it.id == saved.chapter.id } }
    val index = remember(saved.toc, saved.chapter.pages) { readerToc(saved.toc, saved.chapter.pages) }
    val active = activeToc(index, page, position)
    var section by rememberSaveable(saved.chapter.id) { mutableStateOf(initialSection) }
    var query by rememberSaveable(saved.chapter.id) { mutableStateOf("") }
    var searchOpen by rememberSaveable(saved.chapter.id) { mutableStateOf(false) }
    var jump by rememberSaveable(saved.chapter.id) { mutableStateOf(false) }
    var number by rememberSaveable(saved.chapter.id) { mutableStateOf("") }
    val jumpPage = number.toIntOrNull()?.takeIf { it in 1..saved.chapter.pages }?.minus(1)
    fun submitJump() { jumpPage?.let { focusJump -> jump = false; onPage(focusJump, if(saved.epub) "//body" else "") } }
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val unitName = saved.series.unitsName(state.libraries).replaceFirstChar { it.uppercase() }
    DisplayBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("reader-navigator")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if(!compact) Text(saved.series.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${saved.chapter.labelFor(saved.series,state.libraries)} · ${page + 1} / ${saved.chapter.pages}",
                        style = if(compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall, color = Green,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DisplayIconButton(onClick = { number = (page + 1).toString(); jump = true }) { Icon(Icons.Outlined.Numbers,if(saved.epub) tr(R.string.tr_405) else tr(R.string.tr_406)) }
                if(compact) DisplayIconButton(onClick = onDownloadMore) { Icon(Icons.Outlined.CloudDownload,tr(R.string.tr_198)) }
                DisplayIconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close,tr(R.string.tr_407)) }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayChip(section == "units", { section = "units" }, { Text(unitName) }, modifier = Modifier.testTag("navigator-units"))
                DisplayChip(section == "pages", { section = "pages" }, { Text(if(saved.epub) "Índice" else tr(R.string.tr_408)) }, modifier = Modifier.testTag("navigator-pages"))
                if(compact && section == "units") {
                    DisplayIconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Outlined.Search,tr(R.string.tr_409)) }
                    if(query.isNotBlank() && !searchOpen) DisplayTextButton(onClick = { query = "" }) { Text(tr(R.string.tr_410)) }
                }
            }
            if(section == "units") {
                if(!compact || searchOpen) OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(tr(R.string.tr_411, saved.series.unitsName(state.libraries))) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                        focus.clearFocus(); keyboard?.hide(); searchOpen = false
                    }),
                    leadingIcon = { Icon(Icons.Outlined.Search,null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                val list = units.filter { it.title.searchKey().contains(query.searchKey()) || it.chapters.any { c -> c.label.searchKey().contains(query.searchKey()) } }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentUnit.coerceAtLeast(0))
                LaunchedEffect(query) { if(query.isNotEmpty()) listState.scrollToItem(0) }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("navigator-unit-list"), contentPadding = PaddingValues(vertical = 8.dp), flingBehavior=displayFling()) {
                    if(list.isEmpty()) item { Text(tr(R.string.tr_412),Modifier.padding(20.dp)) }
                    items(list,key = { it.key }) { unit ->
                        val isCurrent = unit.chapters.any { it.id == saved.chapter.id }
                        val target = if(isCurrent) saved.chapter.id else unit.next(state)
                        val available = unit.chapters.firstOrNull { it.id == target }?.let { state.playable(it) } == true
                        val readyCount = unit.chapters.count { state.chapters[it.id]?.ready == true }
                        Surface(color = if(isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            ListItem(colors = ListItemDefaults.colors(containerColor = if(isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                                modifier = Modifier.testTag("navigator-unit-${unit.key}").semantics { selected = isCurrent }
                                .clickable(enabled = available) { target?.let(onChapter) },
                                leadingContent = { Cover(repo,account,unit.cover,unit.title,Modifier.size(48.dp,70.dp),fallback = CoverRef("series",saved.series.id)) },
                                headlineContent = { Text(unit.title) },
                                supportingContent = { Text((if(isCurrent) tr(R.string.tr_413) else "") + unit.readingLabel(state) + " · " + when {
                                    readyCount == unit.chapters.size -> tr(R.string.tr_195)
                                    readyCount > 0 -> tr(R.string.tr_414, readyCount, unit.chapters.size)
                                    available -> tr(R.string.tr_415)
                                    else -> tr(R.string.tr_416)
                                }) },
                                trailingContent = { Icon(if(available) Icons.Outlined.MenuBook else Icons.Outlined.CloudDownload, null) })
                        }
                        if(unit.chapters.size > 1) unit.chapters.forEachIndexed { number,c ->
                            val current = c.id == saved.chapter.id
                            val playable = state.playable(c)
                            ListItem(modifier = Modifier.padding(start = 24.dp).testTag("navigator-part-${c.id}")
                                .semantics { selected = current }.clickable(enabled = playable) { onChapter(c.id) },
                                headlineContent = { Text(tr(R.string.tr_417, number + 1)) },
                                supportingContent = { Text((if(current) tr(R.string.tr_413) else "") + if(!playable) tr(R.string.tr_416) else if(state.isRead(c)) tr(R.string.tr_418) else tr(R.string.tr_419, state.position(c) + 1, c.pages)) })
                        }
                    }
                }
                if(!compact) DisplayTextButton(onClick = onDownloadMore,modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.tr_420, saved.series.unitsName(state.libraries))) }
            } else if(saved.epub) {
                if(index.isEmpty()) Text(tr(R.string.tr_421),Modifier.padding(16.dp),style = MaterialTheme.typography.bodySmall)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = active.coerceAtLeast(0))
                LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("navigator-toc"), contentPadding = PaddingValues(bottom = 20.dp), flingBehavior=displayFling()) {
                    if(index.isEmpty()) items(saved.chapter.pages) { i ->
                        ListItem(headlineContent = { Text(tr(R.string.tr_137, i + 1)) }, modifier = Modifier.testTag("navigator-section-$i")
                            .semantics { selected = page == i }.clickable { onPage(i, "") })
                    }
                    items(index.size) { i ->
                        val row = index[i]
                        ListItem(headlineContent = { Text(row.entry.title) }, supportingContent = { Text(tr(R.string.tr_137, row.entry.page + 1) + if(i == active) tr(R.string.tr_422) else "") },
                            modifier = Modifier.padding(start = (row.depth.coerceAtMost(5)*12).dp).testTag("navigator-toc-$i")
                                .semantics { selected = i == active }.clickable { onPage(row.entry.page, row.entry.anchor()) })
                    }
                }
            } else {
                val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = page)
                val dir = remember(saved.chapter.id) { repo.store(account.key).chapterDir(saved.chapter.id) }
                Text(tr(R.string.tr_423),Modifier.padding(horizontal = 16.dp),style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(96.dp),state = gridState,modifier = Modifier.weight(1f).testTag("navigator-page-grid"),
                    contentPadding = PaddingValues(16.dp),horizontalArrangement = Arrangement.spacedBy(10.dp),verticalArrangement = Arrangement.spacedBy(10.dp), flingBehavior=displayFling()) {
                    items(saved.chapter.pages,key = { it }) { i ->
                        DisplayCard(onClick = { onPage(i,"") }, modifier = Modifier.testTag("navigator-page-$i").semantics { selected = i == page },
                            colors = CardDefaults.cardColors(containerColor = if(i == page) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                            DisplayImage(File(dir,"$i.img"),tr(R.string.tr_424, i + 1),Modifier.fillMaxWidth().aspectRatio(.7f),contentScale = ContentScale.Fit)
                            Text("${i + 1}" + if(i == page) tr(R.string.tr_422) else "",Modifier.padding(8.dp),style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if(jump) DisplayAlertDialog(onDismissRequest = { jump = false },
        title = { Text(if(saved.epub) tr(R.string.tr_405) else tr(R.string.tr_406)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
            Text(if(saved.epub) tr(R.string.tr_425, saved.chapter.pages) else tr(R.string.tr_426, saved.chapter.pages))
            OutlinedTextField(number, { value -> number = value.filter { it in '0'..'9' }.take(9) },
                label = { Text(if(saved.epub) tr(R.string.tr_427) else tr(R.string.tr_428)) },singleLine = true,
                isError = number.isNotBlank() && jumpPage == null,
                supportingText = { if(number.isNotBlank() && jumpPage == null) Text(tr(R.string.tr_429, saved.chapter.pages)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if(jumpPage != null) { keyboard?.hide(); submitJump() } }),
                modifier = Modifier.testTag("jump-number"))
        } },
        confirmButton = { DisplayTextButton(enabled = jumpPage != null,onClick = { keyboard?.hide(); submitJump() },modifier = Modifier.testTag("jump-confirm")) { Text(tr(R.string.tr_141)) } },
        dismissButton = { DisplayTextButton(onClick = { jump = false }) { Text(tr(R.string.tr_161)) } })
}
