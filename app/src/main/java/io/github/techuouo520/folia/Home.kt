// Phone home: the Bravais cover wall, tabs, and the sign-in entry. Motion numbers live in the Rust wall.
package io.github.techuouo520.folia

import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.bridge.CollectionCard
import io.github.techuouo520.folia.bridge.CoverAsk
import io.github.techuouo520.folia.bridge.CoverLookup
import io.github.techuouo520.folia.bridge.ExploreCard
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.HomeSnapshot
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.PlacedTile
import io.github.techuouo520.folia.bridge.PointerEnd
import io.github.techuouo520.folia.bridge.SessionPhase
import io.github.techuouo520.folia.bridge.SessionSnapshot
import io.github.techuouo520.folia.bridge.WallBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val tabs = listOf("playlist", "radio", "explore", "albums", "local")

private data class WallItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val path: String,
    val url: String,
    val kind: String,
    val index: Int,
    val section: String,
)

@Composable
fun HomePage(
    bridge: FoliaBridge,
    shell: Shell,
    message: String?,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onPlay: (List<LibraryTrack>, Int) -> Unit,
    onToggle: () -> Unit,
    action: (() -> Unit) -> Unit,
) {
    var tab by remember { mutableStateOf(bridge.homeTab()) }
    var leaving by remember { mutableStateOf<List<WallItem>?>(null) }
    var waveToken by remember { mutableIntStateOf(0) }
    var loginOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val session by produceState(initialValue = SessionSnapshot(SessionPhase.RESOLVING, "", "", ""), bridge) {
        while (isActive) {
            value = withContext(Dispatchers.IO) { runCatching { bridge.sessionSnapshot() }.getOrDefault(value) }
            delay(600)
        }
    }
    val home by produceState(initialValue = HomeSnapshot(session, false, "", emptyList(), emptyList()), bridge, tab) {
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
    val online = tab != "local"
    val signedIn = session.phase == SessionPhase.SIGNED_IN
    val items = remember(tab, shell.tracks, home.cards, home.explore, signedIn) {
        wallItems(tab, shell.tracks, home, signedIn)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = if (bridge.darkTheme()) Color(0xFF101014) else Color(0xFFF4F1EA)) {
        Column(modifier = Modifier.safeDrawingPadding().fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { id ->
                    val selected = id == tab
                    Text(
                        bridge.t(tabKey(id)),
                        modifier = Modifier.clickable {
                            if (id != tab) {
                                leaving = items
                                waveToken += 1
                                tab = id
                                action { bridge.setHomeTab(id) }
                            }
                        }.background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = onOpenSearch) { Text(bridge.t("libraryBravaisHome.search")) }
                TextButton(onClick = { settingsOpen = true }) { Text(bridge.t("libraryBravaisHome.settings")) }
            }
            if (!notificationsGranted) {
                TextButton(onClick = onRequestNotifications, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.permission_notification_action))
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (online && session.phase != SessionPhase.SIGNED_IN) {
                    Guest(bridge, session.phase == SessionPhase.RESOLVING) { loginOpen = true }
                } else if (items.isEmpty()) {
                    Text(bridge.t(if (tab == "local") "localMusic.noLocalMusic" else "home.loadingLibrary"), modifier = Modifier.align(Alignment.Center).padding(24.dp))
                } else {
                    val openTile = rememberUpdatedState<(WallItem) -> Unit> { item ->
                        when (item.kind) {
                            "song" -> onPlay(shell.tracks, item.index)
                            "fm" -> onPlay(bridge.personalFm(), 0)
                            "explore-song" -> onPlay(bridge.exploreTracks(item.section), item.index)
                            else -> onOpenCollection(item.key)
                        }
                    }
                    val openStable = remember<(WallItem, Offset) -> Unit> { { item, _ -> openTile.value(item) } }
                    CoverWall(bridge, items, leaving, waveToken, shell.playback.trackId, openStable)
                }
            }
            if (tab == "local") {
                LocalTools(bridge, shell, action)
            }
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            MiniBar(bridge, shell, onOpenPlayer, onToggle)
        }
    }
    if (loginOpen) {
        LoginDialog(bridge, onDismiss = { loginOpen = false })
    }
    if (settingsOpen) {
        SettingsDialog(bridge, onDismiss = { settingsOpen = false })
    }
}

