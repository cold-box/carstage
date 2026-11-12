package com.carstage.app.home

import android.graphics.BlurMaskFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.carstage.app.stage.Side
import com.carstage.app.ui.CabinColors
import com.carstage.app.ui.StageColors

/**
 * The cabin seen from above, front at the top (docs/10 6장), drawn from what the app sent to the
 * simulator: the ambient strips along the dashboard and doors glowing onto the floor like mood
 * lights, each reading light a cone of warm light from the roof onto its front seat, seat
 * ventilation as waves over the seat and the fan's air from the center vents; a seat's name tells
 * its temperature when the stage changed it. The emulator does not show light changes, so this is
 * what demos and videos show. What does not change is drawn once per size into images, and the
 * ambient light into a white mask, so each frame, which comes at the display's rate while the
 * lights fade, draws those images with the tinted mask and the strips between them, then the
 * air, reading lights, ventilation and seat names over the interior. Its own graphics layer keeps
 * a frame from re-recording the rest of the screen, as for the stage glow.
 */
@Composable
fun CabinView(look: CabinLook, modifier: Modifier = Modifier) {
    // Commands arrive about every 120 ms; a slightly shorter fade keeps up without stepping.
    val fade = tween<Float>(durationMillis = 100)
    val ambient by animateColorAsState(look.ambientArgb?.let(::Color) ?: CabinColors.Unlit, tween(100), label = "ambient")
    val glow by animateFloatAsState(look.ambientGlow, fade, label = "glow")
    val driverLight by animateFloatAsState(if (look.readingLights[Side.DRIVER] == true) 1f else 0f, fade, label = "driverLight")
    val passengerLight by animateFloatAsState(if (look.readingLights[Side.PASSENGER] == true) 1f else 0f, fade, label = "passengerLight")
    // Ventilation and the fan go by steps, which fade in 0.2 s like any change on the screen (docs/10 5장 "움직임").
    val step = tween<Float>(durationMillis = STEP_MS)
    val driverAir by animateFloatAsState(look.ventilation.getValue(Side.DRIVER).coerceIn(0, WAVES).toFloat(), step, label = "driverAir")
    val passengerAir by animateFloatAsState(look.ventilation.getValue(Side.PASSENGER).coerceIn(0, WAVES).toFloat(), step, label = "passengerAir")
    val fan by animateFloatAsState(look.fan.coerceIn(0, MAX_FAN).toFloat(), step, label = "fan")
    val text = rememberTextMeasurer()
    // The seat names in the smallest size a car's screen should show (24, Android's car app quality UX-3).
    val labels = MaterialTheme.typography.labelMedium
    // Measured only when a name changes, and read only while drawing, so a new name redraws without rebuilding the images.
    val names by rememberUpdatedState(
        Side.entries.associateWith { side ->
            val label = look.seatLabel(side)
            // The measurer is new with a new density, which the activity now takes without being recreated.
            SeatName(remember(label, labels, text) { text.measure(label, labels) }, nameColor(look.temperatureChange(side)))
        },
    )

    Spacer(
        modifier.aspectRatio(ASPECT).graphicsLayer().drawWithCache {
            // Nothing to draw, and a blur of no width is refused.
            if (size.minDimension < 1f) return@drawWithCache onDrawBehind {}
            val cabin = CabinShapes(size)
            val under = picture {
                car(cabin)
                floor(cabin)
            }
            // Only its alpha is used, as it is tinted.
            val light = picture(ImageBitmapConfig.Alpha8) { ambientLight(cabin) }
            val over = picture {
                interior(cabin)
                wheel(cabin)
            }
            onDrawBehind {
                drawImage(under)
                if (glow > 0f) drawImage(light, alpha = glow, colorFilter = ColorFilter.tint(ambient))
                // A strip whitens as it brightens, as a lit strip does, and stays a dim line when off.
                val strip = lerp(ambient, Color.White, HOT_CORE * glow)
                cabin.strips.forEach { drawPath(it, strip, alpha = 0.6f + 0.4f * glow, style = cabin.core) }
                drawImage(over)
                air(cabin, fan)
                readingLight(cabin.driver, driverLight)
                readingLight(cabin.passenger, passengerLight)
                // Over the light, as the duel's crown lights and cools the winner's seat together.
                ventilation(cabin.driver, driverAir)
                ventilation(cabin.passenger, passengerAir)
                names.forEach { (side, name) -> seatName(cabin.seat(side), name) }
            }
        },
    )
}

