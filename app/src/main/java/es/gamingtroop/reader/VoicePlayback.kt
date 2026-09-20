package es.gamingtroop.reader

import android.app.*
import android.content.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Application-owned playback, never tied to an Activity or its WebView. */
class VoicePlayback(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    var speaker by mutableStateOf<BookSpeaker?>(null); private set
    var accountKey by mutableStateOf(""); private set
    var chapterId by mutableIntStateOf(0); private set
    var page by mutableIntStateOf(0); private set
    var block by mutableIntStateOf(0); private set
    var revision by mutableIntStateOf(0); private set
    private var started = false
    private var owns by mutableStateOf(false)
    fun ownsPosition(key: String, id: Int) = matches(key,id) && owns
    fun readManually() { pause(); owns=false }
    fun finishPlayback() { pause(); context.stopService(Intent(context,VoiceService::class.java)) }
    fun matches(key: String, id: Int) = accountKey == key && chapterId == id
    fun isSpeaking(key: String, id: Int) = matches(key,id) && speaker?.speaking == true
    fun prepare(key: String, saved: SavedChapter): BookSpeaker {
        if(matches(key,saved.chapter.id)) speaker?.let { return it }
        stop(); require(context.repository().active()?.key == key)
        accountKey=key;chapterId=saved.chapter.id
        val store=context.repository().store(key)
        speaker=BookSpeaker(context,store,saved,scope) { p,b ->
            if(context.repository().active()?.key != key) { stop();return@BookSpeaker }
            owns=true;page=p;block=b;revision++
            // Audio owns progress while playing. Keep the server's completed flag.
            if(!(store.progress(saved.chapter.id)?.pageNum == saved.chapter.pages && p == saved.chapter.pages-1))
                store.record(saved.chapter.id,p,"@text:$b:0:0")
        }
        return speaker!!
    }
    fun play(p: Int, b: Int=0) { startService { speaker?.play(p,b);started=true } }
    fun resume() { startService { speaker?.resume() } }
    private fun startService(action: () -> Unit) {
        if(speaker?.available != true) return
        try { context.startForegroundService(Intent(context,VoiceService::class.java));action() }
        catch(_: RuntimeException) { speaker?.pause() }
    }
    fun toggle(p: Int, b: Int=0) { if(speaker?.speaking==true) pause() else if(started) resume() else play(p,b) }
    fun pause() { speaker?.pause(); if(accountKey.isNotBlank()) Jobs.sync(context,accountKey) }
    fun stop() {
        val key=accountKey
        speaker?.close();speaker=null;accountKey="";chapterId=0;started=false;owns=false;revision=0
        context.stopService(Intent(context,VoiceService::class.java))
        if(key.isNotBlank()) Jobs.sync(context,key)
    }
}
private var playback: VoicePlayback? = null
@Synchronized fun Context.voicePlayback(): VoicePlayback = playback ?: VoicePlayback(applicationContext).also { playback=it }

class VoiceService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var owned: BookSpeaker?=null
    private lateinit var media: MediaSession
    private lateinit var manager: NotificationManager
    private val voice get()=applicationContext.voicePlayback()
    private val noisy=object: BroadcastReceiver() { override fun onReceive(c: Context?,i: Intent?) { voice.pause() } }
    override fun onCreate() {
        super.onCreate()
        manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reading-voice",tr(R.string.tr_145),NotificationManager.IMPORTANCE_LOW))
        media=MediaSession(this,"Troop Reader").apply {
            setCallback(object: MediaSession.Callback() {
                override fun onPlay() { voice.resume() }
                override fun onPause() { voice.pause() }
                override fun onStop() { voice.finishPlayback() }
            });isActive=true
        }
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(noisy,IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(noisy,IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        owned=voice.speaker
        startForeground(1901,notification())
        scope.launch {
            var last=""
            while(isActive) {
                val s=voice.speaker
                if(s==null || applicationContext.repository().active()?.key!=voice.accountKey) { stopSelf();break }
                val next="${s.speaking}:${s.readingPage}:${s.message}"
                if(next!=last) {
                    last=next
                    media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
                        .setState(if(s.speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,1f).build())
                    manager.notify(1901,notification())
                }
                delay(500)
            }
        }
    }
    private fun action(name: String)=PendingIntent.getService(this,if(name=="stop") 2 else 1,
        Intent(this,VoiceService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        val playing=voice.speaker?.speaking==true
        val open=PendingIntent.getActivity(this,1901,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"reading-voice").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(tr(R.string.tr_592))
            .setContentText(tr(R.string.tr_595, voice.page+1, if(playing) tr(R.string.tr_593) else tr(R.string.tr_594)))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(Notification.Action.Builder(null,if(playing) tr(R.string.tr_150) else tr(R.string.tr_151),action("toggle")).build())
            .addAction(Notification.Action.Builder(null,tr(R.string.tr_596),action("stop")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0,1)).build()
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        owned=voice.speaker
        when(intent?.action) { "stop" -> voice.finishPlayback();"toggle" -> if(voice.speaker?.speaking==true) voice.pause() else voice.resume() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onDestroy() { if(voice.speaker===owned) voice.pause();scope.cancel();media.release();unregisterReceiver(noisy);manager.cancel(1901);super.onDestroy() }
}
