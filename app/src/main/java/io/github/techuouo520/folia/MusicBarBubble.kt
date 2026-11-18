package io.github.techuouo520.folia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

internal val MusicBarDockSide = 24.dp
internal val MusicBarDockBottom = 28.dp
internal val MusicBarPanelSide = 10.dp

/** Observe the clipped player panel, including touches consumed by its transport controls. */
@Composable
private fun Modifier.musicBarIdleTimeout(enabled: Boolean, interaction: Int, onTimeout: () -> Unit): Modifier {
    var touching by remember { mutableStateOf(false) }
    var releases by remember { mutableIntStateOf(0) }
    val timeout by rememberUpdatedState(onTimeout)
    if (enabled) LaunchedEffect(touching, releases, interaction) {
        if (!touching) {
            val lastRelease = releases
            delay(3_000)
            if (!touching && releases == lastRelease) timeout()
        }
    }
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val inside = event.changes.any { change ->
                        change.pressed && change.position.x >= 0 && change.position.x < size.width &&
                            change.position.y >= 0 && change.position.y < size.height
                    }
                    if (touching && !inside) releases++
                    touching = inside
                } while (event.changes.any { it.pressed })
            } finally {
                if (touching) releases++
                touching = false
            }
        }
    }
}

@Composable
internal fun MusicBarBubble(
    bridge: FoliaBridge, playback: PlaybackSnapshot, frost: FrostBackdrop,
    expanded: Boolean, onExpanded: (Boolean) -> Unit, onToggle: () -> Unit,
    action: (() -> Unit) -> Unit, dockLeft: Boolean,
) {
    val gate = LocalGripGate.current
    val flight = remember { DockFlight(dockLeft) }
    val motion = remember { CornerMotion(bottomOrigin = true, initiallyOpen = expanded, flight = flight) }
    val openIntent by rememberUpdatedState(expanded)
    val dockIntent by rememberUpdatedState(dockLeft)
    val frame = remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var geometry by remember { mutableIntStateOf(0) }
    val travelling by remember { derivedStateOf { frame.intValue; !motion.docked } }
    val active = expanded || travelling
    // Wake against the last intent actually stepped, including A → B → A between frames.
    LaunchedEffect(motion) {
        fun intent() = BubbleIntent(openIntent, dockIntent, pressed, geometry, gate?.pinned == true)
        var stepped: BubbleIntent? = null
        while (isActive) {
            snapshotFlow { intent() }.first { it != stepped }
            var last = System.nanoTime()
            do {
                val moving = withFrameNanos { now ->
                    val dt = (now - last) / 1e9f
                    last = now
                    val current = intent()
                    stepped = current
                    if (!current.pinned && !current.pressed) flight.targetLeft = current.left
                    val flying = !current.pinned && flight.step(dt)
                    val morphing = motion.step(current.open, dt, current.pressed)
                    frame.intValue++
                    flying || morphing
                }
            } while (moving)
        }
    }

    val source = playback.artSource()
    val cover by key(source?.owner) {
        produceState<ImageBitmap?>(null, bridge, source) {
            value = source?.let { bridge.readArtwork(it) { bytes -> decodeCover(bytes, 192) } }
        }
    }
    val panelSide = MusicBarPanelSide
    val panelBottom = 20.dp
    val dockSide = MusicBarDockSide
    val dockBottom = MusicBarDockBottom
    val density = LocalDensity.current
    var panelHeight by remember { mutableStateOf(108.dp) }
    val clearance = LocalPlayerClearance.current
    var available by remember { mutableStateOf(Rect.Zero) }
    var seekHeld by remember { mutableStateOf(false) }
    DisposableEffect(clearance) { onDispose { clearance?.panel = Rect.Zero } }

    val navigation = LocalCornerNavigation.current
    BackHandler(expanded && navigation?.modalOpen != true && navigation?.insideAlbum != true) { onExpanded(false) }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(start = panelSide, end = panelSide, bottom = panelBottom)
        .onGloballyPositioned { available = it.boundsInRoot() }) {
        val extraWidth = (maxWidth - 420.dp).coerceAtLeast(0.dp)
        val panelWidth = maxWidth.coerceAtMost(420.dp)
        SideEffect {
            if (clearance != null) with(density) {
                clearance.gap = 8.dp.toPx()
                clearance.ceiling = available.top
                val left = if (dockLeft) available.left else available.right - panelWidth.toPx()
                clearance.panel = if (expanded && !available.isEmpty) Rect(left,
                    available.bottom - (panelHeight + SeekHeight + SeekGap).toPx(),
                    left + panelWidth.toPx(), available.bottom) else Rect.Zero
            }
        }
        CornerBubble(
            modifier = Modifier.align(AbsoluteAlignment.BottomRight).absoluteOffset {
                frame.intValue
                val dockWeight = 1f - motion.progress
                val side = flight.rightShare
                val lift = 16.dp * (4f * side * (1f - side))
                IntOffset(
                    ((panelSide - dockSide) * ((2f * side - 1f) * dockWeight) - extraWidth * (1f - side)).roundToPx(),
                    ((panelBottom - dockBottom - lift) * dockWeight).roundToPx(),
                )
            },
            motion = motion, frame = frame, active = active, right = true,
            frost = frost, dark = LocalFoliaDark.current, avatar = cover,
            label = "展开播放条", title = null, artworkPlaceholder = { RecordSleeve() },
            onPressed = { pressed = it },
            onGeometryChanged = { Snapshot.withoutReadObservation { geometry++ } },
            onClick = { interaction++; onExpanded(true) },
        ) {
            // Keep the outgoing body alive until the same outline has finished docking.
            val recover = if (expanded) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    // Reverse on contact even over a transport button that is folding away.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                    onExpanded(true)
                }
            }
            BubblePlayerPanel(
                playback, cover, motion, frame, expanded,
                onFold = { onExpanded(false) },
                onPrevious = { interaction++; action { bridge.previous() } },
                onToggle = { interaction++; onToggle() },
                onNext = { interaction++; action { bridge.next() } },
                onOpenLyrics = { navigation?.openPlayerLyrics(); onExpanded(false) },
                lyricsLabel = bridge.t("localMusic.lyrics"),
                modifier = Modifier.musicBarIdleTimeout(expanded && !pressed && !seekHeld, interaction) { onExpanded(false) }
                    .then(recover).onSizeChanged { panelHeight = with(density) { it.height.toDp() } },
            )
        }
        if (active) key(playback.queueGeneration,playback.queueIndex,source?.owner) {
            BubbleSeekBar(playback, expanded, frost,
                Modifier.align(AbsoluteAlignment.BottomRight).width(panelWidth).absoluteOffset {
                    frame.intValue
                    val side = flight.rightShare
                    val p = motion.progress
                    val top = motion.layoutHeight - motion.rect().top
                    IntOffset((-extraWidth * (1f-side)).roundToPx(),
                        (-top.dp - SeekGap*p + SeekHeight*(1f-p)).roundToPx())
                }.graphicsLayer {
                    frame.intValue
                    val p = motion.progress
                    alpha = p*p
                    scaleX = .85f+.15f*p
                    scaleY = .65f+.35f*p
                },
                held = { seekHeld = it; if (!it) interaction++ },
                commit = { seconds ->
                    interaction++
                    action { bridge.seekQueueItem(seconds, playback.queueGeneration, playback.queueIndex) }
                })
        }
    }
}

