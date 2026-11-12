package com.carstage.app.stage

import com.carstage.app.score.SongResult

/**
 * A two-person singing battle (docs/04 "기능 2. 2인 대결과 발표 모드"). One microphone cannot tell
 * two voices apart, so they take turns: the driver sings one song, then the front passenger, and
 * the higher 흥 점수 wins. A duel asked for during a song can wait for it to end ([Step.WAITING]),
 * so the driver's turn is a whole song. Immutable; each step makes the next state.
 */
data class DuelState(
    val step: Step,
    /** Whose turn it is, or is next while [Step.WAITING]; the passenger once the announcement starts. */
    val turn: Side,
    val scores: Map<Side, SongResult>,
    /** Whether the announcement's crown started ([crowned]); only an announcement keeps it, as [Step.DONE] tells the winner anyway. */
    private val crown: Boolean = false,
) {
    enum class Step { WAITING, TURN, ANNOUNCING, DONE }

    /**
     * Whether the winner is out: from the announcement's crown, when the winner's light comes on,
     * so the screen tells the last score with the light, not before it, and whenever [Step.DONE]
     * (so a stage closed before the crown tells it).
     */
    val winnerShown: Boolean get() = step == Step.DONE || crown

    /** The side with the higher score once both sang; null for a tie or before both sang. */
    val winner: Side?
        get() {
            val driver = scores[Side.DRIVER]?.score ?: return null
            val passenger = scores[Side.PASSENGER]?.score ?: return null
            return when {
                driver > passenger -> Side.DRIVER
                passenger > driver -> Side.PASSENGER
                else -> null
            }
        }

    /** The song the duel waited for ended: the driver's turn. */
    fun begun(): DuelState = if (step == Step.WAITING) start() else this

    /** The song of whoever's turn it was ended with [result]: the passenger's turn, or the announcement. */
    fun scored(result: SongResult): DuelState {
        if (step != Step.TURN) return this
        val sung = scores + (turn to result)
        return if (turn == Side.DRIVER) copy(turn = Side.PASSENGER, scores = sung) else copy(step = Step.ANNOUNCING, scores = sung)
    }

    /** The announcement's crown started: the winner is out, while the announcement still runs. */
    fun crowned(): DuelState = if (step == Step.ANNOUNCING) copy(crown = true) else this

    /** The announcement is over; the result stays on the screen. */
    fun announced(): DuelState = if (step == Step.ANNOUNCING) copy(step = Step.DONE, crown = false) else this

    /**
     * What is left of the duel once its stage closed: nothing of one still being sung, since its
     * songs are no longer heard, and the result of one being announced or done.
     */
    fun closed(): DuelState? = when (step) {
        Step.WAITING, Step.TURN -> null
        Step.ANNOUNCING, Step.DONE -> copy(step = Step.DONE, crown = false)
    }

    companion object {
        /** The driver sings first. */
        fun start() = DuelState(Step.TURN, Side.DRIVER, emptyMap())

        /** The song being sung ends first, then the driver's turn starts. */
        fun waiting() = DuelState(Step.WAITING, Side.DRIVER, emptyMap())

        /** Blue for the driver, red for the front passenger (docs/04), and purple when they tie. */
        fun colorOf(side: Side?): Rgb = when (side) {
            Side.DRIVER -> Rgb(0x2060FF)
            Side.PASSENGER -> Rgb(0xFF3030)
            null -> Rgb(0x9040FF)
        }
    }
}

/** Whether a duel is asked for or going on: there is one and it is not done. */
val DuelState?.underway: Boolean get() = this != null && step != DuelState.Step.DONE
