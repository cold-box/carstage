package com.carstage.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.carstage.app.home.CabinLook.Badge
import com.carstage.app.home.CabinLook.Change
import com.carstage.app.stage.Side
import com.carstage.app.ui.CabinColors
import com.carstage.app.ui.StageColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The cabin's settings as one pill under the drawing, without words (docs/10 6장 "상태 알약"):
 * always the ambient light, as a dot in its color and a bar for its brightness, the pill's edge in
 * the same color; beside it, only while the stage changes them ([CabinLook.badges]), the reading
 * lights, seat ventilation, temperature and the fan. The pill makes room over 0.2 s and the
 * badge then fades in over 0.2 s (out the other way round), the pill staying centered. Which seat
 * a badge is about shows as a dot in the seat's duel color, never as text. A screen reader reads the same settings as sentences.
 */
@Composable
internal fun CabinStatus(look: CabinLook, modifier: Modifier = Modifier) {
    val on = look.ambientArgb != null
    val ambient by animateColorAsState(look.ambientArgb?.let(::Color) ?: OFF, tween(FADE_MS), label = "ambient")
    val badges = look.badges
    Row(
        modifier = modifier
            .semantics { contentDescription = look.captions.joinToString(". ") }
            .height(PILL_HEIGHT)
            // The badges' own expand animation sizes the pill; clipping keeps a badge that is still
            // growing inside the pill's edge instead of drawing over it.
            .clip(PILL)
            .background(StageColors.Surface, PILL)
            .border(2.dp, if (on) ambient.copy(alpha = 0.7f) else OFF, PILL)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmbientMeter(on, ambient, look.brightness)
        for (badge in Badge.entries) {
            AnimatedVisibility(
                visible = badge in badges,
                // Room first, then the badge: fading while the pill still grows showed half an icon
                // sliced at the pill's edge. Leaving runs the other way round.
                enter = expandHorizontally(tween(FADE_MS)) + fadeIn(tween(FADE_MS, delayMillis = FADE_MS)),
                exit = fadeOut(tween(FADE_MS)) + shrinkHorizontally(tween(FADE_MS, delayMillis = FADE_MS)),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(2.dp).height(28.dp).background(OFF))
                    when (badge) {
                        Badge.READING_LIGHTS -> ReadingLightBadge(look)
                        Badge.VENTILATION -> VentilationBadge(look)
                        Badge.TEMPERATURE -> TemperatureBadge(look)
                        Badge.FAN -> FanBadge(look)
                    }
                }
            }
        }
        // The square badges need more room than the round dot before the pill's curved end, or their
        // corners meet the curve and look like they cross the edge: an empty slot adds the row's
        // spacing after the last badge, and only while there are badges, so the meter alone stays centered.
        AnimatedVisibility(
            visible = badges.isNotEmpty(),
            enter = expandHorizontally(tween(FADE_MS)),
            exit = shrinkHorizontally(tween(FADE_MS, delayMillis = FADE_MS)),
        ) {
            Box(Modifier)
        }
    }
}

/** The ambient light: a dot in its color with a soft glow, and a bar as long as its brightness. */
@Composable
private fun AmbientMeter(on: Boolean, color: Color, brightness: Float) {
    val level by animateFloatAsState(brightness, tween(FADE_MS), label = "level")
    // A layer of its own: the bar follows the voices, so it redraws alone.
    Canvas(Modifier.width(METER_WIDTH).height(ICON).graphicsLayer()) {
        val r = size.height * 0.2f
        val dot = Offset(r * 1.8f, size.height / 2)
        if (on) drawCircle(color.copy(alpha = 0.3f), r * 1.8f, dot)
        drawCircle(if (on) color else OFF, r, dot)
        val left = r * 4.2f
        val bar = size.height * 0.16f
        val y = (size.height - bar) / 2
        drawRoundRect(OFF, Offset(left, y), Size(size.width - left, bar), CornerRadius(bar / 2))
        if (on && level > 0f) {
            drawRoundRect(color, Offset(left, y), Size((size.width - left) * level, bar), CornerRadius(bar / 2))
        }
    }
}

/** A badge's drawing in a square of [ICON]; [sides] adds a dot in each seat's color in the top right. */
@Composable
private fun Badge(sides: List<Side> = emptyList(), draw: DrawScope.() -> Unit) {
    Canvas(Modifier.size(ICON).graphicsLayer()) {
        draw()
        sides.forEachIndexed { i, side ->
            val r = size.width * 0.08f
            drawCircle(StageColors.side(side), r, Offset(size.width - r - i * r * 2.6f, r))
        }
    }
}

/** Two lamps, driver left; the lit one is warm and shines a cone down. */
@Composable
private fun ReadingLightBadge(look: CabinLook) {
    val driver by animateFloatAsState(if (look.readingLights[Side.DRIVER] == true) 1f else 0f, tween(FADE_MS), label = "driver")
    val passenger by animateFloatAsState(if (look.readingLights[Side.PASSENGER] == true) 1f else 0f, tween(FADE_MS), label = "passenger")
    Badge {
        lamp(size.width * 0.27f, driver)
        lamp(size.width * 0.73f, passenger)
    }
}

