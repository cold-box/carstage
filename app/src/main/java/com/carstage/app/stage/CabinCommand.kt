package com.carstage.app.stage

/** One change to the cabin. */
sealed interface CabinCommand {
    val target: CabinTarget

    data class AmbientSwitch(val on: Boolean) : CabinCommand {
        override val target get() = CabinTarget.AmbientSwitch
    }

    data class AmbientColor(val color: Rgb) : CabinCommand {
        override val target get() = CabinTarget.AmbientColor
    }

    /** Each output maps the percentage to the brightness range the car supports. */
    data class AmbientBrightness(val level: Percent) : CabinCommand {
        override val target get() = CabinTarget.AmbientBrightness
    }

    data class ReadingLight(val side: Side, val on: Boolean) : CabinCommand {
        override val target get() = CabinTarget.ReadingLight(side)
    }

    /**
     * The fan as [steps] of the car's own above (or below) what it had before the change (0 puts
     * it back), so a director need not know the car's level, as for [Temperature]; outputs clamp
     * the result to the range the car supports. A director that stops asking gets it put back
     * ([LayeredDirector.startOf]).
     */
    data class FanStep(val steps: Int) : CabinCommand {
        override val target get() = CabinTarget.Fan
    }

    /** Ventilation level in the car's own steps (0 is off); outputs clamp it to the supported range. */
    data class SeatVentilation(val side: Side, val level: Int) : CabinCommand {
        init {
            require(level >= 0) { "ventilation level $level is negative" }
        }

        override val target get() = CabinTarget.SeatVentilation(side)
    }

    /**
     * A side's temperature setting as an [offset] in °C from what it had before the change (0 puts
     * it back), so a director need not know the car's value; outputs clamp the result to the
     * range the car supports.
     */
    data class Temperature(val side: Side, val offset: Float) : CabinCommand {
        init {
            require(offset.isFinite()) { "temperature offset $offset is not a number of degrees" }
        }

        override val target get() = CabinTarget.Temperature(side)
    }
}
