package es.gamingtroop.reader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.*

val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
val Surface: Color @Composable get() = MaterialTheme.colorScheme.surface
class MainActivity: ComponentActivity() {
    lateinit var displayPreferences: DisplayPreferences
    lateinit var einkRefresh: EinkRefresh
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if(::einkRefresh.isInitialized && event.actionMasked==android.view.MotionEvent.ACTION_DOWN) einkRefresh.beginTouch()
        val result=super.dispatchTouchEvent(event)
        if(::einkRefresh.isInitialized && event.actionMasked in listOf(android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL)) einkRefresh.endTouch()
        return result
    }
    override fun onPause() { if(::einkRefresh.isInitialized) einkRefresh.foreground(false);super.onPause() }
    override fun onDestroy() { if(::einkRefresh.isInitialized) einkRefresh.dispose();super.onDestroy() }
    var readerKeyHandler: ((android.view.KeyEvent) -> Boolean)? = null
    // Use the public Activity callbacks; ComponentActivity's dispatcher is an
    // AndroidX internal API. Unhandled keys retain Android's normal behaviour.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        readerKeyHandler?.invoke(event) == true || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val result=readerKeyHandler?.invoke(event) == true || super.onKeyUp(keyCode,event)
        if(::einkRefresh.isInitialized) einkRefresh.request()
        return result
    }
    private var updateRequested by mutableStateOf(false)
    private var personalRequested by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayPreferences=DisplayPreferences(this);einkRefresh=EinkRefresh(displayPreferences)
        updateRequested = intent.getBooleanExtra("show_updates", false)
        personalRequested = intent.getBooleanExtra("show_personal", false)
        if(BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) Demo.install(this)
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { DisplayTheme(displayPreferences,einkRefresh) { App(repository(), updateRequested, personalRequested) { updateRequested = false; personalRequested = false } } }
    }
    override fun onResume() {
        super.onResume()
        if(::einkRefresh.isInitialized) einkRefresh.foreground(true)
        if(ReaderApp.updateAutomationEnabled) appUpdates().check()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        updateRequested = intent.getBooleanExtra("show_updates", false)
        personalRequested = intent.getBooleanExtra("show_personal", false)
    }
}

