package io.github.techuouo520.folia

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/** Absolute window coordinates survive a change of window size. One clock, one pose,
 * and no cancellation/reseeding when rotation changes its destination mid-flight. */
internal data class PlayerReflowTarget(val left: Float, val bottom: Float, val width: Float, val fusion: Float)

@Composable
internal fun rememberPlayerReflow(target: PlayerReflowTarget): State<PlayerReflowTarget> {
    val current = remember { mutableStateOf(target) }
    val state = remember { doubleArrayOf(target.left.toDouble(), 0.0, target.bottom.toDouble(), 0.0,
        target.width.toDouble(), 0.0, target.fusion.toDouble(), 0.0) }
    val goal by rememberUpdatedState(target)
    LaunchedEffect(Unit) {
        var stepped = target
        val destination = DoubleArray(4)
        while (isActive) {
            snapshotFlow { goal }.first { it != stepped }
            var previous = System.nanoTime()
            do {
                val moving = withFrameNanos { now ->
                    stepped = goal
                    destination[0]=stepped.left.toDouble(); destination[1]=stepped.bottom.toDouble()
                    destination[2]=stepped.width.toDouble(); destination[3]=stepped.fusion.toDouble()
                    val moving = MotionKernel.playerReflow(state, destination, (now-previous)/1e9)
                    previous = now
                    current.value = PlayerReflowTarget(state[0].toFloat(),state[2].toFloat(),
                        state[4].toFloat(),state[6].toFloat())
                    moving
                }
            } while (moving)
        }
    }
    return current
}
