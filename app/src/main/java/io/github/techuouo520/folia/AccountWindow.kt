package io.github.techuouo520.folia

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.techuouo520.folia.bridge.*
import io.github.techuouo520.folia.spatial.*
import kotlinx.coroutines.*

private data class AccountLogin(val provider: String,val attempt: Int,val snapshot: LoginSnapshot)

@Composable
internal fun AccountContents(bridge: FoliaBridge,session: SessionSnapshot,dark: Boolean,avatar: ImageBitmap?,active: Boolean) {
    val ink=sceneInk(dark)
    val scope=rememberCoroutineScope()
    var provider by remember {mutableStateOf<String?>(null)}
    var attempt by remember {mutableIntStateOf(0)}
    var logoutPending by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val loginFocus=remember {BringIntoViewRequester()}
    DisposableEffect(bridge,active) {
        if(!active) bridge.cancelLogin()
        onDispose {bridge.cancelLogin()}
    }
    val platforms by produceState<List<PlatformInfo>>(emptyList(),bridge) {
        try {value=withContext(Dispatchers.IO){bridge.platforms()}}
        catch(c: CancellationException){throw c}
        catch(e: Exception){error=e.message?:"平台信息加载失败"}
    }
    LaunchedEffect(session.phase) {
        if(session.phase!=SessionPhase.SIGNED_IN) logoutPending=false
        else provider=null
    }
    val loginResult by produceState<AccountLogin?>(null,provider,attempt,session.phase,active) {
        if(!active) return@produceState
        value=null
        val selected=provider
        if(selected!=null && session.phase==SessionPhase.SIGNED_OUT) {
            try {
                // Reset the native generation before reading, so changing platforms never shows the old QR.
                // This only schedules native work; keeping it here orders it with disposal.
                bridge.beginLogin(selected)
                while(isActive) {
                    val snapshot=withContext(Dispatchers.IO){bridge.loginSnapshot()}
                    value=AccountLogin(selected,attempt,snapshot)
                    if(snapshot.phase==LoginPhase.FAILED || snapshot.phase==LoginPhase.EXPIRED || snapshot.phase==LoginPhase.CONFIRMED) break
                    delay(400)
                }
            } catch(c: CancellationException){throw c}
            catch(e: Exception){value=AccountLogin(selected,attempt,LoginSnapshot(LoginPhase.FAILED,0,byteArrayOf(),e.message?:"登录失败"))}
        }
    }
    val login=loginResult?.takeIf {it.provider==provider && it.attempt==attempt}?.snapshot
    LaunchedEffect(provider,attempt,login?.phase,active) {
        if(active && provider!=null && session.phase==SessionPhase.SIGNED_OUT) {
            withFrameNanos { }
            loginFocus.bringIntoView()
        }
    }
    val currentPlatform=platforms.firstOrNull {it.id==session.provider}
    if(session.phase==SessionPhase.SIGNED_IN) {
        Column(Modifier.fillMaxWidth().background(accountSurface(dark),RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                AccountAvatar(avatar,dark)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    WindowText(session.nickname.ifBlank {"已登录账户"},ink,20,FontWeight.SemiBold,lineHeight=28)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        currentPlatform?.let { PlatformMark(it,dark,20.dp) }
                        WindowText(currentPlatform?.name?:session.provider,ink.copy(alpha=.58f),13,lineHeight=20)
                    }
                }
            }
        }
        WindowGroup("账户信息",dark) {
            if(session.userId.isNotBlank()) Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                WindowText("账号 ID",ink.copy(alpha=.55f),12,lineHeight=18)
                WindowText(session.userId,ink,14,FontWeight.Medium,lineHeight=22)
            }
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                WindowText(if(logoutPending) "正在退出" else "退出登录",ink,15,FontWeight.Medium,modifier=Modifier.weight(1f))
                AccountAction(SceneGlyph.Logout,"退出登录",dark,enabled=!logoutPending) {
                    logoutPending=true;error=null
                    AudioFocus.clearResume()
                    AudioFocus.abandon()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO){bridge.beginLogout()}
                        }
                        catch(c: CancellationException){throw c}
                        catch(e: Exception){logoutPending=false;error=e.message?:"退出登录失败"}
                        finally { PlaybackService.requestRefresh() }
                    }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().background(accountSurface(dark),RoundedCornerShape(20.dp)).padding(18.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            AccountAvatar(null,dark)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                WindowText(if(session.phase==SessionPhase.RESOLVING) "正在读取账户" else "未登录",ink,20,FontWeight.SemiBold,lineHeight=28)
                if(session.phase==SessionPhase.SIGNED_OUT) WindowText("选择平台登录",ink.copy(alpha=.58f),13,lineHeight=20)
            }
        }
        if(session.phase==SessionPhase.RESOLVING) SceneLoading(dark)
        else WindowGroup("登录平台",dark) {
            if(platforms.isEmpty() && error==null) Box(Modifier.fillMaxWidth().padding(16.dp),contentAlignment=Alignment.Center){SceneLoading(dark)}
            platforms.forEach {p->
                Row(Modifier.fillMaxWidth().heightIn(min=68.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    PlatformMark(p,dark)
                    Column(Modifier.weight(1f).padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        WindowText(p.name,ink,15,FontWeight.Medium,lineHeight=22)
                        if(provider==p.id) WindowText("扫码登录",ink.copy(alpha=.55f),12,lineHeight=18)
                    }
                    AccountAction(if(provider==p.id) SceneGlyph.Retry else SceneGlyph.Open,
                        if(provider==p.id) "刷新${p.name}登录码" else "登录${p.name}",dark) {
                        error=null;provider=p.id;attempt++
                    }
                }
            }
        }
        if(provider!=null && session.phase==SessionPhase.SIGNED_OUT) {
            val selected=platforms.firstOrNull {it.id==provider}
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(loginFocus)) {
            WindowGroup(selected?.loginTitle?:"扫码登录",dark) {
                val qr=login
                if(qr==null || qr.phase==LoginPhase.LOADING) Box(Modifier.fillMaxWidth().height(160.dp),contentAlignment=Alignment.Center){SceneLoading(dark)}
                if(qr!=null && qr.width>0 && qr.modules.size.toLong()==qr.width.toLong()*qr.width && qr.phase!=LoginPhase.LOADING) {
                    Box(Modifier.fillMaxWidth().padding(vertical=12.dp),contentAlignment=Alignment.Center) {
                        Canvas(Modifier.widthIn(max=232.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.White)
                            .semantics {contentDescription="${selected?.name?:"平台"}登录二维码"}) {
                            // Integer module edges plus four quiet modules avoid blurred QR edges.
                            val pitch=(size.minDimension/(qr.width+8)).toInt().coerceAtLeast(1).toFloat()
                            val inset=(size.minDimension-pitch*(qr.width+8))*.5f+pitch*4
                            qr.modules.forEachIndexed {i,v->if(v.toInt()!=0)drawRect(Color.Black,
                                Offset(inset+i%qr.width*pitch,inset+i/qr.width*pitch),Size(pitch,pitch))}
                        }
                    }
                }
                val status=when(qr?.phase) {
                    LoginPhase.SCANNED -> "已扫码，请在平台 App 中确认"
                    LoginPhase.CONFIRMED -> "正在完成登录"
                    LoginPhase.EXPIRED -> "二维码已过期"
                    LoginPhase.FAILED -> qr.message.ifBlank {"二维码加载失败"}
                    LoginPhase.WAITING -> selected?.loginNote?:"使用${selected?.name?:"平台 App"}扫码"
                    else -> "正在获取二维码"
                }
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    WindowText(status,ink.copy(alpha=.65f),12,lineHeight=19,modifier=Modifier.weight(1f))
                    if(qr?.phase==LoginPhase.EXPIRED || qr?.phase==LoginPhase.FAILED)
                        AccountAction(SceneGlyph.Retry,"刷新登录二维码",dark){attempt++}
                }
            }
            }
        }
    }
    error?.let {WindowText(it,ink.copy(alpha=.7f),12,lineHeight=19)}
}

