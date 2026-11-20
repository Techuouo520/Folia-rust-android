package io.github.techuouo520.folia

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot

@Composable
internal fun PlayerLyricsPage(
    bridge: FoliaBridge, playback: PlaybackSnapshot, open: Boolean, preload: Boolean,
    modifier: Modifier = Modifier,
) {
    val owner = playback.artSource()?.owner
    val navigation = LocalCornerNavigation.current
    val wallLyricsOpen = !open && navigation?.insideAlbum == true
    var retained by remember(owner) { mutableStateOf(false) }
    var prepared by remember(owner, retained) { mutableStateOf(false) }
    val currentOpen by rememberUpdatedState(open)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val context = LocalContext.current.applicationContext

    DisposableEffect(context, lifecycle, owner) {
        fun discardHiddenPage() { if (!currentOpen) retained = false }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> { foreground = false; discardHiddenPage() }
                else -> Unit
            }
        }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(config: Configuration) = Unit
            @Deprecated("Only delivered before Android 14")
            override fun onLowMemory() = discardHiddenPage()
            override fun onTrimMemory(level: Int) {
                if (shouldTrimSceneMemory(level)) discardHiddenPage()
            }
        }
        lifecycle.addObserver(observer)
        context.registerComponentCallbacks(memory)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
        }
    }

    LaunchedEffect(owner, open, preload, foreground, wallLyricsOpen) {
        if (wallLyricsOpen || !foreground) retained = false
        else if (owner != null && (open || preload)) retained = true
    }
    if (wallLyricsOpen || (!open && !retained)) return

    // Keep only the current song's prepared page. Hidden pages neither receive touches
    // nor run their rendering loop once preparation finishes.
    Box(Modifier.fillMaxSize().zIndex(if (open) 1f else -1f)
        .then(modifier).then(if (open) Modifier else Modifier.clearAndSetSemantics {})) {
        BackHandler(open && navigation?.modalOpen != true) { navigation?.back() }
        MonetLyrics(bridge, playback, LocalFoliaDark.current, selected = true,
            active = foreground && (open || !prepared), controlsVisible = open,
            onStatus = { if (it == 1 || it == 2 || it == 3 || it == 5) prepared = true })
        // A real draw behind an opaque cover lets TextureView create its surface during
        // preloading. Alpha zero can skip that draw and postpone initialization until opening.
        if (!open) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
    }
}
