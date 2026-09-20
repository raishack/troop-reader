package es.gamingtroop.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.app.Activity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection

private const val LOCAL = "https://reader.local/"
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable fun ReaderScreen(repo: Repository, account: Account, saved: SavedChapter, settings: ReadingSettings, onNext: (Int) -> Unit = {}, onDownloadMore: () -> Unit = {}, close: () -> Unit) {
    ReaderContent(repo,account,saved,EinkPolicy.reading(settings,LocalDisplayMode.current),onNext,onDownloadMore,close)
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable private fun ReaderContent(repo: Repository, account: Account, saved: SavedChapter, settings: ReadingSettings, onNext: (Int)->Unit, onDownloadMore: ()->Unit, close: ()->Unit) {
    val displayMode=LocalDisplayMode.current
    val displayRefresh=LocalEinkRefresh.current
    val store = remember { repo.store(account.key) }
    val id = saved.chapter.id
    val total = saved.chapter.pages
    DisposableEffect(account.key, id) { repo.reading(account.key, id, true); onDispose { repo.reading(account.key, id, false) } }
    var page by rememberSaveable(id) { mutableIntStateOf((store.progress(id)?.pageNum ?: 0).coerceIn(0, total - 1)) }
    var scrollTarget by rememberSaveable(id) { mutableStateOf(store.progress(id)?.bookScrollId.orEmpty()) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var recordedOpen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, ready) {
        if(ready && !recordedOpen) { store.opened(id); recordedOpen = true; DiscoveryJobs.next(repo.context,account.key,id) }
    }
    var animateTurn by remember(id) { mutableStateOf(false) }
    var turnFrame by remember(id) { mutableStateOf<PageTurnFrame?>(null) }
    var imageError by remember(id) { mutableStateOf(false) }
    var showSettings by rememberSaveable(id) { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var toolMode by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var toolsBusy by remember { mutableStateOf(false) }
    var showToc by rememberSaveable(id) { mutableStateOf(false) }
    var navigatorSection by rememberSaveable(id) { mutableStateOf("units") }
    fun navigate(section: String) { navigatorSection = section; showToc = true }
    var showBookmarks by rememberSaveable(id) { mutableStateOf(false) }
    var draftPage by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var draftScroll by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var draftTitle by rememberSaveable(id) { mutableStateOf("") }
    val state by store.states.collectAsState(context = kotlinx.coroutines.Dispatchers.Main.immediate)
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(id) { alive.set(true); onDispose { alive.set(false) } }
    var lastPosition by remember { mutableStateOf("") }
    var waitingPage by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var waitingAnchor by rememberSaveable(id) { mutableStateOf("") }
    val voice = repo.context.voicePlayback()
    val voiceOwnsPosition = voice.ownsPosition(account.key,id)
    var finish by rememberSaveable(id) { mutableStateOf(false) }
    val accessibility = repo.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    var controls by rememberSaveable(id) { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    var controlEpoch by remember { mutableIntStateOf(0) }
    val nativeView = LocalView.current
    val compactHeader = LocalConfiguration.current.screenHeightDp < 480
    DisposableEffect(nativeView,displayMode) {
        val activity = nativeView.context as? Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it,nativeView) }
        if(!displayMode.eink) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(displayMode,controls,controlEpoch,showSettings,showToc,showBookmarks,finish,draftPage,toolsBusy,showTools) {
        if(controls && !displayMode.eink && !accessibility.isTouchExplorationEnabled && !showSettings && !showToc && !showBookmarks && !finish && draftPage == null && !toolsBusy && !showTools) {
            delay(5000); controls = false
        }
    }
    LaunchedEffect(page,ready,controls,showSettings,showToc,showBookmarks,finish,turnFrame,waitingPage) {
        if(ready && turnFrame==null) displayRefresh?.request()
    }
    val latestPage by rememberUpdatedState(page)
    val latestReady by rememberUpdatedState(ready)
    LaunchedEffect(turnFrame) {
        if(turnFrame != null) {
            // Never leave the old page covering an error, backgrounded or slow WebView.
            delay(10000); turnFrame = null
        }
    }
    val continuous = !saved.epub && settings.imageMode == "continuous"
    var imageJump by remember(id) { mutableStateOf(ImageJump(page)) }
    val step = if (!saved.epub && settings.imageMode == "double") 2 else 1
    fun recordBookPosition(path: String) {
        // Reopening a completed book must not silently mark it unread again.
        val completed = store.progress(id)?.pageNum == total && latestPage == total - 1
        if (alive.get() && !completed && !voice.ownsPosition(account.key,id)) store.record(id, latestPage, path)
    }
    fun changePage(next: Int, target: String = "", fromVoice: Boolean = false) {
        if(!fromVoice && voice.ownsPosition(account.key,id)) voice.readManually()
        if(turnFrame != null || !ready) return
        val p = next.coerceIn(0, total - 1)
        val latest = store.get().chapters[id] ?: return
        val lastNeeded = if(!saved.epub && !continuous && step == 2) minOf(p+1,total-1) else p
        if(!latest.hasPage(lastNeeded)) { waitingPage = p; waitingAnchor = target; return }
        if(continuous) {
            imageJump = ImageJump(p,imageJump.revision+1)
            page = p; store.record(id,p,null); Jobs.sync(repo.context,account.key)
            return
        }
        if (page == p) {
            if (target.isNotEmpty()) {
                web?.evaluateJavascript("window.troopRestore(${codec.encodeToString(target)})", null)
                scrollTarget = target; lastPosition = target
                if(!fromVoice || store.progress(id)?.pageNum != total || p != total-1) store.record(id, p, target)
            }
            return
        }
        if(ready) {
            animateTurn = kotlin.math.abs(p-page) == step && target.isEmpty() &&
                PageEffectPolicy.enabled(settings.pageTurnEffect, android.animation.ValueAnimator.areAnimatorsEnabled()) &&
                !accessibility.isTouchExplorationEnabled
            // Keep the painted viewport until its replacement is actually ready, even
            // with effects disabled. Destroy/recreate otherwise exposes a black frame.
            // The persistent image DOM is replaced atomically after decode. It needs
            // no screenshot, even with zoom: keep the native scale unchanged.
            // Only EPUB renderer replacement and Hoja need an outgoing frame.
            if(saved.epub || animateTurn) turnFrame = web?.pageTurnFrame(PageEffectPolicy.toLeft(p > page, settings.rtl && !saved.epub), if(saved.epub) settings.theme else "light")
        }
        ready = false; controls = accessibility.isTouchExplorationEnabled
        page = p; scrollTarget = target; lastPosition = target
        if(saved.epub) {
            if(target=="troop:end") return
            if(!fromVoice || store.progress(id)?.pageNum != total || p != total-1) store.record(id, p, target.ifBlank { null })
            Jobs.sync(repo.context, account.key)
        } // Images commit progress only after a successful painted swap.
    }
    fun complete() {
        if(turnFrame != null) return
        if(store.get().chapters[id]?.readablePages != total) { waitingPage = total-1; return }
        if(voice.matches(account.key,id)) voice.readManually()
        store.record(id, total, null)
        store.readingCompleted(id)
        Jobs.sync(repo.context,account.key)
        val next = saved.series.successor(store.get(),id)
        if(settings.autoAdvance && next.available) onNext(next.chapter!!.id)
        else { finish = true; controls = true }
    }
    fun nextPage() { if(ready && turnFrame == null) {
        if(displayMode.eink && saved.epub && (web as? ReaderWebView)?.pageViewport(1)==true) return
        if(page + step < total) changePage(page + step) else complete()
    } }
    fun previousPage() { if(ready && turnFrame==null) {
        if(displayMode.eink && saved.epub && (web as? ReaderWebView)?.pageViewport(-1)==true) return
        if(page>0) changePage(page-step,if(displayMode.eink && saved.epub) "troop:end" else "")
    } }
    fun bookmarkHere() {
        val observed = page; val view = web
        fun draft(path: String?) {
            if (!alive.get() || latestPage != observed) return
            draftPage = observed; draftScroll = path; draftTitle = tr(R.string.tr_062, observed + 1)
        }
        if (saved.epub && ready && view != null) view.evaluateJavascript("window.troopPosition()") { result ->
            if (web === view && latestReady) draft(runCatching { codec.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull())
        } else if(!saved.epub) draft(null)
    }
    fun leave(after: () -> Unit = close) {
        val view = web; val observedPage = page
        if (saved.epub && ready && view != null) view.evaluateJavascript("window.troopPosition()") { result ->
            val xpath = runCatching { codec.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull()
            if(alive.get() && web === view && latestPage == observedPage) {
                if(!xpath.isNullOrBlank()) recordBookPosition(xpath)
                after()
            }
        } else after()
    }
    val keyAction by rememberUpdatedState<(android.view.KeyEvent) -> Boolean> { event ->
        if(!settings.volumeKeys || controls && (showSettings || showToc || showBookmarks) || toolsBusy || showTools || finish || draftPage != null || waitingPage != null || voice.isSpeaking(account.key,id)) false
        else if(event.keyCode !in listOf(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_UP)) false
        else { if(event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) {
            if(event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) nextPage() else previousPage()
        }; true }
    }
    DisposableEffect(nativeView,displayMode) {
        val activity = nativeView.context as? MainActivity
        activity?.readerKeyHandler = { keyAction(it) }
        onDispose { activity?.readerKeyHandler = null }
    }
    DisposableEffect(nativeView,settings.orientation) {
        val activity = nativeView.context as? Activity
        val previous = activity?.requestedOrientation
        val requested = when(settings.orientation) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> null // Automatic must not undo rotation/recreation already requested by Android.
        }
        if(requested != null) activity?.requestedOrientation = requested
        onDispose { if(requested != null && previous != null && !activity.isChangingConfigurations) activity.requestedOrientation = previous }
    }
    BackHandler { if(waitingPage != null) waitingPage = null else leave() }

    LaunchedEffect(web, page, ready, voiceOwnsPosition,displayMode) {
        if(!ready || !saved.epub || voiceOwnsPosition || displayMode.eink) return@LaunchedEffect
        val observedPage = page; val observedView = web
        while(true) {
            delay(1500)
            observedView?.evaluateJavascript("window.troopPosition()") { result ->
                if (alive.get() && web === observedView && latestPage == observedPage && latestReady) {
                    val path = runCatching { codec.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull()
                    if(!path.isNullOrBlank() && path != lastPosition) { lastPosition = path; scrollTarget = path; recordBookPosition(path) }
                }
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && saved.epub && latestReady) {
                val observed = latestPage; val observedView = web
                observedView?.evaluateJavascript("window.troopPosition()") { result ->
                    val path = runCatching { codec.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull()
                    if (alive.get() && web === observedView && latestPage == observed && latestReady && !path.isNullOrBlank()) { scrollTarget = path; recordBookPosition(path) }
                }
            }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val pageFile = File(store.chapterDir(id), if(saved.epub) "$page.html" else "$page.img")
    if (!pageFile.isFile || pageFile.length() == 0L) {
        Scaffold { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text(if(saved.ready) tr(R.string.tr_430) else tr(R.string.tr_431), style = MaterialTheme.typography.headlineSmall)
            Text(if(saved.ready) tr(R.string.tr_432) else tr(R.string.tr_433, saved.readablePages, total, localizedStatus(saved.state)))
            if(!saved.ready) DisplayTextButton(onClick = { if(saved.readablePages>0) { page = minOf(page,saved.readablePages-1);scrollTarget = "" } }) { Text(tr(R.string.tr_434)) }
            DisplayButton(onClick = close) { Text(tr(R.string.tr_435)) }
        } }
        return
    }
    var sliderPage by remember(page) { mutableFloatStateOf(page.toFloat()) }
    Box(Modifier.fillMaxSize().testTag("reader-surface")) {
        // Content never resizes when controls appear: avoids jumping to another EPUB paragraph.
        val overlay: @Composable () -> Unit = { TopAppBar(title = { Column(Modifier.clickable { navigate("units") }.testTag("reader-current-unit")) { if(!compactHeader) Text(saved.series.name, maxLines = 1); Row(verticalAlignment = Alignment.CenterVertically) { Text(saved.chapter.labelFor(saved.series,state.libraries),style = if(compactHeader) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,maxLines = 1,modifier = Modifier.weight(1f,false)); Icon(Icons.Outlined.ArrowDropDown,null) } } }, navigationIcon = { DisplayIconButton(onClick = { leave() }) { Icon(Icons.Outlined.ArrowBack, tr(R.string.tr_436)) } }, actions = {
        if(saved.epub) Box {
            DisplayIconButton(onClick={ showTools=true }) { Icon(Icons.Outlined.MoreVert,tr(R.string.tr_162)) }
            DisplayDropdownMenu(expanded=showTools,onDismissRequest={ showTools=false }) {
                listOf("search" to tr(R.string.tr_437), "add" to tr(R.string.tr_403), "notes" to tr(R.string.tr_138), "voice" to tr(R.string.tr_145), "dictionary" to tr(R.string.tr_126)).forEach { (mode,label) ->
                    DropdownMenuItem(text={ Text(label) },onClick={ showTools=false;toolMode=mode })
                }
            }
        }
        DisplayIconButton(onClick = { showBookmarks = true }) { Icon(Icons.Outlined.Bookmarks, tr(R.string.tr_438)) }
        DisplayIconButton(onClick = { navigate(if(saved.epub) "pages" else "units") }) { Icon(Icons.Outlined.FormatListBulleted, if(saved.epub) "Índice" else tr(R.string.tr_439)) }
        DisplayIconButton(onClick = {
            val view = web; val observed = page
            if (saved.epub && ready && view != null) view.evaluateJavascript("window.troopPosition()") { result ->
                val path = runCatching { codec.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull()
                if (alive.get() && web === view && latestPage == observed && latestReady) {
                    if (!path.isNullOrBlank()) { scrollTarget = path; lastPosition = path; recordBookPosition(path) }
                    showSettings = true
                }
            } else showSettings = true
        }) { Icon(Icons.Outlined.Tune, tr(R.string.tr_440)) }
    }) }
        val footer: @Composable () -> Unit = { Surface { Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp)) {
        if(!saved.ready) Text(tr(R.string.tr_441, saved.readablePages, total),style = MaterialTheme.typography.labelSmall)
        Slider(value = sliderPage, onValueChange = { sliderPage = it }, onValueChangeFinished = { changePage(sliderPage.toInt()); controlEpoch++ }, valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(), enabled = total > 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DisplayTextButton(onClick = { previousPage() }, enabled = (page > 0 || displayMode.eink && saved.epub) && ready && turnFrame == null) { Text(if(settings.rtl && !saved.epub) tr(R.string.tr_442) else tr(R.string.tr_443)) }
            DisplayTextButton(onClick = { navigate("pages") },modifier = Modifier.testTag("reader-page-selector")) { Text(if(step == 2 && page + 1 < total) "${page + 1}–${page + 2} / $total" else "${page + 1} / $total", color = Green) }
            if(page + step < total) DisplayTextButton(enabled = ready, onClick = { nextPage() }) { Text(if(settings.rtl && !saved.epub) tr(R.string.tr_446) else tr(R.string.tr_447)) }
            else DisplayTextButton(enabled = ready,onClick = { complete() }) { Text(tr(R.string.tr_448)) }
        }
    } } }
        val keyValue = "${id}-${if(saved.epub) page else "images"}-${settings.copy(wifiOnly = false).hashCode()}"
        if(continuous) key(id) {
            ContinuousReader(store,saved,imageJump,
                onReady = { ready = true },
                onPosition = { p,_ ->
                    if(alive.get()) page = p
                },
                onTap = { fraction ->
                    val action = PageTurnPolicy.tap(settings.pageTurnMode,fraction,1,settings.rtl)
                    if(action > 0) nextPage() else if(action < 0 && page > 0) changePage(page-1)
                    else { controls = !controls; controlEpoch++ }
                },onComplete = { complete() })
        } else key(keyValue) {
            AndroidView(modifier = Modifier.fillMaxSize().testTag("reader-content").semantics {
                stateDescription = if(ready && turnFrame == null) tr(R.string.tr_449) else tr(R.string.tr_450)
            }, factory = { context ->
                ReaderWebView(context).apply {
                    web = this; ready = false
                    eink=displayMode.eink
                    onSettled={
                        displayRefresh?.request()
                        if(saved.epub && web===this && alive.get() && latestReady) {
                            val observed=latestPage
                            evaluateJavascript("window.troopPosition()") { result ->
                                if(web===this && alive.get() && latestReady && latestPage==observed) {
                                    val path=runCatching { codec.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull()
                                    if(!path.isNullOrBlank() && path!=lastPosition) { lastPosition=path;scrollTarget=path;recordBookPosition(path) }
                                }
                            }
                        }
                    }
                    overScrollMode=if(displayMode.eink) android.view.View.OVER_SCROLL_NEVER else android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    isVerticalScrollBarEnabled=!displayMode.eink
                    isHorizontalScrollBarEnabled=!displayMode.eink
                    if(saved.epub) { onManualTouch = { if(voice.ownsPosition(account.key,id)) voice.readManually() }; onAnnotate = { if(web===this) toolMode="add" }; onDictionary = { if(web===this) toolMode="dictionary" } }
                    if(!saved.epub) imagePage = page
                    contentDescription = if(PageTurnPolicy.usesEdgeTaps(settings.pageTurnMode))
                        tr(R.string.tr_451)
                        else tr(R.string.tr_452)
                    readingGestures(settings.rtl && !saved.epub, settings.pageTurnMode,
                        toggle = { if(web === this) { controls = !controls; controlEpoch++ } },
                        next = { if(web===this) nextPage() }, previous = { if(web===this) previousPage() })
                    setBackgroundColor(if(settings.theme == "dark") AndroidColor.rgb(20,24,28) else if(settings.theme == "sepia") AndroidColor.rgb(244,234,210) else AndroidColor.WHITE)
                    this.settings.apply {
                        javaScriptEnabled = true // Only our bundled reader.js is allowed by CSP; no JS bridge is exposed.
                        allowFileAccess = false; allowContentAccess = false; domStorageEnabled = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportZoom(!saved.epub); builtInZoomControls = !saved.epub; displayZoomControls = false
                        loadsImagesAutomatically = true; mediaPlaybackRequiresUserGesture = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    val content = if(saved.epub) File(store.chapterDir(id), "$page.html").readText() else {
                        val second = if(settings.imageMode == "double" && page + 1 < saved.readablePages) "<img src=\"${page+1}.img\" alt=\"${tr(R.string.page_word)} ${page+2}\">" else ""
                        "<div class=\"images ${settings.imageMode}\" data-page=\"$page\" data-total=\"${saved.readablePages}\" data-page-label=\"${tr(R.string.page_word)}\" data-step=\"$step\" dir=\"${if(settings.rtl) "rtl" else "ltr"}\"><img src=\"$page.img\" alt=\"${tr(R.string.page_word)} ${page+1}\">$second</div>"
                    }
                    val document = ReaderHtml.document(content, settings, saved.epub)
                    webViewClient = object: WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val uri = request.url
                            if(uri.scheme != "https" || uri.host != "reader.local") return denied()
                            if(uri.path == "/index.html") return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))
                            if(uri.path == "/reader.js") return WebResourceResponse("text/javascript", "UTF-8", context.assets.open("reader.js"))
                            val relative = uri.path.orEmpty().removePrefix("/")
                            val root = store.chapterDir(id).canonicalFile
                            val file = File(root, relative).canonicalFile
                            if(!file.path.startsWith(root.path + File.separator) || !file.isFile || relative.endsWith(".part")) return denied()
                            val mime = if(file.extension == "img") file.inputStream().buffered().use { URLConnection.guessContentTypeFromStream(it) } ?: "image/jpeg" else when(file.extension) {
                                "css" -> "text/css"; "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                            }
                            return WebResourceResponse(mime, null, file.inputStream())
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if(uri.scheme == "https" && uri.host == "reader.local" && uri.path == "/goto") {
                                uri.getQueryParameter("page")?.toIntOrNull()?.takeIf { it in 0 until total }?.let { changePage(it, uri.fragment?.let { f -> "#${f.removePrefix("#")}" }.orEmpty()) }
                            }
                            return true // No remote navigation, external scripts, tracking or credential forwarding.
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            if(url != LOCAL + "index.html" || web !== view || !alive.get()) return
                            view.post { view.evaluateJavascript("window.troopViewport && window.troopViewport()", null) }
                            fun painted() {
                                view.postVisualStateCallback(0, object: WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        if(web === view && alive.get()) {
                                            ready=true
                                            if(displayMode.eink) (view as? ReaderWebView)?.onSettled?.invoke()
                                        }
                                    }
                                })
                            }
                            if(saved.epub) view.evaluateJavascript("window.troopRestore(${codec.encodeToString(scrollTarget)})") { painted() }
                            else painted()
                        }
                    }
                    loadUrl(LOCAL + "index.html")
                }
            }, update = { view ->
                if(!saved.epub) view.evaluateJavascript("window.troopAvailable && window.troopAvailable(${saved.readablePages})",null)
                if(!saved.epub && view.imagePage != page) {
                    // zoomBy animates natively, independently of visual-state callbacks.
                    // Do not reset it on navigation: decode/swap at the user's scale.
                    val requested = page
                    view.showImages(requested, onReady = {
                        if(web === view && alive.get() && page == requested) {
                            ready = true
                            store.record(id,requested,null); Jobs.sync(repo.context,account.key)
                        }
                    }, onFailure = {
                        if(web === view && alive.get() && page == requested) {
                            page = view.imagePage; ready = true; turnFrame = null; imageError = true
                        }
                    })
                }
            }, onRelease = { view -> if(web == view) web = null; view.stopLoading(); view.destroy() })
        }
        turnFrame?.let { frame ->
            key(frame) {
                AndroidView(modifier = Modifier.fillMaxSize().testTag(if(animateTurn) "page-turn-effect" else "page-turn-hold"),
                    factory = { context -> PageCurlView(context).apply { this.frame = frame } },
                    update = { view ->
                        view.onFinished = { if(turnFrame === frame) turnFrame = null }
                        if(ready) { if(animateTurn) view.start() else view.revealWhenDrawn() }
                    }, onRelease = { it.release() })
            }
        }
        if(controls) {
            Box(Modifier.align(Alignment.TopCenter)) { overlay() }
            Box(Modifier.align(Alignment.BottomCenter)) { footer() }
        }
    }
    ReaderStatistics(store,id,page,ready && !showSettings && !showToc && !showBookmarks && !toolsBusy && !showTools && !finish && draftPage==null)
    if(saved.epub) EpubToolsUi(repo,store,saved,state,web,page,ready,toolMode,{ toolMode=null },{ toolsBusy=it }) { target,anchor,fromVoice -> changePage(target,anchor,fromVoice) }
    if(showSettings) DisplayAlertDialog(onDismissRequest = { showSettings = false }, title = { Text(tr(R.string.tr_440)) }, text = { Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
        DisplaySettings()
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(tr(R.string.tr_454),Modifier.weight(1f))
            DisplaySwitch(saved.series.id in state.profiles,{ store.useSeriesProfile(saved.series.id,it) },modifier=Modifier.testTag("series-reader-profile"))
        }
        Text(if(saved.series.id in state.profiles) tr(R.string.tr_455, saved.series.name) else tr(R.string.tr_456),style=MaterialTheme.typography.bodySmall)
        DisplayTextButton(onClick = { leave(onDownloadMore) }) { Text(tr(R.string.tr_420, saved.series.unitsName(state.libraries))) }
        if(!saved.epub) {
            Text(tr(R.string.tr_457),style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("fit" to tr(R.string.tr_458), "width" to tr(R.string.tr_459), "double" to tr(R.string.tr_460), "continuous" to tr(R.string.tr_461)).forEach { (value,label) -> DisplayChip(settings.imageMode == value, { if(value == "continuous" && !continuous) imageJump = ImageJump(page); store.readerSettings(saved.series.id) { it.copy(imageMode = value) } }, { Text(label) }, enabled=!(displayMode.eink && value=="continuous")) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.tr_462),Modifier.weight(1f))
            DisplaySwitch(settings.autoAdvance, { value -> store.readerSettings(saved.series.id) { it.copy(autoAdvance = value) } })
        }
        val unitName = if(saved.series.isBook(state.libraries)) tr(R.string.tr_067) else tr(R.string.tr_064)
        Text(tr(R.string.tr_463, unitName.lowercase()),style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-1 to tr(R.string.tr_464, unitName),1 to tr(R.string.tr_465, unitName)).forEach { (direction,label) ->
                val target = saved.series.adjacent(state,id,direction)
                DisplayOutlinedButton(enabled = target.available,onClick = { leave { onNext(target.chapter!!.id) } }) { Text(label) }
            }
        }
        Text(tr(R.string.tr_466),style = MaterialTheme.typography.bodySmall)
        Text(tr(R.string.tr_467), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("swipe" to tr(R.string.tr_468), "edges" to tr(R.string.tr_469)).forEach { (value,label) ->
                DisplayChip(if(value == "edges") PageTurnPolicy.usesEdgeTaps(settings.pageTurnMode) else !PageTurnPolicy.usesEdgeTaps(settings.pageTurnMode),
                    { store.readerSettings(saved.series.id) { it.copy(pageTurnMode = value) } }, { Text(label) })
            }
        }
        Text(if(continuous) tr(R.string.tr_470) else if(PageTurnPolicy.usesEdgeTaps(settings.pageTurnMode))
            tr(R.string.tr_471)
            else tr(R.string.tr_472),
            style = MaterialTheme.typography.bodySmall)
        if(!continuous) Text(tr(R.string.tr_473),style = MaterialTheme.typography.bodySmall)
        Text(tr(R.string.tr_474), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("none" to tr(R.string.tr_475), "curl" to tr(R.string.tr_476)).forEach { (value,label) ->
                DisplayChip(if(value == "curl") settings.pageTurnEffect == "curl" else settings.pageTurnEffect != "curl",
                    { store.readerSettings(saved.series.id) { it.copy(pageTurnEffect = value) } }, { Text(label) }, enabled = !continuous && !displayMode.eink)
            }
        }
        Text(if(continuous) tr(R.string.tr_477) else tr(R.string.tr_478),style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(tr(R.string.tr_479),Modifier.weight(1f))
            DisplaySwitch(settings.volumeKeys,{ value -> store.readerSettings(saved.series.id) { it.copy(volumeKeys=value) } })
        }
        Text(tr(R.string.tr_480),style=MaterialTheme.typography.bodySmall)
        Text(tr(R.string.tr_481))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("auto" to tr(R.string.tr_482),"portrait" to tr(R.string.tr_483),"landscape" to tr(R.string.tr_484)).forEach { (value,label) ->
                DisplayChip(settings.orientation==value,{ store.readerSettings(saved.series.id) { it.copy(orientation=value) } },{ Text(label) })
            }
        }
        Text(tr(R.string.tr_485))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("dark" to tr(R.string.tr_486), "sepia" to tr(R.string.tr_487), "light" to tr(R.string.tr_488)).forEach { (value,label) -> DisplayChip(settings.theme == value, { store.readerSettings(saved.series.id) { it.copy(theme = value) } }, { Text(label) },enabled=!displayMode.eink) } }
        if(saved.epub) {
            Text(tr(R.string.tr_489, settings.fontSize))
            Slider(settings.fontSize.toFloat(), { value -> store.readerSettings(saved.series.id) { it.copy(fontSize = value.toInt()) } }, valueRange = 12f..32f)
            Text(tr(R.string.tr_490).format(AppLanguage.locale, settings.lineHeight))
            Slider(settings.lineHeight, { value -> store.readerSettings(saved.series.id) { it.copy(lineHeight = value) } }, valueRange = 1.2f..2.2f)
            Text(tr(R.string.tr_491))
            Slider(settings.margin.toFloat(), { value -> store.readerSettings(saved.series.id) { it.copy(margin = value.toInt()) } }, valueRange = 8f..48f)
            Row { Text(tr(R.string.tr_492), Modifier.weight(1f)); DisplaySwitch(settings.serif, { value -> store.readerSettings(saved.series.id) { it.copy(serif = value) } }) }
        } else {
            Row { Text(tr(R.string.tr_493), Modifier.weight(1f)); DisplaySwitch(settings.rtl, { value -> store.readerSettings(saved.series.id) { it.copy(rtl = value) } }) }
            Text(if(continuous) tr(R.string.tr_494) else tr(R.string.tr_495), style = MaterialTheme.typography.bodySmall)
        }
    } }, confirmButton = { DisplayTextButton(onClick = { showSettings = false }) { Text(tr(R.string.tr_260)) } })
    waitingPage?.let { target ->
        val needed = if(!saved.epub && !continuous && step==2) minOf(target+1,total-1) else target
        DisplayAlertDialog(onDismissRequest={ waitingPage=null },title={ Text(tr(R.string.tr_496)) },
            text={ Text(tr(R.string.tr_497, saved.readablePages, total, localizedStatus(saved.state))) },
            confirmButton={ DisplayTextButton(enabled=saved.hasPage(needed),onClick={ waitingPage=null;changePage(target,waitingAnchor) }) { Text(tr(R.string.tr_498)) } },
            dismissButton={ DisplayTextButton(onClick={ waitingPage=null }) { Text(tr(R.string.tr_499)) } })
    }
    if(imageError) DisplayAlertDialog(onDismissRequest = { imageError = false },title = { Text(tr(R.string.tr_500)) },
        text = { Text(tr(R.string.tr_501)) },
        confirmButton = { DisplayTextButton(onClick = { imageError = false }) { Text(tr(R.string.tr_163)) } })
    if(showBookmarks) DisplayBottomSheet(onDismissRequest = { showBookmarks = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val bookmarks = state.bookmarks.filter { it.chapterId == id && !it.deleted }.sortedWith(compareBy({ it.page }, { it.createdAt }))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("bookmark-list"),
            contentPadding = PaddingValues(bottom = 24.dp), flingBehavior=displayFling()) {
            item { Text(tr(R.string.tr_438), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp)) }
            item { Text(if(state.settings.preferLocalChanges) tr(R.string.tr_502)
                else tr(R.string.tr_503), modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
            item { DisplayButton(onClick = { bookmarkHere() }, enabled = ready, modifier = Modifier.padding(20.dp, 8.dp)) { Text(tr(R.string.tr_504)) } }
            if(bookmarks.isEmpty()) item { Text(tr(R.string.tr_505), Modifier.padding(20.dp)) }
            items(bookmarks, key = { it.id }) { b ->
                val compatible = b.edition.sameEdition(saved.chapter) && b.page in 0 until total
                ListItem(headlineContent = { Text(b.title) }, supportingContent = {
                    Text(tr(R.string.tr_506, b.page + 1) + if(!compatible) tr(R.string.tr_507) else if(b.conflict && !state.settings.preferLocalChanges) tr(R.string.tr_508) else if(b.dirty) tr(R.string.tr_509) else tr(R.string.tr_510))
                }, leadingContent = { DisplayTextButton(enabled = compatible, onClick = {
                    showBookmarks = false; changePage(b.page, b.scroll.orEmpty())
                }) { Text(tr(R.string.tr_141)) } }, trailingContent = { DisplayIconButton(onClick = { store.removeBookmark(b.id); Jobs.sync(repo.context, account.key) }) {
                    Icon(Icons.Outlined.DeleteOutline, tr(R.string.tr_511, b.title))
                } })
            }
        }
    }
    if(draftPage != null) DisplayAlertDialog(onDismissRequest = { draftPage = null }, title = { Text(tr(R.string.tr_512)) },
        text = { OutlinedTextField(draftTitle, { draftTitle = it.take(100) }, label = { Text(tr(R.string.tr_513)) }, singleLine = true) },
        confirmButton = { DisplayTextButton(onClick = {
            store.addBookmark(id, draftPage!!, draftScroll, draftTitle); draftPage = null
            Jobs.sync(repo.context, account.key)
        }) { Text(tr(R.string.tr_160)) } }, dismissButton = { DisplayTextButton(onClick = { draftPage = null }) { Text(tr(R.string.tr_161)) } })
    if(showToc) ReaderNavigator(repo,account,saved,state,page,scrollTarget,navigatorSection,
        onDismiss = { showToc = false; controlEpoch++ },
        onChapter = { target ->
            if(target == id) { showToc = false; controlEpoch++ }
            else if(state.chapters[target]?.let { it.readable && it.series.id == saved.series.id } == true) {
                leave { showToc = false; onNext(target) }
            }
        }, onPage = { target,anchor -> showToc = false; changePage(target,anchor); controlEpoch++ },
        onDownloadMore = { leave(onDownloadMore) })
    if(finish) {
        val next = saved.series.successor(state,id)
        DisplayAlertDialog(onDismissRequest = { finish = false }, title = { Text(tr(R.string.tr_514)) },
            text = { Text(tr(R.string.tr_515) + when {
                next.available -> tr(R.string.tr_516, next.chapter!!.labelFor(saved.series,state.libraries))
                next.chapter != null -> tr(R.string.tr_517, next.chapter.labelFor(saved.series,state.libraries))
                else -> tr(R.string.tr_518)
            }) },
            confirmButton = { DisplayTextButton(onClick = { finish = false; if(next.available) onNext(next.chapter!!.id) else close() }) { Text(if(next.available) tr(R.string.tr_519) else tr(R.string.tr_520)) } },
            dismissButton = { DisplayTextButton(onClick = { finish = false }) { Text(tr(R.string.tr_521)) } })
    }
}
private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
object ReaderHtml {
    fun document(content: String, settings: ReadingSettings, epub: Boolean): String {
        val bg = when(settings.theme) { "light", "eink-mono", "eink-color" -> "#fff"; "sepia" -> "#f4ead2"; else -> "#14181c" }
        val ink=settings.theme.startsWith("eink-")
        val fg = if(ink) "#000" else if(settings.theme == "dark") "#e8e8e0" else "#292722"
        val inkCss=if(!ink) "" else """<style>html,body{background:#fff!important;color:#000!important;scroll-behavior:auto!important}*{animation:none!important;transition:none!important;text-shadow:none!important;box-shadow:none!important}.book-content *{color:#000!important;opacity:1!important}.book-content a{color:#000!important;text-decoration:underline!important;font-weight:bold}.book-content mark[data-troop-note]{color:#000!important;background:${if(settings.theme=="eink-mono") "#fff" else "#ffeb83"}!important;border-bottom:2px solid #000;text-decoration:underline}img,svg{${if(settings.theme=="eink-mono") "filter:grayscale(1)!important;" else ""}}</style>"""
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://reader.local data:; style-src https://reader.local 'unsafe-inline'; font-src https://reader.local data:; script-src https://reader.local; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'">
        <style>html,body{margin:0;background:$bg;color:$fg}body{overflow-wrap:break-word}.book-content{padding:${settings.margin}px}.book-content *{color:inherit!important;background-color:transparent!important;font-size:${settings.fontSize}px!important;line-height:${settings.lineHeight}!important;font-family:${if(settings.serif) "Georgia,serif" else "sans-serif"}!important}.book-content h1,.book-content h2{font-size:1.5em!important}.book-content img,.book-content svg{max-width:100%;height:auto}.images{display:flex;justify-content:center;min-height:var(--reader-height,100vh);align-items:center}.images img{max-width:100%;height:auto}.images.fit img{max-height:var(--reader-height,100vh);object-fit:contain}.images.double img{max-width:50%;max-height:var(--reader-height,100vh);object-fit:contain}.images.width{display:block}.images.width img{width:100%}a{color:#57b680!important}.book-content mark[data-troop-note]{background-color:#eed56f!important;color:#28200d!important}</style></head>
        <body>${if(epub) "<div class=\"book-content\">$content</div>" else content}$inkCss<script src="/reader.js"></script></body></html>""".trimIndent()
    }
}
