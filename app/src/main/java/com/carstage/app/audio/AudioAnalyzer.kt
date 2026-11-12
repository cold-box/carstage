package com.carstage.app.audio

import com.carstage.app.stage.AudioEvent
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

/**
 * Turns microphone frames into [AudioEvent]s for the stage. The music from the car's speakers is
 * in every frame, so loudness is measured as how far the voices stand out of the accompaniment:
 * the [Accompaniment] estimate splits each frame's spectrum, and the voice band (250 Hz to 4 kHz)
 * above that estimate is compared with the whole estimate. A ratio does not change with the music
 * volume or the microphone gain, and it settles within the estimate's 1.5 s history, so a louder
 * song or a new volume is not read as singing for long (docs/09). Voices in the recording itself
 * count as well; only the car's echo cancelling could tell them from the cabin (docs/09).
 *
 * Cheers are decided by [CheerDetector] once the estimate has a full history, the music's mood by
 * [MoodTracker] from the drum bands and the accompaniment's level, and the end of a song by
 * [SongEndDetector] from the gap after it. Runs on the audio thread; pure Kotlin, so it can be
 * tested with synthetic frames. Most numbers are first values from what the sounds are; those
 * chosen by comparing recordings or clips (the cheer's pitch and flatness conditions, the mood's
 * drive thresholds, the song end's 15 dB) say so in docs/09. All get checked with scripted
 * recordings (scripts/replay.sh).
 */
