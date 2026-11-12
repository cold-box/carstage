package com.carstage.app

import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.GlowDirector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

/**
 * The first screen's voice and song meters while the stage is open (docs/10 4장 "노래 중"): how far
 * the voices stand out of the music ([AudioEvent.Frame.level]) and how loud the music is
 * ([AudioEvent.Frame.musicLevel]), each smoothed as the light follows the voices
 * (quick to rise, slow to fall, [GlowDirector.ATTACK] and [GlowDirector.RELEASE]), and a count of
 * the cheers it heard, for a flash. Hears every event on the capture thread ([hear]); [level]
 * moves only by steps of [STEP], so a voice holding steady leaves the meter still (while it moves,
 * it can still change on most frames).
 */
class VoiceMeter {
    private val _level = MutableStateFlow(0f)

    /** 0..1: none to the voices well above the music. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _music = MutableStateFlow(0f)

    /** 0..1: no music to the car's music played loud ([AudioEvent.Frame.musicLevel]), smoothed the same way. */
    val music: StateFlow<Float> = _music.asStateFlow()

    private val _cheers = MutableStateFlow(0)

    /** How many cheers it heard since the app started; each new one flashes the meter. */
    val cheers: StateFlow<Int> = _cheers.asStateFlow()

    /** Touched on the capture thread, and by [reset] before that thread starts. */
    private var smoothed = 0f
    private var smoothedMusic = 0f

    fun hear(event: AudioEvent) {
        when (event) {
            is AudioEvent.Frame -> {
                smoothed = follow(smoothed, event.level, _level)
                smoothedMusic = follow(smoothedMusic, event.musicLevel, _music)
            }
            AudioEvent.Cheer -> _cheers.update { it + 1 }
            else -> Unit
        }
    }

    /** Moves [smoothed] toward [target] as the light does and shows it in [shown] by steps of [STEP]; returns the new value. */
    private fun follow(smoothed: Float, target: Float, shown: MutableStateFlow<Float>): Float {
        val next = smoothed + (target - smoothed) * if (target > smoothed) GlowDirector.ATTACK else GlowDirector.RELEASE
        val drawn = shown.value
        if (abs(next - drawn) >= STEP || (next < STEP && drawn != 0f)) {
            shown.value = if (next < STEP) 0f else next
        }
        return next
    }

    /** Empties the meter before the microphone starts, so a new stage does not start with the last one's level. */
    fun reset() {
        smoothed = 0f
        smoothedMusic = 0f
        _level.value = 0f
        _music.value = 0f
    }

    companion object {
        /**
         * The least change worth drawing: 8 dp of the meter (StageSizes.ButtonWidth, 400 dp), so
         * small wobbles do not redraw it (docs/05 "상태별 화면 확인").
         */
        const val STEP = 0.02f
    }
}
