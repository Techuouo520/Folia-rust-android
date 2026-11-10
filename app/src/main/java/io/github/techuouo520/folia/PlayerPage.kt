// Full-screen transport. The slider shows the Rust position and only sends a seek when the drag ends.
package io.github.techuouo520.folia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.RepeatMode
import io.github.techuouo520.folia.bridge.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PlayerPage(
    bridge: FoliaBridge,
    playback: PlaybackSnapshot,
    message: String?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Double) -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val coverSize = 280.dp
    val edge = with(LocalDensity.current) { coverSize.roundToPx() }.coerceAtLeast(1)
    var equalizerOpen by remember { mutableStateOf(false) }
    val lyric by produceState(initialValue = bridge.lyrics(), playback.trackId) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { bridge.lyrics() }.getOrDefault(value) }
            delay(250)
        }
    }
    val cover by produceState<ImageBitmap?>(null, playback.trackId, edge) {
        value = null
        if (playback.trackId == 0L) {
            return@produceState
        }
        repeat(80) {
            val bytes = withContext(Dispatchers.IO) { runCatching { bridge.artwork() }.getOrNull() }
            if (bytes != null && bytes.isNotEmpty()) {
                value = decodeCover(bytes, edge)
                return@produceState
            }
            delay(250)
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text(bridge.t("ui.back")) }
            }
            val image = cover
            if (image == null) {
                Box(modifier = Modifier.size(coverSize).background(MaterialTheme.colorScheme.surfaceVariant))
            } else {
                Image(image, contentDescription = null, modifier = Modifier.size(coverSize), contentScale = ContentScale.Crop)
            }
            Text(playback.title.ifBlank { bridge.t("ui.noTrack") }, style = MaterialTheme.typography.headlineSmall)
            Text(playback.artist.ifBlank { bridge.t("player.unknownArtist") }, style = MaterialTheme.typography.titleMedium)
            val line = lyric.lines.getOrNull(lyric.lineIndex)
            if (line != null) {
                Text(line.text, style = MaterialTheme.typography.bodyLarge)
                val word = line.words.getOrNull(lyric.wordIndex)
                if (word != null) {
                    Text(word.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { equalizerOpen = true }) { Text(bridge.t("ui.equalizerTitle")) }
            PositionSlider(playback, onSeek)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious, enabled = playback.hasPrevious) { Text(bridge.t("ui.previousTrack")) }
                TextButton(onClick = onToggle) {
                    Text(if (playback.state == TransportState.PLAYING) bridge.t("player.pause") else bridge.t("player.play"))
                }
                TextButton(onClick = onNext, enabled = playback.hasNext) { Text(bridge.t("ui.nextTrack")) }
            }
            TextButton(onClick = onCycleRepeat) { Text(repeatLabel(bridge, playback.repeatMode)) }
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (equalizerOpen) {
        EqualizerDialog(bridge) { equalizerOpen = false }
    }
}

@Composable
private fun PositionSlider(playback: PlaybackSnapshot, onSeek: (Double) -> Unit) {
    val duration = playback.durationSeconds.toFloat().coerceAtLeast(0f)
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragged else playback.positionSeconds.toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration <= 0f) 0f else shown.coerceIn(0f, duration),
            onValueChange = {
                dragging = true
                dragged = it
            },
            onValueChangeFinished = {
                onSeek(dragged.toDouble())
                dragging = false
            },
            valueRange = 0f..(if (duration <= 0f) 1f else duration),
            enabled = duration > 0f,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragging) dragged.toDouble() else playback.positionSeconds))
            Text(formatTime(playback.durationSeconds))
        }
    }
}

private fun repeatLabel(bridge: FoliaBridge, mode: RepeatMode): String {
    return when (mode) {
        RepeatMode.ALL -> bridge.t("player.loopAll")
        RepeatMode.ONE -> bridge.t("player.loopOne")
        else -> bridge.t("player.loopOff")
    }
}

@Composable
fun StoragePermissionScreen(legacy: Boolean, onAllow: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResourceSafe(R.string.permission_storage_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResourceSafe(if (legacy) R.string.permission_storage_legacy_body else R.string.permission_storage_body))
            ButtonAllow(legacy, onAllow)
        }
    }
}

@Composable
private fun ButtonAllow(legacy: Boolean, onAllow: () -> Unit) {
    androidx.compose.material3.Button(onClick = onAllow) {
        Text(stringResourceSafe(if (legacy) R.string.permission_storage_legacy_action else R.string.permission_storage_action))
    }
}

@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)
