package io.github.techuouo520.folia

import androidx.compose.runtime.*
import io.github.techuouo520.folia.spatial.AlbumCamera
import io.github.techuouo520.folia.spatial.CollectionEntry

/** The visible collection owns navigation; cached/disposed pages cannot clear a newer owner. */
internal class CornerNavigation {
    private var entry by mutableStateOf<CollectionEntry?>(null)
    private var album by mutableStateOf<AlbumCamera?>(null)
    var modalOpen by mutableStateOf(false)
    var playerVisible by mutableStateOf(false)
    var playerLyricsOpen by mutableStateOf(false)
        private set
    val insideAlbum get() = playerLyricsOpen || album?.entering == true
    val expanded get() = (insideAlbum || entry?.openRequested == true) && !playerVisible
    fun attach(owner: CollectionEntry) { entry=owner }
    fun detach(owner: CollectionEntry) { if(entry===owner) entry=null }
    fun attach(owner: AlbumCamera) { album=owner }
    fun detach(owner: AlbumCamera) { if(album===owner) album=null }
    fun openPlayerLyrics() {
        if (album?.entering != true) playerLyricsOpen = true
    }
    fun back() {
        if (playerLyricsOpen) {
            playerLyricsOpen = false
            return
        }
        val camera=album
        if(camera?.entering==true) camera.entering=false else entry?.openRequested=false
    }
}

internal val LocalCornerNavigation=staticCompositionLocalOf<CornerNavigation?> {null}
