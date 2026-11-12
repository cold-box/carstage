package com.carstage.app.stage

import com.carstage.app.score.Grade
import com.carstage.app.score.ScoreKeeper
import com.carstage.app.score.SongResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * The stage effect: the light is in the voices' colors and follows them while someone sings, and in
 * the music's colors while nobody does, the voices winning, and a cheer swells it to full
 * ([ParticipationLayer], [VoiceMode]); the song's mood makes it breathe underneath, in time with
 * its beat ([MoodLayer]), a cheer flashes both reading lights ([CheerLayer]), and the end of a
 * song plays the finale in its grade's color ([FinaleLayer]), merged per target by
 * [LayeredDirector], which sends only what changed and sends again what the safety or battery
 * rules held back.
 *
 * It also keeps each song's 흥 점수 ([ScoreKeeper]) and hands it to [onResult], on the audio
 * thread, when a song of at least 10 s ends; a shorter one has no score and no finale. Create one
 * per stage; the layers and the score carry on from one song to the next.
 *
 * A duel ([requestDuel]) runs on the same songs: each turn's light takes the singer's color
 * ([DuelTurnLayer]), each turn's song is scored for the duel instead of [onResult], the driver's
 * turn ends with its finale, and the passenger's with the announcement ([DuelAnnouncement]), which
 * overrides the other layers. A turn needs a song long enough to score; a shorter one keeps the
 * turn. [onDuel] hears every step, and the announcement's crown, on the audio thread.
 *
 * [onSongEnded] hears every end of a song, scored or not, on the audio thread, after a song long
 * enough to score was scored and its finale (or a duel's announcement) started; for whoever waits
 * for the song being sung to end.
 *
 * [onSound] hears which sound to start with the light ([SoundCue]), on the audio thread: the
 * finale's applause for its grade, and the drumroll and fanfare of a duel's announcement. [onSoundStop] hears
 * that the sound playing no longer fits, as when a duel starts over during its announcement.
 * [onSpeak] hears what the MC says ([DuelLines]): the announcement's opening, and its winner with
 * the score (or a tie) with the crown.
 * [onSoundChange] hears how to change the sound playing ([SoundChange]): the announcement's
 * drumroll takes turns between the sides with the reading lights, faster as they do, and its
 * fanfare comes from the winner's side.
 *
 * While it is [night] (read at every event), the ambient brightness is scaled into
 * 0..[NIGHT_CEILING]% ([LayeredDirector.dimmed]), the announcement's too; the colours and the
 * reading lights are as by day (docs/07 "야간 밝기 배려").
 */
class GlowDirector(
    private val onResult: (SongResult) -> Unit = {},
    private val onDuel: (DuelState) -> Unit = {},
    private val onSongEnded: () -> Unit = {},
    private val onSound: (SoundCue) -> Unit = {},
    private val onSoundStop: () -> Unit = {},
    private val onSoundChange: (SoundChange) -> Unit = {},
    private val onSpeak: (String) -> Unit = {},
    private val night: () -> Boolean = { false },
) : Director {
    private val score = ScoreKeeper(FRAMES_PER_SECOND)
    private val finale = FinaleLayer()
    private var duel: DuelState? = null
    private val announcement = DuelAnnouncement()
    private val layers = LayeredDirector(
        listOf(finale, DuelTurnLayer { duel }, ParticipationLayer(), MoodLayer(), CheerLayer()),
        exclusive = announcement,
        ceiling = { Percent(if (night()) NIGHT_CEILING else 100) },
    )

    private class Request(val waitForSong: Boolean, val started: CompletableDeferred<DuelState>)

    /** Set by [requestDuel]; the audio thread starts the duel before its next event. */
    private val requested = AtomicReference<Request?>(null)

    /**
     * Starts a duel, the driver first, from any thread; the audio thread starts it before the
     * next event, and the result completes with the state it started in. With [waitForSong], a
     * song heard long enough to score is sung to its end and scored on its own first
     * ([DuelState.Step.WAITING]); otherwise the song so far is forgotten and the driver's turn
     * starts at once. A turn already running starts over at once, and a running announcement
     * stops. A request replaced by another before the next event never completes.
     */
    fun requestDuel(waitForSong: Boolean): Deferred<DuelState> {
        val request = Request(waitForSong, CompletableDeferred())
        requested.set(request)
        return request.started
    }

    /** Set by [restartSong]; the audio thread forgets the song so far before its next event. */
    private val restartAsked = AtomicBoolean(false)

    /**
     * Forgets the song heard so far, from any thread, as a duel's next singer starts the song they
     * picked: the time spent picking is not their song (docs/13).
     */
    fun restartSong() {
        restartAsked.set(true)
    }

    override fun direct(event: AudioEvent): List<CabinCommand> {
        if (restartAsked.getAndSet(false)) score.restart()
        requested.getAndSet(null)?.let {
            startDuel(it.waitForSong)
            duel?.let(it.started::complete)
        }
        val running = duel
        score.hear(event)?.let { result ->
            if (running?.step == DuelState.Step.TURN) {
                val next = running.scored(result)
                step(next)
                if (next.step == DuelState.Step.ANNOUNCING) announcement.start(next.winner) else startFinale(result.grade)
            } else {
                startFinale(result.grade)
                onResult(result)
            }
        }
        if (event == AudioEvent.SongEnded) {
            if (running?.step == DuelState.Step.WAITING) step(running.begun())
            onSongEnded()
        }
        val commands = layers.direct(event)
        announcement.takeCue()?.let(onSound)
        announcement.takeChange()?.let(onSoundChange)
        announcement.takeLine()?.let { line ->
            when (line) {
                DuelAnnouncement.Line.OPENING -> onSpeak(DuelLines.OPENING)
                DuelAnnouncement.Line.CROWN -> duel?.let { onSpeak(DuelLines.crown(it.winner, it.winner?.let { side -> it.scores[side]?.score } ?: 0)) }
            }
        }
        duel?.let {
            if (it.step != DuelState.Step.ANNOUNCING) return@let
            if (announcement.finished) step(it.announced()) else if (!it.winnerShown && announcement.crowned) step(it.crowned())
        }
        return commands
    }

    private fun startFinale(grade: Grade) {
        finale.start(grade)
        onSound(SoundCue.finaleOf(grade))
    }

    private fun startDuel(waitForSong: Boolean) {
        if (announcement.stop()) onSoundStop()
        if (waitForSong && duel?.step != DuelState.Step.TURN && score.scorable) {
            step(DuelState.waiting())
        } else {
            score.restart()
            step(DuelState.start())
        }
    }

    private fun step(next: DuelState) {
        duel = next
        onDuel(next)
    }

    override fun dropped(command: CabinCommand) = layers.dropped(command)

    companion object {
        /**
         * Audio frames a second at 48 kHz and 2,048 samples ([AudioEvent.Frame]), which time the
         * layers' movements. The microphone delivers 48 kHz; a WAV replayed at another rate moves
         * the light proportionally slower or faster.
         */
        const val FRAMES_PER_SECOND = 48_000f / 2_048

        const val MIN_BRIGHTNESS = 20
        const val ATTACK = 0.5f
        const val RELEASE = 0.08f
        const val MIN_BRIGHTNESS_STEP = 3

        /**
         * The brightest the light goes at night, in percent: every ambient brightness is multiplied
         * by it / 100, not cut off at it, so the light still moves. A guess to check in a dark car.
         */
        const val NIGHT_CEILING = 40

        const val MIN_HUE_STEP = 6f

        /** About a fifth of a second. */
        const val FLASH_FRAMES = 5

        /**
         * [value] if it moved enough to see from [shown] (or [shown] is unset), or reached an end of
         * the voices' range ([MIN_BRIGHTNESS] or 100); else [shown].
         */
        fun stepped(shown: Int, value: Int): Int {
            val atEnd = value == MIN_BRIGHTNESS || value == 100
            return if (shown < 0 || abs(value - shown) >= MIN_BRIGHTNESS_STEP || (atEnd && value != shown)) value else shown
        }

        /** A full-value color for [hue] in degrees, fully saturated unless [saturation] (0..1) says less. */
        fun hsv(hue: Float, saturation: Float = 1f): Rgb {
            val h = ((hue % 360f) + 360f) % 360f / 60f
            val chroma = if (saturation.isNaN()) 1f else saturation.coerceIn(0f, 1f)
            val x = chroma * (1f - abs(h % 2f - 1f))
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(chroma, x, 0f)
                1 -> Triple(x, chroma, 0f)
                2 -> Triple(0f, chroma, x)
                3 -> Triple(0f, x, chroma)
                4 -> Triple(x, 0f, chroma)
                else -> Triple(chroma, 0f, x)
            }
            fun byte(v: Float) = ((v + 1f - chroma) * 255).roundToInt().coerceIn(0, 255)
            return Rgb((byte(r) shl 16) or (byte(g) shl 8) or byte(b))
        }
    }
}
