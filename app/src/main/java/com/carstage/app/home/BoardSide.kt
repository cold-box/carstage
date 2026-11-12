package com.carstage.app.home

import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side

/**
 * One side of the duel's scoreboard (docs/10 4장 "대결 차례", "대결 발표", "대결 결과"), the driver's
 * on the left and the passenger's on the right, as they sit. Plain values, so it can be unit-tested.
 */
data class BoardSide(
    /** In the score's place: the points, [KEPT] for the score the crown tells, or what the side is doing. */
    val text: String,
    /** Whether [text] is a score, set large, rather than words about the turns. */
    val score: Boolean,
    /** Lit in the side's color: whose turn it is, the side the announcement's light is on, the winner (both for a tie). */
    val lit: Boolean,
    /** The winner's side grows (docs/10 "승자 쪽 확대"); neither for a tie. */
    val raised: Boolean = false,
) {
    companion object {
        const val KEPT = "?"
        const val FIRST = "첫 번째"
        const val SECOND = "두 번째"
        const val SINGING = "부르는 중"
        const val NEXT = "다음 차례"

        /**
         * Both sides of [duel]'s board. [lit], the sides whose reading light is on, is read only
         * during the announcement until its crown, so the board takes turns with its light; the
         * score of [DuelState.turn], the passenger's, which nobody has seen, waits for the crown.
         */
        fun of(duel: DuelState, lit: Set<Side>): Map<Side, BoardSide> = Side.entries.associateWith { side ->
            val points = duel.scores[side]?.score?.let { BoardSide("${it}점", score = true, lit = false) }
            when {
                duel.winnerShown -> {
                    val winner = duel.winner
                    (points ?: BoardSide(KEPT, score = true, lit = false)).copy(lit = winner == null || winner == side, raised = winner == side)
                }
                duel.step == DuelState.Step.ANNOUNCING ->
                    (points?.takeIf { side != duel.turn } ?: BoardSide(KEPT, score = true, lit = false)).copy(lit = side in lit)
                duel.step == DuelState.Step.WAITING -> BoardSide(if (side == Side.DRIVER) FIRST else SECOND, score = false, lit = false)
                points != null -> points
                side == duel.turn -> BoardSide(SINGING, score = false, lit = true)
                else -> BoardSide(NEXT, score = false, lit = false)
            }
        }
    }
}
