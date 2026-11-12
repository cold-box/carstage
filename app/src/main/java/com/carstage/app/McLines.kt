package com.carstage.app

import com.carstage.app.score.Grade
import com.carstage.app.score.ScoreKeeper
import com.carstage.app.score.SongResult
import com.carstage.app.stage.Side
import kotlin.random.Random

/**
 * What the MC says ([StageMc], docs/12): short, kind lines in Korean that a family can hear over
 * the music, told from what the car knows of the moment. Each is a few seconds at most, as the
 * voice holds the music down while it speaks. Pure Kotlin.
 */
object McLines {
    /** What the car tells of the moment a stage opens. */
    data class Moment(
        /** Whether the car says it is night ([NightDimming]). */
        val night: Boolean,
        /** Whether the car is charging, and its battery in percent when known ([BatteryWatch]). */
        val charging: Boolean,
        val batteryPercent: Int?,
        /** Whether both front seats are taken ([DuelOffer]) and a duel is worth offering. */
        val twoInFront: Boolean,
        /** The hour of the day, 0..23. */
        val hour: Int,
        /** The minutes driven on this trip, when the car tells them believably ([TripWatch]). */
        val drivenMinutes: Int?,
    )

    /**
     * The greeting as a stage opens: the night (with the drive, after a long one), a long drive
     * (with charging), charging, two in front, else the time of day.
     */
    fun opening(moment: Moment, random: Random = Random.Default): String {
        val drive = moment.drivenMinutes?.let(::spokenDrive)
        return when {
            moment.night -> drive?.let { "밤길을 $it 달려 오셨네요. 조명은 은은하게 갈게요." }
                ?: pick(random, "밤 무대를 열게요. 조명은 은은하게 갈게요.", "조용한 밤이네요. 은은한 조명으로 한 곡 해 볼까요?")
            drive != null && moment.charging -> "$drive 달려 오셨네요. 충전하는 동안 노래로 쉬어 가요."
            drive != null -> pick(random, "$drive 달려 오셨네요. 노래 한 곡으로 쉬어 가요.", "$drive 달려 오셨어요. 한 곡 부르며 기지개도 켜 봐요.")
            moment.charging -> moment.batteryPercent?.let { "충전하는 동안 한 무대 해 볼까요? 지금 배터리는 ${it}%예요." }
                ?: "충전하는 동안 한 무대 해 볼까요?"
            moment.twoInFront -> pick(random, "두 분이 타셨네요. 오늘은 노래 대결 어때요?", "앞자리에 두 분! 대결 한 판 어떠세요?")
            moment.hour in 5..10 -> "좋은 아침이에요! 첫 곡으로 하루를 열어 볼까요?"
            moment.hour in 18..23 -> "오늘도 수고 많으셨어요. 신나게 한 곡 불러 볼까요?"
            else -> pick(random, "무대를 열었어요. 노래를 시작해 볼까요?", "무대 준비 끝! 마음껏 불러 주세요.")
        }
    }

    /**
     * How long a drive took, said roughly and from below: under an hour to the ten minutes below
     * ("40분 넘게"), longer to the half hour below ("1시간 넘게", "2시간 반 넘게"); null for a drive
     * under [LONG_DRIVE_MINUTES], which is not worth a word.
     */
    fun spokenDrive(minutes: Int): String? {
        if (minutes < LONG_DRIVE_MINUTES) return null
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0 -> "${minutes / 10 * 10}분 넘게"
            rest < MINUTES_PER_HOUR / 2 -> "${hours}시간 넘게"
            else -> "${hours}시간 반 넘게"
        }
    }

    /** Half an hour on the road is a drive worth naming; a shorter one gets the usual greeting. */
    const val LONG_DRIVE_MINUTES = 30

    private const val MINUTES_PER_HOUR = 60

    /** A song's score: its title when known, the points, the grade's kind word and what the song did best. */
    fun scored(result: SongResult): String {
        val head = (result.title?.let(::spokenTitle)?.let { "$it, " } ?: "") + "${result.score}점!"
        val grade = when (result.grade) {
            Grade.BEST -> "오늘의 흥부자예요!"
            Grade.GOOD -> "흥이 올랐어요!"
            Grade.BASIC -> "좋은 출발이에요."
        }
        return "$head $grade ${praise(result)}"
    }

    /** What a song did best, kindly; the screen's score reveal shows it too, for when the voice is off or not heard. */
    fun praise(result: SongResult): String = when (bestPart(result)) {
        Part.PARTICIPATION -> "목소리가 차 안을 가득 채웠어요."
        // Heat counts the moments belted or shouted far over the song (docs/09 "흥 점수 v2").
        Part.HEAT -> "시원하게 터뜨리셨네요."
        Part.RESPONSE -> "환호가 쏟아졌어요."
        Part.COMPLETION -> "끝까지 완창하셨네요."
        null -> "다음 곡은 더 크게 불러 봐요."
    }

    /** A duel's turn: the driver first, then the passenger. */
    fun turn(side: Side): String = when (side) {
        Side.DRIVER -> "대결을 시작합니다! 운전석부터 한 곡 불러 주세요."
        Side.PASSENGER -> "이번엔 조수석 차례예요. 실력을 보여 주세요!"
    }

    /** Charging stopped while a stage is open (the screen asks the same, [ChargeFinish]). */
    const val CHARGE_DONE = "충전이 끝났어요. 마지막 곡으로 멋지게 마무리할까요?"

    enum class Part { PARTICIPATION, HEAT, RESPONSE, COMPLETION }

    /** The part with the best share of its points, when that share is at least [PRAISE_SHARE]; ties go to the first. */
    fun bestPart(result: SongResult): Part? = listOf(
        Part.PARTICIPATION to result.participation.toFloat() / ScoreKeeper.PARTICIPATION_POINTS,
        Part.HEAT to result.heat.toFloat() / ScoreKeeper.HEAT_POINTS,
        Part.RESPONSE to result.response.toFloat() / ScoreKeeper.RESPONSE_POINTS,
        Part.COMPLETION to result.completion.toFloat() / ScoreKeeper.COMPLETION_POINTS,
    ).maxByOrNull { it.second }?.takeIf { it.second >= PRAISE_SHARE }?.first

    /**
     * A title as it is said: without what is in brackets (featured artists, versions), and left out
     * when still longer than [MAX_TITLE], so the line ends within the voice's limit.
     */
    fun spokenTitle(title: String): String? =
        title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }

    /** The longest title said, in characters. */
    const val MAX_TITLE = 20

    /** How full a part must be to be praised. */
    const val PRAISE_SHARE = 0.6f

    private fun pick(random: Random, vararg lines: String): String = lines[random.nextInt(lines.size)]
}
