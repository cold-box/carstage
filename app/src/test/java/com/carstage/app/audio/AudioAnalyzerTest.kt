package com.carstage.app.audio

import com.carstage.app.stage.AudioEvent
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnalyzerTest {
    private val rate = 48_000
    private val events = mutableListOf<AudioEvent>()
    private val silences = mutableListOf<Boolean>()
    private val analyzer = AudioAnalyzer(rate, emit = { events += it }, onSilence = { silences += it })
    private val random = Random(1)
    private var time = 0L

    /** Continuous across frames: a chord from bass to treble with a little noise, like music from the speakers. */
    private fun music(amplitude: Double) = FloatArray(2_048) {
        val t = (time + it).toDouble() / rate
        val chord = listOf(55.0, 110.0, 220.0, 277.2, 329.6, 440.0, 659.3, 880.0, 1_318.5, 2_637.0)
            .sumOf { hz -> sin(2 * PI * hz * t) / (1 + hz / 400) }
        (amplitude * (chord / 4 + (random.nextDouble() - 0.5) * 0.1)).toFloat()
    }

    /** A sung note with harmonics up to about 3 kHz. */
    private fun voice(hz: Double, amplitude: Double) = FloatArray(2_048) {
        val t = (time + it).toDouble() / rate
        (amplitude * (1..10).sumOf { k -> sin(2 * PI * hz * k * t) / k } / 3).toFloat()
    }

    /** Several voices shouting at once, with breath noise. */
    private fun cheer(amplitude: Double, fundamentals: List<Double> = listOf(196.0, 233.1, 277.2, 311.1), breath: Double = 0.3) = FloatArray(2_048) {
        val t = (time + it).toDouble() / rate
        val voices = fundamentals.sumOf { hz -> (1..12).sumOf { k -> sin(2 * PI * hz * k * t + k) / k } }
        (amplitude * (voices / 12 + (random.nextDouble() - 0.5) * breath)).toFloat()
    }

    /** A kick drum: a 60 Hz thump that dies away within about 60 ms (a tom or snare body at [hz]). */
    private fun kick(amplitude: Double, hz: Double = 60.0) = FloatArray(2_048) { (amplitude * sin(2 * PI * hz * it / rate) * exp(-it / 600.0)).toFloat() }

    /** A hi-hat: noise that dies away within about 20 ms. */
    private fun hat(amplitude: Double) = FloatArray(2_048) { (amplitude * (random.nextDouble() - 0.5) * exp(-it / 200.0)).toFloat() }

    /** [seconds] of a quiet chord under drums (kick, tom and hi-hat) on every beat at [bpm], at [volume], as in a mix the drums lead. */
    private fun drums(seconds: Int, bpm: Double = 120.0, volume: Double = 1.0) {
        val framesPerBeat = rate * 60 / bpm / 2_048
        repeat(seconds * rate / 2_048) {
            val n = time / 2_048
            val onBeat = (n / framesPerBeat).toInt() != ((n - 1) / framesPerBeat).toInt()
            feed(1) {
                if (onBeat) mix(music(0.01 * volume), kick(0.8 * volume), kick(0.5 * volume, hz = 180.0), hat(0.6 * volume)) else music(0.01 * volume)
            }
        }
    }

    private fun moods() = events.filterIsInstance<AudioEvent.Mood>()

    private fun mix(vararg parts: FloatArray) = FloatArray(2_048) { i -> parts.sumOf { it[i].toDouble() }.toFloat() }

    private fun frames() = events.filterIsInstance<AudioEvent.Frame>()

    /** Every frame fed, so a test can play the same sound to another analyzer. */
    private val recorded = mutableListOf<FloatArray>()

    private fun feed(count: Int, frame: () -> FloatArray) = repeat(count) {
        val next = frame()
        recorded += next
        analyzer.process(next)
        time += 2_048
    }

    @Test
    fun musicAloneStaysDarkFromTheFirstFrame() {
        feed(70) { music(0.1) }
        assertTrue(frames().all { it.level < 0.15f })
        assertTrue(events.none { it == AudioEvent.Cheer })
    }

    @Test
    fun singingAboveTheMusicIsLoudAndVoiced() {
        feed(46) { music(0.05) }
        events.clear()
        feed(20) { mix(music(0.05), voice(330.0, 0.3)) }
        assertTrue("a voice well above the music is bright", frames().drop(2).count { it.level > 0.6f } >= 16)
        assertTrue(frames().drop(2).count { it.voiced } >= 16)
    }

    @Test
    fun theLevelDoesNotDependOnTheVolumeOrTheMicrophoneGain() {
        val quiet = mutableListOf<Float>()
        val loud = mutableListOf<Float>()
        val a = AudioAnalyzer(rate, emit = { if (it is AudioEvent.Frame) quiet += it.level })
        val b = AudioAnalyzer(rate, emit = { if (it is AudioEvent.Frame) loud += it.level })
        repeat(80) { n ->
            val frame = if (n in 40..60) mix(music(0.01), voice(262.0, 0.05)) else music(0.01)
            a.process(frame)
            b.process(FloatArray(frame.size) { frame[it] * 10 })
            time += 2_048
        }
        quiet.zip(loud).forEach { (q, l) -> assertEquals(q, l, 0.01f) }
    }

    @Test
    fun turningTheMusicUpIsNotSingingForLong() {
        feed(46) { music(0.02) }
        events.clear()
        // Ten times louder at once. A noisy passage this sudden may read as one cheer (CheerDetector);
        // this chord is tonal, so it does not.
        feed(80) { music(0.2) }
        assertTrue("within 1.5 s the louder music is the accompaniment", frames().drop(35).all { it.level < 0.15f })
    }

    @Test
    fun turningTheKnobUpIsNotACheer() {
        feed(115) { music(0.02) }
        // 2 dB every half second, 20 dB in all.
        var amplitude = 0.02
        repeat(10) {
            amplitude *= 1.26
            feed(12) { music(amplitude) }
        }
        feed(46) { music(amplitude) }
        assertTrue(events.none { it == AudioEvent.Cheer })
        assertTrue(frames().takeLast(46).all { it.level < 0.15f })
    }

    @Test
    fun aCheerIsHeardOnceAndAnotherOnlyAfterTheCooldown() {
        feed(115) { music(0.05) }
        feed(23) { mix(music(0.05), cheer(0.8)) }
        feed(23) { music(0.05) }
        feed(23) { mix(music(0.05), cheer(0.8)) }
        assertEquals("the second one came within 4 s", 1, events.count { it == AudioEvent.Cheer })
        // The recent voice level (about 5 s) has to settle before a cheer stands out of it again.
        feed(230) { music(0.05) }
        feed(23) { mix(music(0.05), cheer(0.8)) }
        assertEquals(2, events.count { it == AudioEvent.Cheer })
        assertEquals(2, analyzer.cheers)
    }

    @Test
    fun whetherAShoutCountsDoesNotDependOnHowLongTheStageHasBeenOpen() {
        // Short bursts from the start (5 frames, too short for a cheer) set the voices' recent level.
        fun bursts(count: Int) = repeat(count) { n -> feed(1) { if (n % 7 < 5) mix(music(0.05), cheer(0.8)) else music(0.05) } }
        bursts(138)
        feed(12) { mix(music(0.05), cheer(0.8)) }
        assertEquals("a shout as loud as the bursts at about 6 s", 0, analyzer.cheers)
        bursts(322)
        feed(12) { mix(music(0.05), cheer(0.8)) }
        assertEquals("the same at about 20 s", 0, analyzer.cheers)
        feed(345) { music(0.05) }
        feed(12) { mix(music(0.05), cheer(0.8)) }
        assertEquals("after a quiet stretch it stands out", 1, analyzer.cheers)
    }

    @Test
    fun loudSingingIsNotACheer() {
        feed(115) { music(0.05) }
        // As loud above the music as the cheer, but sung: a clear pitch in every frame.
        feed(46) { mix(music(0.05), voice(330.0, 0.5), hiss(0.15)) }
        assertTrue(events.none { it == AudioEvent.Cheer })
    }

    @Test
    fun quietSingingInAQuietCabinIsNotACheerEither() {
        // About -57 dBFS: below the floor for counting sung frames, but its pitch still counts.
        feed(115) { music(0.0005) }
        feed(46) { mix(music(0.0005), voice(330.0, 0.005), hiss(0.0015)) }
        assertTrue(events.none { it == AudioEvent.Cheer })
    }

    @Test
    fun aCheerFromLowVoicesIsHeard() {
        feed(115) { music(0.05) }
        feed(23) { mix(music(0.05), cheer(0.8, listOf(110.0, 123.5, 138.6, 146.8))) }
        assertEquals(1, events.count { it == AudioEvent.Cheer })
    }

    /** A loud held chord (C7, six harmonics a note): YIN finds no pitch in it, but its voice band is tonal. */
    private fun chord(amplitude: Double) = FloatArray(2_048) {
        val t = (time + it).toDouble() / rate
        val notes = listOf(261.6, 329.6, 392.0, 466.2)
        (amplitude * notes.sumOf { hz -> (1..6).sumOf { k -> sin(2 * PI * hz * k * t) / k } } / 8).toFloat()
    }

    @Test
    fun aLoudChordIsNotACheer() {
        feed(115) { music(0.05) }
        feed(23) { mix(music(0.05), chord(0.8)) }
        assertTrue("the chord has no pitch, so only flatness keeps it out", frames().takeLast(23).none { it.voiced })
        assertTrue(events.none { it == AudioEvent.Cheer })
    }

    /** Noise spread evenly over the spectrum, [amplitude] peak to peak: breath, or the cabin's hiss. */
    private fun hiss(amplitude: Double) = FloatArray(2_048) { ((random.nextDouble() - 0.5) * amplitude).toFloat() }

    @Test
    fun aLoudChordOverHissIsStillNotACheer() {
        // About 26 dB of hiss under the chord: its voice band is flatter (about 0.006) but still a chord's.
        feed(115) { mix(music(0.05), hiss(0.03)) }
        feed(23) { mix(music(0.05), chord(0.8), hiss(0.03)) }
        assertTrue(events.none { it == AudioEvent.Cheer })
    }

    @Test
    fun voicesWhoopingWithoutBreathAreStillACheer() {
        // Dense harmonics with no breath between them, about 0.014 to 0.023: the flattest cheer the tests have.
        feed(115) { music(0.05) }
        feed(23) { mix(music(0.05), cheer(0.8, breath = 0.0)) }
        assertEquals(1, events.count { it == AudioEvent.Cheer })
    }

    @Test
    fun drumsAndOrdinarySingingAreNotCheers() {
        feed(46) { music(0.05) }
        repeat(20) {
            feed(1) { mix(music(0.05), kick(0.8)) }
            feed(10) { music(0.05) }
        }
        feed(60) { mix(music(0.05), voice(294.0, 0.08)) }
        assertTrue(events.none { it == AudioEvent.Cheer })
    }

    @Test
    fun drumsOnABeatAreLivelyAndAChordAloneIsCalm() {
        drums(12)
        assertEquals(AudioEvent.Mood.Feel.LIVELY, moods().last().feel)
        assertEquals(120.0, analyzer.beatsPerMinute, 6.0)
        val calm = AudioAnalyzer(rate, emit = { events += it })
        events.clear()
        repeat(12 * rate / 2_048) {
            calm.process(music(0.05))
            time += 2_048
        }
        assertTrue(moods().isNotEmpty() && moods().all { it.feel == AudioEvent.Mood.Feel.CALM })
    }

    @Test
    fun theFirstMoodComesAbout6sAfterTheFirstSound() {
        feed(140) { music(0.05) }
        assertTrue(moods().isEmpty())
        feed(1) { music(0.05) }
        assertEquals(1, moods().size)
    }

    @Test
    fun digitalSilenceNeitherCountsTowardsTheMoodNorDilutesIt() {
        feed(70) { music(0.05) }
        feed(100) { FloatArray(2_048) }
        feed(70) { music(0.05) }
        assertTrue("silence is not part of the first 6 s: ${moods()}", moods().isEmpty())
        feed(1) { music(0.05) }
        assertEquals(1, moods().size)
        assertTrue("a steady song sits at its quiet end: ${moods().last()}", moods().last().energy < 0.3f)
    }

    @Test
    fun theFirstFrameAfterDigitalSilenceIsNotADrumHit() {
        // The microphone drops out for a frame twice a beat and comes back 40 dB louder or quieter.
        repeat(24 * 23) { n ->
            feed(1) {
                when (n % 12) {
                    0, 6 -> FloatArray(2_048)
                    in 1..5 -> music(0.2)
                    else -> music(0.002)
                }
            }
        }
        assertTrue(moods().map { it.feel }.toString(), moods().isNotEmpty() && moods().none { it.feel == AudioEvent.Mood.Feel.LIVELY })
    }

    @Test
    fun drumsAt44kHzAreLivelyAtTheirTempo() {
        val other = AudioAnalyzer(44_100, emit = { events += it })
        val framesPerBeat = 44_100 * 60 / 120.0 / 2_048
        repeat(12 * 44_100 / 2_048) { n ->
            val onBeat = (n / framesPerBeat).toInt() != ((n - 1) / framesPerBeat).toInt()
            other.process(if (onBeat) mix(music(0.01), kick(0.8), kick(0.5, hz = 180.0), hat(0.6)) else music(0.01))
            time += 2_048
        }
        assertEquals(AudioEvent.Mood.Feel.LIVELY, moods().last().feel)
        assertEquals(120.0, other.beatsPerMinute, 6.0)
    }

    /** The cabin's own noise, about -65 dBFS: quiet, but not the digital silence of a borrowed microphone. */
    private fun cabin() = FloatArray(2_048) { ((random.nextDouble() - 0.5) * 0.002).toFloat() }

    @Test
    fun theGapAfterASongEndsIt() {
        feed(35 * 23) { music(0.05) }
        assertTrue(events.none { it == AudioEvent.SongEnded })
        // Frame by frame, stopping at the frame that ends the song.
        repeat(35) { if (events.none { it == AudioEvent.SongEnded }) feed(1) { cabin() } }
        assertEquals("once, about a second into the gap", 1, events.count { it == AudioEvent.SongEnded })
        assertEquals("the last event of its frame", AudioEvent.SongEnded, events.last())
        assertEquals(1, analyzer.songsEnded)
    }

    @Test
    fun aLongChatWithoutMusicIsNotASong() {
        // A minute of talk: phrases of about 2.5 s with a new syllable every 4 frames (a held note
        // would sink into the accompaniment estimate), and pauses of 1.3 s.
        val pitches = listOf(110.0, 135.0, 160.0, 190.0, 220.0, 150.0, 125.0, 175.0, 205.0, 240.0)
        var syllable = 0
        repeat(16) {
            repeat(15) {
                val hz = pitches[syllable++ % pitches.size]
                feed(4) { mix(voice(hz, 0.2), cabin()) }
            }
            feed(30) { cabin() }
        }
        assertTrue(events.none { it == AudioEvent.SongEnded })
    }

    @Test
    fun theMicrophoneGoingAwayAfterASongIsNotItsEnd() {
        feed(35 * 23) { music(0.05) }
        feed(46) { FloatArray(2_048) }
        assertTrue(events.none { it == AudioEvent.SongEnded })
    }

    @Test
    fun aMicrophoneDropoutInsideAGapBreaksIt() {
        feed(35 * 23) { music(0.05) }
        feed(12) { cabin() }
        feed(10) { FloatArray(2_048) }
        feed(12) { cabin() }
        feed(23) { music(0.05) }
        assertTrue("12 + 12 quiet frames with the microphone gone between", events.none { it == AudioEvent.SongEnded })
        feed(35) { cabin() }
        assertEquals("a whole second of the cabin still ends it", 1, events.count { it == AudioEvent.SongEnded })
    }

    @Test
    fun digitalSilenceIsNotPartOfTheSongsLength() {
        feed(20 * 23) { music(0.05) }
        feed(15 * 23) { FloatArray(2_048) }
        feed(23) { music(0.05) }
        feed(35) { cabin() }
        assertTrue("21 s of sound and 15 s of a borrowed microphone", events.none { it == AudioEvent.SongEnded })
        feed(11 * 23) { music(0.05) }
        feed(35) { cabin() }
        assertEquals(1, events.count { it == AudioEvent.SongEnded })
    }

    @Test
    fun theMoodDoesNotDependOnTheVolume() {
        val quiet = mutableListOf<AudioEvent.Mood>()
        val loud = mutableListOf<AudioEvent.Mood>()
        val a = AudioAnalyzer(rate, emit = { if (it is AudioEvent.Mood) quiet += it })
        val b = AudioAnalyzer(rate, emit = { if (it is AudioEvent.Mood) loud += it })
        drums(12)
        recorded.forEach { frame ->
            a.process(FloatArray(frame.size) { frame[it] * 0.1f })
            b.process(frame)
        }
        assertEquals(loud.map { it.feel }, quiet.map { it.feel })
        quiet.zip(loud).forEach { (q, l) -> assertEquals(l.energy, q.energy, 0.01f) }
        assertEquals(AudioEvent.Mood.Feel.LIVELY, quiet.last().feel)
    }

    @Test
    fun shortAndLongReadsAreAnalysedLikeFullFrames() {
        feed(46) { music(0.05) }
        feed(1) { music(0.05).copyOf(1_000) }
        feed(1) { music(0.05).copyOf(1_024) }
        feed(1) { music(0.05) + music(0.05) }
        assertEquals(49, analyzer.frames)
        feed(10) { mix(music(0.05), voice(330.0, 0.3)) }
        assertTrue("the history kept going", frames().takeLast(8).all { it.level > 0.6f })
    }

    @Test
    fun digitalSilenceIsLeftOutOfTheAccompaniment() {
        feed(40) { music(0.05) }
        feed(12) { FloatArray(2_048) }
        assertTrue("silent frames read as quiet", frames().takeLast(12).all { it.level == 0f })
        feed(23) { music(0.05) }
        assertTrue("the music is still the accompaniment at once", frames().takeLast(23).all { it.level < 0.15f })
        assertEquals(12, analyzer.silentFrames)
        assertTrue("a short gap is not reported", silences.isEmpty())
    }

    @Test
    fun aLongSilenceIsReportedOnceAndSoIsItsEnd() {
        feed(10) { music(0.05) }
        feed(AudioAnalyzer.SILENT_NOTICE_FRAMES + 20) { FloatArray(2_048) }
        assertEquals(listOf(true), silences)
        assertTrue(analyzer.isSilent)
        feed(1) { music(0.05) }
        assertEquals(listOf(true, false), silences)
        assertFalse(analyzer.isSilent)
        assertEquals(AudioAnalyzer.SILENT_NOTICE_FRAMES + 20, analyzer.longestSilentRun)
    }

    @Test
    fun theSummaryCountsWhatWasHeard() {
        feed(10) { music(0.05) }
        feed(10) { mix(music(0.05), voice(330.0, 0.3)) }
        assertEquals(20, analyzer.frames)
        assertTrue(analyzer.voicedFrames in 9..12)
        assertTrue(analyzer.loudFrames in 8..10)
        assertTrue(analyzer.loudestDb > -30f)
        assertTrue("the music's level, in dBFS", analyzer.backgroundDb in -45f..-25f)
    }
}
