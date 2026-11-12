package com.carstage.app.home

/**
 * Where the parts of the single column go in a narrow window (docs/10 4장 "세로 한 칸"), in
 * pixels: the cabin drawing on top with what the player and the controls leave of the height, or
 * none when that is less than the least worth drawing, so the buttons and the status come first.
 * Plain values, so it can be unit-tested.
 */
data class ColumnPlan(
    /** The drawing's height, 0 when it is left out. */
    val drawing: Int,
    /** Where the first part shown starts: the top, or lower to center the rest without the drawing. */
    val top: Int,
    /** The column's height: the window's, or more when the controls alone outgrow it, which then scrolls. */
    val height: Int,
) {
    companion object {
        /**
         * The plan for a window [viewport] high, with the music player [player] high (0 while
         * there is none) and the controls [controls] high, parts [gap] apart; the drawing is left
         * out below [minDrawing].
         */
        fun of(viewport: Int, player: Int, controls: Int, gap: Int, minDrawing: Int): ColumnPlan {
            val below = controls + if (player > 0) player + gap else 0
            val room = viewport - below - gap
            if (room >= minDrawing) return ColumnPlan(drawing = room, top = 0, height = viewport)
            return ColumnPlan(drawing = 0, top = ((viewport - below) / 2).coerceAtLeast(0), height = maxOf(viewport, below))
        }
    }
}
