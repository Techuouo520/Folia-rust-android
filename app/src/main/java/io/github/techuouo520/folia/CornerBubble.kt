package io.github.techuouo520.folia

import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.font.FontWeight
import io.github.techuouo520.folia.spatial.SceneGlyph
import io.github.techuouo520.folia.spatial.drawSceneGlyph
import io.github.techuouo520.folia.spatial.sceneInk
import kotlin.math.*

/** One surface, from the actual 50 dp button outline to the open window and back. */
@Composable
internal fun CornerBubble(
    modifier: Modifier, motion: CornerMotion, frame: State<Int>, active: Boolean, right: Boolean,
    frost: FrostBackdrop?, dark: Boolean, avatar: ImageBitmap?, label: String, title: String?,
    artworkPlaceholder: (@Composable () -> Unit)? = null,
    dockWidth: Dp = CORNER_BUTTON_DIAMETER.dp, dockHeight: Dp = CORNER_BUTTON_DIAMETER.dp,
    dockGlassAlpha: Float = 1f, dockFrost: FrostBackdrop? = null, dockFrostWeight: Float = 0f,
    dockGlyphSize: Dp? = null, dockEnabled: Boolean = true, liveHeight: Boolean = false, attachedToPlayer: Boolean = false, dockMotion: CornerMotion? = null, backTurn: FloatArray? = null, backEnabled: Boolean = false,
    onBackPressed: (Boolean)->Unit = {}, onBack: ()->Unit = {},
    onPressed: (Boolean)->Unit, onGeometryChanged: ()->Unit, onClick: ()->Unit, content: @Composable ()->Unit,
) {
    val scroll=rememberScrollState()
    // Shape/blur are driven directly by the same state as the outline, not a second animator.
    val px = androidx.compose.ui.platform.LocalDensity.current.density
    val avoid = if (motion.flight != null || attachedToPlayer) Modifier else Modifier.avoidPlayer { box ->
        frame.value
        val shape = motion.rect()
        Rect(box.left+shape.left*px,box.top+shape.top*px,box.left+shape.right*px,box.top+shape.bottom*px)
    }
    SubcomposeLayout(modifier.then(avoid).widthIn(max=420.dp).fillMaxWidth().layout {measurable,constraints->
        // Keep the source's travelling blur and edge overshoot outside the final panel bounds.
        val pad=64.dp.roundToPx()
        val child=measurable.measure(constraints.offset(horizontal=pad*2,vertical=pad*2))
        layout(child.width-pad*2,child.height-pad*2) {child.place(-pad,-pad)}
    }.graphicsLayer {
        frame.value
        // Resting-shape motion must not blur an already expanded account window.
        val dockBlur=(dockMotion?.blur?:0f)*(1f-motion.progress)
        val px=max(motion.blur*25.dp.toPx(),dockBlur*6.dp.toPx())
        val q=if(px<.75f) 0f else (px*2).roundToInt()*.5f
        renderEffect=if(Build.VERSION.SDK_INT>=31&&q>0) BlurEffect(q,q,TileMode.Decal) else null
    }) {constraints->
        frame.value
        val pad=64.dp.roundToPx()
        val width=constraints.maxWidth-pad*2
        val viewport=constraints.maxHeight-pad*2
        val body=if(active) subcompose("body") {
            if (title == null) content() else PinnedBubbleContents(title,dark,motion,frame,scroll,content)
        }.single().measure(Constraints(minWidth=width,maxWidth=width,maxHeight=viewport)) else null
        if(motion.bounds(width/density,body?.height?.div(density),right,liveHeight)) onGeometryChanged()
        val r=motion.rect();val radius=motion.radius()
        // At rest an opaque sleeve covers every pixel of the MusicBar dock.
        val covered = !active && avatar?.hasAlpha == false && motion.pressScaleX == 1f && motion.pressScaleY == 1f &&
            r.width == dockWidth.value && r.height == dockHeight.value
        val reach = ((dockMotion?.rect()?.height ?: CORNER_BUTTON_DIAMETER) - CORNER_BUTTON_DIAMETER).coerceAtLeast(0f)
        val dx = (backTurn?.get(2) ?: 0f)*reach*motion.pressScaleX
        val dy = (backTurn?.get(3) ?: 1f)*reach*motion.pressScaleY
        val carry = 1f-motion.progress
        val dockShape = if (dx*carry > .02f && dy*carry > .02f)
            TurningDock(radius.dp, dx*carry*density, dy*carry*density, backTurn!![4])
            else RoundedCornerShape(radius.dp)
        val glass=subcompose("glass") {
            val base=if(covered) Modifier else if(frost!=null) Modifier.frostedBackdrop(frost,dark)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // Opacity belongs to the glass only; the foreground glyph stays crisp.
            Box(Modifier.graphicsLayer { alpha=1f+(dockGlassAlpha-1f)*(1f-motion.progress) }.clip(dockShape)
                .then(if(active) Modifier.pointerInput(Unit){detectTapGestures{}} else Modifier)) {
                if(dockFrost==null) Box(Modifier.matchParentSize().then(base))
                else {
                    val share=dockFrostWeight*(1f-motion.progress)
                    Box(Modifier.matchParentSize().graphicsLayer { alpha=1f-share }.then(base))
                    Box(Modifier.matchParentSize().graphicsLayer { alpha=share }.frostedBackdrop(dockFrost,dark))
                }
            }
        }.single().measure(Constraints.fixed(max(1,(r.width*density).roundToInt()),max(1,(r.height*density).roundToInt())))
        val fade=motion.iconAlpha()
        val icon=subcompose("icon") {
            val ink=MaterialTheme.colorScheme.onSurface
            val glyphFraction=dockGlyphSize?.let { it.value/dockWidth.value } ?: (21f/CORNER_BUTTON_DIAMETER) // 19dp → 21dp; retain press scaling.
            Box(Modifier.fillMaxSize().cornerAction(label,dockEnabled && fade>.01f,onPressed,onClick, movingOrigin = motion.flight != null),contentAlignment=androidx.compose.ui.Alignment.Center) {
                if(avatar!=null) Image(avatar,null,Modifier.fillMaxSize().clip(CircleShape),contentScale=ContentScale.Crop)
                else if(artworkPlaceholder != null) artworkPlaceholder()
                else if(right) Image(painterResource(R.drawable.salam_settings),null,Modifier.fillMaxSize(glyphFraction),colorFilter=ColorFilter.tint(ink))
                else Canvas(Modifier.fillMaxSize(glyphFraction)) {
                    scale(size.width/24,size.height/24,Offset.Zero) {drawSceneGlyph(SceneGlyph.User,ink)}
                }
            }
        }.single().measure(Constraints.fixed((dockWidth.toPx()*motion.pressScaleX).roundToInt(),(dockHeight.toPx()*motion.pressScaleY).roundToInt()))
        val backSize = reach.coerceAtMost(CORNER_BUTTON_DIAMETER)
        val back=if(dockMotion!=null && backSize>.01f) subcompose("back") {
            val label=stringResource(R.string.collection_back)
            val ink=MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxSize().clip(CircleShape)
                .cornerAction(label,backEnabled,onBackPressed,onBack),
                contentAlignment=androidx.compose.ui.Alignment.Center) {
                Canvas(Modifier.requiredSize(21.dp).graphicsLayer {
                    scaleX=motion.pressScaleX;scaleY=motion.pressScaleY
                }) {scale(size.width/24,size.height/24,Offset.Zero) {drawSceneGlyph(SceneGlyph.Back,ink)}}
            }
        }.single().measure(Constraints.fixed(
            max(1,(backSize*motion.pressScaleX*density).roundToInt()),
            max(1,(backSize*motion.pressScaleY*density).roundToInt()))) else null
        val surfaceHeight = if (motion.bottomOrigin) motion.layoutHeight.dp.roundToPx()
            else max(ceil(r.bottom*density).toInt(),body?.height?:CORNER_BUTTON_DIAMETER.dp.roundToPx())
        layout(width+pad*2,surfaceHeight+pad*2) {
            glass.place(pad+(r.left*density).roundToInt(),pad+(r.top*density).roundToInt())
            body?.placeWithLayer(pad,pad) {
                frame.value
                shape=CornerClip(motion.rect(),motion.radius());clip=true
            }
            val anchorX=motion.anchorX(r,dockWidth.value*.5f*motion.pressScaleX)
            val iconRadiusY=dockHeight.value*.5f*motion.pressScaleY
            val anchorY=if(motion.bottomOrigin) r.bottom-iconRadiusY else r.top+iconRadiusY
            back?.placeWithLayer(pad+((anchorX+dx)*density-back.width*.5f).roundToInt(),pad+((anchorY+dy)*density-back.height*.5f).roundToInt()) {
                alpha=fade*cornerSmooth(dockMotion?.progress?:0f)
                scaleX=motion.iconScale();scaleY=motion.iconScale()
            }
            icon.placeWithLayer(pad+(anchorX*density-icon.width*.5f).roundToInt(),pad+(anchorY*density-icon.height*.5f).roundToInt()) {
                alpha=fade;scaleX=motion.iconScale();scaleY=motion.iconScale()
            }
        }
    }
}

