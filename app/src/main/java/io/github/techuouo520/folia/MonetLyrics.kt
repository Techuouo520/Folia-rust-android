package io.github.techuouo520.folia

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.techuouo520.folia.bridge.MonetTickerLine
import io.github.techuouo520.folia.bridge.MonetOptions
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
internal fun MonetLyrics(bridge: FoliaBridge, playback: PlaybackSnapshot, dark: Boolean,
    selected: Boolean, radio: Boolean = false, active: Boolean = true,
    controlsVisible: Boolean = true, onStatus: (Int) -> Unit = {}) {
    val identity = playback.artSource()?.owner
    val radioOwner by produceState<ArtworkOwner?>(null, bridge, radio, identity, playback.queueGeneration) {
        value = null
        if (radio && identity != null) {
            val matches = withContext(Dispatchers.IO) {
                runCatching { bridge.personalFm().any { it.artOwner() == identity } }.getOrDefault(false)
            }
            if (matches) value = identity
        }
    }
    var admitted by remember { mutableStateOf(false) }
    val show = admitted || selected || (radio && identity != null && radioOwner == identity)
    LaunchedEffect(show) { if (show) admitted = true }
    val source = identity.takeIf { show }
    var status by remember(source, dark) { mutableIntStateOf(0) }
    val ground = if (dark) Color(0xFF09090B) else Color(0xFFF9F7F3)
    val context = LocalContext.current
    val rendering = active && !LocalPlayerCoversPage.current
    val settings = remember(context) { MonetPreferences(context) }
    var posterView by remember { mutableStateOf<MonetPosterView?>(null) }
    Box(Modifier.fillMaxSize().background(ground)) {
        AndroidView(factory = { MonetPosterView(it, bridge).also { view -> posterView = view } }, modifier = Modifier.fillMaxSize(),
            onRelease = { if (posterView === it) posterView = null; it.release() }, update = {
                it.poster.onPortraitOffset = { x -> settings.update(settings.value.copy(portraitOffset = x)) }
                it.report = { result -> status = result; onStatus(result) }
                it.update(source, playback, dark, settings.value, rendering)
            })
        if (source == null || status != 1) {
            val background = if (source != null && status == 2) Color.Transparent else ground
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when {
                        source == null -> bridge.t("ui.noTrack")
                        status == 2 -> bridge.t("ui.noLyricsAvailable")
                        status == 3 -> bridge.t("playlist.loadFailed").replace("{{error}}", bridge.t("ui.visualizer"))
                        status == 5 -> bridge.t("playlist.loadFailed").replace("{{error}}", bridge.t("localMusic.lyrics"))
                        else -> bridge.t("home.loading")
                    }, color = if (dark) Color(0xFFB8B8BD) else Color(0xFF625C56), fontSize = 15.sp)
                    if (source != null && (status == 3 || status == 5)) {
                        TextButton(onClick = { posterView?.retryLyrics() }) { Text(bridge.t("search.retry")) }
                    }
                }
            }
        }
        if (controlsVisible && rendering && source != null && (status == 1 || status == 2)) SponsorFooter(bridge)
        if (controlsVisible) MonetMenu(settings, dark)
    }
}

/** Surface lifecycle and input stay ordered on one worker. Compose can interrupt the
 * camera at any time without waiting for GPU creation or a pending present. */
