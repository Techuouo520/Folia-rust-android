package io.github.techuouo520.folia.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayList
import java.util.HashMap


internal class DailySpatialRenderer(context:Context?, private val displayDensity:Float, private val covers:DailySpatialCovers?, private val listener:Listener?, private val initialArtworkLimit: Int = 96) {
    private var capture:CaptureRequest? = null
    private val context:Context?
    private val thread:HandlerThread = HandlerThread("Folia-DailyWallGL")
    private val worker:Handler?
    private val main:Handler = Handler(android.os.Looper.getMainLooper())
    private val frameLock:Any = Any()
    private val frame:FloatArray = FloatArray(SpatialNative.DAILY_SPATIAL_FLOATS)
    private val instanceBytes:ByteBuffer? = ByteBuffer.allocateDirect(CAPACITY * INSTANCE_FLOATS * 4).order(ByteOrder.nativeOrder())
    private val instances:FloatBuffer? = instanceBytes!!.asFloatBuffer()
    private val sceneBytes:ByteBuffer? = ByteBuffer.allocateDirect(SpatialNative.DAILY_SPATIAL_FLOATS * 4).order(ByteOrder.nativeOrder())
    private val sceneFloats:FloatBuffer? = sceneBytes!!.asFloatBuffer()
    private val bindingBytes:ByteBuffer? = ByteBuffer.allocateDirect(CAPACITY * 5 * 4).order(ByteOrder.nativeOrder())
    private val bindingInts:java.nio.IntBuffer? = bindingBytes!!.asIntBuffer()
    private var queued:Boolean = false
    private var hasFrame:Boolean = false
    private var pendingSequence:Long = 0
    private var processedSequence:Long = 0
    private var pendingMotionState:Long = 0
    private var lastMotionStep:Long = 0
    private var pendingWidth:Float = 0.toFloat()
    private var pendingHeight:Float = 0.toFloat()
    private var pendingResetClock:Boolean = false
    private val completedFrame:FloatArray = FloatArray(frame.size)
    private val renderTask:Runnable? = Runnable({ this.renderLatest() })
    private var artworkPlan:ArtworkPlan? = null
    private var bindingsArrival = -1f
    private var priorityAsset:CoverAsset? = null
    private val destinationAssets:java.util.HashSet<CoverAsset> = java.util.HashSet()
    private val visibleHigh:java.util.LinkedHashSet<CoverAsset> = java.util.LinkedHashSet()
    private val highUrls:java.util.HashSet<String> = java.util.HashSet()
    private val artworkScene:ByteBuffer? = ByteBuffer.allocateDirect(SpatialNative.DAILY_SPATIAL_FLOATS * 4).order(ByteOrder.nativeOrder())
    private val artworkFrame:FloatArray = FloatArray(SpatialNative.DAILY_SPATIAL_FLOATS)
    @Volatile private var closed:Boolean = false
    @Volatile private var generation:Int = 0
    private var pendingGeneration:Int = 0
    private var width:Int = 0
    private var height:Int = 0
    private var viewWidth:Int = 0
    private var viewHeight:Int = 0
    private var layoutStyle:Int = 0
    private val tablet:Boolean
    private var density:Float = 0.toFloat()
    private val tracks:MutableList<SpatialItem> = ArrayList() // GL thread only
    @Volatile private var dataGeneration:Int = 0
    @Volatile private var dark:Boolean = false
    @Volatile private var accent:Int = 0
    private var display:EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eglContext:EGLContext? = EGL14.EGL_NO_CONTEXT
    private var eglSurface:EGLSurface? = EGL14.EGL_NO_SURFACE
    private var windowConfig:EGLConfig? = null
    private var surface:Surface? = null
    private var textureOwner:SurfaceTexture? = null
    private var hdrSurface:Boolean = false
    private var sceneHdr:Boolean = false
    private var hdrHeadroom:Float = 1f
    private var program:Int = 0
    private var instanceBuffer:Int = 0
    private var coverTexture:Int = 0
    private var previewTexture:Int = 0
    private val meshVaos:IntArray = IntArray(3)
    private val meshCounts:IntArray = IntArray(3)
    private var blurTopRows:Int = 0
    private var blurBottomRows:Int = 0
    // Resolve URLs when catalogue data changes, not for every moving card on every frame.
    private val assetsByUrl:MutableMap<String, CoverAsset> = HashMap()
    private val trackAssets:MutableList<CoverAsset> = ArrayList()
    private val previewKeys:Array<CoverAsset?> = arrayOfNulls<CoverAsset>(PREVIEWS)
    private val previewUsed:LongArray = LongArray(PREVIEWS)
    private val pinned:LongArray = LongArray(HIGH + 1)
    private val previewPinned:LongArray = LongArray(PREVIEWS)
    private val previewColors:IntArray = IntArray(PREVIEWS)
    private val previewLoaded:BooleanArray = BooleanArray(PREVIEWS)
    private var drawEpoch:Long = 0
    private var gpuFence:Long = 0
    // Upload fences protect only their staging buffer, not the entire previous frame.
    // Waiting for the newest draw fence starved HD upgrades during continuous motion.
    private val uploadPbos:IntArray = IntArray(3)
    private val uploadFences:LongArray = LongArray(3)
    private var nextUpload:Int = 0
    private var uploadRetryPosted:Boolean = false
    // Match the 25 packed vec4 uniforms shared by the wall and Rhine cover shaders.
    private val coverBlend = FloatArray(((HIGH + 4) / 4) * 4) { 1f }
    private val coverBlendStarted = LongArray(HIGH + 1)
    private var lastCoverFrameNanos = 0L
    private var coverFadeActive = false
    private var coverFadePosted = false
    private val coverFade = Runnable {
        coverFadePosted = false
        if (!closed && texturesReady) requestLast()
    }
    private var bindingsTopology:Float = -1f
    private var bindingsCatalogue:Int = -1
    private var bindingsContent:Int = -1
    private var bindingsOwner:Int = -2
    private var bindingsPriority:CoverAsset? = null
    private var bindingsPending:Boolean = false
    private var texturesReady:Boolean = false
    private val uploadRetry:Runnable = Runnable { uploadRetryPosted = false
        if (!closed && texturesReady) requestLast() }
    private var printedLyricsCell = -1
    private var drawnRevision:Float = -1f
    private var backgroundRevision:Float = -1f
    private var backgroundOwner:Int = -2
    private var liftInstance:Int = -1
    private var liftLevel:Int = 0
    private var backgroundCatalogue:Long = -1
    private var rhineRenderer:DailyRhineRenderer? = null
    private var drawnRoute:Int = -1
    private var rhineContextReady:Boolean = false
    private var packedCount:Int = -1
    private var packedOwnerAlpha:Float = -1f
    private var packedBatches:Long = 0
    private var paletteDirty:Boolean = true
    private val locations:MutableMap<String?, Int> = HashMap()
    private val keys:Array<CoverAsset?> = arrayOfNulls<CoverAsset>(HIGH + 1)
    private val used:LongArray = LongArray(HIGH + 1)
    private val loaded:BooleanArray = BooleanArray(HIGH + 1)
    private var clock:Long = 0
    private var blurProgram:Int = 0
    private var composeProgram:Int = 0
    private var fullVao:Int = 0
    private var sceneFbo:Int = 0
    private var sceneColor:Int = 0
    private var sceneDepth:Int = 0
    private var sceneWidth:Int = 0
    private var sceneHeight:Int = 0
    private var scenePadX:Int = 0
    private var scenePadY:Int = 0
    private val blurFbos:IntArray = IntArray(8)
    private val blurTextures:IntArray = IntArray(8)
    private var blurImageLocation:Int = 0
    private var blurStepLocation:Int = 0
    private var downsampleProgram:Int = 0
    private var contextBlurProgram:Int = 0
    private var downsampleImage:Int = 0
    private var downsampleTexel:Int = 0
    private var contextImage:Int = 0
    private var contextStep:Int = 0
    private var contextKernelLocation:Int = 0
    private val contextKernelBytes:ByteBuffer? = ByteBuffer.allocateDirect(56).order(ByteOrder.nativeOrder())
    private val contextKernel:FloatBuffer? = contextKernelBytes!!.asFloatBuffer()
    private val blurWidths:IntArray = IntArray(8)
    private val blurHeights:IntArray = IntArray(8)
    private val composeSamplers:IntArray = IntArray(5)
    private val composeTextures:IntArray = IntArray(5)
    private var composePolicyLocation:Int = 0
    private var composeHeightLocation:Int = 0
    private var composeDarkLocation:Int = 0
    private var composeMixLocation:Int = 0
    private var composeBoundsLocation:Int = 0
    private var composeScreenLocation:Int = 0
    private var composeShadowLocation:Int = 0
    private var composeScaleLocation:Int = 0
    private var composeRectLocation:Int = 0
    private var composeOutputLocation:Int = 0
    private var composeRhineLocation:Int = 0
    private var composeKeyLocation:Int = 0
    interface Listener {
        fun onReady()
        fun onHdrReady(supported:Boolean) {}
        fun onFailure()
        fun onDirty()
        fun onFrameComplete(values:FloatArray?, swapped:Boolean)
    }
    interface CaptureListener {
        fun onCaptured(bitmap:Bitmap?, crop:Rect?, snapshot:FloatArray?, failure:String?)
    }
    private class CaptureRequest( val cell:Int,  val listener:CaptureListener?) {
        val started:Long = System.nanoTime()
        var issued:Boolean = false
        var fbo:Int = 0
        var color:Int = 0
        var pbo:Int = 0
        var fence:Long = 0
        var crop:Rect? = null
        var displayCrop:Rect? = null
        var snapshot:FloatArray? = null
    }
    private class ArtworkPlan( val ready:Runnable?,  val epoch:Int, frame:FloatArray?,  val holdScene:Boolean) {
        val assets:ArrayList<CoverAsset> = ArrayList()
        val wanted:java.util.HashSet<CoverAsset> = java.util.HashSet()
        val deadline:Long = System.nanoTime() + 8_000_000_000L
        val held:FloatArray?
        init{
            held = if (holdScene) frame!!.clone() else null
            if (held != null) {
                held[0] = 0f
                held[25] = 0f
            }
        }
        fun add(asset:CoverAsset?) {
            if (assets.size < HIGH && !asset!!.url.isEmpty() && wanted.add(asset)) assets.add(asset)
        }
    }
    private fun wantHigh(asset:CoverAsset?) {
        if (asset != null && !asset.url.isEmpty() && visibleHigh.size < HIGH) visibleHigh.add(asset)
    }
    private fun wantHighTrack(index:Int) {
        if (index >= 0 && index < trackAssets.size) wantHigh(trackAssets.get(index))
    }
    private class CoverAsset( val url:String) {
        var preview:Int = -1
        var high:Int = 0
        var resolvedEpoch:Long = -1
    }