private fun DrawScope.lamp(x: Float, lit: Float) {
    val w = size.width
    // The dome spans top - 0.4 r to top + 0.6 r; its cord reaches up to the ceiling and its light down to the floor.
    val top = w * 0.3f
    val r = w * 0.17f
    val cone = Path().apply {
        moveTo(x - r * 0.8f, top + r * 0.6f)
        lineTo(x + r * 0.8f, top + r * 0.6f)
        lineTo(x + r * 1.15f, w)
        lineTo(x - r * 1.15f, w)
        close()
    }
    drawPath(cone, lerp(OFF, CabinColors.Warm, lit), alpha = 0.35f + 0.25f * lit)
    val dome = Path().apply {
        moveTo(x - r, top + r * 0.6f)
        arcTo(Rect(x - r, top - r * 0.4f, x + r, top + r * 1.6f), 180f, 180f, false)
        close()
    }
    drawPath(dome, lerp(CabinColors.Lamp, CabinColors.Warm, lit))
    drawLine(lerp(CabinColors.Lamp, CabinColors.Warm, lit), Offset(x, top - r * 0.4f), Offset(x, 0f), w * 0.05f, StrokeCap.Round)
}

/** A seat with three waves of air over it, and a dot for each ventilated seat. */
@Composable
private fun VentilationBadge(look: CabinLook) {
    Badge(lastWhile(look.ventilatedSides) { it.isNotEmpty() }) {
        val w = size.width
        val x = w * 0.42f
        val seatW = w * 0.38f
        drawRoundRect(CabinColors.Lamp, Offset(x - seatW / 2, w * 0.58f), Size(seatW, w * 0.28f), CornerRadius(w * 0.08f))
        drawRoundRect(CabinColors.Lamp, Offset(x - seatW / 2 - w * 0.03f, w * 0.84f), Size(seatW + w * 0.06f, w * 0.12f), CornerRadius(w * 0.05f))
        for (i in 0 until WAVES) {
            val y = w * (0.46f - i * 0.16f)
            val wave = Path().apply {
                val left = x - seatW / 2
                moveTo(left, y)
                for (k in 1..12) {
                    val t = k / 12f
                    lineTo(left + seatW * t, y + sin(t * 2 * PI).toFloat() * w * 0.035f)
                }
            }
            drawPath(wave, CabinColors.Cool, style = Stroke(w * 0.06f, cap = StrokeCap.Round))
        }
    }
}

/** One thermometer: blue pointing down when a seat was cooled, warm pointing up when warmed; a dot for each such seat. */
@Composable
private fun TemperatureBadge(look: CabinLook) {
    val (way, sides) = lastWhile(look.temperatureBadge) { it.first != Change.NONE }
    val color by animateColorAsState(if (way == Change.UP) CabinColors.Warm else CabinColors.Cool, tween(FADE_MS), label = "temperature")
    Badge(sides) {
        val w = size.width
        val x = w * 0.36f
        val tube = w * 0.18f
        val bottom = w * 0.8f
        drawRoundRect(color, Offset(x - tube / 2, w * 0.08f), Size(tube, bottom - w * 0.08f), CornerRadius(tube / 2), style = Stroke(w * 0.05f))
        drawCircle(color, w * 0.15f, Offset(x, bottom))
        val top = if (way == Change.UP) w * 0.2f else w * 0.55f
        drawRoundRect(color, Offset(x - tube * 0.2f, top), Size(tube * 0.4f, bottom - top), CornerRadius(tube * 0.2f))
        if (way != Change.NONE) chevron(Offset(w * 0.76f, w * 0.55f), color, up = way == Change.UP)
    }
}

/** A three-blade fan, light blue with an up arrow when the stage raised it, deeper blue with a down arrow when it lowered it. */
@Composable
private fun FanBadge(look: CabinLook) {
    val up = lastWhile(look.fan) { it != 0 } > 0
    val color = if (up) CabinColors.Air else CabinColors.Cool
    // The blades turn a little with the change, so it shows even in a still picture.
    val turn by animateFloatAsState(look.fan.coerceIn(-MAX_FAN, MAX_FAN) * 30f, tween(FADE_MS), label = "turn")
    Badge {
        val w = size.width
        val hub = Offset(w * 0.4f, w / 2)
        rotate(turn, hub) {
            for (i in 0 until 3) {
                rotate(i * 120f, hub) {
                    drawOval(color, Offset(hub.x - w * 0.1f, hub.y - w * 0.4f), Size(w * 0.2f, w * 0.34f))
                }
            }
        }
        drawCircle(StageColors.Surface, w * 0.08f, hub)
        drawCircle(color, w * 0.045f, hub)
        chevron(Offset(w * 0.88f, w * 0.5f), color, up)
    }
}

/**
 * [value] while [shown] says it is worth showing, else the last one that was: a badge fading out
 * keeps its last look (its seat dots, its arrow) instead of losing them as the stage resets.
 */
@Composable
private fun <T> lastWhile(value: T, shown: (T) -> Boolean): T {
    val last = remember { arrayOfNulls<Any?>(1) }
    if (shown(value) || last[0] == null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T
}

/** A small open arrowhead centered on [center]. */
private fun DrawScope.chevron(center: Offset, color: Color, up: Boolean) {
    val k = size.width * 0.12f
    val dy = if (up) k / 2 else -k / 2
    val path = Path().apply {
        moveTo(center.x - k, center.y + dy)
        lineTo(center.x, center.y - dy)
        lineTo(center.x + k, center.y + dy)
    }
    drawPath(path, color, style = Stroke(size.width * 0.07f, cap = StrokeCap.Round))
}

/** What is off: a dim lamp grey, about 3:1 on the pill. */
private val OFF = CabinColors.Lamp.copy(alpha = 0.5f)
private val PILL_HEIGHT = 64.dp
private val PILL = RoundedCornerShape(PILL_HEIGHT / 2)
private val ICON = 36.dp
private val METER_WIDTH = 150.dp
private const val FADE_MS = 200
private const val WAVES = 3
private const val MAX_FAN = 4
