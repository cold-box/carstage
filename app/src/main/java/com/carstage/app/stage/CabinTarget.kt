package com.carstage.app.stage

/** Front seats. Pleos cars sold in Korea are left-hand drive, so the driver sits on ROW_1_LEFT. */
enum class Side { DRIVER, PASSENGER }

/**
 * A cabin setting the stage can change. Commands for the same target replace each other when
 * queued, and restore works per target. Code that decides per target (restore, the safety gate)
 * should use an exhaustive `when` so a new target forces a decision.
 */
sealed interface CabinTarget {
    data object AmbientSwitch : CabinTarget
    data object AmbientColor : CabinTarget
    data object AmbientBrightness : CabinTarget
    data class ReadingLight(val side: Side) : CabinTarget
    data object Fan : CabinTarget
    data class SeatVentilation(val side: Side) : CabinTarget
    data class Temperature(val side: Side) : CabinTarget
}

/** A percentage in 0..100. */
@JvmInline
value class Percent(val value: Int) {
    init {
        require(value in 0..100) { "percent $value out of 0..100" }
    }

    companion object {
        fun coerced(value: Int) = Percent(value.coerceIn(0, 100))

        /** Maps a 0..1 fraction (for example a normalized microphone level) without ever throwing. */
        fun ofFraction(fraction: Float) = coerced(Math.round(fraction * 100))
    }
}

/** An opaque 0xRRGGBB color. */
@JvmInline
value class Rgb(val value: Int) {
    init {
        require(value in 0..0xFFFFFF) { "color 0x%X is not 0xRRGGBB".format(value) }
    }

    /** The color as opaque ARGB, as Compose and android.graphics.Color expect. */
    fun toArgb(): Int = value or OPAQUE

    companion object {
        private const val OPAQUE = 0xFF shl 24

        /** Drops the alpha byte of an ARGB color. */
        fun fromArgb(argb: Int) = Rgb(argb and 0xFFFFFF)
    }
}
