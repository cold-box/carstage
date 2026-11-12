package com.carstage.app.stage

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the karaoke stage for the whole app. Screens and Gleo open and close the stage here, and
 * the audio side reports [AudioEvent]s here; nothing else talks to the [StageEngine].
 *
 * The engine's senders run in [scope], which should live as long as the app, so a stage outlives
 * the screen that opened it and is always closed (and restored) through [close].
 */
class StageController(
    outputs: List<CabinOutput>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    director: Director = Director.Silent,
    /**
     * Rules for single commands (the app passes its safety and battery rules); a command it refuses
     * is not sent and goes to [Director.dropped].
     */
    private val gate: (CabinCommand) -> Boolean = { true },
) {
    enum class Phase { CLOSED, OPENING, OPEN, CLOSING }

    /** Which outputs the open stage drives; an output whose capture failed is missing. */
    data class OpenResult(val driven: List<String>, val alreadyOpen: Boolean)

    sealed interface CloseResult {
        data object NotOpen : CloseResult
        data class Closed(val stop: StageEngine.StopResult) : CloseResult
    }

    private val engine = StageEngine(outputs, log)
    private val lifecycle = Mutex()
    private var driven: List<String> = emptyList()
    private val _phase = MutableStateFlow(Phase.CLOSED)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    @Volatile
    var director: Director = director

    @Volatile
    private var directorFailures = 0

    /** Commands the gate held back in this stage; reported when it closes. */
    @Volatile
    private var gateDrops = 0

    /** Why a song ends from outside the audio side ([endSong]). */
    enum class EndCause {
        /** The screen's button or Gleo. */
        HAND,

        /** The music app moved on to another song ([com.carstage.app.TrackEnds]). */
        TRACK,
    }

    /** Set by [endSong]; the audio thread takes it and delivers the end before its next event. */
    private val songEndRequested = AtomicReference<EndCause?>(null)

    /**
     * Audio frames since the last song ended by [endSong], counted on the audio thread;
     * [Int.MAX_VALUE] when none was. The audio side does not know of that end, so the gap it then
     * hears closes the same song and is ignored for [HAND_END_HOLD_FRAMES].
     */
    @Volatile
    private var framesSinceAskedEnd = Int.MAX_VALUE

    /** Why the last song asked for ended, so the app moving on just after an end by hand is that same end. */
    @Volatile
    private var lastAskedCause: EndCause? = null

    /**
     * Audio frames since the audio side's last end was delivered, on the audio thread;
     * [Int.MAX_VALUE] when none was. The music app often moves on just after the gap that ended the
     * song, so a [EndCause.TRACK] end within [TRACK_END_HOLD_FRAMES] is that same end.
     */
    @Volatile
    private var framesSinceHeardEnd = Int.MAX_VALUE

    /**
     * Opens the stage; when it is already open, reports what it drives. [newDirector] makes a
     * fresh director for a stage that really opens, so no smoothing, flash or score state carries
     * over from the last stage (an open that waited for a close to finish counts as a new stage).
     */
    suspend fun open(newDirector: (() -> Director)? = null): OpenResult = lifecycle.withLock {
        if (_phase.value == Phase.OPEN) return@withLock OpenResult(driven, alreadyOpen = true)
        _phase.value = Phase.OPENING
        try {
            newDirector?.let { director = it() }
            gateDrops = 0
            songEndRequested.set(null)
            framesSinceAskedEnd = Int.MAX_VALUE
            lastAskedCause = null
            framesSinceHeardEnd = Int.MAX_VALUE
            driven = engine.start(scope)
            directorFailures = 0
            log("stage open, driving $driven")
            _phase.value = Phase.OPEN
            OpenResult(driven, alreadyOpen = false)
        } catch (e: Throwable) {
            _phase.value = Phase.CLOSED
            throw e
        }
    }

    /**
     * Runs [block] while no stage is opening, open or closing, holding the lock that opening and
     * closing take, so the two cannot overlap; null without running it when a stage is not closed.
     */
    suspend fun <T> whileClosed(block: suspend () -> T): T? = lifecycle.withLock {
        if (_phase.value == Phase.CLOSED) block() else null
    }

    /** Closes the stage and restores the cabin, even when the caller is cancelled. */
    suspend fun close(): CloseResult = withContext(NonCancellable) {
        lifecycle.withLock {
            if (_phase.value != Phase.OPEN) return@withLock CloseResult.NotOpen
            _phase.value = Phase.CLOSING
            try {
                val stop = engine.stop()
                if (gateDrops > 0) log("the command rules held back $gateDrops commands in this stage")
                if (directorFailures > 1) log("director failed $directorFailures times in this stage")
                log("stage closed, restored all=${stop.restoredAll}" + if (stop.deferred.isEmpty()) "" else ", deferred until parked=${stop.deferred.size}")
                CloseResult.Closed(stop)
            } finally {
                // If stop() failed before the engine ended, stay OPEN so closing can be retried.
                _phase.value = if (engine.isRunning) Phase.OPEN else Phase.CLOSED
            }
        }
    }

    /**
     * Ends the song from outside the audio side ([cause]): by hand, as the screen's button and
     * Gleo do when the gap was missed or the singing is over before the music, or as the music app
     * moves on to another song. The end reaches the director on the audio thread, before its next
     * event, so the director is never called from two threads; if the stage closes first, it is
     * dropped. A gap the audio side hears within 30 s of sound after it is the end of the same song
     * and is ignored, as is a [EndCause.TRACK] end within 30 s after one by hand (the outro the
     * singers stopped before) or within 5 s after an end the audio side heard. False when no stage
     * is open.
     */
    fun endSong(cause: EndCause = EndCause.HAND): Boolean {
        if (_phase.value != Phase.OPEN) return false
        songEndRequested.set(cause)
        return true
    }

    /**
     * Called by the audio side, on the audio thread, for every event; ignored unless the stage is
     * open. A song ended by hand ([endSong]) is delivered first. A director that throws loses that
     * event instead of the audio thread.
     */
    fun onAudio(event: AudioEvent) {
        if (_phase.value != Phase.OPEN) return
        songEndRequested.getAndSet(null)?.let { cause ->
            if (cause == EndCause.TRACK && framesSinceHeardEnd < TRACK_END_HOLD_FRAMES) {
                log("song change right after the end heard, ignored")
            } else if (cause == EndCause.TRACK && lastAskedCause == EndCause.HAND && framesSinceAskedEnd < HAND_END_HOLD_FRAMES) {
                log("song change right after one ended by hand, ignored")
            } else {
                framesSinceAskedEnd = 0
                lastAskedCause = cause
                log(if (cause == EndCause.HAND) "song ended by request" else "song ended as the music app moved on")
                deliver(AudioEvent.SongEnded)
            }
        }
        if (event is AudioEvent.Frame) {
            if (framesSinceAskedEnd != Int.MAX_VALUE) framesSinceAskedEnd++
            if (framesSinceHeardEnd != Int.MAX_VALUE) framesSinceHeardEnd++
        }
        if (event == AudioEvent.SongEnded) {
            if (framesSinceAskedEnd < HAND_END_HOLD_FRAMES) {
                log("song end heard right after one asked for, ignored")
                return
            }
            framesSinceHeardEnd = 0
            log("song ended")
        }
        deliver(event)
    }

    private fun deliver(event: AudioEvent) {
        val current = director
        try {
            current.direct(event).forEach { command ->
                if (gate(command)) {
                    engine.send(command)
                } else {
                    gateDrops++
                    current.dropped(command)
                }
            }
        } catch (e: Exception) {
            if (directorFailures++ == 0) log("director failed on $event: ${e::class.java.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** About 30 s of audio frames, as long as the audio side waits between the ends it hears. */
        val HAND_END_HOLD_FRAMES = (30 * GlowDirector.FRAMES_PER_SECOND).roundToInt()

        /** About 5 s of audio frames: a gap and the app's move to the next song are one end. */
        val TRACK_END_HOLD_FRAMES = (5 * GlowDirector.FRAMES_PER_SECOND).roundToInt()
    }
}
