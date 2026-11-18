package io.github.techuouo520.folia.spatial

import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.LocalCornerNavigation
import kotlin.math.*

internal val LocalCollectionEntry = staticCompositionLocalOf<CollectionEntry?> { null }

internal class CollectionEntry(val item: SpatialItem, val bounds: Rect,
    val style: Int, val corners: List<Offset>) {
    // A new visit is a new intent even when all geometry and the cached world are identical.
    var openRequested by mutableStateOf(true)
    // Keep the source cover visible until this visit can present its destination.
    var sceneReady by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var parentRestored = false
    val screenToPage = android.graphics.Matrix()
    var visibleWidth = 0f
    var visibleHeight = 0f
    val faceWidth=(corners[1]-corners[0]).getDistance().coerceAtLeast(1f)
    val faceHeight=(corners[3]-corners[0]).getDistance().coerceAtLeast(1f)
    val outline=Path().apply {
        moveTo(corners[0].x,corners[0].y)
        corners.drop(1).forEach {lineTo(it.x,it.y)}
        close()
    }
}

/** Read the same published camera/pose as the GL frame, in viewport pixels. */
internal fun collectionBounds(frame: FloatArray, cell: Int, density: Float, width: Int, height: Int): Rect {
    val slot=(0 until frame[16].toInt().coerceIn(0,DailySpatialProtocol.SLOTS))
        .firstOrNull { frame[32+it*16+7].toInt()==cell }
        ?: return Rect(width*.35f,height*.35f,width*.65f,height*.65f)
    val o=32+slot*16
    val points=ArrayList<Pair<Float,Float>>(16)
    val a=DailySpatialProtocol.ACTION
    val owner=frame[a+27]>.5f && frame[a+23].toInt()==slot
    if(frame[DailySpatialProtocol.RHINE]>.5f) {
        val m=DailySpatialProtocol.RHINE_POSES+slot*16
        // Both print faces are supported: Rhine can expose the reverse side of a sleeve.
        for(z in floatArrayOf(-.028733334f,.028733334f)) for(x in floatArrayOf(-.5f,.5f)) for(y in floatArrayOf(-.5f,.5f)) {
            points += (frame[m+12]+frame[m]*x+frame[m+4]*y+frame[m+8]*z) to
                (frame[m+13]+frame[m+1]*x+frame[m+5]*y+frame[m+9]*z)
        }
    } else if(owner) {
        points += frame[a+6] to frame[a+7];points += frame[a+8] to frame[a+9]
    } else {
        // Curved map projection; sample edge midpoints as well as corners.
        for(u in 0..2) for(v in 0..2) {
            val x=frame[o+1]+frame[o+3]*u/2
            val y=frame[o+2]+frame[o+4]*v/2
            val ax=(x+frame[2])*frame[4]/frame[5].coerceAtLeast(1f)
            val ay=(y+frame[3])*frame[4]/frame[6].coerceAtLeast(1f)
            val perspective=1500f/(1500f-frame[5]*(cos(ax)-1)-frame[6]*(cos(ay)-1))
            points += (frame[7]+frame[5]*sin(ax)*perspective) to (frame[8]+frame[6]*sin(ay)*perspective)
        }
    }
    val scale=if(owner) 1f else frame[a+127].coerceAtLeast(.01f)
    val xs=points.map { (frame[7]+(it.first-frame[7])*scale)*density }
    val ys=points.map { (frame[8]+(it.second-frame[8])*scale)*density }
    val left=xs.min().coerceIn(0f,max(0f,width-1f))
    val top=ys.min().coerceIn(0f,max(0f,height-1f))
    return Rect(left,top,xs.max().coerceIn(left+1f,max(left+1f,width.toFloat())),ys.max().coerceIn(top+1f,max(top+1f,height.toFloat())))
}


