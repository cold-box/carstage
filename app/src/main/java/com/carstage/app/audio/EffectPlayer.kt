package com.carstage.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.carstage.app.R
import com.carstage.app.stage.SoundCue
import com.carstage.app.stage.SoundChange
import com.carstage.app.stage.SoundCue.Recording
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Plays the stage's sound cues ([SoundCue]) over whatever the car plays (CLAUDE.md, Product principles):
 * each one asks for transient audio focus that lets the music duck, stops at once when focus is
 * lost (Gleo, a call, navigation) and gives focus back when it ends. Sonification usage, so the car
 * plays it beside the music app instead of pausing that app.
 *
 * The sounds load once, when this is made, and their lengths are read on a thread of their own.
 * [play], [change] and [stop] are safe from any thread; the work runs on the main thread. While a
 * cue plays and shortly after it, [echoing] is true (see [EchoWindow]), so the microphone's hearing
 * of it is not taken for the cabin's voices.
 */
class EffectPlayer(context: Context, private val log: (String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(MAX_PARTS).setAudioAttributes(attributes).build()
    private val sounds: Map<Recording, Int>
    private val lengths = ConcurrentHashMap<Recording, Long>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val echo = EchoWindow()

    // Touched on the main thread only.
    /** The playing streams and the volume of the part each plays, so [change] can move it. */
    private val streams = mutableMapOf<Int, Float>()
    private var focus: AudioFocusRequest? = null

    /** When a muted cue would have ended (elapsed realtime), so its replacing and stopping are told as a real one's. */
    private var mutedUntil = 0L
    private val end = Runnable { finish("ended") }

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) loaded += id else log("sound effect $id failed to load: status $status")
        }
        val resources = Recording.entries.associateWith(::resourceOf)
        sounds = resources.mapValues { (_, res) -> pool.load(context, res, 1) }
        thread(name = "effect-lengths") { resources.forEach { (recording, res) -> lengths[recording] = lengthOf(context, recording, res) } }
    }

    /**
     * Set by debug builds' tests (DevLab) so a run makes no sound on the computer: a cue then keeps
     * its timing and its echo window, so the stage hears as it would, but asks for no focus and plays nothing.
     */
    @Volatile
    var muted = false

    /** Whether a cue is playing or just played, so what the microphone hears may be it. */
    fun echoing(): Boolean = echo.echoing(SystemClock.elapsedRealtime())

    /** Plays [cue], stopping one still playing. */
    fun play(cue: SoundCue) {
        handler.post { playNow(cue) }
    }

    /**
     * Moves the cue playing to [change]'s side and plays it at its rate (a duel's drumroll taking
     * turns between the sides, its fanfare on the winner's side); nothing when none plays. The
     * focus and the echo window keep the length the cue had at its normal rate, a little longer
     * than a faster one plays.
     */
    fun change(change: SoundChange) {
        handler.post {
            val what = "${change.pan} at ${"%.2f".format(change.rate)}x"
            when {
                streams.isNotEmpty() -> {
                    streams.forEach { (stream, volume) ->
                        val (left, right) = change.pan.volumes(volume)
                        pool.setVolume(stream, left, right)
                        pool.setRate(stream, change.rate)
                    }
                    log("sound change $what")
                }
                SystemClock.elapsedRealtime() < mutedUntil -> log("sound change $what (muted)")
            }
        }
    }

    /** Stops a cue that is playing, as when the stage closes. */
    fun stop() {
        handler.post {
            val now = SystemClock.elapsedRealtime()
            if (streams.isNotEmpty()) {
                finish("stopped")
            } else if (now < mutedUntil) {
                mutedUntil = 0L
                echo.cut(now)
                log("sound effect stopped (muted)")
            }
        }
    }

    private fun playNow(cue: SoundCue) {
        if (cue.parts.any { sounds.getValue(it.recording) !in loaded }) return log("sound $cue skipped: not loaded")
        if (streams.isNotEmpty()) finish("replaced")
        if (muted) {
            val now = SystemClock.elapsedRealtime()
            if (now < mutedUntil) log("sound effect replaced (muted)")
            val length = holdMs(cue.parts.map { lengths[it.recording] ?: FALLBACK_MS })
            mutedUntil = now + length
            echo.started(now, length)
            return log("sound $cue muted for $length ms")
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0 && streams.isNotEmpty()) finish("focus lost ($change)") }, handler)
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return log("sound $cue skipped: audio focus not granted")
        }
        focus = request
        val started = cue.parts.filter { part ->
            val stream = pool.play(sounds.getValue(part.recording), part.volume, part.volume, 1, 0, 1f)
            if (stream != 0) streams[stream] = part.volume else log("sound $cue: ${part.recording} failed to play")
            stream != 0
        }
        if (started.isEmpty()) {
            finish("failed to start")
            return log("sound $cue failed to play")
        }
        val length = holdMs(started.map { lengths[it.recording] ?: FALLBACK_MS })
        echo.started(SystemClock.elapsedRealtime(), length)
        handler.postDelayed(end, length)
        log("sound $cue playing for $length ms")
    }

    /** Stops the streams if any and gives focus back; [why] is logged unless the cue simply ended. */
    private fun finish(why: String) {
        handler.removeCallbacks(end)
        streams.keys.forEach(pool::stop)
        streams.clear()
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
        if (why != "ended") {
            echo.cut(SystemClock.elapsedRealtime())
            log("sound effect $why")
        }
    }

    private fun resourceOf(recording: Recording): Int = when (recording) {
        Recording.SHORT_APPLAUSE -> R.raw.sfx_applause_short
        Recording.APPLAUSE -> R.raw.sfx_applause
        Recording.DRUMROLL -> R.raw.sfx_drumroll
        Recording.FANFARE -> R.raw.sfx_fanfare
    }

    /** The file's length, so focus is held as long as it plays; [FALLBACK_MS] when it cannot be read. */
    private fun lengthOf(context: Context, recording: Recording, res: Int): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            context.resources.openRawResourceFd(res).use { fd -> retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: FALLBACK_MS
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        log("sound $recording length unknown, taking $FALLBACK_MS ms: ${e::class.java.simpleName}: ${e.message}")
        FALLBACK_MS
    }

    companion object {
        /**
         * How long to hold focus for parts of [lengthsMs] that started together: the longest, and a
         * little more, as the sound starts a little after play().
         */
        fun holdMs(lengthsMs: List<Long>): Long = lengthsMs.max() + END_MARGIN_MS

        /** The most recordings a cue plays together ([SoundCue.OVATION]). */
        val MAX_PARTS = SoundCue.entries.maxOf { it.parts.size }

        /** Longer than the longest cue (the applause, 4.1 s), for a length that could not be read. */
        const val FALLBACK_MS = 4_500L

        /** How much later than play() the sound may end: output latency and the fade's last milliseconds. */
        const val END_MARGIN_MS = 200L
    }
}