/** Draws [draw] once into an image of this size, which a frame then draws whole. */
private fun CacheDrawScope.picture(config: ImageBitmapConfig = ImageBitmapConfig.Argb8888, draw: DrawScope.() -> Unit): ImageBitmap {
    val image = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1), config)
    CanvasDrawScope().draw(this, layoutDirection, Canvas(image), size, draw)
    return image
}

/** The body, its mirrors and windows. */
private fun DrawScope.car(cabin: CabinShapes) {
    (cabin.mirrors + cabin.body).forEach { part ->
        drawPath(part, CabinColors.Body)
        drawPath(part, CabinColors.BodyEdge, style = cabin.edge)
    }
    drawPath(cabin.windshield, cabin.frontGlass)
    drawPath(cabin.rearWindow, cabin.rearGlass)
}

/** The cabin's floor and its dashboard, dark so the ambient light shows on them. */
private fun DrawScope.floor(cabin: CabinShapes) {
    drawPath(cabin.floor, CabinColors.Floor)
    clipPath(cabin.floor) { drawPath(cabin.dashboard, CabinColors.Dashboard) }
}

/**
 * The ambient strips' light at full glow, in white to be tinted: a faint tint over the cabin and
 * a soft halo around each strip ([HALOS]), kept inside the cabin.
 */
private fun DrawScope.ambientLight(cabin: CabinShapes) {
    clipPath(cabin.floor) {
        drawRect(cabin.tint, alpha = TINT)
        drawIntoCanvas { canvas ->
            HALOS.forEach { (width, blur, alpha) ->
                val paint = Paint().apply {
                    color = Color.White.copy(alpha = alpha)
                    style = PaintingStyle.Stroke
                    strokeWidth = width * size.width
                    strokeCap = StrokeCap.Round
                }
                paint.asFrameworkPaint().maskFilter = BlurMaskFilter(blur * size.width, BlurMaskFilter.Blur.NORMAL)
                cabin.strips.forEach { canvas.drawPath(it, paint) }
            }
        }
    }
}

/** Vents, the console and the seats, which stand dark against the light on the floor. */
private fun DrawScope.interior(cabin: CabinShapes) {
    cabin.vents.forEach { drawPath(it, CabinColors.Vent) }
    drawPath(cabin.console, CabinColors.Console)
    drawCircle(CabinColors.SeatLine, cabin.knobRadius, cabin.knob)
    listOf(cabin.driver, cabin.passenger).forEach { seat ->
        drawPath(seat.back, CabinColors.SeatBack)
        drawPath(seat.cushion, CabinColors.Seat)
        drawPath(seat.headrest, CabinColors.Seat)
        seat.bolsters.forEach { (from, to) -> drawLine(CabinColors.SeatLine, from, to, cabin.thin, StrokeCap.Round) }
    }
    drawPath(cabin.benchBack, CabinColors.SeatBack)
    drawPath(cabin.bench, CabinColors.Seat)
    cabin.headrests.forEach { drawPath(it, CabinColors.Seat) }
    cabin.seams.forEach { (from, to) -> drawLine(CabinColors.SeatLine, from, to, cabin.thin, StrokeCap.Round) }
}

/**
 * Waves of cool air over a seat, one more per step up to [WAVES]; [level] fades between steps.
 * Each wave has a rim of the seat's color, so it keeps its contrast on a reading light's pool.
 */
private fun DrawScope.ventilation(seat: CabinShapes.FrontSeat, level: Float) {
    if (level <= 0f) return
    drawPath(seat.cushion, CabinColors.Cool, alpha = COOL_TINT * level)
    seat.waves.forEachIndexed { i, wave ->
        val shown = (level - i).coerceIn(0f, 1f)
        if (shown > 0f) {
            drawPath(wave, CabinColors.Seat, alpha = shown, style = seat.waveRim)
            drawPath(wave, CabinColors.Cool, alpha = shown, style = seat.waveStroke)
        }
    }
}

/** The steering wheel on the driver's side (left-hand drive). */
private fun DrawScope.wheel(cabin: CabinShapes) {
    drawCircle(CabinColors.Wheel, cabin.wheelRadius, cabin.wheelCenter, style = cabin.rim)
    drawCircle(CabinColors.Wheel, cabin.hubRadius, cabin.wheelCenter)
    cabin.spokes.forEach { drawLine(CabinColors.Wheel, cabin.wheelCenter, it, cabin.thick, StrokeCap.Round) }
}

