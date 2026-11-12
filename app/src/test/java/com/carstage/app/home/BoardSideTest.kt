package com.carstage.app.home

import com.carstage.app.score.SongResult
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardSideTest {
    private fun result(score: Int) = SongResult(participation = score, heat = 0, response = 0, completion = 0, seconds = 60)

    private fun board(duel: DuelState, lit: Set<Side> = emptySet()) = BoardSide.of(duel, lit)

    private val words = { text: String, lit: Boolean -> BoardSide(text, score = false, lit = lit) }
    private val points = { text: String, lit: Boolean -> BoardSide(text, score = true, lit = lit) }

    @Test
    fun aWaitingDuelSaysWhoSingsFirst() {
        assertEquals(
            mapOf(Side.DRIVER to words(BoardSide.FIRST, false), Side.PASSENGER to words(BoardSide.SECOND, false)),
            board(DuelState.waiting()),
        )
    }

    @Test
    fun theSingersSideIsLitAndASungScoreStays() {
        val driver = DuelState.start()
        assertEquals(mapOf(Side.DRIVER to words(BoardSide.SINGING, true), Side.PASSENGER to words(BoardSide.NEXT, false)), board(driver))
        val passenger = driver.scored(result(40))
        assertEquals(mapOf(Side.DRIVER to points("40점", false), Side.PASSENGER to words(BoardSide.SINGING, true)), board(passenger))
        assertEquals("a cheer's sparkle is not the board's during a turn", board(passenger), board(passenger, lit = setOf(Side.DRIVER)))
    }

    @Test
    fun theAnnouncementLightsTheBoardWithTheReadingLightsAndKeepsTheLastScoreForTheCrown() {
        val announcing = DuelState.start().scored(result(40)).scored(result(55))
        assertEquals(mapOf(Side.DRIVER to points("40점", false), Side.PASSENGER to points(BoardSide.KEPT, false)), board(announcing))
        assertEquals(mapOf(Side.DRIVER to points("40점", true), Side.PASSENGER to points(BoardSide.KEPT, false)), board(announcing, setOf(Side.DRIVER)))
        assertEquals(mapOf(Side.DRIVER to points("40점", false), Side.PASSENGER to points(BoardSide.KEPT, true)), board(announcing, setOf(Side.PASSENGER)))
    }

    @Test
    fun theCrownTellsTheScoreAndRaisesTheWinnerUntilTheCardGoes() {
        val crowned = DuelState.start().scored(result(40)).scored(result(55)).crowned()
        val told = mapOf(Side.DRIVER to points("40점", false), Side.PASSENGER to points("55점", true).copy(raised = true))
        assertEquals(told, board(crowned))
        assertEquals("the lights no longer lead", told, board(crowned, setOf(Side.DRIVER)))
        assertEquals(told, board(crowned.announced()))
        assertEquals("a stage closed during the announcement tells it all", told, board(DuelState.start().scored(result(40)).scored(result(55)).closed()!!))
    }

    @Test
    fun aDriversWinRaisesTheDriverAndStillKeepsThePassengersScoreUntilTheCrown() {
        val announcing = DuelState.start().scored(result(60)).scored(result(30))
        assertEquals(points(BoardSide.KEPT, false), board(announcing).getValue(Side.PASSENGER))
        assertEquals(
            mapOf(Side.DRIVER to points("60점", true).copy(raised = true), Side.PASSENGER to points("30점", false)),
            board(announcing.crowned()),
        )
    }

    @Test
    fun aTieLightsBothAndRaisesNeither() {
        val tie = DuelState.start().scored(result(50)).scored(result(50)).announced()
        assertEquals(mapOf(Side.DRIVER to points("50점", true), Side.PASSENGER to points("50점", true)), board(tie))
    }
}
