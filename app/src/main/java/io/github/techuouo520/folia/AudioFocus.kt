// Audio focus for music playback: pause on loss, duck on a transient duck, resume after a transient loss.
package io.github.techuouo520.folia

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.TransportState

object AudioFocus {
    private const val TAG = "FoliaFocus"
    private val main = Handler(Looper.getMainLooper())
    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private var bridge: FoliaBridge? = null
    private var duckedFrom: Float? = null
    private var resumeWhenGained = false
    var onChanged: (() -> Unit)? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val current = bridge ?: return@OnAudioFocusChangeListener
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> onGain(current)
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeWhenGained = false
                    restore(current)
                    current.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onTransientLoss(current)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck(current)
                else -> Log.i(TAG, "audio focus change $change")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio focus change failed", error)
        }
        main.post { onChanged?.invoke() }
    }

    fun acquire(context: Context, bridge: FoliaBridge): Boolean {
        this.bridge = bridge
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        manager = audio
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focus = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { request = it }
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "audio focus was not granted")
        }
        return granted
    }

    fun abandon() {
        resumeWhenGained = false
        request?.let { focus -> manager?.abandonAudioFocusRequest(focus) }
    }

    fun clearResume() {
        resumeWhenGained = false
    }

    private fun onGain(bridge: FoliaBridge) {
        restore(bridge)
        if (resumeWhenGained) {
            resumeWhenGained = false
            bridge.play()
        }
    }

    private fun onTransientLoss(bridge: FoliaBridge) {
        val playing = bridge.status().state == TransportState.PLAYING
        resumeWhenGained = playing
        restore(bridge)
        if (playing) {
            bridge.pause()
        }
    }

    private fun duck(bridge: FoliaBridge) {
        if (duckedFrom != null) {
            return
        }
        val volume = bridge.volume()
        duckedFrom = volume
        bridge.setOutputVolume(volume * 0.2f)
        Log.i(TAG, "ducking volume from $volume")
    }

    private fun restore(bridge: FoliaBridge) {
        val volume = duckedFrom ?: return
        duckedFrom = null
        bridge.setOutputVolume(volume)
    }
}