/**
 * Air from the center vents toward the front seats, reaching further with each step up to
 * [MAX_FAN] and fading toward its end. Each line has a rim of the dashboard's color, fading with
 * it, so near the vent, where the dashboard strip's glow lights the dashboard, it keeps its contrast.
 */
private fun DrawScope.air(cabin: CabinShapes, fan: Float) {
    if (fan <= 0f) return
    val reach = 0.4f + 0.6f * ((fan - 1f) / (MAX_FAN - 1)).coerceIn(0f, 1f)
    val alpha = fan.coerceAtMost(1f)
    cabin.airflows.forEach { (from, lines) ->
        scale(1f, reach, pivot = from) {
            lines.forEach { drawPath(it, cabin.airRimFade, alpha = alpha, style = cabin.airRim) }
            lines.forEach { drawPath(it, cabin.airFade, alpha = alpha, style = cabin.airStroke) }
        }
    }
}

/** A reading light: a lamp on the roof's front edge and its cone of warm light onto the seat, [on] fading 0..1. */
private fun DrawScope.readingLight(seat: CabinShapes.FrontSeat, on: Float) {
    if (on > 0f) {
        drawPath(seat.cone, seat.beam, alpha = on)
        // The pool is a circle's light squashed into the seat's perspective.
        scale(1f, POOL_SQUASH, pivot = seat.pool) { drawCircle(seat.poolLight, seat.poolRadius, seat.pool, alpha = on) }
        drawCircle(seat.lampGlow, seat.lampGlowRadius, seat.lamp, alpha = on)
        drawCircle(CabinColors.Warm, seat.lampRadius, seat.lamp, alpha = on)
    }
    drawCircle(CabinColors.Lamp, seat.lampRadius, seat.lamp, style = seat.lampRim)
}

/** A seat's name, measured, in the color of its temperature ([nameColor]). */
private class SeatName(val layout: TextLayoutResult, val color: Color)

/** A temperature the stage lowered is cool and one it raised warm; as it was, the name is plain. */
private fun nameColor(change: CabinLook.Change): Color = when (change) {
    CabinLook.Change.DOWN -> CabinColors.Cool
    CabinLook.Change.UP -> CabinColors.Warm
    CabinLook.Change.NONE -> StageColors.TextSecondary
}

/** A seat's name under it. */
private fun DrawScope.seatName(seat: CabinShapes.FrontSeat, name: SeatName) {
    drawText(name.layout, name.color, Offset(seat.center.x - name.layout.size.width / 2f, seat.labelTop))
}

/** The drawing's shapes for one size, in fractions of the width (x, and lengths and strokes) and height (y). */
private class CabinShapes(size: Size) {
    private val w = size.width
    private val h = size.height

    private fun at(x: Float, y: Float) = Offset(x * w, y * h)

    private fun box(left: Float, top: Float, right: Float, bottom: Float, corner: Float) =
        Path().apply { addRoundRect(RoundRect(left * w, top * h, right * w, bottom * h, CornerRadius(corner * w))) }

    private fun shape(build: Path.() -> Unit) = Path().apply(build)
    private fun Path.move(x: Float, y: Float) = moveTo(x * w, y * h)
    private fun Path.line(x: Float, y: Float) = lineTo(x * w, y * h)
    private fun Path.curve(cx: Float, cy: Float, x: Float, y: Float) = quadraticBezierTo(cx * w, cy * h, x * w, y * h)
    private fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        cubicTo(x1 * w, y1 * h, x2 * w, y2 * h, x * w, y * h)

    // A car seen from above: a rounded nose, sides a little wider than the cabin, a rounded tail.
    val body = shape {
        move(0.50f, 0.012f)
        curve(0.72f, 0.012f, 0.89f, 0.025f, 0.93f, 0.085f)
        curve(0.955f, 0.12f, 0.96f, 0.18f, 0.96f, 0.26f)
        curve(0.96f, 0.50f, 0.955f, 0.74f, 0.95f, 0.88f)
        curve(0.945f, 0.955f, 0.85f, 0.988f, 0.50f, 0.988f)
        curve(0.15f, 0.988f, 0.055f, 0.955f, 0.05f, 0.88f)
        curve(0.045f, 0.74f, 0.04f, 0.50f, 0.04f, 0.26f)
        curve(0.04f, 0.18f, 0.045f, 0.12f, 0.07f, 0.085f)
        curve(0.11f, 0.025f, 0.28f, 0.012f, 0.50f, 0.012f)
        close()
    }
    val mirrors = listOf(box(0.005f, 0.20f, 0.05f, 0.232f, 0.012f), box(0.95f, 0.20f, 0.995f, 0.232f, 0.012f))
    val edge = Stroke(0.004f * w)