/** Independent action owners share the original escape-slop, cancellation and press response. */
@Composable
private fun Modifier.cornerAction(label: String,enabled: Boolean,onPressed: (Boolean)->Unit,onClick: ()->Unit, movingOrigin: Boolean = false): Modifier {
    val gate = LocalGripGate.current
    val latestClick=rememberUpdatedState(onClick)
    val latestPress=rememberUpdatedState(onPressed)
    val haptics=LocalHapticFeedback.current
    return semantics {
        if(enabled) {
            contentDescription=label;role=Role.Button
            onClick {latestClick.value();true}
        } else hideFromAccessibility()
    }.then(if(enabled) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown();gate?.excludedDown = down.uptimeMillis;down.consume();latestPress.value(true)
            var finger=down.position
            var armed=true
            try {
                while(true) {
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull {it.id==down.id} ?: break
                    val slop=12.dp.toPx()
                    // Both event positions are expressed in the current local frame. The ball can
                    // fly underneath a stationary finger without turning that tap into a drag.
                    finger = if (movingOrigin) finger + (change.position - change.previousPosition) else change.position
                    if(change.isConsumed||finger.x < -slop||finger.y < -slop||finger.x>size.width+slop||finger.y>size.height+slop) {
                        armed=false;latestPress.value(false)
                    }
                    if(!change.pressed) {
                        change.consume()
                        if(armed) {haptics.performHapticFeedback(HapticFeedbackType.ContextClick);latestClick.value()}
                        break
                    }
                    change.consume()
                }
            } finally {latestPress.value(false)}
        }
    } else Modifier)
}

