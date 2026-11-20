package io.github.techuouo520.folia.spatial

import io.github.techuouo520.folia.LocalGripGate
import io.github.techuouo520.folia.GripGate
import io.github.techuouo520.folia.PinMusicBarSide
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import io.github.techuouo520.folia.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.hypot

import io.github.techuouo520.folia.FoliaApp
import io.github.techuouo520.folia.LocalCornerNavigation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

private const val A = DailySpatialProtocol.ACTION
private const val RH = DailySpatialProtocol.RHINE

/** The only View is the EGL texture endpoint. All visible UI and gestures belong to Compose. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SpatialMap(
    items: List<SpatialItem>, dark: Boolean, playingKey: String?, playing: Boolean,
    source: (String) -> ByteArray?, onPlay: (SpatialItem) -> Unit,
    onToggle: () -> Unit, onNext: (SpatialItem) -> Unit,
    isFavorite: (SpatialItem) -> Boolean, onFavorite: (SpatialItem) -> Unit,
    onDislike: (SpatialItem) -> Unit, modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    onFramePresented: () -> Unit = {},
    initialStyle: Int = 2,
    chrome: Boolean = true,
    interactive: Boolean = true,
    sceneActive: Boolean = true,
    expansionComplete: Boolean = true,
    onPreload: (SpatialItem) -> Unit = {},
    onSceneReady: () -> Unit = {},
    onSceneLost: () -> Unit = {},
    onCollectionVisible: (Boolean) -> Unit = {},
    albumContent: @Composable (String) -> Unit,
    onAlbumVisible: (Boolean) -> Unit = {},
    collectionContent: @Composable (SpatialItem, Int, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _, _ -> },
) {
    val presented=rememberUpdatedState(onFramePresented)
    val sceneReady=rememberUpdatedState(onSceneReady)
    val sceneLost=rememberUpdatedState(onSceneLost)
    val collectionVisible=rememberUpdatedState(onCollectionVisible)
    var readySignalled by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val hdrEnabled=FoliaApp.from(context).displaySettings.hdrEnabled
    val callbacks=rememberUpdatedState(MapActions(onPlay,onToggle,onNext,isFavorite,onFavorite,onDislike,onPreload))
    val controller=remember { MapController(context,source,initialStyle,sceneActive,if(chrome) DailySpatialCovers.INITIAL_PREVIEWS else 48) { callbacks.value } }
    val gripGate = LocalGripGate.current
    SideEffect { controller.gripGate = gripGate }
    PinMusicBarSide(sceneActive && interactive && controller.contextActive)
    DisposableEffect(controller) {
        val app=context.applicationContext
        fun trimMemory() { controller.trimRetained();DailySpatialCovers.trimShared() }
        val memory=object: android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit
            @Deprecated("Only delivered before Android 14")
            override fun onLowMemory() { trimMemory() }
            override fun onTrimMemory(level: Int) {
                if(io.github.techuouo520.folia.shouldTrimSceneMemory(level)) trimMemory()
            }
        }
        app.registerComponentCallbacks(memory)
        onDispose {app.unregisterComponentCallbacks(memory);controller.close()}
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller,lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_START) controller.resume()
            if(event==Lifecycle.Event.ON_STOP) controller.suspendRendering()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(hdrEnabled) {controller.setHdrEnabled(hdrEnabled)}
    val coveredByPlayer = io.github.techuouo520.folia.LocalPlayerCoversPage.current
    // Keep the map surface and camera pose for the return flight, but release its frame budget
    // while the opaque lyrics viewport covers it. Re-enable as soon as the exit intent arrives.
    val coveredByLyrics by remember(controller) { derivedStateOf {
        controller.albumCamera?.let { it.entering && it.progress >= 1f } == true
    } }
    val coveredByCollection by remember(controller) { derivedStateOf {
        controller.entry?.let { it.openRequested && it.progress == 1f } == true
    } }
    LaunchedEffect(sceneActive,coveredByPlayer,coveredByLyrics,coveredByCollection) {
        controller.setVisible(sceneActive && !coveredByPlayer && !coveredByLyrics && !coveredByCollection)
    }
    LaunchedEffect(expansionComplete,sceneActive) { controller.expandCollection(expansionComplete&&sceneActive) }
    LaunchedEffect(controller.entry!=null) { collectionVisible.value(controller.entry!=null) }
    DisposableEffect(Unit) { onDispose { collectionVisible.value(false) } }
    LaunchedEffect(items) { controller.updateItems(items) }
    LaunchedEffect(controller.failure) { if(controller.failure!=null) sceneReady.value() }
    val accent=MaterialTheme.colorScheme.primary.toArgb()
    LaunchedEffect(dark,accent) { controller.renderer.setPalette(dark,accent) }
    LaunchedEffect(playingKey,playing) { controller.setPlayback(playingKey,playing) }
    val captionMeasurer=rememberTextMeasurer(cacheSize=64)
    val captionCache=remember { CaptionCache() }
    DisposableEffect(captionCache) { onDispose { captionCache.close() } }
    val shownItems=if(controller.entry!=null) controller.items else items
    val displayDensity=LocalDensity.current
    SideEffect {
        controller.paintCapture={ bitmap,crop,snapshot ->
            CanvasDrawScope().draw(displayDensity,LayoutDirection.Ltr,androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()),Size(controller.width.toFloat(),controller.height.toFloat())) {
                withTransform({translate(-crop.left.toFloat(),-crop.top.toFloat())}) {
                    drawCardLabels(snapshot,shownItems,dark,captionMeasurer,displayDensity.density,captionCache,true)
                }
            }
        }
    }
    val accessibleLyricsCell by remember(controller) { derivedStateOf {
        controller.drawVersion.intValue
        AlbumLyricsChip.cell(controller.frame)
    } }
    val frame=controller.frame
    val collectionProjection = LocalCollectionEntry.current
    val camera = controller.albumCamera
    val ground = if (dark) Color(0xFF09090B) else Color(0xFFF9F7F3)
    LaunchedEffect(camera != null) { onAlbumVisible(camera != null) }
    val navigation = LocalCornerNavigation.current
    if (camera != null) {
        AnimateAlbumCamera(camera) { controller.finishAlbum(camera) }
        DisposableEffect(camera, navigation, sceneActive) {
            if (sceneActive) navigation?.attach(camera)
            onDispose { navigation?.detach(camera) }
        }
        BackHandler(camera.entering && sceneActive && interactive && !coveredByPlayer && navigation?.modalOpen != true) { camera.entering = false }
    }
    val parentWorld=rememberGraphicsLayer()
    val underWorld=rememberGraphicsLayer()
    BackHandler(camera == null && sceneActive && interactive && !coveredByPlayer && controller.contextActive && controller.entry == null) { controller.command(if(frame[RH]>.5f) 23 else 16) }
    val returning = controller.entry?.openRequested == false || camera?.entering == false
    LaunchedEffect(returning) { if (returning) controller.resumeReturningMap() }
    Box(modifier.fillMaxSize().graphicsLayer { alpha=if(chrome&&controller.ready) controller.presentationAlpha else 1f }.background(if(dark) Color.Black else Color.White).drawWithContent {
        controller.uiDrawVersion.intValue
        drawContent()
        controller.onUiDrawn()
    }) {
        Box(Modifier.fillMaxSize().albumCamera(camera, parentWorld, ground).drawWithContent {
            parentWorld.record {this@drawWithContent.drawContent()}
            underWorld.record {drawLayer(parentWorld)}
            val owner=controller.entry
            val p=owner?.progress ?: 0f
            underWorld.alpha=CollectionMorph.underAlpha(p)
            if(owner!=null && p>0f) {
                // Only one copy owns the selected card during the shot. The source layer stays
                // clean for the moving card; exclusion applies solely to the parent presentation.
                clipPath(owner.outline,ClipOp.Difference) {drawLayer(underWorld)}
            } else drawLayer(underWorld)
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer()) {
        if (sceneActive) AndroidView(
            factory={ ctx -> TextureView(ctx).apply {
                isOpaque=true
                surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,w: Int,h: Int) {
                        controller.width=w;controller.height=h;controller.attached=true
                        controller.surfaceTexture=texture
                        controller.renderer.attach(texture,w,h,true)
                        controller.surfaceDisplay=display
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,w: Int,h: Int) {
                        controller.width=w;controller.height=h;controller.renderer.resize(w,h);controller.resetClock=true;controller.wake()
                    }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        readySignalled=false;sceneLost.value();controller.surfaceDetached();controller.renderer.detach(texture);return false
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                        presented.value()
                        if(!readySignalled&&controller.ready) {
                            readySignalled=true;sceneReady.value()
                        }
                    }
                }
            } },
            modifier=Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().semantics {
            val cell = accessibleLyricsCell
            if (cell >= 0 && interactive && sceneActive && !coveredByPlayer && camera == null &&
                controller.ready && controller.entry == null && !controller.contextActive) {
                customActions = listOf(CustomAccessibilityAction("歌词") { controller.openPlayingLyrics(cell); true })
            }
        }.pointerInteropFilter { if(interactive) controller.touch(it, collectionProjection) else true })
        MapCaptions(controller,shownItems,dark,controller.playingIndex,playing,captionMeasurer,captionCache) { cell,song -> controller.play(cell,song) }
        }
        }
        if (camera != null) {
            // Swallow map gestures inside the lyric viewport; retain app chrome and transport.
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = if (camera.progress > 0f) camera.pose[4] else 0f
            }.background(ground).pointerInput(camera) {
                detectTapGestures { }
            }) {
                key(camera) { albumContent(camera.itemKey) }
            }
        }
        if (camera?.entering != true) Box(Modifier.fillMaxSize().zIndex(if (returning) 2f else 0f)) {
            MapActionsOverlay(controller,frame,shownItems,dark)
        }
        if(chrome && camera?.entering != true && controller.entry?.openRequested != true) {
            if(controller.rhineScene) Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)).padding(start=16.dp,top=84.dp,end=16.dp,bottom=8.dp)) {
                SceneLabel("RHINE LAB",dark,large=true)
                SceneLabel("SYNTHESIZE INFORMATION",dark,muted=true)
                SceneLabel("ANALYSIS  OS",dark)
            }
            SceneIcon(SceneGlyph.Locate,"定位播放",dark,controller.rhineScene,
                Modifier.align(Alignment.BottomEnd).zIndex(if (returning) 2f else 0f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(end=16.dp,bottom=16.dp+bottomInset),enabled=controller.playingIndex>=0,
                onClick={controller.command(14)})
        }
        controller.dust?.let { SpatialDustLayer(it,controller.contextPhase==3) }
        controller.retainedEntry?.let { entry ->
            key(entry.item.key,entry.style) {
                CollectionReveal(entry,controller.entry!=null,dark,parentWorld,onClosed=controller::finishEntry,content=collectionContent)
            }
        }
        // Keep this receiver mounted across the end of the flight so a held drag keeps its owner.
        Box(Modifier.fillMaxSize().zIndex(if (returning) 1f else -1f).pointerInteropFilter { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                (!interactive || !sceneActive || !controller.returning)) false
            else controller.touch(event, collectionProjection)
        })
        if(!controller.ready) Box(Modifier.fillMaxSize().background(if(dark) Color.Black else Color.White))
        if(!controller.ready && chrome) Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            if(controller.failure==null) {
                if(sceneActive) SceneLoading(dark)
            } else {
                SceneLabel(controller.failure!!,dark)
                SceneIcon(SceneGlyph.Retry,"重试",dark,controller.rhineScene,onClick={controller.retry()})
            }
        }
    }
}

private data class MapActions(val play: (SpatialItem)->Unit,val toggle: ()->Unit,val next: (SpatialItem)->Unit,
    val favorite: (SpatialItem)->Boolean,val toggleFavorite: (SpatialItem)->Unit,val dislike: (SpatialItem)->Unit,val preload: (SpatialItem)->Unit)

private class MapController(context: Context,source: (String)->ByteArray?,initialStyle: Int,initiallyVisible: Boolean,private val previewLimit: Int,val actions: ()->MapActions) : AutoCloseable {
    val density=context.resources.displayMetrics.density
    private val main=Handler(Looper.getMainLooper())
    private val state=SpatialNative.dailySpatialCreate()
    private val covers=DailySpatialCovers(context, source)
    val frame=FloatArray(DailySpatialProtocol.FLOATS)
    // These clocks are read only by their draw scopes, never by the page composition.
    val drawVersion = mutableIntStateOf(0)
    val backgroundVersion = mutableIntStateOf(0)
    val uiDrawVersion = mutableIntStateOf(0)
    var menuVersion by mutableIntStateOf(0)
    var presentationAlpha by mutableFloatStateOf(0f)
    var backgroundAlpha by mutableFloatStateOf(0f)
    var backgroundScale by mutableFloatStateOf(1f)
    var rhineScene by mutableStateOf(false)
    var contextActive by mutableStateOf(false)
    var contextPhase by mutableIntStateOf(0)
    var items: List<SpatialItem> = emptyList()
    var ready by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var albumCamera by mutableStateOf<AlbumCamera?>(null)
        private set
    var dust by mutableStateOf<SpatialDust?>(null)
    var entry by mutableStateOf<CollectionEntry?>(null)
        private set
    // One recent GPU world only: each renderer already owns a substantial texture atlas.
    var retainedEntry by mutableStateOf<CollectionEntry?>(null)
        private set
    fun trimRetained() { if(entry==null) retainedEntry=null }
    private var pendingOpen: Pair<Int,SpatialItem>?=null
    var playingIndex by mutableIntStateOf(-1)
    private var playingKey: String?=null
    private var running=false
    var width=0;var height=0;var attached=false;var resetClock=true
    var surfaceTexture: SurfaceTexture? = null
    var surfaceDisplay: android.view.Display?=null
        set(value) { field=value;hdr.setActive(hdrEnabled&&hdrSupported&&active&&visible,value) }
    private var hdrSupported=false
    private var hdrEnabled=FoliaApp.from(context).displaySettings.hdrEnabled
    fun setHdrEnabled(enabled: Boolean) {
        if(hdrEnabled==enabled) return
        hdrEnabled=enabled
        hdr.setActive(hdrEnabled&&hdrSupported&&active&&visible,surfaceDisplay)
    }
    private var visible=initiallyVisible
    private var active=true
    fun resume() { active=true;resetClock=true;hdr.setActive(hdrEnabled&&hdrSupported&&visible,surfaceDisplay);wake() }
    fun setVisible(next: Boolean) {
        if(visible==next) return
        visible=next;resetClock=true
        hdr.setActive(hdrEnabled&&hdrSupported&&active&&visible,surfaceDisplay)
        if(next) wake() else {
            main.removeCallbacks(tick);scheduled=false;waitingForUi=false
        }
    }
    fun suspendRendering() { active=false;resetClock=true;main.removeCallbacks(tick);scheduled=false;waitingForUi=false;hdr.close() }
    fun surfaceDetached() {
        cancelPreviewWarm()
        surfaceTexture=null;attached=false;gpuReady=false;ready=false;inFlight=false;resetClock=true
        main.removeCallbacks(tick);scheduled=false;waitingForUi=false;hdr.close()
    }
    private var closed=false;private var gpuReady=false
    private var previewWarmGeneration = 0
    private var warmingPreviews = false
    private var inFlight=false;private var dirty=false;private var scheduled=false;private var epoch=0
    var paintCapture: ((android.graphics.Bitmap,android.graphics.Rect,FloatArray)->Unit)?=null
    private var submittedCamera: AlbumCamera? = null
    private var capturePending=false
    private var waitingForUi = false
    private var layoutStyle = initialStyle
    private val tick = Runnable { scheduled=false;submit() }
    val renderer: DailySpatialRenderer=DailySpatialRenderer(context,density,covers,object: DailySpatialRenderer.Listener {
        override fun onReady() { if(!closed) { gpuReady=true;inFlight=false;resetClock=true;reveal() } }
        override fun onHdrReady(supported: Boolean) { hdrSupported=supported;hdr.setActive(hdrEnabled&&supported&&active&&visible,surfaceDisplay) }
        override fun onFailure() { if(!closed) { cancelPreviewWarm();gpuReady=false;ready=false;failure="地图渲染初始化失败";inFlight=false } }
        override fun onDirty() { wake() }
        override fun onFrameComplete(values: FloatArray?,swapped: Boolean) {
            inFlight=false
            if(closed||!attached||!active||!visible) { resetClock=true;return }
            if(values==null) { dirty=false;if(ready) wake();return }
            // Publish only completed GL data. Each consumer observes its own state clock.
            val worldChanged = frame[A+124]!=values[A+124] || frame[A+4]!=values[A+4] ||
                frame[A+27]!=values[A+27] || frame[A+23]!=values[A+23] || frame[16]!=values[16] || frame[24]!=values[24] || frame[RH]!=values[RH]
            val menuChanged = menuChanged(frame,values)
            values.copyInto(frame)
            val camera = albumCamera
            if (camera != null && camera === submittedCamera && (camera.entering || camera.face != null)) {
                camera.followFrame(frame, density, width, height, worldChanged, idle = !dirty && frame[0] == 0f)
            }
            presentationAlpha=(frame[1]*frame[A+DailySpatialProtocol.STYLE_ALPHA]).coerceIn(0f,1f)
            rhineScene=frame[RH]>.5f
            contextPhase=frame[A+3].toInt()
            contextActive=contextPhase>0 || frame[A+27]>.5f
            backgroundAlpha=if(frame[1]>=.95f) (frame[15]*(1f-frame[A+1])).coerceIn(0f,1f) else 0f
            backgroundScale=frame[A+127]
            if(worldChanged) backgroundVersion.intValue++
            if(menuChanged) menuVersion++
            drawVersion.intValue++
            val pending=dirty;dirty=false
            pendingOpen?.let { request -> pendingOpen=null;openCollection(request.first,request.second);return }
            if(pending || (frame[0]>0f && swapped)) {
                main.removeCallbacks(tick);scheduled=false;waitingForUi=false;submit()
            } else if(frame[0]>0f) {
                // Match dispatchDraw in the source: UI-only animation cannot spin GL.
                waitingForUi=true;uiDrawVersion.intValue++
            } else { resetClock=true }
        }
    },if(previewLimit<DailySpatialCovers.INITIAL_PREVIEWS) 12 else 96)
    val hdr: DailySpatialHdr=DailySpatialHdr(context,renderer::setHdrHeadroom)
    private val progressive=previewLimit<DailySpatialCovers.INITIAL_PREVIEWS
    init {
        covers.setDirty(::wake);style(initialStyle)
        if(progressive) command(33,0)
    }
    fun expandCollection(expanded: Boolean) { if(progressive) command(33,if(expanded) 1 else 0) }
    private var deferredItems: List<SpatialItem>?=null
    fun updateItems(next: List<SpatialItem>) {
        if(entry!=null||pendingOpen!=null) { deferredItems=next;return }
        if(next==items) return
        cancelPreviewWarm()
        albumCamera?.let { camera ->
            if (next.indexOfFirst { it.key == camera.itemKey } != items.indexOfFirst { it.key == camera.itemKey })
                camera.entering=false
        }
        backgroundVersion.intValue++;drawVersion.intValue++;menuVersion++
        ++epoch;if(capturePending) command(22);renderer.cancelCapture();capturePending=false
        val removed=items.indices.filter { i -> next.none { it.key==items[i].key } }
        val onlyRemoval=next.size<items.size && items.filterIndexed { i,_ -> i !in removed }==next
        val append=next.size>=items.size && next.take(items.size)==items
        items=next;playingIndex=items.indexOfFirst { it.key==playingKey }
        renderer.setTracks(next.toMutableList())
        when {
            onlyRemoval -> removed.asReversed().forEach { command(20,it) }
            append -> command(0,next.size)
            else -> command(10,next.size,0f,playingIndex.toFloat())
        }
        syncPlaybackLayout()
        if(!ready) reveal() else wake()
    }
    fun retry() {
        cancelPreviewWarm()
        failure=null
        surfaceTexture?.let { renderer.attach(it,width,height,true) }
    }
    private fun reveal() {
        if (closed || !gpuReady || !attached || items.isEmpty() || ready || warmingPreviews) return
        val generation = ++previewWarmGeneration
        warmingPreviews = true
        // Cached artwork warms in parallel; it must not gate input or the entry animation.
        ready = true
        command(3, 1)
        val indices = linkedSetOf<Int>()
        val visibleCount = frame[16].toInt().coerceIn(0, DailySpatialProtocol.SLOTS)
        for (slot in 0 until visibleCount) {
            val index = frame[DailySpatialProtocol.HEADER + slot * DailySpatialProtocol.STRIDE].toInt()
            if (index in items.indices) indices.add(index)
        }
        val focus = playingIndex.coerceIn(0, items.lastIndex)
        for (distance in 0 until 48) {
            if (focus + distance in items.indices) indices.add(focus + distance)
            if (focus - distance in items.indices) indices.add(focus - distance)
        }
        covers.warmCached(indices.map { items[it] }) {
            if (closed || generation != previewWarmGeneration) return@warmCached
            warmingPreviews = false
            if (!gpuReady || !attached) return@warmCached
            wake()
        }
    }
    private fun cancelPreviewWarm() {
        previewWarmGeneration++
        warmingPreviews = false
        covers.cancelWarm()
    }
    fun style(style: Int) { layoutStyle=style;command(30,style);renderer.setLayoutStyle(style) }
    fun setPlayback(key: String?,playing: Boolean) {
        playingKey=key;playingIndex=items.indexOfFirst { it.key==key };running=playing
        if(playingIndex>=0 && !progressive) renderer.prioritizeArtwork(playingIndex)
        syncPlaybackLayout()
    }
    private fun syncPlaybackLayout() {
        // Audio confirmation can arrive after the first map frame. The entry owns the visual
        // target until return; the transport and playback metadata always use the real queue.
        val camera = albumCamera
        val focus = if (camera != null && camera.entering) items.indexOfFirst { it.key == camera.itemKey } else -1
        command(2, if (focus >= 0) focus else playingIndex, if (running) 1f else 0f)
    }
    fun command(op: Int,index: Int=0,a: Float=0f,b: Float=0f) {
        if(closed) return
        SpatialNative.dailySpatialCommand(state,op,index,a,b);wake()
    }
    val returning get() = entry?.openRequested == false || albumCamera?.entering == false
    private val worldPaused get() = entry?.openRequested == true ||
        albumCamera?.let { it.entering && it.worldAtRest } == true

    fun resumeReturningMap() {
        entry?.let { owner ->
            if (!owner.openRequested && !owner.parentRestored) {
                owner.parentRestored = true
                command(32)
            }
        }
        wake()
    }
    fun wake() {
        if(closed||!attached||!active||!visible||worldPaused) return
        if(inFlight) { dirty=true;return }
        if(scheduled) return
        waitingForUi=false;scheduled=true;main.post(tick)
    }
    fun onUiDrawn() {
        if(waitingForUi) { waitingForUi=false;wake() }
    }
    private fun submit() {
        if(!ready||!attached||!active||!visible||closed||worldPaused||width<=0||height<=0) {
            resetClock=true;return
        }
        if(inFlight) { dirty=true;return }
        // EGL presentation provides backpressure; retain one frame in flight without an FPS cap.
        submittedCamera=albumCamera
        inFlight=true;renderer.submitMotion(state,width/density,height/density,resetClock);resetClock=false
    }
    private fun menuChanged(previous: FloatArray,next: FloatArray): Boolean {
        val wasVisible=previous[A]>.005f || previous[A+2]>.005f
        val visible=next[A]>.005f || next[A+2]>.005f
        if(wasVisible!=visible || previous[A+3]!=next[A+3] || previous[A+5]!=next[A+5]) return true
        if(!visible) return false
        for(i in 0..25) if(previous[A+i]!=next[A+i]) return true
        for(i in 96..119) if(previous[A+i]!=next[A+i]) return true
        return false
    }
    fun play(cell: Int,song: Int) {
        val item=items.getOrNull(song) ?: return
        if(item.collection) { openCollection(cell,item);return }
        command(1,cell)
        if(song==playingIndex) actions().toggle() else { command(29,song);actions().play(item) }
    }
    private fun playFromDoubleTap(cell: Int, index: Int) {
        val item = items.getOrNull(index) ?: return
        if (!item.collection && !(index == playingIndex && running) && width > 0 && height > 0 && song(cell) == index) {
            albumCamera = AlbumCamera(cell,item.key)
        }
        play(cell,index)
        if (albumCamera != null) {
            if (index == playingIndex) command(29,index)
            syncPlaybackLayout()
        }
    }
    fun openPlayingLyrics(cell: Int) {
        val item = items.getOrNull(playingIndex) ?: return
        if (item.collection || item.key != playingKey || cell < 0 || frame[A+22].toInt() != cell ||
            song(cell) != playingIndex || albumCamera?.entering == true || entry?.openRequested == true || capturePending) return
        val camera = albumCamera?.takeIf { it.cell == cell && it.itemKey == item.key } ?: AlbumCamera(cell,item.key)
        camera.entering = true
        albumCamera = camera
        if (contextActive) command(16)
        camera.followFrame(frame,density,width,height,worldChanged=true,idle=false)
        syncPlaybackLayout()
    }
    fun finishAlbum(camera: AlbumCamera) {
        if (albumCamera !== camera || camera.entering) return
        albumCamera=null
        resetClock=true
        syncPlaybackLayout()
    }
    fun openCollection(cell: Int,item: SpatialItem) {
        entry?.let { current ->
            if(current.item.key==item.key) {
                if (current.parentRestored) { command(31); current.parentRestored = false }
                current.openRequested=true
                return
            }
            if (current.openRequested) return
            // A new card can take ownership while the old card is still folding away.
            resumeReturningMap()
        }
        main.removeCallbacks(longPress);main.removeCallbacks(tick);scheduled=false
        if(inFlight) {pendingOpen=cell to item;return}
        val bounds=collectionBounds(frame,cell,density,width,height)
        actions().preload(item)
        // Queue the bookmark before any playback/catalogue changes under the child scene.
        SpatialNative.dailySpatialCommand(state,31,0,0f,0f)
        entry=CollectionEntry(item,bounds,if(rhineScene) 1 else 2,collectionCorners(frame,cell,density,bounds))
        retainedEntry=entry
        lastTap=0;resetClock=true
    }
    fun finishEntry(owner: CollectionEntry) {
        if(entry!==owner || owner.openRequested) return
        resumeReturningMap()
        entry=null
        deferredItems?.let { deferredItems=null;updateItems(it) }
        resetClock=true
        wake()
    }
    private fun song(cell: Int): Int {
        for(i in 0 until frame[16].toInt().coerceIn(0,DailySpatialProtocol.SLOTS)) {
            val o=32+i*16;if(frame[o+7].toInt()==cell) return frame[o].toInt()
        }
        return -1
    }
    private var downCell=-1;private var pointer=-1
    private var downX=0f;private var downY=0f;private var lastX=0f;private var lastY=0f
    private var dismissing=false
    private var dragging=false;private var pinching=false;private var lifted=false;private var browsing=false
    private var lastTap=0L;private var tapCell=-1;private var tapSong=-1;private var tapX=0f;private var tapY=0f
    private var velocity: VelocityTracker?=null
    private val config=ViewConfiguration.get(context)
    var gripGate: GripGate? = null
    private var gestureDown = Long.MIN_VALUE
    private val longPress=Runnable {
        if(pointer>=0&&!dragging&&!pinching&&downCell>=0) { gripGate?.resolve(gestureDown,false);lifted=true;lastTap=0;command(15,downCell) }
    }
    private val scale=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { main.removeCallbacks(longPress);pinching=true;command(11);return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean { command(12,a=detector.scaleFactor);return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { command(13) }
    })
    private var pressedLyrics = false

    private fun lyricsAt(cell: Int, x: Float, y: Float): Boolean {
        if (cell < 0 || cell != AlbumLyricsChip.cell(frame) || contextActive ||
            items.getOrNull(playingIndex)?.collection != false) return false
        for (slot in 0 until frame[16].toInt()) {
            val o = DailySpatialProtocol.HEADER + slot*DailySpatialProtocol.STRIDE
            if (frame[o+7].toInt() == cell && frame[o].toInt() == playingIndex)
                return AlbumLyricsChip.contains(frame, slot, x/density, y/density)
        }
        return false
    }

    private val pickPoint = FloatArray(2)
    private fun pick(x: Float, y: Float, projection: CollectionEntry?): Int {
        pickPoint[0] = x
        pickPoint[1] = y
        if (projection != null && projection.progress < 1f) {
            projection.screenToPage.mapPoints(pickPoint)
            if (pickPoint[0] !in 0f..projection.visibleWidth || pickPoint[1] !in 0f..projection.visibleHeight) return -1
        }
        albumCamera?.takeIf { !it.entering }?.let { camera ->
            pickPoint[0] = (pickPoint[0] - camera.pose[1] * width) / camera.pose[0]
            pickPoint[1] = (pickPoint[1] - camera.pose[2] * height) / camera.pose[0]
        }
        return SpatialNative.dailySpatialHit(state,pickPoint[0]/density,pickPoint[1]/density)
    }

    fun touch(event: MotionEvent, projection: CollectionEntry?): Boolean {
        if(!ready || capturePending || entry?.openRequested == true || pendingOpen!=null || albumCamera?.entering == true) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (returning) resumeReturningMap()
            if (progressive && projection?.progress != 1f) expandCollection(true)
        }
        val rhine=frame[RH]>.5f
        if(frame[A+3]>0 && event.actionMasked==MotionEvent.ACTION_DOWN) { gripGate?.resolve(event.downTime,false);dismissing=true;command(16);return true }
        if(dismissing) { if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL) dismissing=false;return true }
        if(!rhine) scale.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer=event.getPointerId(0);downX=event.x;downY=event.y;lastX=downX;lastY=downY
                dragging=false;pinching=false;lifted=false
                downCell=pick(downX, downY, projection)
                gestureDown = event.downTime
                pressedLyrics = lyricsAt(downCell, pickPoint[0], pickPoint[1])
                if (pressedLyrics) gripGate?.resolve(gestureDown, false)
                else if (downCell >= 0) gripGate?.defer(gestureDown)
                items.getOrNull(song(downCell))?.takeIf {it.collection}?.let {actions().preload(it)}
                renderer.prioritizeArtwork(song(downCell));main.postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong())
                velocity?.recycle();velocity=VelocityTracker.obtain();velocity?.addMovement(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { gripGate?.resolve(gestureDown,false);main.removeCallbacks(longPress);pinching=true;if(browsing) { command(28);browsing=false } }
            MotionEvent.ACTION_POINTER_UP -> {
                if(event.getPointerId(event.actionIndex)==pointer) {
                    val i=if(event.actionIndex==0) 1 else 0
                    pointer=event.getPointerId(i);lastX=event.getX(i);lastY=event.getY(i);pinching=true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i=event.findPointerIndex(pointer);if(i<0||lifted) return true
                val x=event.getX(i);val y=event.getY(i);velocity?.addMovement(event)
                if(hypot(x-downX,y-downY)>config.scaledTouchSlop) { if (!dragging && !pinching) gripGate?.resolve(gestureDown,!pressedLyrics);dragging=true;main.removeCallbacks(longPress) }
                if(dragging&&!pinching) {
                    if(rhine) { if(!browsing) { command(26);browsing=true };command(27,a=(x-lastX)/density,b=(y-lastY)/density) }
                    else command(4,a=(x-lastX)/density,b=(y-lastY)/density)
                }
                lastX=x;lastY=y
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                gripGate?.resolve(gestureDown,event.actionMasked==MotionEvent.ACTION_UP && !lifted && !pressedLyrics && !pinching)
                if(browsing) { command(28);browsing=false }
                if(pinching&&!rhine) command(13)
                if(event.actionMasked==MotionEvent.ACTION_UP&&!lifted) {
                    if(!dragging&&!pinching) {
                        val twice=lastTap>0 && event.eventTime-lastTap<=ViewConfiguration.getDoubleTapTimeout() && hypot(downX-tapX,downY-tapY)<=config.scaledDoubleTapSlop && (downCell==tapCell||frame[24]>.5f)
                        val selected=items.getOrNull(song(downCell))
                        if (pressedLyrics && pick(event.x, event.y, projection) == downCell &&
                            lyricsAt(downCell, pickPoint[0], pickPoint[1])) {
                            lastTap=0
                            openPlayingLyrics(downCell)
                        } else if(selected?.collection==true) { openCollection(downCell,selected);lastTap=0 }
                        else if(twice) { playFromDoubleTap(tapCell,tapSong);lastTap=0 }
                        else { command(1,downCell);lastTap=event.eventTime;tapCell=downCell;tapSong=song(downCell);tapX=downX;tapY=downY }
                    } else if(!rhine&&!pinching) { velocity?.computeCurrentVelocity(1000);command(5,a=(velocity?.xVelocity ?: 0f)/density,b=(velocity?.yVelocity ?: 0f)/density) }
                }
                if(dragging||pinching||event.actionMasked==MotionEvent.ACTION_CANCEL) lastTap=0
                velocity?.recycle();velocity=null;pointer=-1
            }
        }
        return true
    }
    fun menuMaterial(item: SpatialItem,dark: Boolean): Int {
        val base=if(dark) 0xFF24292F.toInt() else 0xFFF4F6F8.toInt()
        val color=covers.preview(item.coverUrl)?.color ?: return base
        return androidx.core.graphics.ColorUtils.blendARGB(base,color,if(dark) .18f else .12f)
    }
    fun captureDislike() {
        if(capturePending||dust!=null) return
        val item=items.getOrNull(frame[A+5].toInt()) ?: return
        val cell=frame[A+4].toInt();val token=++epoch;capturePending=true;command(21,cell)
        renderer.captureCard(cell,object: DailySpatialRenderer.CaptureListener {
            override fun onCaptured(bitmap: android.graphics.Bitmap?,crop: android.graphics.Rect?,snapshot: FloatArray?,failure: String?) {
                if(closed||token!=epoch) { bitmap?.recycle();return }
                capturePending=false
                if(bitmap==null||crop==null||snapshot==null||failure!=null) { bitmap?.recycle();command(22);return }
                try {
                val outline=android.graphics.Path()
                repeat(32) { n -> val x=snapshot[A+32+n*2]*density-crop.left;val y=snapshot[A+33+n*2]*density-crop.top
                    if(n==0) outline.moveTo(x,y) else outline.lineTo(x,y) }
                outline.close()
                val canvas=android.graphics.Canvas(bitmap);val save=canvas.save();canvas.clipOutPath(outline)
                canvas.drawColor(android.graphics.Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);canvas.restoreToCount(save)
                val clipped=canvas.save();canvas.clipPath(outline)
                paintCapture?.invoke(bitmap,crop,snapshot);canvas.restoreToCount(clipped)
                dust=SpatialDust(bitmap,crop.left.toFloat(),crop.top.toFloat(),density,{
                    actions().dislike(item);command(19)
                },{ dust=null;command(19) })
                command(18)
                } catch(error: Exception) {
                    if(!bitmap.isRecycled) bitmap.recycle()
                    command(22)
                    android.util.Log.e("FoliaSpatial","Dust capture handoff failed",error)
                }
            }
        });wake()
    }
    override fun close() {
        if(closed) return
        closed=true;++epoch;main.removeCallbacksAndMessages(null);hdr.close();covers.close();dust?.close();velocity?.recycle()
        renderer.close { SpatialNative.dailySpatialRelease(state) }
    }
}

@Composable
private fun MapActionsOverlay(controller: MapController,frame: FloatArray,items: List<SpatialItem>,dark: Boolean) {
    controller.menuVersion
    if(frame[A]<=.005f && frame[A+2]<=.005f) return
    val item=items.getOrNull(frame[A+5].toInt()) ?: return
    val favorite=controller.actions().favorite(item)
    val labels=listOf(if(item.collection) "打开" else "播放","下一首播放",if(favorite) "取消收藏" else "收藏","不喜欢")
    val material=Color(controller.menuMaterial(item,dark))
    repeat(4) { i ->
        val o=A+96+i*4;val alpha=frame[A+116+i]
        val split = i == 3 && !item.collection && frame[A+4] == frame[A+22] && frame[A+5].toInt() == controller.playingIndex
        val w = if(split) (frame[o+2]-6f)*.5f else frame[o+2]
        if (split && alpha>.005f) SpatialActionTile(4,"歌词",false,false,material,
            enabled=alpha>.6f&&frame[A+3]==1f&&frame[A+26]<.5f,
            modifier=Modifier.offset((frame[o]+w+6f).dp,frame[o+1].dp)
                .size(w.coerceAtLeast(1f).dp,frame[o+3].coerceAtLeast(1f).dp)
                .graphicsLayer {this.alpha=alpha.coerceIn(0f,1f)}) { controller.openPlayingLyrics(frame[A+4].toInt()) }
        if(alpha>.005f) SpatialActionTile(i,labels[i],item.collection,favorite,material,
            enabled=alpha>.6f&&frame[A+3]==1f&&frame[A+26]<.5f,
            modifier=Modifier.offset(frame[o].dp,frame[o+1].dp)
                .size(w.coerceAtLeast(1f).dp,frame[o+3].coerceAtLeast(1f).dp)
                .graphicsLayer {this.alpha=alpha.coerceIn(0f,1f)}) {
                if(i==3) controller.command(17)
                else { controller.command(16);when(i) {
                    0->if(item.collection) controller.openCollection(frame[A+4].toInt(),item) else controller.actions().play(item)
                    1->controller.actions().next(item)
                    2->controller.actions().toggleFavorite(item)
                } }
        }
    }
    val confirmationAlpha=frame[A+2].coerceIn(0f,1f)
    if(confirmationAlpha>.005f) Row(Modifier.offset((frame[A+18]-52).dp,(frame[A+19]-24).dp)
        .graphicsLayer { alpha=confirmationAlpha;scaleX=frame[A+25];scaleY=scaleX },horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        val enabled=confirmationAlpha>.6f&&frame[A+3]==2f&&frame[A+26]<.5f
        SceneIcon(SceneGlyph.Close,"取消",dark,controller.rhineScene,enabled=enabled,onClick={controller.command(16)})
        SceneIcon(SceneGlyph.Check,"确认不喜欢",dark,controller.rhineScene,enabled=enabled,selected=true,onClick=controller::captureDislike)
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun MapCaptions(controller: MapController,items: List<SpatialItem>,dark: Boolean,current: Int,playing: Boolean,measurer: TextMeasurer,captionCache: CaptionCache,onPlay: (Int,Int)->Unit) {
    val frame=controller.frame
    val density=LocalDensity.current.density
    val playIcon=painterResource(if(dark) R.drawable.spatial_pause else R.drawable.spatial_pause_s)
    val pauseIcon=painterResource(if(dark) R.drawable.spatial_play else R.drawable.spatial_play_s)
    var pressed by remember { mutableIntStateOf(-1) }
    fun buttonAt(x: Float,y: Float): Int {
        if(frame[RH]<.5f || frame[A+3]!=0f || frame[A+27]>.5f) return -1
        repeat(3) { i ->
            val o=RH+25+i*12
            if(frame[o+6]>.005f&&frame[o+8]>.5f) {
                val xx=frame[o+7];val xy=frame[o+9];val yx=frame[o+10];val yy=frame[o+11]
                val det=xx*yy-xy*yx
                if(kotlin.math.abs(det)>.0001f) {
                    val dx=x/density-frame[o+2];val dy=y/density-frame[o+3]
                    val lx=(yy*dx-yx*dy)/det;val ly=(-xy*dx+xx*dy)/det
                    if(lx>=frame[o+4]-52 && lx<=frame[o+4] && ly>=0 && ly<=frame[o+5]) return i
                }
            }
        }
        return -1
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer {
        alpha=controller.backgroundAlpha
        scaleX=controller.backgroundScale;scaleY=scaleX
    }) {
        controller.backgroundVersion.intValue
        captionCache.revision
        drawCardLabels(frame,items,dark,measurer,density,captionCache,backgroundOnly=true)
    }
    Canvas(Modifier.fillMaxSize().pointerInteropFilter { e ->
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed=buttonAt(e.x,e.y);pressed>=0 }
            MotionEvent.ACTION_UP -> { val i=pressed;pressed=-1
                if(i>=0&&buttonAt(e.x,e.y)==i) { val o=RH+25+i*12;onPlay(frame[o].toInt(),frame[o+1].toInt()) };i>=0 }
            MotionEvent.ACTION_CANCEL -> { val held=pressed>=0;pressed=-1;held }
            else -> pressed>=0
        }
    }) {
        val rhine=frame[RH]>.5f
        controller.drawVersion.intValue
        captionCache.revision
        drawCardLabels(frame,items,dark,measurer,density,captionCache,onlyOwner=true)
        if(rhine) repeat(3) { i ->
            val o=RH+25+i*12;val item=items.getOrNull(frame[o+1].toInt()) ?: return@repeat
            if(frame[o+6]<.005f) return@repeat
            val matrix=Matrix().apply {
                this[0,0]=frame[o+7];this[0,1]=frame[o+9];this[1,0]=frame[o+10];this[1,1]=frame[o+11]
                this[3,0]=frame[o+2]*density;this[3,1]=frame[o+3]*density
            }
            val text=measurer.measure(item.name,TextStyle(fontSize=15.sp),maxLines=2,overflow=TextOverflow.Ellipsis,constraints=Constraints(maxWidth=((frame[o+4]-60)*density).toInt().coerceAtLeast(1)))
            withTransform({transform(matrix)}) {
                drawText(text,color=(if(dark) Color.White else Color.Black).copy(alpha=frame[o+6].coerceIn(0f,1f)),topLeft=Offset(0f,(frame[o+5]*density-text.size.height)*.5f))
                withTransform({translate((frame[o+4]-40)*density,(frame[o+5]-28)*density*.5f)}) {
                    if(item.collection) {
                        val ink=(if(dark) Color.White else Color.Black).copy(alpha=frame[o+6].coerceIn(0f,1f))
                        drawLine(ink,Offset(9*density,6*density),Offset(18*density,14*density),2*density,androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(ink,Offset(18*density,14*density),Offset(9*density,22*density),2*density,androidx.compose.ui.graphics.StrokeCap.Round)
                    } else with(if(frame[o+1].toInt()==current&&playing) pauseIcon else playIcon) { draw(Size(28*density,28*density),alpha=frame[o+6].coerceIn(0f,1f)) }
                }
            }
        }
    }
}

private fun DrawScope.drawCardLabels(frame: FloatArray,items: List<SpatialItem>,dark: Boolean,measurer: TextMeasurer,density: Float,cache: CaptionCache,onlyOwner: Boolean=false,backgroundOnly: Boolean=false) {
    val rhine=frame[RH]>.5f
    if(backgroundOnly&&rhine) return
    val count=frame[16].toInt().coerceIn(0,DailySpatialProtocol.SLOTS)
    val slot=frame[A+23].toInt()
    if(onlyOwner && (frame[A+27]<=.5f || slot !in 0 until count)) return
    val start=if(onlyOwner) slot else 0
    val end=if(onlyOwner) slot+1 else count
    val started=System.nanoTime();var built=0;var pending=false
        for(n in start until end) {
            val o=32+n*16;val item=items.getOrNull(frame[o].toInt()) ?: continue
            val owner=frame[A+27]>.5f && n==frame[A+23].toInt()
            if((rhine||onlyOwner)&&!owner || backgroundOnly&&owner) continue
            val w=frame[o+12]*density;val h=frame[o+13]*density
            if(w<12*density||h<12*density) continue
            val alpha=(frame[o+6]*frame[o+9]*(if(backgroundOnly) 1f else frame[15])).coerceIn(0f,1f)
            if(alpha<.005f) continue
            val ink=if(dark||(!rhine&&frame[o+15]>.5f)) Color.White else Color.Black
            // Shape in the native face-space panel, then apply one uniform projection scale.
            // Never reflow against the animated projected height of a lifted Rhine sleeve.
            // Cache native card-space typography, never the camera-dependent projected size.
            val metrics=DailySpatialProtocol.MAP_CAPTIONS+n*16
            val layoutW=(if(rhine) frame[RH+15] else frame[metrics])*density
            val layoutH=(if(rhine) frame[RH+16] else frame[metrics+1])*density
            val bucketScale=if(rhine) 1f else frame[metrics+2].coerceAtLeast(.1f)
            val textWidth=(layoutW/(4*density)).toInt().coerceAtLeast(1)*(4*density).toInt().coerceAtLeast(1)
            val textHeight=(layoutH/(4*density)).toInt().coerceAtLeast(1)*(4*density).toInt().coerceAtLeast(1)
            val cell=frame[o+7].toInt()
            var caption=cache.entries.get(cell)
            if(caption==null || caption.item!=item || caption.width!=textWidth || caption.height!=textHeight || caption.scale!=bucketScale) {
                // Populate newly visible captions during gestures too, within the same frame budget.
                if(owner || (built<4 && (built==0 || System.nanoTime()-started<2_000_000L))) {
            val titleSize=15.5f*bucketScale
            val artistSize=12.5f*bucketScale
            val gap=4*density*bucketScale
            val titleProbe=measurer.measure(item.name,TextStyle(fontSize=titleSize.sp),softWrap=false)
            val artistProbe=measurer.measure(item.singer,TextStyle(fontSize=artistSize.sp),softWrap=false)
            val titleLine=titleProbe.size.height.coerceAtLeast(1).toFloat()
            val artistLine=artistProbe.size.height.coerceAtLeast(1).toFloat()
            val artistLines=if(textHeight>=3*titleLine+2*artistLine+gap) 2 else 1
            val titleLines=((textHeight-gap-artistLine*artistLines)/titleLine).toInt().coerceIn(1,8)
            val widthFit=minOf(1f,textWidth*titleLines/maxOf(textWidth.toFloat(),titleProbe.size.width.toFloat()),textWidth*artistLines/maxOf(textWidth.toFloat(),artistProbe.size.width.toFloat()))
            val fit=minOf(widthFit.coerceAtLeast(.62f),1f,textHeight/maxOf(1f,titleLine*titleLines+artistLine*artistLines+gap))
            val title=measurer.measure(item.name,TextStyle(fontSize=(titleSize*fit).sp,fontWeight=FontWeight.Medium),maxLines=titleLines,overflow=TextOverflow.Ellipsis,constraints=Constraints(maxWidth=textWidth))
            val artist=measurer.measure(item.singer,TextStyle(fontSize=(artistSize*fit).sp),maxLines=artistLines,overflow=TextOverflow.Ellipsis,constraints=Constraints(maxWidth=textWidth))
                    caption=CaptionLayout(item,textWidth,textHeight,bucketScale,title,artist,title.size.height+gap*fit)
                    cache.entries.put(cell,caption);built++
                } else pending=true
            }
            val shaped=caption ?: continue
            val x=frame[o+10]*density;val y=frame[o+11]*density
            val scale=(frame[o+14]/bucketScale).coerceAtLeast(.001f)
            withTransform({
                translate(x,y);scale(scale,scale,Offset.Zero)
            }) {
                clipRect(0f,0f,minOf(shaped.width.toFloat(),w/scale),minOf(shaped.height.toFloat(),h/scale)) {
                    drawText(shaped.title,color=ink.copy(alpha=alpha));drawText(shaped.artist,color=ink.copy(alpha=alpha*.78f),topLeft=Offset(0f,shaped.artistTop))
                }
            }
        }
    if(pending&&!onlyOwner) cache.requestFrame()
}

@Composable
private fun DislikeGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s=size.minDimension
        val heart=androidx.compose.ui.graphics.Path().apply {
            moveTo(s*.5f,s*.84f)
            cubicTo(s*.18f,s*.62f,s*.06f,s*.44f,s*.12f,s*.30f)
            cubicTo(s*.18f,s*.16f,s*.40f,s*.12f,s*.50f,s*.28f)
            cubicTo(s*.60f,s*.12f,s*.82f,s*.16f,s*.88f,s*.30f)
            cubicTo(s*.94f,s*.44f,s*.82f,s*.62f,s*.50f,s*.84f);close()
        }
        val gap=android.graphics.Path()
        android.graphics.Paint().apply {
            style=android.graphics.Paint.Style.STROKE;strokeWidth=s*.085f*2.6f;strokeCap=android.graphics.Paint.Cap.ROUND
        }.getFillPath(android.graphics.Path().apply { moveTo(s*.1f,s*.1f);lineTo(s*.9f,s*.9f) },gap)
        val cut=androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference,heart,gap.asComposePath())
        drawPath(cut,color)
        drawLine(color,Offset(s*.12f,s*.12f),Offset(s*.88f,s*.88f),s*.085f,androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private data class CaptionLayout(val item: SpatialItem,val width: Int,val height: Int,val scale: Float,val title: androidx.compose.ui.text.TextLayoutResult,
    val artist: androidx.compose.ui.text.TextLayoutResult,val artistTop: Float)

/** Bounded shaping work; a single next-frame invalidation drains pending labels and then stops. */
private class CaptionCache : AutoCloseable {
    val entries=android.util.LruCache<Int,CaptionLayout>(512)
    var revision by mutableIntStateOf(0)
    private var posted=false
    private var closed=false
    private val callback=android.view.Choreographer.FrameCallback { posted=false;if(!closed) revision++ }
    fun requestFrame() {
        if(!closed&&!posted) { posted=true;android.view.Choreographer.getInstance().postFrameCallback(callback) }
    }
    override fun close() { closed=true;android.view.Choreographer.getInstance().removeFrameCallback(callback);entries.evictAll() }
}
