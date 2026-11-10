// Foreground media session. The Rust player is the source of position, and this service only mirrors it.
package io.github.techuouo520.folia

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

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var player: FoliaPlayer? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                return
            }
            Log.i(TAG, "audio route disconnected, pausing")
            AudioFocus.clearResume()
            try {
                FoliaApp.from(context).bridge.pause()
            } catch (error: Exception) {
                Log.e(TAG, "pause after audio disconnect failed", error)
            }
            AudioFocus.abandon()
            player?.refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val created = FoliaPlayer(this, FoliaApp.from(this).bridge, Looper.getMainLooper())
        player = created
        mediaSession = MediaSession.Builder(this, created).setId("folia").build()
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
            var artTrack = Long.MIN_VALUE
            while (isActive) {
                delay(250)
                created.refresh()
                val track = runCatching { FoliaApp.from(this@PlaybackService).bridge.status().trackId }.getOrNull() ?: continue
                if (track == artTrack) {
                    continue
                }
                artTrack = track
                created.setArtwork(track, null)
                launch(Dispatchers.IO) {
                    var bytes: ByteArray? = null
                    var attempt = 0
                    while (attempt < 40 && bytes == null) {
                        bytes = runCatching { FoliaApp.from(this@PlaybackService).bridge.artwork() }.getOrNull()
                        if (bytes == null) {
                            delay(150)
                        }
                        attempt += 1
                    }
                    val ready = bytes ?: return@launch
                    if (artTrack != track) {
                        return@launch
                    }
                    // The bytes stay encoded. Media3 and the system decode them off the UI thread.
                    launch(Dispatchers.Main) { created.setArtwork(track, ready) }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        scope.cancel()
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