    init{
        this.context = context!!.getApplicationContext()
        this.density = displayDensity
        this.tablet = context.getResources().getConfiguration().smallestScreenWidthDp >= 600
        thread.start()
        worker = Handler(thread.getLooper())
    }
    fun setHdrHeadroom(headroom:Float) {
        val value = DisplayCapabilities.headroom(headroom)
        worker!!.post({ if (closed || hdrHeadroom == value) return@post
            hdrHeadroom = value
            if (rhineRenderer != null) rhineRenderer!!.setHdrHeadroom(value)
            // Headroom changes cover emission, refraction source, Gaussian and final
            // optics even when Rust's poses/revisions are unchanged.
            paletteDirty = true
            drawnRevision = -1f
            backgroundRevision = -1f
            rhineContextReady = false
            requestLast() })
    }
    fun setPalette(dark:Boolean, accent:Int) {
        this.dark = dark
        this.accent = accent
        worker!!.post({ paletteDirty = true
            requestLast() })
    }
    fun setTracks(values:MutableList<SpatialItem>?) {
        val snapshot = values!!.toTypedArray()
        ++dataGeneration
        worker!!.post({ tracks.clear()
            tracks.addAll(snapshot)
            indexAssets(snapshot, false) })
    }
    fun appendTracks(values:MutableList<SpatialItem>?) {
        val fresh = values!!.toTypedArray()
        ++dataGeneration
        worker!!.post({ tracks.addAll(fresh)
            indexAssets(fresh, true) })
    }
    private fun indexAssets(values:Array<SpatialItem>?, append:Boolean) {
        if (!append)
        {
            assetsByUrl.clear()
            trackAssets.clear()
            // Reuse owners of resident textures; do not retain unrelated old catalogues.
            for (asset in previewKeys) if (asset != null) assetsByUrl.put(asset.url, asset)
            for (asset in keys) if (asset != null) assetsByUrl.put(asset.url, asset)
        }
        for (track in values!!) trackAssets.add(assetsByUrl.computeIfAbsent(track.coverUrl, { CoverAsset(it) }))
    }
    private fun resetAssets() {
        for (asset in assetsByUrl.values) {
            asset.preview = -1
            asset.high = 0
            asset.resolvedEpoch = -1
        }
        java.util.Arrays.fill(keys, null)
        java.util.Arrays.fill(previewKeys, null)
        java.util.Arrays.fill(loaded, false)
        java.util.Arrays.fill(previewLoaded, false)
        java.util.Arrays.fill(coverBlend, 1f)
        java.util.Arrays.fill(coverBlendStarted, 0L)
        lastCoverFrameNanos = 0L
        coverFadeActive = false
    }
    private fun assignHigh(slot: Int, asset: CoverAsset) {
        keys[slot]?.high = 0
        keys[slot] = asset
        asset.high = slot
        loaded[slot] = false
        coverBlend[slot] = 0f
        coverBlendStarted[slot] = 0L
    }

    private fun advanceCoverBlend(now: Long): Boolean {
        var changed = false
        coverFadeActive = false
        for (slot in 1..HIGH) {
            val started = coverBlendStarted[slot]
            if (started == 0L) continue
            val elapsed = (now - maxOf(lastCoverFrameNanos, started)).coerceIn(0L, 50_000_000L)
            val progress = (coverBlend[slot] + elapsed.toFloat() / COVER_FADE_NANOS.toFloat()).coerceAtMost(1f)
            if (coverBlend[slot] != progress) {
                coverBlend[slot] = progress
                changed = true
            }
            if (progress < 1f) coverFadeActive = true else coverBlendStarted[slot] = 0L
        }
        lastCoverFrameNanos = now
        return changed
    }

