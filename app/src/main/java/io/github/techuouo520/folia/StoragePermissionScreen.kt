package io.github.techuouo520.folia

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.techuouo520.folia.spatial.SceneGlyph
import io.github.techuouo520.folia.spatial.drawSceneGlyph

/** The permission gate uses the same theme, ordinary frost and Rust motion as the app.
 * Its artwork is drawn locally: no media access or catalogue work precedes permission. */
@Composable
fun StoragePermissionScreen(legacy: Boolean, onAllow: () -> Unit) {
    val dark = LocalFoliaDark.current
    val colors = MaterialTheme.colorScheme
    val frost = rememberFrostBackdrop()
    val arrival = rustTravel(1f, 12.0)
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Canvas(Modifier.fillMaxSize().frostSource(frost).clearAndSetSemantics {}) {
            drawRect(colors.background)
            val span = size.maxDimension
            drawRect(Brush.radialGradient(
                listOf(colors.primary.copy(alpha = if (dark) .16f else .13f), Color.Transparent),
                center = Offset(size.width * .24f, size.height * .43f), radius = span * .65f,
            ))
            drawRect(Brush.radialGradient(
                listOf(colors.tertiary.copy(alpha = if (dark) .09f else .12f), Color.Transparent),
                center = Offset(size.width * .88f, size.height * .82f), radius = span * .52f,
            ))
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Text("Folia", color = colors.onBackground, fontSize = 20.sp,
                fontWeight = FontWeight.Medium, letterSpacing = .4.sp,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 720.dp && maxWidth > maxHeight
                val viewportHeight = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.widthIn(max = 1040.dp).fillMaxWidth()
                        .heightIn(min = viewportHeight).padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center) {
                        if (wide) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                                PermissionArtwork(Modifier.weight(1f).heightIn(max = 380.dp).aspectRatio(1f), arrival)
                                PermissionPanel(legacy, onAllow, frost, dark, arrival,
                                    Modifier.weight(1f).widthIn(max = 440.dp))
                            }
                        } else {
                            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                PermissionArtwork(Modifier.size(if (viewportHeight < 620.dp) 164.dp else 232.dp), arrival)
                                PermissionPanel(legacy, onAllow, frost, dark, arrival, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPanel(legacy: Boolean, onAllow: () -> Unit, frost: FrostBackdrop,
    dark: Boolean, arrival: FloatState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.graphicsLayer {
        val p = arrival.floatValue
        translationY = (1f - p) * 20.dp.toPx()
        alpha = p.coerceIn(0f, 1f)
    }.clip(RoundedCornerShape(32.dp)).frostedBackdrop(frost, dark).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(50.dp).clip(CircleShape).background(colors.primary.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                PermissionGlyph(SceneGlyph.Map, colors.primary)
            }
            Text(stringResource(R.string.permission_storage_category), color = colors.onSurfaceVariant,
                fontSize = 13.sp, lineHeight = 19.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.permission_storage_title), color = colors.onSurface,
                fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(if (legacy) R.string.permission_storage_legacy_body else R.string.permission_storage_body),
                color = colors.onSurfaceVariant, fontSize = 15.sp, lineHeight = 25.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val press = rustTravel(if (pressed) .97f else 1f, 22.0, 1f)
            Row(Modifier.fillMaxWidth().graphicsLayer {
                scaleX = press.floatValue; scaleY = press.floatValue
            }.clip(RoundedCornerShape(20.dp)).background(colors.primary)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onAllow)
                .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (legacy) R.string.permission_storage_legacy_action else R.string.permission_storage_action),
                    color = colors.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                PermissionGlyph(SceneGlyph.Open, colors.onPrimary)
            }
            Text(stringResource(R.string.permission_storage_return), color = colors.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun PermissionGlyph(glyph: SceneGlyph, color: Color) {
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) { drawSceneGlyph(glyph, color) }
    }
}

/** Album sleeves echo the map's layered cards without inventing a user's library. */
@Composable
private fun PermissionArtwork(modifier: Modifier, arrival: FloatState) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalFoliaDark.current
    Canvas(modifier.clearAndSetSemantics {}.graphicsLayer {
        val p = arrival.floatValue
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 28.dp.toPx()
        scaleX = .94f + p * .06f; scaleY = scaleX
    }) {
        val unit = size.minDimension / 340f
        translate((size.width - 340f * unit) * .5f, (size.height - 340f * unit) * .5f) {
            scale(unit, unit, Offset.Zero) {
                drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = .17f), Color.Transparent),
                    center = Offset(170f, 190f), radius = 162f), 162f, Offset(170f, 190f))
                rotate(13f, Offset(190f, 160f)) {
                    drawRoundRect(colors.primary.copy(alpha = if (dark) .12f else .16f),
                        Offset(92f, 40f), Size(208f, 244f), CornerRadius(21f))
                }
                rotate(-11f, Offset(140f, 180f)) {
                    drawRoundRect(colors.onSurface.copy(alpha = .06f), Offset(36f, 62f), Size(220f, 244f), CornerRadius(23f))
                }
                rotate(-3f, Offset(168f, 170f)) {
                    drawRoundRect(Brush.linearGradient(listOf(
                        colors.surfaceContainerHighest, colors.surfaceContainer, colors.surfaceContainerLow),
                        Offset(62f, 62f), Offset(284f, 290f)),
                        Offset(58f, 56f), Size(224f, 244f), CornerRadius(24f))
                    // A printed disc and lyric lines share one sleeve, like the map's music cards.
                    val center = Offset(170f, 157f)
                    drawCircle(colors.onSurface.copy(alpha = if (dark) .12f else .055f), 75f, center)
                    for (r in listOf(60f, 65f, 70f)) {
                        drawCircle(colors.onSurface.copy(alpha = .06f), r, center, style = Stroke(.7f))
                    }
                    drawCircle(Brush.linearGradient(listOf(colors.primary, colors.tertiary)), 29f, center)
                    drawCircle(colors.surfaceContainer, 6f, center)
                    drawLine(colors.onSurface.copy(alpha = .60f), Offset(82f, 253f), Offset(180f, 253f), 4f, StrokeCap.Round)
                    drawLine(colors.onSurface.copy(alpha = .20f), Offset(82f, 269f), Offset(145f, 269f), 3f, StrokeCap.Round)
                }
            }
        }
    }
}
