package com.carstage.app

import com.carstage.app.score.SongResult
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.GlowDirector
import com.carstage.app.stage.Side
import com.carstage.app.stage.SoundCue
import com.carstage.app.stage.SoundChange
import com.carstage.app.stage.StageController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the songs on the stage came to: the last song's 흥 점수 ([result]) and a duel ([duel],
 * docs/04 "기능 2"). Makes each stage's director ([newDirector]) and hears it on the audio thread;
 * asks that director for a duel ([startDuel]).
 */
class SongSession(
    private val scope: CoroutineScope,
    private val phase: StateFlow<StageController.Phase>,
    /** Whether another app plays music, so a duel asked for during a song waits for it. */
    private val musicPlaying: StateFlow<Boolean>,
    private val openStage: suspend () -> OpenOutcome,
    private val notices: NoticeBoard,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val fail: (String, Throwable) -> Unit,
    /** The title of the song that played over the [seconds] just ended, if the music app told it ([SongTitles]). */
    private val titleOf: (seconds: Int) -> String? = { null },
) {
    private val _result = MutableStateFlow<SongResult?>(null)

    /** The last song's 흥 점수, until the screen dismisses it, a new stage opens or the car drives off. */
    val result: StateFlow<SongResult?> = _result.asStateFlow()

    private val _stageScores = MutableStateFlow<List<SongResult>>(emptyList())

    /**
     * Every song scored on the stage opened last, in order, until the screen dismisses them, a new
     * stage opens or the car drives off: the basic flow shows them once the stage closes (docs/13).
     */
    val stageScores: StateFlow<List<SongResult>> = _stageScores.asStateFlow()

    private val _duel = MutableStateFlow<DuelState?>(null)

    /**
     * The duel on the open stage, or its result, until dismissed, the next song is scored on its
     * own, a new stage opens or the car drives off. Closing the stage ends a duel still being sung
     * and shows the result of one being announced.
     */
    val duel: StateFlow<DuelState?> = _duel.asStateFlow()

    private val _scored = MutableStateFlow(false)

    /**
     * Whether a song on this stage has been scored yet, on its own or as a duel's turn, so the
     * screen says a score will come only until the first one does (docs/10 7장); a new stage
     * starts again without one.
     */
    val scored: StateFlow<Boolean> = _scored.asStateFlow()

    /** Whether a duel's winner is being announced, which the last song after charging waits for. */
    val announcing: Flow<Boolean> = duel.map { it?.step == DuelState.Step.ANNOUNCING }

    /** The director of the stage opened last, to start a duel on it; set when a stage really opens. */
    @Volatile
    private var director: GlowDirector? = null

    init {
        scope.launch {
            phase.filter { it == StageController.Phase.CLOSED }.collect { _duel.update { it?.closed() } }
        }
    }

    /** A new stage's director, remembered so a duel can be asked of it; the other callbacks are the stage's. */
    fun newDirector(
        onSongEnded: () -> Unit,
        onSound: (SoundCue) -> Unit,
        onSoundStop: () -> Unit,
        onSoundChange: (SoundChange) -> Unit,
        onSpeak: (String) -> Unit,
        night: () -> Boolean,
    ): GlowDirector = GlowDirector(
        onResult = ::songScored,
        onDuel = ::duelStep,
        onSongEnded = onSongEnded,
        onSound = onSound,
        onSoundStop = onSoundStop,
        onSoundChange = onSoundChange,
        onSpeak = onSpeak,
        night = night,
    ).also { director = it }

    /** A stage opened; a new one starts with no score, no duel and no song scored. */
    fun opened(alreadyOpen: Boolean) {
        if (alreadyOpen) return
        clear()
        _scored.value = false
    }

    /** The screen hides the stage while driving; the score and a duel go with it. */
    fun clear() {
        _result.value = null
        _duel.value = null
        _stageScores.value = emptyList()
    }

    /** The screen closed the stage's scores. */
    fun dismissStageScores() {
        _stageScores.value = emptyList()
    }

    /**
     * Asks for a duel, opening the stage first when it is not open; the driver sings first. While
     * another app plays music, a song already heard long enough to score is sung to its end first,
     * unless [now]; see [GlowDirector.requestDuel]. Waits up to [START_TIMEOUT_MS] for the audio
     * thread to start it, to say how. A refusal is already on the screen.
     */
    suspend fun startDuel(now: Boolean = false): DuelOutcome {
        if (phase.value != StageController.Phase.OPEN) {
            val outcome = openStage()
            if (outcome is OpenOutcome.Refused) return DuelOutcome.Refused(outcome.reason)
        }
        val current = director ?: run {
            warn("duel asked while the open stage has no director")
            notices.show(NoticeBoard.DUEL_FAILED, NoticeTone.ERROR)
            return DuelOutcome.Refused(NoticeBoard.DUEL_FAILED)
        }
        val waitForSong = !now && musicPlaying.value
        val before = duel.value
        val started = current.requestDuel(waitForSong)
        log("duel requested${if (waitForSong) ", waiting for a song being sung" else ""}")
        val state = withTimeoutOrNull(START_TIMEOUT_MS) { started.await() }
        if (state == null) log("duel not started within $START_TIMEOUT_MS ms")
        val driverAlready = before?.step == DuelState.Step.TURN && before.turn == Side.DRIVER
        return DuelOutcome.Asked(afterThisSong = state?.let { it.step == DuelState.Step.WAITING }, turnIsNew = !driverAlready)
    }

    /** Asks for a duel from a screen tap; see [startDuel]. Failures become a notice instead of a crash. */
    fun requestDuel(now: Boolean) {
        scope.launch {
            try {
                startDuel(now)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                fail("duel start failed", e)
                notices.show(NoticeBoard.DUEL_FAILED, NoticeTone.ERROR)
            }
        }
    }

    /** The screen closed the last song's score. */
    fun dismissResult() {
        _result.value = null
    }

    /** A duel's next singer starts the song they picked; see [GlowDirector.restartSong]. */
    fun restartSong() {
        director?.restartSong()
    }

    /** The screen closed the duel's result. */
    fun dismissDuel() {
        _duel.value = null
    }

    /** Called on the audio thread when a song ends with a score; the screen shows it. */
    private fun songScored(scored: SongResult) {
        val result = scored.copy(title = titleOf(scored.seconds))
        log(
            "song scored ${result.score} (${result.grade}) over ${result.seconds} s: participation ${result.participation}, " +
                "heat ${result.heat}, response ${result.response}, completion ${result.completion}" +
                (result.title?.let { ", title \"$it\"" } ?: ""),
        )
        _result.value = result
        _stageScores.update { it + result }
        _scored.value = true
        // A finished duel's result gives way to the song sung after it.
        _duel.update { if (it?.step == DuelState.Step.DONE) null else it }
    }

    /**
     * Called on the audio thread at each step of a duel and at its announcement's crown (logged as
     * `duel crowned`); the screen shows it in the place of a song's score, so a song scored before
     * or while waiting for the duel is not shown after it.
     */
    private fun duelStep(state: DuelState) {
        val step = if (state.step == DuelState.Step.ANNOUNCING && state.winnerShown) "crowned" else state.step.name.lowercase()
        log("duel $step: turn ${state.turn}, scores ${state.scores.mapValues { it.value.score }}, winner ${state.winner}")
        _duel.value = state
        _result.value = null
        // A turn's song is scored for the duel rather than on its own, but it was scored all the same.
        if (state.scores.isNotEmpty()) _scored.value = true
    }

    private companion object {
        /** How long a duel request waits for the audio thread, which may still be starting the microphone. */
        const val START_TIMEOUT_MS = 1_000L
    }
}
