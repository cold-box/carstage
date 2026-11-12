package com.carstage.app.stage

import com.carstage.app.score.Grade
import com.carstage.app.score.SongResult
import com.carstage.app.stage.AudioEvent.Mood.Feel
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowDirectorTest {
    /** The announcement's first frame of the winner's light. */
    private val crownFrame = DuelAnnouncement.DIM_FRAMES + DuelAnnouncement.ALTERNATING_FRAMES + DuelAnnouncement.HUSH_FRAMES

    private val director = GlowDirector()
    private val quiet = AudioEvent.Frame(loudness = 0f, voiced = false)
    private val loud = AudioEvent.Frame(loudness = 1f, voiced = true)
    private val sung = AudioEvent.Frame(loudness = 0.6f, voiced = true)

    private fun List<CabinCommand>.brightness() = filterIsInstance<CabinCommand.AmbientBrightness>().lastOrNull()?.level?.value

    private fun run(event: AudioEvent, times: Int) = (1..times).flatMap { director.direct(event) }

    private fun List<CabinCommand>.lights() = filterIsInstance<CabinCommand.ReadingLight>()

    @Test
    fun theFirstEventTurnsTheAmbientLightOnInTheMusicsColor() {
        val first = director.direct(quiet)
        assertEquals(CabinCommand.AmbientSwitch(true), first.first())
        assertEquals(GlowDirector.MIN_BRIGHTNESS, first.brightness())
        assertEquals(CabinCommand.AmbientColor(GlowDirector.hsv(ParticipationLayer.MUSIC_HUE, 0.9f)), first.last())
        assertTrue("the switch is sent once", director.direct(loud).none { it is CabinCommand.AmbientSwitch })
    }

    @Test
    fun louderSingingIsBrighterAndTheLightFallsBackSlowly() {
        director.direct(quiet)
        val up = run(loud, VoiceMode.ENTER_FRAMES + 2)
        assertTrue("rises within a few frames of the voice mode", up.brightness()!! >= 85)
        val down = run(quiet, 3)
        assertTrue("falls slowly", down.brightness()!! >= 60)
        val after = run(quiet, 60)
        assertEquals("the light comes all the way back down", GlowDirector.MIN_BRIGHTNESS, after.brightness())
    }

    @Test
    fun loudMusicWithoutASungVoiceLeavesTheLightToTheMusic() {
        director.direct(quiet)
        val unsung = AudioEvent.Frame(loudness = 1f, voiced = false)
        val soft = AudioEvent.Frame(loudness = VoiceMode.ENTER_LEVEL - 0.05f, voiced = true)
        val commands = run(unsung, 100) + run(soft, 100)
        assertTrue("no brighter than the floor: $commands", commands.none { it is CabinCommand.AmbientBrightness })
        assertTrue("still the music's colors: $commands", commands.none { it is CabinCommand.AmbientColor })
    }

    @Test
    fun smallChangesAreNotSent() {
        director.direct(quiet)
        assertTrue(run(quiet, 10).isEmpty())
        assertTrue(director.direct(AudioEvent.Frame(0.02f, false)).isEmpty())
    }

    @Test
    fun singingGlidesIntoTheVoicesColorsAndMovesWithinThem() {
        director.direct(quiet)
        assertTrue("a few sung frames are not a voice yet", run(sung, VoiceMode.ENTER_FRAMES - 1).none { it is CabinCommand.AmbientColor })
        val glide = run(sung, 46).filterIsInstance<CabinCommand.AmbientColor>()
        val glideHues = listOf(ParticipationLayer.MUSIC_HUE) + glide.map { hueOf(it.color) }
        assertTrue("it glides without a jump: $glideHues", glide.size >= 8 && glideHues.zipWithNext().all { (a, b) -> hueGap(a, b) <= 10f })
        assertTrue("through blue and violet, not round through green and red: $glideHues", glideHues.all { it in 175f..340f })
        val voice = ParticipationLayer.voicePaletteOf(Feel.MODERATE)
        assertTrue("it arrives within the voices' colors: $glideHues", glideHues.last() in voice.lowest - 1..voice.highest + 1)
        val hues = run(sung, 200).filterIsInstance<CabinCommand.AmbientColor>().map { hueOf(it.color) }
        assertTrue("magenta to pink: $hues", hues.all { it in voice.lowest - 1..voice.highest + 1 })
        assertTrue("both ends are reached: $hues", hues.any { it < voice.lowest + 4 } && hues.any { it > voice.highest - 4 })
    }

    @Test
    fun theVoiceModeHoldsThroughABreathThenGlidesBackToTheMusic() {
        director.direct(quiet)
        run(sung, 60)
        val held = run(quiet, VoiceMode.HOLD_FRAMES)
        assertTrue("a breath keeps the voices' colors: $held", held.none { it is CabinCommand.AmbientColor })
        val back = run(quiet, 60).filterIsInstance<CabinCommand.AmbientColor>()
        val hues = back.map { hueOf(it.color) }
        assertTrue("then back to the music without a jump: $hues", back.size >= 8 && hues.zipWithNext().all { (a, b) -> hueGap(a, b) <= 10f })
        val music = ParticipationLayer.musicPaletteOf(Feel.MODERATE)
        assertEquals("at the music's color for its energy, not short of it", music.center, hues.last(), 1f)
    }

    @Test
    fun theVoicesWinOverTheMusic() {
        director.direct(quiet)
        director.direct(AudioEvent.Mood(Feel.LIVELY, energy = 1f))
        val script = (0 until 300).map { if (it % 30 < 26) sung else quiet }
        val hues = script.flatMap { director.direct(it) }.filterIsInstance<CabinCommand.AmbientColor>().map { hueOf(it.color) }
        val voice = ParticipationLayer.voicePaletteOf(Feel.LIVELY)
        assertTrue("short gaps in the singing stay with the voices: $hues", hues.drop(hues.size / 2).all { it in voice.lowest - 1..voice.highest + 1 })
    }

    @Test
    fun theMusicsColorFollowsItsLoudnessAndFeel() {
        director.direct(quiet)
        director.direct(AudioEvent.Mood(Feel.CALM, energy = 0f))
        val calm = run(quiet, 60).filterIsInstance<CabinCommand.AmbientColor>().last().color
        assertEquals("the calm music's quiet end", ParticipationLayer.musicPaletteOf(Feel.CALM).lowest, hueOf(calm), 1f)
        assertEquals("softer", 0.8f, saturationOf(calm), 0.03f)
        director.direct(AudioEvent.Mood(Feel.LIVELY, energy = 1f))
        val lively = run(quiet, 60).filterIsInstance<CabinCommand.AmbientColor>()
        val hues = lively.map { hueOf(it.color) }
        assertTrue("no jump: $hues", hues.zipWithNext().all { (a, b) -> hueGap(a, b) <= 10f })
        assertEquals("the lively music's loud end", ParticipationLayer.musicPaletteOf(Feel.LIVELY).highest, hues.last(), 1f)
    }

    @Test
    fun theTwoModesColorsAreFarApartAndClearOfTheDuelAndFinaleColors() {
        val voices = Feel.entries.map(ParticipationLayer::voicePaletteOf)
        val music = Feel.entries.map(ParticipationLayer::musicPaletteOf)
        val others = listOf(DuelState.colorOf(Side.DRIVER), DuelState.colorOf(Side.PASSENGER), FinaleLayer.colorOf(com.carstage.app.score.Grade.GOOD))
            .map(::hueOf)
        for (v in voices) for (m in music) assertTrue("voices and music apart", hueGap(v.lowest, m.highest) >= 90f)
        for (palette in voices + music) for (hue in others) {
            assertTrue("$palette clear of $hue", listOf(palette.lowest, palette.highest).all { hueGap(it, hue) >= 20f })
            assertTrue("$palette does not contain $hue", ParticipationLayer.signedHueDistance(palette.center, hue).let { abs(it) > palette.halfWidth })
        }
    }

    @Test
    fun theMoodMakesTheLightBreatheWhenNobodySings() {
        run(quiet, 3)
        assertTrue("no breath before the first mood", run(quiet, 200).isEmpty())
        director.direct(AudioEvent.Mood(Feel.MODERATE, energy = 0f))
        val levels = breath(frames = (20 * GlowDirector.FRAMES_PER_SECOND).toInt())
        assertTrue("it rises above the voices' floor: $levels", levels.max() >= 36)
        assertTrue("and falls back towards it: $levels", levels.drop(70).min() <= 24)
        assertEquals("one breath every 6 s", 6f, period(levels), 0.6f)
    }

    @Test
    fun aLivelySongBreathesFasterAndACalmOneSlower() {
        director.direct(AudioEvent.Mood(Feel.LIVELY, energy = 0f))
        assertEquals("4 s breaths", 4f, period(breath(frames = (20 * GlowDirector.FRAMES_PER_SECOND).toInt())), 0.4f)
        val calm = GlowDirector()
        calm.direct(AudioEvent.Mood(Feel.CALM, energy = 0f))
        assertEquals("8 s breaths", 8f, period(breath(frames = (28 * GlowDirector.FRAMES_PER_SECOND).toInt(), of = calm)), 0.8f)
    }

    @Test
    fun aBreathLastsAnEvenNumberOfTheSongsBeats() {
        director.direct(AudioEvent.Mood(Feel.MODERATE, energy = 0f, beatsPerMinute = 100f))
        // 6 s is 10 beats at 100 BPM.
        assertEquals("10 beats", 6f, period(breath(frames = (24 * GlowDirector.FRAMES_PER_SECOND).toInt())), 0.6f)
        val lively = GlowDirector()
        lively.direct(AudioEvent.Mood(Feel.LIVELY, energy = 0f, beatsPerMinute = 70f))
        // 4 s is 4.7 beats at 70 BPM: 6 beats, about 5.1 s.
        assertEquals("6 beats", 5.14f, period(breath(frames = (24 * GlowDirector.FRAMES_PER_SECOND).toInt(), of = lively)), 0.5f)
        val unsure = GlowDirector()
        unsure.direct(AudioEvent.Mood(Feel.LIVELY, energy = 0f, beatsPerMinute = 400f))
        assertEquals("a tempo out of range keeps the feel's 4 s", 4f, period(breath(frames = (20 * GlowDirector.FRAMES_PER_SECOND).toInt(), of = unsure)), 0.4f)
    }

    @Test
    fun theFirstBreathGrowsFromTheFloor() {
        director.direct(quiet)
        director.direct(AudioEvent.Mood(Feel.LIVELY, energy = 1f))
        val levels = listOf(GlowDirector.MIN_BRIGHTNESS) + breath(frames = 150)
        assertTrue("in steps: $levels", levels.zipWithNext().all { (a, b) -> abs(a - b) <= GlowDirector.MIN_BRIGHTNESS_STEP + 1 })
        assertTrue("to the loud end's breath, 45 % ± 12: $levels", levels.max() >= 54)
    }

    @Test
    fun theLouderPartsOfTheSongBreatheHigher() {
        director.direct(AudioEvent.Mood(Feel.CALM, energy = 0f))
        val verse = breath(frames = 200)
        director.direct(AudioEvent.Mood(Feel.CALM, energy = 1f))
        val chorus = breath(frames = 200)
        assertTrue("$verse / $chorus", chorus.drop(40).average() > verse.drop(40).average() + 10)
        val steps = (verse + chorus).zipWithNext().map { (a, b) -> abs(a - b) }
        assertTrue("without a jump: $steps", steps.max() <= GlowDirector.MIN_BRIGHTNESS_STEP + 1)
    }

    @Test
    fun aNewFeelMidBreathDoesNotJump() {
        director.direct(AudioEvent.Mood(Feel.CALM, energy = 0f))
        // About the top of an 8 s breath, where a jump to another period or depth would show most.
        val calm = breath(frames = 47)
        director.direct(AudioEvent.Mood(Feel.LIVELY, energy = 0f))
        val lively = breath(frames = 100)
        val steps = (calm + lively).zipWithNext().map { (a, b) -> abs(a - b) }
        assertTrue("$calm / $lively", steps.max() <= GlowDirector.MIN_BRIGHTNESS_STEP + 1)
    }

    @Test
    fun energyOutsideItsRangeBreathesWithinTheLevels() {
        fun peak(energy: Float) = GlowDirector().let { other ->
            other.direct(AudioEvent.Mood(Feel.LIVELY, energy))
            breath(frames = 200, of = other).max()
        }
        assertEquals("NaN is the quiet end", peak(0f), peak(Float.NaN))
        assertEquals("below 0 is the quiet end", peak(0f), peak(-3f))
        assertEquals("above 1 is the loud end", peak(1f), peak(5f))
    }

    @Test
    fun aMoodThatRestatesTheFeelChangesNothing() {
        // The audio side repeats the mood every 2 s; between quiet stretches (where the breath
        // shows) and singing with a cheer, the light must be the same as without the repeats.
        val mood = AudioEvent.Mood(Feel.MODERATE, 0.5f)
        val restated = GlowDirector()
        director.direct(mood)
        restated.direct(mood)
        val script = (0 until 600).map { i -> if (i % 150 == 90) AudioEvent.Cheer else if (i % 150 < 40) sung else quiet }
        val plain = script.map { director.direct(it) }
        val withRepeats = script.mapIndexed { i, event ->
            if (i % 47 == 0) assertTrue("a restated mood sends nothing by itself", restated.direct(mood).isEmpty())
            restated.direct(event)
        }
        assertEquals(plain, withRepeats)
    }

    @Test
    fun theVoicesShineAboveTheBreath() {
        director.direct(AudioEvent.Mood(Feel.LIVELY, energy = 1f))
        run(quiet, 50)
        assertTrue("singing at full is brighter than any breath", run(loud, 10).brightness()!! >= 85)
    }

    /** The last brightness each director sent, so a breath carries on across calls. */
    private val lastSent = mutableMapOf<GlowDirector, Int>()

    /** The brightness after each of [frames] quiet frames: the last one [of] sent. */
    private fun breath(frames: Int, of: GlowDirector = director): List<Int> = (1..frames).map {
        of.direct(quiet).brightness()?.let { lastSent[of] = it }
        lastSent[of] ?: GlowDirector.MIN_BRIGHTNESS
    }

    /** Seconds between rises through the middle of [levels], the median after the first rise from the middle. */
    private fun period(levels: List<Int>): Float {
        val middle = (levels.max() + levels.min()) / 2f
        val rises = levels.indices.drop(20).filter { levels[it - 1] < middle && levels[it] >= middle }
        val gaps = rises.zipWithNext().map { (a, b) -> b - a }.sorted()
        assertTrue("at least two breaths: $levels", gaps.isNotEmpty())
        return gaps[gaps.size / 2] / GlowDirector.FRAMES_PER_SECOND
    }

    private fun saturationOf(color: Rgb): Float {
        val channels = channels(color)
        return (channels.max() - channels.min()) / channels.max().toFloat()
    }

    private fun channels(color: Rgb) = listOf(color.value shr 16 and 0xFF, color.value shr 8 and 0xFF, color.value and 0xFF)

    private fun hueOf(color: Rgb): Float {
        val r = (color.value shr 16 and 0xFF) / 255f
        val g = (color.value shr 8 and 0xFF) / 255f
        val b = (color.value and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0f) return 0f
        val h = when (max) {
            r -> 60 * ((g - b) / delta)
            g -> 60 * ((b - r) / delta + 2)
            else -> 60 * ((r - g) / delta + 4)
        }
        return (h + 360f) % 360f
    }

    private fun hueGap(a: Float, b: Float) = abs(a - b).let { minOf(it, 360f - it) }

    @Test
    fun readingLightsAreLeftAloneUntilTheFirstCheer() {
        // A passenger may be reading by one; before the first cheer the stage does not switch it off.
        assertTrue((director.direct(quiet) + run(loud, 30) + run(sung, 30)).lights().isEmpty())
    }

    @Test
    fun aCheerFlashesBothReadingLights() {
        director.direct(quiet)
        val on = director.direct(AudioEvent.Cheer)
        assertEquals(Side.entries.map { CabinCommand.ReadingLight(it, true) }, on.lights())
        assertTrue(run(quiet, GlowDirector.FLASH_FRAMES - 1).lights().isEmpty())
        assertEquals(Side.entries.map { CabinCommand.ReadingLight(it, false) }, director.direct(quiet).lights())
    }

    @Test
    fun aCheerDuringAFlashKeepsTheLightsOnLonger() {
        director.direct(quiet)
        director.direct(AudioEvent.Cheer)
        run(quiet, 3)
        assertTrue("already on, nothing to send", director.direct(AudioEvent.Cheer).lights().isEmpty())
        assertTrue(run(quiet, GlowDirector.FLASH_FRAMES - 1).lights().isEmpty())
        assertTrue(director.direct(quiet).contains(CabinCommand.ReadingLight(Side.DRIVER, false)))
    }

    @Test
    fun aCheerSwellsTheLightToFullThenItFallsBack() {
        run(quiet, 3)
        assertEquals("full at once", 100, director.direct(AudioEvent.Cheer).brightness())
        assertTrue("then it falls", director.direct(quiet).brightness()!! < 100)
        val after = run(quiet, 60)
        assertEquals(GlowDirector.MIN_BRIGHTNESS, after.brightness())
    }

    @Test
    fun aCheerDuringSingingSwellsAboveTheVoicesAndSettlesBackOnThem() {
        val singing = AudioEvent.Frame(loudness = 0.5f, voiced = true)
        val voices = run(singing, 40).brightness()!!
        assertEquals(100, director.direct(AudioEvent.Cheer).brightness())
        // The light as it stands after each frame (the last brightness sent).
        var current = 100
        val after = (1..80).map { director.direct(singing).brightness()?.let { current = it }; current }
        assertTrue("never below the voices: $after", after.all { it >= voices })
        assertTrue("about 0.65 s later still well above the voices: $after", after[14] > voices + 5)
        assertTrue("back near the voices: $after", after.last() <= voices + GlowDirector.MIN_BRIGHTNESS_STEP)
    }

    @Test
    fun whatTheSafetyRulesHeldBackIsSentAgain() {
        val first = director.direct(quiet)
        first.forEach(director::dropped)
        val again = director.direct(quiet)
        assertEquals("switch, brightness and color go out again", first, again)
        assertTrue(director.direct(quiet).isEmpty())
    }

    @Test
    fun readingLightsHeldBackFromSwitchingOffAreSwitchedOffAgain() {
        director.direct(quiet)
        director.direct(AudioEvent.Cheer)
        val off = run(quiet, GlowDirector.FLASH_FRAMES).lights()
        assertEquals(Side.entries.map { CabinCommand.ReadingLight(it, false) }, off)
        off.forEach(director::dropped)
        assertEquals(Side.entries.map { CabinCommand.ReadingLight(it, false) }, director.direct(quiet).lights())
        assertTrue(director.direct(quiet).lights().isEmpty())
    }

    /**
     * A song of [seconds] that scores the best grade with its [cheers] (without them it is good):
     * the cabin joins in over the recording's own level in three of every ten seconds, belting in
     * half of their frames (docs/09 "흥 점수 v2").
     */
    private fun bestSong(director: GlowDirector, seconds: Int = 40, cheers: Boolean = true) {
        val frames = (seconds * GlowDirector.FRAMES_PER_SECOND).toInt()
        repeat(frames) { n ->
            val joined = (n / GlowDirector.FRAMES_PER_SECOND).toInt() % 10 < 3
            val loudness = if (!joined) 0.15f else if (n % 2 == 0) 1.5f else 0.6f
            director.direct(AudioEvent.Frame(loudness = loudness, voiced = true))
            if (cheers && (n == frames / 3 || n == frames * 2 / 3)) director.direct(AudioEvent.Cheer)
        }
    }

    @Test
    fun theEndOfASongHandsOverItsScoreOnce() {
        val results = mutableListOf<SongResult>()
        val scored = GlowDirector(onResult = { results += it })
        bestSong(scored)
        assertTrue(results.isEmpty())
        scored.direct(AudioEvent.SongEnded)
        assertEquals(1, results.size)
        assertEquals(Grade.BEST, results.single().grade)
        scored.direct(quiet)
        assertEquals(1, results.size)
    }

    @Test
    fun theFinaleShowsTheGradesColorSwellsAndGivesTheLightBack() {
        val scored = GlowDirector()
        bestSong(scored)
        val start = scored.direct(AudioEvent.SongEnded)
        assertEquals(CabinCommand.AmbientColor(FinaleLayer.colorOf(Grade.BEST)), start.filterIsInstance<CabinCommand.AmbientColor>().single())
        assertEquals("full at once", 100, start.brightness())
        var current = 100
        val levels = (1 until FinaleLayer.FINALE_FRAMES).map { scored.direct(quiet).brightness()?.let { current = it }; current }
        assertTrue("down to half: $levels", levels.min() == FinaleLayer.LOWEST)
        // Down to half once a second: three swells in 3 s, far from three flashes a second.
        val dips = levels.indices.drop(1).filter { levels[it] == FinaleLayer.LOWEST && levels[it - 1] > FinaleLayer.LOWEST }
        assertEquals("$levels", 3, dips.size)
        assertTrue("$dips", dips.zipWithNext().all { (a, b) -> b - a >= GlowDirector.FRAMES_PER_SECOND - 1 })
        val back = scored.direct(quiet).filterIsInstance<CabinCommand.AmbientColor>()
        assertTrue("then the voices' color is back: $back", back.isNotEmpty() && back.none { it.color == FinaleLayer.colorOf(Grade.BEST) })
    }

    @Test
    fun onlyTheBestGradeFlashesTheReadingLights() {
        val best = GlowDirector()
        bestSong(best)
        assertEquals(Side.entries.map { CabinCommand.ReadingLight(it, true) }, best.direct(AudioEvent.SongEnded).lights())
        val later = (1..FinaleLayer.READING_FLASH_FRAMES).flatMap { best.direct(quiet).lights() }
        assertEquals(Side.entries.map { CabinCommand.ReadingLight(it, false) }, later)

        // The best grade before any cheer on the stage: a passenger's reading light is left alone.
        // The score now takes a cheer for the best grade (docs/09 "흥 점수 v2"), so only the layer
        // itself can be asked this.
        val noCheer = FinaleLayer()
        noCheer.start(Grade.BEST)
        assertTrue(noCheer.wants().lights().isEmpty())
        repeat(FinaleLayer.FINALE_FRAMES) {
            noCheer.hear(quiet)
            assertTrue(noCheer.wants().lights().isEmpty())
        }

        val basic = GlowDirector()
        repeat((40 * GlowDirector.FRAMES_PER_SECOND).toInt()) { basic.direct(quiet) }
        val end = basic.direct(AudioEvent.SongEnded)
        assertEquals(CabinCommand.AmbientColor(FinaleLayer.colorOf(Grade.BASIC)), end.filterIsInstance<CabinCommand.AmbientColor>().single())
        assertTrue(end.lights().isEmpty())
    }

    /** How many times the finale dips to half over its frames after [start]. */
    private fun finaleDips(director: GlowDirector, start: List<CabinCommand>): Int {
        var current = start.brightness() ?: -1
        val levels = (1 until FinaleLayer.FINALE_FRAMES).map { director.direct(quiet).brightness()?.let { current = it }; current }
        return levels.indices.drop(1).count { levels[it] == FinaleLayer.LOWEST && levels[it - 1] > FinaleLayer.LOWEST }
    }

    @Test
    fun everySongInAStageGetsTheWholeFinale() {
        val results = mutableListOf<SongResult>()
        val scored = GlowDirector(onResult = { results += it })
        bestSong(scored)
        assertEquals(3, finaleDips(scored, scored.direct(AudioEvent.SongEnded)))
        bestSong(scored)
        val second = scored.direct(AudioEvent.SongEnded)
        assertEquals(2, results.size)
        assertEquals("the second song's reading lights flash too", Side.entries.map { CabinCommand.ReadingLight(it, true) }, second.lights())
        assertEquals("the second song's finale swells three times as well", 3, finaleDips(scored, second))
    }

    @Test
    fun anEndRightAfterAnEndKeepsTheScoreAndTheFinale() {
        val results = mutableListOf<SongResult>()
        val scored = GlowDirector(onResult = { results += it })
        bestSong(scored)
        scored.direct(AudioEvent.SongEnded)
        repeat(10) { scored.direct(quiet) }
        val again = scored.direct(AudioEvent.SongEnded)
        assertEquals(1, results.size)
        assertTrue("$again", again.filterIsInstance<CabinCommand.AmbientColor>().none { it.color == FinaleLayer.colorOf(Grade.BASIC) })
    }

    @Test
    fun eachGradeHasItsOwnColor() {
        assertEquals(Grade.entries.size, Grade.entries.map(FinaleLayer::colorOf).toSet().size)
    }

    @Test
    fun onlyTheBestGradeTouchesTheReadingLights() {
        Grade.entries.forEach { grade ->
            val finale = FinaleLayer().apply {
                hear(AudioEvent.Cheer)
                start(grade)
            }
            assertEquals("$grade", grade == Grade.BEST, finale.wants().lights().isNotEmpty())
        }
    }

    @Test
    fun everyFinaleGivesACheerWindThatEndsAsItWas() {
        Grade.entries.forEach { grade ->
            val finale = FinaleLayer().apply { start(grade) }
            val wind = (0 until FinaleLayer.FINALE_FRAMES + 3).map { frame ->
                val fan = finale.wants().filterIsInstance<CabinCommand.FanStep>().singleOrNull()?.steps
                finale.hear(quiet)
                frame to fan
            }
            assertTrue("$grade: no wind in the first half second", wind.take(FinaleLayer.WIND_FROM_FRAMES).all { it.second == null })
            assertTrue(
                "$grade: one step up until two seconds",
                wind.subList(FinaleLayer.WIND_FROM_FRAMES, FinaleLayer.WIND_UNTIL_FRAMES).all { it.second == FinaleLayer.WIND_STEPS },
            )
            assertTrue("$grade: then no opinion, so the director brings it back", wind.drop(FinaleLayer.WIND_UNTIL_FRAMES).all { it.second == null })
        }
    }

    @Test
    fun theCheerWindReachesTheCarOnceUpAndOnceBack() {
        val director = GlowDirector()
        bestSong(director)
        val sent = director.direct(AudioEvent.SongEnded) + (0 until FinaleLayer.FINALE_FRAMES + 2).flatMap { director.direct(quiet) }
        assertEquals(
            "only the changes go out",
            listOf(CabinCommand.FanStep(FinaleLayer.WIND_STEPS), CabinCommand.FanStep(0)),
            sent.filterIsInstance<CabinCommand.FanStep>(),
        )
    }

    @Test
    fun aDefrosterOnForTheWholeFinaleLetsNoFanCommandOut() {
        val director = GlowDirector()
        // What StageController does with a gate that holds every climate command back.
        fun deliver(event: AudioEvent): List<CabinCommand> = director.direct(event).filter { command ->
            val allowed = command !is CabinCommand.FanStep && command !is CabinCommand.Temperature
            if (!allowed) director.dropped(command)
            allowed
        }
        bestSong(director)
        val out = deliver(AudioEvent.SongEnded) + (0 until FinaleLayer.FINALE_FRAMES + 50).flatMap { deliver(quiet) }
        assertTrue("neither the wind nor a \"back\" reaches the car", out.none { it is CabinCommand.FanStep })
    }

    @Test
    fun aSongTooShortToScoreHasNoFinale() {
        val results = mutableListOf<SongResult>()
        val scored = GlowDirector(onResult = { results += it })
        repeat(100) { scored.direct(sung) }
        assertTrue(scored.direct(AudioEvent.SongEnded).none { it is CabinCommand.AmbientColor })
        assertTrue(results.isEmpty())
    }

    @Test
    fun aDuelColorsEachTurnScoresBothAndAnnouncesTheWinner() {
        val results = mutableListOf<SongResult>()
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onResult = { results += it }, onDuel = { steps += it })
        duel.direct(quiet)
        duel.requestDuel(waitForSong = false)
        assertTrue("it starts on the audio thread's next event", steps.isEmpty())
        val first = duel.direct(quiet)
        assertEquals(DuelState.start(), steps.single())
        assertEquals("the driver's blue", CabinCommand.AmbientColor(DuelState.colorOf(Side.DRIVER)), first.filterIsInstance<CabinCommand.AmbientColor>().single())

        bestSong(duel)
        val driverEnd = duel.direct(AudioEvent.SongEnded)
        assertEquals(DuelState.Step.TURN to Side.PASSENGER, steps.last().step to steps.last().turn)
        assertEquals("the driver's turn ends with its finale", FinaleLayer.colorOf(Grade.BEST), driverEnd.filterIsInstance<CabinCommand.AmbientColor>().single().color)
        val afterFinale = (1..FinaleLayer.FINALE_FRAMES).flatMap { duel.direct(quiet) }.filterIsInstance<CabinCommand.AmbientColor>()
        assertEquals("then the passenger's red", DuelState.colorOf(Side.PASSENGER), afterFinale.last().color)

        quietFor(duel, 40)
        val passengerEnd = duel.direct(AudioEvent.SongEnded)
        assertEquals(DuelState.Step.ANNOUNCING, steps.last().step)
        assertEquals(Side.DRIVER, steps.last().winner)
        assertEquals("the announcement dims below the voices' floor", DuelAnnouncement.DIM_BRIGHTNESS, passengerEnd.brightness())
        val announced = steps.size
        var crownedAt = -1
        val show = (1..DuelAnnouncement.TOTAL_FRAMES).flatMap { n ->
            duel.direct(quiet).also { if (crownedAt < 0 && steps.last().winnerShown) crownedAt = n }
        }
        assertEquals(
            "the winner is told with the crown, then the announcement ends",
            listOf(DuelState.Step.ANNOUNCING to true, DuelState.Step.DONE to true),
            steps.drop(announced).map { it.step to it.winnerShown },
        )
        assertEquals("as the winner's light comes on", crownFrame, crownedAt)
        assertTrue("the driver's seat is ventilated", CabinCommand.SeatVentilation(Side.DRIVER, DuelAnnouncement.WINNER_VENTILATION) in show)
        assertTrue("the passenger's is left alone", show.none { it is CabinCommand.SeatVentilation && it.side == Side.PASSENGER })
        assertTrue("no purple for a win", CabinCommand.AmbientColor(DuelState.colorOf(null)) !in show)
        assertTrue("the duel's songs are its own, not solo results", results.isEmpty())
        val after = (1..30).flatMap { duel.direct(quiet) }
        assertTrue("the light is the voices' again", after.none { it is CabinCommand.AmbientBrightness && it.level.value == DuelAnnouncement.DIM_BRIGHTNESS })
        assertTrue("not the last turn's red", after.filterIsInstance<CabinCommand.AmbientColor>().none { it.color == DuelState.colorOf(Side.PASSENGER) })
    }

    private fun quietFor(director: GlowDirector, seconds: Int) = (1..(seconds * GlowDirector.FRAMES_PER_SECOND).toInt()).flatMap { director.direct(quiet) }

    /** Asks for a duel at once; the driver sings their best and the passenger stays quiet. Returns as the announcement starts. */
    private fun driverWins(director: GlowDirector, cheers: Boolean = true) {
        director.requestDuel(waitForSong = false)
        director.direct(quiet)
        bestSong(director, cheers = cheers)
        director.direct(AudioEvent.SongEnded)
        quietFor(director, 40)
        director.direct(AudioEvent.SongEnded)
    }

    @Test
    fun aDuelAskedForAtOnceMidSongForgetsTheSongSoFar() {
        val results = mutableListOf<SongResult>()
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onResult = { results += it }, onDuel = { steps += it })
        bestSong(duel, seconds = 30)
        duel.requestDuel(waitForSong = false)
        quietFor(duel, 40)
        duel.direct(AudioEvent.SongEnded)
        assertEquals(0, steps.last().scores.getValue(Side.DRIVER).participation)
        assertTrue(results.isEmpty())
    }

    @Test
    fun aDuelAskedForDuringASongWaitsForItAndScoresItOnItsOwn() {
        val results = mutableListOf<SongResult>()
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onResult = { results += it }, onDuel = { steps += it })
        bestSong(duel, seconds = 30)
        duel.requestDuel(waitForSong = true)
        val waiting = duel.direct(sung)
        assertEquals(listOf(DuelState.waiting()), steps)
        assertTrue("no turn color yet", waiting.filterIsInstance<CabinCommand.AmbientColor>().none { it.color == DuelState.colorOf(Side.DRIVER) })
        bestSong(duel, seconds = 10)
        val end = duel.direct(AudioEvent.SongEnded)
        assertEquals("the song it waited for is scored on its own", 1, results.size)
        assertEquals(DuelState.start(), steps.last())
        assertEquals("with its finale", FinaleLayer.colorOf(results.single().grade), end.filterIsInstance<CabinCommand.AmbientColor>().single().color)
        val turn = (1..FinaleLayer.FINALE_FRAMES).flatMap { duel.direct(quiet) }.filterIsInstance<CabinCommand.AmbientColor>()
        assertEquals("then the driver's blue", DuelState.colorOf(Side.DRIVER), turn.last().color)
        quietFor(duel, 40)
        duel.direct(AudioEvent.SongEnded)
        assertEquals("the driver's turn is the next song", 0, steps.last().scores.getValue(Side.DRIVER).participation)
    }

    @Test
    fun aFinaleComesWithApplauseForItsGradeAndADuelsAnnouncementWithADrumrollAndAFanfare() {
        val sounds = mutableListOf<SoundCue>()
        val solo = GlowDirector(onSound = { sounds += it })
        bestSong(solo, seconds = 5)
        solo.direct(AudioEvent.SongEnded)
        assertTrue("no finale, no applause", sounds.isEmpty())
        quietFor(solo, 40)
        solo.direct(AudioEvent.SongEnded)
        assertEquals("nobody sang: a short round", listOf(SoundCue.SHORT_APPLAUSE), sounds)
        bestSong(solo)
        solo.direct(AudioEvent.SongEnded)
        assertEquals("the best song: an ovation", listOf(SoundCue.SHORT_APPLAUSE, SoundCue.OVATION), sounds)

        sounds.clear()
        val duel = GlowDirector(onSound = { sounds += it })
        driverWins(duel)
        assertEquals("the driver's turn ended with its finale; the passenger's with the announcement", listOf(SoundCue.OVATION), sounds)
        repeat(DuelAnnouncement.TOTAL_FRAMES + 1) { duel.direct(quiet) }
        assertEquals(listOf(SoundCue.OVATION, SoundCue.DRUMROLL, SoundCue.FANFARE), sounds)
    }

    @Test
    fun theAnnouncementsDrumrollMovesBetweenTheSidesAndTheFanfareToTheWinner() {
        val events = mutableListOf<Any>()
        val duel = GlowDirector(onSound = { events += it }, onSoundChange = { events += it.pan })
        driverWins(duel)
        events.clear()
        repeat(DuelAnnouncement.TOTAL_FRAMES + 1) { duel.direct(quiet) }
        val alternate = DuelAnnouncement.RUNS.indices.map { if (it % 2 == 0) SoundPan.DRIVER else SoundPan.PASSENGER }
        assertEquals(
            "the drumroll starts, moves with each turn of the lights and back to the middle; the fanfare starts and moves to the winner",
            listOf(SoundCue.DRUMROLL) + alternate + listOf(SoundPan.CENTER, SoundCue.FANFARE, SoundPan.DRIVER),
            events,
        )
    }

    @Test
    fun theMcOpensTheAnnouncementAndCrownsTheWinnerWithTheirScore() {
        val lines = mutableListOf<String>()
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it }, onSpeak = { lines += it })
        driverWins(duel)
        assertEquals("the announcement opens as the passenger's song is scored", listOf(DuelLines.OPENING), lines)
        repeat(DuelAnnouncement.TOTAL_FRAMES + 1) { duel.direct(quiet) }
        val done = steps.last()
        val driverScore = done.scores.getValue(Side.DRIVER).score
        assertEquals(listOf(DuelLines.OPENING, DuelLines.crown(Side.DRIVER, driverScore)), lines)
        assertTrue("the line names the winner", lines.last().contains("운전석"))
    }

    @Test
    fun aDuelStartedOverDuringItsAnnouncementStopsItsSound() {
        var stops = 0
        val sounds = mutableListOf<SoundCue>()
        val duel = GlowDirector(onSound = { sounds += it }, onSoundStop = { stops++ })
        duel.requestDuel(waitForSong = false)
        duel.direct(quiet)
        assertEquals("no announcement was running", 0, stops)
        driverWins(duel)
        repeat(DuelAnnouncement.DIM_FRAMES + 5) { duel.direct(quiet) }
        assertEquals(SoundCue.DRUMROLL, sounds.last())
        duel.requestDuel(waitForSong = false)
        duel.direct(quiet)
        assertEquals("the drumroll stops", 1, stops)
        repeat(DuelAnnouncement.TOTAL_FRAMES) { duel.direct(quiet) }
        assertEquals("and no fanfare follows", SoundCue.DRUMROLL, sounds.last())
    }

    @Test
    fun everySongEndIsReportedScoredOrNot() {
        var ends = 0
        val results = mutableListOf<SongResult>()
        val director = GlowDirector(onResult = { results += it }, onSongEnded = { ends++ })
        bestSong(director, seconds = 5)
        director.direct(AudioEvent.SongEnded)
        assertEquals("too short to score, still an end", 1 to 0, ends to results.size)
        bestSong(director)
        director.direct(AudioEvent.SongEnded)
        assertEquals(2 to 1, ends to results.size)
        director.direct(quiet)
        assertEquals(2, ends)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aDuelRequestTellsHowTheDuelStarted() {
        val fresh = GlowDirector()
        val onOpen = fresh.requestDuel(waitForSong = true)
        assertFalse("not before the audio thread's next event", onOpen.isCompleted)
        fresh.direct(quiet)
        assertEquals("a stage just opened has no song to wait for", DuelState.start(), onOpen.getCompleted())

        val singing = GlowDirector()
        bestSong(singing, seconds = 30)
        val replaced = singing.requestDuel(waitForSong = false)
        val waiting = singing.requestDuel(waitForSong = true)
        singing.direct(sung)
        assertEquals(DuelState.waiting(), waiting.getCompleted())
        assertFalse("a request replaced before the next event never completes", replaced.isCompleted)
    }

    @Test
    fun aDuelAskedForEarlyInASongStartsAtOnce() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        bestSong(duel, seconds = 8)
        duel.requestDuel(waitForSong = true)
        duel.direct(quiet)
        assertEquals("too short to score, so nothing to wait for", listOf(DuelState.start()), steps)
    }

    @Test
    fun aDuelAskedForDuringATurnStartsOverAtOnce() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        duel.requestDuel(waitForSong = false)
        duel.direct(quiet)
        bestSong(duel)
        duel.direct(AudioEvent.SongEnded)
        bestSong(duel, seconds = 20)
        assertEquals(Side.PASSENGER, steps.last().turn)
        duel.requestDuel(waitForSong = true)
        duel.direct(quiet)
        assertEquals("the turn's song is not waited for", DuelState.start(), steps.last())
    }

    @Test
    fun aDuelAskedForDuringTheAnnouncementStopsIt() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        driverWins(duel)
        repeat(DuelAnnouncement.TOTAL_FRAMES - 10) { duel.direct(quiet) }
        duel.requestDuel(waitForSong = false)
        val stopped = duel.direct(quiet)
        assertEquals(DuelState.start(), steps.last())
        assertTrue("the winner's seat goes off", CabinCommand.SeatVentilation(Side.DRIVER, 0) in stopped)
        assertTrue("and their reading light", CabinCommand.ReadingLight(Side.DRIVER, false) in stopped)
        assertTrue("the light is the voices' again", stopped.brightness()!! < 100)
    }

    @Test
    fun aDuelAskedForDuringTheCrownIsCrownedAgainAtItsOwnWinnersLight() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        driverWins(duel)
        while (!steps.last().winnerShown) duel.direct(quiet)
        val restarted = steps.size
        driverWins(duel)
        assertEquals("the new duel is not born crowned", DuelState.start(), steps[restarted])
        val announced = steps.size
        var crownedAt = -1
        repeat(DuelAnnouncement.TOTAL_FRAMES) { n ->
            duel.direct(quiet)
            if (crownedAt < 0 && steps.last().winnerShown) crownedAt = n + 1
        }
        assertEquals(listOf(DuelState.Step.ANNOUNCING to true, DuelState.Step.DONE to true), steps.drop(announced).map { it.step to it.winnerShown })
        assertEquals("at its own winner's light", crownFrame, crownedAt)
    }

    @Test
    fun anEndHeldBackBySafetyIsSentAgain() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        driverWins(duel, cheers = false)
        var end = emptyList<CabinCommand>()
        while (steps.last().step != DuelState.Step.DONE) end = duel.direct(quiet)
        val held = end.filter { it is CabinCommand.SeatVentilation || it is CabinCommand.ReadingLight }
        assertEquals("what the crown turned on", setOf(CabinCommand.ReadingLight(Side.DRIVER, false), CabinCommand.SeatVentilation(Side.DRIVER, 0)), held.toSet())
        held.forEach(duel::dropped)
        val after = (1..10).flatMap { duel.direct(quiet) }
        assertTrue("$after", held.all { it in after })
    }

    @Test
    fun aSecondDuelInTheSameStageIsAnnouncedInFull() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        driverWins(duel)
        repeat(DuelAnnouncement.TOTAL_FRAMES + 1) { duel.direct(quiet) }
        driverWins(duel)
        repeat(DuelAnnouncement.TOTAL_FRAMES - 1) { duel.direct(quiet) }
        assertEquals("still announcing", DuelState.Step.ANNOUNCING, steps.last().step)
        repeat(2) { duel.direct(quiet) }
        assertEquals(DuelState.Step.DONE, steps.last().step)
    }

    @Test
    fun afterTheDuelSongsAreScoredOnTheirOwn() {
        val results = mutableListOf<SongResult>()
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onResult = { results += it }, onDuel = { steps += it })
        driverWins(duel)
        repeat(DuelAnnouncement.TOTAL_FRAMES + 1) { duel.direct(quiet) }
        val stepsAtDone = steps.size
        bestSong(duel)
        duel.direct(AudioEvent.SongEnded)
        assertEquals(1, results.size)
        assertEquals("the duel is over", stepsAtDone, steps.size)
    }

    @Test
    fun aTurnTooShortToScoreDoesNotPassTheTurn() {
        val steps = mutableListOf<DuelState>()
        val duel = GlowDirector(onDuel = { steps += it })
        duel.requestDuel(waitForSong = false)
        duel.direct(quiet)
        bestSong(duel, seconds = 8)
        duel.direct(AudioEvent.SongEnded)
        assertEquals(listOf(DuelState.start()), steps)
    }

    @Test
    fun hsvSoftensWithLessSaturation() {
        assertEquals(Rgb(0xFF8080), GlowDirector.hsv(0f, 0.5f))
        assertEquals(Rgb(0xFFFFFF), GlowDirector.hsv(200f, 0f))
        assertEquals(GlowDirector.hsv(120f), GlowDirector.hsv(120f, 1f))
    }

    @Test
    fun hsvCoversTheWheel() {
        assertEquals(Rgb(0xFF0000), GlowDirector.hsv(0f))
        assertEquals(Rgb(0x00FF00), GlowDirector.hsv(120f))
        assertEquals(Rgb(0x0000FF), GlowDirector.hsv(240f))
        assertEquals(Rgb(0xFF00FF), GlowDirector.hsv(300f))
        assertEquals(GlowDirector.hsv(10f), GlowDirector.hsv(370f))
    }

    @Test
    fun atNightEveryFrameIsTheDaysLightScaledIntoTheNightCeiling() {
        var night = true
        val byDay = GlowDirector()
        val steps = mutableListOf<DuelState.Step>()
        val atNight = GlowDirector(night = { night }, onDuel = { steps += it.step })
        // The light as it stands after each frame: the last brightness each sent.
        var day = -1
        var dim = -1
        var frames = 0
        fun hear(event: AudioEvent) {
            byDay.direct(event).forEach { if (it is CabinCommand.AmbientBrightness) day = it.level.value }
            atNight.direct(event).forEach { if (it is CabinCommand.AmbientBrightness) dim = it.level.value }
            val ceiling = Percent(if (night) GlowDirector.NIGHT_CEILING else 100)
            assertEquals("frame $frames: day $day", LayeredDirector.dimmed(CabinCommand.AmbientBrightness(Percent(day)), ceiling), CabinCommand.AmbientBrightness(Percent(dim)))
            frames++
        }
        listOf(byDay, atNight).forEach { it.requestDuel(waitForSong = false) }
        hear(quiet)
        val song = (40 * GlowDirector.FRAMES_PER_SECOND).toInt()
        repeat(song) { n ->
            val chorus = (n / GlowDirector.FRAMES_PER_SECOND).toInt() / 5 % 2 == 0
            hear(AudioEvent.Frame(loudness = if (chorus) 0.9f else 0.4f, voiced = true))
            if (n % 46 == 0) hear(AudioEvent.Mood(if (chorus) Feel.LIVELY else Feel.MODERATE, energy = if (chorus) 0.9f else 0.3f))
            if (n == song / 3) hear(AudioEvent.Cheer)
        }
        hear(AudioEvent.SongEnded)
        repeat((40 * GlowDirector.FRAMES_PER_SECOND).toInt()) { hear(quiet) }
        hear(AudioEvent.SongEnded)
        repeat(DuelAnnouncement.TOTAL_FRAMES) { hear(quiet) }
        assertEquals("the whole duel, its announcement too", listOf(DuelState.Step.ANNOUNCING, DuelState.Step.DONE), steps.takeLast(2))
        night = false
        hear(AudioEvent.Cheer)
        assertEquals("by day the light is the day's again at once", 100, dim)
    }
}
