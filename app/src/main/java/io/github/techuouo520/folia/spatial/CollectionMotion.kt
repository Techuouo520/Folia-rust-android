package io.github.techuouo520.folia.spatial

import io.github.techuouo520.folia.MotionKernel

/** Independently owned by each camera. Retargeting preserves position and velocity. */
internal class CollectionMotion(private val frequency: Double, private val damping: Double = 1.0) {
    private val state = DoubleArray(2)
    val position get() = state[0]
    val velocity get() = state[1]
    fun step(target: Double, seconds: Double) = MotionKernel.travel(state, target, seconds, frequency, false, damping)
}
