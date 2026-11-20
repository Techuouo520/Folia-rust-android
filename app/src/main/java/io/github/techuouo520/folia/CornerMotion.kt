package io.github.techuouo520.folia

import kotlin.math.*

internal const val CORNER_BUTTON_DIAMETER = 50f
internal const val CORNER_BUTTON_RADIUS = CORNER_BUTTON_DIAMETER / 2f

internal data class CornerRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * .5f
    val centerY get() = (top + bottom) * .5f
}

internal data class CornerContent(val x: Float, val y: Float, val scale: Float, val alpha: Float)
internal fun cornerSmooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Layout adapter for the Rust port of RealtimeFrostedGlassView's default bubble motion, including
 * animateBubbleTo, DropSway, stepBubbleStretch/applyBubbleDrop, content fling,
 * motionBlurShare and live-size spring. Coordinates are dp (the source's px / density).
 * Liquid optics are deliberately separate: this class only supplies motion/geometry.
 */
internal class CornerMotion(val bottomOrigin: Boolean = false, initiallyOpen: Boolean = false, val flight: DockFlight? = null) {
    // Layout-visible fields of ui_motion::Bubble. Its velocities stay in this same array on retarget.
    private val state = FloatArray(31).apply {
        this[0] = if (initiallyOpen) 1f else 0f
        this[1] = this[0]
        this[9] = 1f
        this[11] = CORNER_BUTTON_DIAMETER
        this[21] = CORNER_BUTTON_RADIUS
        this[24] = 1f
        this[27] = 1f
        this[28] = 1f
    }
    val x get() = state[0]
    val y get() = state[1]
    val blur get() = state[4]
    private val stretchX get() = state[5]
    private val stretchY get() = state[6]
    private val resizeProgress get() = state[9]
    private val height get() = state[11]
    val pressScaleX get() = state[27]
    val pressScaleY get() = state[28]
    private val morphing get() = state[29] != 0f
    private val resizing get() = state[30] != 0f
    val progress get() = ((x + y) * .5f).coerceIn(0f, 1f)
    val docked get() = !opening && !morphing
    private var opening = initiallyOpen
    private var blurStart = 0f
    private var blurCarry = 0f
    private var width = CORNER_BUTTON_DIAMETER
    val layoutHeight get() = height
    private var wantedHeight = CORNER_BUTTON_DIAMETER
    private var resizeFromHeight = CORNER_BUTTON_DIAMETER

    private fun resize(progress: Float, active: Boolean) {
        state[9] = progress
        state[10] = 0f
        state[30] = if (active) 1f else 0f
    }
    private var measured = false
    private var rowCount = 0
    private var appearFrom = Int.MAX_VALUE
    private var right = false
    private val rightShare get() = flight?.rightShare ?: if (right) 1f else 0f

    fun anchorX(rect: CornerRect, radius: Float) = mix(rect.left + radius, rect.right - radius, rightShare)
    private var dock = CornerRect(0f,0f,CORNER_BUTTON_DIAMETER,CORNER_BUTTON_DIAMETER)

    /** Live resting outline from the same bubble motion; changing it never restarts the window. */
    fun dockBounds(rect: CornerRect) { dock=rect }

    fun bounds(width: Float, height: Float?, right: Boolean, liveHeight: Boolean = false): Boolean {
        var changed=false
        this.width = width.coerceAtLeast(CORNER_BUTTON_DIAMETER)
        this.right = right
        if (height != null && liveHeight) {
            // The outer reflow already integrates height. A second resize spring would
            // reset its velocity on each frame and leave the outline behind its content.
            state[11]=height.coerceAtLeast(CORNER_BUTTON_DIAMETER)
            wantedHeight=state[11]; resizeFromHeight=state[11]; measured=true; resize(1f,false)
        } else if (height != null && (opening || docked)) {
            val next=height.coerceAtLeast(CORNER_BUTTON_DIAMETER)
            if (!measured || docked) {
                state[11] = next; wantedHeight = next; resize(1f, false); measured = true
            } else if (next != wantedHeight) {
                resizeFromHeight=this.height;wantedHeight=next
                resize(0f, true); changed = true
            }
        }
        return changed
    }

    fun rows(count: Int) {
        if(count!=rowCount) {
            appearFrom=if(opening && count>rowCount) rowCount else Int.MAX_VALUE
            rowCount=count
        }
    }
    fun rowAppearance(index: Int): Float = if(resizing && index>=appearFrom) cornerSmooth((resizeProgress-.3f)/.6f) else 1f

    /** A new intent turns the running motion, carrying position, stretch, sway and blur. */
    fun target(open: Boolean) {
        if (opening == open) return
        opening = open
        if (!open && resizing) {
            // The close starts from the live expanded shape; no resize runs underneath it.
            wantedHeight = height; resize(1f, false)
        }
        state[29] = 1f
        val kick = if (open) 1.4f else -1.4f
        state[2] += kick; state[3] += kick
        blurStart = progress
        blurCarry = blur
    }

    fun step(open: Boolean, seconds: Float, pressed: Boolean = false): Boolean {
        target(open)
        val wasResizing = resizing
        val active = MotionKernel.bubble(state, open, pressed, seconds, width, dock.height, rightShare,
            resizeFromHeight, wantedHeight, blurStart, blurCarry)
        if (wasResizing && !resizing) appearFrom = Int.MAX_VALUE
        return active
    }

    /** Real changing outline. Never scale the window, its text, or its backdrop as a whole. */
    fun rect(): CornerRect {
        val cx = mix(CORNER_BUTTON_RADIUS, width - CORNER_BUTTON_RADIUS, rightShare)
        val from = CornerRect(
            cx + (dock.left - CORNER_BUTTON_RADIUS) * pressScaleX,
            CORNER_BUTTON_RADIUS + (dock.top - CORNER_BUTTON_RADIUS) * pressScaleY,
            cx + (dock.right - CORNER_BUTTON_RADIUS) * pressScaleX,
            CORNER_BUTTON_RADIUS + (dock.bottom - CORNER_BUTTON_RADIUS) * pressScaleY,
        )
        val px = x.coerceIn(0f, 1.12f); val py = y.coerceIn(0f, 1.12f)
        val l = mix(from.left, 0f, px); val r = mix(from.right, width, px)
        val top = mix(from.top, 0f, py); val b = mix(from.bottom, height, py)
        val w = r - l; val h = b - top
        val dw = (w * ((1 + stretchX) * (1 + stretchY).pow(-.6f) - 1)).coerceIn(-22f, 22f)
        val dh = (h * ((1 + stretchY) * (1 + stretchX).pow(-.6f) - 1)).coerceIn(-22f, 22f)
        val outline = CornerRect(l - rightShare * dw + state[12], top + state[13],
            r + (1f - rightShare) * dw + state[14], b + dh + state[15])
        return if (bottomOrigin) CornerRect(outline.left, height - outline.bottom, outline.right, height - outline.top) else outline
    }

    fun radius(): Float = min(mix(CORNER_BUTTON_RADIUS, 26f, min(x, y).coerceIn(0f, 1.12f)), min(rect().width, rect().height) * .5f)
    fun iconAlpha(): Float = cornerSmooth(1 - progress * 1.55f)
    fun iconScale(): Float = mix(.6f, 1f, iconAlpha())

    fun content(centerX: Float, centerY: Float): CornerContent {
        if (!morphing && progress >= .999f) return CornerContent(0f, 0f, 1f, 1f)
        val r = rect()
        val ax = anchorX(r, CORNER_BUTTON_RADIUS)
        val ay = if (bottomOrigin) r.bottom - CORNER_BUTTON_RADIUS else r.top + CORNER_BUTTON_RADIUS
        val dx = centerX - ax; val dy = centerY - ay
        val order = (hypot(dx, dy) / max(1f, hypot(width, height))).coerceIn(0f, 1f)
        val lead = .42f * order
        val fx = max(0f, (x - lead) / (1 - lead)); val fy = max(0f, (y - lead) / (1 - lead))
        return CornerContent(-dx * (1 - fx) * .68f, -dy * (1 - fy) * .68f,
            mix(.78f, 1f, (fx + fy) * .5f).coerceIn(.78f, 1.05f), cornerSmooth((min(fx, fy) - .04f) / .5f))
    }

}

/** The MusicBar keeps its velocity through hand changes. */
internal class DockFlight(left: Boolean) {
    private val state = doubleArrayOf(if (left) 0.0 else 1.0, 0.0)
    val rightShare get() = state[0].toFloat()
    val velocity get() = state[1].toFloat()
    var targetLeft = left
    fun step(seconds: Float) = MotionKernel.travel(state, if (targetLeft) 0.0 else 1.0, seconds.toDouble(), 11.0, true)
}
