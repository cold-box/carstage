package com.carstage.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/**
 * The MC's voice (docs/04 "발표 모드 연출 순서", docs/12): speaks a duel announcement's lines and the MC's ([com.carstage.app.StageMc]) through the
 * car's speech service with the Pleos TextToSpeech SDK, on the device (no network, no app
 * registration), under the sound effects' rules (CLAUDE.md, Product principles): transient
 * audio focus that lets the music duck, stopped at once when focus is lost (Gleo, a call), given
 * back when the line ends or after [limitMs] at the latest. It holds the engine only while a stage
 * is open ([prepare], [release]), so every stage starts it again. If the speech service does not
 * start, cannot be reached or fails for good, the engine is let go until the next stage (or until
 * the user turns the MC off and on again) and the screen's words stand alone, as
 * they do while the user has turned the MC off ([setOn]): no line is said and no engine is held. While it speaks and shortly after, [echoing] is true, so
 * the microphone's hearing of it is not taken for the cabin's cheers. Safe from any thread; the
 * work runs on the main thread.
 */
class Announcer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val warn: (String, Throwable?) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * For the focus request and the speech service's playback: unset, the service speaks as media,
     * which the music check would take for another app's music (docs/05 "Pleos TTS로 MC 음성 확인").
     */
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val echo = EchoWindow()

    /**
     * Set by debug builds' tests (DevLab) with the sound effects': lines are logged, not spoken,
     * and keep an echo window as long as they would take to say ([estimateMs]).
     */
    @Volatile
    var muted = false

    /** Where the engine stands, for the Lab. */
    @Volatile
    var state: String = "not started"
        private set

    /**
     * Whether the engine was let go after a failure and has not started since: lines are only
     * logged, so Gleo reads its own answers out instead of leaving them to the MC (docs/12 "Gleo와 MC").
     */
    @Volatile
    var failed = false
        private set

    /** Whether a line is being spoken; read from the audio thread. */
    @Volatile
    private var speaking = false

    // Touched on the main thread only.
    private var tts: TextToSpeech? = null
    private var events: Events? = null
    private var ready = false
    private var current: String? = null
    private var started = false
    private var startedAt = 0L
    private var focus: AudioFocusRequest? = null
    private var timeout: Runnable? = null
    private var lines = 0

    /** Whether a stage holds the voice, from [prepare] to [release]. */
    private var held = false

    /** Whether the user lets the MC speak ([setOn]). */
    private var on = true

    /** The last line asked for while the speech service was starting, said once it is ready. */
    private var waiting: String? = null

    /** The app's stop() calls whose onStop has not come yet, so a stop the service makes itself is told apart. */
    private var pendingStops = 0

    /**
     * One engine's callbacks, which may come on another thread. They name no line; one line is
     * spoken at a time, so they are taken for [current], and dropped once their engine is let go.
     */
    private inner class Events(private val engine: TextToSpeech) : EventListener {
        private fun onMain(block: () -> Unit) {
            handler.post { if (engine === tts) block() }
        }

        // Comes as each line is taken, before it starts.
        override fun onReady() = Unit

        override fun onStart() = onMain {
            if (current == null) return@onMain
            started = true
            log("voice started after ${SystemClock.elapsedRealtime() - startedAt} ms")
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() = onMain {
            current?.let { finish(it, "ended") } ?: log("voice engine done with no line")
        }

        override fun onStop() = onMain {
            // A stop the app asked for follows a line it has already ended; ending [current] then could end the next.
            if (pendingStops > 0) {
                pendingStops--
                return@onMain
            }
            current?.let { finish(it, "stopped by the speech service") }
        }

        override fun onError(errorType: ErrorType) = onMain {
            current?.let { finish(it, "failed ($errorType)") }
            if (errorType in LASTING) drop(engine, "the speech service cannot speak ($errorType)") else warn("voice engine error $errorType", null)
        }
    }

    /** Whether the MC is speaking or just spoke, so what the microphone hears may be it. */
    fun echoing(): Boolean = speaking || echo.echoing(SystemClock.elapsedRealtime())

    /**
     * Holds the voice for a stage that opened and starts the engine, unless the MC is off ([setOn]
     * starts it if the MC is turned on later in the stage) or it is starting or running; a stage's
     * lines come later.
     */
    fun prepare() {
        handler.post {
            held = true
            start()
        }
    }

    /** Stops a line and lets the engine go, as the stage closes. */
    fun release() {
        handler.post {
            held = false
            close("let go as the stage closed")
        }
    }

    /**
     * Lets the MC speak or not, as the user set it ([com.carstage.app.McSetting]): turned off, a
     * line being spoken stops and the engine is let go; turned on while a stage is open, it starts.
     */
    fun setOn(on: Boolean) {
        handler.post {
            if (on == this.on) return@post
            this.on = on
            log("voice turned ${if (on) "on" else "off"}")
            when {
                !on -> close(OFF)
                held -> start()
                else -> state = "on: starts with the next stage"
            }
        }
    }

    private fun start() {
        if (tts != null) return
        if (!on) {
            state = OFF
            return
        }
        state = "starting"
        val engine = try {
            TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
        } catch (e: Exception) {
            failed = true
            state = "the speech service could not be reached"
            return warn("voice: $state; the screen's words stand alone", e)
        }
        tts = engine
        pendingStops = 0
        try {
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post { initialized(engine, null) }
                }

                override fun onFailure(errorType: ErrorType) {
                    handler.post { initialized(engine, errorType) }
                }
            })
        } catch (e: Exception) {
            return drop(engine, "the speech service did not start", e)
        }
        // A service that never answers would leave the stage without its voice and nothing said.
        handler.postDelayed({ if (engine === tts && !ready) drop(engine, "the speech service did not answer in $START_LIMIT_MS ms") }, START_LIMIT_MS)
    }

    /** Stops a line, forgets one waiting for the engine and lets the engine go, for [why]. */
    private fun close(why: String) {
        stopNow()
        waiting?.let { log("voice skipped, $why: \"$it\"") }
        waiting = null
        tts?.let(::letGo)
        tts = null
        ready = false
        state = why
    }

    /** Speaks [line], cutting one still being spoken. */
    fun speak(line: String) {
        handler.post { speakNow(line) }
    }

    /** Stops a line being spoken, as when a duel starts over. */
    fun stop() {
        handler.post { stopNow() }
    }

    /** Ends the line being spoken, focus and the echo window first, then asks the engine to stop it. */
    private fun stopNow() {
        val id = current ?: return
        finish(id, "stopped")
        halt()
    }

    /** Asks the engine to stop speaking; its onStop is then the app's own. */
    private fun halt() {
        val engine = tts ?: return
        pendingStops++
        try {
            engine.stop()
        } catch (e: Exception) {
            drop(engine, "the speech service could not stop a line", e)
        }
    }

    /** The engine started, or could not ([failure]); without it the next stage, or the MC turned on again, tries again. */
    private fun initialized(engine: TextToSpeech, failure: ErrorType?) {
        if (engine !== tts) return
        if (failure != null) return drop(engine, "the speech service did not start ($failure)")
        val listener = Events(engine)
        events = listener
        try {
            engine.addEventListener(listener)
            engine.setLocale(Locale.KOREA)
            engine.setAudioAttributes(attributes)
        } catch (e: Exception) {
            return drop(engine, "the speech service did not take its settings", e)
        }
        ready = true
        failed = false
        state = "ready: Pleos on-device speech"
        log("voice $state")
        waiting?.let {
            waiting = null
            speakNow(it)
        }
    }

    /** Lets [engine] go, ending a line it was speaking; the next stage, or the MC turned off and on again, starts a new one. */
    private fun drop(engine: TextToSpeech, why: String, e: Throwable? = null) {
        if (engine !== tts) return
        waiting?.let { log("voice skipped, $why: \"$it\"") }
        waiting = null
        current?.let { finish(it, "cut") }
        tts = null
        ready = false
        failed = true
        state = why
        letGo(engine)
        warn("voice: $why; the screen's words stand alone", e)
    }

    /** Lets [engine] go; its callbacks are ignored from now on, so a listener that will not come off does not keep it. */
    private fun letGo(engine: TextToSpeech) {
        val listener = events
        events = null
        try {
            listener?.let(engine::removeEventListener)
        } catch (e: Exception) {
            warn("voice: removing the speech service's listener failed", e)
        }
        try {
            engine.release()
        } catch (e: Exception) {
            warn("voice: letting the speech service go failed", e)
        }
    }

    private fun speakNow(line: String) {
        // Nothing is said, so the microphone hears nothing of it.
        if (!on) return log("voice off: \"$line\"")
        if (muted) {
            echo.started(SystemClock.elapsedRealtime(), estimateMs(line))
            return log("voice \"$line\" (muted)")
        }
        val engine = tts
        if (engine != null && !ready) {
            waiting?.let { log("voice skipped, a newer line came while the speech service started: \"$it\"") }
            waiting = line
            return log("voice waits for the speech service to start: \"$line\"")
        }
        if (!ready || engine == null) return log("voice skipped, $state: \"$line\"")
        stopNow()
        // Stopping the last line may have let the engine go.
        if (engine !== tts || !ready) return log("voice skipped, $state: \"$line\"")
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                val id = current
                if (change < 0 && id != null) {
                    finish(id, "focus lost ($change)")
                    halt()
                }
            }, handler)
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return warn("voice skipped, audio focus not granted: \"$line\"", null)
        }
        focus = request
        val id = "line-${++lines}"
        current = id
        started = false
        // The microphone may hear it from the first moment.
        speaking = true
        startedAt = SystemClock.elapsedRealtime()
        // An engine that dies or stalls sends no callback; the line still ends.
        timeout = Runnable {
            if (current != id) return@Runnable
            val heard = started
            finish(id, "timed out")
            halt()
            // An engine that never started the line is not answering; the stage goes on without it.
            if (!heard) tts?.let { drop(it, "the speech service did not start a line in ${limitMs(line)} ms") }
        }.also { handler.postDelayed(it, limitMs(line)) }
        try {
            engine.speak(line)
        } catch (e: Exception) {
            finish(id, "failed to start")
            return drop(engine, "the speech service did not take a line", e)
        }
        log("voice \"$line\"")
    }

    /** Ends line [id] if it is the one being spoken: gives focus back and keeps the echo window a little longer. */
    private fun finish(id: String, why: String) {
        if (id != current) return
        current = null
        speaking = false
        timeout?.let(handler::removeCallbacks)
        timeout = null
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
        echo.started(SystemClock.elapsedRealtime(), 0)
        log("voice $why after ${SystemClock.elapsedRealtime() - startedAt} ms")
    }

    companion object {
        /** About how long a Korean line takes to say: a syllable is a character, and an engine needs a moment to start. */
        fun estimateMs(line: String): Long = START_MS + PER_CHARACTER_MS * line.length

        /** The longest a line may hold focus and the echo window: twice its estimate and a second, at most [MAX_LINE_MS]. */
        fun limitMs(line: String): Long = minOf(MAX_LINE_MS, 2 * estimateMs(line) + 1_000L)

        private const val START_MS = 300L
        private const val PER_CHARACTER_MS = 130L
        const val MAX_LINE_MS = 8_000L

        /** [state] while the user has turned the MC off. */
        private const val OFF = "off: the MC was turned off"

        /** The longest the speech service may take to start; about 0.5 s on the emulator (docs/05). */
        private const val START_LIMIT_MS = 5_000L

        /** Errors that will not pass within a stage, so the engine is let go until the next one. */
        private val LASTING = setOf(
            ErrorType.PERMISSION_DENIED,
            ErrorType.NOT_SUPPORTED_API,
            ErrorType.UNAVAILABLE_LANGUAGE,
            ErrorType.UNAUTHENTICATED,
            ErrorType.ON_DEVICE_ENGINE_ERROR,
        )
    }
}
