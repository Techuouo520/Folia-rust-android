package io.github.techuouo520.folia

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.lab.hv.NowPlayingLyricsTickerView
import com.lab.hv.util.AppStrings
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.MonetOptions
import io.github.techuouo520.folia.bridge.MonetTickerLine
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.TransportState
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Both children share the poster's pixel coordinate system. The ticker owns only the lyric rail. */
internal class MonetPosterView(context: Context, bridge: FoliaBridge) : FrameLayout(context) {
    val poster = MonetView(context, bridge)
    private var ticker: NowPlayingLyricsTickerView? = null
    private var bounds: List<Float>? = null
    private var owner: ArtworkOwner? = null
    private var dark = false
    private var active = false
    private var playing = false
    private var position = 0L
    private var rowGeneration = 0L
    private var rowsReady = false
    private var rowsFailed = false
    private var posterStatus = 0
    private var viewport = LyricsViewport()
    // FLIP only the retained rail's presentation. Text is measured once at the final
    // width; Rust springs carry position/velocity through another resize mid-flight.
    private val railMotion = Array(5) { doubleArrayOf(0.0, 0.0) }
    private val railTarget = DoubleArray(5)
    private val railClip = android.graphics.Rect()
    private var railInitialized = false
    private var railAnimating = false
    private var railFrameTime = 0L
    private val railClock get() = android.view.Choreographer.getInstance()
    private val railFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = if (railFrameTime == 0L) 1.0/60.0 else (now-railFrameTime)/1e9
            railFrameTime = now
            var moving = false
            for (i in 0..4) moving = MotionKernel.travel(railMotion[i], railTarget[i], dt, 14.0, true) || moving
            railAnimating = moving
            applyRailTransform()
            if (moving) railClock.postFrameCallback(this)
        }
    }

    private fun applyRailTransform() {
        ticker?.apply {
            pivotX = 0f; pivotY = 0f
            translationX = (railMotion[0][0]-railTarget[0]).toFloat()
            translationY = (railMotion[1][0]-railTarget[1]).toFloat()
            // Preserve glyph proportions. The viewport changes independently of font
            // scale; stretching a portrait-height rail into landscape distorts text.
            val fontScale = (railMotion[4][0]/railTarget[4].coerceAtLeast(1.0)).toFloat().coerceAtLeast(.01f)
            scaleX = fontScale
            scaleY = fontScale
            railClip.set(0, 0, (railMotion[2][0]/fontScale).roundToInt().coerceAtLeast(1),
                (railMotion[3][0]/fontScale).roundToInt().coerceAtLeast(1))
            clipBounds = if (railAnimating) railClip else null
            if (!railAnimating) {
                translationX=0f; translationY=0f; scaleX=1f; scaleY=1f
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (railInitialized && rowsReady && !railAnimating) {
            railAnimating=true; railFrameTime=0L
            railClock.postFrameCallback(railFrame)
        }
    }

    override fun onDetachedFromWindow() {
        railClock.removeFrameCallback(railFrame)
        railAnimating = false
        super.onDetachedFromWindow()
    }
    var report: (Int) -> Unit = {}

    init {
        AppStrings.init(context)
        addView(poster, LayoutParams(-1, -1))
        poster.onRail = { geometry, lines ->
            if (geometry != null) bounds = geometry
            if (lines != null) replaceRows(lines) else geometry?.let { placeTicker() }
        }
        poster.report = {
            posterStatus = it
            publish()
        }
    }

    private fun replaceRows(lines: List<MonetTickerLine>) {
        resetTicker()
        if (lines.isEmpty()) return
        val prepareStarted = android.os.SystemClock.uptimeMillis()
        android.util.Log.i("FoliaLyrics", "Preparing ${lines.size} received lyric lines")
        // Keep the original row pools, spring chain and parse executor across songs.
        val view = ticker ?: NowPlayingLyricsTickerView(context).also {
            ticker = it
            it.visibility = View.INVISIBLE
            it.applyDarkMode(dark)
            it.setOnLyricSeekListener { ms -> poster.seek(ms) }
            addView(it, LayoutParams(0, 0))
        }
        placeTicker()
        view.setPlaybackState(position, playing && active, true)
        val generation = rowGeneration
        view.setOnLyricsErrorListener {
            if (generation == rowGeneration) {
                view.setOnRowsReadyListener(null)
                rowsReady = false
                rowsFailed = true
                poster.setTickerReady(false)
                publish()
            }
        }
        view.setOnRowsReadyListener {
            if (generation == rowGeneration && !view.isLyricsParsePending && view.hasPreparedRows()) {
                view.setOnRowsReadyListener(null)
                view.setPlaybackState(position, playing && active, true)
                view.prepareForRevealFrame {
                    if (generation == rowGeneration && !rowsFailed) {
                        rowsReady = true
                        android.util.Log.i("FoliaLyrics", "Lyric rows ready in ${android.os.SystemClock.uptimeMillis() - prepareStarted} ms")
                        poster.setTickerReady(true)
                        publish()
                    }
                }
            }
        }
        view.setMonetLines(lines)
    }

    private fun placeTicker() {
        val view = ticker ?: return
        val rect = bounds ?: return
        if (rect.size != 6) return
        val params = LayoutParams(rect[2].roundToInt().coerceAtLeast(1), rect[3].roundToInt().coerceAtLeast(1))
        params.leftMargin = rect[0].roundToInt()
        params.topMargin = rect[1].roundToInt()
        params.gravity = android.view.Gravity.LEFT or android.view.Gravity.TOP
        val targets = doubleArrayOf(params.leftMargin.toDouble(), params.topMargin.toDouble(),
            params.width.toDouble(), params.height.toDouble(), rect[4].toDouble())
        val changed = !targets.contentEquals(railTarget)
        for (i in 0..4) {
            railTarget[i] = targets[i]
            if (!railInitialized || !rowsReady) { railMotion[i][0] = targets[i]; railMotion[i][1] = 0.0 }
        }
        railInitialized = true
        if (changed) view.layoutParams = params
        if (changed && rowsReady && !railAnimating) {
            railAnimating = true
            railFrameTime = 0L
            railClock.postFrameCallback(railFrame)
        }
        applyRailTransform()
        view.setMonetTextSize(rect[4], rect[5], viewport.landscape)
    }

    fun update(song: ArtworkOwner?, snapshot: PlaybackSnapshot, night: Boolean, options: MonetOptions, visible: Boolean, viewport: LyricsViewport) {
        this.viewport = viewport
        val changed = owner != song
        if (changed) {
            resetTicker()
            owner = song
            bounds = null
            posterStatus = 0
        }
        if (dark != night) {
            dark = night
            ticker?.applyDarkMode(night)
        }
        val nextPlaying = snapshot.state == TransportState.PLAYING && !snapshot.loading
        val nextPosition = (snapshot.positionSeconds * 1000).roundToLong()
        val clockChanged = position != nextPosition || nextPlaying != playing || active != visible
        active = visible
        playing = nextPlaying
        position = nextPosition
        publish()
        if (clockChanged && rowsReady && active) ticker?.setPlaybackState(position, playing)
        poster.update(song, night, playing, options, visible, viewport)
    }

    private fun publish() {
        val shown = rowsReady && posterStatus == 1 && active
        ticker?.let { view ->
            val target = if (shown) View.VISIBLE else View.INVISIBLE
            if (view.visibility != target) {
                view.visibility = target
                view.setTickerEnabled(shown)
                if (shown) view.setPlaybackState(position, playing, true)
            }
        }
        report(when {
            posterStatus == 1 && rowsFailed -> 5
            posterStatus == 1 && !rowsReady -> 0
            else -> posterStatus
        })
    }

    private fun resetTicker() {
        rowGeneration++
        rowsReady = false
        rowsFailed = false
        poster.setTickerReady(false)
        ticker?.let { view ->
            view.setOnRowsReadyListener(null)
            view.setOnLyricsErrorListener(null)
            view.setTickerEnabled(false)
            view.cancelPendingLyricsWork()
            view.visibility = View.INVISIBLE
        }
    }

    fun retryLyrics() {
        resetTicker()
        posterStatus = 0
        publish()
        poster.retryLyrics()
    }

    fun release() {
        railClock.removeFrameCallback(railFrame)
        railAnimating = false
        resetTicker()
        ticker?.let(::removeView)
        ticker = null
        poster.release()
    }
}
