package io.github.techuouo520.folia

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.techuouo520.folia.bridge.*
import io.github.techuouo520.folia.spatial.SpatialNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val LocalFoliaDark=staticCompositionLocalOf {false}
internal val LocalArtworkClusters = staticCompositionLocalOf<IntArray?> { null }
internal val SkyBlue=Color(0xFF87CEEB)
private data class ArtworkPalette(val accent: Int,val clusters: IntArray?=null)
/** No wallpaper/system dynamic colours: one artwork seed owns every semantic colour role. */
@Composable
internal fun FoliaTheme(bridge: FoliaBridge,dark: Boolean,playback: PlaybackSnapshot?=null,content: @Composable ()->Unit) {
    val cache=remember(bridge) {LruCache<PlaybackArtwork,ArtworkPalette>(32)}
    val identity=playback?.artSource()
    var started by remember {mutableStateOf<PlaybackArtwork?>(null)}
    val latestIdentity=rememberUpdatedState(identity)
    LaunchedEffect(identity,playback?.state,playback?.loading) {
        if(playback?.state==TransportState.PLAYING || playback?.loading==true) started=identity
        else if(playback==null || playback.queueLength==0) started=null
    }
    // Pausing retains the current cover theme. A restored, never-started queue stays sky blue.
    var seedOwner by remember {mutableStateOf<PlaybackArtwork?>(null)}
    val seed by produceState(ArtworkPalette(SkyBlue.toArgb()),started,bridge) {
        val key=started
        if(key==null) {seedOwner=null;value=ArtworkPalette(SkyBlue.toArgb());return@produceState}
        cache.get(key)?.let {if(latestIdentity.value==key) {seedOwner=key;value=it};return@produceState}
        // Keep the displayed seed during a cover fetch; do not flash blue between tracks.
        val found=bridge.readArtwork(key) { bytes ->
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>96) sample*=2
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return@readArtwork null
            try {
                val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                if(pixels.any {(it ushr 24)>=20}) ArtworkPalette(SpatialNative.coverAccent(pixels),SpatialNative.coverClusters(pixels,bitmap.width,bitmap.height)) else ArtworkPalette(SkyBlue.toArgb())
            } finally {bitmap.recycle()}
        }
        if(latestIdentity.value==key) {
            if(found!=null) cache.put(key,found)
            seedOwner=key
            value=found ?: ArtworkPalette(SkyBlue.toArgb())
        }
    }
    val target=remember(seed,dark) {foliaColorScheme(Color(seed.accent),dark)}
    val playerTarget=remember(seed,dark) {playerPalette(seed.clusters,dark)}
    var playerShown by remember {mutableStateOf(playerTarget)}
    var shown by remember {mutableStateOf(target)}
    val clock=remember {Animatable(1f)}
    var shownDark by remember {mutableStateOf(dark)}
    LaunchedEffect(target,playerTarget,identity,seedOwner) {
        if(started!=null && seedOwner!=identity && dark==shownDark) return@LaunchedEffect
        shownDark=dark
        val from=shown
        val playerFrom=playerShown
        clock.snapTo(0f)
        clock.animateTo(1f,tween(420)) {shown=blendScheme(from,target,value);playerShown=playerFrom.blend(playerTarget,value)}
    }
    CompositionLocalProvider(LocalFoliaDark provides dark,LocalPlayerPalette provides playerShown,
        LocalArtworkClusters provides seed.clusters.takeIf { seedOwner == identity }) {
        MaterialTheme(colorScheme=shown,content=content)
    }
}