/** Header and scroll viewport are separate layout children, both inside the moving outline. */
@Composable
private fun PinnedBubbleContents(title: String,dark: Boolean,motion: CornerMotion,frame: State<Int>,
    scroll: androidx.compose.foundation.ScrollState,content: @Composable ()->Unit) {
    SubcomposeLayout {constraints->
        val w=constraints.maxWidth
        val maxHeight=min(constraints.maxHeight,560.dp.roundToPx())
        val inset=20.dp.roundToPx()
        val top=(CORNER_BUTTON_DIAMETER+12).dp.roundToPx()
        val header=subcompose("header") {
            WindowText(title,sceneInk(dark),28,FontWeight.SemiBold,lineHeight=36,modifier=Modifier.semantics {heading()})
        }.single().measure(Constraints(maxWidth=(w-inset*2).coerceAtLeast(0),maxHeight=(maxHeight-top).coerceAtLeast(0)))
        val contentTop=(top+header.height+16.dp.roundToPx()).coerceAtMost(maxHeight)
        val body=subcompose("scroll") {
            Box(Modifier.verticalScroll(scroll)) {
                BubbleRows(motion,frame,{scroll.value},contentTop/density,content)
            }
        }.single().measure(Constraints(minWidth=w,maxWidth=w,maxHeight=(maxHeight-contentTop).coerceAtLeast(0)))
        layout(w,contentTop+body.height) {
            body.place(0,contentTop)
            header.placeWithLayer(inset,top) {
                frame.value
                val f=motion.content((inset+header.width*.5f)/density,(top+header.height*.5f)/density)
                translationX=f.x*density;translationY=f.y*density
                scaleX=f.scale;scaleY=f.scale;alpha=f.alpha
            }
        }
    }
}

/** Each row is laid out at its final size, then rides the source's distance-based content fling. */
@Composable
private fun BubbleRows(motion: CornerMotion,frame: State<Int>,scrollY: ()->Int,contentTop: Float,content: @Composable ()->Unit) {
    Layout(content=content) {measurables,constraints->
        val inset=20.dp.roundToPx();val top=0;val gap=16.dp.roundToPx()
        val w=constraints.maxWidth
        val children=measurables.map {it.measure(Constraints(maxWidth=(w-inset*2).coerceAtLeast(0)))}
        motion.rows(children.size)
        val h=top+children.sumOf {it.height}+gap*max(0,children.size-1)+inset
        layout(w,h) {
            var y=top
            children.forEachIndexed {index,child->
                val at=y
                child.placeWithLayer(inset,at) {
                    frame.value
                    val f=motion.content((inset+child.width*.5f)/density,contentTop+(at-scrollY()+child.height*.5f)/density)
                    translationX=f.x*density;translationY=f.y*density
                    scaleX=f.scale;scaleY=f.scale;alpha=f.alpha*motion.rowAppearance(index)
                }
                y+=child.height+gap
            }
        }
    }
}

/** Value identity changes with geometry, invalidating Compose's cached outline every moving frame. */
private data class CornerClip(val rect: CornerRect,val radius: Float): Shape {
    override fun createOutline(size: Size,layoutDirection: LayoutDirection,density: Density): Outline {
        val d=density.density
        return Outline.Rounded(RoundRect(Rect(rect.left*d,rect.top*d,rect.right*d,rect.bottom*d),CornerRadius(radius*d)))
    }
}

/** The rounded window swept along the vector from the avatar to the back button. */
private data class TurningDock(val radius: Dp, val dx: Float, val dy: Float, val angle: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val x = dx.coerceIn(0f,size.width)
        val y = dy.coerceIn(0f,size.height)
        val r = minOf(with(density){ radius.toPx() }, (size.width-x)/2, (size.height-y)/2)
        val path = Path()
        val stops = floatArrayOf(0f,90f,90f+angle,180f,270f,270f+angle,360f)
        for (i in 1 until stops.size) {
            val a = stops[i-1]; val b = stops[i]
            if (b <= a) continue
            val mid = (a+b)/2
            val far = mid < 90f+angle || mid > 270f+angle
            val cx = (if(mid<90f || mid>270f) size.width-x-r else r) + if(far) x else 0f
            val cy = (if(mid<180f) size.height-y-r else r) + if(far) y else 0f
            path.arcTo(Rect(cx-r,cy-r,cx+r,cy+r),a,b-a,i==1)
        }
        path.close()
        return Outline.Generic(path)
    }
}
