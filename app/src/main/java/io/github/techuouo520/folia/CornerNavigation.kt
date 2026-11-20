package io.github.techuouo520.folia

import androidx.compose.runtime.*
import io.github.techuouo520.folia.spatial.AlbumCamera
import io.github.techuouo520.folia.spatial.CollectionEntry

/** The visible collection owns navigation; cached/disposed pages cannot clear a newer owner. */
internal class CornerNavigation {
    private var entry by mutableStateOf<CollectionEntry?>(null)
    private var album by mutableStateOf<AlbumCamera?>(null)
    private var bubbleLyrics by mutableStateOf<BubbleLyricsEntry?>(null)
    // Current rendered MusicBar geometry (dp), shared with its attached settings entry.
    var cardFrost by mutableStateOf<FrostBackdrop?>(null)
    var playerPresentation by mutableStateOf<State<PlayerReflowTarget>?>(null)
    val cardFusion get() = playerPresentation?.value?.fusion?.coerceIn(0f,1f) ?: 0f
    val playerCard: androidx.compose.ui.geometry.Rect get() {
        val pose=playerPresentation?.value ?: return androidx.compose.ui.geometry.Rect.Zero
        val f=pose.fusion.coerceIn(0f,1f)
        val art=48f+(pose.width-48f)*f
        val height=120f+(art+96f-120f)*f
        return androidx.compose.ui.geometry.Rect(pose.left,pose.bottom-height,pose.left+pose.width,pose.bottom)
    }
    var modalOpen by mutableStateOf(false)
    var playerVisible by mutableStateOf(false)
    // Layout follows the user's request immediately; page lifetime still includes
    // the outgoing pixel/camera transition so its content is never torn down early.
    val lyricsRequested get() = bubbleLyrics?.openRequested ?: (album?.entering == true)
    val insideAlbum get() = bubbleLyrics != null || album?.entering == true
    val expanded get() = (insideAlbum || entry?.openRequested == true) && !playerVisible
    fun attach(owner: CollectionEntry) { entry=owner }
    fun detach(owner: CollectionEntry) { if(entry===owner) entry=null }
    fun attach(owner: AlbumCamera) { album=owner }
    fun detach(owner: AlbumCamera) { if(album===owner) album=null }
    fun attach(owner: BubbleLyricsEntry) { bubbleLyrics=owner }
    fun detach(owner: BubbleLyricsEntry) { if(bubbleLyrics===owner) bubbleLyrics=null }
    fun back() {
        bubbleLyrics?.let { it.openRequested=false; return }
        val camera=album
        if(camera?.entering==true) camera.entering=false else entry?.openRequested=false
    }
}

internal val LocalCornerNavigation=staticCompositionLocalOf<CornerNavigation?> {null}