internal class MonetView(context: Context, private val bridge: FoliaBridge) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private data class Scene(val song: ArtworkOwner?, val dark: Boolean, val playing: Boolean, val options: MonetOptions, val active: Boolean)
    private var requested: Scene? = null
    private var songGeneration = 0L
    private var serial = 0
    private var disposed = false
    private val thread = HandlerThread("Folia Monet").apply { start() }
    private val worker = Handler(thread.looper)
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    var onRail: (List<Float>?, List<MonetTickerLine>?) -> Unit = { _, _ -> }
    var onPortraitOffset: (Float) -> Unit = {}
    var report: (Int) -> Unit = {}

    // Everything below belongs to this surface's Looper, including its vsync registration.
    private val clock by lazy { Choreographer.getInstance() }
    private var ready = false
    private var tickerReady = false
    private var visible = false
    private var scene: Scene? = null
    private var workerSongGeneration = 0L
    private var selected: ArtworkOwner? = null
    private var applied: MonetOptions? = null
    private var surfaceId = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastStatus = -1
    private var queued = false
    private var delayed = false
    private var interactiveUntil = 0L
    private val vsync = Choreographer.FrameCallback { render() }

    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var scrolling = false
    private var dragging = false

    private external fun attach(surface: Surface): Boolean
    private external fun detach()
    private external fun pointer(kind: Int, x: Float, y: Float, delta: Float)

    private external fun drawFrame(width: Int, height: Int, density: Float, dark: Boolean): Int

    init { surfaceTextureListener = this }

    fun update(owner: ArtworkOwner?, dark: Boolean, playing: Boolean, options: MonetOptions, active: Boolean) {
        val next = Scene(owner, dark, playing, options, active)
        if (requested == next || disposed) return
        if (requested?.song != owner) songGeneration++
        val generation = songGeneration
        requested = next
        worker.post {
            workerSongGeneration = generation
            if (scene?.song != owner) selected = null
            if (scene?.song != owner || scene?.dark != dark) lastStatus = -1
            scene = next
            wake()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!disposed) worker.post { visible = isVisible; wake() }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        attachSurface(texture, width, height, false)
    }

    private fun attachSurface(texture: SurfaceTexture, width: Int, height: Int, retryLyrics: Boolean) {
        if (disposed) return
        val id = ++serial
        val surface = Surface(texture)
        val shown = isShown && windowVisibility == VISIBLE
        worker.post {
            surfaceId = id
            selected = null
            applied = null
            lastStatus = -1
            surfaceWidth = width
            surfaceHeight = height
            visible = shown
            ready = try {
                stopSurface()
                if (retryLyrics) {
                    scene?.song?.let { bridge.retryMonetLyrics(it.id, it.provider, it.song) }
                }
                if (attach(surface)) {
                    bridge.installMonetMaskFilter(MonetGlyphBlur())
                    true
                } else false
            } catch (error: Exception) {
                android.util.Log.e("FoliaMonet", "Could not prepare lyrics surface", error)
                false
            } finally { surface.release() }
            if (ready) wake() else post { if (serial == id && !disposed) report(3) }
        }
    }

    fun retryLyrics() {
        val texture = surfaceTexture ?: return
        attachSurface(texture, width, height, true)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        worker.post { surfaceWidth = width; surfaceHeight = height; wake() }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        ++serial
        if (!worker.post { stopSurface(); texture.release() }) texture.release()
        return false
    }

    private fun stopSurface() {
        if (queued) clock.removeFrameCallback(vsync)
        queued = false
        ready = false
        detach()
    }

    private fun wake() {
        if (queued && !delayed && ready && visible && scene?.song != null && scene?.active == true) return
        if (queued) clock.removeFrameCallback(vsync)
        queued = false
        schedule(0)
    }

    private fun schedule(delay: Long) {
        if (!ready || !visible || scene?.active != true || scene?.song == null || surfaceWidth <= 0 || surfaceHeight <= 0 || queued) return
        queued = true
        delayed = delay > 0
        clock.postFrameCallbackDelayed(vsync, delay)
    }

    fun setTickerReady(ready: Boolean) {
        worker.post {
            if (tickerReady != ready) {
                tickerReady = ready
                wake()
            }
        }
    }

    private fun render() {
        queued = false
        val current = scene ?: return
        val owner = current.song ?: return
        val id = surfaceId
        val generation = workerSongGeneration
        val result = try {
            if (selected != owner) {
                bridge.selectMonet(owner.id, owner.provider, owner.song)
                selected = owner
            }
            if (applied != current.options) {
                bridge.configureMonet(current.options)
                applied = current.options
            }
            drawFrame(surfaceWidth, surfaceHeight, density, current.dark)
        } catch (e: Exception) {
            android.util.Log.e("FoliaMonet", "Could not draw lyrics", e)
            3
        }
        val status = result and 15
        val lines = if (result and 16 != 0) bridge.monetTickerLines() else null
        val bounds = if (result and 32 != 0) bridge.monetTickerBounds() else null
        val offset = result ushr 8
        if ((status != 4 && status != lastStatus) || offset != 0 || bounds != null || lines != null) {
            if (status != 4) lastStatus = status
            post {
                if (id == serial && generation == songGeneration && !disposed && requested?.song == owner) {
                    if (bounds != null || lines != null) onRail(bounds, lines)
                    if (status != 4 && requested?.dark == current.dark) report(status)
                    if (offset != 0) onPortraitOffset((1 - offset).toFloat())
                }
            }
        }
        schedule(when {
            status == 3 || status == 5 -> 1000L
            status == 0 -> 80L
            status == 1 && !tickerReady -> 80L
            !current.playing && SystemClock.uptimeMillis() > interactiveUntil -> 80L
            else -> 0L
        })
    }

    fun seek(ms: Long) {
        val song = requested?.song ?: return
        worker.post { if (scene?.song == song) bridge.seek(ms / 1000.0) }
    }

    private fun send(kind: Int, x: Float, y: Float, dy: Float = 0f) {
        worker.post {
            if (ready) {
                pointer(kind, x / density, y / density, dy / density)
                interactiveUntil = SystemClock.uptimeMillis() + 3200
                wake()
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=x; startY=y; lastY=y; scrolling=false; dragging=false }
            MotionEvent.ACTION_MOVE -> {
                if (!scrolling && !dragging) {
                    if (abs(y-startY) > slop && abs(y-startY) > abs(x-startX)) scrolling=true
                    else if (abs(x-startX) > slop) { dragging=true; send(0,startX,startY) }
                }
                if (scrolling) send(3,x,y,y-lastY) else if (dragging) send(2,x,y)
                lastY=y
            }
            MotionEvent.ACTION_UP -> if (!scrolling) {
                if (!dragging) send(0,x,y)
                send(1,x,y)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) send(1,x,y)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun release() {
        disposed = true
        ++serial
        worker.post { stopSurface(); thread.quitSafely() }
    }
}
