// Media3 player whose timeline and transport commands are answered by the Rust queue.
package io.github.techuouo520.folia

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.RepeatMode
import io.github.techuouo520.folia.bridge.TransportState
import java.io.File

@UnstableApi
class FoliaPlayer(context: Context, private val bridge: FoliaBridge, looper: Looper) : SimpleBasePlayer(looper) {
    private val appContext = context.applicationContext
    private var cachedGeneration = Long.MIN_VALUE
    private var cachedItems = emptyList<MediaItemData>()
    private var artwork: ByteArray? = null
    private var artworkTrack = Long.MIN_VALUE

    fun setArtwork(trackId: Long, bytes: ByteArray?) {
        artworkTrack = trackId
        artwork = bytes
        cachedGeneration = Long.MIN_VALUE
        invalidateState()
    }

    fun refresh() {
        invalidateState()
    }

    override fun getState(): State {
        val status = try {
            bridge.status()
        } catch (error: Exception) {
            Log.e(TAG, "playback status failed", error)
            return State.Builder()
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayerError(PlaybackException(error.message ?: "status", error, PlaybackException.ERROR_CODE_UNSPECIFIED))
                .build()
        }
        val playlist = playlist(status)
        val playbackState = when {
            playlist.isEmpty() -> Player.STATE_IDLE
            status.state == TransportState.PLAYING || status.state == TransportState.PAUSED -> Player.STATE_READY
            status.state == TransportState.ENDED -> Player.STATE_ENDED
            else -> Player.STATE_IDLE
        }
        val positionMs = (status.positionSeconds * 1000.0).toLong().coerceAtLeast(0L)
        val builder = State.Builder()
            .setAvailableCommands(commands(status, playlist.isNotEmpty()))
            .setPlayWhenReady(status.state == TransportState.PLAYING, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setRepeatMode(repeatMode(status.repeatMode))
            .setVolume(status.volume.coerceIn(0f, 1f))
            .setContentPositionMs(PositionSupplier.getConstant(positionMs))
            .setContentBufferedPositionMs(PositionSupplier.getConstant(positionMs))
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE / 4)
        if (playlist.isNotEmpty()) {
            builder.setPlaylist(playlist).setCurrentMediaItemIndex(status.queueIndex.coerceIn(0, playlist.lastIndex))
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        return runBridge {
            if (playWhenReady) {
                if (AudioFocus.acquire(appContext, bridge)) {
                    bridge.play()
                }
            } else {
                AudioFocus.clearResume()
                bridge.pause()
                AudioFocus.abandon()
            }
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        return runBridge {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> bridge.next()
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> bridge.previous()
                Player.COMMAND_SEEK_TO_MEDIA_ITEM -> if (mediaItemIndex >= 0) bridge.playIndex(mediaItemIndex)
                else -> if (positionMs != C.TIME_UNSET) bridge.seek(positionMs / 1000.0)
            }
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        return runBridge {
            AudioFocus.clearResume()
            bridge.stop()
            AudioFocus.abandon()
        }
    }

    override fun handleRelease(): ListenableFuture<*> = handleStop()

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        return runBridge {
            bridge.setRepeat(
                when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
            )
        }
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        return runBridge { bridge.setVolume(if (volumeOperationType == C.VOLUME_OPERATION_TYPE_MUTE) 0f else volume) }
    }

    private fun playlist(status: PlaybackSnapshot): List<MediaItemData> {
        if (status.queueGeneration == cachedGeneration) {
            return if (status.queueLength == 0) emptyList() else cachedItems
        }
        val built = try {
            bridge.queue().mapIndexed { index, track -> item(index, track) }
        } catch (error: Exception) {
            Log.e(TAG, "queue read failed", error)
            return cachedItems
        }
        cachedGeneration = status.queueGeneration
        cachedItems = built
        return built
    }

    private fun item(index: Int, track: LibraryTrack): MediaItemData {
        val metadataBuilder = MediaMetadata.Builder().setTitle(track.title)
        if (track.artist.isNotBlank()) {
            metadataBuilder.setArtist(track.artist)
        }
        if (track.album.isNotBlank()) {
            metadataBuilder.setAlbumTitle(track.album)
        }
        if (track.id == artworkTrack && artwork != null) {
            metadataBuilder.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val metadata = metadataBuilder.build()
        val mediaItem = MediaItem.Builder().setMediaId(track.path.ifBlank { track.id.toString() }).setUri(fileUri(track.path)).setMediaMetadata(metadata).build()
        val durationUs = if (track.durationSeconds > 0.0) (track.durationSeconds * 1_000_000.0).toLong() else C.TIME_UNSET
        return MediaItemData.Builder("$index:${track.id}")
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs > 0)
            .setIsDynamic(false)
            .build()
    }

    private fun commands(status: PlaybackSnapshot, hasPlaylist: Boolean): Player.Commands {
        val builder = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_RELEASE)
        if (hasPlaylist) {
            builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        }
        if (status.hasNext) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (status.hasPrevious) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    private fun runBridge(block: () -> Unit): ListenableFuture<*> {
        try {
            block()
        } catch (error: Exception) {
            Log.e(TAG, "playback command failed", error)
        }
        return Futures.immediateVoidFuture()
    }

    private fun repeatMode(mode: RepeatMode): Int {
        return when (mode) {
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun fileUri(path: String): Uri {
        if (path.isEmpty()) {
            return Uri.EMPTY
        }
        return Uri.fromFile(File(path))
    }

    private companion object {
        const val TAG = "FoliaPlayer"
    }
}
