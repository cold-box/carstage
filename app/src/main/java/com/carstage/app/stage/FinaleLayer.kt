package com.carstage.app.stage

import com.carstage.app.score.Grade
import com.carstage.app.stage.GlowDirector.Companion.FRAMES_PER_SECOND
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The finale when a song ends (docs/04 "피날레"): for about 3 s the ambient light takes the grade's
 * color and swells three times, once a second, far from the three flashes a second that can
 * trigger seizures. The brightest wins the merge, so voices or a cheer still loud may fill the dips;
 * only the color is the finale's for sure. Put it first in the layers, so its color wins. Then it
 * has no opinion again and the other layers take the ambient light back.
 *
 * For the best grade both reading lights also flash once and are then wanted off, as [CheerLayer]
 * does, but only once a cheer was heard on this stage: before that a passenger may be reading by
 * one, and the light stays as it is. After a flash they stay off until the stage closes and puts
 * back what was there.
 *
 * Every finale also gives a "cheer wind" (docs/04 "정차 중 연출 순서"): from [WIND_FROM_FRAMES] to
 * [WIND_UNTIL_FRAMES] the fan is [WIND_STEPS] above what the car had, and then the layer has no
 * opinion, so the director brings it back ([LayeredDirector.startOf]). A climate system that is
 * off refuses the fan (the app never turns it on), and the light and the applause carry the
 * finale alone.
 */
class FinaleLayer : EffectLayer {
    private var grade = Grade.BASIC
    private var elapsed = 0
    private var running = false
    private var cheered = false

    /** Starts the finale for [grade]; one already running starts over. */
    fun start(grade: Grade) {
        this.grade = grade
        elapsed = 0
        running = true
    }

    override fun hear(event: AudioEvent) {
        if (event == AudioEvent.Cheer) cheered = true
        if (running && event is AudioEvent.Frame && ++elapsed >= FINALE_FRAMES) running = false
    }

    override fun wants(): List<CabinCommand> {
        if (!running) return emptyList()
        // Full at once, down to half and back, once a second, in steps of a tenth so the car is
        // not written every frame.
        val swell = abs(cos(PI * elapsed / FRAMES_PER_SECOND)).toFloat()
        val level = ((LOWEST + (100 - LOWEST) * swell) / STEP).roundToInt() * STEP
        val light = listOf(
            CabinCommand.AmbientSwitch(true),
            CabinCommand.AmbientColor(colorOf(grade)),
            CabinCommand.AmbientBrightness(Percent.coerced(level)),
        )
        val wind = if (elapsed in WIND_FROM_FRAMES until WIND_UNTIL_FRAMES) listOf(CabinCommand.FanStep(WIND_STEPS)) else emptyList()
        if (grade != Grade.BEST || !cheered) return light + wind
        return light + wind + Side.entries.map { CabinCommand.ReadingLight(it, elapsed < READING_FLASH_FRAMES) }
    }

    companion object {
        /** About 3 s. */
        val FINALE_FRAMES = (3 * FRAMES_PER_SECOND).roundToInt()

        /** About half a second. */
        val READING_FLASH_FRAMES = (FRAMES_PER_SECOND / 2).roundToInt()

        /** The cheer wind: one step up from half a second to two seconds into the finale. */
        const val WIND_STEPS = 1
        val WIND_FROM_FRAMES = (FRAMES_PER_SECOND / 2).roundToInt()
        val WIND_UNTIL_FRAMES = (2 * FRAMES_PER_SECOND).roundToInt()

        const val LOWEST = 50
        const val STEP = 10

        /** Warm white, violet and gold, from the lowest grade to the best (docs/04 "점수 등급"). */
        fun colorOf(grade: Grade): Rgb = when (grade) {
            Grade.BASIC -> Rgb(0xFFF1E0)
            Grade.GOOD -> GlowDirector.hsv(270f)
            Grade.BEST -> Rgb(0xFFC020)
        }
    }
}
