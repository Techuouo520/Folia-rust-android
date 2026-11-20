package io.github.techuouo520.folia

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.spatial.AlbumPixelScan
import io.github.techuouo520.folia.spatial.CollectionMotion
import kotlinx.coroutines.flow.collectLatest

internal class BubbleLyricsEntry(val source: Rect) {
    var openRequested by mutableStateOf(true)
    var progress by mutableFloatStateOf(0f)
}

internal class BubbleLyricsState(val preview: GraphicsLayer) {
    var previewBounds by mutableStateOf(Rect.Zero)
    var viewport by mutableStateOf(Rect.Zero)
    var entry by mutableStateOf<BubbleLyricsEntry?>(null)
        private set

    fun open() {
        entry?.let { it.openRequested = true; return }
        focus()?.let { entry = BubbleLyricsEntry(it) }
    }

    fun focus(): Rect? {
        if (previewBounds.isEmpty || viewport.isEmpty) return null
        val bounds = previewBounds.intersect(viewport)
        if (bounds.isEmpty) return null
        return Rect(
            (bounds.left - viewport.left) / viewport.width,
            (bounds.top - viewport.top) / viewport.height,
            (bounds.right - viewport.left) / viewport.width,
            (bounds.bottom - viewport.top) / viewport.height,
        )
    }

    fun returned(owner: BubbleLyricsEntry) {
        if (entry === owner && !owner.openRequested) entry = null
    }
}

@Composable
internal fun rememberBubbleLyrics(): BubbleLyricsState {
    val preview = rememberGraphicsLayer()
    return remember(preview) { BubbleLyricsState(preview) }
}

/** The page and the portal share one frost source; the transport stays in its own sibling. */
@Composable
internal fun BubbleLyricsHost(
    state: BubbleLyricsState, bridge: FoliaBridge, playback: PlaybackSnapshot,
    frost: FrostBackdrop, preload: Boolean, content: @Composable () -> Unit,
) {
    val entry = state.entry
    val covered by remember(state) { derivedStateOf {
        state.entry?.let { it.openRequested && it.progress == 1f } == true
    } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { state.viewport = it.boundsInRoot() }.frostSource(frost)) {
        CompositionLocalProvider(LocalPlayerCoversPage provides covered) { content() }
        val portal = if (entry == null) Modifier else key(entry) { bubbleLyricsTransition(entry, state) }
        // The prepared native page stays in the same composition slot across opening and return.
        PlayerLyricsPage(bridge, playback, open = entry != null, preload = preload, modifier = portal)
    }
}

@Composable
private fun bubbleLyricsTransition(entry: BubbleLyricsEntry, state: BubbleLyricsState): Modifier {
    val navigation = LocalCornerNavigation.current
    DisposableEffect(entry, navigation) {
        navigation?.attach(entry)
        onDispose { navigation?.detach(entry) }
    }
    LaunchedEffect(entry) {
        val motion = CollectionMotion(12.0, damping = 1.2)
        snapshotFlow { entry.openRequested }.collectLatest { open ->
            var last = System.nanoTime()
            do {
                val moving = withFrameNanos { now ->
                    val moving = motion.step(if (open) 1.0 else 0.0, (now-last)/1e9)
                    last = now
                    entry.progress = motion.position.toFloat()
                    moving
                }
            } while (moving)
            if (!open) state.returned(entry)
        }
    }
    val dark = LocalFoliaDark.current
    val clusters = LocalArtworkClusters.current
    val fallback = MaterialTheme.colorScheme.primary
    val pixelColors = remember(clusters, fallback) {
        List(3) { i -> clusters?.getOrNull(i*4)?.let { Color(it) } ?: fallback }
    }
    val ground = if (dark) Color(0xFF09090B) else Color(0xFFF9F7F3)
    return Modifier.fillMaxSize().pointerInput(entry) { detectTapGestures {} }.drawWithCache {
        val focus = state.focus() ?: entry.source
        val world = obtainGraphicsLayer()
        val artwork = obtainGraphicsLayer()
        val lyrics = obtainGraphicsLayer()
        val portal = obtainGraphicsLayer()
        val face = Path().apply { addRect(focus) }
        val scan = AlbumPixelScan(size, focus, artwork)
        val pose = FloatArray(8)
        val columns = 32
        val rows = (columns * size.height / size.width).roundToInt().coerceIn(8, 96)
        val pixels = FloatArray(columns * rows * 6)
        val reveal = Path()
        val source = Rect(focus.left * size.width, focus.top * size.height,
            focus.right * size.width, focus.bottom * size.height)
        onDrawWithContent {
            val p = entry.progress
            if (p == 1f) {
                drawContent()
            } else if (p > 0f) {
                MotionKernel.lyricsPortalPose(pose, p, focus.left, focus.top,
                    focus.right, focus.bottom)
                // Only the preview display list enters the effect, never a previous transition frame.
                world.record {
                    drawRect(ground, source.topLeft, source.size)
                    withTransform({
                        translate(source.left, source.top)
                        scale(source.width / state.preview.size.width.coerceAtLeast(1),
                            source.height / state.preview.size.height.coerceAtLeast(1), Offset.Zero)
                    }) { drawLayer(state.preview) }
                }
                lyrics.record { this@onDrawWithContent.drawContent() }
                lyrics.alpha = pose[6]
                portal.record {
                    drawRect(ground)
                    val center = Offset((pose[0]+pose[2])*size.width*.5f, (pose[1]+pose[3])*size.height*.5f)
                    withTransform({
                        translate(center.x-source.center.x*pose[4], center.y-source.center.y*pose[4])
                        scale(pose[4], pose[4], Offset.Zero)
                    }) {
                        drawLayer(world)
                        with(scan) { drawScan(world, face, focus, ground, pose[5]) }
                    }
                    drawLayer(lyrics)
                }
                // The moving frontier itself consists of released pixels. No growing rectangular
                // background may cover this mask or hide the detached pieces.
                val count = MotionKernel.lyricsPixels(pixels, columns, rows, p,
                    focus.center.x, focus.center.y, size.height / size.width)
                reveal.reset()
                var at = 0
                repeat(count) {
                    reveal.addRect(Rect(pixels[at]*size.width, pixels[at+1]*size.height,
                        pixels[at+2]*size.width, pixels[at+3]*size.height))
                    at += 6
                }
                portal.alpha = pose[7]
                clipPath(reveal) { drawLayer(portal) }
                // Only the unjoined frontier carries album colors; the settled lyric page is clear.
                at = 0
                repeat(count) {
                    val alpha = pixels[at+4] * pose[7]
                    if (alpha > 0f) {
                        val left = pixels[at]*size.width
                        val top = pixels[at+1]*size.height
                        drawRect(pixelColors[pixels[at+5].toInt()].copy(alpha = alpha),
                            Offset(left, top), androidx.compose.ui.geometry.Size(
                                pixels[at+2]*size.width-left, pixels[at+3]*size.height-top))
                    }
                    at += 6
                }
            }
        }
    }
}
