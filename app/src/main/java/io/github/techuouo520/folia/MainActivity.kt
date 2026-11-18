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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class Shell(
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
        configureSystemBars(FoliaApp.from(this).displaySettings.dark(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES))
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

private fun ComponentActivity.configureSystemBars(dark: Boolean) {
    // SystemBarStyle.auto retains the platform's three-button navigation contrast protection.
    // Do not hide navigation bars: only the scene draws underneath them.
    enableEdgeToEdge(
        statusBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) {dark},
        navigationBarStyle=SystemBarStyle.auto(0xE6FFFFFF.toInt(),0xCC101014.toInt()) {dark},
    )
}

@Composable
private fun FoliaScreen(bridge: FoliaBridge, resumeEpoch: Int) {
    val context = LocalContext.current
    FoliaApp.from(context).appearanceVersion
    val dark=FoliaApp.from(context).displaySettings.dark(androidx.compose.foundation.isSystemInDarkTheme())
    DisposableEffect(dark) {
        (context as? ComponentActivity)?.configureSystemBars(dark)
        onDispose { }
    }
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
        FoliaTheme(bridge,dark) {
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
        }
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialShell = remember(bridge) { idleShell(bridge) }
    val shell by produceState(initialValue = initialShell, bridge, lifecycle) {
        var tracks = withContext(Dispatchers.IO) { runCatching { bridge.tracks() }.getOrDefault(emptyList()) }
        var sawDone = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
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
    }
    var route by remember { mutableStateOf("home") }
    val dockLeft = rememberSaveable { mutableStateOf(false) }
    var playerExpanded by remember {mutableStateOf(!fullscreenMusicBarEnabled)}
    val commandScope=rememberCoroutineScope()
    val commandLock=remember {Mutex()}
    var message by remember { mutableStateOf<String?>(null) }
    val action: (() -> Unit) -> Unit = { block ->
        commandScope.launch {
            try {
                commandLock.withLock {withContext(Dispatchers.IO) {block()}}
                message=null;PlaybackService.requestRefresh()
            } catch(error: Exception) {
                if(error is kotlinx.coroutines.CancellationException) throw error
                Log.e("Folia","playback action failed",error);message=error.message?:error.javaClass.simpleName
            }
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
    FoliaTheme(bridge,dark,shell.playback) {
    val playerFrost=rememberFrostBackdrop()
    val playerProgress=remember(route) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val playerFootprint = if (fullscreenMusicBarEnabled) 88.dp else MusicBarDockBottom + CORNER_BUTTON_DIAMETER.dp
    val clearance = remember { PlayerClearance() }
    val sponsor = rememberSponsorCredit(shell.playback)
    val screenCorner=playerDisplayRadius()
    val playerInFront by remember(playerProgress,playerExpanded) {
        androidx.compose.runtime.derivedStateOf { playerExpanded || playerProgress.floatValue>0f }
    }
    val gripGate = remember { GripGate() }
    val dockTouch = if (!fullscreenMusicBarEnabled && route == "home") {
        val grip = remember { GripContact() }
        ObserveGripSensors(grip)
        Modifier.observeMusicBarSide(dockLeft, grip, gripGate)
    } else Modifier
    androidx.compose.runtime.CompositionLocalProvider(LocalGripGate provides gripGate, LocalPlayerClearance provides clearance,
        LocalSponsorCredit provides sponsor) {
    AppCornerWindows(bridge,modifier=dockTouch,playerInFront=fullscreenMusicBarEnabled && route=="home" && playerInFront,player={
        if (route == "home") {
            if (fullscreenMusicBarEnabled) MusicBar(bridge,shell.playback,playerFrost,playerExpanded,{playerExpanded=it},
                toggle,action,playerProgress,screenCorner)
            else MusicBarBubble(bridge,shell.playback,playerFrost,playerExpanded,{playerExpanded=it},toggle,action,
                dockLeft=dockLeft.value)
        }
    }) {
    when (route) {
        "search" -> SearchPage(bridge = bridge, onBack = { route = "home" }, onPlay = { tracks, index ->
            if (!AudioFocus.acquire(context, bridge)) {
                message = context.getString(R.string.focus_denied)
            } else {
                action { bridge.playTracks(tracks, index) }
                route = "home";playerExpanded=true
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
            onOpenPlayer = { playerExpanded=true },
            preloadPlayerLyrics = !fullscreenMusicBarEnabled && playerExpanded && !shell.playback.currentLyric.isNullOrBlank(),
            playerProgress=playerProgress,playerFootprint=playerFootprint,frost=playerFrost,screenCorner=screenCorner,
            onOpenSearch = { route = "search" },
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
}
}

}

// The original immersive player and its transition remain available behind this switch.
private val fullscreenMusicBarEnabled = false

private fun idleShell(bridge: FoliaBridge): Shell {
    val playback = runCatching { bridge.status() }.getOrElse {
        PlaybackSnapshot(TransportState.IDLE, 0.0, 0.0, 1f, 0L, "", "", "", "", -1, 0, false, false, RepeatMode.OFF, 0L, false, "", "", "", null)
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
