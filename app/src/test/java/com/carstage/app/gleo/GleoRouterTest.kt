package com.carstage.app.gleo

import com.carstage.app.DuelOutcome
import com.carstage.app.OpenOutcome
import com.carstage.app.StageMc
import com.carstage.app.stage.StageController.OpenResult
import com.carstage.app.stage.CarCabin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GleoRouterTest {
    private val ok = Result.success(Unit)

    private fun router(result: OpenResult, opened: MutableList<Unit> = mutableListOf(), stageOpen: Boolean = false) =
        GleoRouter(endSong = { stageOpen }, startDuel = { DuelOutcome.Asked(afterThisSong = false) }) { opened += Unit; OpenOutcome.Opened(result) }

    @Test
    fun openStageOpensTheStageAndRepliesSuccess() = runTest {
        val opened = mutableListOf<Unit>()
        val reply = router(OpenResult(listOf(CarCabin.NAME, "simulator"), alreadyOpen = false), opened)
            .handle(GleoActions.OPEN_STAGE, ok)
        assertEquals(GleoActions.Status.SUCCESS, reply.status)
        assertEquals("무대를 열었어요. 노래를 불러 보세요.", reply.message)
        assertTrue("the MC greets the new stage itself", reply.mcSays)
        assertEquals("read out, it greets", setOf(StageMc.Said.GREETING), reply.says)
        assertEquals(1, opened.size)
    }

    @Test
    fun openStageSaysWhenOnlyTheScreenReacts() = runTest {
        val reply = router(OpenResult(listOf("simulator"), alreadyOpen = false)).handle(GleoActions.OPEN_STAGE, ok)
        assertEquals(GleoActions.Status.SUCCESS, reply.status)
        assertTrue("화면에서만" in reply.message)
        assertFalse("only Gleo says the car could not be reached", reply.mcSays)
        assertTrue("it does not greet, so the MC still does", reply.says.isEmpty())
    }

    @Test
    fun openStageRefusedBySafetyRepliesFailWithTheReason() = runTest {
        val reply = GleoRouter(endSong = { false }, startDuel = { DuelOutcome.Asked(afterThisSong = false) }) { OpenOutcome.Refused("정차 중에만 무대를 열 수 있어요.") }.handle(GleoActions.OPEN_STAGE, ok)
        assertEquals(GleoActions.Status.FAIL, reply.status)
        assertEquals("정차 중에만 무대를 열 수 있어요.", reply.message)
    }

    @Test
    fun startBattleStartsADuelAndRepliesSuccess() = runTest {
        val duels = mutableListOf<Unit>()
        val reply = GleoRouter(endSong = { true }, startDuel = { duels += Unit; DuelOutcome.Asked(afterThisSong = false) }) { OpenOutcome.Refused("unused") }
            .handle(GleoActions.START_BATTLE, ok)
        assertEquals(GleoActions.Status.SUCCESS, reply.status)
        assertEquals("대결을 시작해요. 운전석부터 한 곡 불러 주세요.", reply.message)
        assertTrue("the MC calls the driver's turn itself", reply.mcSays)
        assertEquals(setOf(StageMc.Said.GREETING, StageMc.Said.DRIVER_TURN), reply.says)
        assertEquals(1, duels.size)
    }

    @Test
    fun startBattleDuringASongSaysTheDuelStartsAfterIt() = runTest {
        val reply = GleoRouter(endSong = { true }, startDuel = { DuelOutcome.Asked(afterThisSong = true) }) { OpenOutcome.Refused("unused") }
            .handle(GleoActions.START_BATTLE, ok)
        assertEquals(GleoActions.Status.SUCCESS, reply.status)
        assertEquals("지금 곡이 끝나면 대결을 시작해요. 다음 곡은 운전석부터 불러 주세요.", reply.message)
        assertFalse("the MC has no line for the wait", reply.mcSays)
    }

    @Test
    fun startBattleNotStartedInTimeSaysToLookAtTheScreen() = runTest {
        val reply = GleoRouter(endSong = { true }, startDuel = { DuelOutcome.Asked(afterThisSong = null) }) { OpenOutcome.Refused("unused") }
            .handle(GleoActions.START_BATTLE, ok)
        assertEquals(GleoActions.Status.SUCCESS, reply.status)
        assertEquals("대결을 시작해요. 화면에서 차례를 확인해 주세요.", reply.message)
        assertFalse(reply.mcSays)
        assertTrue("it does not say who starts, so the MC still calls the turn", reply.says.isEmpty())
    }

    @Test
    fun startBattleRefusedRepliesFailWithTheReason() = runTest {
        val reply = GleoRouter(endSong = { false }, startDuel = { DuelOutcome.Refused("정차 중에만 무대를 열 수 있어요.") }) { OpenOutcome.Refused("unused") }
            .handle(GleoActions.START_BATTLE, ok)
        assertEquals(GleoActions.Status.FAIL, reply.status)
        assertEquals("정차 중에만 무대를 열 수 있어요.", reply.message)
    }

    @Test
    fun finishSongEndsTheSongOnAnOpenStage() = runTest {
        val ended = mutableListOf<Unit>()
        val router = GleoRouter(endSong = { ended += Unit; true }, startDuel = { DuelOutcome.Asked(afterThisSong = false) }) { OpenOutcome.Refused("unused") }
        val reply = router.handle(GleoActions.FINISH_SONG, ok)
        assertEquals(GleoActions.Status.SUCCESS, reply.status)
        assertEquals("곡을 마쳤어요.", reply.message)
        assertEquals(1, ended.size)
    }

    @Test
    fun finishSongWithoutAStageRepliesFailAndOpensNothing() = runTest {
        val opened = mutableListOf<Unit>()
        val reply = router(OpenResult(emptyList(), alreadyOpen = false), opened, stageOpen = false).handle(GleoActions.FINISH_SONG, ok)
        assertEquals(GleoActions.Status.FAIL, reply.status)
        assertTrue("무대" in reply.message)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun unknownActionsAndUnreadableParametersReplyError() = runTest {
        val opened = mutableListOf<Unit>()
        val router = router(OpenResult(emptyList(), alreadyOpen = false), opened)
        assertEquals(GleoActions.Status.ERROR, router.handle("DANCE", ok).status)
        assertEquals(GleoActions.Status.ERROR, router.handle(GleoActions.OPEN_STAGE, Result.failure<Unit>(IllegalStateException())).status)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun anOpenStageAndFailuresAreSaidByGleo() = runTest {
        val already = router(OpenResult(listOf(CarCabin.NAME), alreadyOpen = true)).handle(GleoActions.OPEN_STAGE, ok)
        assertFalse("no new stage, no greeting", already.mcSays)
        val refused = GleoRouter(endSong = { false }, startDuel = { DuelOutcome.Refused("x") }) { OpenOutcome.Refused("x") }
        assertFalse("a refused open", refused.handle(GleoActions.OPEN_STAGE, ok).mcSays)
        val finished = router(OpenResult(listOf(CarCabin.NAME), alreadyOpen = false), stageOpen = true).handle(GleoActions.FINISH_SONG, ok)
        assertFalse("an ended song", finished.mcSays)
    }

    @Test
    fun aDuelAskedAgainOnTheDriversTurnIsSaidByGleo() = runTest {
        val reply = GleoRouter(endSong = { true }, startDuel = { DuelOutcome.Asked(afterThisSong = false, turnIsNew = false) }) { OpenOutcome.Refused("unused") }
            .handle(GleoActions.START_BATTLE, ok)
        assertEquals("대결을 시작해요. 운전석부터 한 곡 불러 주세요.", reply.message)
        assertFalse("the MC has no new turn to call", reply.mcSays)
    }

    @Test
    fun gleoOnlyBeepsForASuccessTheMcSaysWhenTheMcCanSpeak() {
        val said = GleoRouter.Reply(GleoActions.Status.SUCCESS, "무대를 열었어요.", mcSays = true)
        assertEquals(GleoActions.Mode.BEEP, said.mode(mcCanSpeak = true))
        assertEquals("the MC is off or its voice failed", GleoActions.Mode.VOICE, said.mode(mcCanSpeak = false))
        assertEquals("nothing the MC says", GleoActions.Mode.VOICE, said.copy(mcSays = false).mode(mcCanSpeak = true))
        assertEquals("a failure", GleoActions.Mode.VOICE, said.copy(status = GleoActions.Status.FAIL).mode(mcCanSpeak = true))
    }

    @Test
    fun aBuildThatLeavesTheCabinAloneOpensWithoutSayingTheCarWasNotReached() = runTest {
        val reply = GleoRouter(drivesCabin = false, endSong = { false }, startDuel = { DuelOutcome.Asked(afterThisSong = false) }) {
            OpenOutcome.Opened(OpenResult(listOf("simulator"), alreadyOpen = false))
        }.handle(GleoActions.OPEN_STAGE, ok)
        assertEquals("무대를 열었어요. 노래를 불러 보세요.", reply.message)
        assertTrue("the MC greets the new stage itself", reply.mcSays)
    }
}
