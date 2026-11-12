package com.carstage.app.stage

import com.carstage.app.score.SongResult
import com.carstage.app.stage.DuelState.Step
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelTest {
    private fun result(score: Int) = SongResult(participation = score, heat = 0, response = 0, completion = 0, seconds = 60)

    @Test
    fun theDriverSingsThenThePassengerThenTheResultIsAnnounced() {
        val start = DuelState.start()
        assertEquals(DuelState(Step.TURN, Side.DRIVER, emptyMap()), start)
        val second = start.scored(result(40))
        assertEquals(DuelState(Step.TURN, Side.PASSENGER, mapOf(Side.DRIVER to result(40))), second)
        assertNull("no winner before both sang", second.winner)
        val announcing = second.scored(result(45))
        assertEquals(Step.ANNOUNCING, announcing.step)
        assertEquals(Side.PASSENGER, announcing.winner)
        assertEquals(Step.DONE, announcing.announced().step)
        assertEquals("a song after the turns is not part of it", announcing, announcing.scored(result(50)))
        assertEquals("only an announcement ends", start, start.announced())
    }

    @Test
    fun theCrownTellsTheWinnerBeforeTheAnnouncementEnds() {
        val announcing = DuelState.start().scored(result(40)).scored(result(45))
        assertFalse("not before the crown", announcing.winnerShown)
        val crowned = announcing.crowned()
        assertEquals(Step.ANNOUNCING, crowned.step)
        assertTrue(crowned.winnerShown)
        assertEquals(Step.DONE to true, crowned.announced().let { it.step to it.winnerShown })
        assertEquals("an announcement that ends unseen is told too", crowned.announced(), announcing.announced())
        assertTrue("a result is always told", DuelState(Step.DONE, Side.PASSENGER, announcing.scores).winnerShown)
        assertEquals("only an announcement is crowned", DuelState.start(), DuelState.start().crowned())
        assertTrue("a stage closed during it tells it", announcing.closed()!!.winnerShown)
    }

    @Test
    fun aDuelWaitingForTheSongStartsWithTheDriverWhenItEnds() {
        val waiting = DuelState.waiting()
        assertEquals("the song it waits for is not a turn", waiting, waiting.scored(result(70)))
        assertEquals(DuelState.start(), waiting.begun())
        assertEquals("only a waiting duel begins", DuelState.start().scored(result(40)), DuelState.start().scored(result(40)).begun())
    }

    @Test
    fun closingTheStageEndsTheTurnsAndKeepsTheResult() {
        assertNull(DuelState.waiting().closed())
        assertNull(DuelState.start().scored(result(40)).closed())
        val announcing = DuelState.start().scored(result(40)).scored(result(30))
        assertEquals("the result shows at once", announcing.announced(), announcing.closed())
        assertEquals(announcing.announced(), announcing.announced().closed())
    }

    @Test
    fun aHigherDriverWinsAndEqualScoresTie() {
        assertEquals(Side.DRIVER, DuelState.start().scored(result(48)).scored(result(30)).winner)
        assertNull(DuelState.start().scored(result(30)).scored(result(30)).winner)
    }

    @Test
    fun eachSideAndATieHaveTheirOwnColor() {
        assertEquals(3, listOf(Side.DRIVER, Side.PASSENGER, null).map(DuelState::colorOf).toSet().size)
    }

    @Test
    fun theTurnColorIsOnlyForTurns() {
        var state: DuelState? = null
        val layer = DuelTurnLayer { state }
        assertTrue(layer.wants().isEmpty())
        state = DuelState.waiting()
        assertTrue("waiting", layer.wants().isEmpty())
        state = DuelState.start()
        assertEquals(listOf(CabinCommand.AmbientColor(DuelState.colorOf(Side.DRIVER))), layer.wants())
        state = state.scored(result(40))
        assertEquals(listOf(CabinCommand.AmbientColor(DuelState.colorOf(Side.PASSENGER))), layer.wants())
        state = state.scored(result(30))
        assertTrue("announcing", layer.wants().isEmpty())
        state = state.announced()
        assertTrue("done", layer.wants().isEmpty())
    }

    private val fps = GlowDirector.FRAMES_PER_SECOND
    private val frame = AudioEvent.Frame(loudness = 0f, voiced = false)
    private val crownFrame = DuelAnnouncement.DIM_FRAMES + DuelAnnouncement.ALTERNATING_FRAMES + DuelAnnouncement.HUSH_FRAMES

    /** What [show] wants on each frame from its start to its end frame: [DuelAnnouncement.TOTAL_FRAMES] + 1 lists. */
    private fun play(show: DuelAnnouncement, winner: Side?): List<List<CabinCommand>> {
        show.start(winner)
        val frames = mutableListOf(show.wants())
        repeat(DuelAnnouncement.TOTAL_FRAMES) {
            show.hear(frame)
            frames += show.wants()
        }
        return frames
    }

    private fun play(winner: Side?) = play(DuelAnnouncement(), winner)

    /** How many frames each side stays lit while the sides take turns. */
    private fun runs(frames: List<List<CabinCommand>>): List<Int> {
        val alternating = frames.drop(DuelAnnouncement.DIM_FRAMES).take(DuelAnnouncement.ALTERNATING_FRAMES).map { it.lit().single() }
        val runs = mutableListOf<Int>()
        alternating.forEachIndexed { i, side -> if (i > 0 && side == alternating[i - 1]) runs[runs.lastIndex]++ else runs += 1 }
        return runs
    }

    private fun List<CabinCommand>.brightness() = filterIsInstance<CabinCommand.AmbientBrightness>().singleOrNull()?.level?.value
    private fun List<CabinCommand>.color() = filterIsInstance<CabinCommand.AmbientColor>().singleOrNull()?.color
    private fun List<CabinCommand>.lit() = filterIsInstance<CabinCommand.ReadingLight>().filter { it.on }.map { it.side }
    private fun List<CabinCommand>.ventilated() = filterIsInstance<CabinCommand.SeatVentilation>().filter { it.level > 0 }.map { it.side }

    @Test
    fun theAnnouncementDimsAlternatesHushesAndCrownsTheWinner() {
        val frames = play(Side.PASSENGER)
        assertTrue("dim", frames.take(DuelAnnouncement.DIM_FRAMES).all { it.brightness() == DuelAnnouncement.DIM_BRIGHTNESS && it.lit().isEmpty() })
        val alternating = frames.drop(DuelAnnouncement.DIM_FRAMES).take(DuelAnnouncement.ALTERNATING_FRAMES)
        assertTrue("one side lit at a time, in its color", alternating.all { f -> f.lit().size == 1 && f.color() == DuelState.colorOf(f.lit().single()) })
        assertEquals("the driver first", Side.DRIVER, alternating.first().lit().single())
        val hush = frames.drop(DuelAnnouncement.DIM_FRAMES + DuelAnnouncement.ALTERNATING_FRAMES).take(DuelAnnouncement.HUSH_FRAMES)
        assertTrue("hush", hush.all { it.brightness() == DuelAnnouncement.DIM_BRIGHTNESS && it.lit().isEmpty() })
        val crowned = frames.drop(crownFrame).take(DuelAnnouncement.WINNER_FRAMES)
        assertTrue("crowned", crowned.all { it.brightness() == 100 && it.color() == DuelState.colorOf(Side.PASSENGER) && it.lit() == listOf(Side.PASSENGER) })
        assertEquals(listOf(Side.PASSENGER), crowned.first().ventilated())
        assertTrue("dimmer than the voices ever go", DuelAnnouncement.DIM_BRIGHTNESS < GlowDirector.MIN_BRIGHTNESS)
    }

    @Test
    fun theShowLastsAboutTenSecondsInItsFourParts() {
        val parts = mutableListOf<Pair<Int?, Int>>()
        play(Side.PASSENGER).take(DuelAnnouncement.TOTAL_FRAMES).map { it.brightness() }.forEach { level ->
            if (parts.isNotEmpty() && parts.last().first == level) parts[parts.lastIndex] = level to parts.last().second + 1 else parts += level to 1
        }
        assertEquals(listOf(DuelAnnouncement.DIM_BRIGHTNESS, DuelAnnouncement.ALTERNATING_BRIGHTNESS, DuelAnnouncement.DIM_BRIGHTNESS, 100), parts.map { it.first })
        listOf(1f, 4f, 1f, 4f).zip(parts).forEach { (seconds, part) -> assertEquals("$parts", seconds, part.second / fps, 0.05f) }
    }

    // Photosensitivity, measured in seconds rather than through the constants that set it.

    @Test
    fun eachSideComesBackNoSoonerThanSevenTenthsOfASecond() {
        val lit = play(Side.DRIVER).map { it.lit().singleOrNull() }
        Side.entries.forEach { side ->
            val onsets = lit.indices.filter { lit[it] == side && (it == 0 || lit[it - 1] != side) }
            assertTrue("$side lit at $onsets", onsets.zipWithNext().all { (a, b) -> (b - a) / fps >= 0.7f })
        }
    }

    @Test
    fun noSideIsLitForLessThanAThirdOfASecondEvenLast() {
        val runs = runs(play(Side.DRIVER))
        assertTrue("$runs", runs.all { it / fps >= 1f / 3 })
    }

    @Test
    fun theTurnsStartAtASecondAndGetAtLeastTwiceAsFast() {
        val runs = runs(play(Side.DRIVER))
        assertEquals("$runs", 1f, runs.first() / fps, 0.05f)
        assertTrue("faster and faster: $runs", runs.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("$runs", runs.min() * 2 <= runs.first())
    }

    // Crowning and letting go.

    @Test
    fun aDriverWinIsCrownedOnTheDriversSideAndLeavesThePassengersSeat() {
        val frames = play(Side.DRIVER)
        val crowned = frames[crownFrame]
        assertEquals(DuelState.colorOf(Side.DRIVER), crowned.color())
        assertEquals(listOf(Side.DRIVER), crowned.lit())
        assertEquals(listOf(CabinCommand.SeatVentilation(Side.DRIVER, DuelAnnouncement.WINNER_VENTILATION)), crowned.filterIsInstance<CabinCommand.SeatVentilation>())
        assertTrue("the passenger's seat is never touched", frames.flatten().none { it is CabinCommand.SeatVentilation && it.side == Side.PASSENGER })
        assertEquals(
            "a cool celebration on the winner's side",
            listOf(CabinCommand.Temperature(Side.DRIVER, DuelAnnouncement.WINNER_COOLING)),
            crowned.filterIsInstance<CabinCommand.Temperature>(),
        )
        assertTrue("the passenger's temperature is never touched", frames.flatten().none { it is CabinCommand.Temperature && it.side == Side.PASSENGER })
        assertTrue("cooler, not warmer", DuelAnnouncement.WINNER_COOLING < 0f)
    }

    @Test
    fun aTieCrownsBothInPurple() {
        val crowned = play(null)[DuelAnnouncement.TOTAL_FRAMES - 1]
        assertEquals(DuelState.colorOf(null), crowned.color())
        assertEquals(Side.entries, crowned.lit())
        assertEquals(Side.entries, crowned.ventilated())
        assertEquals(Side.entries.map { CabinCommand.Temperature(it, DuelAnnouncement.WINNER_COOLING) }, crowned.filterIsInstance<CabinCommand.Temperature>())
    }

    @Test
    fun theEndTurnsOffBothReadingLightsAndTheWinnersSeat() {
        assertEquals(
            setOf(
                CabinCommand.ReadingLight(Side.DRIVER, false),
                CabinCommand.ReadingLight(Side.PASSENGER, false),
                CabinCommand.SeatVentilation(Side.DRIVER, 0),
                CabinCommand.Temperature(Side.DRIVER, 0f),
            ),
            play(Side.DRIVER).last().toSet(),
        )
        assertEquals(
            "a tie turned on both seats",
            Side.entries.flatMap { listOf(CabinCommand.ReadingLight(it, false), CabinCommand.SeatVentilation(it, 0), CabinCommand.Temperature(it, 0f)) }.toSet(),
            play(null).last().toSet(),
        )
    }

    @Test
    fun theEndIsWantedForAWhileThenLetGo() {
        val show = DuelAnnouncement()
        assertTrue("nothing to announce yet", show.wants().isEmpty())
        val end = play(show, Side.DRIVER).last()
        repeat(DuelAnnouncement.END_FRAMES - 1) {
            show.hear(frame)
            assertEquals("frame ${it + 1} after the end", end, show.wants())
        }
        show.hear(frame)
        assertTrue("then no opinion", show.wants().isEmpty())
    }

    @Test
    fun theShowIsCrownedFromTheWinnersLightUntilItEnds() {
        val show = DuelAnnouncement()
        show.start(Side.PASSENGER)
        val crowned = (1..DuelAnnouncement.TOTAL_FRAMES + 1).filter {
            show.hear(frame)
            show.crowned
        }
        assertEquals("from the crown's first frame to the last", (crownFrame until DuelAnnouncement.TOTAL_FRAMES).toList(), crowned)
        show.start(Side.DRIVER)
        assertFalse("a new show starts uncrowned", show.crowned)
        repeat(crownFrame) { show.hear(frame) }
        assertTrue(show.crowned)
        show.stop()
        assertFalse("a stopped show is not", show.crowned)
    }

    @Test
    fun theShowIsNotFinishedBeforeItsLastFrame() {
        val show = DuelAnnouncement()
        show.start(Side.DRIVER)
        repeat(DuelAnnouncement.TOTAL_FRAMES - 1) {
            show.hear(frame)
            show.wants()
            assertFalse("frame ${it + 1}", show.finished)
        }
        show.hear(frame)
        show.wants()
        assertTrue(show.finished)
    }

    @Test
    fun aSecondShowPlaysInFull() {
        val show = DuelAnnouncement()
        val first = play(show, Side.DRIVER)
        assertEquals(first, play(show, Side.DRIVER))
        repeat(3) { show.hear(frame) }
        assertEquals("one started during the last one's end", first, play(show, Side.DRIVER))
    }

    @Test
    fun aStoppedShowTurnsOffWhatItTurnedOn() {
        val alternating = DuelAnnouncement()
        alternating.start(Side.DRIVER)
        repeat(DuelAnnouncement.DIM_FRAMES + 2) { alternating.hear(frame) }
        assertEquals(listOf(Side.DRIVER), alternating.wants().lit())
        assertTrue("it was running", alternating.stop())
        assertTrue(alternating.finished)
        assertEquals("no seat was ventilated yet", Side.entries.map { CabinCommand.ReadingLight(it, false) }, alternating.wants())

        val crowning = DuelAnnouncement()
        crowning.start(Side.PASSENGER)
        repeat(crownFrame + 2) { crowning.hear(frame) }
        crowning.wants()
        crowning.stop()
        assertTrue(CabinCommand.SeatVentilation(Side.PASSENGER, 0) in crowning.wants())

        val idle = DuelAnnouncement()
        assertFalse("nothing was running", idle.stop())
        assertTrue("nothing ran, nothing to turn off", idle.wants().isEmpty())
    }

    @Test
    fun theDrumrollStartsWithTheTurnsAndTheFanfareWithTheCrownOnce() {
        val show = DuelAnnouncement()
        show.start(Side.PASSENGER)
        val cues = mutableMapOf<Int, SoundCue>()
        repeat(DuelAnnouncement.TOTAL_FRAMES + DuelAnnouncement.END_FRAMES) { n ->
            show.hear(frame)
            show.takeCue()?.let { cues[n + 1] = it }
        }
        assertEquals(mapOf(DuelAnnouncement.DIM_FRAMES to SoundCue.DRUMROLL, crownFrame to SoundCue.FANFARE), cues)
        assertNull("taken once", show.takeCue())
        val drumroll = DuelAnnouncement.ALTERNATING_FRAMES / fps
        assertEquals("the drumroll file is about as long as the turns", 4f, drumroll, 0.1f)
    }

    /** The sound changes [show] asks for, by frame, over a whole announcement for [winner]; and which side each frame lit. */
    private fun changes(show: DuelAnnouncement, winner: Side?): Pair<Map<Int, SoundChange>, Map<Int, Side>> {
        show.start(winner)
        val lit = mutableMapOf<Int, Side>()
        val changes = mutableMapOf<Int, SoundChange>()
        repeat(DuelAnnouncement.TOTAL_FRAMES + DuelAnnouncement.END_FRAMES) { n ->
            show.hear(frame)
            show.wants().lit().singleOrNull()?.let { lit[n + 1] = it }
            show.takeChange()?.let { changes[n + 1] = it }
        }
        return changes to lit
    }

    @Test
    fun theDrumrollTakesTurnsWithTheReadingLightsFasterAndFasterAndTheFanfareCrownsTheWinnersSide() {
        val (changes, lit) = changes(DuelAnnouncement(), Side.PASSENGER)
        val alternating = DuelAnnouncement.DIM_FRAMES until DuelAnnouncement.DIM_FRAMES + DuelAnnouncement.ALTERNATING_FRAMES
        val turns = alternating.filter { it == alternating.first || lit[it] != lit[it - 1] }
        val hush = alternating.last + 1
        assertEquals(
            "toward the side lit as each turn starts, both sides for the hush, the winner's side with the crown",
            turns.mapIndexed { i, at -> at to SoundChange(SoundPan.toward(lit.getValue(at)), DuelAnnouncement.DRUMROLL_RATES[i]) }.toMap() +
                (hush to SoundChange(SoundPan.CENTER, DuelAnnouncement.FASTEST_DRUMROLL)) + (crownFrame to SoundChange(SoundPan.PASSENGER)),
            changes,
        )
        assertEquals("one change per turn", DuelAnnouncement.RUNS.size, turns.size)
        assertEquals("the drumroll starts on the driver's side as recorded", SoundChange(SoundPan.DRIVER, 1f), changes[DuelAnnouncement.DIM_FRAMES])
    }

    @Test
    fun theDrumrollSpeedsUpEvenlyToItsFastest() {
        val rates = DuelAnnouncement.DRUMROLL_RATES
        assertEquals(DuelAnnouncement.RUNS.size, rates.size)
        assertEquals(1f, rates.first(), 1e-6f)
        assertEquals(DuelAnnouncement.FASTEST_DRUMROLL, rates.last(), 1e-6f)
        val step = (DuelAnnouncement.FASTEST_DRUMROLL - 1f) / (rates.size - 1)
        assertTrue("in even steps", rates.zipWithNext().all { (a, b) -> abs(b - a - step) < 1e-5f })
    }

    @Test
    fun theSpedUpDrumrollLastsIntoTheLastTurn() {
        // sfx_drumroll.ogg is 3.99 s long (ffprobe); played faster, it ends sooner.
        val recording = 3.98
        var played = 0.0
        var at = 0.0
        var ends = Double.NaN
        DuelAnnouncement.RUNS.forEachIndexed { i, run ->
            val seconds = run / fps.toDouble()
            val rate = DuelAnnouncement.DRUMROLL_RATES[i].toDouble()
            if (ends.isNaN() && played + seconds * rate >= recording) ends = at + (recording - played) / rate
            played += seconds * rate
            at += seconds
        }
        val lastTurn = (DuelAnnouncement.ALTERNATING_FRAMES - DuelAnnouncement.RUNS.last()) / fps.toDouble()
        val hush = DuelAnnouncement.ALTERNATING_FRAMES / fps.toDouble()
        assertTrue("it plays into the last turn (ends at $ends s, last turn from $lastTurn s)", ends > lastTurn + 0.15)
        assertTrue("and ends at most a fifth of a second before the hush ($hush s)", hush - ends <= 0.2)
    }

    @Test
    fun aTiesFanfareStaysInTheMiddle() {
        val (changes, _) = changes(DuelAnnouncement(), winner = null)
        assertNull("nothing moves it with the crown", changes[crownFrame])
        assertEquals("the last change brings the drumroll back to the middle", SoundPan.CENTER, changes.values.last().pan)
    }

    @Test
    fun theMcOpensAsTheShowStartsAndCrownsTheWinnerOnce() {
        val show = DuelAnnouncement()
        show.start(Side.DRIVER)
        assertEquals("the opening as the light dims", DuelAnnouncement.Line.OPENING, show.takeLine())
        assertNull("once", show.takeLine())
        val lines = mutableMapOf<Int, DuelAnnouncement.Line>()
        repeat(DuelAnnouncement.TOTAL_FRAMES + DuelAnnouncement.END_FRAMES) { n ->
            show.hear(frame)
            show.takeLine()?.let { lines[n + 1] = it }
        }
        val crownLine = crownFrame + DuelAnnouncement.CROWN_LINE_FRAMES
        assertEquals("the winner is named as the fanfare ends", mapOf(crownLine to DuelAnnouncement.Line.CROWN), lines)
        val winnersPartLeft = (DuelAnnouncement.TOTAL_FRAMES - crownLine) / fps
        assertTrue("with time left to say it before the winner's part ends ($winnersPartLeft s)", winnersPartLeft >= 2f)
    }

    @Test
    fun aStoppedShowSaysNoMore() {
        val show = DuelAnnouncement()
        show.start(Side.DRIVER)
        show.stop()
        assertNull("the opening not yet said is dropped", show.takeLine())
        repeat(DuelAnnouncement.TOTAL_FRAMES) { show.hear(frame) }
        assertNull(show.takeLine())
    }

    @Test
    fun theMcNamesTheWinnersSeatAndScoreOrATie() {
        assertEquals("결과를 발표하겠습니다", DuelLines.OPENING)
        assertEquals("승자는 운전석! 72점입니다", DuelLines.crown(Side.DRIVER, 72))
        assertEquals("승자는 조수석! 53점입니다", DuelLines.crown(Side.PASSENGER, 53))
        assertEquals("무승부! 환상의 케미예요", DuelLines.crown(null, 40))
    }

    @Test
    fun aStoppedShowChangesNoSound() {
        val show = DuelAnnouncement()
        show.start(Side.DRIVER)
        repeat(DuelAnnouncement.DIM_FRAMES) { show.hear(frame) }
        show.stop()
        assertNull("the change due on this frame is dropped", show.takeChange())
        repeat(DuelAnnouncement.TOTAL_FRAMES) { show.hear(frame) }
        assertNull(show.takeChange())
    }

    @Test
    fun aStoppedShowStartsNoMoreSounds() {
        val show = DuelAnnouncement()
        show.start(Side.DRIVER)
        repeat(DuelAnnouncement.DIM_FRAMES) { show.hear(frame) }
        show.stop()
        assertNull("the drumroll due on this frame is dropped", show.takeCue())
        repeat(DuelAnnouncement.TOTAL_FRAMES) { show.hear(frame) }
        assertNull(show.takeCue())
    }

    @Test
    fun aDuelIsUnderWayFromTheAskUntilItIsDone() {
        assertFalse("none", (null as DuelState?).underway)
        assertTrue(DuelState.waiting().underway)
        assertTrue(DuelState.start().underway)
        val announcing = DuelState.start().scored(result(40)).scored(result(45))
        assertTrue(announcing.underway)
        assertFalse("done", announcing.announced().underway)
    }
}