private data class BubbleIntent(val open: Boolean, val left: Boolean, val pressed: Boolean, val geometry: Int, val pinned: Boolean)

@Composable
private fun RecordSleeve() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxSize().background(colors.primaryContainer, CircleShape)) {
        val radius = size.minDimension * .33f
        drawCircle(colors.onPrimaryContainer.copy(alpha = .16f), radius)
        drawCircle(colors.onPrimaryContainer.copy(alpha = .5f), radius * .82f, style = Stroke(1.dp.toPx()))
        drawCircle(colors.primary, radius * .26f)
    }
}

@Composable
private fun BubblePlayerPanel(
    playback: PlaybackSnapshot, cover: ImageBitmap?, motion: CornerMotion, frame: State<Int>, enabled: Boolean,
    onFold: () -> Unit, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, modifier: Modifier,
    onOpenLyrics: () -> Unit, lyricsLabel: String,
) {
    val colors = MaterialTheme.colorScheme
    val playing = playback.state == TransportState.PLAYING
    val hasTrack = playback.queueLength > 0
    val credits = remember(playback.artist, playback.album) {
        listOf(playback.artist, playback.album).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
    // Both rows keep their final text layout while the original content fling brings them out of the corner.
    Layout(modifier = modifier, content = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "收起播放条", onClick = onFold)) {
                if (cover != null) Image(cover, "专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else RecordSleeve()
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playback.title.ifBlank { "暂无播放" }, color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (credits.isNotEmpty()) Text(credits, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HandedTransportRow(motion, frame, preview = { clip ->
            Box(clip.heightIn(min = 48.dp).clickable(enabled = enabled && hasTrack,
                role = Role.Button, onClickLabel = lyricsLabel, onClick = onOpenLyrics),
                contentAlignment = Alignment.CenterStart) {
                Text(if (hasTrack) playback.currentLyric ?: "暂无歌词" else "",
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }) {
            PlayerTransport(R.drawable.salam_transport_previous_icon, "上一首", enabled && playback.hasPrevious,
                onPrevious, Modifier.size(48.dp), direction = -1, compactWeight = 1f,
                available = playback.hasPrevious, compactIconHeight = 15.5.dp)
            PlayerTransport(if (playing) R.drawable.salam_transport_play else R.drawable.salam_transport_pause,
                if (playing) "暂停" else "播放", enabled && hasTrack, onToggle,
                Modifier.size(48.dp).background(colors.primary.copy(alpha = .10f), CircleShape),
                compactWeight = 1f, available = hasTrack)
            PlayerTransport(R.drawable.salam_transport_next, "下一首", enabled && playback.hasNext,
                onNext, Modifier.size(48.dp), direction = 1, compactWeight = 1f,
                available = playback.hasNext, compactIconHeight = 15.5.dp)
        }
    }) { children, constraints ->
        val edge = 14.dp.roundToPx()
        val top = 12.dp.roundToPx()
        val gap = 4.dp.roundToPx()
        val w = constraints.maxWidth
        val rowWidth = (w - 2 * edge).coerceAtLeast(0)
        val rows = children.map { it.measure(Constraints.fixedWidth(rowWidth)) }
        val height = constraints.constrainHeight(top + rows.sumOf { it.height } + gap + 8.dp.roundToPx())
        layout(w, height) {
            var y = top
            for (row in rows) {
                val cy = (y + row.height * .5f) / density
                row.placeWithLayer(edge, y) {
                    frame.value
                    val pose = motion.content(w * .5f / density, cy)
                    translationX = pose.x * density
                    translationY = pose.y * density
                    scaleX = pose.scale
                    scaleY = pose.scale
                    alpha = pose.alpha
                }
                y += row.height + gap
            }
        }
    }
}


/** Keep one transport group with live hit bounds and a single lyric layout. The preview
 * fades into the space the controls vacate, without lettering passing underneath them. */
@Composable
private fun HandedTransportRow(motion: CornerMotion, frame: State<Int>,
    preview: @Composable (Modifier) -> Unit, controls: @Composable RowScope.() -> Unit) {
    fun side(): Float {
        frame.value
        return (motion.flight?.rightShare ?: 1f).coerceIn(0f, 1f)
    }
    Layout(content = {
        Row(verticalAlignment = Alignment.CenterVertically, content = controls)
        preview(Modifier.drawWithContent {
            val right = side()
            val free = ((size.width + 4.dp.toPx()) * maxOf(right, 1f - right) - 4.dp.toPx()).coerceIn(0f, size.width)
            if (right >= .5f) clipRect(right = free) { this@drawWithContent.drawContent() }
            else clipRect(left = size.width - free) { this@drawWithContent.drawContent() }
        })
    }) { children, constraints ->
        val width = constraints.maxWidth
        val buttons = children[0].measure(constraints.copy(minWidth = 0))
        val travel = width - buttons.width
        val lyric = children[1].measure(Constraints.fixedWidth((travel - 4.dp.roundToPx()).coerceAtLeast(0)))
        val height = maxOf(lyric.height, buttons.height)
        layout(width, height) {
            val right = side()
            buttons.place((travel * right).roundToInt(), (height - buttons.height) / 2, zIndex = 1f)
            lyric.placeWithLayer(if (right >= .5f) 0 else width - lyric.width, (height - lyric.height) / 2) {
                alpha = cornerSmooth(kotlin.math.abs(2f * side() - 1f))
            }
        }
    }
}
