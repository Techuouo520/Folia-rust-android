package io.github.techuouo520.folia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import io.github.techuouo520.folia.bridge.*
import io.github.techuouo520.folia.spatial.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.*

@Composable
internal fun AppCornerWindows(bridge: FoliaBridge,playerInFront: Boolean,player: @Composable ()->Unit,modifier: Modifier = Modifier,content: @Composable ()->Unit) {
    FoliaApp.from(LocalContext.current).appearanceVersion
    val frost=rememberFrostBackdrop();val dark=LocalFoliaDark.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by produceState(SessionSnapshot(SessionPhase.RESOLVING,"","","",""),bridge,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive){value=withContext(Dispatchers.IO){runCatching {bridge.sessionSnapshot()}.getOrDefault(value)};delay(600)}
        }
    }
    val avatar by produceState<ImageBitmap?>(null,session.provider,session.userId,session.avatarUrl) {
        value=null
        if(session.avatarUrl.isNotBlank())withContext(Dispatchers.IO) {
            val key="account:${session.provider}:${session.userId}:${session.avatarUrl}"
            bridge.requestCovers(listOf(CoverAsk(key,"",session.avatarUrl,128,128)))
            while(isActive){when(val found=bridge.coverLookup(key)){
                is CoverLookup.Ready->{value=decodeCover(found.bytes,128);break}
                is CoverLookup.Missing->break
                is CoverLookup.Pending->delay(80)
            }}
        }
    }
    val navigation=remember {CornerNavigation()}
    val viewport = LocalLyricsViewport.current
    // Keep absolute anchors alive across viewport changes; the attached gear reads
    // the very same card pose as MusicBar, so it cannot lag behind a second animator.
    val leftAnchor=rustTravel(viewport.left+10f,14.0,viewport.left+10f)
    val rightAnchor=rustTravel(viewport.width-viewport.right-10f,14.0,viewport.width-viewport.right-10f)
    val topAnchor=rustTravel(viewport.top+4f,14.0,viewport.top+4f)
    var backPressed by remember {mutableStateOf(false)}
    val turn = remember { floatArrayOf(0f,0f,0f,1f,90f) }
    val dockMotion=remember {CornerMotion().apply {bounds(CORNER_BUTTON_DIAMETER,CORNER_BUTTON_DIAMETER*2,false)}}
    var owner by remember {mutableStateOf<String?>(null)}
    var requested by remember {mutableStateOf<String?>(null)}
    var accountPressed by remember {mutableStateOf(false)}
    var settingsPressed by remember {mutableStateOf(false)}
    var layoutSignal by remember {mutableIntStateOf(0)}
    val frame=remember {mutableIntStateOf(0)}
    val accountMotion=remember {CornerMotion()}
    val settingsMotion=remember {CornerMotion()}
    LaunchedEffect(Unit) {
        snapshotFlow {listOf(requested,accountPressed,settingsPressed,backPressed,navigation.expanded,navigation.insideAlbum,layoutSignal)}.collectLatest {
            if(owner==null && requested!=null) owner=requested
            var last=withFrameNanos{it}
            do {
                var moving=false
                withFrameNanos {now->
                    val dt=(now-last)/1e9f;last=now
                    val dockMoving=dockMotion.step(navigation.expanded,dt)
                    val turning = MotionKernel.backDock(turn,navigation.insideAlbum,dt)
                    val dock = dockMotion.rect()
                    val reach = (dock.height-CORNER_BUTTON_DIAMETER).coerceAtLeast(0f)
                    accountMotion.dockBounds(dock.copy(right=dock.right+reach*turn[2],
                        bottom=dock.top+CORNER_BUTTON_DIAMETER+reach*turn[3]))
                    val a=accountMotion.step(owner=="account"&&requested==owner,dt,accountPressed||backPressed)
                    val s=settingsMotion.step(owner=="settings"&&requested==owner,dt,settingsPressed)
                    moving=a||s||dockMoving||turning
                    if(owner!=null && requested!=owner && (if(owner=="account") accountMotion else settingsMotion).docked) {
                        owner=requested
                        moving=moving||owner!=null
                    }
                    frame.intValue++
                }
            }while(moving)
        }
    }
    LaunchedEffect(playerInFront) { if(playerInFront) requested=null }
    SideEffect {navigation.modalOpen=owner!=null && !playerInFront}
    PinMusicBarSide(owner != null || requested != null || settingsPressed)
    BackHandler(owner!=null && !playerInFront){requested=null}
    CompositionLocalProvider(LocalCornerNavigation provides navigation) {
      Box(Modifier.fillMaxSize()) {
        Box(modifier.fillMaxSize().frostSource(frost)) {
            content()
        }
        if(owner!=null) Canvas(Modifier.fillMaxSize().zIndex(1f).pointerInput(Unit){detectTapGestures{requested=null}}) {
            frame.intValue
            drawRect(Color.Black.copy(alpha=.18f*max(accountMotion.progress,settingsMotion.progress)))
        }
        listOf("account","settings").forEach {name->
            key(name) {
                val right=name=="settings"
                // Separate root siblings: the modal scrim covers both idle buttons; the active
                // window remains above the scrim, its sibling button, the player and the scene.
                val f=navigation.cardFusion.coerceIn(0f,1f)
                val panelWidth=(viewport.width-viewport.left-viewport.right-20f).coerceIn(50f,420f)
                val card=navigation.playerCard
                val buttonSize=CORNER_BUTTON_DIAMETER+(LandscapeCardButtonSize.value-CORNER_BUTTON_DIAMETER)*f
                // Size/material use the card's existing Rust fusion channel, including
                // mid-flight reversals. The expanded settings sheet remains full opacity.
                if(right) SideEffect {
                    settingsMotion.dockBounds(CornerRect(CORNER_BUTTON_DIAMETER-buttonSize,0f,
                        CORNER_BUTTON_DIAMETER,buttonSize))
                }
                val end=rightAnchor.floatValue*(1f-f)+(card.right-LandscapeCardButtonInset.value)*f
                val y=topAnchor.floatValue*(1f-f)+(card.top+LandscapeCardButtonInset.value)*f
                val hiddenAccount=!right && owner!="account"
                val inputHidden=hiddenAccount && f>.01f
                Box(Modifier.fillMaxSize()
                    .then(if(playerInFront || inputHidden) Modifier.clearAndSetSemantics {} else Modifier)
                    .zIndex(if(owner==name) 2f else if(right && f>0f) .5f else 0f)) {
                    CornerBubble(
                        modifier=Modifier.absoluteOffset {
                            val x=if(right) end-panelWidth else leftAnchor.floatValue
                            IntOffset(x.dp.roundToPx(),(if(right) y else topAnchor.floatValue).dp.roundToPx())
                        }.wrapContentSize(Alignment.TopStart,unbounded=true).requiredWidth(panelWidth.dp)
                            .heightIn(max=(viewport.height-viewport.bottom-(if(right) y else topAnchor.floatValue)-10f).coerceAtLeast(50f).dp)
                            .graphicsLayer {
                                if(hiddenAccount) { alpha=1f-f; scaleX=1f-.12f*f; scaleY=scaleX }
                            },
                        motion=if(right) settingsMotion else accountMotion,frame=frame,
                        active=owner==name,right=right,frost=frost,dark=dark, dockEnabled=!inputHidden,
                        dockWidth=(if(right) buttonSize else CORNER_BUTTON_DIAMETER).dp,
                        dockHeight=(if(right) buttonSize else CORNER_BUTTON_DIAMETER).dp,
                        dockGlyphSize=if(right) 21.dp else null,
                        dockGlassAlpha=if(right) 1f+(LandscapeCardGlassAlpha-1f)*f else 1f,
                        dockFrost=if(right && f>0f) navigation.cardFrost else null,
                        dockFrostWeight=if(right) f else 0f,
                        dockMotion=if(right) null else dockMotion, backTurn=if(right) null else turn,
                        backEnabled=!right&&!inputHidden&&navigation.expanded&&owner==null,
                        onBackPressed={backPressed=it},onBack=navigation::back,
                        avatar=if(right) null else avatar,
                        label=if(right) "设置" else "用户账户",
                        title=if(right) "设置" else "账户",
                        onPressed={if(right) settingsPressed=it else accountPressed=it},
                        onGeometryChanged={Snapshot.withoutReadObservation {layoutSignal++}},
                        onClick={if(!inputHidden) requested=if(requested==name) null else name},
                    ) {
                        if(right) SettingsContents(bridge,dark,requested==owner)
                        else AccountContents(bridge,session,dark,avatar,requested==owner)
                    }
                }
            }
        }
        // The moving card physically occludes the corners. Only its uncovered surroundings
        // swallow new touches during travel. At rest the corner sheets can cover the mini bar.
        Box(Modifier.fillMaxSize().zIndex(if(playerInFront) 3f else 0f)) {
            if(playerInFront) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while(true) awaitPointerEvent().changes.forEach { it.consume() } }
            })
            player()
        }
      }
    }
}

