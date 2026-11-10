// Track list, folder field, scan status, and the bottom transport bar.
package io.github.techuouo520.folia

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.ScanPhase
import io.github.techuouo520.folia.bridge.TransportState

@Composable
fun LibraryPage(
    bridge: FoliaBridge,
    shell: Shell,
    message: String?,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPlay: (Int) -> Unit,
    onToggle: () -> Unit,
    action: (() -> Unit) -> Unit,
) {
    var folder by remember { mutableStateOf(runCatching { bridge.musicFolder() }.getOrDefault("/storage/emulated/0/Music")) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(bridge.t("app.name"), style = MaterialTheme.typography.headlineMedium)
            Text(bridge.t("localMusic.allSongs"), style = MaterialTheme.typography.titleMedium)
            if (!notificationsGranted) {
                Text(stringResource(R.string.permission_notification_body))
                TextButton(onClick = onRequestNotifications) { Text(stringResource(R.string.permission_notification_action)) }
            }
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.music_folder_label)) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { action { bridge.setMusicFolder(folder.trim()); bridge.startScan() } }) {
                    Text(bridge.t("localMusic.importFolder"))
                }
                TextButton(onClick = { action { bridge.startScan() } }) { Text(bridge.t("options.refresh")) }
            }
            Text(scanLabel(bridge, shell))
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shell.tracks.isEmpty() && shell.scan.phase != ScanPhase.RUNNING) {
                    item { Text(bridge.t("localMusic.noLocalMusic")) }
                }
                itemsIndexed(shell.tracks, key = { _, track -> track.id }) { index, track ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPlay(index) }.padding(vertical = 8.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium)
                        Text(track.artist.ifBlank { bridge.t("player.unknownArtist") }, style = MaterialTheme.typography.bodyMedium)
                        Text(formatTime(track.durationSeconds), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (shell.playback.trackId != 0L) {
                MiniPlayer(bridge, shell.playback, onOpenPlayer, onToggle)
            }
        }
    }
}

@Composable
private fun MiniPlayer(bridge: FoliaBridge, playback: io.github.techuouo520.folia.bridge.PlaybackSnapshot, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(playback.title.ifBlank { bridge.t("ui.noTrack") }, style = MaterialTheme.typography.titleSmall)
            Text(playback.artist.ifBlank { bridge.t("player.unknownArtist") }, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onToggle) {
            Text(if (playback.state == TransportState.PLAYING) bridge.t("player.pause") else bridge.t("player.play"))
        }
    }
}

@Composable
private fun scanLabel(bridge: FoliaBridge, shell: Shell): String {
    return when (shell.scan.phase) {
        ScanPhase.RUNNING -> bridge.t("localMusic.importing")
        ScanPhase.FAILED -> stringResource(R.string.scan_failed) + ": " + shell.scan.error
        ScanPhase.DONE -> stringResource(R.string.scan_result, shell.scan.updated, shell.scan.removed, shell.scan.total)
        else -> ""
    }
}

fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
