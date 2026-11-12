package com.carstage.app.audio

import android.os.Process
import com.carstage.app.audio.echo.CabinEchoCanceller
import com.carstage.app.audio.echo.EchoCanceller
import kotlin.concurrent.thread

/**
 * Hears what the other apps play while the stage listens, so the echo canceller can take that
 * music out of the microphone and leave the voices (docs/09 "에코 제거"). [LiveMic] calls [begin]
 * as the microphone starts and [end] as it stops, so the capture runs exactly as long. The
 * sources are tried in order on the tap's own thread ([candidates]: a development build's helper,
 * then the capture the user allowed for this stage); when none gives sound, the stage goes on
 * without echo cancelling and the first screen says so once an app run ([show]). Audio stays in
 * memory for at most [ReferenceBuffer.CAPACITY_MS] and is never stored or sent (CLAUDE.md, Product principles).
 */
class PlaybackTap(
    private val candidates: () -> Sequence<Candidate>,
    private val musicPlaying: () -> Boolean,
    private val show: (String) -> Unit,
    private val hide: (String) -> Unit,
    private val log: (String) -> Unit,
    private val warn: (String, Throwable?) -> Unit,
    /** Whatever the stage was given to capture with is let go once it ends (the user's consent is for one stage). */
    private val release: () -> Unit = {},
    private val canceller: (Int) -> EchoCanceller = { CabinEchoCanceller(it) },
) {
    /** One way to the music: a source to open, or why there is none. */
    sealed interface Candidate {
        data class Source(val source: PlaybackSource) : Candidate

        data class Missing(val why: EchoOff) : Candidate
    }

    /**
     * Whether the canceller runs, from the next stage; the music is captured and measured either
     * way. Off until it is shown to help: in the emulator's virtual cabin it found the delay but
     * took out under 2 dB and cut the voice frames by a third (docs/05). A development build turns
     * it on to test (`--es echo on`).
     */
    @Volatile
    var cancels = false

    /**
     * How much longer the reference is held back before the canceller sees it, from the next stage.
     * 0 in the product, where the speaker reaches the microphone within tens of milliseconds; a
     * development build sets it (`--ei echo_hold_ms`) for the emulator's virtual cabin, whose echo
     * comes about 0.5 s after the reference, past what the canceller searches (docs/05).
     */
    @Volatile
    var extraHoldMs = 0

    private val noticed = mutableSetOf<EchoOff>()
    private var worker: Thread? = null
    private var session: Session? = null

    /** One stage's capture; its own flag, so a thread that ends late cannot run on into the next stage. */
    private class Session {
        @Volatile
        var running = true

        @Volatile
        var current: PlaybackSource? = null
    }

    /** Starts hearing the music for a microphone at [sampleRate]; [recording] when the Lab plays a file instead. */
    @Synchronized
    fun begin(sampleRate: Int, recording: Boolean): EchoPath {
        end()
        val engine = canceller(sampleRate)
        if (recording) return EchoPath(engine, null, cancels = false, off = EchoOff.RECORDING)
        val buffer = ReferenceBuffer(sampleRate, holdMs = ReferenceBuffer.HOLD_MS + extraHoldMs)
        lateinit var path: EchoPath
        path = EchoPath(
            engine,
            buffer,
            cancels = cancels,
            off = null,
            musicPlaying = musicPlaying,
            onBlocked = {
                warn("playback tap: only silence while music plays (${path.sourceLabel}); the music app may not allow capture", null)
                noticeOnce(EchoOff.BLOCKED)
            },
        )
        val started = Session()
        session = started
        worker = thread(name = "playback-tap") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            started.capture(buffer, path)
        }
        return path
    }

    private fun Session.capture(buffer: ReferenceBuffer, path: EchoPath) {
        var why = EchoOff.UNAVAILABLE
        for (candidate in candidates()) {
            if (!running) return
            val source = when (candidate) {
                is Candidate.Missing -> {
                    why = candidate.why
                    continue
                }
                is Candidate.Source -> candidate.source
            }
            val format = try {
                current = source
                // A stage that ended meanwhile closed nothing yet, as the source was not current.
                if (!running) {
                    source.close()
                    return
                }
                source.open()
            } catch (e: Exception) {
                current = null
                source.close()
                log("playback tap: ${source.label} did not start: ${e::class.java.simpleName}: ${e.message}")
                why = EchoOff.FAILED
                continue
            }
            path.sourceLabel = source.label
            if (!cancels) path.off = EchoOff.SWITCHED_OFF
            log("playback tap on: ${source.label} (${format.sampleRate} Hz, ${format.channels} channels)${if (cancels) "" else ", canceller off for a test"}")
            val pcm = ShortArray(format.sampleRate / BLOCKS_PER_SECOND * format.channels)
            var failure: String? = null
            try {
                while (running) {
                    val count = source.read(pcm)
                    if (count < 0) {
                        failure = "read returned $count"
                        break
                    }
                    if (count > 0) buffer.write(pcm, count, format.channels, format.sampleRate, System.nanoTime())
                }
            } catch (e: Exception) {
                failure = "${e::class.java.simpleName}: ${e.message}"
            } finally {
                current = null
                source.close()
            }
            // Only a capture that ended by itself is a failure; a stage that closed ends it too.
            if (running && failure != null) {
                warn("playback tap: ${source.label} stopped by itself: $failure", null)
                path.off = EchoOff.FAILED
                noticeOnce(EchoOff.FAILED)
            }
            return
        }
        if (!running) return
        path.off = why
        log("playback tap off: ${why.reason}")
        noticeOnce(why)
    }

    private fun noticeOnce(why: EchoOff) {
        val text = why.notice ?: return
        if (synchronized(noticed) { noticed.add(why) }) show(text)
    }

    /** Stops hearing the music, and takes down what was said about it. */
    @Synchronized
    fun end() {
        val stopping = session ?: return
        stopping.running = false
        stopping.current?.close()
        worker?.join(JOIN_MS)
        worker = null
        session = null
        release()
        hide(EchoOff.NOTICE_LOST)
    }

    companion object {
        /** Reads of 20 ms: short enough that a frame's reference has arrived [ReferenceBuffer.HOLD_MS] later. */
        private const val BLOCKS_PER_SECOND = 50
        private const val JOIN_MS = 1_000L
    }
}
