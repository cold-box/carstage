package com.carstage.app.lab

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import com.carstage.app.stage.CabinCommand.AmbientBrightness
import com.carstage.app.stage.CabinCommand.AmbientColor
import com.carstage.app.stage.CabinCommand.AmbientSwitch
import com.carstage.app.stage.CabinCommand.FanStep
import com.carstage.app.stage.CabinCommand.ReadingLight
import com.carstage.app.stage.CabinCommand.SeatVentilation
import com.carstage.app.stage.Cue
import com.carstage.app.stage.Percent
import com.carstage.app.stage.Rgb
import com.carstage.app.stage.Side
import com.carstage.app.stage.SimulatedCabin
import com.carstage.app.stage.StageEngine
import com.carstage.app.stage.VehicleCabin
import com.carstage.app.stage.play
import com.carstage.app.vehicle.VehicleSession
import com.carstage.app.vehicle.awaitVehicle
import com.carstage.app.vehicle.awaitVehicle2
import com.carstage.app.vehicle.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Plays a short test cue on the real cabin and the simulator, then checks per target that the
 * stage engine put things back, comparing the raw values the SDK reports. A target only proves
 * restore when the car actually changed during the cue; on the emulator usually only the ambient
 * switch and seat ventilation do, so the rest is reported as unverified.
 */
class StageProbe(
    private val session: VehicleSession,
    private val report: (String) -> Unit,
) {
    /**
     * Sets the ambient switch to AUTOMATIC first, because a car in AUTOMATIC must come back to
     * AUTOMATIC rather than OFF, and puts the original switch value back at the end.
     */
    suspend fun run() {
        val original = try {
            setAmbientSwitch(LightSwitch.AUTOMATIC)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("could not set ambient switch to AUTOMATIC: ${e.describe()}")
            null
        }
        original?.let { report("ambient switch set to AUTOMATIC for the test (was $it)") }
        try {
            runStage()
        } finally {
            if (original != null) {
                withContext(NonCancellable) {
                    try {
                        setAmbientSwitch(original)
                    } catch (e: Exception) {
                        report("could not put ambient switch back to $original: ${e.describe()}")
                    }
                }
            }
        }
    }

    /** Sets the ambient switch to [value] and returns the previous value, or null when [value] is unsupported. */
    private suspend fun setAmbientSwitch(value: LightSwitch): LightSwitch? {
        session.awaitReady().getOrThrow()
        val light = session.light
        val (_, switches) = awaitVehicle2<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?> { ok, fail ->
            light.checkAmbientLightSwitchCapabilities(ok, fail)
        }
        val area = switches?.entries?.firstOrNull { value in it.value }?.key ?: return null
        val previous = awaitVehicle<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) }
        awaitVehicle2<SeatArea?, LightSwitch?> { ok, fail -> light.setAmbientLightSwitch(area, value, ok, fail) }
        return previous
    }

    private suspend fun runStage() = coroutineScope {
        val vehicle = VehicleCabin(session, report)
        val simulator = SimulatedCabin()
        val engine = StageEngine(listOf(vehicle, simulator), report)
        val before = vehicle.read()
        report("vehicle before: ${before.raw}")
        var during = before
        lateinit var result: StageEngine.StopResult
        report("driving: ${engine.start(this)}")
        try {
            engine.play(TEST_CUE)
            during = vehicle.read()
            report("simulator during: ${simulator.state.value}")
        } finally {
            result = engine.stop()
        }
        val after = vehicle.read()
        // Raw SDK values, so AUTOMATIC versus OFF or an exact brightness step counts.
        val verdicts = before.raw.keys.map { target ->
            val changed = during.raw[target] != before.raw[target]
            val back = after.raw[target] == before.raw[target]
            target to when {
                !back -> "DIFFERS: before ${before.raw[target]}, after ${after.raw[target]}"
                changed -> "restored to ${before.raw[target]}"
                else -> "unverified (the car did not change it)"
            }
        }
        verdicts.forEach { (target, verdict) -> report("vehicle $target: $verdict") }
        val simulatorBack = simulator.state.value == SimulatedCabin.DEFAULT
        val ok = result.restoredAll && simulatorBack && verdicts.none { it.second.startsWith("DIFFERS") }
        report("stage restore: ${if (ok) "PASS" else "FAIL"} (restore failures=${result.failures.size}, deferred=${result.deferred.size}, simulator back=$simulatorBack)")
    }

    private companion object {
        val TEST_CUE = Cue.build {
            at(
                0,
                AmbientSwitch(true),
                AmbientColor(Rgb(0xFF1744)),
                AmbientBrightness(Percent(100)),
                ReadingLight(Side.DRIVER, true),
            )
            // The emulator refuses fan writes while HVAC is off; a refused write must not need restoring.
            at(300, AmbientColor(Rgb(0x2979FF)), SeatVentilation(Side.DRIVER, 3), FanStep(5))
            at(600, ReadingLight(Side.PASSENGER, true), SeatVentilation(Side.PASSENGER, 2))
            at(900, AmbientColor(Rgb(0xFFC400)), AmbientBrightness(Percent(60)))
            at(1_200, ReadingLight(Side.DRIVER, false))
        }
    }
}
