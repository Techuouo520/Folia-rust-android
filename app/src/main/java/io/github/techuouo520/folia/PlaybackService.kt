// Foreground media session. The Rust player is the source of position, and this service only mirrors it.
package io.github.techuouo520.folia

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var player: FoliaPlayer? = null
    private val wakeLock by lazy { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"Folia:Playback").apply {setReferenceCounted(false)} }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                return
            }
            Log.i(TAG, "audio route disconnected, pausing")
            AudioFocus.clearResume()
            scope.launch {
                withContext(Dispatchers.IO) {runCatching {FoliaApp.from(context).bridge.pause()}.onFailure {Log.e(TAG,"pause after disconnect failed",it)}}
                AudioFocus.abandon();player?.refresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val created = FoliaPlayer(this, FoliaApp.from(this).bridge, Looper.getMainLooper())
        created.onSnapshot = { snapshot ->
            val ongoing = snapshot.state == io.github.techuouo520.folia.bridge.TransportState.PLAYING ||
                (snapshot.loading && snapshot.state != io.github.techuouo520.folia.bridge.TransportState.PAUSED)
            if (ongoing && !wakeLock.isHeld) wakeLock.acquire()
            if (!ongoing && wakeLock.isHeld) wakeLock.release()
        }
        player = created
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        val activity=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(this,created).setId("folia").setSessionActivity(activity)
            .setPeriodicPositionUpdateEnabled(true)
            .setCallback(object: MediaSession.Callback {
                override fun onPlaybackResumption(session: MediaSession,controller: MediaSession.ControllerInfo,isForPlayback: Boolean)=created.resumption()
            }).build()
        AudioFocus.onChanged = { created.refresh() }
        refresh = { created.refresh() }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisy, filter)
        }
        created.refresh()
        scope.launch {
            while(isActive) {
                created.refresh()
                delay(250)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Match SalamPlayer: task dismissal pauses and checkpoints; ordinary backgrounding plays on.
        scope.launch {
            withContext(Dispatchers.IO) {runCatching {FoliaApp.from(this@PlaybackService).bridge.pause()}}
            AudioFocus.clearResume();AudioFocus.abandon();pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if(wakeLock.isHeld) wakeLock.release()
        refresh = null
        AudioFocus.onChanged = null
        unregisterReceiver(noisy)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FoliaPlayback"
        private val main = Handler(Looper.getMainLooper())
        private var refresh: (() -> Unit)? = null

        fun requestRefresh() {
            val callback = refresh ?: return
            main.post(callback)
        }
    }
}
