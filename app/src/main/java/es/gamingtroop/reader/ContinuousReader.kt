package es.gamingtroop.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.math.roundToInt

/** An explicit jump is separate from the observed page, so scrolling never reloads content. */
data class ImageJump(val page: Int, val revision: Int = 0)
internal data class ImageBounds(val width: Int, val height: Int) {
    val valid get() = width in 1..20000 && height in 1..20000
    fun displayHeight(viewportWidth: Int): Int = if(valid)
        (viewportWidth.toDouble() * height / width).coerceIn(1.0,30000.0).roundToInt() else viewportWidth
    fun decodeSize(viewportWidth: Int): Pair<Int,Int> {
        val h = displayHeight(viewportWidth)
        val scale = minOf(1.0,2048.0 / viewportWidth.coerceAtLeast(1),4096.0 / h)
        return (viewportWidth * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }
}

/** Lazy native pages: only visible/adjacent images are decoded, no whole-volume bitmap. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable fun ContinuousReader(store: Store, saved: SavedChapter, jump: ImageJump,
    onReady: () -> Unit, onPosition: (Int,Float) -> Unit, onTap: (Float) -> Unit, onComplete: () -> Unit) {
    val id = saved.chapter.id
    val total = saved.readablePages.coerceAtLeast(1)
    val initial = remember(id) { store.imagePosition(id) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = jump.page.coerceIn(0,total-1))
    val context = LocalContext.current
    val density = LocalDensity.current
    var dimensions by remember(id) { mutableStateOf<List<ImageBounds>>(emptyList()) }
    var positioned by remember(id) { mutableStateOf(false) }
    var jumping by remember(id) { mutableStateOf(false) }
    val latestPosition by rememberUpdatedState(onPosition)
    val latestTap by rememberUpdatedState(onTap)
    val latestReady by rememberUpdatedState(onReady)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(id,total) {
        val known = dimensions.take(total)
        val bounds = known + withContext(Dispatchers.IO) {
            (known.size until total).map { page ->
                ensureActive()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(File(store.chapterDir(id),"$page.img").path,options)
                ImageBounds(options.outWidth,options.outHeight)
            }
        }
        withContext(Dispatchers.Main.immediate) { dimensions = bounds }
    }
    fun visible(): Pair<Int,Float>? {
        if(!positioned || jumping) return null
        val index = list.firstVisibleItemIndex.coerceAtMost(total-1)
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val fraction = if(list.firstVisibleItemIndex >= total) .9999f else
            list.firstVisibleItemScrollOffset.toFloat() / (item?.size ?: 1).coerceAtLeast(1)
        return index to fraction.coerceIn(0f,.9999f)
    }
    fun save(position: Pair<Int,Float>) {
        store.recordImagePosition(id,position.first,position.second)
        latestPosition(position.first,position.second)
    }
    fun flush() { visible()?.let { save(it) } }
    DisposableEffect(id,lifecycle) {
        val observer = LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_PAUSE) flush() }
        lifecycle.addObserver(observer)
        onDispose { flush(); lifecycle.removeObserver(observer) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        LaunchedEffect(jump,dimensions.isNotEmpty()) {
            if(dimensions.isEmpty()) return@LaunchedEffect
            withContext(Dispatchers.Main.immediate) {
                jumping = true
                val fraction = if(!positioned && jump.revision == 0 && initial?.page == jump.page) initial.fraction else 0f
                val target=jump.page.coerceIn(0,dimensions.lastIndex)
                list.scrollToItem(target,(dimensions[target].displayHeight(width)*fraction).roundToInt())
                positioned = true; jumping = false; latestReady()
            }
        }
        LaunchedEffect(id,list) {
            snapshotFlow { visible() }.distinctUntilChanged().debounce(250).filterNotNull().collect {
                withContext(Dispatchers.Main.immediate) { if(visible() == it) save(it) }
            }
        }
        if(dimensions.isEmpty()) DisplaySpinner(Modifier.align(Alignment.Center))
        else LazyColumn(state = list,modifier = Modifier.fillMaxSize().testTag("continuous-pages")
            .semantics { stateDescription = if(positioned) tr(R.string.tr_071) else tr(R.string.tr_072) }
            .pointerInput(id) { detectTapGestures(onTap = { latestTap(it.x / size.width.coerceAtLeast(1)) }) }, flingBehavior=displayFling()) {
            items(total,key = { it }) { index ->
                val bounds = dimensions.getOrNull(index) ?: ImageBounds(0,0)
                val height = with(density) { bounds.displayHeight(width).toDp() }
                Box(Modifier.fillMaxWidth().height(height).testTag("continuous-page-$index"),contentAlignment = Alignment.Center) {
                    if(!bounds.valid) Text(tr(R.string.tr_073, index+1),Modifier.padding(24.dp))
                    else {
                        var failed by remember(index) { mutableStateOf(false) }
                        val file = File(store.chapterDir(id),"$index.img")
                        val size = bounds.decodeSize(width)
                        val request = remember(file,width) { ImageRequest.Builder(context).data(file)
                            .memoryCacheKey("${file.path}:${file.lastModified()}:${size.first}:${size.second}")
                            .size(size.first,size.second).build() }
                        DisplayImage(request,tr(R.string.tr_062, index+1),Modifier.fillMaxSize(),contentScale = ContentScale.Fit,
                            onError = { failed = true },onSuccess = { failed = false })
                        if(failed) Text(tr(R.string.tr_074),Modifier.padding(24.dp))
                    }
                }
            }
            item(key = "end") {
                Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if(saved.readablePages == saved.chapter.pages) tr(R.string.tr_075) else tr(R.string.tr_076),style = MaterialTheme.typography.titleLarge)
                    Text(if(saved.readablePages == saved.chapter.pages) tr(R.string.tr_077) else tr(R.string.tr_078, saved.readablePages, saved.chapter.pages, localizedStatus(saved.state)))
                    DisplayButton(onClick = onComplete,enabled = total == saved.chapter.pages && dimensions.all { it.valid },modifier = Modifier.testTag("continuous-finish")) { Text(tr(R.string.tr_079)) }
                }
            }
        }
    }
}
