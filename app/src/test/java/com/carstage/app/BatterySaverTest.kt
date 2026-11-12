package com.carstage.app

import com.carstage.app.safety.DriveState
import com.carstage.app.safety.SafetyRules
import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.Percent
import com.carstage.app.stage.Rgb
import com.carstage.app.stage.Side
import com.carstage.app.vehicle.BatteryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatterySaverTest {
    private val open = MutableStateFlow(false)
    private val battery = MutableStateFlow<BatteryState?>(null)
    private var mayIdle = false
    /** Every notice shown, in order. */
    private val notices = mutableListOf<String>()

    /** Every notice taken down, in order. */
    private val hidden = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    private fun TestScope.saver() = BatterySaver(
        backgroundScope,
        open,
        battery,
        mayIdle = { mayIdle },
        show = { notices += it },
        hide = { hidden += it },
        log = { logged += it },
    ).also { runCurrent() }

    private fun TestScope.percent(value: Int?, charging: Boolean? = false) {
        battery.value = BatteryState(value, charging)
        runCurrent()
    }

    private fun TestScope.setOpen(value: Boolean) {
        open.value = value
        runCurrent()
    }

    private val lights = listOf(
        CabinCommand.AmbientSwitch(true),
        CabinCommand.AmbientColor(Rgb(0xFF0000)),
        CabinCommand.AmbientBrightness(Percent(100)),
        CabinCommand.ReadingLight(Side.DRIVER, true),
    )
    private val turnOn = listOf(CabinCommand.FanStep(3), CabinCommand.SeatVentilation(Side.PASSENGER, 3))
    private val turnOff = listOf(CabinCommand.FanStep(0), CabinCommand.SeatVentilation(Side.PASSENGER, 0))

    @Test
    fun aLowBatteryHoldsBackClimateAndSeatEffectsThatTurnSomethingOn() = runTest {
        val saver = saver()
        setOpen(true)
        percent(19)
        assertTrue(saver.low)
        assertTrue("the lights still answer", lights.all(saver::allows))
        assertTrue("nothing is turned on", turnOn.none(saver::allows))
        assertTrue("turning off saves the battery", turnOff.all(saver::allows))
        assertEquals(listOf(BatterySaver.LOW_NOTICE), notices)
    }

    @Test
    fun enoughBatteryHoldsNothingBack() = runTest {
        val saver = saver()
        setOpen(true)
        percent(BatterySaver.LOW_PERCENT)
        assertFalse(saver.low)
        assertTrue((lights + turnOn + turnOff).all(saver::allows))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun aBatteryAlreadyLowWhenTheStageOpensIsHeldBackFromTheStart() = runTest {
        val saver = saver()
        percent(15)
        assertFalse("nothing is held back while closed", saver.low)
        assertTrue("and nothing is said", notices.isEmpty())
        setOpen(true)
        assertTrue("the reading from before the open counts", saver.low)
        assertTrue(turnOn.none(saver::allows))
        assertEquals(listOf(BatterySaver.LOW_NOTICE), notices)
        setOpen(false)
        setOpen(true)
        assertTrue("a new stage judges the same, unchanged reading again", saver.low)
        assertEquals("and tells the new stage", 2, notices.size)
    }

    @Test
    fun aBatteryBackAtTheFloorLetsClimateAndSeatEffectsBackIn() = runTest {
        val saver = saver()
        setOpen(true)
        percent(19)
        assertTrue(turnOn.none(saver::allows))
        percent(BatterySaver.LOW_PERCENT)
        assertFalse(saver.low)
        assertTrue(turnOn.all(saver::allows))
    }

    @Test
    fun onTheChargerTheEffectsRunEvenOnALowBattery() = runTest {
        val saver = saver()
        setOpen(true)
        percent(12, charging = true)
        assertFalse("power comes from the charger", saver.low)
        assertTrue(turnOn.all(saver::allows))
        percent(12, charging = false)
        assertTrue("unplugged", saver.low)
        percent(12, charging = null)
        assertTrue("an unknown switch is not charging", saver.low)
        percent(13, charging = true)
        assertFalse(saver.low)
        assertEquals("battery charging at 13%: climate and seat effects again", logged.last())
    }

    @Test
    fun theScreenIsToldOncePerStageAndAFallingBatteryIsLoggedOnce() = runTest {
        saver()
        setOpen(true)
        percent(19)
        percent(18)
        percent(25)
        percent(15)
        assertEquals("once on this stage", 1, notices.size)
        assertEquals(2, logged.count { it.startsWith("battery low") })
        setOpen(false)
        setOpen(true)
        percent(14)
        assertEquals("again on a new stage", 2, notices.size)
    }

    @Test
    fun anUnreadableBatteryKeepsTheLastJudgementAndClosingClearsIt() = runTest {
        val saver = saver()
        setOpen(true)
        percent(10)
        percent(null)
        assertTrue("one failed read does not lift the hold", saver.low)
        battery.value = null
        runCurrent()
        assertTrue(saver.low)
        setOpen(false)
        assertFalse("nothing is held back once closed", saver.low)
        setOpen(true)
        assertFalse("nor on a new stage until the battery is read as low", saver.low)
    }

    @Test
    fun theCommandsHeldBackAreCountedPerStage() = runTest {
        val saver = saver()
        setOpen(true)
        percent(10)
        repeat(3) { turnOn.forEach(saver::allows) }
        turnOff.forEach(saver::allows)
        setOpen(false)
        assertEquals("battery rules held back 6 commands in this stage", logged.last())
        setOpen(true)
        setOpen(false)
        assertEquals("nothing held back on the next stage, nothing logged", 1, logged.count { it.startsWith("battery rules held back") })
    }

    @Test
    fun aCarThatMayIdleIsWarnedOncePerStage() = runTest {
        mayIdle = true
        saver()
        setOpen(true)
        advanceTimeBy(BatterySaver.IDLE_WARNING_MS - 1)
        assertTrue(notices.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(BatterySaver.IDLE_NOTICE), notices)
        advanceTimeBy(5 * BatterySaver.IDLE_WARNING_MS)
        assertEquals("once per stage", 1, notices.size)
        setOpen(false)
        setOpen(true)
        advanceTimeBy(BatterySaver.IDLE_WARNING_MS)
        runCurrent()
        assertEquals("a new stage counts from its opening", 2, notices.size)
    }

    @Test
    fun aCarThatMayIdleIsAskedWhenTheWarningIsDue() = runTest {
        saver()
        setOpen(true)
        advanceTimeBy(BatterySaver.IDLE_WARNING_MS - 1)
        mayIdle = true
        advanceTimeBy(1)
        runCurrent()
        assertEquals("the kind of car learned meanwhile counts", listOf(BatterySaver.IDLE_NOTICE), notices)
        assertEquals("stage open for over 30 min in a car that may idle: warning about idling", logged.last())
    }

    @Test
    fun anEvIsNotWarnedAboutIdling() = runTest {
        saver()
        setOpen(true)
        advanceTimeBy(3 * BatterySaver.IDLE_WARNING_MS)
        runCurrent()
        assertTrue(notices.isEmpty())
    }

    @Test
    fun aLowBatteryLetsALevelGoDownButNotUp() = runTest {
        val saver = saver()
        setOpen(true)
        assertTrue(saver.allows(CabinCommand.SeatVentilation(Side.PASSENGER, 3)))
        percent(15)
        assertTrue("down from 3", saver.allows(CabinCommand.SeatVentilation(Side.PASSENGER, 1)))
        assertFalse("back up from 1", saver.allows(CabinCommand.SeatVentilation(Side.PASSENGER, 2)))
        assertTrue("the same costs no more, so a director sending it again settles", saver.allows(CabinCommand.SeatVentilation(Side.PASSENGER, 1)))
        assertTrue(saver.allows(CabinCommand.SeatVentilation(Side.PASSENGER, 0)))
        assertFalse("nothing was let through for the other seat", saver.allows(CabinCommand.SeatVentilation(Side.DRIVER, 1)))
        assertFalse("nor for the fan", saver.allows(CabinCommand.FanStep(1)))
        assertFalse("a cooler setting draws on the battery", saver.allows(CabinCommand.Temperature(Side.DRIVER, -2f)))
        assertTrue("a fan below where the stage found it only saves it", saver.allows(CabinCommand.FanStep(-1)))
        assertTrue("back to where the stage found it is always let through", saver.allows(CabinCommand.Temperature(Side.DRIVER, 0f)))
        setOpen(false)
        assertTrue("not low once closed", saver.allows(CabinCommand.FanStep(5)))
        setOpen(true)
        percent(15)
        assertFalse("a new stage forgets the levels let through before", saver.allows(CabinCommand.FanStep(4)))
    }

    @Test
    fun theLowNoticeComesDownWhenTheBatteryIsNoLongerLowOrTheStageCloses() = runTest {
        saver()
        setOpen(true)
        hidden.clear()
        percent(15)
        assertTrue(hidden.isEmpty())
        percent(15, charging = true)
        assertEquals("plugged in: the effects run again", listOf(BatterySaver.LOW_NOTICE), hidden)
        percent(15)
        assertEquals("told once per stage", 1, notices.size)
        setOpen(false)
        assertEquals("the stage closed", listOf(BatterySaver.LOW_NOTICE, BatterySaver.LOW_NOTICE, BatterySaver.IDLE_NOTICE), hidden)
    }

    @Test
    fun theIdleWarningComesDownWhenTheStageCloses() = runTest {
        mayIdle = true
        saver()
        setOpen(true)
        advanceTimeBy(BatterySaver.IDLE_WARNING_MS)
        runCurrent()
        assertEquals(listOf(BatterySaver.IDLE_NOTICE), notices)
        setOpen(false)
        assertTrue(BatterySaver.IDLE_NOTICE in hidden)
    }

    @Test
    fun theBatteryOnlyLearnsTheLevelsTheSafetyRulesLetThrough() = runTest {
        val saver = saver()
        setOpen(true)
        val clear = DriveState(DriveState.Motion(parked = true, restricted = false), DriveState.Climate(defrosting = false, wiping = false))
        var drive = clear.copy(climate = DriveState.Climate(defrosting = true, wiping = false))
        val gate = cabinGate({ SafetyRules.allows(it, drive) }, saver::allows)
        assertFalse("the defroster holds the fan back", gate(CabinCommand.FanStep(5)))
        drive = clear
        percent(15)
        assertFalse("5 was never sent, so 4 is not lower than anything sent", gate(CabinCommand.FanStep(4)))
    }

    @Test
    fun theNoticesNameTheirNumbers() {
        assertTrue(BatterySaver.LOW_NOTICE.contains("${BatterySaver.LOW_PERCENT}%"))
        assertTrue(BatterySaver.IDLE_NOTICE.contains("${BatterySaver.IDLE_WARNING_MS / 60_000}분"))
    }

    @Test
    fun theEngineSendsOnlyWhatBothTheSafetyRulesAndTheBatteryAllow() = runTest {
        val saver = saver()
        setOpen(true)
        val parked = DriveState(DriveState.Motion(parked = true, restricted = false), DriveState.Climate(defrosting = false, wiping = false))
        var drive = parked
        val gate = cabinGate({ SafetyRules.allows(it, drive) }, saver::allows)
        val seatOn = CabinCommand.SeatVentilation(Side.PASSENGER, 3)
        assertTrue(gate(seatOn))
        percent(15)
        assertFalse("the battery alone holds it back", gate(CabinCommand.SeatVentilation(Side.PASSENGER, 4)))
        percent(80)
        drive = parked.copy(motion = DriveState.Motion(parked = null))
        assertFalse("the safety rules alone hold it back", gate(seatOn))
        assertFalse("the lights too", gate(CabinCommand.AmbientBrightness(Percent(50))))
    }
}
