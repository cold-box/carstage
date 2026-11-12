package com.carstage.app.stage

import com.carstage.app.stage.AudioEvent.Mood.Feel
import com.carstage.app.stage.GlowDirector.Companion.FRAMES_PER_SECOND
import com.carstage.app.stage.GlowDirector.Companion.MIN_BRIGHTNESS
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The song's background: the ambient light breathes, slowly and gently for a calm song and faster
 * and deeper for a lively one ([Breath]), around a level that rises in the louder parts of the
 * song. A breath lasts a whole, even number of the song's beats when its tempo is known, so the
 * light swells with the music's pulse (the phase is not locked to the beat). The voices'
 * brightness is merged over it (the higher wins), so this shows when nobody is singing. No opinion until the first [AudioEvent.Mood], about 6 s after the stage opens; the
 * breath then grows from the voices' floor, and a change of mood changes it smoothly, without a jump.
 */
class MoodLayer : EffectLayer {
    private var heardMood = false
    private var breath = Breath.of(Feel.MODERATE)
    private var center = MIN_BRIGHTNESS.toFloat()
    private var targetCenter = MIN_BRIGHTNESS.toFloat()
    private var depth = 0f

    /** Where in the breath the light is, 0..1, so a new period continues from the same place. */
    private var phase = 0f
    private var shown = -1

    override fun hear(event: AudioEvent) {
        when (event) {
            is AudioEvent.Mood -> {
                targetCenter = LOW_CENTER + (HIGH_CENTER - LOW_CENTER) * event.level
                breath = Breath.of(event.feel, event.beatsPerMinute)
                heardMood = true
            }
            is AudioEvent.Frame -> if (heardMood) breathe()
            AudioEvent.Cheer, AudioEvent.SongEnded -> Unit
        }
    }

    override fun wants(): List<CabinCommand> =
        if (shown < 0) emptyList() else listOf(CabinCommand.AmbientBrightness(Percent.coerced(shown)))

    private fun breathe() {
        center += (targetCenter - center).coerceIn(-CENTER_GLIDE, CENTER_GLIDE)
        depth += (breath.depth - depth).coerceIn(-DEPTH_GLIDE, DEPTH_GLIDE)
        phase = (phase + 1f / (breath.seconds * FRAMES_PER_SECOND)) % 1f
        shown = GlowDirector.stepped(shown, (center + depth * sin(2 * PI * phase).toFloat()).roundToInt())
    }

    /** One breath of the light: [seconds] long, [depth] percentage points either side of the level. */
    private data class Breath(val seconds: Float, val depth: Float) {
        companion object {
            /**
             * At most one breath every 4 s, far from the three flashes a second that can trigger
             * seizures: the feel's length, stretched to the next even number of beats at
             * [beatsPerMinute] (at 120 BPM a lively song breathes every 8 beats, a calm one every
             * 16). A tempo outside [SLOWEST_BPM]..[FASTEST_BPM] or NaN keeps the feel's length.
             */
            fun of(feel: Feel, beatsPerMinute: Float = Float.NaN): Breath {
                val (seconds, depth) = when (feel) {
                    Feel.CALM -> 8f to 6f
                    Feel.MODERATE -> 6f to 9f
                    Feel.LIVELY -> 4f to 12f
                }
                if (!(beatsPerMinute in SLOWEST_BPM..FASTEST_BPM)) return Breath(seconds, depth)
                val beat = 60f / beatsPerMinute
                val beats = 2 * ceil(seconds / (2 * beat) - 1e-3f)
                return Breath(maxOf(seconds, beats * beat), depth)
            }
        }
    }

    companion object {
        /**
         * The level the light breathes around, from the quiet to the loud parts of the song, above
         * the voices' floor; the bottom of a deep breath can dip under that floor, where the
         * voices' light shows instead.
         */
        const val LOW_CENTER = 30f
        const val HIGH_CENTER = 45f

        /** Percentage points a frame: a new level is reached within about a second (the first one, from the floor, in about 2 s)... */
        const val CENTER_GLIDE = 0.5f

        /** ...and a deeper or shallower breath within a second (the first breath grows in up to 2 s). */
        const val DEPTH_GLIDE = 0.25f

        /** The tempos the mood reports (MoodTracker); a breath follows only those. */
        const val SLOWEST_BPM = 60f
        const val FASTEST_BPM = 180f
    }
}