/** Rhine unfolds its inclined sleeve; the map starts at its projected card footprint. */
internal fun collectionCorners(frame: FloatArray, cell: Int, density: Float, bounds: Rect): List<Offset> {
    val fallback=listOf(bounds.topLeft,bounds.topRight,bounds.bottomRight,bounds.bottomLeft)
    if(frame[DailySpatialProtocol.RHINE]<.5f) return fallback
    val slot=(0 until frame[16].toInt().coerceIn(0,DailySpatialProtocol.SLOTS)).firstOrNull {frame[32+it*16+7].toInt()==cell} ?: return fallback
    val m=DailySpatialProtocol.RHINE_POSES+slot*16
    val a=DailySpatialProtocol.ACTION
    val scale=if(frame[a+27]>.5f && frame[a+23].toInt()==slot) 1f else frame[a+127]
    val det=frame[m]*frame[m+5]-frame[m+1]*frame[m+4]
    if(abs(det)<1f) return fallback
    val flip=if(det<0) -1f else 1f
    val z=if(frame[m+10]<0) .028733334f else -.028733334f
    return listOf(-.6f to -.73333335f,.6f to -.73333335f,.6f to .6f,-.6f to .6f).map { (x,y) ->
        val px=frame[m+12]+frame[m]*x*flip+frame[m+4]*y+frame[m+8]*z
        val py=frame[m+13]+frame[m+1]*x*flip+frame[m+5]*y+frame[m+9]*z
        Offset((frame[7]+(px-frame[7])*scale)*density,(frame[8]+(py-frame[8])*scale)*density)
    }
}

