package io.github.techuouo520.folia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.MonetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal class MonetPreferences(context: Context) {
    private val store = context.getSharedPreferences("monet_lyrics", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "monet_portraits")
    val defaults = MonetOptions(fontScale = 1.2f, keywordColoring = true, showDescription = true,
        squarePortrait = true, showHanger = true, showSpectrum = true, lineSpectrum = false,
        useCustom = false, portraitPath = "", portraitOffset = 0f)
    var value by mutableStateOf(defaults.copy(
        fontScale = store.getFloat("fontScale", defaults.fontScale),
        keywordColoring = store.getBoolean("keywords", true),
        showDescription = store.getBoolean("description", true),
        squarePortrait = store.getBoolean("square", true),
        showHanger = store.getBoolean("hanger", true),
        showSpectrum = store.getBoolean("spectrum", true),
        lineSpectrum = store.getBoolean("line", false),
        useCustom = store.getBoolean("custom", false),
        portraitPath = store.getString("path", "").orEmpty(),
        portraitOffset = store.getFloat("offset", 0f)))
        private set

    fun update(next: MonetOptions) {
        if (next == value) return
        val previous = value.portraitPath
        value = next
        store.edit().putFloat("fontScale", next.fontScale).putBoolean("keywords", next.keywordColoring)
            .putBoolean("description", next.showDescription).putBoolean("square", next.squarePortrait)
            .putBoolean("hanger", next.showHanger).putBoolean("spectrum", next.showSpectrum)
            .putBoolean("line", next.lineSpectrum).putBoolean("custom", next.useCustom)
            .putString("path", next.portraitPath).putFloat("offset", next.portraitOffset).apply()
        if (previous != next.portraitPath && previous.isNotEmpty()) {
            File(previous).takeIf { it.parentFile == folder }?.delete()
        }
    }

    suspend fun importPortrait(context: Context, uri: Uri) {
        var pending: File? = null
        try {
            val path = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image")
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1536) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw IOException("Cannot decode portrait")
                try {
                    folder.mkdirs()
                    val file = File.createTempFile("portrait-", ".png", folder)
                    pending = file
                    file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Cannot save portrait") }
                    file.absolutePath
                } finally { bitmap.recycle() }
            }
            update(value.copy(portraitPath = path, useCustom = true))
            pending = null
        } finally { pending?.delete() }
    }
}

@Composable
internal fun BoxScope.MonetMenu(settings: MonetPreferences, dark: Boolean) {
    val options = settings.value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            error = null
            try { settings.importPortrait(context, uri) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法读取这张图片，请重新选择。" }
            finally { importing = false }
        }
    }
    val motion = remember { CornerMotion(bottomOrigin = true) }
    val frame = remember { mutableIntStateOf(0) }
    var geometry by remember { mutableIntStateOf(0) }
    val moving by remember { derivedStateOf { frame.intValue; !motion.docked } }
    val dockWidth = 48.dp
    val dockHeight = 48.dp
    SideEffect { motion.dockBounds(CornerRect(0f, 0f, dockWidth.value, dockHeight.value)) }
    LaunchedEffect(open, pressed, geometry, dockWidth) {
        var last = System.nanoTime()
        do {
            val again = withFrameNanos { now ->
                val active = motion.step(open, (now - last) / 1e9f, pressed)
                last = now
                frame.intValue++
                active
            }
        } while (again)
    }
    PinMusicBarSide(open || moving || pressed)
    BackHandler(open) { open = false }
    if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { open = false } })
    val ink = if (dark) Color(0xFFF4F4F5) else Color(0xFF1C1917)
    val clearance = LocalPlayerClearance.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val panel = clearance?.panel ?: Rect.Zero
    val density = LocalDensity.current
    val overlaps = !anchor.isEmpty && !panel.isEmpty &&
        panel.left < anchor.left + with(density) { dockWidth.toPx() } && panel.right > anchor.left
    val lift = rustTravel(if (overlaps) (anchor.bottom - panel.top).coerceAtLeast(0f) else 0f, 19.0)
    val inset = rustTravel(if (overlaps) with(density) { (MusicBarPanelSide - MusicBarDockSide).toPx() } else 0f, 19.0)
    Box(Modifier.align(AbsoluteAlignment.BottomLeft)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .absolutePadding(left = MusicBarDockSide, right = 16.dp,
            bottom = MusicBarDockBottom + CORNER_BUTTON_DIAMETER.dp)
        .onGloballyPositioned { anchor = it.boundsInRoot() }) {
        CornerBubble(modifier = Modifier.absoluteOffset { IntOffset(inset.floatValue.roundToInt(), -lift.floatValue.roundToInt()) }
            .padding(bottom = 12.dp),
            attachedToPlayer = true, motion = motion, frame = frame, active = open || moving, right = false, frost = null, dark = dark,
            avatar = null, label = "歌词设置", title = "歌词设置", dockWidth = dockWidth, dockHeight = dockHeight,
            artworkPlaceholder = {
                Box(
                    Modifier.fillMaxSize().border(1.dp, ink.copy(alpha = .14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(R.drawable.lyrics_settings),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        colorFilter = ColorFilter.tint(ink),
                    )
                }
            }, onPressed = { pressed = it }, onGeometryChanged = { Snapshot.withoutReadObservation { geometry++ } },
            onClick = { open = !open }) {
            MonetSwitch("显示肖像拖动柄", options.showHanger) { settings.update(options.copy(showHanger = it)) }
            MonetSwitch("显示音频频谱", options.showSpectrum) { settings.update(options.copy(showSpectrum = it)) }
            if (options.showSpectrum) MonetChoice("频谱样式", "柱状", "曲线", options.lineSpectrum) {
                settings.update(options.copy(lineSpectrum = it))
            }
            Column {
                Text("歌词字号  ${(options.fontScale * 100).roundToInt()}%")
                Slider(value = options.fontScale, onValueChange = {
                    settings.update(options.copy(fontScale = (it * 20).roundToInt() / 20f))
                }, valueRange = .7f..1.5f, steps = 15)
            }
            MonetSwitch("关键词着色", options.keywordColoring) { settings.update(options.copy(keywordColoring = it)) }
            MonetChoice("宽屏肖像", "方形", "长方形", !options.squarePortrait) { settings.update(options.copy(squarePortrait = !it)) }
            if (options.portraitPath.isNotEmpty()) MonetChoice("肖像来源", "专辑封面", "自选图片", options.useCustom) {
                settings.update(options.copy(useCustom = it))
            }
            Row {
                TextButton(enabled = !importing, onClick = { picker.launch("image/*") }) { Text(if (importing) "正在读取图片…" else "选择肖像图片") }
                if (options.portraitPath.isNotEmpty()) TextButton(enabled = !importing, onClick = {
                    settings.update(options.copy(portraitPath = "", useCustom = false))
                }) { Text("清除图片") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !importing, onClick = { settings.update(settings.defaults) }) { Text("恢复默认") }
                TextButton(onClick = { open = false }) { Text("完成") }
            }
        }
    }
}

@Composable
private fun MonetSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MonetChoice(label: String, first: String, second: String, secondSelected: Boolean, change: (Boolean) -> Unit) {
    Column {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !secondSelected, onClick = { change(false) }, label = { Text(first) })
            FilterChip(selected = secondSelected, onClick = { change(true) }, label = { Text(second) })
        }
    }
}