@Composable fun App(repo: Repository, updateRequested: Boolean = false, personalRequested: Boolean = false, updateHandled: () -> Unit = {}) {
    var account by remember { mutableStateOf(repo.active()) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(updateRequested) { if(updateRequested) { showUpdates = true; updateHandled() } }
    CompositionLocalProvider(LocalOpenUpdates provides { showUpdates = true }) {
    if (account == null) Login(repo) { account = it }
    else key(account!!.key) { Home(repo, account!!, personalRequested, updateHandled) {
        WorkManager.getInstance(repo.context).cancelAllWorkByTag(account!!.key)
        repo.context.voicePlayback().stop(); repo.vault.clear(); account = null
    } }
    }
    if(showUpdates) UpdateDialog(repo.context.appUpdates()) { showUpdates = false }
}

@Composable fun Login(repo: Repository, onLogin: (Account) -> Unit) {
    val openUpdates = LocalOpenUpdates.current
    var server by rememberSaveable { mutableStateOf("https://libros.gamingtroop.es") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState(), flingBehavior=displayFling()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(52.dp), tint = Green)
        Spacer(Modifier.height(18.dp))
        Text("Tu biblioteca.\nTambién sin conexión.", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        Text("TROOP READER · PARA KAVITA", color = Green, modifier = Modifier.padding(vertical = 18.dp), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(server, { server = it }, label = { Text("Servidor Kavita") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp)) }
        DisplayButton(onClick = { busy = true; error = null; scope.launch {
            try { val a = repo.login(server, user, password); password = ""; Jobs.scheduleSync(repo.context, a.key); DiscoveryJobs.schedule(repo.context,a.key); onLogin(a) }
            catch (e: Exception) { error = if (e is ApiError || e is IllegalArgumentException) e.message else "No se pudo conectar de forma segura. Comprueba servidor y conexión." }
            finally { busy = false }
        } }, enabled = !busy && user.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp)) {
            if (busy) DisplaySpinner(Modifier.size(22.dp)) else Text("Entrar en mi biblioteca")
        }
        Text("App independiente · Versión de prueba\nTu contraseña no se guarda. Las descargas quedan en este dispositivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
        if (BuildConfig.DEBUG) DisplayTextButton(onClick = { Demo.install(repo.context); repo.active()?.let(onLogin) }, enabled = !busy) { Text("Ver demostración sin cuenta") }
        DisplayTextButton(onClick = openUpdates) { Text("Actualizaciones de la app") }
        DisplaySettings()
    } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun Home(repo: Repository, account: Account, personalRequested: Boolean = false, personalHandled: () -> Unit = {}, logout: () -> Unit) {
    val openUpdates = LocalOpenUpdates.current
    val store = remember { repo.store(account.key) }
    val state by store.states.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate)
    val work by remember(account.key) { WorkManager.getInstance(repo.context).getWorkInfosByTagFlow(account.key) }.collectAsState(emptyList(), context = Dispatchers.Main.immediate)
    val network = networkState(repo.context)
    val online = network.available
    var refreshEpoch by remember { mutableIntStateOf(0) }
    val demo = account.server == "https://demo.invalid"
    // UI continuations must return to Main after repository/WorkManager IO.
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var personalStart by rememberSaveable { mutableStateOf("favorites") }
    LaunchedEffect(personalRequested) { if(personalRequested) { personalStart="news:${android.os.SystemClock.elapsedRealtime()}";tab=3;personalHandled() } }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedLibrary by rememberSaveable { mutableIntStateOf(0) }
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var reader by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var loadingChapters by remember { mutableStateOf(false) }
    var chapterError by remember { mutableStateOf<String?>(null) }
    var chapterNotice by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatch by remember { mutableStateOf(false) }
    var queueBusy by remember { mutableStateOf(false) }
    var batchJob by remember { mutableStateOf<Job?>(null) }
    var volumes by remember { mutableStateOf<List<Volume>>(emptyList()) }
    val units = selectedSeries?.readingUnits(volumes, state.libraries).orEmpty()
    var busy by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var policyBusy by remember { mutableStateOf(false) }
    fun showCatalog(series: Series) {
        selectedSeries = series; chapterError = null; chapterNotice = null
        selection = emptySet(); volumes = series.cachedVolumes(store.get())
    }
    fun perform(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
        snackbar.showSnackbar(if(e is ApiError || e is IllegalStateException || e is IllegalArgumentException) e.message.orEmpty() else "No se pudo conectar. Lo descargado sigue disponible.")
    } } }
    // Re-query the network on resume, then retry stale/failed catalogue requests.
    LaunchedEffect(network.generation, refreshEpoch) { withContext(Dispatchers.Main.immediate) {
        if(online && !demo) {
            Jobs.sync(repo.context, account.key)
            busy = true
            try { repo.refreshLibrary(account.key); if(store.get().followed.isNotEmpty()) DiscoveryJobs.check(repo,account.key) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { snackbar.showSnackbar("No se pudo cargar Kavita. Tus descargas siguen disponibles; puedes reintentar.") }
            finally { busy = false }
        }
    } }
    LaunchedEffect(selectedSeries?.id, network.generation, refreshEpoch) { withContext(Dispatchers.Main.immediate) {
        val series = selectedSeries ?: return@withContext
        if(online && !demo) {
            loadingChapters = true; chapterError = null
            try { volumes = repo.volumes(account.key, series.id) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { chapterError = "No se pudo consultar Kavita. Se muestra el catálogo guardado." }
            finally { loadingChapters = false }
        }
    } }
    LaunchedEffect(units) {
        selection = selection.intersect(units.map { it.key }.toSet())
        if(selection.isEmpty()) confirmBatch = false
    }
    val displayRefresh=LocalEinkRefresh.current
    LaunchedEffect(tab,reader,selectedSeries?.id,settings,query,selectedLibrary,selectedCategory) { displayRefresh?.request() }
    LaunchedEffect(busy,loadingChapters,volumes,state.coverRevision) { displayRefresh?.request(data=true) }
    val openId = reader
    if (openId != null && state.chapters[openId]?.readable == true) {
        key(openId) { ReaderScreen(repo, account, state.chapters.getValue(openId), state.readerSettings(state.chapters.getValue(openId).series.id),
            onNext = { reader = it }, onDownloadMore = {
                reader = null; showCatalog(state.chapters.getValue(openId).series)
                Jobs.sync(repo.context, account.key)
            }, close = { reader = null; Jobs.sync(repo.context, account.key) }) }
        return
    }
    BackHandler(tab != 0 && selectedSeries == null && !settings && !signingOut) { tab = 0 }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Column { Text("Troop Reader", fontWeight = FontWeight.Bold); Text("${account.username} · Kavita", style = MaterialTheme.typography.labelMedium, color = Green) } }, actions = {
            DisplayIconButton(onClick = { refreshEpoch++ }, enabled = !demo) { Icon(Icons.Outlined.Refresh, "Actualizar biblioteca") }
            DisplayIconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Ajustes") }
            UpdateIcon(repo.context.appUpdates())
        })
    }, bottomBar = {
        NavigationBar { listOf("Biblioteca" to Icons.Outlined.LocalLibrary, "Descargas" to Icons.Outlined.DownloadForOffline, "Progreso" to Icons.Outlined.Sync, "Mi espacio" to Icons.Outlined.PersonOutline).forEachIndexed { index, (label, icon) ->
            DisplayNavigationItem(selected = tab == index, onClick = { tab = index }, icon = { BadgedBox(badge = { if(index == 2 && (state.pending.isNotEmpty() || state.bookmarks.any { it.dirty })) Badge { Text((state.pending.size + state.bookmarks.count { it.dirty }).toString()) } }) { Icon(icon, label) } }, label = { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        } }
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        if (demo || !online) Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(if (demo) "Demostración · datos de ejemplo" else "Sin conexión · tus descargas siguen disponibles", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(18.dp, 8.dp))
        }
        if(busy) DisplayProgress(Modifier.fillMaxWidth())
        when(tab) {
            0 -> {
                val list = state.series.filter { (selectedLibrary == 0 || it.libraryId == selectedLibrary) && (selectedCategory.isBlank() || it.category(state.libraries).name == selectedCategory) && it.name.searchKey().contains(query.searchKey()) }
                val continuing = state.chapters.values.filter {
                    (selectedLibrary == 0 || it.series.libraryId == selectedLibrary) && it.readable &&
                        !state.isRead(it.chapter) && state.hasStarted(it.chapter)
                }.sortedByDescending { it.lastReadAt }
                LazyVerticalGrid(GridCells.Adaptive(145.dp), contentPadding = PaddingValues(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f).testTag("library-grid"), flingBehavior=displayFling()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar en la biblioteca") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), flingBehavior=displayFling()) {
                            item { DisplayChip(selectedLibrary == 0, { selectedLibrary = 0 }, { Text("Todo") }) }
                            items(state.libraries.size) { i -> val lib = state.libraries[i]; DisplayChip(selectedLibrary == lib.id, { selectedLibrary = lib.id }, { Text(lib.name) }) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CategoryFilters(state.series.map { it.category(state.libraries) }.distinct(), selectedCategory) { selectedCategory = it }
                    }
                    if (continuing.isNotEmpty() && query.isBlank() && selectedCategory.isBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Continúa donde lo dejaste", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), flingBehavior=displayFling()) {
                                    items(continuing.take(12), key = { it.chapter.id }) { book ->
                                        DisplayCard(onClick = { reader = book.chapter.id }, modifier = Modifier.width(270.dp).testTag("continue-${book.chapter.id}")) {
                                            Column(Modifier.padding(14.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Cover(repo, account, CoverRef("chapter",book.chapter.id), book.chapter.labelFor(book.series, state.libraries), Modifier.size(44.dp,64.dp), online, network.generation, CoverRef("series",book.series.id)); Spacer(Modifier.width(10.dp))
                                                    Text(book.series.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                                Text(book.chapter.labelFor(book.series, state.libraries), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                                                DisplayProgress(progress = { book.progressFraction(state.progress[book.chapter.id]) }, modifier = Modifier.fillMaxWidth())
                                                Text(if(book.ready) "Disponible sin conexión" else "Lectura parcial · ${book.downloadedPages}/${book.chapter.pages}", style = MaterialTheme.typography.labelSmall, color = Green)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if(list.isEmpty() && !busy) item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(if(query.isNotBlank() || selectedLibrary != 0) "Sin resultados" else "Tu biblioteca aparecerá aquí", if(query.isNotBlank() || selectedLibrary != 0) "Prueba otro título o cambia el filtro de biblioteca." else "Conéctate a Kavita y pulsa actualizar. Las descargas no requieren conexión.")
                    }
                    items(list, key = { it.id }) { s -> Column(Modifier.testTag("library-series-${s.id}").clickable {
                        val start = s.readingStart(state)
                        if(start != null) reader = start
                        else showCatalog(s)
                    }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(10.dp)).background(Surface), contentAlignment = Alignment.Center) {
                            Cover(repo, account, CoverRef("series",s.id), s.name, Modifier.fillMaxSize(), online, network.generation + refreshEpoch)
                            if(state.chapters.values.any { it.series.id == s.id && it.ready }) Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.OfflinePin, "Descargado", tint = Green, modifier = Modifier.padding(5.dp).size(18.dp)) }
                        }
                        val localBooks = state.chapters.values.filter { it.series.id == s.id }
                        val pagesRead = localBooks.sumOf { (state.progress[it.chapter.id]?.pageNum ?: it.chapter.pagesRead).coerceIn(0, it.chapter.pages) }
                        val progress = if(localBooks.isNotEmpty()) pagesRead.toFloat() / localBooks.sumOf { it.chapter.pages }.coerceAtLeast(1) else s.pagesRead.toFloat() / s.pages.coerceAtLeast(1)
                        if(progress > 0f) DisplayProgress(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
                        Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        Text(s.category(state.libraries).label + if(s.format == 4) " · PDF" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DisplayTextButton(onClick = { showCatalog(s) }, modifier = Modifier.testTag("catalog-series-${s.id}")) {
                            Column { Text("Ver ${s.unitsName(state.libraries)}"); Text("Descargar más", style = MaterialTheme.typography.labelSmall) }
                        }
                    } }
                }
            }
            1 -> OfflineLibrary(repo, account, state, work, online, network.generation, downloadMore = { showCatalog(it) }) { reader = it }
            3 -> PersonalHub(repo,account,state,personalStart) { showCatalog(it) }
            2 -> {
                Text("Progreso de lectura", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(18.dp))
                Text(state.syncMessage, color = Green, modifier = Modifier.padding(horizontal = 18.dp))
                Text(if(state.settings.preferLocalChanges) "Prioridad: cambios de este móvil" else "Prioridad: revisar conflictos",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp))
                if(state.lastSync > 0) Text("Última comprobación: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(state.lastSync))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(18.dp,8.dp))
                DisplayButton(onClick = { perform { repo.sync(account.key) } }, enabled = !demo, modifier = Modifier.padding(18.dp, 8.dp)) { Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(8.dp)); Text("Sincronizar ahora") }
                if(state.pending.isEmpty() && state.syncIssues.isEmpty() && state.bookmarkIssues.isEmpty() && state.bookmarks.none { it.dirty }) EmptyState("Sin cambios pendientes", "Puedes seguir leyendo sin cobertura. Tu avance se guardará aquí hasta reconectar.")
                LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), flingBehavior=displayFling()) {
                    items(state.syncIssues.toList(), key = { "issue-${it.first}" }) { (id, message) -> Card { Column(Modifier.padding(16.dp)) {
                        Text(state.chapters[id]?.series?.name ?: "Lectura $id", fontWeight = FontWeight.Bold)
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Text("Tu copia local y tu progreso se conservan.", style = MaterialTheme.typography.bodySmall)
                    } } }
                    items(state.bookmarkIssues.toList(), key = { "bookmark-issue-${it.first}" }) { (id, message) -> Card { Column(Modifier.padding(16.dp)) {
                        Text("Marcadores · ${state.chapters[id]?.series?.name ?: id}", fontWeight = FontWeight.Bold)
                        Text(message, color = MaterialTheme.colorScheme.error)
                    } } }
                    items(state.bookmarks.filter { it.dirty }, key = { "bookmark-${it.id}" }) { b -> Card { Column(Modifier.padding(16.dp)) {
                        Text("Marcador · ${b.title}", fontWeight = FontWeight.Bold)
                        Text(state.chapters[b.chapterId]?.series?.name.orEmpty())
                        Text(if(b.deleted) "Eliminación pendiente" else "Guardado en el móvil · pendiente de sincronizar")
                        b.error?.takeUnless { b.conflict && state.settings.preferLocalChanges && !b.restored }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if(b.conflict && (!state.settings.preferLocalChanges || b.restored)) {
                            Text("La API no informa de la fecha de edición. No elegimos una versión por su hora de descarga.", style = MaterialTheme.typography.bodySmall)
                            if(!b.deleted && state.chapters[b.chapterId]?.epub == true) DisplayTextButton(onClick = { perform {
                                repo.resolveBookmark(account.key,b.id,true); if(!demo) repo.sync(account.key)
                            } }) { Text("Conservar ambos") }
                            DisplayTextButton(onClick = { perform { repo.resolveBookmark(account.key,b.id,false); if(!demo) repo.sync(account.key) } }) { Text("Conservar Kavita") }
                        } else if(b.deleted) DisplayTextButton(onClick = { store.undoBookmarkDelete(b.id); Jobs.sync(repo.context,account.key) }) { Text("Deshacer eliminación") }
                    } } }
                    items(state.pending.toList(), key = { it.first }) { (id, p) -> Card { Column(Modifier.padding(16.dp)) {
                        Text(state.chapters[id]?.series?.name ?: "Lectura $id", fontWeight = FontWeight.Bold)
                        Text("En este móvil: ${p.local.pageNum} · ${if(p.conflict != null) "En Kavita: ${p.conflict.pageNum}" else "Pendiente de enviar"}")
                        p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if(p.conflict != null && (!state.settings.preferLocalChanges || p.restored)) {
                            Text("También cambió en otro dispositivo. Elige qué posición conservar.", modifier = Modifier.padding(top = 8.dp))
                            DisplayTextButton(onClick = { perform { repo.resolve(account.key, id, true); repo.sync(account.key) } }) { Text("Conservar la del móvil") }
                            DisplayTextButton(onClick = { perform { repo.resolve(account.key, id, false) } }) { Text("Conservar la de Kavita") }
                        }
                    } } }
                }
            }
        }
    } }
    if(selectedSeries != null) DisplayBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { if(!queueBusy) selectedSeries = null }) {
        val series = selectedSeries!!
        val noun = series.unitsName(state.libraries)
        val eligible = units.filter { !it.ready(state) && it.chapters.isNotEmpty() && it.chapters.all { c -> c.pages > 0 } }.map { it.key }.toSet()
        val allSelected = eligible.isNotEmpty() && selection.containsAll(eligible)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("series-catalog"), contentPadding = PaddingValues(18.dp), flingBehavior=displayFling()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(repo, account, CoverRef("series",series.id), series.name, Modifier.size(60.dp,86.dp), online, network.generation + refreshEpoch)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(series.name, style = MaterialTheme.typography.headlineSmall)
                        Text("${units.size} $noun", color = Green)
                    }
                }
                SeriesPersonalControls(store,series,state)
                Text(if(demo || !online) "Catálogo guardado · abre los $noun descargados" else "Elige $noun para leer sin conexión", modifier = Modifier.padding(vertical = 12.dp), color = Green)
                if(!demo && online && units.isNotEmpty()) {
                    DisplayTextButton(enabled = !queueBusy && !loadingChapters && eligible.isNotEmpty(), onClick = {
                        selection = if(allSelected) emptySet() else eligible
                    }) { Text(if(allSelected) "Quitar selección" else "Seleccionar todos los $noun") }
                    DisplayButton(enabled = !queueBusy && !loadingChapters && selection.isNotEmpty(), onClick = { confirmBatch = true }) { Text("Descargar (${selection.size})") }
                    Text("Los ya descargados no se repiten.", style = MaterialTheme.typography.bodySmall)
                }
                if(queueBusy || loadingChapters) DisplayProgress(Modifier.fillMaxWidth())
                if(batchJob?.isActive == true) DisplayTextButton(onClick = {
                    batchJob?.cancel(); chapterNotice = "Preparación detenida. Lo añadido sigue en Descargas."
                }) { Text("Detener preparación del lote") }
                chapterNotice?.let { Text(it, color = Green, modifier = Modifier.padding(vertical = 8.dp)) }
                chapterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if(!demo && chapterError != null) DisplayTextButton(onClick = { refreshEpoch++ }) { Text("Reintentar conexión") }
            }
            items(units, key = { it.key }) { unit ->
                val ready = unit.ready(state)
                ListItem(leadingContent = {
                    Cover(repo, account, unit.cover, unit.title, Modifier.size(52.dp,76.dp), online, network.generation + refreshEpoch, CoverRef("series",series.id))
                }, headlineContent = { Text(unit.title) }, supportingContent = {
                    Text("${unit.pages} páginas/secciones" + if(unit.chapters.size > 1) " · ${unit.chapters.size} partes" else "")
                    Text(unit.readingLabel(state), color = Green)
                    if(ready) Text("Disponible sin conexión", color = Green)
                }, trailingContent = {
                    Column {
                    if(unit.readable(state)) DisplayTextButton(onClick = { selectedSeries = null; reader = unit.next(state) }) { Text(if(ready) "Leer" else "Leer disponible") }
                    if(!ready && !demo && online) DisplayCheckbox(checked = unit.key in selection,
                        onCheckedChange = { checked -> selection = if(checked) selection + unit.key else selection - unit.key },
                        enabled = !queueBusy && unit.key in eligible,
                        modifier = Modifier.semantics { contentDescription = "Seleccionar ${unit.title}" })
                    }
                })
            }
            if(units.isEmpty() && !loadingChapters) item { Text("No hay $noun cargados. Si estás offline, abre Descargas.", modifier = Modifier.padding(16.dp)) }
        }
    }
    if(confirmBatch && selectedSeries != null) DisplayAlertDialog(onDismissRequest = { confirmBatch = false },
        title = { Text("Descargar selección") },
        text = { Text("${selection.size} ${selectedSeries!!.unitsName(state.libraries)} · ${selectedParts(units, selection).sumOf { it.pages.toLong() }} páginas/secciones. Se guardarán de uno en uno. " +
            (if(state.settings.wifiOnly) "Esperarán a una red sin límite de datos. " else "Pueden usar datos móviles. ") +
            "El tamaño final depende de las imágenes y recursos. Puedes pausar cada descarga.") },
        confirmButton = { DisplayTextButton(onClick = {
            val series = selectedSeries!!; val selectedUnits = units.filter { it.key in selection }; val chosen = selectedParts(units, selection)
            confirmBatch = false; queueBusy = true; chapterError = null
            batchJob = scope.launch {
                try {
                    val result = repo.prepareBatch(account.key,series,chosen) { id -> Jobs.download(repo.context,account.key,id) }
                    val failed = selectedUnits.filter { u -> u.chapters.any { it.id in result.errors } }
                    chapterNotice = "${selectedUnits.size - failed.size} ${series.unitsName(state.libraries)} preparados · ${result.queued} archivos en cola"
                    chapterError = result.errors.takeIf { it.isNotEmpty() }?.let { errors ->
                        "${errors.size} archivos no preparados: " + errors.entries.take(3).joinToString("; ") { (id, msg) -> "${chosen.find { it.id == id }?.label ?: id}: $msg" }
                    }
                    selection = failed.map { it.key }.toSet()
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { chapterError = "No se completó el lote. Revisa Descargas antes de reintentar." }
                finally { queueBusy = false }
            }
        }) { Text("Añadir a descargas") } }, dismissButton = { DisplayTextButton(onClick = { confirmBatch = false }) { Text("Cancelar") } })
    if(settings) DisplayAlertDialog(onDismissRequest = { settings = false }, title = { Text("Ajustes") }, text = { Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
        Text("${account.server}\nKavita ${account.version.ifBlank { "versión no informada" }}\nTroop Reader ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        DisplayTextButton(onClick = { settings = false; openUpdates() }) { Text("Actualizaciones de la app") }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Descargar solo por Wi‑Fi", Modifier.weight(1f)); DisplaySwitch(state.settings.wifiOnly, { value -> perform {
            policyBusy = true
            try { Jobs.setWifiOnly(repo.context,account.key,value) }
            finally { policyBusy = false }
        } }, enabled = !policyBusy, modifier = Modifier.semantics { contentDescription = "Solo Wi-Fi" }) }
        DisplaySettings()
        SmartDownloadSettings(repo,account,state)
        if(state.coverIssues.isNotEmpty()) {
            Text("Carátulas: ${state.coverIssues.size} avisos", color = MaterialTheme.colorScheme.error)
            for(issue in state.coverIssues.values.distinct().take(2)) Text(issue, style = MaterialTheme.typography.bodySmall)
        }
        Text("Espacio libre en el móvil: ${sizeText(store.root.usableSpace)}", style = MaterialTheme.typography.bodySmall)
        Text("Solo Wi‑Fi usa redes sin límite de datos. El cambio se aplica también a las descargas pendientes, sin borrar la cola. Las pausadas manualmente seguirán pausadas. El progreso sí se sincroniza por datos. Android puede retrasar el trabajo en segundo plano.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dar prioridad a este móvil", Modifier.weight(1f))
            DisplaySwitch(state.settings.preferLocalChanges, { value ->
                store.update { it.copy(settings = it.settings.copy(preferLocalChanges = value)) }
            }, modifier = Modifier.semantics { contentDescription = "Prioridad de este móvil" })
        }
        Text(if(state.settings.preferLocalChanges)
            "Los cambios pendientes de lectura y marcadores de esta app sustituyen a los de Kavita, aunque la web haya avanzado más. Sin cambios locales, se reciben los de la web."
            else "Si cambias una lectura o marcador también en la web, revisa el conflicto antes de sustituirlo.", style = MaterialTheme.typography.bodySmall)
        Text("Se aplica a las siguientes operaciones de sincronización.", style = MaterialTheme.typography.bodySmall)
        DisplayTextButton(onClick = { settings = false; signingOut = true }) { Text("Cerrar sesión") }
    } }, confirmButton = { DisplayTextButton(onClick = { settings = false }) { Text("Listo") } })
    if(signingOut) DisplayAlertDialog(onDismissRequest = { signingOut = false }, title = { Text("Cerrar sesión") }, text = { Text("Las descargas y ${state.pending.size} avances pendientes se conservarán para esta cuenta. Inicia sesión de nuevo con el mismo usuario para acceder a ellos.") }, confirmButton = { DisplayTextButton(onClick = logout) { Text("Cerrar sesión") } }, dismissButton = { DisplayTextButton(onClick = { signingOut = false }) { Text("Cancelar") } })
}
@Composable fun EmptyState(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.AutoStories, null, Modifier.size(48.dp), tint = Green); Spacer(Modifier.height(16.dp)); Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) } }
fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1000 -> "%.0f KB".format(bytes / 1000.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}
