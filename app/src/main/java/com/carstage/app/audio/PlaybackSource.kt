package com.carstage.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Where the stage hears what the other apps play: Android's playback capture, or a development
 * build's helper (DevPlayback). Used on the tap's own thread: [open] and [read] block.
 */
interface PlaybackSource {
    /** What the log calls this source. */
    val label: String

    /** Starts capturing; throws when it cannot. */
    fun open(): Format

    /** Fills [pcm] with interleaved 16-bit samples as they arrive; the count, or a negative number once it ended. */
    fun read(pcm: ShortArray): Int

    /** Stops; from any thread, ending a [read] that waits. */
    fun close()

    data class Format(val sampleRate: Int, val channels: Int)

    companion object {
        /** What may be captured of other apps' playback (AudioPlaybackCaptureConfiguration allows no more): music, games and unlabeled sound. */
        val USAGES = listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
    }
}

/**
 * Android's capture of what other apps play ([AudioPlaybackCaptureConfiguration]), with the
 * [projection] the user allowed for this stage. Apps that do not allow capture
 * (`allowAudioPlaybackCapture="false"`, or a policy) are left out by Android and give silence.
 * The app's own sounds are sonification, which capture never takes.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ProjectionPlayback(private val projection: MediaProjection) : PlaybackSource {
    override val label = "playback capture"

    @Volatile
    private var record: AudioRecord? = null

    // RECORD_AUDIO is checked before a stage opens (AppGraph.openStage).
    @SuppressLint("MissingPermission")
    override fun open(): PlaybackSource.Format {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .apply { PlaybackSource.USAGES.forEach(::addMatchingUsage) }
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(PlaybackSource.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(PlaybackSource.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val started = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, PlaybackSource.SAMPLE_RATE / 5 * 4))
            .build()
        if (started.state != AudioRecord.STATE_INITIALIZED) {
            started.release()
            error("AudioRecord for playback capture not initialized")
        }
        started.startRecording()
        if (started.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            started.release()
            error("playback capture did not start recording")
        }
        record = started
        return PlaybackSource.Format(PlaybackSource.SAMPLE_RATE, PlaybackSource.CHANNELS)
    }

    override fun read(pcm: ShortArray): Int = record?.read(pcm, 0, pcm.size) ?: -1

    override fun close() {
        val running = record ?: return
        record = null
        runCatching { running.stop() }
        running.release()
    }
}
