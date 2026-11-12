package com.carstage.app

import com.carstage.app.score.SongResult
import com.carstage.app.stage.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class McLinesTest {
    private val day = McLines.Moment(night = false, charging = false, batteryPercent = null, twoInFront = false, hour = 14, drivenMinutes = null)

    @Test
    fun theGreetingTellsTheMomentInTurn() {
        val all = day.copy(night = true, charging = true, batteryPercent = 64, twoInFront = true, hour = 7)
        assertTrue("night first", McLines.opening(all, Random(1)).contains("밤"))
        assertEquals("then charging", "충전하는 동안 한 무대 해 볼까요? 지금 배터리는 64%예요.", McLines.opening(all.copy(night = false)))
        assertEquals("a battery not read", "충전하는 동안 한 무대 해 볼까요?", McLines.opening(day.copy(charging = true)))
        assertTrue("then two in front", McLines.opening(day.copy(twoInFront = true, hour = 7), Random(1)).contains("대결"))
        assertTrue("then the morning", McLines.opening(day.copy(hour = 7)).contains("아침"))
        assertTrue("the evening", McLines.opening(day.copy(hour = 20)).contains("수고"))
        assertTrue("else a plain greeting", McLines.opening(day, Random(1)).contains("무대"))
    }

    @Test
    fun aLongDriveIsGreetedAfterTheNightAndBeforeCharging() {
        val drove = day.copy(drivenMinutes = 95)
        assertEquals("밤길을 1시간 반 넘게 달려 오셨네요. 조명은 은은하게 갈게요.", McLines.opening(drove.copy(night = true)))
        assertEquals("1시간 반 넘게 달려 오셨네요. 충전하는 동안 노래로 쉬어 가요.", McLines.opening(drove.copy(charging = true, batteryPercent = 64)))
        assertTrue("before two in front", McLines.opening(drove.copy(twoInFront = true), Random(1)).startsWith("1시간 반 넘게 달려 오셨"))
        assertTrue("before the time of day", McLines.opening(drove.copy(hour = 7), Random(2)).startsWith("1시간 반 넘게 달려 오셨"))
    }

    @Test
    fun aShortOrUnknownDriveGetsTheUsualGreeting() {
        assertEquals(McLines.opening(day.copy(night = true), Random(3)), McLines.opening(day.copy(night = true, drivenMinutes = 29), Random(3)))
        assertEquals("충전하는 동안 한 무대 해 볼까요?", McLines.opening(day.copy(charging = true, drivenMinutes = 29)))
        assertTrue("from half an hour", McLines.opening(day.copy(drivenMinutes = 30), Random(1)).startsWith("30분 넘게 달려 오셨"))
    }

    @Test
    fun aDriveIsSaidRoughlyAndFromBelow() {
        assertNull("not worth a word", McLines.spokenDrive(29))
        assertEquals("30분 넘게", McLines.spokenDrive(30))
        assertEquals("50분 넘게", McLines.spokenDrive(59))
        assertEquals("1시간 넘게", McLines.spokenDrive(60))
        assertEquals("1시간 넘게", McLines.spokenDrive(89))
        assertEquals("1시간 반 넘게", McLines.spokenDrive(90))
        assertEquals("2시간 넘게", McLines.spokenDrive(121))
        assertEquals("10시간 반 넘게", McLines.spokenDrive(630))
    }

    @Test
    fun aScoreSaysTheSongItsPointsAndWhatItDidBest() {
        val best = SongResult(participation = 25, heat = 15, response = 35, completion = 10, seconds = 180, title = "Vibe Ace")
        assertEquals("Vibe Ace, 85점! 오늘의 흥부자예요! 환호가 쏟아졌어요.", McLines.scored(best))
        val untitled = SongResult(participation = 30, heat = 10, response = 5, completion = 5, seconds = 90)
        assertEquals("50점! 흥이 올랐어요! 목소리가 차 안을 가득 채웠어요.", McLines.scored(untitled))
        val belted = SongResult(participation = 10, heat = 25, response = 0, completion = 0, seconds = 60)
        assertEquals("35점! 좋은 출발이에요. 시원하게 터뜨리셨네요.", McLines.scored(belted))
    }

    @Test
    fun aLongTitleIsSaidWithoutItsBracketsOrNotAtAll() {
        assertEquals("작은 것들을 위한 시", McLines.spokenTitle("작은 것들을 위한 시 (Boy With Luv) (Feat. Halsey)"))
        assertEquals("Song", McLines.spokenTitle("Song [Live]"))
        assertNull("still too long", McLines.spokenTitle("이 노래 제목은 정말로 너무너무 길어서 말하기 어려워요"))
        assertNull("nothing but brackets", McLines.spokenTitle("(Intro)"))
        val line = McLines.scored(SongResult(25, 15, 35, 10, 180, title = "작은 것들을 위한 시 (Boy With Luv) (Feat. Halsey)"))
        assertTrue(line.startsWith("작은 것들을 위한 시, 85점!"))
        assertTrue(com.carstage.app.audio.Announcer.estimateMs(line) < com.carstage.app.audio.Announcer.MAX_LINE_MS)
    }

    @Test
    fun aPartIsPraisedFromSixtyPercent() {
        // 18 of participation's 30 points.
        assertEquals(McLines.Part.PARTICIPATION, McLines.bestPart(SongResult(18, 0, 0, 0, 60)))
        assertNull(McLines.bestPart(SongResult(17, 0, 0, 0, 60)))
    }

    @Test
    fun aQuietSongIsCheeredOn() {
        val quiet = SongResult(participation = 5, heat = 0, response = 0, completion = 2, seconds = 60)
        assertNull(McLines.bestPart(quiet))
        assertTrue(McLines.scored(quiet).endsWith("다음 곡은 더 크게 불러 봐요."))
    }

    @Test
    fun aDuelCallsTheDriverThenThePassenger() {
        assertTrue(McLines.turn(Side.DRIVER).contains("운전석부터"))
        assertTrue(McLines.turn(Side.PASSENGER).contains("조수석 차례"))
    }

    @Test
    fun everyLineIsShortEnoughToSayOverTheMusic() {
        val long = day.copy(drivenMinutes = 23 * 60 + 59)
        val moments = listOf(day, day.copy(night = true), day.copy(charging = true, batteryPercent = 100), day.copy(twoInFront = true), day.copy(hour = 7), day.copy(hour = 20)) +
            listOf(long, long.copy(night = true), long.copy(charging = true))
        val lines = moments.flatMap { m -> (0..5).map { McLines.opening(m, Random(it)) } } +
            McLines.scored(SongResult(30, 25, 35, 10, 300, title = "제목이 아주 긴 노래의 이름")) +
            McLines.turn(Side.DRIVER) + McLines.turn(Side.PASSENGER) + McLines.CHARGE_DONE
        lines.forEach { assertTrue("$it: under the voice's limit", com.carstage.app.audio.Announcer.estimateMs(it) < com.carstage.app.audio.Announcer.MAX_LINE_MS) }
    }
}