    private fun scheduleCoverFade() {
        if (!coverFadeActive) {
            if (coverFadePosted) worker!!.removeCallbacks(coverFade)
            coverFadePosted = false
        } else if (!coverFadePosted) {
            coverFadePosted = true
            worker!!.postDelayed(coverFade, 16L)
        }
    }
    fun setLayoutStyle(style:Int) {
        worker!!.post({ layoutStyle = style
            bindingsTopology = -1f
            drawnRevision = -1f })
    }
    fun prioritizeArtwork(index:Int) {
        worker!!.post({ if (closed || index < 0 || index >= trackAssets.size) return@post
            priorityAsset = trackAssets.get(index)
            if (priorityAsset!!.high == 0 || !loaded[priorityAsset!!.high]) covers!!.request(priorityAsset!!.url, true)
            requestLast() })
    }
    // Only the initial reveal may wait for artwork. A live Rhine scene keeps advancing Rust.
    @JvmOverloads  fun prepareArtwork(focusIndex:Int, ready:Runnable?, holdScene:Boolean = true) {
        worker!!.post({ if (closed) return@post
            if (!texturesReady || program == 0 || tracks.isEmpty()) {
                main.post((ready)!!)
                return@post
            }
            if (!holdScene) main.post((ready)!!)
            try
            {
                bindingsTopology = -1f
                val plan = ArtworkPlan(ready, generation, frame, holdScene)
                // Secondary worlds reveal after their first useful batch; the normal scheduler
                // continues filling the unchanged 96-slot HD atlas behind interaction.
                val warmLimit=if(holdScene) initialArtworkLimit.coerceIn(1,HIGH) else HIGH
                artworkPlan = plan
                destinationAssets.clear()
                covers!!.highPreparation(true)
                val focus = if (focusIndex >= 0 && focusIndex < tracks.size) focusIndex else 0
                plan.add(trackAssets.get(focus))
                if (!holdScene)
                {
                    priorityAsset = trackAssets.get(focus)
                    // Keep the returning card resident while destination textures are prepared.
                    val ownerSlot = frame[ACTION + 23].toInt()
                    if (frame[ACTION + 27] > .5f && ownerSlot >= 0 && ownerSlot < frame[16].toInt())
                    {
                        val ownerTrack = frame[32 + ownerSlot * 16].toInt()
                        if (ownerTrack >= 0 && ownerTrack < trackAssets.size) plan.add(trackAssets.get(ownerTrack))
                    }
                }
                val preview = SpatialNative.dailySpatialCreate()
                if (preview == 0L) throw IllegalStateException("HD destination unavailable")
                try
                {
                    SpatialNative.dailySpatialCommand(preview, 10, tracks.size, (focus).toFloat(), (if (focusIndex >= 0) focus else -1).toFloat())
                    SpatialNative.dailySpatialCommand(preview, 3, 1, (0).toFloat(), (0).toFloat())
                    SpatialNative.dailySpatialCommand(preview, 30, layoutStyle, (0).toFloat(), (0).toFloat())
                    // Rust predicts the final playback camera + wave, independently of the live scene.
                    for (step in 0..11) if (!SpatialNative.dailySpatialStep(preview, .1f, width / density, height / density, (artworkScene)!!)) throw IllegalStateException("HD destination layout failed")
                    artworkScene!!.asFloatBuffer().get(artworkFrame)
                }

                finally
                {
                    SpatialNative.dailySpatialRelease(preview)
                }
                // Predict the destination again now that the camera can follow. The Rust
                // guard includes the small composition offset of the loose follow band.
                val count = Math.min(CAPACITY, artworkFrame[16].toInt())
                if (artworkFrame[DailySpatialProtocol.RHINE] > .5f)
                {
                    val ahead = Math.min(192, artworkFrame[22].toInt())
                    val start = 32 + CAPACITY * 16
                    var n = 0
                    while (n < ahead && plan.assets.size < warmLimit)
                    {
                        val index = artworkFrame[start + n].toInt()
                        if (index >= 0 && index < trackAssets.size) plan.add(trackAssets.get(index))
                        n++
                    }
                }
                run({ var n = 0
                    while (n < count && plan.assets.size < warmLimit)
                    {
                        val at = 32 + n * 16
                        val index = artworkFrame[at].toInt()
                        if (index < 0 || index >= trackAssets.size) {
                            n++
                            continue
                        }
                        // Prefer actual screen intersections before the conservatively padded guard.
                        val x = artworkFrame[at + 1]
                        val y = artworkFrame[at + 2]
                        val extent = artworkFrame[at + 3]
                        if (x + extent < 0 || x - extent > width / density || y + extent < 0 || y - extent > height / density) {
                            n++
                            continue
                        }
                        plan.add(trackAssets.get(index))
                        n++
                    } })
                var n = 0
                while (n < count && plan.assets.size < warmLimit) {
                    val index = artworkFrame[32 + n * 16].toInt()
                    if (index >= 0 && index < trackAssets.size) plan.add(trackAssets.get(index))
                    n++
                }
                destinationAssets.addAll(plan.wanted)
                pumpArtwork(plan)
            }
            catch (error:Exception) {
                artworkPlan = null
                covers!!.highPreparation(false)
                if (holdScene) fail() else requestLast()
            }
        })
    }
    private fun finishArtwork(plan:ArtworkPlan?) {
        if (artworkPlan != plan) return
        artworkPlan = null
        destinationAssets.clear()
        covers!!.highPreparation(false)
        if (plan!!.holdScene) lastMotionStep = 0
        drawnRevision = -1f
        backgroundRevision = -1f
        packedCount = -1
        main.post({ if (!closed && plan.epoch == generation) {
                if (plan.holdScene) plan.ready!!.run()
                listener!!.onDirty()
            } })
    }
    private fun pumpArtwork(plan:ArtworkPlan?) {
        if (closed || artworkPlan != plan || plan!!.epoch != generation) return
        try
        {
            var pending = 0
            var uploads = 0
            var bindingsChanged = false
            for (asset in plan.assets)
            {
                var slot = asset.high
                if (slot == 0)
                {
                    var oldest = Long.MAX_VALUE
                    for (k in 1..HIGH) {
                        if (keys[k] == null) {
                            slot = k
                            break
                        }
                        if (!plan.wanted.contains(keys[k]) && used[k] < oldest) {
                            slot = k
                            oldest = used[k]
                        }
                    }
                    if (slot > 0) {
                        assignHigh(slot, asset)
                        bindingsChanged = true
                    }
                }
                if (slot == 0) {
                    pending++
                    continue
                }
                used[slot] = ++clock
                if (loaded[slot]) continue
                val pixels = covers!!.full(asset.url)
                if (pixels != null && uploads < (if (plan.holdScene) 4 else 1) && uploadHigh(slot, pixels))
                {
                    loaded[slot] = true
                    covers.uploaded(asset.url)
                    uploads++
                }
                else if (!covers.unavailable(asset.url)) {
                    covers.request(asset.url, true)
                    pending++
                }
            }
            if (uploads > 0 && plan.holdScene) GLES30.glFlush() // Hidden preparation has no window swap.
            if (!plan.holdScene && (bindingsChanged || uploads > 0))
            {
                // Slot reuse invalidates packed bindings as well as the retained background.
                bindingsTopology = -1f
                drawnRevision = -1f
                backgroundRevision = -1f
                packedCount = -1
                requestLast()
            }
            // A failed source cannot lock playback/navigation forever. Retain its preview fallback.
            if (pending == 0 || System.nanoTime() >= plan.deadline) {
                finishArtwork(plan)
                return
            }
            worker!!.postDelayed({ pumpArtwork(plan) }, 8L)
        }
        catch (error:Exception) {
            artworkPlan = null
            covers!!.highPreparation(false)
            if (plan.holdScene) fail() else requestLast()
        }

    }
    fun cancelArtwork() {
        worker!!.post({ artworkPlan = null
            destinationAssets.clear()
            covers!!.highPreparation(false)
            lastMotionStep = 0
            requestLast() })
    }
    fun attach(owner:SurfaceTexture?, width:Int, height:Int, requestHdr:Boolean) {
        worker!!.post({ if (closed) return@post
            try
            {
                destroy(false)
                covers!!.resumeHigh()
                textureOwner = owner
                viewWidth = width
                viewHeight = height
                configureRenderSize(false)
                create(owner, requestHdr)
                warmPipeline(generation)
            }
            catch (failure:Exception) {
                fail(failure)
            }
        })
    }
    fun resize(width:Int, height:Int) {
        worker!!.post({ if (closed) return@post
            try
            {
                viewWidth = width
                viewHeight = height
                configureRenderSize(frame[DailySpatialProtocol.RHINE] > .5f)
                drawnRevision = -1f
                requestLast()
            }
            catch (failure:Exception) {
                fail(failure)
            }
        })
    }
    private fun configureRenderSize(rhine:Boolean) {
        val scale = if (tablet && rhine) .9f else 1f
        val w = Math.max(1, Math.round(viewWidth * scale))
        val h = Math.max(1, Math.round(viewHeight * scale))
        val d = displayDensity * scale
        if (width == w && height == h && density == d) return
        width = w
        height = h
        density = d
        // TextureView scales this complete GL image back to the unchanged View.
        // Scale pixels-per-dp with the buffers so projection/blur keep their size.
        if (textureOwner != null) textureOwner!!.setDefaultBufferSize(w, h)
        // GLES producers can override the default buffer size. Recreate only
        // the window surface so the new size takes effect; retain GL resources.
        if (eglSurface !== EGL14.EGL_NO_SURFACE)
        {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            eglSurface = EGL14.eglCreateWindowSurface(display, windowConfig, surface, if (hdrSurface) intArrayOf(0x309D, 0x3350, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface === EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) throw IllegalStateException("Rhine render-size surface unavailable")
            EGL14.eglSwapInterval(display, 0)
        }
        backgroundRevision = -1f
        packedCount = -1
        rhineContextReady = false
    }
    fun detach(owner:SurfaceTexture?) {
        if (!worker!!.post({ if (textureOwner === owner) destroy(true) else owner!!.release() })) owner!!.release()
    }
    fun close(released: () -> Unit = {}) {
        if (closed) return
        closed = true
        worker!!.post({ destroy(false)
            released()
            thread.quitSafely() })
    }
    fun submitMotion(state:Long, width:Float, height:Float, resetClock:Boolean) {
        if (closed) return
        synchronized (frameLock) {
            pendingMotionState = state
            pendingWidth = width
            pendingHeight = height
            pendingResetClock = pendingResetClock or resetClock
            pendingGeneration = dataGeneration
            pendingSequence++
            hasFrame = true
            if (!queued) {
                queued = true
                worker!!.post((renderTask)!!)
            }
        }
    }
    fun captureCard(cell:Int, listener:CaptureListener?) {
        worker!!.post({ if (capture != null) finishCapture(capture, null, "superseded", null)
            if (closed || !texturesReady) {
                main.post({ listener!!.onCaptured(null, null, null, "surface-not-ready") })
                return@post
            }
            capture = CaptureRequest(cell, listener)
            requestLast() })
    }
    fun cancelCapture() {
        worker!!.post({ if (capture != null) finishCapture(capture, null, "cancelled", null) })
    }
    private fun beginCapture() {
        val job = capture
        if (job == null || job.issued) return
        val at = ACTION
        if (frame[at + 3].toInt() != 2 || frame[at + 4].toInt() != job.cell) {
            finishCapture(job, null, "owner-changed", null)
            return
        }
        if (frame[at + 26] < .5f) {
            if (System.nanoTime() - job.started > 2_000_000_000L) finishCapture(job, null, "pose-latch-not-applied", null) else requestLast()
            return
        } // Wait for the Rust pose latch, not every animation.
        job.issued = true
        job.snapshot = frame.clone()
        job.crop = Rect(Math.max(0, Math.floor((frame[at + 6] * density).toDouble()).toInt()), Math.max(0, Math.floor((frame[at + 7] * density).toDouble()).toInt()),
        Math.min(width, Math.ceil((frame[at + 8] * density).toDouble()).toInt()), Math.min(height, Math.ceil((frame[at + 9] * density).toDouble()).toInt()))
        job.displayCrop = Rect(Math.max(0, Math.floor((frame[at + 6] * displayDensity).toDouble()).toInt()), Math.max(0, Math.floor((frame[at + 7] * displayDensity).toDouble()).toInt()),
        Math.min(viewWidth, Math.ceil((frame[at + 8] * displayDensity).toDouble()).toInt()), Math.min(viewHeight, Math.ceil((frame[at + 9] * displayDensity).toDouble()).toInt()))
        try
        {
            val w = job.crop!!.width()
            val h = job.crop!!.height()
            val pixels = w.toLong() * h
            if (w <= 0 || h <= 0 || pixels > 4_194_304L) throw IllegalStateException("invalid crop " + w + "x" + h)
            if (sceneFbo == 0) throw IllegalStateException("scene target missing")
            val prior = GLES30.glGetError()
            if (prior != GLES30.GL_NO_ERROR) android.util.Log.w("DailyDust", "prior GL error 0x" + Integer.toHexString(prior))
            job.color = renderTexture(w, h, false, false)
            job.fbo = framebuffer(job.color, 0)
            // Recompose the retained scene into a crop-sized target. This is the same final
            // depth/blur composition, without asking a second Surface producer for a buffer.
            composeTo(job.fbo, job.crop!!.left, height - job.crop!!.bottom)
            drawLifted(job.fbo, job.crop!!.left, height - job.crop!!.bottom)
            val id = IntArray(1)
            GLES30.glGenBuffers(1, id, 0)
            job.pbo = id[0]
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, job.pbo)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, pixels.toInt() * 4, null, GLES30.GL_STREAM_READ)
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
            job.fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES30.glFlush()
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR || job.fence == 0L) throw IllegalStateException("readback submit GL=0x" + Integer.toHexString(error))
            android.util.Log.i("DailyDust", "capture submitted cell=" + job.cell + " size=" + w + "x" + h)
        }
        catch (failure:RuntimeException) {
            finishCapture(job, null, "readback-submit", failure)
            return
        }
        catch (failure:LinkageError) {
            finishCapture(job, null, "readback-submit", failure)
            return
        }
        finally
        {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
        }
        pollCapture(job)
    }
    private fun pollCapture(job:CaptureRequest?) {
        if (capture != job || closed) return
        try
        {
            val status = GLES30.glClientWaitSync(job!!.fence, 0, 0L)
            if (status == GLES30.GL_TIMEOUT_EXPIRED)
            {
                if (System.nanoTime() - job.started > 5_000_000_000L) throw IllegalStateException("GPU readback fence timeout")
                worker!!.postDelayed({ pollCapture(job) }, 2L)
                return
            }
            if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) throw IllegalStateException("GPU readback fence failed")
            val w = job.crop!!.width()
            val h = job.crop!!.height()
            val argb = IntArray(w * h)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, job.pbo)
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, argb.size * 4, GLES30.GL_MAP_READ_BIT)
            if (!(mapped is ByteBuffer)) throw IllegalStateException("pixel buffer mapping failed")
            val converted:Boolean
            try
            {
                converted = SpatialNative.dailySnapshotPixels((mapped as ByteBuffer?)!!, w, h, argb)
            }

            finally
            {
                if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) throw IllegalStateException("pixel buffer invalidated")
            }
            if (!converted) throw IllegalStateException("Rust pixel conversion rejected buffer")
            var bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(argb, 0, w, 0, 0, w, h)
            if (w != job.displayCrop!!.width() || h != job.displayCrop!!.height())
            {
                val expanded = Bitmap.createScaledBitmap(bitmap, job.displayCrop!!.width(), job.displayCrop!!.height(), true)
                if (expanded !== bitmap) bitmap.recycle()
                bitmap = expanded
            }
            job.crop = job.displayCrop // Dust/caption consumers use physical View pixels.
            android.util.Log.i("DailyDust", "capture ready cell=" + job.cell)
            finishCapture(job, bitmap, null, null)
        }
        catch (failure:RuntimeException) {
            finishCapture(job, null, "readback-complete", failure)
        }
        catch (failure:LinkageError) {
            finishCapture(job, null, "readback-complete", failure)
        }
        finally
        {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }
    }
    private fun finishCapture(job:CaptureRequest?, bitmap:Bitmap?, failure:String?, cause:Throwable?) {
        if (capture == job) capture = null
        if (job!!.fence != 0L) {
            GLES30.glDeleteSync(job.fence)
            job.fence = 0
        }
        if (job.pbo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(job.pbo), 0)
            job.pbo = 0
        }
        if (job.fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(job.fbo), 0)
            job.fbo = 0
        }
        if (job.color != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(job.color), 0)
            job.color = 0
        }
        if (failure != null && !failure.equals("cancelled") && !failure.equals("superseded")) android.util.Log.e("DailyDust", failure + " cell=" + job.cell, cause)
        main.post({ job.listener!!.onCaptured(bitmap, job.crop, job.snapshot, failure) })
    }
    private fun requestLast() {
        if (!closed) main.post({ if (!closed) listener!!.onDirty() })
    }
    private fun renderLatest() {
        val token:Int
        val epoch = generation
        val state:Long
        val w:Float
        val h:Float
        val reset:Boolean
        synchronized (frameLock) {
            queued = false
            if (!hasFrame || closed || pendingSequence == processedSequence) return
            processedSequence = pendingSequence
            token = pendingGeneration
            state = pendingMotionState
            w = pendingWidth
            h = pendingHeight
            reset = pendingResetClock
            pendingResetClock = false
        }
        if (!texturesReady || program == 0 || eglSurface === EGL14.EGL_NO_SURFACE || token != dataGeneration)
        {
            lastMotionStep = 0
            main.post({ if (!closed && epoch == generation) listener!!.onFrameComplete(null, false) })
            return
        }
        if (artworkPlan != null && artworkPlan!!.holdScene) {
            val held = artworkPlan!!.held
            lastMotionStep = 0
            main.post({ if (!closed && epoch == generation) listener!!.onFrameComplete(held, false) })
            return
        }
        try
        {
            val now = System.nanoTime()
            val dt = if (reset || lastMotionStep == 0L) 0f else Math.max(0f, (now - lastMotionStep) / 1_000_000_000f)
            android.os.Trace.beginSection("Daily.RustScene")
            try
            {
                if (!SpatialNative.dailySpatialStep(state, dt, w, h, (sceneBytes)!!))
                {
                    if (!closed) throw IllegalStateException("Daily Rust scene unavailable")
                    return
                }
            }

            finally
            {
                android.os.Trace.endSection()
            }
            lastMotionStep = now
            sceneFloats!!.position(0)
            sceneFloats.get(frame)
            if (token != dataGeneration) {
                main.post({ if (!closed && epoch == generation) listener!!.onFrameComplete(null, false) })
                return
            }
            val swapped:Boolean
            android.os.Trace.beginSection("Daily.GLScene")
            try
            {
                swapped = draw(token)
            }

            finally
            {
                android.os.Trace.endSection()
            }
            System.arraycopy(frame, 0, completedFrame, 0, frame.size)
            // Retain the one-in-flight snapshot contract: captions never read a newer scene.
            main.post({ if (!closed && epoch == generation) listener!!.onFrameComplete(if (token == dataGeneration) completedFrame else null, swapped) })
            scheduleCoverFade()
            beginCapture()
        }
        catch (failure:Exception) {
            fail(failure)
        }

    }

    private fun previewSlot(asset:CoverAsset?):Int {
        val found = asset!!.preview
        if (found >= 0) {
            previewUsed[found] = ++clock
            return found
        }
        var slot = -1
        var oldest = Long.MAX_VALUE
        for (n in 0 until PREVIEWS) {
            if (previewKeys[n] == null) {
                slot = n
                break
            }
            if (previewPinned[n] != drawEpoch && previewUsed[n] < oldest) {
                oldest = previewUsed[n]
                slot = n
            }
        }
        if (slot < 0) return -1
        if (previewKeys[slot] != null) previewKeys[slot]!!.preview = -1
        previewKeys[slot] = asset
        asset.preview = slot
        previewLoaded[slot] = false
        previewUsed[slot] = ++clock
        return slot
    }
    private fun uploadPreview(slot:Int, image:DailySpatialCovers.Preview?) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTexture)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, (slot % 32) * TILE, (slot / 32) * TILE, image!!.bitmap)
        previewColors[slot] = image.color
        previewLoaded[slot] = true
    }
    private fun openWindow(floating:Boolean):Boolean {
        val bits = if (floating) 16 else 8
        var attributes:IntArray? = intArrayOf(EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits, EGL14.EGL_ALPHA_SIZE, bits, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3339, if (floating) 0x333B else 0x333A, EGL14.EGL_NONE)
        // Do not pass EXT attributes to SDR drivers that do not advertise them.
        if (!floating) attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig?>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (eglContext === EGL14.EGL_NO_CONTEXT) return false
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, if (floating) intArrayOf(0x309D, 0x3350, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE), 0)
        var ready = eglSurface !== EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
        if (ready && floating)
        {
            val gl = GLES30.glGetString(GLES30.GL_EXTENSIONS)
            ready = gl != null && (gl.contains("GL_EXT_color_buffer_float") || gl.contains("GL_EXT_color_buffer_half_float"))
            if (ready) {
                val tex = IntArray(1)
                val fbo = IntArray(1)
                GLES30.glGenTextures(1, tex, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, 2, 2)
                GLES30.glGenFramebuffers(1, fbo, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
                ready = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, fbo, 0)
                GLES30.glDeleteTextures(1, tex, 0)
            }
        }
        if (ready) {
            windowConfig = configs[0]
            return true
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, eglContext)
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        return false
    }
    @Throws(Exception::class)
    private fun create(owner:SurfaceTexture?, requestHdr:Boolean) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("EGL initialize")
        surface = Surface(owner)
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
        val candidate = requestHdr && extensions != null && extensions.contains("EGL_EXT_pixel_format_float") && extensions.contains("EGL_EXT_gl_colorspace_scrgb_linear")
        hdrSurface = candidate && openWindow(true)
        if (!hdrSurface && !openWindow(false)) throw IllegalStateException("ES3 surface unavailable")
        val hdr = hdrSurface
        main.post({ if (!closed && textureOwner === owner) listener!!.onHdrReady(hdr) })
        android.util.Log.i("DailyHDR", if (hdrSurface) "FP16 scRGB output negotiated" else "SDR output fallback")
        EGL14.eglSwapInterval(display, 0) // No app-imposed swap cadence; the driver may apply its own constraints.
        val v = shader(GLES30.GL_VERTEX_SHADER, "shaders/daily_wall.vert")
        val f = shader(GLES30.GL_FRAGMENT_SHADER, "shaders/daily_wall.frag")
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, v)
        GLES30.glAttachShader(program, f)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(program))
        AlbumLyricsChip.upload(requireNotNull(context))
        AlbumLyricsChip.configure(program)
        for (name in arrayOf<String>("u_screen", "u_center", "u_radius", "u_pan", "u_zoom", "u_mode", "u_header", "u_scroll", "u_dark", "u_accent", "u_covers", "u_previews", "u_layer", "u_lift_rect", "u_lift_mix", "u_lift_radius", "u_linear_output")) locations.put(name, GLES30.glGetUniformLocation(program, name))
        locations["u_cover_blend[0]"] = GLES30.glGetUniformLocation(program, "u_cover_blend[0]")
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        instanceBuffer = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, CAPACITY * INSTANCE_FLOATS * 4, null, GLES30.GL_STREAM_DRAW)
        for (level in 0..2) createMesh(level, 8 shl level)
        GLES30.glGenTextures(1, ids, 0)
        coverTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, coverTexture)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, DailySpatialCovers.MIPMAPS.levels.size, GLES30.GL_RGBA8, SIZE, SIZE, HIGH + 1)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenTextures(1, ids, 0)
        previewTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTexture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, DailySpatialCovers.ATLAS_SIDE * TILE, ((PREVIEWS + DailySpatialCovers.ATLAS_SIDE - 1) / DailySpatialCovers.ATLAS_SIDE) * TILE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        resetAssets()
        blurProgram = linkProgram("shaders/daily_fullscreen.vert", "shaders/daily_depth_blur.frag")
        composeProgram = linkProgram("shaders/daily_fullscreen.vert", "shaders/daily_depth_compose.frag")
        downsampleProgram = linkProgram("shaders/daily_fullscreen.vert", "shaders/daily_context_downsample.frag")
        contextBlurProgram = linkProgram("shaders/daily_fullscreen.vert", "shaders/daily_context_gaussian.frag")
        downsampleImage = GLES30.glGetUniformLocation(downsampleProgram, "u_image")
        downsampleTexel = GLES30.glGetUniformLocation(downsampleProgram, "u_texel")
        contextImage = GLES30.glGetUniformLocation(contextBlurProgram, "u_image")
        contextStep = GLES30.glGetUniformLocation(contextBlurProgram, "u_step")
        contextKernelLocation = GLES30.glGetUniformLocation(contextBlurProgram, "u_kernel[0]")
        blurImageLocation = GLES30.glGetUniformLocation(blurProgram, "u_image")
        blurStepLocation = GLES30.glGetUniformLocation(blurProgram, "u_step")
        val samplers = arrayOf<String>("u_scene", "u_depth", "u_soft", "u_far", "u_context_scene")
        for (i in 0..4) composeSamplers[i] = GLES30.glGetUniformLocation(composeProgram, samplers[i])
        composePolicyLocation = GLES30.glGetUniformLocation(composeProgram, "u_depth_policy")
        composeHeightLocation = GLES30.glGetUniformLocation(composeProgram, "u_height")
        composeDarkLocation = GLES30.glGetUniformLocation(composeProgram, "u_dark")
        composeMixLocation = GLES30.glGetUniformLocation(composeProgram, "u_context_mix")
        composeBoundsLocation = GLES30.glGetUniformLocation(composeProgram, "u_lift_bounds")
        composeScreenLocation = GLES30.glGetUniformLocation(composeProgram, "u_screen")
        composeShadowLocation = GLES30.glGetUniformLocation(composeProgram, "u_shadow")
        composeOutputLocation = GLES30.glGetUniformLocation(composeProgram, "u_output_mode")
        composeRhineLocation = GLES30.glGetUniformLocation(composeProgram, "u_rhine")
        composeKeyLocation = GLES30.glGetUniformLocation(composeProgram, "u_key_light")
        composeScaleLocation = GLES30.glGetUniformLocation(composeProgram, "u_background_scale")
        composeRectLocation = GLES30.glGetUniformLocation(composeProgram, "u_scene_rect")
        GLES30.glGenVertexArrays(1, ids, 0)
        fullVao = ids[0]
        drawnRevision = -1f
        paletteDirty = true
        texturesReady = false
        generation++
    }
    @Throws(Exception::class)
    private fun shader(kind:Int, asset:String?):Int {
        val source = DailySpatialShaders.load(context, asset, "")
        val shader = GLES30.glCreateShader(kind)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException(message)
        }
        return shader
    }
    private fun draw(token:Int):Boolean {
        if (advanceCoverBlend(System.nanoTime())) {
            drawnRevision = -1f
            backgroundRevision = -1f
            rhineContextReady = false
        }
        val route = if (frame[DailySpatialProtocol.RHINE] > .5f) 1 else 0
        if (route != drawnRoute) {
            drawnRoute = route
            bindingsTopology = -1f
            drawnRevision = -1f
            backgroundRevision = -1f
            rhineContextReady = false
        }
        val chipCell = AlbumLyricsChip.cell(frame)
        if (printedLyricsCell != chipCell) {
            printedLyricsCell = chipCell
            drawnRevision = -1f
            backgroundRevision = -1f
        }
        val count = Math.min(CAPACITY, frame[16].toInt())
        // Manual exploration supersedes a live playback landing plan (its ready callback
        // already ran). It must not own every HD layer or suppress browse uploads for 8s.
        if (route == 1 && frame[DailySpatialProtocol.RHINE + 12] > .5f && !destinationAssets.isEmpty())
        {
            if (artworkPlan != null && !artworkPlan!!.holdScene) {
                artworkPlan = null
                covers!!.highPreparation(false)
            }
            destinationAssets.clear()
            bindingsTopology = -1f
        }
        val owner = if (frame[ACTION + 27] > .5f) frame[ACTION + 4].toInt() else -1
        // Cover IO continues in its workers. Static-scene bindings were already requested;
        // defer only redundant polling/upload attempts during this short lift transition.
        val ownerSlot = frame[ACTION + 23].toInt()
        val ownerTrack = if (ownerSlot >= 0 && ownerSlot < count) frame[32 + ownerSlot * 16].toInt() else -1
        val ownerAsset = if (ownerTrack >= 0 && ownerTrack < trackAssets.size) trackAssets.get(ownerTrack) else null
        val retainedLift = (owner >= 0 && ownerAsset != null && ownerAsset.high > 0 && loaded[ownerAsset.high] && frame[25] > .5f && backgroundRevision == frame[ACTION + DailySpatialProtocol.WORLD_REVISION]
        && backgroundOwner == owner && backgroundCatalogue == dataGeneration.toLong() && !paletteDirty && packedCount == count)
        if (retainedLift)
        {
            if (drawnRevision == frame[23]) return false
            drawnRevision = frame[23]
            renderScene(count, false)
            return swapScene()
        }
        val arrival = frame[ACTION + DailySpatialProtocol.ARRIVAL_ACTIVE] > .5f
        val arrivalRevision = if(arrival) frame[ACTION + DailySpatialProtocol.ARRIVAL_REVISION] else -1f
        // Restore the native world revision after admission without carrying its transient
        // stamp into normal Rhine hologram/UI-only frames (which must not rebuild the world).
        if(bindingsArrival != arrivalRevision) backgroundRevision = -1f
        val contentRevision = covers!!.contentRevision()
        val topology = frame[DailySpatialProtocol.RHINE + DailySpatialProtocol.RHINE_TOPOLOGY]
        // Rust keeps slot identity stable through camera translation and wave-only frames.
        // Decode completions/failures publish a revision; pending GPU uploads still retry.
        if ((route == 1 && bindingsArrival == arrivalRevision && bindingsTopology == topology && bindingsCatalogue == dataGeneration
        && bindingsContent == contentRevision && bindingsOwner == owner && bindingsPriority == priorityAsset && !bindingsPending))
        {
            if (drawnRevision == frame[23] && !paletteDirty) return false
            drawnRevision = frame[23]
            if (paletteDirty) backgroundRevision = -1f
            renderScene(count, false)
            paletteDirty = false
            return swapScene()
        }
        ++drawEpoch
        // Keep the prepared landing area resident while the camera traverses intermediate cells.
        if (artworkPlan != null) for (asset in destinationAssets) if (asset.high > 0) pinned[asset.high] = drawEpoch
        val ahead = Math.min(192, frame[22].toInt())
        val aheadStart = 32 + CAPACITY * 16
        if (route == 1)
        {
            visibleHigh.clear()
            wantHigh(ownerAsset)
            if(!arrival) wantHigh(priorityAsset)
            if (artworkPlan != null) for (asset in destinationAssets) wantHigh(asset)
            // Rust sends HD priority independently of draw order, including the future
            // browsing crest. Only this bounded set pins layers, never all visible spines.
            for (n in 0 until ahead) wantHighTrack(frame[aheadStart + n].toInt())
            for (n in 0 until count) if(!arrival || frame[32 + n * 16 + 9] > .001f) wantHighTrack(frame[32 + n * 16].toInt())
            highUrls.clear()
            for (asset in visibleHigh) {
                highUrls.add(asset.url)
                if (asset.high > 0) pinned[asset.high] = drawEpoch
            }
            covers.retainHigh(highUrls)
            // Assign all layers before emitting any binding: later priority replacements
            // cannot make an earlier instance sample the wrong album in this frame.
            for (asset in visibleHigh)
            {
                var slot = asset.high
                if (slot == 0)
                {
                    var oldest = Long.MAX_VALUE
                    for (k in 1..HIGH)
                    {
                        if (keys[k] == null) {
                            slot = k
                            break
                        }
                        if (pinned[k] != drawEpoch && used[k] < oldest) {
                            slot = k
                            oldest = used[k]
                        }
                    }
                    if (slot > 0) {
                        assignHigh(slot, asset)
                    }
                }
                if (slot > 0) {
                    pinned[slot] = drawEpoch
                    used[slot] = ++clock
                    if (!loaded[slot]) covers.request(asset.url, true)
                }
            }
        }
        // Preview residency covers all visible cards. Landscape retains its original HD policy.
        for (n in 0 until count + ahead)
        {
            if(arrival && n<count && frame[32+n*16+9]<=.001f) continue
            val index = frame[if (n < count) 32 + n * 16 else aheadStart + n - count].toInt()
            if (index < 0 || index >= trackAssets.size) continue
            val asset = trackAssets.get(index)
            if (asset.preview >= 0) previewPinned[asset.preview] = drawEpoch
            if (route == 0 && asset.high > 0 && (n < count || n - count < 32)) pinned[asset.high] = drawEpoch
        }
        // Expansion and the existing HWUI cover/bar transition share the GPU. Keep the
        // resident artwork/preview during this handoff; upgrade texture resolution afterwards.
        val expanding = route == 0 && frame[25] > .5f
        val highUploadBudget = if (artworkPlan != null && !artworkPlan!!.holdScene) 0 else (if (route == 1) 2 else 1)
        var uploads = 0
        var previewUploads = 0
        var pendingUpload = false
        var highSlotsExhausted = false
        for (n in 0 until count + ahead)
        {
            val visible = n < count
            val at = 32 + n * 16
            if(arrival && visible && frame[at+9]<=.001f) {
                // Keep slot alignment, but do not decode, pin or upload cards ahead of the wave.
                continue
            }
            val index = frame[if (visible) at else aheadStart + n - count].toInt()
            if (index < 0 || index >= tracks.size) {
                continue
            }
            val asset = trackAssets.get(index)
            val url = asset.url
            var slot = asset.high
            var small = asset.preview
            if (!url.isEmpty() && asset.resolvedEpoch != drawEpoch)
            {
                asset.resolvedEpoch = drawEpoch
                small = previewSlot(asset)
                if (small >= 0)
                {
                    previewPinned[small] = drawEpoch
                    if (!previewLoaded[small])
                    {
                        val image = covers.preview(url)
                        if (image != null)
                        {
                            // Cached visible previews are small enough to admit together on the first frame.
                            if (visible || previewUploads < 2) {
                                uploadPreview(small, image)
                                previewUploads++
                            } else pendingUpload = true
                        }
                        else
                        covers.request(url, false)
                    }
                }
                // High-resolution working set: viewport first, then a small nearby guard ring.
                if (if (route == 1) visibleHigh.contains(asset) else (visible || n - count < 32))
                {
                    // IO begins even during expansion; only the GPU upload retains its frame budget.
                    // A decoded image with no GPU residency cannot be consumed. Re-requesting
                    // those images evicts the 48MiB CPU LRU and completion wakes the map forever.
                    // Assign a bounded working-set slot before requesting a high decode.
                    if (route == 0 && slot == 0 && !expanding && (!highSlotsExhausted || asset == ownerAsset || asset == priorityAsset))
                    {
                        var oldest = Long.MAX_VALUE
                        for (k in 1..HIGH) {
                            if (keys[k] == null) {
                                slot = k
                                break
                            }
                            if (pinned[k] != drawEpoch && used[k] < oldest) {
                                slot = k
                                oldest = used[k]
                            }
                        }
                        if (slot == 0 && (asset == ownerAsset || asset == priorityAsset))
                        {
                            // A pressed/lifted cover outranks the far guard, even when all old slots were pinned.
                            for (k in 1..HIGH) if (keys[k] != ownerAsset && used[k] < oldest) {
                                slot = k
                                oldest = used[k]
                            }
                        }
                        if (slot == 0) highSlotsExhausted = true
                        if (slot > 0) {
                            assignHigh(slot, asset)
                        }
                    }
                    if (expanding && slot > 0 && !loaded[slot]) pendingUpload = true
                    if (slot > 0)
                    {
                        used[slot] = ++clock
                        pinned[slot] = drawEpoch
                        if (!loaded[slot] && !expanding)
                        {
                            val pixels = covers.full(url)
                            if (pixels != null)
                            {
                                val previewReady = small >= 0 && previewLoaded[small]
                                if ((previewReady || covers.unavailable(url)) && uploads < highUploadBudget && uploadHigh(slot, pixels)) {
                                    loaded[slot] = true
                                    covers.uploaded(url)
                                    uploads++
                                } else pendingUpload = true
                            }
                            else if (!expanding) covers.request(url, true)
                        }
                    }
                }
            }
        }
        // Resolve after all slot assignments so an owner promotion cannot leave stale earlier bindings.
        bindingInts!!.clear()
        for (n in 0 until count) {
            val at = 32 + n * 16
            val index = frame[at].toInt()
            val asset = trackAssets.getOrNull(index)
            if (asset == null || (arrival && frame[at + 9] <= .001f)) {
                bindingInts.put(0).put(0).put(-1).put(0).put(-0xaaaaab)
                continue
            }
            val high = asset.high
            val small = asset.preview
            val previewReady = small >= 0 && previewLoaded[small]
            bindingInts.put(high).put(if (high > 0 && loaded[high]) 1 else 0).put(small)
                .put(if (previewReady) 1 else 0).put(if (previewReady) previewColors[small] else -0xaaaaab)
        }
        bindingsArrival = arrivalRevision
        bindingsTopology = if (route == 1) topology else -1f
        bindingsCatalogue = dataGeneration
        bindingsContent = contentRevision
        bindingsOwner = owner
        bindingsPriority = priorityAsset
        bindingsPending = pendingUpload
        if (pendingUpload && frame[0] <= 0f && frame[ACTION + 26] < .5f && !uploadRetryPosted)
        {
            // Resource retry only, never an animation clock or a rendering loop.
            // Do not busy-submit unchanged Rust/HWUI frames while staging is in flight.
            uploadRetryPosted = true
            worker!!.postDelayed(uploadRetry, 4L)
        }
        if (drawnRevision == frame[23] && uploads == 0 && previewUploads == 0 && !paletteDirty) return false
        drawnRevision = frame[23]
        if (uploads > 0 || previewUploads > 0 || paletteDirty) backgroundRevision = -1f
        renderScene(count, false)
        paletteDirty = false
        return swapScene()
    }

    private fun uploadHigh(layer:Int, pixels:ByteBuffer?):Boolean {
        for (n in uploadPbos.indices)
        {
            val slot = (nextUpload + n) % uploadPbos.size
            if (uploadFences[slot] != 0L)
            {
                val status = GLES30.glClientWaitSync(uploadFences[slot], 0, 0L)
                if (status == GLES30.GL_WAIT_FAILED) throw IllegalStateException("HD staging fence failed")
                if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) continue
                GLES30.glDeleteSync(uploadFences[slot])
                uploadFences[slot] = 0
            }
            if (uploadPbos[slot] == 0)
            {
                GLES30.glGenBuffers(1, uploadPbos, slot)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, uploadPbos[slot])
                GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, DailySpatialCovers.MIPMAPS.bytes, null, GLES30.GL_STREAM_DRAW)
            }
            else
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, uploadPbos[slot])
            try
            {
                pixels!!.position(0)
                GLES30.glBufferSubData(GLES30.GL_PIXEL_UNPACK_BUFFER, 0, DailySpatialCovers.MIPMAPS.bytes, pixels)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, coverTexture)
                for ((index, level) in DailySpatialCovers.MIPMAPS.levels.withIndex()) {
                    GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, index, 0, 0, layer, level.side, level.side, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, level.offset)
                }
                // Publish only after every level has been submitted; one fence protects
                // the complete packed PBO from reuse while the GPU is still reading it.
                uploadFences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                if (uploadFences[slot] == 0L) throw IllegalStateException("HD staging fence unavailable")
            }

            finally
            {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            }
            nextUpload = (slot + 1) % uploadPbos.size
            coverBlend[layer] = 0f
            coverBlendStarted[layer] = System.nanoTime()
            coverFadeActive = true
            return true
        }
        return false
    }
    private fun swapScene():Boolean {
        android.os.Trace.beginSection("Daily.EGLSwap")
        try
        {
            if (!EGL14.eglSwapBuffers(display, eglSurface)) throw IllegalStateException("Surface swap failed")
            return true
        }

        finally
        {
            android.os.Trace.endSection()
        }
    }

    private fun renderScene(count:Int, warming:Boolean) {
        configureRenderSize(frame[DailySpatialProtocol.RHINE] > .5f)
        ensureSceneTargets()
        if (frame[DailySpatialProtocol.RHINE] > .5f) {
            renderRhine(count, warming)
            return
        }
        val action = ACTION
        val owner = if (frame[action + DailySpatialProtocol.LIFT_ACTIVE] > .5f) frame[action + 4].toInt() else -1
        val rebuild = warming || backgroundRevision != frame[action + DailySpatialProtocol.WORLD_REVISION] || backgroundOwner != owner || backgroundCatalogue != dataGeneration.toLong()
        val slot = frame[action + DailySpatialProtocol.OWNER_SLOT].toInt()
        val ownerAlpha = if (owner >= 0 && slot >= 0 && slot < count) frame[32 + slot * 16 + 9] else -1f
        if (rebuild || packedCount != count || packedOwnerAlpha != ownerAlpha)
        {
            packedBatches = SpatialNative.dailyPackInstances((sceneBytes)!!, (bindingBytes)!!, (instanceBytes)!!, density)
            if (packedBatches < 0 || frame[action + DailySpatialProtocol.ABI_VERSION] != DailySpatialProtocol.VERSION.toFloat()) throw IllegalStateException("Invalid daily instance buffers or protocol version")
            liftInstance = ((packedBatches.ushr(30)) and 1023L).toInt() - 1
            liftLevel = ((packedBatches.ushr(40)) and 3L).toInt()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuffer)
            instances!!.clear()
            instances.limit(count * INSTANCE_FLOATS)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, CAPACITY * INSTANCE_FLOATS * 4, null, GLES30.GL_STREAM_DRAW)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * INSTANCE_FLOATS * 4, instances)
            packedCount = count
            packedOwnerAlpha = ownerAlpha
        }
        val batches = packedBatches
        bindWall()
        if (rebuild)
        {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glViewport(0, 0, width, height)
            val bg = if (dark) 0f else 1f
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(bg, bg, bg, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUniform1f(locations.getValue("u_layer"), 0f)
            var first = 0
            for (level in 0..2)
            {
                val batch = if (warming) count else ((batches.ushr((level * 10))) and 1023L).toInt()
                if (batch == 0) continue
                if (warming) first = 0
                GLES30.glBindVertexArray(meshVaos[level])
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuffer)
                for (loc in 1..4) GLES30.glVertexAttribPointer(loc, 4, GLES30.GL_FLOAT, false, INSTANCE_FLOATS * 4, first * INSTANCE_FLOATS * 4 + (loc - 1) * 16)
                GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[level], GLES30.GL_UNSIGNED_SHORT, 0, batch)
                first += batch
            }
            filterBackground(owner >= 0 || warming)
            backgroundRevision = frame[action + DailySpatialProtocol.WORLD_REVISION]
            backgroundOwner = owner
            backgroundCatalogue = dataGeneration.toLong()
        }
        composeTo(0, 0, 0)
        drawLifted(0, 0, 0)
    }
    private fun renderRhine(count:Int, warming:Boolean) {
        if (frame[ACTION + DailySpatialProtocol.ABI_VERSION] != DailySpatialProtocol.VERSION.toFloat()) throw IllegalStateException("Rhine protocol mismatch")
        if (rhineRenderer == null) {
            try
            {
                rhineRenderer = DailyRhineRenderer(context, hdrSurface)
                rhineRenderer!!.setHdrHeadroom(hdrHeadroom)
            }
            catch (failure:Exception) {
                throw IllegalStateException("Rhine renderer unavailable", failure)
            }

        }
        rhineRenderer!!.setCoverBlend(coverBlend)
        val owner = if (frame[ACTION + 27] > .5f) frame[ACTION + 4].toInt() else -1
        val rebuild = warming || backgroundRevision != frame[ACTION + 124] || backgroundOwner != owner || backgroundCatalogue != dataGeneration.toLong()
        sceneBytes!!.putFloat((DailySpatialProtocol.RHINE + 18) * 4, if (dark) 0f else -1f)
        sceneBytes.putFloat((DailySpatialProtocol.RHINE + DailySpatialProtocol.RHINE_HDR_HEADROOM) * 4, if (hdrSurface) hdrHeadroom else 1f)
        if (rebuild)
        {
            // Keep the actual padded target, including its screen-edge/blur guard.
            // Rust filters draw submissions only; full preload, hits and lights stay intact.
            val clip = (DailySpatialProtocol.RHINE + DailySpatialProtocol.RHINE_CLIP_LEFT) * 4
            sceneBytes.putFloat(clip, -scenePadX / density)
            sceneBytes.putFloat(clip + 4, -scenePadY / density)
            sceneBytes.putFloat(clip + 8, (width + scenePadX) / density)
            sceneBytes.putFloat(clip + 12, (height + scenePadY) / density)
            sceneBytes.putFloat((DailySpatialProtocol.RHINE + DailySpatialProtocol.RHINE_CLIP_ENABLED) * 4, 1f)
            val changed:Boolean
            android.os.Trace.beginSection("Daily.RustLightPack")
            try
            {
                changed = rhineRenderer!!.updateBackground(sceneBytes, bindingBytes)
            }

            finally
            {
                android.os.Trace.endSection()
            }
            // Revisions conservatively cover moving lights/owners, even when the
            // actual background submission is identical. Keep its rendered pixels
            // and Gaussian caches in that case. New GPU pixels still force a redraw.
            if (changed || warming || backgroundRevision < 0)
            {
                if (warming || backgroundRevision < 0) rhineRenderer!!.invalidateRefractionSource()
                android.os.Trace.beginSection("Daily.RhinePasses")
                try
                {
                    rhineRenderer!!.background(frame, density, dark, sceneFbo, coverTexture, previewTexture, sceneColor, width, height, scenePadX, scenePadY)
                    filterBackground(false)
                }

                finally
                {
                    android.os.Trace.endSection()
                }
                rhineContextReady = false
            }
            backgroundRevision = frame[ACTION + 124]
            backgroundOwner = owner
            backgroundCatalogue = dataGeneration.toLong()
        }
        if ((warming || frame[ACTION + 3] > 0) && !rhineContextReady) {
            filterContext()
            rhineContextReady = true
        }
        rhineRenderer!!.updateForeground(sceneBytes, bindingBytes)
        packedCount = count
        composeTo(0, 0, 0)
        drawLifted(0, 0, 0)
    }
    private fun bindWall() {
        GLES30.glUseProgram(program)
        GLES30.glUniform4fv(locations.getValue("u_cover_blend[0]"), coverBlend.size / 4, coverBlend, 0)
        GLES30.glUniform1f(locations.getValue("u_linear_output"), (0).toFloat())
        GLES30.glUniform2f(locations.getValue("u_screen"), width / density, height / density)
        GLES30.glUniform2f(locations.getValue("u_center"), frame[7], frame[8])
        GLES30.glUniform2f(locations.getValue("u_radius"), frame[5], frame[6])
        GLES30.glUniform2f(locations.getValue("u_pan"), frame[2], frame[3])
        GLES30.glUniform1f(locations.getValue("u_zoom"), frame[4])
        GLES30.glUniform1f(locations.getValue("u_mode"), frame[1])
        GLES30.glUniform1f(locations.getValue("u_header"), frame[20])
        GLES30.glUniform1f(locations.getValue("u_scroll"), frame[21])
        GLES30.glUniform1f(locations.getValue("u_dark"), (if (dark) 1 else 0).toFloat())
        GLES30.glUniform3f(locations.getValue("u_accent"), ((accent shr 16) and 255) / 255f, ((accent shr 8) and 255) / 255f, (accent and 255) / 255f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, coverTexture)
        GLES30.glUniform1i(locations.getValue("u_covers"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTexture)
        GLES30.glUniform1i(locations.getValue("u_previews"), 1)
        val a = ACTION
        GLES30.glUniform4f(locations.getValue("u_lift_rect"), frame[a + 28], frame[a + 29], frame[a + 30], frame[a + 31])
        GLES30.glUniform1f(locations.getValue("u_lift_mix"), frame[a + 24])
        GLES30.glUniform1f(locations.getValue("u_lift_radius"), frame[a + 21])
    }
    private fun drawLifted(target:Int, left:Int, bottom:Int) {
        if (frame[DailySpatialProtocol.RHINE] > .5f) {
            if (rhineRenderer != null) rhineRenderer!!.foreground(frame, density, dark, target, left, bottom, coverTexture, previewTexture, sceneColor, sceneDepth, blurTextures[1], blurTextures[3], blurTextures[6])
            return
        }
        if (liftInstance < 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(-left, -bottom, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        bindWall()
        GLES30.glUniform1f(locations.getValue("u_linear_output"), (if (hdrSurface && target == 0) 1 else 0).toFloat())
        GLES30.glUniform1f(locations.getValue("u_layer"), 1f)
        GLES30.glBindVertexArray(meshVaos[liftLevel])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuffer)
        for (loc in 1..4) GLES30.glVertexAttribPointer(loc, 4, GLES30.GL_FLOAT, false, INSTANCE_FLOATS * 4, liftInstance * INSTANCE_FLOATS * 4 + (loc - 1) * 16)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, meshCounts[liftLevel], GLES30.GL_UNSIGNED_SHORT, 0, 1)
    }

    private fun warmPipeline(epoch:Int) {
        if (closed || epoch != generation) return
        val warmState = SpatialNative.dailySpatialCreate()
        if (warmState == 0L) throw IllegalStateException("Daily warm scene unavailable")
        try
        {
            SpatialNative.dailySpatialCommand(warmState, 0, 1, (0).toFloat(), (0).toFloat())
            SpatialNative.dailySpatialCommand(warmState, 3, 1, (0).toFloat(), (0).toFloat())
            SpatialNative.dailySpatialCommand(warmState, 2, 0, (1).toFloat(), (0).toFloat())
            // Initialize the otherwise unused layer zero so the high-res sampler is exercised.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, coverTexture)
            val blank = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
            for ((index, level) in DailySpatialCovers.MIPMAPS.levels.withIndex()) {
                blank.position(0)
                GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, index, 0, 0, 0, level.side, level.side, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, blank)
            }
            for (phase in 0..2)
            {
                if (!SpatialNative.dailySpatialStep(warmState, .25f, width / density, height / density, (sceneBytes)!!)) throw IllegalStateException("Daily warm scene failed")
                sceneFloats!!.position(0)
                sceneFloats.get(frame)
                frame[1] = phase * .5f
                sceneBytes.putFloat(4, frame[1])
                // A one-song catalogue now repeats spatially; warm just the nearest instance.
                frame[16] = 1f
                sceneBytes.putFloat(16 * 4, 1f)
                bindingInts!!.clear()
                bindingInts.put(0).put(if (phase == 0) 1 else 0).put(0).put(if (phase == 1) 1 else 0).put(-0xac9480)
                frame[DailySpatialProtocol.RHINE] = 0f
                sceneBytes.putFloat(DailySpatialProtocol.RHINE * 4, (0).toFloat())
                renderScene(1, true)
            }
            // Establish the portrait visible set before targeting its long-press owner.
            SpatialNative.dailySpatialStep(warmState, (0).toFloat(), Math.min(width, height) / density, Math.max(width, height) / density, (sceneBytes)!!)
            sceneFloats!!.position(0)
            sceneFloats.get(frame)
            SpatialNative.dailySpatialCommand(warmState, 15, frame[39].toInt(), (0).toFloat(), (0).toFloat())
            SpatialNative.dailySpatialStep(warmState, .25f, Math.min(width, height) / density, Math.max(width, height) / density, (sceneBytes))
            sceneFloats.position(0)
            sceneFloats.get(frame)
            frame[16] = 1f
            sceneBytes.putFloat(16 * 4, 1f)
            bindingInts!!.clear()
            bindingInts.put(0).put(1).put(0).put(1).put(-0xac9480)
            frame[ACTION + 27] = 0f
            sceneBytes.putFloat((ACTION + 27) * 4, (0).toFloat())
            renderScene(1, true)
            frame[ACTION + 27] = 1f
            frame[ACTION + 23] = 0f
            sceneBytes.putFloat((ACTION + 27) * 4, (1).toFloat())
            sceneBytes.putFloat((ACTION + 23) * 4, (0).toFloat())
            renderScene(1, true)
            if (width >= height) {
                frame[DailySpatialProtocol.RHINE] = 0f
                sceneBytes.putFloat(DailySpatialProtocol.RHINE * 4, (0).toFloat())
                renderScene(1, true)
            }
        }

        finally
        {
            backgroundRevision = -1f
            SpatialNative.dailySpatialRelease(warmState)
            drawnRevision = -1f
            lastMotionStep = 0
        }
        gpuFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (gpuFence == 0L) throw IllegalStateException("Daily warm fence unavailable")
        GLES30.glFlush()
        finishWarmPipeline(epoch, System.nanoTime())
    }
    private fun finishWarmPipeline(epoch:Int, started:Long) {
        if (closed || epoch != generation) return
        val status = GLES30.glClientWaitSync(gpuFence, 0, 0L)
        if (status == GLES30.GL_TIMEOUT_EXPIRED)
        {
            if (System.nanoTime() - started > 10_000_000_000L) {
                fail()
                return
            }
            // Preparation only: yield the worker instead of blocking CPU or interactive frames.
            worker!!.postDelayed({ finishWarmPipeline(epoch, started) }, 4L)
            return
        }
        if (status == GLES30.GL_WAIT_FAILED) {
            fail()
            return
        }
        GLES30.glDeleteSync(gpuFence)
        gpuFence = 0
        texturesReady = true
        main.post({ if (!closed && epoch == generation) {
                listener!!.onReady()
                listener.onDirty()
            } })
    }
    private fun createMesh(level:Int, divisions:Int) {
        val count = divisions * divisions * 6
        meshCounts[level] = count
        val mesh = direct((divisions + 1) * (divisions + 1) * 2)
        for (y in 0..divisions) for (x in 0..divisions) {
            mesh!!.put(x / divisions.toFloat()).put(y / divisions.toFloat())
        }
        mesh!!.flip()
        val indices = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (y in 0 until divisions) for (x in 0 until divisions) {
            val a = y * (divisions + 1) + x
            val b = a + 1
            val c = a + divisions + 1
            val d = c + 1
            indices.put(a.toShort()).put(b.toShort()).put(c.toShort()).put(c.toShort()).put(b.toShort()).put(d.toShort())
        }
        indices.flip()
        val id = IntArray(1)
        GLES30.glGenVertexArrays(1, id, 0)
        meshVaos[level] = id[0]
        GLES30.glBindVertexArray(id[0])
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.remaining() * 4, mesh, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, count * 2, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuffer)
        for (loc in 1..4) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, 4, GLES30.GL_FLOAT, false, INSTANCE_FLOATS * 4, (loc - 1) * 16)
            GLES30.glVertexAttribDivisor(loc, 1)
        }
    }
    @Throws(Exception::class)
    private fun linkProgram(vertex:String?, fragment:String?):Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException(GLES30.glGetProgramInfoLog(p))
        return p
    }
    private fun renderTexture(w:Int, h:Int, depth:Boolean, floating:Boolean = sceneHdr):Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, if (depth) GLES30.GL_DEPTH_COMPONENT24 else (if (floating) GLES30.GL_RGBA16F else GLES30.GL_RGBA8), w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (depth) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, if (depth) GLES30.GL_NEAREST else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    private fun framebuffer(color:Int, depth:Int):Int {
        val id = IntArray(1)
        GLES30.glGenFramebuffers(1, id, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, color, 0)
        if (depth != 0) GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, depth, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("Daily depth framebuffer incomplete")
        return id[0]
    }
    private fun ensureSceneTargets() {
        // Allocate once during preparation: a shrinking background must sample real offscreen
        // geometry, not repeat the outermost texels via CLAMP_TO_EDGE.
        val rhine = frame[DailySpatialProtocol.RHINE] > .5f
        val px = if (rhine) Math.ceil(width * .035).toInt() else 0
        val py = if (rhine) Math.ceil(height * .035).toInt() else 0
        val tw = width + 2 * px
        val th = height + 2 * py
        val floating = hdrSurface && rhine && hdrHeadroom > 1f
        if (sceneWidth == tw && sceneHeight == th && scenePadX == px && scenePadY == py && sceneHdr == floating && sceneFbo != 0) return
        deleteSceneTargets()
        sceneHdr = floating
        sceneWidth = tw
        sceneHeight = th
        scenePadX = px
        scenePadY = py
        val bands = SpatialNative.dailyBlurRegions(sceneHeight, density, frame[26] + scenePadY / density, frame[27] + scenePadY / density, frame[29])
        blurTopRows = (bands.ushr(32)).toInt()
        blurBottomRows = bands.toInt()
        sceneColor = renderTexture(sceneWidth, sceneHeight, false)
        sceneDepth = renderTexture(sceneWidth, sceneHeight, true)
        sceneFbo = framebuffer(sceneColor, sceneDepth)
        val w = Math.max(1, (sceneWidth + 3) / 4)
        val h = Math.max(1, (sceneHeight + 3) / 4)
        for (i in 0..7)
        {
            val divisor = if (i < 4) 4 else if (i == 4) 2 else if (i == 5) 4 else 8
            blurWidths[i] = Math.max(1, (sceneWidth + divisor - 1) / divisor)
            blurHeights[i] = Math.max(1, (sceneHeight + divisor - 1) / divisor)
            blurTextures[i] = renderTexture(blurWidths[i], blurHeights[i], false)
            blurFbos[i] = framebuffer(blurTextures[i], 0)
        }
        if (!SpatialNative.dailyContextBlurKernel(frame[ACTION + 20], density, (contextKernelBytes)!!)) throw IllegalStateException("Invalid context blur kernel")
        composeTextures[0] = sceneColor
        composeTextures[1] = sceneDepth
        composeTextures[2] = blurTextures[1]
        composeTextures[3] = blurTextures[3]
        composeTextures[4] = blurTextures[6]
    }
    private fun blurPass(input:Int, output:Int, xStep:Float, yStep:Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFbos[output])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
        GLES30.glUniform2f(blurStepLocation, xStep, yStep)
        val w = Math.max(1, (sceneWidth + 3) / 4)
        val h = Math.max(1, (sceneHeight + 3) / 4)
        // Rust expands both bands by the complete two-level filter footprint. The omitted
        // middle pixels are never sampled by the final composition, so blur quality is unchanged.
        if (blurTopRows > 0) {
            GLES30.glScissor(0, h - blurTopRows, w, blurTopRows)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        if (blurBottomRows > 0) {
            GLES30.glScissor(0, 0, w, blurBottomRows)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }
    private fun filterBackground(context:Boolean) {
        // Landscape has no screen-edge blur. Context/lift glass is a separate material.
        if (frame[30] <= 0f) {
            if (context) filterContext()
            return
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(fullVao)
        GLES30.glUseProgram(blurProgram)
        GLES30.glUniform1i(blurImageLocation, 0)
        GLES30.glViewport(0, 0, Math.max(1, (sceneWidth + 3) / 4), Math.max(1, (sceneHeight + 3) / 4))
        val radius = frame[29] * density / 3.230769f
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        blurPass(sceneColor, 0, radius * .28f / sceneWidth, 0f)
        blurPass(blurTextures[0], 1, 0f, radius * .28f / sceneHeight)
        blurPass(blurTextures[1], 2, radius / sceneWidth, 0f)
        blurPass(blurTextures[2], 3, 0f, radius / sceneHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if (context) filterContext()
    }

    private fun filterContext() {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(fullVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUseProgram(downsampleProgram)
        GLES30.glUniform1i(downsampleImage, 0)
        var input = sceneColor
        var sourceWidth = sceneWidth
        var sourceHeight = sceneHeight
        for (level in 4..6)
        {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFbos[level])
            GLES30.glViewport(0, 0, blurWidths[level], blurHeights[level])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
            GLES30.glUniform2f(downsampleTexel, 1f / sourceWidth, 1f / sourceHeight)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            input = blurTextures[level]
            sourceWidth = blurWidths[level]
            sourceHeight = blurHeights[level]
        }
        GLES30.glUseProgram(contextBlurProgram)
        GLES30.glUniform1i(contextImage, 0)
        contextKernel!!.position(0)
        GLES30.glUniform2fv(contextKernelLocation, 7, contextKernel)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFbos[7])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTextures[6])
        GLES30.glUniform2f(contextStep, 1f / blurWidths[6], (0).toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFbos[6])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTextures[7])
        GLES30.glUniform2f(contextStep, (0).toFloat(), 1f / blurHeights[7])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }
    private fun composeTo(target:Int, left:Int, bottom:Int) {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(fullVao)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(-left, -bottom, width, height)
        GLES30.glUseProgram(composeProgram)
        GLES30.glUniform1f(composeOutputLocation, (if (hdrSurface) (if (target == 0) 1 else 2) else 0).toFloat())
        val rhine = DailySpatialProtocol.RHINE
        GLES30.glUniform1f(composeRhineLocation, frame[rhine])
        GLES30.glUniform4f(composeKeyLocation, frame[rhine + 8], frame[rhine + 9], frame[rhine + 10], frame[rhine + 11])
        for (i in 0..4) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, composeTextures[i])
            GLES30.glUniform1i(composeSamplers[i], i)
        }
        GLES30.glUniform4f(composePolicyLocation, frame[26], frame[27], frame[28], frame[30])
        GLES30.glUniform1f(composeHeightLocation, height / density)
        GLES30.glUniform1f(composeDarkLocation, (if (dark) 1 else 0).toFloat())
        val a = ACTION
        GLES30.glUniform4f(composeRectLocation, scenePadX / sceneWidth.toFloat(), scenePadY / sceneHeight.toFloat(), width / sceneWidth.toFloat(), height / sceneHeight.toFloat())
        GLES30.glUniform1f(composeScaleLocation, frame[a + DailySpatialProtocol.BACKGROUND_SCALE])
        GLES30.glUniform1f(composeMixLocation, frame[a + 1])
        GLES30.glUniform4f(composeBoundsLocation, frame[a + 6], frame[a + 7], frame[a + 8], frame[a + 9])
        GLES30.glUniform2f(composeScreenLocation, width / density, height / density)
        GLES30.glUniform1f(composeShadowLocation, frame[a + 126])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }
    private fun deleteSceneTargets() {
        if (rhineRenderer != null) rhineRenderer!!.invalidateSceneAttachment()
        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            GLES30.glDeleteTextures(2, intArrayOf(sceneColor, sceneDepth), 0)
            GLES30.glDeleteFramebuffers(8, blurFbos, 0)
            GLES30.glDeleteTextures(8, blurTextures, 0)
        }
        backgroundRevision = -1f
        rhineContextReady = false
        liftInstance = -1
        packedCount = -1
        packedOwnerAlpha = -1f
        scenePadY = 0
        scenePadX = scenePadY
        sceneHeight = scenePadX
        sceneWidth = sceneHeight
        sceneDepth = sceneWidth
        sceneColor = sceneDepth
        sceneFbo = sceneColor
        java.util.Arrays.fill(blurFbos, 0)
        java.util.Arrays.fill(blurTextures, 0)
    }
    private fun fail(failure:Throwable?) {
        android.util.Log.e("DailySpatial", "Renderer failed", failure)
        fail()
    }
    private fun fail() {
        destroy(false)
        main.post({ if (!closed) listener!!.onFailure() })
    }
    private fun destroy(releaseTexture:Boolean) {
        if (capture != null) finishCapture(capture, null, "surface-destroyed", null)
        generation++
        worker!!.removeCallbacks(uploadRetry)
        worker.removeCallbacks(coverFade)
        coverFadePosted = false
        uploadRetryPosted = false
        java.util.Arrays.fill(uploadPbos, 0)
        java.util.Arrays.fill(uploadFences, 0)
        nextUpload = 0
        artworkPlan = null
        destinationAssets.clear()
        covers!!.highPreparation(false)
        texturesReady = false
        gpuFence = 0
        lastMotionStep = 0
        rhineRenderer = null
        drawnRoute = -1
        hdrSurface = false
        // Context destruction owns all remaining GL allocations; never issue deletes without it.
        backgroundRevision = -1f
        rhineContextReady = false
        liftInstance = -1
        packedCount = -1
        packedOwnerAlpha = -1f
        scenePadY = 0
        scenePadX = scenePadY
        sceneHeight = scenePadX
        sceneWidth = sceneHeight
        sceneDepth = sceneWidth
        sceneColor = sceneDepth
        sceneFbo = sceneColor
        java.util.Arrays.fill(blurFbos, 0)
        java.util.Arrays.fill(blurTextures, 0)
        if (display !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        covers.releaseHigh()
        program = 0
        locations.clear()
        resetAssets()
        if (surface != null) {
            surface!!.release()
            surface = null
        }
        if (releaseTexture && textureOwner != null) textureOwner!!.release()
        textureOwner = null
    }

    companion object {
        private const val COVER_FADE_NANOS = 220_000_000L
        private val CAPACITY:Int = DailySpatialProtocol.SLOTS
        private val HIGH:Int = 96
        private val SIZE:Int = DailySpatialCovers.SIZE
        private val INSTANCE_FLOATS:Int = 16
        private val PREVIEWS:Int = DailySpatialCovers.PREVIEWS
        private val TILE:Int = DailySpatialCovers.TILE
        private val ACTION:Int = DailySpatialProtocol.ACTION
        private fun direct(n:Int):FloatBuffer? {
            return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
    }
}
