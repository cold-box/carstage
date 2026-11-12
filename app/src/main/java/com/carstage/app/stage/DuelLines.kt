package com.carstage.app.stage

/**
 * What the MC says during a duel's announcement (docs/04 "발표 모드 연출 순서"), in Korean: the
 * opening as the light dims, and the winner with their score (or a tie) with the crown. The
 * screen shows the same moments, so a car without a voice loses only the voice.
 */
object DuelLines {
    const val OPENING = "결과를 발표하겠습니다"

    /** The crown's line for [winner] (null for a tie) and the winner's [score]. */
    fun crown(winner: Side?, score: Int): String = when (winner) {
        null -> "무승부! 환상의 케미예요"
        else -> "승자는 ${nameOf(winner)}! ${score}점입니다"
    }

    private fun nameOf(side: Side) = when (side) {
        Side.DRIVER -> "운전석"
        Side.PASSENGER -> "조수석"
    }
}
