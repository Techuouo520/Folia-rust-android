// Library, player, and the permission gate. Playback position is read from Rust on a timer.
package io.github.techuouo520.folia

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.LibraryTrack
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import io.github.techuouo520.folia.bridge.RepeatMode
import io.github.techuouo520.folia.bridge.ScanPhase
import io.github.techuouo520.folia.bridge.ScanSnapshot
import io.github.techuouo520.folia.bridge.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class Shell(
    val playback: PlaybackSnapshot,
    val scan: ScanSnapshot,
    val tracks: List<LibraryTrack>,
)

class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = FoliaApp.from(this).bridge
        setContent { FoliaScreen(bridge, resumed.intValue) }
    }

    override fun onStart() {
        super.onStart()
        if (controllerFuture == null) {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, token).buildAsync()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue += 1
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}

@Composable
private fun FoliaScreen(bridge: FoliaBridge, resumeEpoch: Int) {
    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(storageGranted(context)) }
    var notificationsGranted by remember { mutableStateOf(notificationsGranted(context)) }
    LaunchedEffect(resumeEpoch) {
        storageGranted = storageGranted(context)
        notificationsGranted = notificationsGranted(context)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        storageGranted = storageGranted(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = notificationsGranted(context)
    }
    if (!storageGranted) {
        StoragePermissionScreen(
            legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
            onAllow = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openAllFilesAccess(context)
                } else {
                    storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
        )
        return
    }
    val shell by produceState(initialValue = idleShell(bridge), bridge) {
        var tracks = withContext(Dispatchers.IO) { runCatching { bridge.tracks() }.getOrDefault(emptyList()) }
        var sawDone = false
        while (true) {
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    val scan = bridge.scanSnapshot()
                    var loaded = tracks
                    if (scan.phase == ScanPhase.RUNNING) {
                        sawDone = false
                    } else if (scan.phase == ScanPhase.DONE && !sawDone) {
                        loaded = runCatching { bridge.tracks() }.getOrDefault(tracks)
                        sawDone = true
                    }
                    Triple(bridge.status(), scan, loaded)
                }.getOrNull()
            }
            if (next != null) {
                tracks = next.third
                value = Shell(next.first, next.second, tracks)
            }
            delay(250)
        }
    }
    var route by remember { mutableStateOf("home") }
    var collectionKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val action: (() -> Unit) -> Unit = { block ->
        try {
            block()
            message = null
            PlaybackService.requestRefresh()
        } catch (error: Exception) {
            Log.e("Folia", "playback action failed", error)
            message = error.message ?: error.javaClass.simpleName
        }
    }
    val playAt: (Int) -> Unit = { index ->
        if (!AudioFocus.acquire(context, bridge)) {
            message = context.getString(R.string.focus_denied)
        } else {
            action { bridge.playLibrary(index) }
        }
    }
    val toggle = {
        if (shell.playback.state == TransportState.PLAYING) {
            action {
                AudioFocus.clearResume()
                bridge.pause()
                AudioFocus.abandon()
            }
        } else if (!AudioFocus.acquire(context, bridge)) {
            message = context.getString(R.string.focus_denied)
        } else {
            action { bridge.play() }
        }
    }
    when (route) {
        "player" -> PlayerPage(
            bridge = bridge,
            playback = shell.playback,
            message = message,
            onBack = { route = "home" },
            onToggle = toggle,
            onPrevious = { action { bridge.previous() } },
            onNext = { action { bridge.next() } },
            onSeek = { seconds -> action { bridge.seek(seconds) } },
            onCycleRepeat = { action { bridge.cycleRepeat() } },
        )
        "search" -> SearchPage(bridge = bridge, onBack = { route = "home" }, onPlay = { tracks, index ->
            if (!AudioFocus.acquire(context, bridge)) {
                message = context.getString(R.string.focus_denied)
            } else {
                action { bridge.playTracks(tracks, index) }
                route = "player"
            }
        })
        "collection" -> CollectionPage(bridge = bridge, collectionKey = collectionKey, onBack = { route = "home" }, onPlay = { tracks, index ->
            if (!AudioFocus.acquire(context, bridge)) {
                message = context.getString(R.string.focus_denied)
            } else {
                action { bridge.playTracks(tracks, index) }
                route = "player"
            }
        })
        else -> HomePage(
            bridge = bridge,
            shell = shell,
            message = message,
            notificationsGranted = notificationsGranted,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenPlayer = { route = "player" },
            onOpenSearch = { route = "search" },
            onOpenCollection = { key ->
                collectionKey = key
                route = "collection"
            },
            onPlay = { tracks, index ->
                if (!AudioFocus.acquire(context, bridge)) {
                    message = context.getString(R.string.focus_denied)
                } else {
                    action { bridge.playTracks(tracks, index) }
                }
            },
            onToggle = toggle,
            action = action,
        )
    }
}

private fun idleShell(bridge: FoliaBridge): Shell {
    val playback = runCatching { bridge.status() }.getOrElse {
        PlaybackSnapshot(TransportState.IDLE, 0.0, 0.0, 1f, 0L, "", "", "", "", -1, 0, false, false, RepeatMode.OFF, 0L, false, "", "", "")
    }
    return Shell(playback, ScanSnapshot(ScanPhase.IDLE, 0, 0, 0, 0, 0, 0, "", ""), emptyList())
}

private fun storageGranted(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun notificationsGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) {
        return true
    }
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

private fun openAllFilesAccess(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
