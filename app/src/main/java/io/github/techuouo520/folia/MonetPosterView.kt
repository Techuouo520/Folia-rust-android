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
        view.layoutParams = params
        view.setMonetTextSize(rect[4], rect[5])
    }

    fun update(song: ArtworkOwner?, snapshot: PlaybackSnapshot, night: Boolean, options: MonetOptions, visible: Boolean) {
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
        val nextPlaying = snapshot.state == TransportState.PLAYING
        val nextPosition = (snapshot.positionSeconds * 1000).roundToLong()
        val clockChanged = position != nextPosition || nextPlaying != playing || active != visible
        active = visible
        playing = nextPlaying
        position = nextPosition
        publish()
        if (clockChanged && rowsReady && active) ticker?.setPlaybackState(position, playing)
        poster.update(song, night, playing, options, visible)
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
        resetTicker()
        ticker?.let(::removeView)
        ticker = null
        poster.release()
    }
}
