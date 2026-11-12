package com.carstage.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeBoardTest {
    private val board = NoticeBoard()

    @Test
    fun anEventReplacesTheLineAndAStateAddsAndTakesOutItsOwn() {
        board.show("통화로 멈췄어요.", NoticeTone.INFO)
        board.add("배터리 안내.", NoticeTone.CAUTION)
        assertEquals("통화로 멈췄어요. 배터리 안내.", board.notice.value)
        board.remove("배터리 안내.")
        assertEquals("통화로 멈췄어요.", board.notice.value)
        board.show("복원 실패.", NoticeTone.ERROR)
        assertEquals("복원 실패.", board.notice.value)
    }

    @Test
    fun theSilenceComesFirstWhileItLastsAndThenOnlyItGoes() {
        board.show("연결 실패.", NoticeTone.INFO)
        board.silence(true)
        assertEquals("${NoticeBoard.SILENT_MIC} 연결 실패.", board.notice.value)
        board.add("공회전 안내.", NoticeTone.CAUTION)
        board.silence(false)
        assertEquals("what came before and after stays", "연결 실패. 공회전 안내.", board.notice.value)
    }

    @Test
    fun aNewStageShowsItsOwnNoticesKeepingItsBatteryNotice() {
        board.show("지난 무대의 안내.", NoticeTone.INFO)
        board.add(BatterySaver.LOW_NOTICE, NoticeTone.CAUTION)
        board.opened(alreadyOpen = false, recovery = "복원했어요.", connection = null)
        assertEquals("복원했어요. ${BatterySaver.LOW_NOTICE}", board.notice.value)
        board.opened(alreadyOpen = false, recovery = null, connection = null)
        assertEquals("only the battery stays", BatterySaver.LOW_NOTICE, board.notice.value)
    }

    @Test
    fun aNewStageKeepsTheControlNoticeItsCarMayAlreadyHaveCausedToo() {
        board.add(NoticeBoard.CONTROL_DENIED, NoticeTone.CAUTION)
        board.add(BatterySaver.LOW_NOTICE, NoticeTone.CAUTION)
        board.opened(alreadyOpen = false, recovery = null, connection = null)
        assertEquals("${BatterySaver.LOW_NOTICE} ${NoticeBoard.CONTROL_DENIED}", board.notice.value)
    }

    @Test
    fun aNewStageKeepsTheNightNoticeItsOpeningMayAlreadyHaveAdded() {
        board.show("지난 안내.", NoticeTone.INFO)
        board.add(NightDimming.NOTICE, NoticeTone.INFO)
        board.opened(alreadyOpen = false, recovery = "복원했어요.", connection = null)
        assertEquals("복원했어요. ${NightDimming.NOTICE}", board.notice.value)
    }

    @Test
    fun aNewStageWithNothingToSayClearsTheLine() {
        board.show("지난 안내.", NoticeTone.INFO)
        board.opened(alreadyOpen = false, recovery = null, connection = null)
        assertNull(board.notice.value)
    }

    @Test
    fun anOpenStageOpenedAgainAddsToWhatItShows() {
        board.show(NoticeBoard.SILENT_MIC, NoticeTone.CAUTION)
        board.opened(alreadyOpen = true, recovery = null, connection = NoticeBoard.NOT_CONNECTED)
        assertEquals("${NoticeBoard.SILENT_MIC} ${NoticeBoard.NOT_CONNECTED}", board.notice.value)
    }

    private fun tone() = board.toneOf(board.notice.value!!)

    @Test
    fun theLineTakesTheKindOfItsMostSeriousNotice() {
        board.show("통화로 멈췄어요.", NoticeTone.INFO)
        assertEquals(NoticeTone.INFO, tone())
        board.add("배터리 안내.", NoticeTone.CAUTION)
        assertEquals(NoticeTone.CAUTION, tone())
        board.add("복원 실패.", NoticeTone.ERROR)
        assertEquals(NoticeTone.ERROR, tone())
        board.remove("복원 실패.")
        assertEquals("the error gone, the caution leads again", NoticeTone.CAUTION, tone())
        board.show("통화로 멈췄어요.", NoticeTone.INFO)
        assertEquals("a replaced line has only its own kind", NoticeTone.INFO, tone())
    }

    @Test
    fun aSilentMicrophoneAndACarOutOfReachAreToWatch() {
        board.show("다시 정차했어요.", NoticeTone.INFO)
        board.silence(true)
        assertEquals(NoticeTone.CAUTION, tone())
        board.silence(false)
        board.opened(alreadyOpen = true, recovery = null, connection = NoticeBoard.NOT_CONNECTED)
        assertEquals(NoticeTone.CAUTION, tone())
    }

    @Test
    fun textTheBoardWasNeverGivenIsAnError() {
        assertEquals(NoticeTone.ERROR, board.toneOf("모르는 안내."))
    }
}
