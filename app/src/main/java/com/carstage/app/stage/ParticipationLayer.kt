package com.carstage.app.stage

import com.carstage.app.stage.AudioEvent.Mood.Feel
import com.carstage.app.stage.GlowDirector.Companion.ATTACK
import com.carstage.app.stage.GlowDirector.Companion.MIN_BRIGHTNESS
import com.carstage.app.stage.GlowDirector.Companion.MIN_HUE_STEP
import com.carstage.app.stage.GlowDirector.Companion.RELEASE
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The ambient light in the stage's two modes ([VoiceMode], docs/09 section 5 "목소리 모드와 노래 모드"):
 * - the voice mode, someone singing over the music: the light takes the voices' colors, magenta to
 *   pink ([voicePaletteOf]), brighter as the voices stand out of the music (rising quickly and
 *   falling slowly, so it breathes instead of flickering), and a sung note moves the color back
 *   and forth within them, wider and faster for a livelier song;
 * - the music mode, nobody singing: the light takes the music's colors, teal to cyan
 *   ([musicPaletteOf]), deeper teal in the quiet parts and towards cyan in the loud ones
 *   ([AudioEvent.Mood.energy]); its brightness is left to the breath of [MoodLayer], at the floor here.
 *
 * Voices win: with music and voices together it is the voice mode. A cheer swells the light to full
 * at once in either mode, and it falls back at the release rate (about 2 s).
 *
 * The color never jumps: it moves to where it should be the short way round, at most [GLIDE] degrees
 * a frame (a change of mode in about 1.5 s, as the screen's glow follows in about a second), and its
 * saturation glides as well.
 */
class ParticipationLayer : EffectLayer {
    private val mode = VoiceMode()
    private var level = 0f
    private var shownBrightness = MIN_BRIGHTNESS

    private var feel = Feel.MODERATE

    /** The middle of the music's range until the first mood, so the light starts in the middle of its colors. */
    private var energy = 0.5f

    /** Degrees from the voice palette's center, back and forth within its half width as notes are sung. */
    private var offset = 0f
    private var direction = 1f

    private var hue = MUSIC_HUE
    private var saturation = musicPaletteOf(feel).saturation
    private var shownHue = hue
    private var shownSaturation = saturation

    /** Whether the light is in the voice mode now. */
    val singing: Boolean get() = mode.voice

    override fun hear(event: AudioEvent) {
        when (event) {
            AudioEvent.Cheer -> {
                level = 1f
                shownBrightness = 100
            }
            is AudioEvent.Mood -> {
                feel = event.feel
                energy = event.level
            }
            is AudioEvent.Frame -> {
                val voice = mode.hear(event)
                val target = if (voice) event.level else 0f
                level += (target - level) * if (target > level) ATTACK else RELEASE
                shownBrightness = GlowDirector.stepped(shownBrightness, (MIN_BRIGHTNESS + (100 - MIN_BRIGHTNESS) * level).roundToInt())
                moveColor(voice, sung = event.voiced)
            }
            AudioEvent.SongEnded -> Unit
        }
    }

    override fun wants(): List<CabinCommand> = listOf(
        CabinCommand.AmbientSwitch(true),
        CabinCommand.AmbientBrightness(Percent.coerced(shownBrightness)),
        CabinCommand.AmbientColor(GlowDirector.hsv(shownHue, shownSaturation)),
    )

    private fun moveColor(voice: Boolean, sung: Boolean) {
        val palette = if (voice) voicePaletteOf(feel) else musicPaletteOf(feel)
        val target = if (voice) {
            if (sung) {
                offset += direction * palette.degreesPerSungFrame
                if (offset >= palette.halfWidth) direction = -1f
                if (offset <= -palette.halfWidth) direction = 1f
            }
            offset = offset.coerceIn(-palette.halfWidth, palette.halfWidth)
            palette.center + offset
        } else {
            // The voices start again from the middle of their colors.
            offset = 0f
            palette.center + palette.halfWidth * (2 * energy - 1)
        }
        val gap = signedHueDistance(hue, target)
        hue = ((hue + sign(gap) * minOf(abs(gap), GLIDE)) % 360f + 360f) % 360f
        saturation += (palette.saturation - saturation).coerceIn(-SATURATION_GLIDE, SATURATION_GLIDE)
        // A softening that arrives in steps smaller than one to see still ends where it should.
        val saturationArrived = abs(saturation - palette.saturation) < 1e-3f && abs(saturation - shownSaturation) >= 1e-3f
        // So does a glide to the music's colors; the voices' color moves on with every sung note, so
        // there the steps alone decide.
        val hueArrived = !voice && abs(signedHueDistance(hue, target)) < 1e-3f && abs(signedHueDistance(shownHue, hue)) >= 1e-3f
        if (abs(signedHueDistance(shownHue, hue)) >= MIN_HUE_STEP || abs(saturation - shownSaturation) >= MIN_SATURATION_STEP ||
            saturationArrived || hueArrived
        ) {
            shownHue = hue
            shownSaturation = saturation
        }
    }

    /** The colors of one mode for one feel of the song: [halfWidth] degrees either side of [center]. */
    data class Palette(val center: Float, val halfWidth: Float, val saturation: Float, val degreesPerSungFrame: Float = 0f) {
        val lowest: Float get() = center - halfWidth
        val highest: Float get() = center + halfWidth
    }

    companion object {
        /**
         * The voices' colors, magenta to pink (300 to 336 degrees): the stage's own colors, a long
         * way round from the music's teal, and clear of the duel's sides (the driver's blue at about
         * 225 degrees, the passenger's red at 0) and of the finale's violet (270) and gold. Calm
         * songs sing in a softer, narrower band, slowly; lively ones across all of it, faster (about
         * 14, 23 and 35 degrees a second of singing).
         */
        fun voicePaletteOf(feel: Feel): Palette = when (feel) {
            Feel.CALM -> Palette(center = VOICE_HUE, halfWidth = 8f, saturation = 0.8f, degreesPerSungFrame = 0.6f)
            Feel.MODERATE -> Palette(center = VOICE_HUE, halfWidth = 13f, saturation = 1f, degreesPerSungFrame = 1f)
            Feel.LIVELY -> Palette(center = VOICE_HUE, halfWidth = 18f, saturation = 1f, degreesPerSungFrame = 1.5f)
        }

        /**
         * The music's colors, teal to cyan (160 to 198 degrees): greener and softer for a calm song,
         * nearer cyan for a lively one, and short of the driver's blue.
         */
        fun musicPaletteOf(feel: Feel): Palette = when (feel) {
            Feel.CALM -> Palette(center = 170f, halfWidth = 10f, saturation = 0.8f)
            Feel.MODERATE -> Palette(center = MUSIC_HUE, halfWidth = 10f, saturation = 0.9f)
            Feel.LIVELY -> Palette(center = 188f, halfWidth = 10f, saturation = 1f)
        }

        /** Magenta-pink, the middle of the voices' colors. */
        const val VOICE_HUE = 318f

        /** Teal-cyan, the middle of the music's colors for a moderate song, where the light starts. */
        const val MUSIC_HUE = 180f

        /** Degrees a frame the color moves at most: from the music's colors to the voices' in about 1.5 s. */
        const val GLIDE = 4f
        const val SATURATION_GLIDE = 0.02f
        const val MIN_SATURATION_STEP = 0.05f

        /** From [from] to [to] the short way round, in -180..180 degrees. */
        fun signedHueDistance(from: Float, to: Float): Float = ((to - from) % 360f + 540f) % 360f - 180f
    }
}
