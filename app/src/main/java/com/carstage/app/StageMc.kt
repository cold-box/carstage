package com.carstage.app

import android.os.SystemClock
import com.carstage.app.score.SongResult
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side
import com.carstage.app.stage.StageController.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The MC that runs the stage out loud (docs/12 "차를 아는 MC"): greets a stage as it opens with
 * what the car tells of the moment ([McLines.opening]), says each song's score once its finale's
 * sound is over ([McLines.scored]), calls a duel's turns ([McLines.turn]) and asks to end with a
 * last song when charging stopped ([McLines.CHARGE_DONE]). The duel's announcement has its own
 * lines ([com.carstage.app.stage.DuelLines]), so the MC keeps quiet through it.
 *
 * Lines go out one at a time, in the order they came: each waits until its time, then while the
 * sound effects or the voice itself play ([soundPlaying], for at most [SOUND_WAIT_MS]), and while
 * Gleo gives an answer ([gleoSpoke], [gleoBeeped]). A greeting or the duel's first turn that Gleo has just
 * read out itself is left out; after an answer Gleo only beeped, the MC says them. Right before it
 * is said, a line must still be true: its stage still open and not closed since the line was
 * asked, its score still the one on the screen, its turn still the turn, the question still asked.
 * While the user has turned the MC off ([on], [McSetting]), no line is asked for, and one asked
 * before is not said if the MC is still off when its turn comes. The voice is [speak] ([com.carstage.app.audio.Announcer]).
 */
class StageMc(
    private val scope: CoroutineScope,
    private val phase: StateFlow<Phase>,
    private val result: StateFlow<SongResult?>,
    private val duel: StateFlow<DuelState?>,
    private val chargeStep: StateFlow<ChargeFinish.Step>,
    private val moment: () -> McLines.Moment,
    private val soundPlaying: () -> Boolean,
    private val on: () -> Boolean,
    private val speak: (String) -> Unit,
    private val log: (String) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val random: Random = Random.Default,
) {
    /** The MC's lines that an answer Gleo reads out tells too, so the MC does not say them again. */
    enum class Said {
        /** Gleo said it opened the stage, or started a duel on it. */
        GREETING,

        /** Gleo said who sings first in a duel starting now. */
        DRIVER_TURN,
    }

    private class Line(val due: Long, val saidBy: Said?, val closes: Int, val still: () -> Boolean, val text: () -> String)

    private val lines = Channel<Line>(Channel.UNLIMITED)
    private val pending = AtomicInteger()

    /** Until when Gleo gives its answer; lines wait for it. */
    @Volatile
    private var gleoUntil = 0L

    @Volatile
    private var gleoSaid: Set<Said> = emptySet()

    /** Counts the stages closed, so a line asked on one stage is not said on the next. */
    @Volatile
    private var closes = 0

    /** Whether a line waits to be said or the voice is speaking; the stage after the last song waits for it. */
    val busy: Boolean get() = pending.get() > 0 || soundPlaying()

    /** Gleo just read an answer out: the MC gives it [GLEO_QUIET_MS] and leaves out the lines it [said] meanwhile. */
    fun gleoSpoke(said: Set<Said>) {
        gleoSaid = said
        gleoUntil = now() + GLEO_QUIET_MS
    }

    /** Gleo only beeped, leaving the answer to the MC: the MC gives the beep [BEEP_QUIET_MS] and says its lines. */
    fun gleoBeeped() {
        gleoSaid = emptySet()
        gleoUntil = now() + BEEP_QUIET_MS
    }

    init {
        scope.launch { for (line in lines) say(line) }
        scope.launch {
            phase.map { it == Phase.OPEN }.distinctUntilChanged().collect { open ->
                if (open) ask(OPENING_DELAY_MS, Said.GREETING) { McLines.opening(moment(), random) } else closes++
            }
        }
        scope.launch {
            // The score shown when the MC starts is old news.
            result.drop(1).filterNotNull().collect { scored ->
                ask(still = { result.value === scored }) { McLines.scored(scored) }
            }
        }
        scope.launch {
            duel.map { state -> state?.takeIf { it.step == DuelState.Step.TURN }?.turn }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { side ->
                    ask(saidBy = if (side == Side.DRIVER) Said.DRIVER_TURN else null, still = { duel.value?.let { it.step == DuelState.Step.TURN && it.turn == side } == true }) {
                        McLines.turn(side)
                    }
                }
        }
        scope.launch {
            chargeStep.drop(1).filter { it == ChargeFinish.Step.ASKING }.collect {
                ask(still = { chargeStep.value == ChargeFinish.Step.ASKING }) { McLines.CHARGE_DONE }
            }
        }
    }

    /** Queues a line due [afterMs] from now (by default as long as a sound effect takes to start). */
    private fun ask(afterMs: Long = SETTLE_MS, saidBy: Said? = null, still: () -> Boolean = { true }, text: () -> String) {
        if (!on()) return log("mc: left out, the MC is off")
        pending.incrementAndGet()
        lines.trySend(Line(now() + afterMs, saidBy, closes, still, text))
    }

    private suspend fun say(line: Line) {
        try {
            delay(line.due - now())
            val until = now() + SOUND_WAIT_MS
            while (soundPlaying() && now() < until) delay(POLL_MS)
            val quiet = gleoUntil - now()
            if (quiet > 0) {
                if (line.saidBy != null && line.saidBy in gleoSaid) return log("mc: left out, Gleo said it")
                delay(quiet)
            }
            if (line.closes != closes) return log("mc: left out, its stage closed")
            if (phase.value != Phase.OPEN || !line.still()) return
            if (!on()) return log("mc: left out, the MC is off")
            if (duel.value?.step == DuelState.Step.ANNOUNCING) return log("mc: left out, a duel is being announced")
            val text = line.text()
            log("mc: \"$text\"")
            speak(text)
            // The voice starts on its own thread; the next line waits for it.
            delay(SETTLE_MS)
        } finally {
            pending.decrementAndGet()
        }
    }

    companion object {
        /**
         * How long a sound effect asked for with a score or a turn takes to start: the score comes
         * a moment before its finale's applause, which the MC must not talk over.
         */
        const val SETTLE_MS = 500L

        /** A moment after the stage opens, once the speech service has started. */
        const val OPENING_DELAY_MS = 1_200L

        /** The longest sound effect (fanfare and applause, about 4.3 s) and the longest line (8 s) with a little more. */
        const val SOUND_WAIT_MS = 10_000L

        /** About as long as Gleo takes to read out an answer. */
        const val GLEO_QUIET_MS = 4_000L

        /** Gleo's beep and a little more (the beep's length is not documented). */
        const val BEEP_QUIET_MS = 1_000L

        private const val POLL_MS = 200L
    }
}
