// Edge-to-edge world. Insets belong to controls, not to the scene viewport.
package io.github.techuouo520.folia

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import io.github.techuouo520.folia.spatial.SceneLabel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.bridge.CollectionCard
import io.github.techuouo520.folia.bridge.ExploreCard
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.HomeSnapshot
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.SessionPhase
import io.github.techuouo520.folia.bridge.SessionSnapshot
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext


internal data class WallItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val path: String,
    val url: String,
    val kind: String,
    val index: Int,
    val section: String,
)

internal val LocalPlayerCoversPage = staticCompositionLocalOf { false }

@Composable
internal fun HomePage(
    bridge: FoliaBridge,
    shell: Shell,
    message: String?,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenPlayer: () -> Unit,
    preloadPlayerLyrics: Boolean,
    playerProgress: androidx.compose.runtime.FloatState,
    playerFootprint: androidx.compose.ui.unit.Dp,
    frost: FrostBackdrop,
    screenCorner: Float,
    onOpenSearch: () -> Unit,
    onPlay: (List<LibraryTrack>, Int) -> Unit,
    onToggle: () -> Unit,
    action: (() -> Unit) -> Unit,
) {
    FoliaApp.from(androidx.compose.ui.platform.LocalContext.current).appearanceVersion
    val tab = remember { bridge.homeTab() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val session by produceState(initialValue = SessionSnapshot(SessionPhase.RESOLVING, "", "", "", ""), bridge, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) { runCatching { bridge.sessionSnapshot() }.getOrDefault(value) }
                delay(600)
            }
        }
    }
    val home by produceState(initialValue = HomeSnapshot(session, false, "", emptyList(), emptyList()), bridge, tab, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        if (tab != "local") {
                            bridge.beginHome(tab)
                        }
                        bridge.homeSnapshot(tab)
                    }.getOrDefault(value)
                }
                delay(700)
            }
        }
    }
    val online = tab != "local"
    val signedIn = session.phase == SessionPhase.SIGNED_IN
    val items = remember(tab, shell.tracks, home.cards, home.explore, signedIn) {
        wallItems(tab, shell.tracks, home, signedIn)
    }
    val pageCovered by remember { derivedStateOf { playerProgress.floatValue > .001f } }
    val navigation = LocalCornerNavigation.current
    val playerLyricsOpen = navigation?.playerLyricsOpen == true
    val hasPlayer = true
    val bottomSafe=WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val playerInset = playerFootprint + bottomSafe
    val density = androidx.compose.ui.platform.LocalDensity.current
    var controlsInset by remember { mutableStateOf(0.dp) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().then(if (hasPlayer) Modifier.frostSource(frost) else Modifier)) {
                Box(Modifier.fillMaxSize().then(if (playerLyricsOpen) Modifier.clearAndSetSemantics {} else Modifier).graphicsLayer {
                    val p = playerProgress.floatValue
                    scaleX = playerMix(1f,.85f,playerSmooth(0f,1f,p))
                    scaleY = scaleX
                    alpha = 1f-playerSmooth(.1f,.9f,p)
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(screenCorner.dp)
                    clip = p > 0f
                }) {
                    CompositionLocalProvider(LocalPlayerCoversPage provides (pageCovered || playerLyricsOpen)) {
                        if (online && session.phase != SessionPhase.SIGNED_IN) {
                            Guest(bridge, session.phase == SessionPhase.RESOLVING)
                        } else if (items.isEmpty()) {
                            Text(bridge.t(if (tab == "local") "localMusic.noLocalMusic" else "home.loadingLibrary"), modifier = Modifier.align(Alignment.Center).padding(24.dp))
                        } else {
                            val openTile = rememberUpdatedState<(WallItem) -> Unit> { item ->
                                when (item.kind) {
                                    "song" -> onPlay(shell.tracks, item.index)
                                    "fm" -> onPlay(bridge.personalFm(), 0)
                                    "explore-song" -> onPlay(bridge.exploreTracks(item.section), item.index)
                                    else -> Unit // Collections stay inside the spatial scene.
                                }
                            }
                            FoliaSpatialWall(bridge, items, shell, { openTile.value(it) }, onPlay, onToggle, action, playerInset + controlsInset, frost::sourceFramePresented)
                        }
                        if (LocalCornerNavigation.current?.insideAlbum != true) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(bottom = playerInset).avoidPlayer().onSizeChanged { controlsInset = with(density) { it.height.toDp() } }) {
                            if (tab == "local") LocalTools(bridge, shell, action)
                            if (message != null) {
                                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
                PlayerLyricsPage(bridge, shell.playback, playerLyricsOpen, preloadPlayerLyrics)
            }

        }
    }
}

private fun wallItems(tab: String, tracks: List<LibraryTrack>, home: HomeSnapshot, signedIn: Boolean): List<WallItem> {
    if (tab == "local") {
        return tracks.mapIndexed { index, track ->
            WallItem(track.id.toString(), track.title, track.artist, track.path, "", "song", index, "")
        }
    }
    if (!signedIn) {
        return emptyList()
    }
    if (tab == "explore") {
        return home.explore.map { card -> exploreItem(card) }
    }
    return home.cards.map { card -> collectionItem(card) }
}

private fun collectionItem(card: CollectionCard): WallItem {
    return WallItem(card.key, card.title, card.subtitle, "", card.coverUrl, card.kind, 0, "")
}

private fun exploreItem(card: ExploreCard): WallItem {
    val kind = if (card.playable) "explore-song" else "playlist"
    return WallItem(card.key, card.title, card.subtitle, "", card.coverUrl, kind, card.trackIndex, card.section)
}

@Composable
private fun Guest(bridge: FoliaBridge, resolving: Boolean) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 320.dp).avoidPlayer(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(bridge.t(if (resolving) "home.loading" else "libraryBravaisHome.wallHint.guestTitle"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (!resolving) {
                Text(bridge.t("home.connectFromAvatar"), modifier = Modifier.widthIn(max = 280.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun scanLabel(bridge: FoliaBridge, shell: Shell): String {
    val scan = shell.scan
    return when (scan.phase) {
        io.github.techuouo520.folia.bridge.ScanPhase.RUNNING -> "${bridge.t("localMusic.importing")} ${scan.seen}"
        io.github.techuouo520.folia.bridge.ScanPhase.FAILED -> stringResource(R.string.scan_failed) + ": " + scan.error
        io.github.techuouo520.folia.bridge.ScanPhase.DONE -> stringResource(R.string.scan_result, scan.updated, scan.removed, scan.total)
        else -> ""
    }
}

@Composable
private fun LocalTools(bridge: FoliaBridge, shell: Shell, action: (() -> Unit) -> Unit) {
    var folder by remember { mutableStateOf(runCatching { bridge.musicFolder() }.getOrDefault("/storage/emulated/0/Music")) }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(value = folder, onValueChange = { folder = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.music_folder_label)) })
        Text(scanLabel(bridge, shell), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { action { bridge.setMusicFolder(folder.trim()); bridge.startScan() } }) { Text(bridge.t("localMusic.importFolder")) }
            TextButton(onClick = { action { bridge.startScan() } }) { Text(bridge.t("options.refresh")) }
        }
    }
}

