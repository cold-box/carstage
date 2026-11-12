package com.carstage.app

import com.carstage.app.stage.CabinTarget
import com.carstage.app.stage.RestoreJournal
import com.carstage.app.stage.Side
import com.carstage.app.stage.StageRecovery
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftoversTest {
    private val journal = RestoreJournal(File(Files.createTempDirectory("leftovers").toFile().apply { deleteOnExit() }, "journal.properties"))
    private val notices = NoticeBoard()
    private var stageOpen = false
    private var parked: Boolean? = true
    private var restore: suspend (Map<CabinTarget, String>) -> List<StageRecovery.Failure> = { emptyList() }

    private fun TestScope.leftovers() = Leftovers(
        backgroundScope,
        StageRecovery(journal, { restore(it) }) { _, _ -> },
        whileClosed = { block -> if (stageOpen) null else block() },
        parkedNow = { parked },
        notices = notices,
        warn = {},
    )

    @Test
    fun nothingLeftSaysNothing() = runTest {
        val leftovers = leftovers()
        assertTrue(leftovers.restore())
        assertNull(notices.notice.value)
        assertNull(leftovers.takeNotice())
    }

    @Test
    fun whatWasPutBackIsToldNowAndAgainWithTheNextOpen() = runTest {
        journal.record(CabinTarget.AmbientBrightness, "48")
        val leftovers = leftovers()
        assertTrue(leftovers.restore())
        assertEquals("지난 무대에서 바뀐 차량 설정을 원래대로 되돌렸어요.", notices.notice.value)
        assertEquals("all put back is news, not a problem", NoticeTone.INFO, notices.toneOf(notices.notice.value!!))
        assertEquals("kept for the next open", notices.notice.value, leftovers.takeNotice())
        assertNull("once", leftovers.takeNotice())
    }

    @Test
    fun anOpenStageIsNeverTakenForTheLastOne() = runTest {
        journal.record(CabinTarget.AmbientBrightness, "48")
        stageOpen = true
        assertTrue("an open stage already ran its recovery when it opened", leftovers().restore())
        assertEquals("its journal is left alone", mapOf(CabinTarget.AmbientBrightness to "48"), journal.entries())
    }

    @Test
    fun anUnreachableCarKeepsTheJournal() = runTest {
        journal.record(CabinTarget.AmbientBrightness, "48")
        restore = { error("vehicle service not ready") }
        assertFalse(leftovers().restore())
        assertNull(notices.notice.value)
    }

    @Test
    fun valuesRefusedWhileDrivingWaitForParking() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        journal.record(light, "OFF")
        parked = false
        restore = { listOf(StageRecovery.Failure(light, "driving", retryWhenParked = true)) }
        assertFalse("not done until parked", leftovers().restore())
        assertEquals(NoticeBoard.RESTORE_AFTER_PARKING, notices.notice.value)
        assertEquals("a promise to put back once parked is to watch", NoticeTone.CAUTION, notices.toneOf(notices.notice.value!!))
    }

    @Test
    fun onceParkedItPutsBackTheRestAndTellsWhatWasForgottenWhileDriving() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        journal.record(light, "OFF")
        journal.record(CabinTarget.AmbientBrightness, "48")
        parked = false
        restore = {
            listOf(
                StageRecovery.Failure(light, "driving", retryWhenParked = true),
                StageRecovery.Failure(CabinTarget.AmbientBrightness, "write failed"),
            )
        }
        assertFalse(leftovers().restore())
        val driving = "지난 무대에서 바뀐 차량 설정 중 일부는 되돌리지 못했어요. 차량 설정에서 확인해 주세요. 나머지는 정차하면 되돌릴게요."
        assertEquals(driving, notices.notice.value)
        assertEquals("some not put back went wrong, whatever waits for parking", NoticeTone.ERROR, notices.toneOf(driving))
        parked = true
        restore = { emptyList() }
        // StageRecovery reads the journal on the real IO dispatcher, so wait for the notice rather than advance time.
        val told = "되돌리지 못했던 차량 설정을 원래대로 되돌렸어요. 주행 중에 일부 설정은 되돌리지 못했어요. 차량 설정에서 확인해 주세요."
        assertEquals(told, notices.notice.first { it != driving })
        assertEquals("put back now, but what was forgotten while driving went wrong", NoticeTone.ERROR, notices.toneOf(told))
        assertEquals(emptyMap<CabinTarget, String>(), journal.entries())
    }

    @Test
    fun anUnreachableCarOnceParkedIsGivenUpAndLeftForTheNextStart() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        journal.record(light, "OFF")
        parked = false
        restore = { listOf(StageRecovery.Failure(light, "driving", retryWhenParked = true)) }
        assertFalse(leftovers().restore())
        parked = true
        restore = { error("vehicle service not ready") }
        notices.notice.first { it == NoticeBoard.RECOVERY_GAVE_UP }
        assertEquals("the next start or open tries again", mapOf<CabinTarget, String>(light to "OFF"), journal.entries())
    }

    @Test
    fun atStartItWaitsForTheCarThenGivesUp() = runTest {
        journal.record(CabinTarget.AmbientBrightness, "48")
        restore = { error("vehicle service not ready") }
        leftovers().restoreAtStart()
        assertEquals(NoticeBoard.RECOVERY_GAVE_UP, notices.notice.value)
        assertEquals(NoticeTone.ERROR, notices.toneOf(notices.notice.value!!))
        assertEquals("the next open tries again", mapOf(CabinTarget.AmbientBrightness to "48"), journal.entries())
    }
}
