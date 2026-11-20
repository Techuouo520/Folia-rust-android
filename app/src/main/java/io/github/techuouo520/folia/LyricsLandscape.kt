package io.github.techuouo520.folia

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import io.github.techuouo520.folia.bridge.FoliaBridge

internal val LandscapeCardButtonSize = 40.dp
internal val LandscapeCardButtonInset = 8.dp
internal const val LandscapeCardGlassAlpha = .5f

/** Window geometry in dp, shared by the Compose card and Rust poster. No page identity changes. */
internal data class LyricsViewport(
    val landscape: Boolean = false, val width: Float = 0f, val height: Float = 0f,
    val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f,
    val cardX: Float = 0f, val cardY: Float = 0f, val cardWidth: Float = 0f,
    val cardHeight: Float = 0f, val coverHeight: Float = 0f,
)
internal val LocalLyricsViewport = staticCompositionLocalOf { LyricsViewport() }

@Composable
internal fun LyricsViewportHost(bridge: FoliaBridge, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val left = insets.getLeft(density, direction) / density.density
    val top = insets.getTop(density) / density.density
    val right = insets.getRight(density, direction) / density.density
    val bottom = insets.getBottom(density) / density.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = remember(maxWidth, maxHeight, left, top, right, bottom) {
            val rect = bridge.monetLandscapeLayout(maxWidth.value, maxHeight.value, left, top, right, bottom)
            LyricsViewport(maxWidth > maxHeight, maxWidth.value, maxHeight.value, left, top, right, bottom,
                rect[0], rect[1], rect[2], rect[3], rect[4])
        }
        CompositionLocalProvider(LocalLyricsViewport provides viewport, content = content)
    }
}