    val windshield = shape {
        move(0.22f, 0.10f)
        curve(0.50f, 0.085f, 0.78f, 0.10f)
        line(0.885f, 0.165f)
        curve(0.50f, 0.15f, 0.115f, 0.165f)
        close()
    }
    val frontGlass = Brush.verticalGradient(listOf(CabinColors.Glass, CabinColors.Body), startY = 0.085f * h, endY = 0.165f * h)
    val rearWindow = shape {
        move(0.14f, 0.905f)
        curve(0.50f, 0.915f, 0.86f, 0.905f)
        line(0.78f, 0.958f)
        curve(0.50f, 0.97f, 0.22f, 0.958f)
        close()
    }
    val rearGlass = Brush.verticalGradient(listOf(CabinColors.Body, CabinColors.Glass), startY = 0.905f * h, endY = 0.97f * h)

    /** The cabin inside the body, which the ambient light stays in. */
    val floor = Path().apply {
        val front = CornerRadius(0.05f * w)
        val rear = CornerRadius(0.12f * w)
        addRoundRect(RoundRect(0.085f * w, 0.165f * h, 0.915f * w, 0.905f * h, front, front, rear, rear))
    }
    val dashboard = shape {
        move(0.085f, 0.165f)
        line(0.915f, 0.165f)
        line(0.915f, 0.225f)
        curve(0.50f, 0.268f, 0.085f, 0.225f)
        close()
    }

    /** The ambient strips: along the dashboard's rear edge and down each door. */
    val strips = listOf(
        shape {
            move(0.13f, 0.228f)
            curve(0.50f, 0.266f, 0.87f, 0.228f)
        },
        shape {
            move(0.108f, 0.29f)
            curve(0.100f, 0.56f, 0.108f, 0.84f)
        },
        shape {
            move(0.892f, 0.29f)
            curve(0.900f, 0.56f, 0.892f, 0.84f)
        },
    )

    val core = Stroke(0.014f * w, cap = StrokeCap.Round)

    val tint = Brush.radialGradient(listOf(Color.White, Color.Transparent), at(0.50f, 0.52f), 0.55f * h)

    val vents = listOf(0.19f, 0.41f, 0.59f, 0.81f).map { x -> box(x - 0.0375f, 0.18f, x + 0.0375f, 0.198f, 0.008f) }

    /** Air from the two center vents, three lines each, bending a little toward its seat. */
    val airflows = listOf(0.41f to -1f, 0.59f to 1f).map { (x, toward) ->
        val top = 0.205f
        val length = 0.13f
        at(x, top) to listOf(-0.02f, 0f, 0.02f).map { dx ->
            shape {
                move(x + dx, top)
                curve(x + dx + 0.012f, top + length / 3, x + dx - 0.012f + toward * 0.008f, top + 2 * length / 3, x + dx + toward * 0.015f, top + length)
            }
        }
    }
    val airFade = Brush.verticalGradient(listOf(CabinColors.Air, Color.Transparent), startY = 0.205f * h, endY = 0.335f * h)
    val airStroke = Stroke(0.007f * w, cap = StrokeCap.Round)
    val airRimFade = Brush.verticalGradient(listOf(CabinColors.Dashboard, Color.Transparent), startY = 0.205f * h, endY = 0.335f * h)
    val airRim = Stroke(0.017f * w, cap = StrokeCap.Round)

    val console = box(0.46f, 0.255f, 0.54f, 0.52f, 0.025f)
    val knob = at(0.50f, 0.30f)
    val knobRadius = 0.018f * w

