package com.carstage.app.audio

import android.os.SystemClock
import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.AudioEvent.Mood.Feel
import com.carstage.app.stage.VoiceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The stage's ears: listens while the stage is open and hands every [AudioEvent] to [onEvent] on
 * the capture thread. Audio stays in memory one frame at a time and is never stored or sent
 * (CLAUDE.md, Product principles). If the input stops on its own, [onFailed] gets the reason (and the
 * exception, for a fault in the audio code) so the stage can close instead of sitting open and
 * deaf; [onSilence] hears when only digital silence has arrived for a while, and when it ends.
 * [state] says the same for the screen (docs/10 7장 "듣는 중 표시": 듣고 있어요, 잠시 안 들려요).
 * While it listens, [tap] hears what the other apps play, and every frame goes through its
 * [EchoPath] before the analysis, so the music is taken out and the voices stay (docs/09 "에코 제거").
 */
class LiveMic(
    private val hasPermission: () -> Boolean,
    private val onEvent: (AudioEvent) -> Unit,
    private val onFailed: (String, Throwable?) -> Unit,
    private val onSilence: (Boolean) -> Unit,
    private val log: (String) -> Unit,
    private val warn: (String, Throwable?) -> Unit,
    private val tap: PlaybackTap? = null,
) {
    /** The running input; volatile because a failure report from the capture thread checks it. */
    @Volatile
    private var input: AudioInput? = null
    private var analyzer: AudioAnalyzer? = null
    private var echo: EchoPath? = null
    private var startedAt = 0L

    private val _state = MutableStateFlow(State.OFF)

    /** Whether it is listening, and whether only silence has arrived for a while. */
    val state: StateFlow<State> = _state.asStateFlow()

    enum class State {
        OFF,
        LISTENING,

        /** Listening, but only digital silence arrives: another app took the microphone, or it is muted. */
        SILENT,
    }

    /** The microphone's source ([MicInput.SOURCE] unless a development build tries another), from the next [start]. */
    @Volatile
    var source: Int = MicInput.SOURCE

    /** The stage's mode over what was heard, counted for the log. */
    private var modes = VoiceMode()

    /** Used once, by the next [start], instead of the microphone: the Lab plays recordings through it. */
    @Volatile
    var nextInput: AudioInput? = null

    /** Starts listening if it is not already; false when the microphone cannot be used. */
    @Synchronized
    fun start(): Boolean {
        if (input != null) return true
        val newInput = nextInput.also { nextInput = null } ?: run {
            if (!hasPermission()) {
                warn("microphone: RECORD_AUDIO not granted", null)
                return false
            }
            MicInput(log = { warn(it, null) }, source = source)
        }
        val newModes = VoiceMode()
        val newAnalyzer = AudioAnalyzer(newInput.sampleRate, { event ->
            if (event is AudioEvent.Frame) newModes.hear(event)
            onEvent(event)
        }) { silent ->
            if (silent) warn("${newInput.label}: only digital silence for about 3 s", null)
            if (input === newInput) _state.value = if (silent) State.SILENT else State.LISTENING
            onSilence(silent)
        }
        // A recording the Lab plays has no music playing beside it to take out.
        val newEcho = tap?.begin(newInput.sampleRate, recording = newInput !is MicInput)
        // Recorded before starting, so a failure the capture thread reports at once is not ignored.
        input = newInput
        analyzer = newAnalyzer
        echo = newEcho
        modes = newModes
        startedAt = SystemClock.elapsedRealtime()
        return try {
            newInput.start({ frame -> newAnalyzer.process(newEcho?.process(frame) ?: frame) }) { reason, cause ->
                // Only the input that is still running may close the stage; a late report from
                // an input that was already stopped must not touch a newer stage.
                if (input === newInput) {
                    warn("${newInput.label} stopped by itself: $reason", cause)
                    onFailed(reason, cause)
                }
            }
            log("${newInput.label} on" + newInput.details().let { if (it.isEmpty()) "" else " ($it)" })
            _state.value = State.LISTENING
            true
        } catch (e: Exception) {
            input = null
            analyzer = null
            echo = null
            newInput.stop()
            tap?.end()
            // A source a development build chose stays until the app ends, so say so where it fails.
            val chosen = if (newInput is MicInput && source != MicInput.SOURCE) {
                " (development micsource ${AudioSources.nameOf(source)}; --es micsource MIC goes back)"
            } else {
                ""
            }
            warn("${newInput.label} failed to start: ${e::class.java.simpleName}: ${e.message}$chosen", e)
            false
        }
    }

    /** Whether [candidate] is what the microphone is listening to right now (the Lab checks its replay). */
    fun isListeningTo(candidate: AudioInput): Boolean = input === candidate

    /**
     * Stops listening and logs what was heard, in numbers only. About 23 frames a second are
     * expected; fewer means the input stalled.
     */
    @Synchronized
    fun stop() {
        val running = input ?: return
        input = null
        running.stop()
        tap?.end()
        _state.value = State.OFF
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        analyzer?.let {
            // A silence that was reported ends with the microphone, so its notice does not outlive it.
            if (it.isSilent) onSilence(false)
            log(
                "${running.label} off after ${"%.1f".format(seconds)} s: ${it.frames} frames, ${it.voicedFrames} sung, " +
                    "${it.loudFrames} loud, ${it.cheers} cheers, ${it.silentFrames} silent (longest run ${it.longestSilentRun}), " +
                    "loudest ${"%.1f".format(it.loudestDb)} dBFS, background ${"%.1f".format(it.backgroundDb)} dBFS, " +
                    "moods ${Feel.entries.joinToString(" / ") { feel -> "${it.moodsReported(feel)} ${feel.name.lowercase()}" }} " +
                    "(${it.moodChanges} changes, last beat ${"%.0f".format(it.beatsPerMinute)} BPM), ${it.songsEnded} songs ended, " +
                    "modes ${modes.voiceFrames} voice / ${modes.musicFrames} music (${modes.switches} switches)" +
                    (echo?.let { path -> ", ${path.summary()}" } ?: ""),
            )
        }
        analyzer = null
        echo = null
    }
}
