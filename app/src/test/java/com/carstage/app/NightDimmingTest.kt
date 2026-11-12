package com.carstage.app

import com.carstage.app.stage.GlowDirector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NightDimmingTest {
    private val open = MutableStateFlow(false)
    private val night = MutableStateFlow<Boolean?>(null)

    /** What the first screen shows. */
    private val board = NoticeBoard()
    private val logged = mutableListOf<String>()

    private fun TestScope.dimming() = NightDimming(
        backgroundScope,
        open,
        night,
        show = { board.add(it, NoticeTone.INFO) },
        hide = board::remove,
        log = { logged += it },
    ).also { runCurrent() }

    private fun TestScope.set(isOpen: Boolean = open.value, car: Boolean? = night.value) {
        open.value = isOpen
        night.value = car
        runCurrent()
    }

    private val nightLine = "the stage's light dims to ${GlowDirector.NIGHT_CEILING}% at most"
    private val dayLine = "the stage's light uses its full range"

    @Test
    fun aCarThatDoesNotSayIsInTheDay() = runTest {
        val dimming = dimming()
        set(isOpen = true)
        assertFalse(dimming.dim)
        assertNull(board.notice.value)
        assertTrue("nothing changed, nothing logged", logged.isEmpty())
    }

    @Test
    fun theCarsNightDimsTheLightAndAnOpenStageSaysSoWhileItLasts() = runTest {
        val dimming = dimming()
        set(car = true)
        assertTrue("dim before a stage opens, so its first light is", dimming.dim)
        assertNull("no stage, no notice", board.notice.value)
        set(isOpen = true)
        assertEquals(NightDimming.NOTICE, board.notice.value)
        set(car = false)
        assertFalse(dimming.dim)
        assertNull("day takes the notice down", board.notice.value)
        set(car = true)
        assertEquals("night again, said again", NightDimming.NOTICE, board.notice.value)
        board.add(BatterySaver.LOW_NOTICE, NoticeTone.CAUTION)
        set(isOpen = false)
        assertEquals("the stage closes: only its own notice goes", BatterySaver.LOW_NOTICE, board.notice.value)
        assertTrue("still night for the next stage", dimming.dim)
        assertEquals(listOf("night by the car: $nightLine", "day by the car: $dayLine", "night by the car: $nightLine"), logged)
    }

    @Test
    fun aCarThatStopsSayingTurnsBackToDay() = runTest {
        val dimming = dimming()
        set(isOpen = true, car = true)
        set(car = null)
        assertFalse(dimming.dim)
        assertNull(board.notice.value)
        assertEquals("day as the car does not say: $dayLine", logged.last())
    }

    @Test
    fun aTestMakesItNightOrDayWhateverTheCarSaysUntilItFollowsTheCarAgain() = runTest {
        val dimming = dimming()
        dimming.force(true)
        runCurrent()
        assertTrue(dimming.dim)
        assertEquals("night by a test: $nightLine", logged.last())
        set(car = false)
        assertTrue("the car's day does not undo it", dimming.dim)
        dimming.force(false)
        set(car = true)
        assertFalse("nor its night", dimming.dim)
        dimming.force(null)
        runCurrent()
        assertTrue("following the car's night again", dimming.dim)
    }
}