private fun tabKey(tab: String): String = when (tab) {
    "radio" -> "home.radio"
    "explore" -> "home.explore"
    "albums" -> "home.albums"
    "local" -> "localMusic.folder"
    else -> "home.playlists"
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
private fun Guest(bridge: FoliaBridge, resolving: Boolean, onLogin: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(bridge.t(if (resolving) "home.loading" else "libraryBravaisHome.wallHint.guestTitle"), style = MaterialTheme.typography.titleLarge)
        if (!resolving) {
            Text(bridge.t("libraryBravaisHome.connect"), modifier = Modifier.padding(top = 12.dp))
            Button(onClick = onLogin, modifier = Modifier.padding(top = 16.dp)) { Text(bridge.t("libraryBravaisHome.connect")) }
        }
    }
}

@Composable
private fun CoverWall(
    bridge: FoliaBridge,
    items: List<WallItem>,
    leaving: List<WallItem>?,
    waveToken: Int,
    playingId: Long,
    onOpen: (WallItem, Offset) -> Unit,
) {
    val density = LocalDensity.current.density
    val wall = remember { WallBoard() }
    val measurer = rememberTextMeasurer()
    var frame by remember { mutableIntStateOf(0) }
    var drawing by remember { mutableStateOf(false) }
    val gate = remember { DragGate() }
    val cache = remember { BitmapCache() }
    val titles = remember { TitleCache() }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    LaunchedEffect(waveToken) {
        if (waveToken == 0) {
            return@LaunchedEffect
        }
        wall.beginWave(SystemClock.uptimeMillis().toDouble())
        drawing = true
    }
    LaunchedEffect(drawing) {
        if (!drawing) {
            return@LaunchedEffect
        }
        while (isActive && drawing) {
            withFrameNanos { }
            val now = SystemClock.uptimeMillis().toDouble()
            val moving = wall.frame(now) || wall.shiftRunning(now)
            frame++
            if (!moving && !gate.down) {
                drawing = false
            }
        }
    }
    LaunchedEffect(items, width, density) {
        val waiting = mutableListOf<CoverAsk>()
        while (isActive) {
            if (!gate.down) {
                val snapshot = withContext(Dispatchers.Default) { wall.tiles(items.size) }
                val asks = snapshot.mapNotNull { tile ->
                    val item = items.getOrNull(tile.index) ?: return@mapNotNull null
                    if (item.path.isEmpty() && item.url.isEmpty()) {
                        return@mapNotNull null
                    }
                    val px = (tile.width * density).toInt().coerceIn(32, 512)
                    CoverAsk(cacheKey(item, px), item.path, item.url, px, px)
                }
                val queued = waiting.map { it.key }.toSet()
                val fresh = asks.filter { ask -> ask.key !in queued && cache.get(ask.key) == null }
                if (fresh.isNotEmpty()) {
                    withContext(Dispatchers.IO) { bridge.requestCovers(fresh) }
                    waiting += fresh
                }
                if (waiting.isNotEmpty()) {
                    val arrived = withContext(Dispatchers.IO) {
                        val images = mutableListOf<Pair<String, ImageBitmap>>()
                        val still = mutableListOf<CoverAsk>()
                        for (ask in waiting) {
                            when (val found = bridge.coverLookup(ask.key)) {
                                is CoverLookup.Ready -> decodeCover(found.bytes, ask.width)?.let { images += ask.key to it }
                                is CoverLookup.Missing -> Unit
                                is CoverLookup.Pending -> still += ask
                            }
                        }
                        waiting.clear()
                        waiting.addAll(still)
                        images
                    }
                    arrived.forEach { (key, image) -> cache.put(key, image) }
                    if (arrived.isNotEmpty() && !drawing) {
                        frame++
                    }
                }
            }
            delay(if (gate.down) 200 else 100)
        }
    }
    val redraw = frame
    Canvas(modifier = Modifier.fillMaxSize().onSizeChanged {
        width = (it.width / density).toInt().coerceAtLeast(1)
        height = (it.height / density).toInt().coerceAtLeast(1)
        wall.resize(width.toFloat(), height.toFloat())
    }.pointerInput(items.size, density) {
        awaitEachGesture {
            val down = awaitFirstDown()
            gate.down = true
            drawing = true
            wall.pointerDown(down.position.x / density, down.position.y / density, SystemClock.uptimeMillis().toDouble())
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    gate.down = false
                    val end = wall.pointerUp(SystemClock.uptimeMillis().toDouble())
                    if (end is PointerEnd.Drag || end is PointerEnd.Click) {
                        drawing = true
                    }
                    if (end is PointerEnd.Click) {
                        val hit = wall.hit(end.x, end.y, items.size)
                        val item = items.getOrNull(hit)
                        if (item != null && item.kind != "song" && item.kind != "fm" && item.kind != "explore-song") {
                            wall.beginFlip(end.x, end.y, SystemClock.uptimeMillis().toDouble())
                        }
                        if (item != null) {
                            onOpen(item, Offset(end.x, end.y))
                        }
                    }
                    break
                }
                if (change.positionChange() != Offset.Zero) {
                    val dragging = wall.pointerMove(change.position.x / density, change.position.y / density, SystemClock.uptimeMillis().toDouble())
                    if (dragging) {
                        change.consume()
                    }
                }
            }
        }
    }) {
        val now = SystemClock.uptimeMillis().toDouble()
        val showTitle = !gate.down
        if (redraw < 0) {
            return@Canvas
        }
        drawRect(if (bridge.darkTheme()) Color(0xFF101014) else Color(0xFFF4F1EA))
        val previous = leaving
        if (previous != null && wall.shiftRunning(now)) {
            wall.placed(previous.size, now, true).forEach { tile ->
                val item = previous.getOrNull(tile.index) ?: return@forEach
                drawTile(tile, item, cache, titles, density, measurer, false, showTitle)
            }
        }
        wall.placed(items.size, now, false).forEach { tile ->
            val item = items.getOrNull(tile.index) ?: return@forEach
            val playing = item.kind == "song" && item.key == playingId.toString()
            drawTile(tile, item, cache, titles, density, measurer, playing, showTitle)
        }
    }
}

