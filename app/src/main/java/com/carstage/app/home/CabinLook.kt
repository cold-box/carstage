package com.carstage.app.home

import com.carstage.app.stage.CabinState
import com.carstage.app.stage.Side

/**
 * What the cabin simulator draws for a [CabinState], as plain values so it can be unit-tested.
 * Unknown values are drawn as off.
 */
data class CabinLook(
    /** The ambient light as opaque ARGB, or null when it is off. */
    val ambientArgb: Int?,
    /** How strongly the ambient light glows, 0..1. */
    val ambientGlow: Float,
    val readingLights: Map<Side, Boolean>,
    val ventilation: Map<Side, Int>,
    /** Each side's temperature setting as an offset in °C from the stage's start (0 for as it was). */
    val temperature: Map<Side, Float>,
    /** The fan as steps from the stage's start (0 for as it was). */
    val fan: Int,
    /** One Korean line per part of the cabin, read out by a screen reader for the status pill under the drawing ([CabinStatus]). */
    val captions: List<String>,
) {
    /** The ambient light's brightness, 0..1, as [ambientGlow] carries it; 0 while the light is off. */
    val brightness: Float
        get() = if (ambientArgb == null) 0f else ((ambientGlow - MIN_GLOW) / (1 - MIN_GLOW)).coerceIn(0f, 1f)

    /**
     * What the status pill ([CabinStatus]) attaches beside the ambient light, in its order: each
     * while the stage changes it (a reading light on, a seat ventilated, a temperature or the fan
     * moved from the start). The ambient light is always in the pill, so it is not listed.
     */
    val badges: List<Badge>
        get() = buildList {
            if (readingLights.values.any { it }) add(Badge.READING_LIGHTS)
            if (ventilation.values.any { it > 0 }) add(Badge.VENTILATION)
            if (Side.entries.any { temperatureChange(it) != Change.NONE }) add(Badge.TEMPERATURE)
            if (fan != 0) add(Badge.FAN)
        }

    /** One icon the status pill attaches while what it shows is changed (see [badges]). */
    enum class Badge { READING_LIGHTS, VENTILATION, TEMPERATURE, FAN }

    /** The seats whose ventilation is on, driver first, for the pill's seat dots. */
    val ventilatedSides: List<Side>
        get() = Side.entries.filter { ventilation.getValue(it) > 0 }

    /**
     * The way the pill's one thermometer points, and the seats it marks: down when any seat was
     * cooled (the duel's crown cools the winner), else up when any was warmed; NONE with no seats
     * while neither moved. A seat moved the other way is left to the screen reader's sentence.
     */
    val temperatureBadge: Pair<Change, List<Side>>
        get() {
            val way = Side.entries.map(::temperatureChange).let { changes ->
                when {
                    Change.DOWN in changes -> Change.DOWN
                    Change.UP in changes -> Change.UP
                    else -> Change.NONE
                }
            }
            return way to Side.entries.filter { way != Change.NONE && temperatureChange(it) == way }
        }

    /** Which way the stage moved a seat's temperature from the start, for its name's color. */
    enum class Change { NONE, DOWN, UP }

    fun temperatureChange(side: Side): Change {
        val offset = temperature.getValue(side)
        return when {
            offset < 0f -> Change.DOWN
            offset > 0f -> Change.UP
            else -> Change.NONE
        }
    }

    /** A seat's name under the drawing, with its temperature when the stage changed it ("조수석 -2°"); see [temperatureChange]. */
    fun seatLabel(side: Side): String {
        val offset = temperature.getValue(side)
        if (offset == 0f) return side.label
        val degrees = if (offset % 1f == 0f) "%+.0f".format(offset) else "%+.1f".format(offset)
        return "${side.label} $degrees°"
    }

    companion object {
        /** The dimmest glow that is still visible, so 0 % reads as "on but dark" rather than off. */
        const val MIN_GLOW = 0.15f

        fun of(state: CabinState): CabinLook {
            val on = state.ambientOn == true
            val brightness = state.ambientBrightness?.value ?: 0
            val color = state.ambientColor
            val readingLights = Side.entries.associateWith { state.readingLight(it) == true }
            val ventilation = Side.entries.associateWith { state.seatVentilation(it) ?: 0 }
            val temperature = Side.entries.associateWith { state.temperatureOffset(it) ?: 0f }
            val fan = state.fanSteps ?: 0
            return CabinLook(
                ambientArgb = if (on) color?.toArgb() else null,
                ambientGlow = if (on && color != null) MIN_GLOW + (1 - MIN_GLOW) * brightness / 100f else 0f,
                readingLights = readingLights,
                ventilation = ventilation,
                temperature = temperature,
                fan = fan,
                captions = listOf(
                    if (on) "앰비언트 켜짐 · 밝기 $brightness%" else "앰비언트 꺼짐",
                    "독서등 " + Side.entries.joinToString(" · ") { "${it.label} ${if (readingLights.getValue(it)) "켜짐" else "꺼짐"}" },
                    "시트 통풍 " + Side.entries.joinToString(" · ") { "${it.label} ${ventilation.getValue(it)}단" },
                    "온도 " + Side.entries.joinToString(" · ") { "${it.label} ${describe(temperature.getValue(it))}" },
                    "풍량 " + when {
                        fan > 0 -> "${fan}단 올림"
                        fan < 0 -> "${-fan}단 내림"
                        else -> "그대로"
                    },
                ),
            )
        }

        /** An offset as the screen says it: as it was, or so many degrees down or up. */
        private fun describe(offset: Float): String = when {
            offset < 0f -> "%.1f도 낮춤".format(-offset)
            offset > 0f -> "%.1f도 높임".format(offset)
            else -> "그대로"
        }

        val Side.label: String
            get() = when (this) {
                Side.DRIVER -> "운전석"
                Side.PASSENGER -> "조수석"
            }
    }
}
