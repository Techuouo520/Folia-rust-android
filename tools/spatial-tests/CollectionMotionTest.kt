package io.github.techuouo520.folia.spatial

import io.github.techuouo520.folia.MotionKernel
import kotlin.math.abs

fun main() {
    for((frequency, damping) in listOf(12.0 to 1.2, 14.0 to 1.0, 16.0 to 1.0, 18.0 to 1.0)) for(hz in listOf(60,90,120)) {
        val motion=CollectionMotion(frequency, damping)
        val dt=1.0/hz
        // Fast open/back/open sequences retain current position and velocity at each handoff.
        for(target in listOf(1.0,0.0,1.0,0.0,1.0)) {
            repeat(7) {
                val before=motion.position
                motion.step(target,dt)
                check(motion.position.isFinite() && motion.velocity.isFinite())
                check(motion.position in 0.0..1.0)
                check(abs(motion.position-before)<.20) {"discontinuous handoff at $hz Hz"}
            }
            val before=motion.position;val speed=motion.velocity
            motion.step(1.0-target,0.0)
            check(motion.position==before && motion.velocity==speed) {"retarget reset momentum"}
        }
        repeat(hz*2) {motion.step(0.0,dt)}
        check(motion.position==0.0 && motion.velocity==0.0) {"return did not dock"}
        repeat(hz*2) {motion.step(1.0,dt)}
        check(motion.position==1.0 && motion.velocity==0.0) {"cached reentry failed"}
        repeat(20) {motion.step(1.0,dt)}
        check(motion.position==1.0) {"settled world drifted"}
    }
    // Source geometry and alpha are a reversible function of ONE progress, independent of
    // visit count/direction. The visible card owns the first frame, not a blank seam.
    val frame=FloatArray(7)
    CollectionMorph.frame(400f,890f,140f,190f,6f,0f,frame)
    check(abs(frame[0]*frame[1]-140f)<.001f && abs(frame[0]*frame[2]-190f)<.001f)
    check(frame[CollectionMorph.ORIGIN_ALPHA]==1f && frame[CollectionMorph.CONTENT_ALPHA]==0f)
    CollectionMorph.frame(400f,890f,140f,190f,6f,.04f,frame)
    check(frame[CollectionMorph.ORIGIN_ALPHA]==1f && frame[CollectionMorph.CONTENT_ALPHA]==0f)
    var oldSource=1f;var oldContent=0f
    for(n in 0..100) {
        val p=n/100f
        CollectionMorph.frame(400f,890f,140f,190f,6f,p,frame)
        val opening=frame.copyOf()
        check(frame.all {it.isFinite()})
        check(frame[CollectionMorph.ORIGIN_ALPHA]<=oldSource && frame[CollectionMorph.CONTENT_ALPHA]>=oldContent)
        oldSource=frame[CollectionMorph.ORIGIN_ALPHA];oldContent=frame[CollectionMorph.CONTENT_ALPHA]
        CollectionMorph.frame(400f,890f,140f,190f,6f,1f,frame)
        CollectionMorph.frame(400f,890f,140f,190f,6f,p,frame)
        check(frame.contentEquals(opening)) {"return alpha/geometry diverged from entry"}
    }
    CollectionMorph.frame(400f,890f,140f,190f,6f,1f,frame)
    check(frame[0]==1f && frame[1]==400f && frame[2]==890f && frame[3]==0f)
    check(frame[CollectionMorph.ORIGIN_ALPHA]==0f && frame[CollectionMorph.CONTENT_ALPHA]==1f)
    // Exercise the actual JNI packet consumed by the MusicBar portal, for both hand anchors.
    for (left in listOf(.04f, .65f)) {
        val portal = FloatArray(8)
        var previousZoom = 1f
        for (n in 0..100) {
            val p = n / 100f
            MotionKernel.lyricsPortalPose(portal, p, left, .85f, left+.3f, .91f)
            check(portal.all { it.isFinite() })
            check(portal[0] >= 0f && portal[1] >= 0f && portal[2] <= 1f && portal[3] <= 1f)
            check(portal[4] >= previousZoom)
            previousZoom = portal[4]
            val opening = portal.copyOf()
            MotionKernel.lyricsPortalPose(portal, 1f, left, .85f, left+.3f, .91f)
            MotionKernel.lyricsPortalPose(portal, p, left, .85f, left+.3f, .91f)
            check(portal.contentEquals(opening)) { "MusicBar return diverged from entry" }
        }
        MotionKernel.lyricsPortalPose(portal, 0f, left, .85f, left+.3f, .91f)
        check(portal[0] == left && portal[1] == .85f && portal[4] == 1f)
        check(portal[5] == 0f && portal[6] == 0f && portal[7] == 0f)
        MotionKernel.lyricsPortalPose(portal, 1f, left, .85f, left+.3f, .91f)
        check(portal.take(4) == listOf(0f, 0f, 1f, 1f))
        check(portal[5] == 1f && portal[6] == 1f && portal[7] == 1f)
    }
    // The visible frontier must contain detached cells, not a single scaled rectangle.
    for (cx in listOf(.18f, .82f)) {
        val columns = 32
        val rows = 72
        val pixels = FloatArray(columns*rows*6)
        check(MotionKernel.lyricsPixels(pixels, columns, rows, 0f, cx, .9f, 2.25f) == 0)
        val count = MotionKernel.lyricsPixels(pixels, columns, rows, .6f, cx, .9f, 2.25f)
        check(count > rows) { "Pixel frontier collapsed into a solid rectangle" }
        check((0 until count).any { i -> pixels[i*6+2]-pixels[i*6] < .95f/columns }) {
            "Missing detached pixel fragments"
        }
        check((0 until count).any { pixels[it*6+4] > 0f }) { "Missing album-colored frontier" }
        check((0 until count).all { pixels[it*6+4] in 0f..1f && pixels[it*6+5] in 0f..2f })
        val opening = pixels.copyOf(count*6)
        MotionKernel.lyricsPixels(pixels, columns, rows, 1f, cx, .9f, 2.25f)
        val reverseCount = MotionKernel.lyricsPixels(pixels, columns, rows, .6f, cx, .9f, 2.25f)
        check(reverseCount == count && pixels.copyOf(count*6).contentEquals(opening))
        val filled = MotionKernel.lyricsPixels(pixels, columns, rows, 1f, cx, .9f, 2.25f)
        check(filled == rows)
        for (row in 0 until rows) {
            check(pixels[row*6+4] == 0f) { "Color tint remains after joining" }
            check(pixels[row*6] == 0f && pixels[row*6+2] == 1f)
            check(abs(pixels[row*6+1]-row.toFloat()/rows)<.00001f)
            check(abs(pixels[row*6+3]-(row+1).toFloat()/rows)<.00001f)
        }
    }
    val oldMotion = CollectionMotion(14.0)
    val heavyMotion = CollectionMotion(12.0, 1.2)
    repeat(24) { oldMotion.step(1.0, 1.0/120); heavyMotion.step(1.0, 1.0/120) }
    check(heavyMotion.position < oldMotion.position) { "Requested slower diffusion was lost" }
    println("Pixel diffusion: detached frontier, full coverage and exact reverse passed")
    println("MusicBar lyric portal: JNI bounds, endpoints and reverse path passed")
    println("Card morph: source ownership, prewarm, endpoints and reversible alpha passed")
    println("Collection motion: rapid reversal, continuity, docking and reentry passed at 60/90/120 Hz")
}