private class DragGate {
    var down: Boolean = false
}

private fun DrawScope.drawTile(
    tile: PlacedTile,
    item: WallItem,
    cache: LruCache<String, ImageBitmap>,
    titles: TitleCache,
    density: Float,
    measurer: TextMeasurer,
    playing: Boolean,
    showTitle: Boolean,
) {
    if (!tile.visible || tile.alpha <= 0.01f) {
        return
    }
    val scale = density * tile.scale
    val width = tile.width * scale * tile.squeeze
    val height = tile.height * scale
    val left = (tile.x + tile.width / 2f) * density - width / 2f
    val top = (tile.y + tile.height / 2f) * density - height / 2f - tile.lift * density
    val px = (tile.width * density).toInt().coerceIn(32, 512)
    val image = cache.get(cacheKey(item, px))
    drawRoundRect(Color(0xFF2A2A30).copy(alpha = tile.alpha), Offset(left, top), Size(width, height), CornerRadius(12f, 12f))
    if (image != null && width > 1f && height > 1f) {
        drawImage(
            image,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
            alpha = tile.alpha,
            filterQuality = if (showTitle) FilterQuality.Low else FilterQuality.None,
        )
    }
    if (showTitle) {
        val titleWidth = width.toInt().coerceAtLeast(1)
        val titleKey = item.title + "@" + titleWidth
        val title = titles.get(titleKey) ?: measurer.measure(
            item.title,
            TextStyle(color = Color.White, fontSize = 14.sp),
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = titleWidth),
        ).also { titles.put(titleKey, it) }
        drawText(title, Color.White.copy(alpha = tile.alpha), Offset(left + 8f, top + height - title.size.height - 22f))
    }
    if (playing) {
        drawRoundRect(Color(0xFFE11D48), Offset(left, top), Size(width, height), CornerRadius(12f, 12f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

private class BitmapCache : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 2
}

private class TitleCache : LruCache<String, TextLayoutResult>(256)

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

private fun cacheKey(item: WallItem, px: Int): String {
    val source = if (item.path.isNotEmpty()) item.path else item.url
    return "$source@$px"
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

@Composable
private fun MiniBar(bridge: FoliaBridge, shell: Shell, onOpen: () -> Unit, onToggle: () -> Unit) {
    if (shell.playback.title.isEmpty()) {
        return
    }
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(shell.playback.title, maxLines = 1)
            Text(shell.playback.artist.ifBlank { bridge.t("player.unknownArtist") }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        TextButton(onClick = onToggle) { Text(bridge.t(if (shell.playback.state == io.github.techuouo520.folia.bridge.TransportState.PLAYING) "player.pause" else "player.play")) }
    }
}
