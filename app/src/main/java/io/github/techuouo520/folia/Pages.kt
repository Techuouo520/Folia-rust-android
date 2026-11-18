// Sign-in, search, a collection's tracks, settings and the equalizer. Layout stays plain on purpose.
package io.github.techuouo520.folia

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.LoginPhase
import io.github.techuouo520.folia.bridge.SearchSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoginDialog(bridge: FoliaBridge, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var logoutError by remember { mutableStateOf<String?>(null) }
    val provider = remember { bridge.sessionSnapshot().provider.ifBlank { "netease" } }
    val platform = remember(bridge,provider) { bridge.platforms().firstOrNull { it.id == provider } }
    DisposableEffect(bridge,provider) { onDispose { bridge.cancelLogin() } }
    LaunchedEffect(provider) { bridge.beginLogin(provider) }
    val login by produceState(initialValue = bridge.loginSnapshot(), provider) {
        while (isActive) {
            value = withContext(Dispatchers.IO) { bridge.loginSnapshot() }
            if (value.phase == LoginPhase.CONFIRMED) {
                withContext(Dispatchers.IO) { bridge.beginHome("playlist") }
            }
            delay(500)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(platform?.loginTitle ?: bridge.t("home.loginTitle")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(platform?.loginNote ?: bridge.t("home.loginNote"))
                logoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (login.width > 0) {
                    Canvas(modifier = Modifier.size(220.dp).background(Color.White)) {
                        val cell = size.minDimension / login.width
                        login.modules.forEachIndexed { index, dark ->
                            if (dark.toInt() != 0) {
                                val x = (index % login.width) * cell
                                val y = (index / login.width) * cell
                                drawRect(Color.Black, Offset(x, y), Size(cell, cell))
                            }
                        }
                    }
                } else if (login.phase == LoginPhase.LOADING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(when (login.phase) {
                    LoginPhase.SCANNED -> bridge.t("home.qrScanned")
                    LoginPhase.CONFIRMED -> bridge.t("home.loginSuccess") + " " + login.message
                    LoginPhase.EXPIRED -> bridge.t("home.retryQr")
                    LoginPhase.FAILED -> login.message
                    else -> ""
                })
                if (login.phase == LoginPhase.EXPIRED || login.phase == LoginPhase.FAILED) {
                    Button(onClick = { bridge.beginLogin(provider) }) { Text(bridge.t("home.retryQr")) }
                }
            }
        },
        confirmButton = {
            if (bridge.sessionSnapshot().phase == io.github.techuouo520.folia.bridge.SessionPhase.SIGNED_IN) {
                TextButton(onClick = {
                    AudioFocus.clearResume()
                    AudioFocus.abandon()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { bridge.beginLogout() }
                            onDismiss()
                        } catch(error: Exception) {
                            if(error is kotlinx.coroutines.CancellationException) throw error
                            logoutError = error.message ?: "退出登录失败"
                        } finally {
                            PlaybackService.requestRefresh()
                        }
                    }
                }) { Text(bridge.t("ui.cancel")) }
            } else {
                TextButton(onClick = onDismiss) { Text(bridge.t("home.closeLogin")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(bridge.t("ui.back")) } },
    )
}

@Composable
fun SearchPage(bridge: FoliaBridge, onBack: () -> Unit, onPlay: (List<LibraryTrack>, Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(SearchSnapshot(false, "", "", emptyList(), false, 0)) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { bridge.beginSearch(query, 0) }
        while (isActive) {
            snapshot = withContext(Dispatchers.IO) { bridge.searchSnapshot() }
            if (!snapshot.loading) {
                break
            }
            delay(200)
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding().padding(top=80.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(bridge.t("ui.back")) }
            androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(bridge.t("libraryBravaisHome.searchPlaceholder")) }, singleLine = true)
            if (snapshot.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (snapshot.error.isNotEmpty()) {
                Text(snapshot.error, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(snapshot.tracks, key = { _, track -> track.id }) { index, track ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPlay(snapshot.tracks, index) }.padding(vertical = 8.dp)) {
                        Text(track.title)
                        Text(track.artist.ifBlank { bridge.t("player.unknownArtist") }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (snapshot.hasMore) {
                Button(onClick = { bridge.beginSearch(query, snapshot.nextOffset) }) { Text(bridge.t("home.loadMore")) }
            }
        }
    }
}

@Composable
fun CollectionPage(bridge: FoliaBridge, collectionKey: String, onBack: () -> Unit, onPlay: (List<LibraryTrack>, Int) -> Unit) {
    val page by produceState(initialValue = bridge.collectionPage(collectionKey), collectionKey) {
        withContext(Dispatchers.IO) { bridge.beginCollection(collectionKey) }
        while (isActive) {
            value = withContext(Dispatchers.IO) { bridge.collectionPage(collectionKey) }
            if (!value.loading) {
                break
            }
            delay(200)
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding().padding(top=80.dp).padding(16.dp)) {
            TextButton(onClick = onBack) { Text(bridge.t("ui.back")) }
            Text(page.title, style = MaterialTheme.typography.titleLarge)
            if (page.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            if (page.error.isNotEmpty()) {
                Text(page.error, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn {
                itemsIndexed(page.tracks, key = { _, track -> track.id }) { index, track ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onPlay(page.tracks, index) }.padding(vertical = 10.dp)) {
                        Text("${index + 1}", modifier = Modifier.padding(end = 12.dp))
                        Column {
                            Text(track.title)
                            Text(track.artist.ifBlank { bridge.t("player.unknownArtist") }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

