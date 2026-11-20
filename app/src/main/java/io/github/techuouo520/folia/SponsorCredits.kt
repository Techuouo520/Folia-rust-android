package io.github.techuouo520.folia

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.bridge.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal data class SponsorCredit(val id: String, val name: String, val platform: String?, val amountFen: Long?)
internal data class SponsorPresentation(val credit: SponsorCredit, val track: String, val visible: Boolean, val hideAt: Long)

internal val LocalSponsorCredit = staticCompositionLocalOf<SponsorPresentation?> { null }

private const val SPONSOR_VISIBLE_MS = 6_000L

private fun readSponsorCredits(context: Context): List<SponsorCredit> = try {
    val rows = context.assets.open("sponsors.json").bufferedReader().use { JSONArray(it.readText()) }
    buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.isNull("id") || row.isNull("name")) continue
            val id = row.optString("id").trim()
            val name = row.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            val platform = if (row.isNull("platform")) null else row.optString("platform").trim().takeIf { it.isNotEmpty() }
            val amount = row.optLong("amountFen", -1).takeIf { it > 0 }
            add(SponsorCredit(id, name, platform, amount))
        }
    }.distinctBy { it.id }
} catch (error: Exception) {
    Log.w("FoliaSponsor", "Could not load sponsor credits", error)
    emptyList()
}

@Composable
internal fun rememberSponsorCredit(playback: PlaybackSnapshot): SponsorPresentation? {
    val context = LocalContext.current.applicationContext
    val sponsors by produceState<List<SponsorCredit>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) { readSponsorCredits(context) }
    }
    val owner = playback.artSource()?.owner
    val track = remember(owner) {
        owner?.let {
            JSONArray(listOf(it.id, it.provider, it.song, it.localFile)).toString()
        }
    }
    var selectedTrack by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var timedTrack by rememberSaveable { mutableStateOf<String?>(null) }
    var hideAt by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(track) {
        if (track == null) {
            timedTrack = null
            hideAt = 0L
        } else if (track != timedTrack) {
            timedTrack = track
            hideAt = SystemClock.elapsedRealtime() + SPONSOR_VISIBLE_MS
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible by produceState(false, track, timedTrack, hideAt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val remaining = if (track != null && track == timedTrack) hideAt - SystemClock.elapsedRealtime() else 0L
            // A restored deadline from before a device reboot must not extend the display window.
            value = remaining in 1..SPONSOR_VISIBLE_MS
            if (value) {
                delay(remaining)
                value = false
            }
        }
    }
    LaunchedEffect(track, sponsors) {
        if (track == null) {
            selectedTrack = null
            selectedId = null
        } else if (sponsors.isNotEmpty() && (track != selectedTrack || sponsors.none { it.id == selectedId })) {
            val choices = if (sponsors.size > 1) sponsors.filter { it.id != selectedId } else sponsors
            selectedId = choices.random().id
            selectedTrack = track
        }
    }
    // The root owns the choice so page recreation, seeking and pause/resume cannot reroll it.
    return sponsors.firstOrNull { track != null && selectedTrack == track && it.id == selectedId }
        ?.let { SponsorPresentation(it, selectedTrack.orEmpty(), visible && timedTrack == track, hideAt) }
}

@Composable
internal fun BoxScope.SponsorFooter(bridge: FoliaBridge) {
    val presentation = LocalSponsorCredit.current ?: return
    val viewport = LocalLyricsViewport.current
    val footerBottom = rustTravel(if (viewport.landscape) 12f else
        (MusicBarDockBottom + CORNER_BUTTON_DIAMETER.dp + 12.dp).value, 14.0,
        (MusicBarDockBottom + CORNER_BUTTON_DIAMETER.dp + 12.dp).value)
    val footerWidth = if (viewport.landscape)
        (viewport.cardX-10f-viewport.left-MusicBarDockSide.value-64f).coerceIn(1f,360f).dp else 360.dp
    val sponsor = presentation.credit
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = Locale.getDefault()
    val amount = remember(sponsor.amountFen, locale) {
        sponsor.amountFen?.let {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 2
            }.format(BigDecimal.valueOf(it, 2))
        }
    }
    val contribution = if (amount == null) bridge.t("sponsorship.support")
        else bridge.t("sponsorship.contribution").replace("{{amount}}", amount)
    Box(Modifier.align(AbsoluteAlignment.BottomLeft).windowInsetsPadding(WindowInsets.safeDrawing)
        .absolutePadding(left = MusicBarDockSide + 48.dp + 16.dp, right = MusicBarDockSide,
            bottom = footerBottom.floatValue.dp)) {
        key(presentation.track) {
            AnimatedVisibility(visible = presentation.visible && SystemClock.elapsedRealtime() < presentation.hideAt,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(300, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it / 4 }) {
                Column(Modifier.widthIn(max = footerWidth).avoidPlayer().semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(bridge.t("sponsorship.thanks").replace("{{name}}", sponsor.name),
                            modifier = Modifier.weight(1f, fill = false), color = ink, fontSize = 12.sp,
                            lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        sponsor.platform?.let { platform ->
                            val shape = RoundedCornerShape(4.dp)
                            Text(platform, modifier = Modifier.widthIn(max = 100.dp).background(ink.copy(alpha = .05f), shape)
                                .border(1.dp, ink.copy(alpha = .12f), shape).padding(horizontal = 5.dp, vertical = 1.dp),
                                color = ink, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(contribution, color = ink, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
