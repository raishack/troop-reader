package es.gamingtroop.reader

import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun EpubToolsUi(repo: Repository, store: Store, saved: SavedChapter, state: LocalState, web: WebView?, page: Int,
    ready: Boolean, mode: String?, close: () -> Unit, onBusy: (Boolean) -> Unit, onJump: (Int,String,Boolean) -> Unit) {
    val scope=rememberCoroutineScope { Dispatchers.Main.immediate }
    val voice=repo.context.voicePlayback()
    val speaker=remember(saved.chapter.id) { voice.prepare(repo.active()!!.key,saved) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> foreground=event==Lifecycle.Event.ON_RESUME || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer);onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(voice.revision,foreground,ready) {
        if(foreground && ready && voice.ownsPosition(repo.active()!!.key,saved.chapter.id))
            onJump(voice.page,"@text:${voice.block}:0:0",true)
    }
    val notes=state.notes.filter { it.chapterId==saved.chapter.id }
    LaunchedEffect(web,ready,page,notes) {
        if(ready) web?.evaluateJavascript("window.troopHighlights && window.troopHighlights(${codec.encodeToString(notes.filter { it.page==page && it.edition.sameEdition(saved.chapter) })})",null)
    }
    var query by rememberSaveable(saved.chapter.id) { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<BookSearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<BookNote?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    var hasStartedVoice by remember { mutableStateOf(false) }
    LaunchedEffect(mode,draft,error) { onBusy(mode!=null || draft!=null || error!=null) }
    LaunchedEffect(mode,query) {
        if(mode!="search") return@LaunchedEffect
        searching=true;error=null
        try { delay(250);hits=withContext(Dispatchers.IO) { EpubText.search(store,saved,query) } }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { error=tr(R.string.tr_123) }
        finally { searching=false }
    }
    LaunchedEffect(mode,web) {
        if(mode!="add") return@LaunchedEffect
        val selectedPage=page
        if(!ready || web==null) { error=tr(R.string.tr_124);close();return@LaunchedEffect }
        web.evaluateJavascript("window.troopSelection && window.troopSelection()") { result ->
            val selection=runCatching { codec.parseToJsonElement(result).jsonObject }.getOrNull()
            val quote=selection?.get("quote")?.jsonPrimitive?.contentOrNull
            if(quote.isNullOrBlank() || quote.length>10000) error=selection?.get("error")?.jsonPrimitive?.contentOrNull
                ?: tr(R.string.tr_125)
            else {
                draft=BookNote(java.util.UUID.randomUUID().toString(),saved.chapter.id,saved.chapter,selectedPage,
                    selection.getValue("block").jsonPrimitive.int,selection.getValue("start").jsonPrimitive.int,
                    selection.getValue("end").jsonPrimitive.int,quote)
                comment=""
            }
            close()
        }
    }
    var dictionaryText by remember { mutableStateOf("") }
    var dictionaryTargets by remember { mutableStateOf<List<DictionaryTarget>>(emptyList()) }
    LaunchedEffect(mode) {
        if(mode=="dictionary") {
            dictionaryTargets=DictionaryLookup.targets(repo.context)
            web?.evaluateJavascript("window.getSelection().toString()") { raw ->
                dictionaryText=runCatching { codec.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault("").take(200)
            }
        }
    }
    if(mode=="dictionary") DisplayAlertDialog(onDismissRequest=close,title={ Text(tr(R.string.tr_126)) },text={ Column {
        OutlinedTextField(dictionaryText,{ dictionaryText=it.take(200) },label={ Text(tr(R.string.tr_127)) },singleLine=true)
        Text(tr(R.string.tr_128))
        if(dictionaryTargets.isEmpty()) Text(tr(R.string.tr_129))
        LazyColumn(Modifier.heightIn(max=260.dp), flingBehavior=displayFling()) { items(dictionaryTargets) { target -> DisplayTextButton(enabled=dictionaryText.isNotBlank(),onClick={
            voice.pause()
            try { repo.context.startActivity(DictionaryLookup.intent(target,dictionaryText));close() } catch(_: Exception) { error=tr(R.string.tr_130) }
        }) { Text(target.label) } } }
    } },confirmButton={ DisplayTextButton(onClick=close) { Text(tr(R.string.tr_115)) } })
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if(uri!=null) scope.launch {
        try { withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri,"wt")!!.use {
            it.write(notesMarkdown(store.get().let { s -> s.copy(notes=s.notes.filter { it.chapterId==saved.chapter.id }) }).toByteArray())
        } } }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { error=tr(R.string.tr_131) }
    } }
    if(mode=="search") DisplayAlertDialog(onDismissRequest=close,title={ Text(tr(R.string.tr_132)) },text={ Column {
        OutlinedTextField(query,{ query=it.take(200) },label={ Text(tr(R.string.tr_133)) },singleLine=true,modifier=Modifier.fillMaxWidth().testTag("book-search-query"))
        if(searching) DisplayProgress(Modifier.fillMaxWidth())
        Text(if(query.trim().length<2) tr(R.string.tr_134) else tr(R.string.tr_135, hits.size)+if(hits.size==200) tr(R.string.tr_136) else "",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max=340.dp).testTag("book-search-results"), flingBehavior=displayFling()) {
            items(hits,key={ "${it.page}:${it.block}:${it.start}" }) { hit -> DisplayTextButton(onClick={ voice.pause();close();onJump(hit.page,hit.anchor,false) }) {
                Column(Modifier.fillMaxWidth()) { Text(tr(R.string.tr_137, hit.page+1),color=Green);Text(hit.excerpt) }
            } }
        }
    } },confirmButton={ DisplayTextButton(onClick=close) { Text(tr(R.string.tr_115)) } })
    if(mode=="notes") DisplayAlertDialog(onDismissRequest=close,title={ Text(tr(R.string.tr_138)) },text={ Column {
        Text(tr(R.string.tr_139),style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max=360.dp).testTag("book-notes"), flingBehavior=displayFling()) {
            items(notes.sortedBy { it.page },key={ it.id }) { note -> Column(Modifier.padding(vertical=10.dp)) {
                Text(note.quote);if(note.comment.isNotBlank()) Text(note.comment,color=Green)
                val compatible=note.edition.sameEdition(saved.chapter)
                Text(if(compatible) tr(R.string.tr_137, note.page+1) else tr(R.string.tr_140),style=MaterialTheme.typography.bodySmall)
                FlowRow {
                    DisplayTextButton(enabled=compatible,onClick={ voice.pause();close();onJump(note.page,"@text:${note.block}:${note.start}:${note.end}",false) }) { Text(tr(R.string.tr_141)) }
                    DisplayTextButton(enabled=compatible,onClick={ draft=note;comment=note.comment }) { Text(tr(R.string.tr_142)) }
                    DisplayTextButton(onClick={ store.update { it.copy(notes=it.notes.filterNot { n -> n.id==note.id }) } }) { Text(tr(R.string.tr_143)) }
                }
            } }
        }
        DisplayOutlinedButton(enabled=notes.isNotEmpty(),onClick={ export.launch("troop-reader-notas-${saved.chapter.id}.md") }) { Text(tr(R.string.tr_144)) }
    } },confirmButton={ DisplayTextButton(onClick=close) { Text(tr(R.string.tr_115)) } })
    if(mode=="voice") DisplayAlertDialog(onDismissRequest=close,title={ Text(tr(R.string.tr_145)) },text={ Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
        Text(localizedStatus(speaker.message))
        Text(tr(R.string.tr_146).format(AppLanguage.locale, speaker.rate));Slider(speaker.rate,speaker::speed,valueRange=.5f..2f)
        Text(tr(R.string.tr_147))
        FlowRow { listOf(0,15,30,60).forEach { minute -> DisplayChip(speaker.timerMinutes==minute,{ speaker.setTimer(minute) },{ Text(if(minute==0) tr(R.string.tr_148) else "$minute min") }) } }
        FlowRow {
            DisplayButton(enabled=speaker.available && ready,onClick={
                if(speaker.speaking) voice.pause()
                else if(hasStartedVoice) voice.resume()
                else {
                    web?.evaluateJavascript("window.troopVisibleBlock()") { raw ->
                        voice.play(page,raw.toIntOrNull() ?: 0);hasStartedVoice=true
                    }
                }
            }) { Text(if(speaker.speaking) tr(R.string.tr_150) else if(hasStartedVoice) tr(R.string.tr_151) else tr(R.string.tr_152)) }
            DisplayTextButton(enabled=speaker.available && ready,onClick={ voice.play(page);hasStartedVoice=true }) { Text(tr(R.string.tr_153)) }
        }
        Text(tr(R.string.tr_154),style=MaterialTheme.typography.bodySmall)
        DisplayTextButton(onClick={ try { repo.context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(_: Exception) { error=tr(R.string.tr_155) } }) { Text(tr(R.string.tr_156)) }
    } },confirmButton={ DisplayTextButton(onClick=close) { Text(tr(R.string.tr_157)) } })
    draft?.let { n -> DisplayAlertDialog(onDismissRequest={ draft=null },title={ Text(tr(R.string.tr_158)) },text={ Column(Modifier.verticalScroll(rememberScrollState(), flingBehavior=displayFling())) {
        Text(n.quote.take(1500));OutlinedTextField(comment,{ comment=it.take(4000) },label={ Text(tr(R.string.tr_159)) },modifier=Modifier.fillMaxWidth().testTag("note-comment"))
    } },confirmButton={ DisplayTextButton(onClick={ store.saveNote(n.copy(comment=comment));draft=null }) { Text(tr(R.string.tr_160)) } },dismissButton={ DisplayTextButton(onClick={ draft=null }) { Text(tr(R.string.tr_161)) } }) }
    error?.let { message -> DisplayAlertDialog(onDismissRequest={ error=null },title={ Text(tr(R.string.tr_162)) },text={ Text(message) },confirmButton={ DisplayTextButton(onClick={ error=null }) { Text(tr(R.string.tr_163)) } }) }
}

@Composable fun ReaderStatistics(store: Store, chapterId: Int, page: Int, active: Boolean) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val tracker=remember(chapterId) { ReadingTracker(store,chapterId) }
    DisposableEffect(lifecycle,active,page) {
        fun start() { if(active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tracker.start(page) }
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_RESUME -> start()
            Lifecycle.Event.ON_PAUSE -> tracker.stop()
            else -> Unit
        } }
        lifecycle.addObserver(observer);start()
        onDispose { tracker.stop();lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tracker) { while(true) { delay(10000);tracker.sample() } }
}
internal class ReadingTracker(private val store: Store, private val chapterId: Int,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var started: Long?=null;private var page=0
    fun start(p: Int) { stop();page=p;started=clock() }
    fun sample() { val last=started ?: return;val now=clock();store.readingTime(chapterId,page,now-last);started=now }
    fun stop() { sample();started=null }
}
