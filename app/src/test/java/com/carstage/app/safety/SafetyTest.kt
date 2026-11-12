package com.carstage.app.safety

import com.carstage.app.safety.DriveState.Block
import com.carstage.app.safety.DriveState.Climate
import com.carstage.app.safety.DriveState.Motion
import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.Percent
import com.carstage.app.stage.Side
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyTest {
    private val parkedMotion = Motion(parked = true, gear = "GEAR_PARK", restricted = false)
    private val clear = Climate(defrosting = false, wiping = false)
    private val parked = DriveState(parkedMotion, clear)
    private val fan = CabinCommand.FanStep(4)
    private val lights = listOf(
        CabinCommand.AmbientBrightness(Percent(80)),
        CabinCommand.ReadingLight(Side.DRIVER, true),
        CabinCommand.SeatVentilation(Side.PASSENGER, 2),
    )

    private class FakeReader(
        var motion: suspend () -> Motion,
        var climate: suspend () -> Climate,
    ) : DriveReader {
        override suspend fun motion() = motion.invoke()
        override suspend fun climate() = climate.invoke()
    }

    private val logs = mutableListOf<String>()
    private val reader = FakeReader({ parkedMotion }, { clear })
    private val guard = SafetyGuard(reader, { line, _ -> logs += line }, pollMs = 500, climatePollMs = 2_000)

    private fun motion(parked: Boolean?, restricted: Boolean? = false) = DriveState(Motion(parked = parked, restricted = restricted), clear)

    @Test
    fun theStageRunsOnlyWhenKnownToBeParkedWithoutRestrictions() {
        assertNull(parked.block)
        assertTrue(parked.stageAllowed)
        assertNull("unknown restrictions do not block", motion(parked = true, restricted = null).block)
        assertEquals(Block.NOT_PARKED, motion(parked = false).block)
        assertEquals("driving wins over restrictions", Block.NOT_PARKED, motion(parked = false, restricted = true).block)
        assertEquals(Block.GEAR_UNKNOWN, motion(parked = null).block)
        assertEquals(Block.RESTRICTED, motion(parked = true, restricted = true).block)
    }

    @Test
    fun eachBlockTellsTheUserWhatHappened() {
        assertTrue("기어" in Block.GEAR_UNKNOWN.refusal && "기어" in Block.GEAR_UNKNOWN.stopNotice)
        assertTrue("주행" in Block.NOT_PARKED.stopNotice)
        assertFalse("a parked car is not told it drove", "주행을 시작" in Block.RESTRICTED.stopNotice)
        assertEquals(Block.entries.size, Block.entries.map { it.stopNotice }.toSet().size)
    }

    @Test
    fun climateEffectsRunOnlyWhileTheDefrosterAndWipersAreKnownOff() {
        assertTrue(SafetyRules.allows(fan, parked))
        assertFalse("unknown blocks", SafetyRules.allows(fan, parked.copy(climate = Climate())))
        assertFalse(SafetyRules.allows(fan, parked.copy(climate = clear.copy(defrosting = true))))
        assertFalse(SafetyRules.allows(fan, parked.copy(climate = clear.copy(wiping = true))))
        assertTrue(lights.all { SafetyRules.allows(it, parked.copy(climate = Climate(defrosting = true, wiping = true))) })
        val cooler = CabinCommand.Temperature(Side.DRIVER, -2f)
        assertTrue("a temperature setting is climate too", SafetyRules.allows(cooler, parked))
        assertFalse(SafetyRules.allows(cooler, parked.copy(climate = clear.copy(defrosting = true))))
        assertFalse(SafetyRules.allows(cooler, parked.copy(climate = Climate())))
        val back = CabinCommand.Temperature(Side.DRIVER, 0f)
        assertFalse("a change back waits for the defroster too; the director keeps asking", SafetyRules.allows(back, parked.copy(climate = clear.copy(defrosting = true))))
        assertFalse(SafetyRules.allows(CabinCommand.FanStep(0), parked.copy(climate = Climate())))
        assertTrue(SafetyRules.allows(CabinCommand.FanStep(1), parked))
    }

    @Test
    fun nothingIsSentUnlessTheStageMayRun() {
        Block.entries.forEach { block ->
            val state = when (block) {
                Block.GEAR_UNKNOWN -> motion(parked = null)
                Block.NOT_PARKED -> motion(parked = false)
                Block.RESTRICTED -> motion(parked = true, restricted = true)
            }
            assertTrue(block.name, (lights + fan).none { SafetyRules.allows(it, state) })
        }
    }

    @Test
    fun theGuardDecidesCommandsFromTheLatestState() = runTest {
        assertFalse("before any check nothing is known, so nothing is sent", guard.allows(lights[0]))
        guard.check()
        assertTrue(guard.allows(lights[0]))
        assertTrue(guard.allows(fan))
        reader.climate = { clear.copy(wiping = true) }
        guard.checkClimate()
        assertFalse(guard.allows(fan))
        assertTrue(guard.allows(lights[0]))
        reader.motion = { parkedMotion.copy(parked = false) }
        guard.checkMotion()
        assertFalse(guard.allows(lights[0]))
    }

    @Test
    fun aReaderThatThrowsCountsAsUnknownAndIsLoggedOnce() = runTest {
        reader.motion = { error("binder died") }
        assertEquals(Block.GEAR_UNKNOWN, guard.checkMotion().block)
        guard.checkMotion()
        assertTrue(logs.single().contains("binder died"))
        reader.motion = { parkedMotion }
        assertNull(guard.checkMotion().block)
        assertTrue(logs.last().contains("works again"))
    }

    @Test
    fun theWatchChecksAtOnce() = runTest {
        reader.motion = { parkedMotion.copy(parked = false) }
        val unsafe = async { guard.awaitUnsafe() }
        runCurrent()
        assertTrue(unsafe.isCompleted)
        assertEquals(Block.NOT_PARKED, unsafe.await().block)
    }

    @Test
    fun theWatchReturnsOnThePollAfterDrivingStarts() = runTest {
        val unsafe = async { guard.awaitUnsafe() }
        advanceTimeBy(1_100)
        runCurrent()
        assertFalse(unsafe.isCompleted)
        reader.motion = { parkedMotion.copy(parked = false) }
        advanceTimeBy(400)
        runCurrent()
        assertTrue(unsafe.isCompleted)
        assertEquals(false, unsafe.await().motion.parked)
    }

    @Test
    fun oneUnreadableGearPausesCommandsAndTwoInARowStopTheStage() = runTest {
        val unsafe = async { guard.awaitUnsafe() }
        runCurrent()
        reader.motion = { Motion() }
        advanceTimeBy(501)
        assertFalse("one lost reply does not end the song", unsafe.isCompleted)
        assertFalse("but nothing is sent meanwhile", guard.allows(lights[0]))
        reader.motion = { parkedMotion }
        advanceTimeBy(500)
        assertTrue(guard.allows(lights[0]))
        reader.motion = { Motion() }
        advanceTimeBy(500)
        assertFalse("a good read in between starts the count again", unsafe.isCompleted)
        advanceTimeBy(500)
        assertTrue(unsafe.isCompleted)
        assertEquals(Block.GEAR_UNKNOWN, unsafe.await().block)
    }

    @Test
    fun aHungClimateReadDoesNotDelayNoticingThatTheCarDrives() = runTest {
        reader.climate = { awaitCancellation() }
        val unsafe = async { guard.awaitUnsafe() }
        advanceTimeBy(1_100)
        assertFalse("climate is unknown while the read hangs", guard.allows(fan))
        assertTrue(guard.allows(lights[0]))
        reader.motion = { parkedMotion.copy(parked = false) }
        advanceTimeBy(500)
        assertTrue(unsafe.isCompleted)
    }

    @Test
    fun climateFromAnEarlierWatchIsNotTrusted() = runTest {
        guard.check()
        assertTrue(guard.allows(fan))
        val read = CompletableDeferred<Climate>()
        reader.climate = { read.await() }
        val unsafe = async { guard.awaitUnsafe() }
        runCurrent()
        assertFalse("the fan waits for a fresh read", guard.allows(fan))
        read.complete(clear)
        runCurrent()
        assertTrue(guard.allows(fan))
        reader.motion = { parkedMotion.copy(parked = false) }
        advanceTimeBy(501)
        assertTrue(unsafe.isCompleted)
    }
}
