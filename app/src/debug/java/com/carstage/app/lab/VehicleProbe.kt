package com.carstage.app.lab

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import android.os.SystemClock
import com.carstage.app.vehicle.VehicleSession
import com.carstage.app.vehicle.awaitVehicle
import com.carstage.app.vehicle.awaitVehicle2
import com.carstage.app.vehicle.describe
import com.carstage.app.vehicle.describeAreas
import com.carstage.app.vehicle.describeByArea
import com.carstage.app.vehicle.hex
import com.carstage.app.vehicle.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checks the Vehicle SDK surfaces CarStage depends on (ambient/reading/cabin light, HVAC fan,
 * temperature, seat occupancy/ventilation, gear) and reports support, values and latencies.
 * Every control write is restored to the value read before it.
 */
class VehicleProbe(
    private val session: VehicleSession,
    private val report: (String) -> Unit,
) {
    private enum class WriteOutcome { PERSISTED, ACK_ONLY, REJECTED }

    suspend fun run() {
        try {
            session.initialize()
        } catch (e: Exception) {
            report("initialize failed: ${e.describe()}")
            return
        }
        report("initialized")
        awaitReady()
        step("gear") { probeGear() }
        step("seat occupancy") { probeSeatOccupancy() }
        step("odometer") { probeOdometer() }
        val colorAreas = step("ambient capabilities") { probeAmbientCapabilities() }.orEmpty()
        val originalSwitches = step("ambient switch on") { switchAmbientOn(colorAreas) }.orEmpty()
        try {
            step("ambient brightness write") { probeAmbientBrightnessWrite(colorAreas) }
            val palette = step("ambient color write") { probeAmbientColorWrite(colorAreas) }
            if (palette != null) step("ambient ping-pong") { probeAmbientPingPong(colorAreas, palette) }
        } finally {
            restoreAmbientSwitches(originalSwitches)
        }
        step("reading light") { probeReadingLights() }
        step("cabin light") { probeCabinLight() }
        step("hvac fan") { probeHvacFan() }
        step("hvac temperature/dual") { probeHvacTemperatureAndDual() }
        step("seat ventilation") { probeSeatVentilation() }
    }

    /** Health check for `scripts/doctor.sh`: true when the vehicle service answers a gear read. */
    suspend fun smoke(): Boolean {
        try {
            session.initialize()
        } catch (e: Exception) {
            report("initialize failed: ${e.describe()}")
            return false
        }
        awaitReady()
        return step("gear") { probeGear() } != null
    }

    private suspend fun <T> step(name: String, block: suspend () -> T): T? {
        report("-- $name")
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("$name failed: ${e.describe()}")
            null
        }
    }

    private suspend fun awaitReady() {
        session.awaitReady().fold(
            { report("service ready after $it attempt(s)") },
            { report("service readiness unknown: ${it.describe()}") },
        )
    }

    private suspend fun probeGear() {
        val gear = session.readGear()
        report("gear=$gear parked=${gear == VehicleGear.GEAR_PARK}")
    }

    /**
     * Each Odometer read with its capability check: the six trip reads (current, cumulative, since
     * refill; time and distance) and the total distance driven. The MC's greeting uses only the
     * current trip's time and distance (docs/12).
     */
    private suspend fun probeOdometer() {
        val odometer = session.odometer
        suspend fun <T> read(name: String, check: ((CapabilityConfig?) -> Unit, (Exception) -> Unit) -> Unit, get: ((T) -> Unit, (Exception) -> Unit) -> Unit) {
            val config = runCatching { awaitVehicle<CapabilityConfig?>(check) }
            val value = runCatching { awaitVehicle<T>(get) }
            report("odometer $name access=${config.map { it?.accessType }.text()} value=${value.text()}")
        }
        read<Int?>("trip current time", odometer::checkTripCurrentDrivingTimeCapabilities, odometer::getTripCurrentDrivingTime)
        read<Float?>("trip current distance", odometer::checkTripCurrentDrivingDistanceCapabilities, odometer::getTripCurrentDrivingDistance)
        read<Int?>("trip cumulative time", odometer::checkTripCumulativeDrivingTimeCapabilities, odometer::getTripCumulativeDrivingTime)
        read<Float?>("trip cumulative distance", odometer::checkTripCumulativeDrivingDistanceCapabilities, odometer::getTripCumulativeDrivingDistance)
        read<Int?>("trip since refill time", odometer::checkTripSinceRefillDrivingTimeCapabilities, odometer::getTripSinceRefillDrivingTime)
        read<Float?>("trip since refill distance", odometer::checkTripSinceRefillDrivingDistanceCapabilities, odometer::getTripSinceRefillDrivingDistance)
        read<Float?>("distance driven", odometer::checkCurrentDistanceDrivenCapabilities, odometer::getCurrentDistanceDriven)
    }

    private suspend fun probeSeatOccupancy() {
        val seat = session.seat
        val (config, areas) = awaitVehicle2<CapabilityConfig?, List<SeatArea>?> { ok, fail ->
            seat.checkSeatOccupancyCapabilities(ok, fail)
        }
        report("occupancy access=${config?.accessType} areas=${areas.describeAreas()}")
        areas.orEmpty().forEach { area ->
            val occupied = runCatching {
                awaitVehicle<Boolean?> { ok, fail -> seat.getCurrentSeatOccupancy(area, ok, fail) }
            }
            report("occupancy ${area.label()}=${occupied.text()}")
        }
    }

    private suspend fun probeAmbientCapabilities(): List<SeatArea> {
        val light = session.light
        val (colorConfig, colorAreas) = awaitVehicle2<CapabilityConfig?, List<SeatArea>?> { ok, fail ->
            light.checkAmbientLightBrightnessColorCapabilities(ok, fail)
        }
        report("ambient color access=${colorConfig?.accessType} areas=${colorAreas.describeAreas()}")

        runCatching {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?> { ok, fail ->
                light.checkAmbientLightBrightnessCapabilities(ok, fail)
            }
        }.onSuccess { (config, bounds) ->
            report("ambient brightness access=${config?.accessType} bounds=${bounds.describeByArea()}")
        }.onFailure { report("ambient brightness capability failed: ${it.describe()}") }

        runCatching {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?> { ok, fail ->
                light.checkAmbientLightSwitchCapabilities(ok, fail)
            }
        }.onSuccess { (config, switches) ->
            report("ambient switch access=${config?.accessType} options=${switches.describeByArea()}")
        }.onFailure { report("ambient switch capability failed: ${it.describe()}") }

        runCatching {
            awaitVehicle<CapabilityConfig?> { ok, fail ->
                light.checkAmbientColorAutoByDriveModeEnabledCapabilities(ok, fail)
            }
        }.onSuccess { config ->
            val enabled = runCatching {
                awaitVehicle<Boolean?> { ok, fail -> light.getAmbientColorAutoByDriveModeEnabled(ok, fail) }
            }
            report("ambient auto color by drive mode access=${config?.accessType} enabled=${enabled.text()}")
        }.onFailure { report("ambient auto color capability failed: ${it.describe()}") }

        colorAreas.orEmpty().forEach { area ->
            val switch = runCatching {
                awaitVehicle<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) }
            }
            val brightness = runCatching {
                awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) }
            }
            val color = runCatching {
                awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) }
            }
            report(
                "ambient ${area.label()} switch=${switch.text()} brightness=${brightness.text()} " +
                    "color=${color.map { it.hex() }.text()}"
            )
        }
        return colorAreas.orEmpty()
    }

    /** Turns ambient light ON where it is off, since color writes may be ignored while the light is off. */
    private suspend fun switchAmbientOn(areas: List<SeatArea>): Map<SeatArea, LightSwitch> {
        val light = session.light
        val originals = mutableMapOf<SeatArea, LightSwitch>()
        areas.forEach { area ->
            val original = awaitVehicle<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) }
            if (original == null || original == LightSwitch.ON) {
                report("ambient switch ${area.label()} already $original")
                return@forEach
            }
            val ack = runCatching {
                awaitVehicle2<SeatArea?, LightSwitch?> { ok, fail -> light.setAmbientLightSwitch(area, LightSwitch.ON, ok, fail) }
            }
            delay(STATE_SETTLE_MS)
            val readBack = runCatching {
                awaitVehicle<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) }
            }
            report("ambient switch ${area.label()} $original->ON ack=${ack.map { it.second }.text()} readback=${readBack.text()}")
            if (ack.isSuccess) originals[area] = original
        }
        return originals
    }

    private suspend fun restoreAmbientSwitches(originals: Map<SeatArea, LightSwitch>) {
        originals.forEach { (area, original) ->
            runCatching {
                awaitVehicle2<SeatArea?, LightSwitch?> { ok, fail -> session.light.setAmbientLightSwitch(area, original, ok, fail) }
            }.onFailure { report("restore ambient switch ${area.label()} failed: ${it.describe()}") }
        }
    }

    private suspend fun probeAmbientBrightnessWrite(areas: List<SeatArea>) {
        val light = session.light
        val area = areas.firstOrNull() ?: return
        val original = awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) } ?: return
        val target = if (original >= 50) 10 else 90
        val ack = runCatching {
            awaitVehicle2<SeatArea?, Int?> { ok, fail -> light.setAmbientLightBrightness(area, target, ok, fail) }
        }
        delay(STATE_SETTLE_MS)
        val readBack = runCatching { awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) } }
        report("ambient brightness ${area.label()} $original->$target ack=${ack.map { it.second }.text()} readback=${readBack.text()}")
        runCatching {
            awaitVehicle2<SeatArea?, Int?> { ok, fail -> light.setAmbientLightBrightness(area, original, ok, fail) }
        }.onFailure { report("restore ambient brightness failed: ${it.describe()}") }
    }

    /** Tries candidate color encodings on the first area and returns two that the vehicle accepted. */
    private suspend fun probeAmbientColorWrite(areas: List<SeatArea>): Pair<Int, Int>? {
        val light = session.light
        val area = areas.firstOrNull() ?: run {
            report("no ambient color area")
            return null
        }
        val original = awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) }
        report("original color ${area.label()}=${original.hex()}")

        val updates = Channel<Int?>(Channel.UNLIMITED)
        val listener = object : AmbientLightBrightnessColorListener {
            override fun onBrightnessColorUpdated(value: Int?) {
                updates.trySend(value)
            }

            override fun onFailed(exception: Exception) {
                report("color listener failed: ${exception.describe()}")
            }
        }
        light.registerAmbientLightBrightnessColorListener(area, listener)
        try {
            delay(LISTENER_SETTLE_MS)
            updates.drain()
            val outcomes = COLOR_CANDIDATES.associateWith { writeColor(area, it, updates) }
            val persisted = outcomes.filterValues { it == WriteOutcome.PERSISTED }.keys.toList()
            val acked = outcomes.filterValues { it != WriteOutcome.REJECTED }.keys.toList()
            report("color values persisted=${persisted.map { it.hex() }} acked=${acked.map { it.hex() }}")
            val usable = if (persisted.size >= 2) persisted else acked
            return if (usable.size >= 2) usable[0] to usable[1] else null
        } finally {
            if (original != null) restoreColor(area, original)
            light.unregisterAmbientLightBrightnessColorListener(area, listener)
        }
    }

    private suspend fun writeColor(area: SeatArea, value: Int, updates: Channel<Int?>): WriteOutcome {
        val light = session.light
        val started = SystemClock.elapsedRealtime()
        val ack = runCatching {
            awaitVehicle2<SeatArea?, Int?> { ok, fail -> light.setAmbientLightBrightnessColor(area, value, ok, fail) }
        }
        val ackMs = SystemClock.elapsedRealtime() - started
        if (ack.isFailure) {
            report("set color ${value.hex()} rejected after ${ackMs}ms: ${ack.exceptionOrNull()?.describe()}")
            return WriteOutcome.REJECTED
        }
        val listenerMs = withTimeoutOrNull(LISTENER_TIMEOUT_MS) {
            while (updates.receive() != value) Unit
            SystemClock.elapsedRealtime() - started
        }
        val readBack = runCatching {
            awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) }
        }
        report(
            "set color ${value.hex()} ack=${ackMs}ms accepted=${ack.getOrNull()?.second.hex()} " +
                "listener=${listenerMs?.let { "${it}ms" } ?: "none"} readback=${readBack.map { it.hex() }.text()}"
        )
        return if (readBack.getOrNull() == value || listenerMs != null) WriteOutcome.PERSISTED else WriteOutcome.ACK_ONLY
    }

    private suspend fun restoreColor(area: SeatArea, value: Int) {
        runCatching {
            awaitVehicle2<SeatArea?, Int?> { ok, fail ->
                session.light.setAmbientLightBrightnessColor(area, value, ok, fail)
            }
        }.onFailure { report("restore color ${area.label()} failed: ${it.describe()}") }
    }

    /** Swaps two colors between up to two areas at shrinking intervals to find a usable reveal speed. */
    private suspend fun probeAmbientPingPong(areas: List<SeatArea>, palette: Pair<Int, Int>) = coroutineScope {
        val light = session.light
        val targets = areas.take(2)
        val originals = targets.associateWith { area ->
            runCatching { awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) } }.getOrNull()
        }
        try {
            for (intervalMs in PING_PONG_INTERVALS_MS) {
                val acks = mutableListOf<Long>()
                var failures = 0
                repeat(PING_PONG_STEPS) { step ->
                    val stepStarted = SystemClock.elapsedRealtime()
                    val results = targets.mapIndexed { index, area ->
                        val color = if ((step + index) % 2 == 0) palette.first else palette.second
                        async { timedColorSet(area, color) }
                    }.awaitAll()
                    results.forEach { latency -> if (latency == null) failures++ else acks += latency }
                    delay((intervalMs - (SystemClock.elapsedRealtime() - stepStarted)).coerceAtLeast(0))
                }
                report(
                    "ping-pong interval=${intervalMs}ms areas=${targets.describeAreas()} " +
                        "ack avg=${acks.average().toInt()}ms max=${acks.maxOrNull()}ms " +
                        "failures=$failures/${PING_PONG_STEPS * targets.size}"
                )
            }
        } finally {
            originals.forEach { (area, value) -> if (value != null) restoreColor(area, value) }
        }
    }

    private suspend fun timedColorSet(area: SeatArea, color: Int): Long? {
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            awaitVehicle2<SeatArea?, Int?> { ok, fail -> session.light.setAmbientLightBrightnessColor(area, color, ok, fail) }
            SystemClock.elapsedRealtime() - started
        }.getOrNull()
    }

    private suspend fun probeReadingLights() {
        val light = session.light
        val (config, switches) = awaitVehicle2<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?> { ok, fail ->
            light.checkReadingLightSwitchCapabilities(ok, fail)
        }
        report("reading light access=${config?.accessType} options=${switches.describeByArea()}")
        switches.orEmpty().forEach { (area, options) ->
            if (LightSwitch.ON !in options) return@forEach
            val original = runCatching {
                awaitVehicle<LightSwitch?> { ok, fail -> light.getReadingLightSwitch(area, ok, fail) }
            }.getOrNull()
            val started = SystemClock.elapsedRealtime()
            val ack = runCatching {
                awaitVehicle<LightSwitch?> { ok, fail -> light.setReadingLightSwitch(area, LightSwitch.ON, ok, fail) }
            }
            val ackMs = SystemClock.elapsedRealtime() - started
            delay(STATE_SETTLE_MS)
            val switchReadBack = runCatching {
                awaitVehicle<LightSwitch?> { ok, fail -> light.getReadingLightSwitch(area, ok, fail) }
            }
            val state = runCatching {
                awaitVehicle<LightState?> { ok, fail -> light.getCurrentReadingLightState(area, ok, fail) }
            }
            report(
                "reading ${area.label()} original=$original set ON ack=${ack.text()} (${ackMs}ms) " +
                    "switch=${switchReadBack.text()} state=${state.text()}"
            )
            if (original != null && original != LightSwitch.ON) {
                runCatching {
                    awaitVehicle<LightSwitch?> { ok, fail -> light.setReadingLightSwitch(area, original, ok, fail) }
                }.onFailure { report("restore reading ${area.label()} failed: ${it.describe()}") }
            }
        }
    }

    private suspend fun probeCabinLight() {
        val light = session.light
        val (config, options) = awaitVehicle2<CapabilityConfig?, List<LightSwitch>?> { ok, fail ->
            light.checkCabinLightSwitchCapabilities(ok, fail)
        }
        val current = runCatching { awaitVehicle<LightSwitch?> { ok, fail -> light.getCabinLightSwitch(ok, fail) } }
        report("cabin light access=${config?.accessType} options=$options current=${current.text()}")
    }

    private suspend fun probeHvacFan() {
        val hvac = session.hvac
        val (config, bounds) = awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(ok, fail)
        }
        report("fan speed access=${config?.accessType} bounds=${bounds.describeByArea()}")
        val (area, range) = bounds?.entries?.firstOrNull()?.toPair() ?: run {
            report("no fan speed area")
            return
        }
        val poweredOnArea = ensureHvacPower()
        val original = awaitVehicle<Int?> { ok, fail -> hvac.getHvacFanSpeed(area, ok, fail) }
        report("fan ${area.label()} speed=$original")
        val target = when {
            original == null -> range.minValue + 1
            original < range.maxValue -> original + 1
            else -> original - 1
        }

        val updates = Channel<Int?>(Channel.UNLIMITED)
        val listener = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(speed: Int?) {
                updates.trySend(speed)
            }

            override fun onFailed(exception: Exception) {
                report("fan listener failed: ${exception.describe()}")
            }
        }
        hvac.registerHvacFanSpeed(area, listener)
        try {
            delay(LISTENER_SETTLE_MS)
            updates.drain()
            val started = SystemClock.elapsedRealtime()
            val ack = runCatching {
                awaitVehicle2<SeatArea?, Int?> { ok, fail -> hvac.setHvacFanSpeed(area, target, ok, fail) }
            }
            val ackMs = SystemClock.elapsedRealtime() - started
            val listenerMs = withTimeoutOrNull(LISTENER_TIMEOUT_MS) {
                while (updates.receive() != target) Unit
                SystemClock.elapsedRealtime() - started
            }
            val readBack = runCatching { awaitVehicle<Int?> { ok, fail -> hvac.getHvacFanSpeed(area, ok, fail) } }
            report(
                "set fan $original->$target ack=${ack.map { it.second }.text()} (${ackMs}ms) " +
                    "listener=${listenerMs?.let { "${it}ms" } ?: "none"} readback=${readBack.text()}"
            )
        } finally {
            if (original != null) {
                runCatching {
                    awaitVehicle2<SeatArea?, Int?> { ok, fail -> hvac.setHvacFanSpeed(area, original, ok, fail) }
                }.onFailure { report("restore fan failed: ${it.describe()}") }
            }
            hvac.unregisterHvacFanSpeed(area, listener)
            if (poweredOnArea != null) {
                runCatching {
                    awaitVehicle2<SeatArea?, Boolean?> { ok, fail -> hvac.setHvacPower(poweredOnArea, false, ok, fail) }
                }.onFailure { report("restore hvac power failed: ${it.describe()}") }
            }
        }
    }

    /** Turns HVAC power on if it is off, because fan writes fail with NOT_AVAILABLE while it is off. */
    private suspend fun ensureHvacPower(): SeatArea? {
        val hvac = session.hvac
        val (config, areas) = awaitVehicle2<CapabilityConfig?, List<SeatArea>?> { ok, fail ->
            hvac.checkHvacPowerCapabilities(ok, fail)
        }
        val area = areas?.firstOrNull()
        val power = area?.let { runCatching { awaitVehicle<Boolean?> { ok, fail -> hvac.getHvacPowerState(it, ok, fail) } } }
        report("hvac power access=${config?.accessType} areas=${areas.describeAreas()} state=${power?.text()}")
        if (area == null || power?.getOrNull() != false) return null
        val ack = runCatching {
            awaitVehicle2<SeatArea?, Boolean?> { ok, fail -> hvac.setHvacPower(area, true, ok, fail) }
        }
        delay(STATE_SETTLE_MS)
        val readBack = runCatching { awaitVehicle<Boolean?> { ok, fail -> hvac.getHvacPowerState(area, ok, fail) } }
        report("hvac power ${area.label()} off->on ack=${ack.map { it.second }.text()} readback=${readBack.text()}")
        return if (ack.isSuccess) area else null
    }

    private suspend fun probeHvacTemperatureAndDual() {
        val hvac = session.hvac
        runCatching {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Float>>?> { ok, fail ->
                hvac.checkHvacTemperatureCapabilities(ok, fail)
            }
        }.onSuccess { (config, bounds) ->
            report("temperature access=${config?.accessType} bounds=${bounds.describeByArea()}")
        }.onFailure { report("temperature capability failed: ${it.describe()}") }

        val (config, areas) = awaitVehicle2<CapabilityConfig?, List<SeatArea>?> { ok, fail ->
            hvac.checkHvacDualCapabilities(ok, fail)
        }
        val dual = areas?.firstOrNull()?.let { area ->
            runCatching { awaitVehicle<Boolean?> { ok, fail -> hvac.getHvacDualState(area, ok, fail) } }.text()
        }
        report("dual access=${config?.accessType} areas=${areas.describeAreas()} state=$dual")
    }

    private suspend fun probeSeatVentilation() {
        val seat = session.seat
        val (config, bounds) = awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?> { ok, fail ->
            seat.checkSeatVentilationCapabilities(ok, fail)
        }
        report("seat ventilation access=${config?.accessType} bounds=${bounds.describeByArea()}")
        val (area, range) = bounds?.entries?.firstOrNull()?.toPair() ?: return
        val original = runCatching {
            awaitVehicle<Int?> { ok, fail -> seat.getHvacSeatVentilationState(area, ok, fail) }
        }.getOrNull()
        val ack = runCatching {
            awaitVehicle2<SeatArea?, Int?> { ok, fail -> seat.setHvacSeatVentilation(area, range.maxValue, ok, fail) }
        }
        delay(STATE_SETTLE_MS)
        val readBack = runCatching {
            awaitVehicle<Int?> { ok, fail -> seat.getHvacSeatVentilationState(area, ok, fail) }
        }
        report(
            "seat ventilation ${area.label()} $original->${range.maxValue} " +
                "ack=${ack.map { it.second }.text()} readback=${readBack.text()}"
        )
        if (original != null) {
            runCatching {
                awaitVehicle2<SeatArea?, Int?> { ok, fail -> seat.setHvacSeatVentilation(area, original, ok, fail) }
            }.onFailure { report("restore seat ventilation failed: ${it.describe()}") }
        }
    }

    private fun Channel<*>.drain() {
        while (tryReceive().isSuccess) Unit
    }

    private fun Result<*>.text(): String = fold({ it.toString() }, { it.describe() })

    private companion object {
        const val LISTENER_SETTLE_MS = 300L
        const val LISTENER_TIMEOUT_MS = 1_500L
        const val STATE_SETTLE_MS = 500L
        const val PING_PONG_STEPS = 6
        val PING_PONG_INTERVALS_MS = listOf(1_000L, 500L, 300L, 150L)

        // Encoding of the ambient "brightness color" value is undocumented; try ARGB, RGB and small indices.
        val COLOR_CANDIDATES = listOf(0xFF2962FF.toInt(), 0xFFFF1744.toInt(), 0x2962FF, 0xFF1744, 1, 2)
    }
}
