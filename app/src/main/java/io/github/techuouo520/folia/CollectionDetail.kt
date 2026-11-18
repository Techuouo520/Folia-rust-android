package io.github.techuouo520.folia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.bridge.CollectionPage as CollectionSnapshot
import io.github.techuouo520.folia.bridge.*
import io.github.techuouo520.folia.spatial.*

/** The contents of a collection are another native spatial world, never a list page. */
@Composable
internal fun CollectionDetail(bridge: FoliaBridge, cache: CollectionCache, item: SpatialItem, style: Int, interactive: Boolean, active: Boolean, expanded: Boolean,
    shell: Shell, bottomInset: Dp, fallbackCover: String, onFramePresented: ()->Unit,
    onPlay: (List<LibraryTrack>,Int)->Unit, onToggle: ()->Unit, action: (() -> Unit)->Unit) {
    val key=item.key
    val dark=LocalFoliaDark.current
    val rhine=style==1
    val titleBackdrop=rememberFrostBackdrop()
    DisposableEffect(cache,key) {
        cache.pin(key);cache.request(key,item.name,fallbackCover)
        onDispose {cache.unpin(key)}
    }
    val page=cache.peek(key) ?: CollectionSnapshot(key,item.name,true,"",emptyList())
    val context=LocalContext.current
    val store=remember { context.getSharedPreferences("spatial-map",0) }
    var hidden by remember { mutableStateOf(store.getStringSet("hidden",emptySet())!!.toSet()) }
    var favorites by remember { mutableStateOf(store.getStringSet("favorites",emptySet())!!.toSet()) }
    fun identity(track: LibraryTrack)="track:${track.provider}:${track.onlineId}:${track.id}"
    val catalogue=remember(page.tracks,fallbackCover) {
        page.tracks.mapIndexed { index,track ->
            // Duplicate tracks keep distinct cells and the original queue position.
            SpatialItem("$index:${identity(track)}",track.title,track.artist,
                SpatialCoverSource.key(track.path,track.coverUrl.ifBlank {fallbackCover}),track.path) to index
        }.toMap()
    }
    val visible=remember(catalogue,hidden) { catalogue.keys.filter { identity(page.tracks[catalogue.getValue(it)]) !in hidden } }
    val tracks=rememberUpdatedState(page.tracks)
    val indices=rememberUpdatedState(remember(catalogue) { catalogue.entries.associate { it.key.key to it.value } })
    val coverIndices=rememberUpdatedState(remember(catalogue) {catalogue.entries.associate {it.key.coverUrl to it.value}})
    val source=remember(bridge,key,fallbackCover) { { coverKey: String ->
        val track=coverIndices.value[coverKey]?.let { tracks.value.getOrNull(it) }
        if(track==null) null else {
            val url=track.coverUrl.ifBlank { fallbackCover }
            val requestKey="spatial-original:${SpatialCoverSource.version(coverKey)}"
            bridge.requestCovers(listOf(CoverAsk(requestKey,track.path,url,1536,1536)))
            val until=System.nanoTime()+8_000_000_000L
            var bytes: ByteArray?=null
            while(!Thread.currentThread().isInterrupted && System.nanoTime()<until) {
                when(val found=bridge.coverLookup(requestKey)) {
                    is CoverLookup.Ready -> {bytes=found.bytes;break}
                    is CoverLookup.Missing -> break
                    is CoverLookup.Pending -> Thread.sleep(20)
                }
            }
            bytes
        }
    } }
    var albumVisible by remember(key) { mutableStateOf(false) }
    val entry = LocalCollectionEntry.current
    SideEffect {
        if (visible.isEmpty() && !page.loading) entry?.sceneReady = true
    }
    val playback=shell.playback
    val playingKey=remember(visible,catalogue,playback.provider,playback.onlineId,playback.trackId) {
        visible.firstOrNull { cell ->
            val track=page.tracks[catalogue.getValue(cell)]
            if(track.onlineId.isNotEmpty()) track.provider==playback.provider && track.onlineId==playback.onlineId
            else track.id==playback.trackId
        }?.key
    }
    Box(Modifier.fillMaxSize()) {
        // Only the live map/Rhine scene enters the title blur, never the title itself.
        Box(Modifier.fillMaxSize().frostSource(titleBackdrop)) {
        SpatialMap(
            albumContent={ selected ->
                val track = indices.value[selected]?.let { tracks.value.getOrNull(it) }
                MonetLyrics(bridge, playback, dark, track != null && track.artOwner() == playback.artSource()?.owner, active = active)
            },
            onAlbumVisible={ albumVisible=it },
            items=visible,dark=dark,playingKey=playingKey,playing=playback.state==TransportState.PLAYING,
            source=source,initialStyle=style,chrome=false,expansionComplete=expanded,interactive=interactive,sceneActive=active,bottomInset=bottomInset,
            onFramePresented={titleBackdrop.sourceFramePresented();onFramePresented()},
            onSceneReady={ entry?.sceneReady = true },
            onPlay={ cell -> indices.value[cell.key]?.let {onPlay(tracks.value,it)} },onToggle=onToggle,
            onNext={ cell -> indices.value[cell.key]?.let {index -> action {bridge.enqueueNext(listOf(tracks.value[index]))} } },
            isFavorite={ cell -> identity(page.tracks[catalogue.getValue(cell)]) in favorites },
            onFavorite={ cell ->
                val id=identity(page.tracks[catalogue.getValue(cell)])
                favorites=if(id in favorites) favorites-id else favorites+id
                store.edit().putStringSet("favorites",favorites).apply()
            },
            onDislike={ cell ->
                hidden=hidden+identity(page.tracks[catalogue.getValue(cell)])
                store.edit().putStringSet("hidden",hidden).apply()
            },
        )
        }
        if (!albumVisible) CollectionHeading(page.title.ifBlank {item.name},"${page.tracks.size}  /  ${item.singer}",dark,titleBackdrop)
        if(visible.isEmpty()) Column(Modifier.align(Alignment.Center).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(page.loading) {if(active) SceneLoading(dark)}
            else if(page.error.isNotEmpty()) {
                BasicText(page.error,style=TextStyle(color=sceneInk(dark),fontSize=13.sp),maxLines=5)
                SceneIcon(SceneGlyph.Retry,stringResource(R.string.collection_retry),dark,rhine,enabled=interactive) {cache.request(key,item.name,fallbackCover,force=true)}
            } else SceneLabel(stringResource(R.string.collection_empty),dark,muted=true)
        }
    }
}