/** Complete roles, including both sides of switches/sliders and all container states. */
internal fun foliaColorScheme(seed: Color,dark: Boolean): ColorScheme {
    val hsl=FloatArray(3);ColorUtils.colorToHSL(seed.toArgb(),hsl)
    fun tone(light: Float,saturation: Float=hsl[1],hue: Float=hsl[0])=
        Color(ColorUtils.HSLToColor(floatArrayOf(hue,saturation.coerceIn(0f,1f),light)))
    val chroma=hsl[1].coerceAtMost(.78f)
    fun accent(light: Float)=tone(light,chroma)
    fun neutral(light: Float)=tone(light,hsl[1]*.10f)
    fun variant(light: Float)=tone(light,hsl[1]*.24f)
    val primary=accent(if(dark) .76f else .38f)
    fun on(color: Color)=if(ColorUtils.calculateLuminance(color.toArgb())>.179) Color(0xFF101418) else Color.White
    val container=accent(if(dark) .24f else .9f)
    val secondary=tone(if(dark) .76f else .36f,chroma*.55f)
    val secondaryContainer=tone(if(dark) .23f else .91f,chroma*.35f)
    val tertiary=tone(if(dark) .78f else .36f,chroma*.55f,(hsl[0]+28)%360)
    val tertiaryContainer=tone(if(dark) .24f else .91f,chroma*.35f,(hsl[0]+28)%360)
    val base=if(dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary=primary,onPrimary=on(primary),primaryContainer=container,onPrimaryContainer=on(container),inversePrimary=accent(if(dark) .38f else .76f),
        secondary=secondary,onSecondary=on(secondary),secondaryContainer=secondaryContainer,onSecondaryContainer=on(secondaryContainer),
        tertiary=tertiary,onTertiary=on(tertiary),tertiaryContainer=tertiaryContainer,onTertiaryContainer=on(tertiaryContainer),
        background=neutral(if(dark) .055f else .98f),onBackground=neutral(if(dark) .92f else .1f),
        surface=neutral(if(dark) .07f else .98f),onSurface=neutral(if(dark) .92f else .1f),
        surfaceVariant=variant(if(dark) .22f else .9f),onSurfaceVariant=variant(if(dark) .78f else .30f),
        outline=variant(if(dark) .58f else .46f),outlineVariant=variant(if(dark) .3f else .78f),
        surfaceTint=primary,inverseSurface=neutral(if(dark) .9f else .18f),inverseOnSurface=neutral(if(dark) .15f else .95f),
        surfaceDim=neutral(if(dark) .055f else .87f),surfaceBright=neutral(if(dark) .24f else .98f),
        surfaceContainerLowest=neutral(if(dark) .035f else 1f),surfaceContainerLow=neutral(if(dark) .09f else .96f),
        surfaceContainer=neutral(if(dark) .12f else .94f),surfaceContainerHigh=neutral(if(dark) .16f else .92f),surfaceContainerHighest=neutral(if(dark) .21f else .9f),
        primaryFixed=accent(.9f),primaryFixedDim=accent(.8f),onPrimaryFixed=accent(.1f),onPrimaryFixedVariant=accent(.25f),
        secondaryFixed=tone(.9f,chroma*.55f),secondaryFixedDim=tone(.8f,chroma*.55f),onSecondaryFixed=tone(.1f,chroma*.55f),onSecondaryFixedVariant=tone(.25f,chroma*.55f),
        tertiaryFixed=tone(.9f,chroma*.55f,(hsl[0]+28)%360),tertiaryFixedDim=tone(.8f,chroma*.55f,(hsl[0]+28)%360),
        onTertiaryFixed=tone(.1f,chroma*.55f,(hsl[0]+28)%360),onTertiaryFixedVariant=tone(.25f,chroma*.55f,(hsl[0]+28)%360),
    )
}