class AudioAnalyzer(
    private val sampleRate: Int,
    private val emit: (AudioEvent) -> Unit,
    /** True once digital silence has lasted [SILENT_NOTICE_FRAMES], false when sound comes back. */
    private val onSilence: (Boolean) -> Unit = {},
) {
    private val pitch = YinPitchDetector(sampleRate)
    private val spectrum = Spectrum(ANALYSIS_SIZE)
    private val accompaniment = Accompaniment(spectrum.bins)
    private val magnitudes = FloatArray(spectrum.bins)
    private val background = FloatArray(spectrum.bins)
    private val voiceBins = bins(VOICE_LOW_HZ, VOICE_HIGH_HZ)
    private val cheerDetector = CheerDetector()
    private val drumBands = DRUM_BANDS_HZ.map { (low, high) -> bins(low, high) }.filterNot { it.isEmpty() }
    private val drumLevels = FloatArray(drumBands.size)

    private val mood = MoodTracker(sampleRate.toFloat() / ANALYSIS_SIZE)
    private val songEnd = SongEndDetector(sampleRate.toFloat() / ANALYSIS_SIZE)

    /** Songs heard to end, for the log. */
    val songsEnded: Int get() = songEnd.songsEnded

    /** The music's moods so far, for the log. */
    fun moodsReported(feel: AudioEvent.Mood.Feel): Int = mood.timesReported(feel)
    val moodChanges: Int get() = mood.changes
    val beatsPerMinute: Double get() = mood.beatsPerMinute

    /** Counts since the start, for the log when the microphone stops. */
    var frames = 0
        private set
    var voicedFrames = 0
        private set

    /** Frames with the voices at least half the full range above the accompaniment, which is what makes the light bright. */
    var loudFrames = 0
        private set
    val cheers: Int get() = cheerDetector.cheers

    /** Frames of digital silence, and the longest run of them. */
    var silentFrames = 0
        private set
    var longestSilentRun = 0
        private set
    private var silentRun = 0

    /** Whether a long silence was reported and has not ended yet. */
    val isSilent: Boolean get() = silentRun >= SILENT_NOTICE_FRAMES
    var loudestDb = SignalAnalysis.SILENCE_DB
        private set

    /** The accompaniment's mean level over the frames heard so far, in dBFS; NaN before any sound. */
    val backgroundDb: Float get() = if (soundFrames == 0) Float.NaN else (backgroundDbSum / soundFrames).toFloat()
    private var backgroundDbSum = 0.0
    private var soundFrames = 0

    fun process(frame: FloatArray) {
        val db = SignalAnalysis.rmsDbfs(frame)
        frames++
        if (db < SILENT_FRAME_DB) {
            // Digital silence, not a quiet cabin: Android hands out zeros while another app (Gleo)
            // holds the microphone, and some drivers do right after starting. Counting it would
            // put silence into the accompaniment estimate and make the cabin read as loud after.
            silentFrames++
            longestSilentRun = maxOf(longestSilentRun, ++silentRun)
            if (silentRun == SILENT_NOTICE_FRAMES) onSilence(true)
            cheerDetector.interrupt()
            mood.interrupt()
            songEnd.interrupt()
            emit(AudioEvent.Frame(loudness = 0f, voiced = false))
            return
        }
        if (silentRun >= SILENT_NOTICE_FRAMES) onSilence(false)
        silentRun = 0

        // A short read is padded with silence and a long one cut, so the history stays one size.
        spectrum.magnitudes(if (frame.size == ANALYSIS_SIZE) frame else frame.copyOf(ANALYSIS_SIZE), magnitudes)
        accompaniment.estimate(magnitudes, background)
        var voiceEnergy = 0.0
        for (k in voiceBins) {
            val above = magnitudes[k] - background[k]
            if (above > 0) voiceEnergy += above.toDouble() * above
        }
        var backgroundEnergy = 0.0
        for (value in background) backgroundEnergy += value.toDouble() * value
        val ratioDb = decibels(voiceEnergy) - decibels(backgroundEnergy)
        for (i in drumBands.indices) {
            var energy = 0.0
            for (k in drumBands[i]) energy += magnitudes[k].toDouble() * magnitudes[k]
            drumLevels[i] = decibels(energy)
        }
        val moodNow = mood.process(drumLevels, decibels(backgroundEnergy))
        // Parseval: twice the one-sided bin energy over size × window energy is the mean power.
        val accompanimentDb = decibels(2 * backgroundEnergy / (ANALYSIS_SIZE * spectrum.windowEnergy))
        backgroundDbSum += accompanimentDb
        soundFrames++
        val songEnded = songEnd.process(db, accompanimentDb)

        // Pitch for the color only above the floor where singing was measured; for a cheer also
        // when the voices clearly stand out, since a ratio does not depend on how loud the cabin is.
        val mayCheer = ratioDb >= CheerDetector.OVER_ACCOMPANIMENT_DB
        val pitched = (db > VOICED_FLOOR_DB || mayCheer) && pitch.detect(frame) != null
        // Only a frame that may be a cheer needs it; a chord is not pitched but is tonal.
        val tonal = mayCheer && voiceBandFlatness() < CheerDetector.MIN_FLATNESS
        val voiced = pitched && db > VOICED_FLOOR_DB
        if (voiced) voicedFrames++
        loudestDb = maxOf(loudestDb, db)
        val loudness = ratioDb / VOICE_RANGE_DB
        if (loudness >= 0.5f) loudFrames++

        // Until the history is full the estimate rests on a few frames, so the voices' recent energy
        // starts only from a settled estimate (docs/09 section 5).
        val cheer = soundFrames > Accompaniment.HISTORY_FRAMES && cheerDetector.process(voiceEnergy, ratioDb, pitched, tonal)
        val music = (accompanimentDb - MUSIC_FLOOR_DB) / (MUSIC_FULL_DB - MUSIC_FLOOR_DB)
        emit(AudioEvent.Frame(loudness = loudness, voiced = voiced, music = music))
        if (cheer) emit(AudioEvent.Cheer)
        if (moodNow != null) emit(moodNow)
        if (songEnded) emit(AudioEvent.SongEnded)
    }

    /**
     * Spectral flatness of this frame's voice band, music included (call it after the frame's
     * spectrum): the geometric mean of the bins' power over their arithmetic mean. It is 1 only
     * when every bin holds the same power; white noise measures about 0.56 per frame, since each
     * bin's power scatters, and it is near 0 when a few peaks hold nearly everything.
     */
    private fun voiceBandFlatness(): Double {
        var logSum = 0.0
        var sum = 0.0
        for (k in voiceBins) {
            val power = magnitudes[k].toDouble() * magnitudes[k] + 1e-20
            logSum += ln(power)
            sum += power
        }
        val count = voiceBins.last - voiceBins.first + 1
        return exp(logSum / count) / (sum / count)
    }

    /** The bins from [lowHz] up to, not including, [highHz]. */
    private fun bins(lowHz: Float, highHz: Float): IntRange {
        val hzPerBin = sampleRate.toFloat() / ANALYSIS_SIZE
        return ceil(lowHz / hzPerBin).toInt()..minOf(ceil(highHz / hzPerBin).toInt() - 1, ANALYSIS_SIZE / 2)
    }

    private fun decibels(energy: Double): Float = (10 * log10(energy + 1e-12)).toFloat()

    companion object {
        /** Samples per spectrum, the microphone's usual frame. */
        const val ANALYSIS_SIZE = MicCapture.DEFAULT_FRAME_SIZE

        /** How far the voices above the accompaniment count as singing at full volume. */
        const val VOICE_RANGE_DB = 20f

        /**
         * The accompaniment's level that reads as no music and as music at full volume, for the
         * screen's song meter ([AudioEvent.Frame.music]): the quiet emulator cabin sat near
         * -58 dBFS and a car radio recording's music near -43 dBFS (docs/05), its loud parts higher.
         */
        const val MUSIC_FLOOR_DB = -60f
        const val MUSIC_FULL_DB = -25f

        /** Where voices carry most of their energy; bass and drums below and cymbals above stay out. */
        const val VOICE_LOW_HZ = 250f
        const val VOICE_HIGH_HZ = 4_000f

        /**
         * For the mood, the bands outside the voice band where drums sound: kick and bass, the
         * snare's body, and hi-hats, cymbals and the snare's crack. Voices reach in too, with low
         * fundamentals and hissing consonants: talk alone drove these bands harder than any drum
         * track tried, so only the beat's regularity keeps it from reading as lively (docs/09).
         */
        val DRUM_BANDS_HZ = listOf(40f to 120f, 120f to 250f, 4_000f to 6_000f, 6_000f to 9_000f, 9_000f to 12_000f)

        /** Below this a frame is digital silence; even the emulator's idle noise is about -73 dBFS. */
        const val SILENT_FRAME_DB = -80f

        /** About 3 s: longer than a short Gleo turn that borrows the microphone. */
        const val SILENT_NOTICE_FRAMES = 69

        /** Quieter frames do not count as sung; the Lab measured singing well above this. */
        const val VOICED_FLOOR_DB = -50f
    }
}
