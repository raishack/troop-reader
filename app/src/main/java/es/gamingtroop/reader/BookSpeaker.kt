package es.gamingtroop.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** Offline TTS engine. Its owner supplies lifetime and progress handling. */
class BookSpeaker(context: Context, private val store: Store, private val saved: SavedChapter,
    private val scope: CoroutineScope, private val location: (Int,Int) -> Unit) {
    var available by mutableStateOf(false); private set
    var utterancesStarted by mutableIntStateOf(0); private set
    var speaking by mutableStateOf(false); private set
    var message by mutableStateOf(tr(R.string.tr_042)); private set
    var rate by mutableFloatStateOf(1f); private set
    var timerMinutes by mutableIntStateOf(0); private set
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if(it < 0) pause() }.build()
    private var generation = 0
    private var job: Job? = null
    private var timer: Job? = null
    private var page = 0; private var block = 0; private var chunk = 0
    val readingPage get() = page
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"TroopReader:Voice")
    private var loadedPage = -1
    private var blocks = emptyList<BookTextBlock>()
    private var chunks = emptyList<String>()
    private var closed = false
    private var initialized = false
    private lateinit var engine: TextToSpeech
    init {
        engine = TextToSpeech(context.applicationContext) { status -> handler.post {
            if(closed) return@post
            initialized = status == TextToSpeech.SUCCESS
            if(initialized) {
                val voices = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty() }
                val voice = voices.firstOrNull { it.locale.language == "es" }
                    ?: voices.firstOrNull { it.locale.language == Locale.getDefault().language } ?: voices.firstOrNull()
                if(voice != null) { engine.voice = voice; available = true; message = tr(R.string.tr_043, voice.locale.displayLanguage) }
                else message = tr(R.string.tr_044)
            } else message = tr(R.string.tr_045)
        } }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { handler.post { if(id=="$generation" && !closed) utterancesStarted++ } }
            override fun onDone(id: String?) { handler.post { if(id == "$generation" && speaking && !closed) { chunk++; speakNext(generation) } } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post {
                if(id == "$generation" && !closed) { pause(); message = tr(R.string.tr_047) }
            } }
        })
    }
    fun play(startPage: Int, startBlock: Int = 0) {
        if(!available || closed) return
        pause(); page = startPage.coerceIn(0,saved.chapter.pages-1); block = startBlock.coerceAtLeast(0); chunk = 0
        loadedPage = -1; chunks = emptyList()
        if(audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message = tr(R.string.tr_048); return }
        if(!wake.isHeld) wake.acquire(4*60*60*1000L)
        speaking = true
        setTimer(timerMinutes)
        speakNext(generation)
    }
    fun resume() {
        if(!available || closed || speaking) return
        if(audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if(!wake.isHeld) wake.acquire(4*60*60*1000L)
        speaking = true; setTimer(timerMinutes); speakNext(generation)
    }
    private fun speakNext(token: Int) {
        job = scope.launch {
            try {
                while(speaking && token == generation && !closed) {
                    if(page >= saved.chapter.pages) { pause(); message = tr(R.string.tr_049); return@launch }
                    if(store.get().chapters[saved.chapter.id]?.hasPage(page)!=true) { pause();message=tr(R.string.tr_050);return@launch }
                    if(loadedPage != page) {
                        blocks = withContext(Dispatchers.IO) { EpubText.blocks(File(store.chapterDir(saved.chapter.id),"$page.html").readText(),page) }
                        if(token != generation || closed) return@launch
                        loadedPage = page
                    }
                    if(block >= blocks.size) { page++; block = 0; chunks = emptyList(); chunk = 0; continue }
                    if(chunks.isEmpty()) chunks = EpubText.speechChunks(blocks[block].text)
                    if(chunk >= chunks.size) { block++; chunk = 0; chunks = emptyList(); continue }
                    location(page,block)
                    engine.setSpeechRate(rate)
                    if(engine.speak(chunks[chunk],TextToSpeech.QUEUE_FLUSH,null,"$token") == TextToSpeech.ERROR) {
                        pause(); message = tr(R.string.tr_051)
                    }
                    return@launch
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { pause(); message = tr(R.string.tr_052) }
        }
    }
    fun speed(value: Float) { rate = value.coerceIn(.5f,2f); if(initialized) engine.setSpeechRate(rate) }
    fun setTimer(minutes: Int) {
        timerMinutes = minutes.coerceIn(0,60); timer?.cancel()
        if(speaking && timerMinutes > 0) timer = scope.launch { delay(timerMinutes * 60000L); pause(); message = tr(R.string.tr_053) }
    }
    fun pause() {
        generation++; speaking = false; job?.cancel(); timer?.cancel()
        if(::engine.isInitialized) engine.stop()
        if(wake.isHeld) wake.release()
        audio.abandonAudioFocusRequest(focus)
    }
    fun close() { closed = true; pause(); if(::engine.isInitialized) engine.shutdown() }
}