/** Every role uses the same displayed frame; interrupted changes start from that exact frame. */
private fun blendScheme(a: ColorScheme,b: ColorScheme,t: Float)=a.copy(
    error=lerp(a.error,b.error,t),onError=lerp(a.onError,b.onError,t),errorContainer=lerp(a.errorContainer,b.errorContainer,t),onErrorContainer=lerp(a.onErrorContainer,b.onErrorContainer,t),
    primary=lerp(a.primary,b.primary,t),
    onPrimary=lerp(a.onPrimary,b.onPrimary,t),
    primaryContainer=lerp(a.primaryContainer,b.primaryContainer,t),
    onPrimaryContainer=lerp(a.onPrimaryContainer,b.onPrimaryContainer,t),
    inversePrimary=lerp(a.inversePrimary,b.inversePrimary,t),
    secondary=lerp(a.secondary,b.secondary,t),
    onSecondary=lerp(a.onSecondary,b.onSecondary,t),
    secondaryContainer=lerp(a.secondaryContainer,b.secondaryContainer,t),
    onSecondaryContainer=lerp(a.onSecondaryContainer,b.onSecondaryContainer,t),
    tertiary=lerp(a.tertiary,b.tertiary,t),
    onTertiary=lerp(a.onTertiary,b.onTertiary,t),
    tertiaryContainer=lerp(a.tertiaryContainer,b.tertiaryContainer,t),
    onTertiaryContainer=lerp(a.onTertiaryContainer,b.onTertiaryContainer,t),
    background=lerp(a.background,b.background,t),
    onBackground=lerp(a.onBackground,b.onBackground,t),
    surface=lerp(a.surface,b.surface,t),
    onSurface=lerp(a.onSurface,b.onSurface,t),
    surfaceVariant=lerp(a.surfaceVariant,b.surfaceVariant,t),
    onSurfaceVariant=lerp(a.onSurfaceVariant,b.onSurfaceVariant,t),
    outline=lerp(a.outline,b.outline,t),
    outlineVariant=lerp(a.outlineVariant,b.outlineVariant,t),
    surfaceTint=lerp(a.surfaceTint,b.surfaceTint,t),
    inverseSurface=lerp(a.inverseSurface,b.inverseSurface,t),
    inverseOnSurface=lerp(a.inverseOnSurface,b.inverseOnSurface,t),
    surfaceDim=lerp(a.surfaceDim,b.surfaceDim,t),
    surfaceBright=lerp(a.surfaceBright,b.surfaceBright,t),
    surfaceContainerLowest=lerp(a.surfaceContainerLowest,b.surfaceContainerLowest,t),
    surfaceContainerLow=lerp(a.surfaceContainerLow,b.surfaceContainerLow,t),
    surfaceContainer=lerp(a.surfaceContainer,b.surfaceContainer,t),
    surfaceContainerHigh=lerp(a.surfaceContainerHigh,b.surfaceContainerHigh,t),
    surfaceContainerHighest=lerp(a.surfaceContainerHighest,b.surfaceContainerHighest,t),
    primaryFixed=lerp(a.primaryFixed,b.primaryFixed,t),
    primaryFixedDim=lerp(a.primaryFixedDim,b.primaryFixedDim,t),
    onPrimaryFixed=lerp(a.onPrimaryFixed,b.onPrimaryFixed,t),
    onPrimaryFixedVariant=lerp(a.onPrimaryFixedVariant,b.onPrimaryFixedVariant,t),
    secondaryFixed=lerp(a.secondaryFixed,b.secondaryFixed,t),
    secondaryFixedDim=lerp(a.secondaryFixedDim,b.secondaryFixedDim,t),
    onSecondaryFixed=lerp(a.onSecondaryFixed,b.onSecondaryFixed,t),
    onSecondaryFixedVariant=lerp(a.onSecondaryFixedVariant,b.onSecondaryFixedVariant,t),
    tertiaryFixed=lerp(a.tertiaryFixed,b.tertiaryFixed,t),
    tertiaryFixedDim=lerp(a.tertiaryFixedDim,b.tertiaryFixedDim,t),
    onTertiaryFixed=lerp(a.onTertiaryFixed,b.onTertiaryFixed,t),
    onTertiaryFixedVariant=lerp(a.onTertiaryFixedVariant,b.onTertiaryFixedVariant,t),
)
