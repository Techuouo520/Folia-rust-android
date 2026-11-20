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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.techuouo520.folia.spatial.drawSceneGlyph
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.PlaybackIssue
import io.github.techuouo520.folia.bridge.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
    action: (() -> Unit) -> Unit, dockLeft: Boolean, lyrics: BubbleLyricsState,
) {
    val gate = LocalGripGate.current
    val viewport = LocalLyricsViewport.current
    val navigation = LocalCornerNavigation.current
    val fused = viewport.landscape && navigation?.lyricsRequested == true
    val flight = remember { DockFlight(dockLeft) }
    val motion = remember { CornerMotion(bottomOrigin = true, initiallyOpen = expanded, flight = flight) }
    val frame = remember { mutableIntStateOf(0) }
    frame.intValue
    val normalWidth = (viewport.width-viewport.left-viewport.right-20f).coerceIn(50f,420f)
    val side = flight.rightShare
    val dockWeight = 1f-motion.progress
    val normalLeft = viewport.left+10f +
        (viewport.width-viewport.left-viewport.right-20f-normalWidth)*side - 14f*(2f*side-1f)*dockWeight
    val normalBottom = viewport.height-viewport.bottom-20f - (8f+16f*4f*side*(1f-side))*dockWeight
    val presentation = rememberPlayerReflow(if (fused)
        PlayerReflowTarget(viewport.cardX,viewport.cardY+viewport.cardHeight,viewport.cardWidth,1f)
        else PlayerReflowTarget(normalLeft,normalBottom,normalWidth,0f))
    val coverFrost=rememberFrostBackdrop()
    val pose by presentation
    DisposableEffect(navigation,presentation,coverFrost) {
        navigation?.playerPresentation=presentation
        navigation?.cardFrost=coverFrost
        onDispose { navigation?.let { if(it.playerPresentation === presentation) { it.playerPresentation=null; it.cardFrost=null } } }
    }
    val fusion = rememberUpdatedState(pose.fusion.coerceIn(0f,1f))
    val reflowing = fused || fusion.value > .0001f
    // Return the same panel to portrait before resuming the original dock intention.
    val opened = expanded || reflowing
    val openIntent by rememberUpdatedState(opened)
    val dockIntent by rememberUpdatedState(dockLeft)
    var pressed by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var geometry by remember { mutableIntStateOf(0) }
    val travelling by remember { derivedStateOf { frame.intValue; !motion.docked } }
    val active = opened || travelling
    val lyricsHeld = lyrics.entry != null
    PinMusicBarSide(lyricsHeld || reflowing)
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
            value = source?.let { bridge.readArtwork(it) { bytes -> decodeCover(bytes, 512) } }
        }
    }
    val providerNames by produceState<Map<String, String>>(emptyMap(), bridge) {
        value = withContext(Dispatchers.IO) {
            runCatching { bridge.platforms().associate { it.id to it.shortName } }.getOrDefault(emptyMap())
        }
    }
    val issueText = when (playback.issue) {
        PlaybackIssue.SUBSCRIPTION_REQUIRED -> bridge.t("status.songSubscriptionRequired")
            .replace("{{provider}}", providerNames[playback.provider] ?: playback.provider)
        PlaybackIssue.CUSTOM_SOURCE_UNAVAILABLE -> bridge.t("status.customSourceUnavailable")
        null -> null
    }
    val previewText = when {
        playback.queueLength == 0 -> ""
        issueText != null -> issueText
        playback.loading -> bridge.t("home.loading")
        else -> playback.currentLyric ?: bridge.t("ui.noLyricsAvailable")
    }
    val density = LocalDensity.current
    val clearance = LocalPlayerClearance.current
    var seekHeld by remember { mutableStateOf(false) }
    val f = fusion.value
    val artSize = 48f + (pose.width-48f)*f
    val bodyHeight = 120f + (artSize+96f-120f)*f
    SideEffect {
        if (clearance != null) with(density) {
            clearance.gap=8.dp.toPx(); clearance.ceiling=viewport.top.dp.toPx()
            clearance.panel=if (expanded && !reflowing) Rect(pose.left.dp.toPx(),
                (pose.bottom-bodyHeight-SeekHeight.value-SeekGap.value).dp.toPx(),
                (pose.left+pose.width).dp.toPx(),pose.bottom.dp.toPx()) else Rect.Zero
        }
    }
    DisposableEffect(clearance, navigation) { onDispose {
        clearance?.panel=Rect.Zero
    } }
    BackHandler(expanded && !lyricsHeld && navigation?.modalOpen != true && navigation?.insideAlbum != true) { onExpanded(false) }
    Box(Modifier.fillMaxSize()) {
        val panelWidth = pose.width.dp
        CornerBubble(
            modifier = Modifier.absoluteOffset {
                frame.intValue
                IntOffset(pose.left.dp.roundToPx(), (pose.bottom-motion.layoutHeight).dp.roundToPx())
            }.wrapContentSize(Alignment.TopStart, unbounded=true).requiredWidth(panelWidth)
                .heightIn(max=maxOf(viewport.height,pose.width+96f,120f).dp),
            motion = motion, frame = frame, active = active, right = true, liveHeight = true,
            frost = frost, dark = LocalFoliaDark.current, avatar = cover,
            label = "展开播放条", title = null, artworkPlaceholder = { RecordSleeve() },
            onPressed = { pressed = it },
            onGeometryChanged = { Snapshot.withoutReadObservation { geometry++ } },
            onClick = { interaction++; onExpanded(true) },
        ) {
            // Keep the outgoing body alive until the same outline has finished docking.
            val recover = if (opened) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    // Reverse on contact even over a transport button that is folding away.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                    onExpanded(true)
                }
            }
            BubblePlayerPanel(
                playback, cover, motion, frame, opened, lyrics, fusion, coverFrost, canFold = !lyricsHeld && !reflowing,
                onFold = { if (!lyricsHeld && !reflowing) onExpanded(false) },
                onPrevious = { interaction++; action { bridge.previous() } },
                onToggle = { interaction++; onToggle() },
                onNext = { interaction++; action { bridge.next() } },
                lyricsLabel = bridge.t("localMusic.lyrics"), previewText = previewText, hasIssue = issueText != null,
                modifier = Modifier.musicBarIdleTimeout(expanded && !reflowing && !lyricsHeld && !pressed && !seekHeld && !playback.loading, interaction) { onExpanded(false) }
                    .then(recover),
            )
        }
        if (active) key(playback.queueGeneration,playback.queueIndex,source?.owner) {
            BubbleSeekBar(playback, opened, frost,
                Modifier.absoluteOffset {
                    frame.intValue
                    val p = motion.progress
                    val top = motion.layoutHeight-motion.rect().top
                    val gap = (top+SeekGap.value*p-SeekHeight.value*(1f-p))*(1f-f)+4f*f
                    IntOffset((pose.left+14f*f).dp.roundToPx(), (pose.bottom-SeekHeight.value-gap).dp.roundToPx())
                }.graphicsLayer {
                    frame.intValue
                    val p = motion.progress
                    alpha = p*p
                    scaleX = .85f+.15f*p
                    scaleY = .65f+.35f*p
                }.wrapContentSize(Alignment.TopStart, unbounded=true).requiredWidth(panelWidth-28.dp*f),
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
    playback: PlaybackSnapshot, cover: ImageBitmap?, motion: CornerMotion, frame: State<Int>, enabled: Boolean, lyrics: BubbleLyricsState, fusion: State<Float>, coverFrost: FrostBackdrop, canFold: Boolean,
    onFold: () -> Unit, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, modifier: Modifier,
    lyricsLabel: String, previewText: String, hasIssue: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val navigation = LocalCornerNavigation.current
    val backLabel = "返回"
    val playing = playback.state == TransportState.PLAYING || (playback.loading && playback.state != TransportState.PAUSED)
    val hasTrack = playback.queueLength > 0
    val credits = remember(playback.artist, playback.album) {
        listOf(playback.artist, playback.album).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }
    // Both rows keep their final text layout while the original content fling brings them out of the corner.
    Layout(modifier = modifier, content = {
        Box(Modifier.clip(RoundedCornerShape((13f + 13f*fusion.value).dp))
            .clickable(enabled = enabled && canFold, role = Role.Button, onClickLabel = "收起播放条", onClick = onFold)) {
            // Sample only the artwork; neither button can feed its previous glass
            // back into the next blur frame during rotation.
            Box(Modifier.fillMaxSize().frostSource(coverFrost)) {
                if (cover != null) Image(cover, "专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else RecordSleeve()
            }
            // The portrait avatar/back capsule is hidden in landscape. Keep its
            // navigation action inside the same cover card, opposite the settings gear.
            Box(Modifier.align(Alignment.TopStart).padding(LandscapeCardButtonInset).size(LandscapeCardButtonSize)
                .graphicsLayer { alpha=fusion.value }
                .clip(CircleShape)
                .clickable(enabled=fusion.value>.5f, role=Role.Button, onClickLabel=backLabel) { navigation?.back() },
                contentAlignment=Alignment.Center) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha=LandscapeCardGlassAlpha }
                    .then(if(fusion.value>0f) Modifier.frostedBackdrop(coverFrost,LocalFoliaDark.current) else Modifier))
                Canvas(Modifier.size(21.dp)) {
                    withTransform({ scale(size.width/24f,size.height/24f,androidx.compose.ui.geometry.Offset.Zero) }) {
                        drawSceneGlyph(
                            io.github.techuouo520.folia.spatial.SceneGlyph.Back,colors.onSurface)
                    }
                }
            }
        }
        Column(Modifier.graphicsLayer { alpha=1f-fusion.value; clip=true }, verticalArrangement = Arrangement.Center) {
            Text(playback.title.ifBlank { "暂无播放" }, color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (credits.isNotEmpty()) Text(credits, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HandedTransportRow(motion, frame, fusion, preview = { clip ->
            Text(previewText, clip
                    .heightIn(min = 48.dp)
                    .onGloballyPositioned { lyrics.previewBounds = it.boundsInRoot() }
                    .clickable(enabled = enabled && hasTrack && fusion.value < .5f, role = Role.Button, onClickLabel = lyricsLabel) { lyrics.open() }
                    .drawWithContent {
                        lyrics.preview.record { this@drawWithContent.drawContent() }
                        drawLayer(lyrics.preview)
                    }.wrapContentHeight(),
                color = if (hasIssue) colors.error else colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }) {
            PlayerTransport(R.drawable.salam_transport_previous_icon, "上一首", enabled && playback.hasPrevious,
                onPrevious, Modifier.weight(1f).height(48.dp), direction = -1, compactWeight = 1f,
                available = playback.hasPrevious, compactIconHeight = 15.5.dp)
            Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                PlayerTransport(if (playing) R.drawable.salam_transport_play else R.drawable.salam_transport_pause,
                    if (playing) "暂停" else "播放", enabled && hasTrack, onToggle,
                    Modifier.fillMaxSize().background(colors.primary.copy(alpha = .10f), CircleShape),
                    compactWeight = 1f, available = hasTrack)
                if (enabled && hasTrack && playback.loading) {
                    CircularProgressIndicator(Modifier.size(44.dp).clearAndSetSemantics {},
                        color = colors.primary, strokeWidth = 1.5.dp)
                }
            }
            PlayerTransport(R.drawable.salam_transport_next, "下一首", enabled && playback.hasNext,
                onNext, Modifier.weight(1f).height(48.dp), direction = 1, compactWeight = 1f,
                available = playback.hasNext, compactIconHeight = 15.5.dp)
        }
    }) { children, constraints ->
        val f = fusion.value.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b-a)*f
        val edge = 14.dp.toPx()
        val w = constraints.maxWidth
        val artWidth = mix(48.dp.toPx(), w.toFloat()).roundToInt()
        val artHeight = artWidth
        val art = children[0].measure(Constraints.fixed(artWidth, artHeight))
        val creditsX = mix(edge+60.dp.toPx(), edge)
        val creditsWidth = (w-creditsX-edge).roundToInt().coerceAtLeast(0)
        val credits = children[1].measure(Constraints.fixed(creditsWidth, mix(48.dp.toPx(),0f).roundToInt()))
        val transport = children[2].measure(Constraints.fixed((w-2*edge).roundToInt().coerceAtLeast(0), 48.dp.roundToPx()))
        val artY = mix(12.dp.toPx(), 0f)
        val creditsY = mix(12.dp.toPx(), artHeight+4.dp.toPx())
        val controlsY = mix(64.dp.toPx(), artHeight+4.dp.toPx())
        val height = constraints.constrainHeight(mix(120.dp.toPx(), artHeight+96.dp.toPx()).roundToInt())
        layout(w, height) {
            fun androidx.compose.ui.layout.Placeable.at(x: Float, y: Float) {
                placeWithLayer(x.roundToInt(), y.roundToInt()) {
                    frame.value
                    val pose = motion.content((x+width*.5f)/density, (y+this@at.height*.5f)/density)
                    translationX=pose.x*density; translationY=pose.y*density
                    scaleX=pose.scale; scaleY=pose.scale; alpha=pose.alpha
                }
            }
            art.at(mix(edge,0f), artY)
            credits.at(creditsX, creditsY)
            transport.at(edge, controlsY)
        }
    }

}


/** Keep one transport group with live hit bounds and a single lyric layout. The preview
 * fades into the space the controls vacate, without lettering passing underneath them. */
@Composable
private fun HandedTransportRow(motion: CornerMotion, frame: State<Int>, fusion: State<Float>,
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
        val buttons = children[0].measure(Constraints.fixed(minOf(width,144.dp.roundToPx()),48.dp.roundToPx()))
        val travel = width - buttons.width
        val lyric = children[1].measure(Constraints.fixedWidth((travel - 4.dp.roundToPx()).coerceAtLeast(0)))
        val height = maxOf(lyric.height, buttons.height)
        layout(width, height) {
            val right = side()
            buttons.place((travel * (right+(0.5f-right)*fusion.value)).roundToInt(), (height - buttons.height) / 2, zIndex = 1f)
            lyric.placeWithLayer(if (right >= .5f) 0 else width - lyric.width, (height - lyric.height) / 2) {
                alpha = cornerSmooth(kotlin.math.abs(2f * side() - 1f)) * (1f-fusion.value)
            }
        }
    }
}
