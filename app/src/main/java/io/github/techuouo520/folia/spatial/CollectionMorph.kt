package io.github.techuouo520.folia.spatial

import kotlin.math.max

/** SalamPlayer NativeKernels.libraryMorph contract, adapted to the single reversible progress. */
internal object CollectionMorph {
    const val SCALE=0
    const val CLIP_WIDTH=1
    const val CLIP_HEIGHT=2
    const val RADIUS=3
    const val CONTENT_ALPHA=4
    const val ORIGIN_ALPHA=5
    const val UNDER_ALPHA=6

    fun frame(width: Float,height: Float,fromWidth: Float,fromHeight: Float,radius: Float,
        progress: Float,out: FloatArray) {
        val w=width.coerceAtLeast(1f);val h=height.coerceAtLeast(1f)
        val fw=fromWidth.coerceAtLeast(1f);val fh=fromHeight.coerceAtLeast(1f)
        val p=progress.coerceIn(0f,1f)
        val start=max(fw/w,fh/h)
        out[SCALE]=start+(1-start)*p
        out[CLIP_WIDTH]=fw/start+(w-fw/start)*p
        out[CLIP_HEIGHT]=fh/start+(h-fh/start)*p
        out[RADIUS]=radius/start*(1-p)
        out[CONTENT_ALPHA]=smooth(.18f,.70f,p)
        out[ORIGIN_ALPHA]=1-smooth(.05f,.42f,p)
        out[UNDER_ALPHA]=underAlpha(p)
    }
    fun underAlpha(progress: Float)=1-.5f*smooth(.25f,1f,progress)
    private fun smooth(low: Float,high: Float,p: Float): Float {
        val t=((p-low)/(high-low)).coerceIn(0f,1f)
        return t*t*(3-2*t)
    }
}