    val wheelCenter = at(0.31f, 0.275f)
    val wheelRadius = 0.07f * w
    val hubRadius = 0.024f * w
    val rim = Stroke(0.014f * w)
    val spokes = listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, 1f)).map { wheelCenter + it * wheelRadius }

    val driver = FrontSeat(0.31f)
    val passenger = FrontSeat(0.69f)

    fun seat(side: Side) = if (side == Side.DRIVER) driver else passenger

    val bench = box(0.16f, 0.625f, 0.84f, 0.735f, 0.045f)
    val benchBack = box(0.155f, 0.738f, 0.845f, 0.80f, 0.03f)
    val headrests = listOf(0.27f, 0.50f, 0.73f).map { x -> box(x - 0.055f, 0.792f, x + 0.055f, 0.82f, 0.015f) }
    val seams = listOf(0.387f, 0.613f).map { x -> at(x, 0.64f) to at(x, 0.72f) }

    val thin = 0.007f * w
    val thick = 0.012f * w

    /** A front seat centered at [x], with its reading light's cone from the roof's front edge. */
    inner class FrontSeat(x: Float) {
        val center = at(x, 0.39f)
        val cushion = box(x - 0.12f, 0.335f, x + 0.12f, 0.445f, 0.045f)
        val back = box(x - 0.125f, 0.448f, x + 0.125f, 0.505f, 0.03f)
        val headrest = box(x - 0.06f, 0.498f, x + 0.06f, 0.528f, 0.015f)
        val bolsters = listOf(-0.078f, 0.078f).map { dx -> at(x + dx, 0.352f) to at(x + dx, 0.428f) }
        val labelTop = 0.545f * h

        val waves = listOf(0.372f, 0.398f, 0.424f).map { y ->
            shape {
                move(x - 0.07f, y)
                var from = x - 0.07f
                repeat(4) { i ->
                    val crest = if (i % 2 == 0) -0.008f else 0.008f
                    curve(from + 0.0175f, y + crest, from + 0.035f, y)
                    from += 0.035f
                }
            }
        }
        val waveStroke = Stroke(0.008f * w, cap = StrokeCap.Round)
        val waveRim = Stroke(0.018f * w, cap = StrokeCap.Round)

        val lamp = at(x, 0.165f)
        val lampRadius = 0.022f * w
        val lampRim = Stroke(0.005f * w)
        val lampGlowRadius = 3 * lampRadius
        val lampGlow = Brush.radialGradient(listOf(CabinColors.Warm.copy(alpha = 0.8f), Color.Transparent), lamp, lampGlowRadius)
        val pool = center
        val poolRadius = 0.135f * w
        val poolLight = Brush.radialGradient(listOf(CabinColors.Warm.copy(alpha = 0.45f), Color.Transparent), pool, poolRadius)

        /** From a narrow start at the lamp out to the far half of the pool on the seat. */
        val cone = Path().apply {
            val start = 0.014f * w
            val depth = poolRadius * POOL_SQUASH
            moveTo(lamp.x - start, lamp.y)
            lineTo(pool.x - poolRadius, pool.y)
            arcTo(Rect(pool.x - poolRadius, pool.y - depth, pool.x + poolRadius, pool.y + depth), 180f, -180f, false)
            lineTo(lamp.x + start, lamp.y)
            close()
        }
        val beam = Brush.verticalGradient(
            listOf(CabinColors.Warm.copy(alpha = 0.5f), CabinColors.Warm.copy(alpha = 0.1f)),
            startY = lamp.y,
            endY = pool.y + poolRadius * POOL_SQUASH,
        )
    }
}

/** Width over height of a car seen from above. */
private const val ASPECT = 0.62f

/** docs/10 5장 "움직임": a state change fades in 0.2 s. */
private const val STEP_MS = 200

/** Ventilation's waves: one per step, up to the highest the stage sends (DuelAnnouncement.WINNER_VENTILATION). */
private const val WAVES = 3

/** The fan's air reaches its furthest at this many steps up. */
private const val MAX_FAN = 4
private const val COOL_TINT = 0.05f

/** The pool of a reading light, a circle squashed this much so it reads as lying on the cushion. */
private const val POOL_SQUASH = 0.6f

/**
 * The ambient light's tint over the whole cabin at full glow, under the halos. With [HALOS], it
 * lights the floor under the seat names and the dashboard under the lamps by about white 0.15 at
 * most, which StageColorsTest assumes.
 */
private const val TINT = 0.08f

/** The strips' halo at full glow: each layer's stroke width and blur radius (fractions of the width) and alpha, widest first. */
private val HALOS = listOf(Triple(0.07f, 0.10f, 0.9f), Triple(0.025f, 0.03f, 0.8f))

/** How far a strip's core whitens at full glow. */
private const val HOT_CORE = 0.4f
