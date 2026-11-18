package io.github.techuouo520.folia

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.CoverAsk
import io.github.techuouo520.folia.bridge.CoverLookup
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.TransportState
import io.github.techuouo520.folia.spatial.SpatialItem
import io.github.techuouo520.folia.spatial.SpatialMap
import io.github.techuouo520.folia.spatial.SpatialCoverSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FoliaSpatialWall(bridge: FoliaBridge,items: List<WallItem>,shell: Shell,
    onOpen: (WallItem)->Unit,onPlayTracks: (List<LibraryTrack>, Int)->Unit,onToggle: ()->Unit,action: (() -> Unit)->Unit,bottomInset: Dp = 0.dp,onFramePresented: () -> Unit = {},onCollectionVisible: (Boolean) -> Unit = {}) {
    val context=LocalContext.current
    val store=remember { context.getSharedPreferences("spatial-map",0) }
    var hidden by remember { mutableStateOf(store.getStringSet("hidden",emptySet())!!.toSet()) }
    var favorites by remember { mutableStateOf(store.getStringSet("favorites",emptySet())!!.toSet()) }
    val visible=remember(items,hidden) { items.filter { it.key !in hidden } }
    val lookup=rememberUpdatedState(remember(items) { items.associateBy { it.key } })
    val coverSources = rememberUpdatedState(remember(items) {
        items.associateBy { SpatialCoverSource.key(it.path, it.url) }
    })
    val scope=rememberCoroutineScope()
    val collections=remember(bridge,scope) { CollectionCache(bridge,scope) }
    val warmKeys=remember(visible) { visible.filter {it.kind !in setOf("song","explore-song","fm")}.take(2) }
    LaunchedEffect(warmKeys) { warmKeys.forEach {collections.request(it.key,it.title,it.url)} }
    val source=remember(bridge) { { key: String ->
        val item=coverSources.value[key]
        if(item==null || (item.path.isEmpty()&&item.url.isEmpty())) null
        else {
            val requestKey="spatial-original:${SpatialCoverSource.version(key)}"
            bridge.requestCovers(listOf(CoverAsk(requestKey,item.path,item.url,1536,1536)))
            val until=System.nanoTime()+8_000_000_000L
            var result: ByteArray?=null
            while(!Thread.currentThread().isInterrupted && System.nanoTime()<until) {
                when(val found=bridge.coverLookup(requestKey)) {
                    is CoverLookup.Ready -> { result=found.bytes;break }
                    is CoverLookup.Missing -> break
                    is CoverLookup.Pending -> Thread.sleep(20)
                }
            }
            result
        }
    } }
    // exploreTracks clones the Rust feed and converts a whole section across UniFFI.
    // Resolve identities once per catalogue, off the UI thread, rather than per card/tick.
    val identities by produceState<Map<String, Pair<String, String>>>(emptyMap(), bridge, items) {
        value=withContext(Dispatchers.IO) {
            buildMap {
                for ((section,cards) in items.filter { it.kind=="explore-song" }.groupBy { it.section }) {
                    val tracks=bridge.exploreTracks(section)
                    for (card in cards) tracks.getOrNull(card.index)?.let { put(card.key,it.provider to it.onlineId) }
                }
            }
        }
    }
    val playback=shell.playback
    val playingKey=remember(visible,identities,playback.trackId,playback.provider,playback.onlineId) {
        val online=playback.provider to playback.onlineId
        visible.firstOrNull { item ->
            when(item.kind) {
                "song" -> item.key==playback.trackId.toString()
                "explore-song" -> identities[item.key]==online
                else -> false
            }
        }?.key
    }
    SpatialMap(
        bottomInset=bottomInset,
        onFramePresented=onFramePresented,
        items=remember(visible) { visible.map { SpatialItem(it.key,it.title,it.subtitle,SpatialCoverSource.key(it.path,it.url),it.path,it.kind !in setOf("song","explore-song","fm")) } },
        dark=LocalFoliaDark.current,playingKey=playingKey,playing=shell.playback.state==TransportState.PLAYING,
        onCollectionVisible=onCollectionVisible,
        onPreload={ item -> lookup.value[item.key]?.let {collections.request(it.key,it.title,it.url)} },
        collectionContent={ item, style, interactive, active, expanded ->
            CollectionDetail(bridge,collections,item,style,interactive,active,expanded,shell,bottomInset,lookup.value[item.key]?.url.orEmpty(),
                onFramePresented,onPlayTracks,onToggle,action)
        },
        albumContent={ key ->
            val card = lookup.value[key]
            val selected = key == playingKey || (card?.kind == "explore-song" &&
                identities[key] == (playback.provider to playback.onlineId))
            MonetLyrics(bridge, playback, LocalFoliaDark.current, selected, card?.kind == "fm")
        },
        source=source,onPlay={ item -> lookup.value[item.key]?.let(onOpen) },onToggle=onToggle,
        onNext={ item -> scope.launch {
            try {
                val target=lookup.value[item.key] ?: return@launch
                val tracks=withContext(Dispatchers.IO) { when(target.kind) {
                    "song" -> listOfNotNull(shell.tracks.getOrNull(target.index))
                    "explore-song" -> listOfNotNull(bridge.exploreTracks(target.section).getOrNull(target.index))
                    "fm" -> bridge.personalFm()
                    else -> {
                        bridge.beginCollection(target.key)
                        var page=bridge.collectionPage(target.key)
                        repeat(80) { if(page.tracks.isEmpty()&&page.loading) { Thread.sleep(100);page=bridge.collectionPage(target.key) } }
                        page.tracks
                    }
                } }
                action { check(tracks.isNotEmpty()) { "该卡片暂时没有可加入队列的曲目" };bridge.enqueueNext(tracks) }
            } catch(error: Exception) { action { throw error } }
        } },
        isFavorite={ it.key in favorites },
        onFavorite={ item -> favorites=if(item.key in favorites) favorites-item.key else favorites+item.key
            store.edit().putStringSet("favorites",favorites).apply() },
        onDislike={ item -> hidden=hidden+item.key;store.edit().putStringSet("hidden",hidden).apply() },
    )
}