/** One source card grows into a live native world with coordinated geometry and alpha. */
@Composable
internal fun CollectionReveal(entry: CollectionEntry, active: Boolean, dark: Boolean, parentWorld: GraphicsLayer, onClosed: (CollectionEntry)->Unit,
    content: @Composable (SpatialItem,Int,Boolean,Boolean,Boolean)->Unit) {
    val motion=remember(entry.item.key,entry.style) { CollectionMotion(if(entry.style==1) 16.0 else 18.0) }
    val closed=rememberUpdatedState(onClosed)
    val interactive by remember(entry,active) { derivedStateOf {
        active && entry.openRequested && entry.sceneReady && entry.progress > 0f
    } }
    val expanded by remember(entry,active) { derivedStateOf { active && entry.openRequested && entry.progress==1f } }
    val navigation=LocalCornerNavigation.current
    DisposableEffect(navigation,entry,active) {
        if(active) navigation?.attach(entry)
        onDispose {if(active) navigation?.detach(entry)}
    }
    val dismiss={entry.openRequested=false}
    BackHandler(active && entry.openRequested && !io.github.techuouo520.folia.LocalPlayerCoversPage.current && navigation?.modalOpen!=true) { dismiss() }
    LaunchedEffect(entry,active) {
        // Cancellation must be observed even while both the old and new targets are zero.
        snapshotFlow { entry.openRequested to entry.sceneReady }.collectLatest { (requested, ready) ->
            val target=if(active && requested && ready) 1.0 else 0.0
            var previous=withFrameNanos {it}
            do {
                var moving=false
                withFrameNanos {now ->
                    moving=motion.step(target,(now-previous)/1e9)
                    previous=now;entry.progress=motion.position.toFloat()
                }
            } while(moving)
            if(active && !entry.openRequested) closed.value(entry)
        }
    }
    val nativeMatrix=remember { android.graphics.Matrix() }
    val values=remember { FloatArray(9) }
    val matrix=remember { Matrix() }
    val src=remember { FloatArray(8) }
    val dst=remember { FloatArray(8) }
    val world = if (active) rememberGraphicsLayer() else null
    val origin = if (active) rememberGraphicsLayer() else null
    val geometry=remember {FloatArray(7)}
    val clip=remember {Path()}
    val ground=MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().graphicsLayer {
        translationX=if(active) 0f else size.width*2f
    }.then(if(!active) Modifier.clearAndSetSemantics { } else Modifier)) {
        Box(Modifier.fillMaxSize().drawWithContent {
            if (world == null || origin == null) return@drawWithContent
            // Parent records only the original native scene and its card labels, never this
            // transition. Both image layers and geometry consume the same reversible progress.
            val p=entry.progress.coerceIn(0f,1f)
            if (active && p == 1f && !parentWorld.isReleased) {
                drawRect(ground)
                drawContent()
                return@drawWithContent
            }
            world.record {this@drawWithContent.drawContent()}
            if(!active || parentWorld.isReleased) return@drawWithContent
            if(p<=0f) {
                // TextureView must actually draw to publish its first frame. Keep the
                // complete parent above it until the destination can start one continuous flight.
                world.alpha=1f
                drawLayer(world)
                drawLayer(parentWorld)
                return@drawWithContent
            }
            CollectionMorph.frame(size.width,size.height,entry.faceWidth,entry.faceHeight,
                (if(entry.style==1) 1.dp else 6.dp).toPx(),p,geometry)
            val w=geometry[CollectionMorph.CLIP_WIDTH];val h=geometry[CollectionMorph.CLIP_HEIGHT]
            val fitScale=geometry[CollectionMorph.SCALE]
            // First unproject the real card from the clean retained parent RenderNode.
            // This carries its artwork, labels and Rhine glass shading, without CPU readback.
            repeat(4) {i ->
                src[i*2]=entry.corners[i].x;src[i*2+1]=entry.corners[i].y
                dst[i*2]=if(i==1||i==2) entry.faceWidth else 0f
                dst[i*2+1]=if(i>=2) entry.faceHeight else 0f
            }
            if(!nativeMatrix.setPolyToPoly(src,0,dst,0,4)) return@drawWithContent
            nativeMatrix.getValues(values);copyPlanarMatrix(values,matrix)
            origin.record(size=IntSize(entry.faceWidth.roundToInt().coerceAtLeast(1),entry.faceHeight.roundToInt().coerceAtLeast(1))) {
                // RenderNode bounds alone do not clip drawing: exclude neighboring cards
                // before this isolated source is scaled into the growing container.
                clipRect(0f,0f,entry.faceWidth,entry.faceHeight) {
                    withTransform({transform(matrix)}) { drawLayer(parentWorld) }
                }
            }
            // Uniform fit/clip growth from SalamPlayer; retain the Rhine face inclination
            // as an extra projection which relaxes into the destination plane.
            repeat(4) {i ->
                val right=i==1||i==2;val bottom=i>=2
                src[i*2]=if(right) w else 0f;src[i*2+1]=if(bottom) h else 0f
                val localX=if(right) entry.faceWidth else 0f
                val localY=if(bottom) entry.faceHeight else 0f
                val skewX=entry.corners[i].x-entry.corners[0].x-localX
                val skewY=entry.corners[i].y-entry.corners[0].y-localY
                dst[i*2]=entry.corners[0].x*(1-p)+src[i*2]*fitScale+skewX*(1-p)
                dst[i*2+1]=entry.corners[0].y*(1-p)+src[i*2+1]*fitScale+skewY*(1-p)
            }
            if(!nativeMatrix.setPolyToPoly(src,0,dst,0,4)) return@drawWithContent
            nativeMatrix.getValues(values);copyPlanarMatrix(values,matrix)
            nativeMatrix.invert(entry.screenToPage)
            entry.visibleWidth = w
            entry.visibleHeight = h
            clip.reset();clip.addRoundRect(RoundRect(Rect(0f,0f,w,h),CornerRadius(geometry[CollectionMorph.RADIUS])))
            world.alpha=1f
            origin.alpha=geometry[CollectionMorph.ORIGIN_ALPHA]
            withTransform({transform(matrix)}) {
                clipPath(clip) {
                    drawRect(ground,size=androidx.compose.ui.geometry.Size(w,h))
                    drawLayer(world)
                    // Fill the changing aspect ratio without stretching or exposing the ground.
                    val coverScale=max(w/entry.faceWidth,h/entry.faceHeight)
                    val left=(w-entry.faceWidth*coverScale)*.5f
                    val top=(h-entry.faceHeight*coverScale)*.5f
                    withTransform({
                        translate(left,top)
                        scale(coverScale,coverScale,Offset.Zero)
                    }) { drawLayer(origin) }
                }
            }
        }) {
            CompositionLocalProvider(LocalCollectionEntry provides entry) {
                Box(Modifier.fillMaxSize().graphicsLayer()) { content(entry.item,entry.style,interactive,active,expanded) }
            }
        }
    }
}

private fun copyPlanarMatrix(values: FloatArray,matrix: Matrix) {
    matrix.reset()
    matrix[0,0]=values[0];matrix[1,0]=values[1];matrix[3,0]=values[2]
    matrix[0,1]=values[3];matrix[1,1]=values[4];matrix[3,1]=values[5]
    matrix[0,3]=values[6];matrix[1,3]=values[7];matrix[3,3]=values[8]
}