@Composable
private fun accountSurface(dark: Boolean)=MaterialTheme.colorScheme.onSurface.copy(alpha=.045f)

// Reuse the core project's platform artwork without tinting its brand colours.
@Composable
private fun PlatformMark(platform: PlatformInfo,dark: Boolean,side: Dp=38.dp) {
    val resource=when(platform.id) {
        "netease" -> R.drawable.platform_netease
        "kugou" -> R.drawable.platform_kugou
        "qq" -> R.drawable.platform_qq
        "bodian" -> R.drawable.platform_bodian
        "qishui" -> R.drawable.platform_qishui
        else -> null
    }
    val shape=if(platform.id=="qq") CircleShape else RoundedCornerShape(side*.3f)
    Box(Modifier.size(side).clip(shape)
        .background(Color(platform.color.toInt()).copy(alpha=.13f)),contentAlignment=Alignment.Center) {
        if(resource!=null) Image(painterResource(resource),contentDescription=null,
            modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else WindowText(platform.letter,sceneInk(dark),(side.value*.45f).toInt(),FontWeight.SemiBold,
            lineHeight=(side.value*.63f).toInt())
    }
}

@Composable
private fun AccountAvatar(avatar: ImageBitmap?,dark: Boolean) {
    val ink=sceneInk(dark)
    Box(Modifier.size(60.dp).clip(CircleShape).background(accountSurface(dark)),contentAlignment=Alignment.Center) {
        if(avatar!=null) Image(avatar,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Canvas(Modifier.size(26.dp)){scale(size.width/24,size.height/24,Offset.Zero){drawSceneGlyph(SceneGlyph.User,ink.copy(alpha=.65f))}}
    }
}

@Composable
private fun AccountAction(glyph: SceneGlyph,label: String,dark: Boolean,enabled: Boolean=true,action: ()->Unit) {
    val interaction=remember {MutableInteractionSource()}
    val ink=sceneInk(dark).copy(alpha=if(enabled) .8f else .35f)
    Canvas(Modifier.size(44.dp).semantics {contentDescription=label}
        .clickable(interactionSource=interaction,indication=null,enabled=enabled,role=Role.Button,onClick=action)) {
        val unit=20.dp.toPx()/24
        translate((size.width-unit*24)/2,(size.height-unit*24)/2) {
            scale(unit,unit,Offset.Zero){drawSceneGlyph(glyph,ink)}
        }
    }
}
