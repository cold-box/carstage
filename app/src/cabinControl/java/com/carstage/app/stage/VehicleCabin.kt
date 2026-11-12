package com.carstage.app.stage

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import com.carstage.app.vehicle.VehicleCallTimeoutException
import com.carstage.app.vehicle.VehicleSession
import com.carstage.app.vehicle.awaitVehicle
import com.carstage.app.vehicle.awaitVehicle2
import com.carstage.app.vehicle.awaitVehicle3
import com.carstage.app.vehicle.describe
import com.carstage.app.vehicle.isControlDenied
import com.carstage.app.vehicle.isDrivingRestricted
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the real cabin through the Pleos Vehicle SDK.
 *
 * - Areas and ranges come from the capability checks, because they differ between car models.
 *   Checks run in [capture] and [read], and [apply] uses what the last capture found. An area-less
 *   answer means unsupported; a failed check is retried at the next capture, not on every write.
 * - A write the SDK answers with an error is reported as [CabinWriteRejected]; a timeout is not,
 *   because the write may still have landed. A write refused for want of permission
 *   ([isControlDenied]) is [CabinControlDenied], and [denials] keeps that target from being sent
 *   again in the stage, so a car that allows only some control still gets the rest (see [applyOnce]).
 * - [capture] keeps the raw values the car reported (for example AUTOMATIC for the ambient switch,
 *   or an exact brightness step), and [restore] writes those back unchanged. The write is always
 *   sent: neither an acknowledged write nor a read proves the value (the emulator acknowledges
 *   reading light writes without changing what it reads back). When the car refuses it only
 *   because it is driving (reading lights in gear D on the emulator), the read-back is the best
 *   evidence left: holding its original counts as restored, and anything else is deferred
 *   ([CabinRestoreDeferred]) and, with a [journal], stays saved for a run once the car is parked
 *   (see [restoreOne]). Without a journal nothing keeps it, so it is a plain failure.
 * - HVAC power is never switched on, so fan commands fail while the climate system is off; the
 *   emulator still acknowledges temperature settings then (2026-10-25).
 * - With a [journal], each target's original raw value is saved to disk before its first change
 *   and dropped when the stage ends, except for targets whose restore failed or was deferred
 *   (entries this stage did not write are left alone); a first change the car refused drops its
 *   original at once, as nothing changed, and so does a fan or temperature change written back
 *   ([settled]). So [restoreOriginals] can undo a stage the process never closed or could not
 *   fully restore.
 * - The fan and the temperature come as changes ([CabinCommand.FanStep], [CabinCommand.Temperature]):
 *   each is measured from the car's value read just before it ([baseFor]), so a change someone
 *   made during the stage is where it returns, and closing writes nothing for one already back.
 *   A change whose original cannot be saved is refused.
 */
class VehicleCabin(
    private val session: VehicleSession,
    private val report: (String) -> Unit,
    private val journal: RestoreJournal? = null,
    private val denials: ControlDenials = ControlDenials {},
) : CarCabin {
    override val name = CarCabin.NAME

    private data class Ranged(val area: SeatArea, val min: Int, val max: Int)

    /** A side's temperature area and the setting range the car allows, in °C. */
    private data class Degrees(val area: SeatArea, val min: Float, val max: Float)

    private class Capabilities(
        /** Features whose capability check failed, so whether the car has them is unknown. */
        val unknown: Set<String>,
        val ambientSwitch: SeatArea?,
        val ambientColor: SeatArea?,
        val ambientBrightness: Ranged?,
        val readingLights: Map<Side, SeatArea>,
        val fan: Ranged?,
        val seatVentilation: Map<Side, Ranged>,
        val temperature: Map<Side, Degrees>,
    )

    /** How [restoreOne] put a target back. */
    enum class Restore { WRITTEN, ALREADY_ORIGINAL }

    /** Raw SDK values next to the simplified state, so diagnostics can compare exactly what the car reported. */
    data class Reading(val state: CabinState, val raw: Map<CabinTarget, Any>)

    private class Captured(
        val state: CabinState,
        val raw: Map<CabinTarget, Any>,
        val writers: Map<CabinTarget, suspend () -> Unit>,
    )

    private var cached: Capabilities? = null
    private var active: Capabilities? = null
    private var restorers: Map<CabinTarget, suspend () -> Unit> = emptyMap()
    private var originals: Map<CabinTarget, Any> = emptyMap()

    /** Targets whose original is in the [journal] for this stage. */
    private val journaled = mutableSetOf<CabinTarget>()

    /** Targets whose restore failed or was deferred in this stage; their originals stay in the [journal]. */
    private val unrestored = mutableSetOf<CabinTarget>()

    /**
     * For the fan and the temperature, set as changes: what the car had when the change under way
     * began, read just before it ([baseFor]), so a change someone made since the stage opened is
     * where the stage goes back to; none once the change is written back ([settled]).
     */
    private val changeBase = mutableMapOf<CabinTarget, Any>()

    override suspend fun capture(): CabinState {
        val caps = capabilities()
        val captured = readAll(caps)
        active = caps
        restorers = captured.writers
        originals = captured.raw
        journaled.clear()
        unrestored.clear()
        changeBase.clear()
        denials.stageStarted()
        return captured.state
    }

    /** Reads the current values without changing what [restore] puts back; for diagnostics. */
    suspend fun read(): Reading = readAll(capabilities()).let { Reading(it.state, it.raw) }

    override suspend fun restore(target: CabinTarget) {
        // A fan or temperature change the stage already put back left nothing to undo, and writing
        // the original again could fight the climate system (a defroster that raised the fan since).
        if (LayeredDirector.startOf(target) != null && target !in changeBase) return
        try {
            val write = checkNotNull(restorers[target]) { "no captured value for $target" }
            val original = encode(checkNotNull(originals[target]) { "no captured value for $target" })
            val caps = checkNotNull(active) { "nothing captured" }
            val read = suspend { withTimeoutOrNull(READ_BACK_TIMEOUT_MS) { readRaw(caps, target) } }
            if (restoreOne(original, write, read) == Restore.ALREADY_ORIGINAL) {
                report("restore $target: refused while driving, but it already holds its original")
            }
        } catch (e: CabinRestoreDeferred) {
            unrestored += target
            // Without a journal (Lab probes) nothing restores it later.
            if (journal == null) throw IllegalStateException("${e.message}; nothing keeps it to restore later", e)
            throw e
        } catch (e: Exception) {
            // Failed: its original stays in the journal.
            unrestored += target
            throw e
        }
    }

    override suspend fun apply(command: CabinCommand): ApplyResult {
        val caps = active ?: return ApplyResult.UNSUPPORTED
        val target = command.target
        return applyOnce(
            target,
            denials,
            save = { saveOriginal(target) },
            write = { write(caps, command) },
            forget = { forgetOriginal(target) },
        )
    }

    override suspend fun ended() {
        journal?.let { journal ->
            val restored = journaled - unrestored
            withContext(Dispatchers.IO) { journal.forget(restored) }
        }
        journaled.clear()
        unrestored.clear()
    }

    /**
     * Saves [target]'s original before its first change in this stage; refuses the change if it
     * cannot. True when this call saved it.
     */
    private suspend fun saveOriginal(target: CabinTarget): Boolean {
        val journal = journal ?: return false
        if (target in journaled) return false
        val raw = originals[target] ?: return false
        try {
            withContext(Dispatchers.IO) { journal.record(target, encode(raw)) }
        } catch (e: IOException) {
            throw CabinWriteRejected("its original value could not be saved first: ${e.describe()}", e)
        }
        journaled += target
        return true
    }

    /**
     * A fan or temperature change was written back: nothing of the stage is left in that target, so
     * neither closing nor a recovery after a crash writes its original again.
     */
    private suspend fun settled(target: CabinTarget) {
        changeBase.remove(target)
        forgetOriginal(target)
    }

    /** Drops the original saved for a change that left nothing to restore; [ended] drops it if this cannot. */
    private suspend fun forgetOriginal(target: CabinTarget) {
        val journal = journal ?: return
        try {
            withContext(Dispatchers.IO) { journal.forget(setOf(target)) }
            journaled -= target
        } catch (e: Exception) {
            // IOException, or IllegalArgumentException from a damaged file.
            if (e is CancellationException) throw e
            report("restore journal could not drop $target, which the car denied; the stage's end drops it: ${e.describe()}")
        }
    }

    /**
     * Writes back originals that a stage left in the [RestoreJournal] (see [restoreEach]) and
     * returns what could not be restored. Throws when the car cannot be reached yet (service not
     * ready, the check for a saved target's feature failed, or not one write answered), so the
     * caller keeps the journal and tries again later.
     */
    override suspend fun restoreOriginals(saved: Map<CabinTarget, String>): List<StageRecovery.Failure> {
        val caps = capabilities()
        val unknown = saved.keys.map(::featureOf).filter { it in caps.unknown }
        check(unknown.isEmpty()) { "capability check failed for ${unknown.distinct()}" }
        return restoreEach(
            saved,
            write = { target, raw -> writeRaw(caps, target, raw) },
            read = { target -> withTimeoutOrNull(READ_BACK_TIMEOUT_MS) { readRaw(caps, target) } },
        )
    }

    /** Reads [target]'s current raw value, in the form [capture] keeps; null when the car has no such target. */
    private suspend fun readRaw(caps: Capabilities, target: CabinTarget): Any? {
        val light = session.light
        return when (target) {
            CabinTarget.AmbientSwitch -> caps.ambientSwitch?.let { area ->
                awaitVehicle<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) }
            }
            CabinTarget.AmbientColor -> caps.ambientColor?.let { area ->
                awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) }
            }
            CabinTarget.AmbientBrightness -> caps.ambientBrightness?.let { range ->
                awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightness(range.area, ok, fail) }
            }
            is CabinTarget.ReadingLight -> caps.readingLights[target.side]?.let { area ->
                awaitVehicle<LightSwitch?> { ok, fail -> light.getReadingLightSwitch(area, ok, fail) }
            }
            CabinTarget.Fan -> caps.fan?.let { range ->
                awaitVehicle<Int?> { ok, fail -> session.hvac.getHvacFanSpeed(range.area, ok, fail) }
            }
            is CabinTarget.SeatVentilation -> caps.seatVentilation[target.side]?.let { range ->
                awaitVehicle<Int?> { ok, fail -> session.seat.getHvacSeatVentilationState(range.area, ok, fail) }
            }
            is CabinTarget.Temperature -> caps.temperature[target.side]?.let { range -> readTemperature(range.area) }
        }
    }

    /** Writes a raw value in its saved text form; false when the car has no such target. */
    private suspend fun writeRaw(caps: Capabilities, target: CabinTarget, raw: String): Boolean {
        when (target) {
            CabinTarget.AmbientSwitch -> writeAmbientSwitch(caps.ambientSwitch ?: return false, LightSwitch.valueOf(raw))
            CabinTarget.AmbientColor -> writeAmbientColor(caps.ambientColor ?: return false, raw.toInt())
            CabinTarget.AmbientBrightness -> writeAmbientBrightness((caps.ambientBrightness ?: return false).area, raw.toInt())
            is CabinTarget.ReadingLight -> {
                val area = caps.readingLights[target.side] ?: return false
                awaitVehicle<LightSwitch?> { ok, fail -> session.light.setReadingLightSwitch(area, LightSwitch.valueOf(raw), ok, fail) }
            }
            CabinTarget.Fan -> writeFan((caps.fan ?: return false).area, raw.toInt())
            is CabinTarget.SeatVentilation -> writeVentilation((caps.seatVentilation[target.side] ?: return false).area, raw.toInt())
            is CabinTarget.Temperature -> writeTemperature((caps.temperature[target.side] ?: return false).area, raw.toFloat())
        }
        return true
    }

    private suspend fun write(caps: Capabilities, command: CabinCommand): ApplyResult {
        val light = session.light
        when (command) {
            is CabinCommand.AmbientSwitch -> {
                val area = caps.ambientSwitch ?: return ApplyResult.UNSUPPORTED
                writeAmbientSwitch(area, if (command.on) LightSwitch.ON else LightSwitch.OFF)
            }
            is CabinCommand.AmbientColor -> {
                val area = caps.ambientColor ?: return ApplyResult.UNSUPPORTED
                // The emulator reports colors as 0x00RRGGBB (initial 0x00FFFFFF).
                writeAmbientColor(area, command.color.value)
            }
            is CabinCommand.AmbientBrightness -> {
                val range = caps.ambientBrightness ?: return ApplyResult.UNSUPPORTED
                writeAmbientBrightness(range.area, fromPercent(command.level.value, range.min, range.max))
            }
            is CabinCommand.ReadingLight -> {
                val area = caps.readingLights[command.side] ?: return ApplyResult.UNSUPPORTED
                awaitVehicle<LightSwitch?> { ok, fail ->
                    light.setReadingLightSwitch(area, if (command.on) LightSwitch.ON else LightSwitch.OFF, ok, fail)
                }
            }
            is CabinCommand.FanStep -> {
                val range = caps.fan ?: return ApplyResult.UNSUPPORTED
                if (command.steps == 0) {
                    val base = changeBase[command.target] as? Int ?: return ApplyResult.UNCHANGED
                    writeFan(range.area, base)
                    settled(command.target)
                } else {
                    val base = baseFor(caps, command.target) as? Int ?: throw CabinWriteRejected("its value could not be read before changing it")
                    writeFan(range.area, fanFor(base, command.steps, range.min, range.max))
                }
            }
            is CabinCommand.SeatVentilation -> {
                val range = caps.seatVentilation[command.side] ?: return ApplyResult.UNSUPPORTED
                writeVentilation(range.area, command.level.coerceIn(range.min, range.max))
            }
            is CabinCommand.Temperature -> {
                val range = caps.temperature[command.side] ?: return ApplyResult.UNSUPPORTED
                if (command.offset == 0f) {
                    val base = changeBase[command.target] as? Float ?: return ApplyResult.UNCHANGED
                    writeTemperature(range.area, base)
                    settled(command.target)
                } else {
                    val base = baseFor(caps, command.target) as? Float ?: throw CabinWriteRejected("its value could not be read before changing it")
                    writeTemperature(range.area, temperatureFor(base, command.offset, range.min, range.max))
                }
            }
        }
        return ApplyResult.APPLIED
    }

    /** Reads every supported value with its raw form and a writer that puts the raw value back. */
    private suspend fun readAll(caps: Capabilities): Captured {
        val light = session.light
        var state = CabinState()
        val raws = mutableMapOf<CabinTarget, Any>()
        val writers = mutableMapOf<CabinTarget, suspend () -> Unit>()

        caps.ambientSwitch?.let { area ->
            read("ambient switch") { awaitVehicle<LightSwitch?> { ok, fail -> light.getAmbientLightSwitch(area, ok, fail) } }
                ?.let { raw ->
                    state = state.with(CabinCommand.AmbientSwitch(raw == LightSwitch.ON))
                    raws[CabinTarget.AmbientSwitch] = raw
                    writers[CabinTarget.AmbientSwitch] = { writeAmbientSwitch(area, raw) }
                }
        }
        caps.ambientColor?.let { area ->
            read("ambient color") { awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) } }
                ?.let { raw ->
                    state = state.with(CabinCommand.AmbientColor(Rgb.fromArgb(raw)))
                    raws[CabinTarget.AmbientColor] = raw
                    writers[CabinTarget.AmbientColor] = { writeAmbientColor(area, raw) }
                }
        }
        caps.ambientBrightness?.let { range ->
            read("ambient brightness") { awaitVehicle<Int?> { ok, fail -> light.getAmbientLightBrightness(range.area, ok, fail) } }
                ?.let { raw ->
                    state = state.with(CabinCommand.AmbientBrightness(Percent(toPercent(raw, range.min, range.max))))
                    raws[CabinTarget.AmbientBrightness] = raw
                    writers[CabinTarget.AmbientBrightness] = { writeAmbientBrightness(range.area, raw) }
                }
        }
        caps.readingLights.forEach { (side, area) ->
            read("reading light $side") { awaitVehicle<LightSwitch?> { ok, fail -> light.getReadingLightSwitch(area, ok, fail) } }
                ?.let { raw ->
                    state = state.with(CabinCommand.ReadingLight(side, raw == LightSwitch.ON))
                    raws[CabinTarget.ReadingLight(side)] = raw
                    writers[CabinTarget.ReadingLight(side)] = {
                        awaitVehicle<LightSwitch?> { ok, fail -> light.setReadingLightSwitch(area, raw, ok, fail) }
                    }
                }
        }
        caps.fan?.let { range ->
            read("fan speed") { awaitVehicle<Int?> { ok, fail -> session.hvac.getHvacFanSpeed(range.area, ok, fail) } }
                ?.let { raw ->
                    // The stage's start: commands give steps from it.
                    state = state.with(CabinCommand.FanStep(0))
                    raws[CabinTarget.Fan] = raw
                    writers[CabinTarget.Fan] = { writeFan(range.area, raw) }
                }
        }
        caps.seatVentilation.forEach { (side, range) ->
            read("seat ventilation $side") {
                awaitVehicle<Int?> { ok, fail -> session.seat.getHvacSeatVentilationState(range.area, ok, fail) }
            }?.let { raw ->
                state = state.with(CabinCommand.SeatVentilation(side, raw.coerceAtLeast(0)))
                raws[CabinTarget.SeatVentilation(side)] = raw
                writers[CabinTarget.SeatVentilation(side)] = { writeVentilation(range.area, raw) }
            }
        }
        caps.temperature.forEach { (side, range) ->
            read("temperature $side") { readTemperature(range.area) }?.let { raw ->
                // The stage's start: commands give offsets from it.
                state = state.with(CabinCommand.Temperature(side, 0f))
                raws[CabinTarget.Temperature(side)] = raw
                writers[CabinTarget.Temperature(side)] = { writeTemperature(range.area, raw) }
            }
        }
        return Captured(state, raws, writers)
    }

    private suspend fun writeAmbientSwitch(area: SeatArea, value: LightSwitch) {
        awaitVehicle2<SeatArea?, LightSwitch?> { ok, fail -> session.light.setAmbientLightSwitch(area, value, ok, fail) }
    }

    private suspend fun writeAmbientColor(area: SeatArea, raw: Int) {
        awaitVehicle2<SeatArea?, Int?> { ok, fail -> session.light.setAmbientLightBrightnessColor(area, raw, ok, fail) }
    }

    private suspend fun writeAmbientBrightness(area: SeatArea, raw: Int) {
        awaitVehicle2<SeatArea?, Int?> { ok, fail -> session.light.setAmbientLightBrightness(area, raw, ok, fail) }
    }

    private suspend fun writeFan(area: SeatArea, raw: Int) {
        awaitVehicle2<SeatArea?, Int?> { ok, fail -> session.hvac.setHvacFanSpeed(area, raw, ok, fail) }
    }

    private suspend fun readTemperature(area: SeatArea): Float? =
        awaitVehicle<Float?> { ok, fail -> session.hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }

    private suspend fun writeTemperature(area: SeatArea, celsius: Float) {
        awaitVehicle2<SeatArea?, Float?> { ok, fail -> session.hvac.setHvacTemperature(area, celsius, TemperatureUnit.CELSIUS, ok, fail) }
    }

    private suspend fun writeVentilation(area: SeatArea, raw: Int) {
        awaitVehicle2<SeatArea?, Int?> { ok, fail -> session.seat.setHvacSeatVentilation(area, raw, ok, fail) }
    }

    /**
     * Discovers areas and ranges once. The result is cached only when every check answered; a
     * check that failed (timeout, service error) is retried on the next call instead of being
     * remembered as unsupported.
     */
    private suspend fun capabilities(): Capabilities {
        cached?.let { return it }
        session.awaitReady().onFailure { throw IllegalStateException("vehicle service not ready", it) }
        val failed = mutableSetOf<String>()
        suspend fun <T> discover(feature: String, block: suspend () -> T?): T? = try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            failed += feature
            report("$feature capability check failed: ${e.describe()}")
            null
        }
        val light = session.light
        val switches = discover("ambient switch") {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?> { ok, fail ->
                light.checkAmbientLightSwitchCapabilities(ok, fail)
            }.second
        }
        val colorAreas = discover("ambient color") {
            awaitVehicle2<CapabilityConfig?, List<SeatArea>?> { ok, fail ->
                light.checkAmbientLightBrightnessColorCapabilities(ok, fail)
            }.second
        }
        val brightness = discover("ambient brightness") {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?> { ok, fail ->
                light.checkAmbientLightBrightnessCapabilities(ok, fail)
            }.second
        }
        val reading = discover("reading light") {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?> { ok, fail ->
                light.checkReadingLightSwitchCapabilities(ok, fail)
            }.second
        }
        val fan = discover("fan speed") {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?> { ok, fail ->
                session.hvac.checkHvacFanSpeedCapabilities(ok, fail)
            }.second
        }
        val ventilation = discover("seat ventilation") {
            awaitVehicle2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?> { ok, fail ->
                session.seat.checkSeatVentilationCapabilities(ok, fail)
            }.second
        }
        val temperature = discover("temperature") {
            // The overload SDK 2.3.2 asks for; its third value (the car's setting steps) is not used yet.
            awaitVehicle3<CapabilityConfig?, Map<SeatArea, AreaBounds<Float>>?, Any?> { ok, fail ->
                session.hvac.checkHvacTemperatureCapabilities(ok, fail)
            }.second
        }
        val caps = Capabilities(
            unknown = failed,
            ambientSwitch = switches?.entries?.firstOrNull { LightSwitch.ON in it.value }?.key,
            ambientColor = colorAreas?.firstOrNull(),
            ambientBrightness = brightness?.entries?.firstNotNullOfOrNull { ranged("ambient brightness", it.key, it.value) },
            readingLights = reading.orEmpty()
                .filterValues { LightSwitch.ON in it }
                .keys.mapNotNull { area -> sideOf(area.values.toList())?.let { it to area } }.toMap(),
            fan = fan?.entries?.firstNotNullOfOrNull { ranged("fan speed", it.key, it.value) },
            seatVentilation = ventilation.orEmpty().entries.mapNotNull { (area, bounds) ->
                val side = sideOf(area.values.toList()) ?: return@mapNotNull null
                ranged("seat ventilation", area, bounds)?.let { side to it }
            }.toMap(),
            temperature = temperature.orEmpty().entries.mapNotNull { (area, bounds) ->
                val side = sideOf(area.values.toList()) ?: return@mapNotNull null
                if (bounds.minValue > bounds.maxValue) return@mapNotNull null.also { report("temperature range ${bounds.minValue}..${bounds.maxValue} is empty; ignored") }
                side to Degrees(area, bounds.minValue, bounds.maxValue)
            }.toMap(),
        )
        if (failed.isEmpty()) cached = caps
        return caps
    }

    private fun ranged(feature: String, area: SeatArea, bounds: AreaBounds<Int>): Ranged? {
        if (bounds.minValue > bounds.maxValue) {
            report("$feature range ${bounds.minValue}..${bounds.maxValue} is empty; ignored")
            return null
        }
        return Ranged(area, bounds.minValue, bounds.maxValue)
    }

    /**
     * What a fan or temperature change is measured from: the car's value read just before the
     * first change of an excursion, kept in [changeBase] until a change of 0 has written it back.
     * Null when it cannot be read (in time); the change is then skipped, as a guess could move the
     * car the wrong way. The read runs on the vehicle's sender, a few milliseconds on the emulator.
     */
    private suspend fun baseFor(caps: Capabilities, target: CabinTarget): Any? {
        changeBase[target]?.let { return it }
        val now = withTimeoutOrNull(READ_BACK_TIMEOUT_MS) { read("$target before a change") { readRaw(caps, target) } }
        if (now == null) report("$target not changed: its value could not be read first")
        return now?.also { changeBase[target] = it }
    }

    /** Reads one value; an unreadable value is left out, so the stage will not change it. */
    private suspend fun <T> read(what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        report("read $what failed: ${e.describe()}")
        null
    }

    internal companion object {
        /**
         * One cabin write as [apply] makes it, without the car: a target [denials] holds is refused
         * without [save] or [write]. Otherwise [save] saves its original (true when this call did)
         * and [write] writes it; one the car took ([ApplyResult.APPLIED]) tells [denials] it allows
         * the target. A refusal for want of permission ([isControlDenied]) marks the target denied
         * for the stage; other refusals are [CabinWriteRejected]. Either way, when this call saved
         * the original, it [forget]s it, as nothing changed. A timeout passes through, as the write
         * may have landed.
         */
        suspend fun applyOnce(
            target: CabinTarget,
            denials: ControlDenials,
            save: suspend () -> Boolean,
            write: suspend () -> ApplyResult,
            forget: suspend () -> Unit,
        ): ApplyResult {
            if (denials.isDenied(target)) throw CabinControlDenied("not sent: the car does not let this app control it")
            val saved = save()
            return try {
                write().also { if (it == ApplyResult.APPLIED) denials.allowed(target) }
            } catch (e: VehicleCallTimeoutException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isControlDenied()) {
                    // Nothing changed either (the climate system off, say), so nothing is left to restore after a crash.
                    if (saved) forget()
                    throw CabinWriteRejected("the car refused it: ${e.describe()}", e)
                }
                denials.denied(target)
                // An app that ends now must not leave a recovery the car would refuse too.
                if (saved) forget()
                throw CabinControlDenied("the car does not let this app control it: ${e.describe()}", e)
            }
        }

        /** A read-back after a refusal; the refusal itself came within milliseconds, so a slow read means trouble. */
        private const val READ_BACK_TIMEOUT_MS = 1_000L

        /** A side is an area holding exactly one front seat. */
        fun sideOf(seats: List<SeatValue>): Side? = when (seats.singleOrNull()) {
            SeatValue.ROW_1_LEFT -> Side.DRIVER
            SeatValue.ROW_1_RIGHT -> Side.PASSENGER
            else -> null
        }

        /** The capability check that tells whether the car has [target]. */
        fun featureOf(target: CabinTarget): String = when (target) {
            CabinTarget.AmbientSwitch -> "ambient switch"
            CabinTarget.AmbientColor -> "ambient color"
            CabinTarget.AmbientBrightness -> "ambient brightness"
            is CabinTarget.ReadingLight -> "reading light"
            CabinTarget.Fan -> "fan speed"
            is CabinTarget.SeatVentilation -> "seat ventilation"
            is CabinTarget.Temperature -> "temperature"
        }

        /**
         * Writes an original back with [write], always first: neither an acknowledged write nor a
         * read proves what the target holds. When the car refuses the write only because it is
         * driving, [read] is the best evidence left: a target that holds [original] (its saved
         * text form, see [encode]) needs nothing, and any other, or one that cannot be read, is
         * deferred ([CabinRestoreDeferred]). Other failures are thrown as they are.
         */
        suspend fun restoreOne(original: String, write: suspend () -> Unit, read: suspend () -> Any?): Restore {
            try {
                write()
                return Restore.WRITTEN
            } catch (e: Exception) {
                if (e is CancellationException || !e.isDrivingRestricted()) throw e
                var readFailure: Exception? = null
                val now = try {
                    read()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    readFailure = failure
                    null
                }
                if (now != null && encode(now) == original) return Restore.ALREADY_ORIGINAL
                val held = now?.let(::encode) ?: readFailure?.let { "an unknown value (read failed: ${it.describe()})" } ?: "an unknown value"
                throw CabinRestoreDeferred("the car refuses it while driving and it holds $held", e).also { deferred ->
                    readFailure?.let(deferred::addSuppressed)
                }
            }
        }

        /**
         * Writes each saved original back with [restoreOne], the ambient switch last, and returns
         * what could not be restored; refusals for driving are marked to retry once parked.
         * [write] returns false when the car has no such target. Throws when not one write
         * answered (all timed out), because the car is then not reachable after all.
         */
        suspend fun restoreEach(
            saved: Map<CabinTarget, String>,
            write: suspend (CabinTarget, String) -> Boolean,
            read: suspend (CabinTarget) -> Any?,
        ): List<StageRecovery.Failure> {
            var timeouts = 0
            val failures = saved.entries.sortedBy { it.key == CabinTarget.AmbientSwitch }.mapNotNull { (target, raw) ->
                try {
                    var supported = true
                    restoreOne(raw, write = { supported = write(target, raw) }, read = { read(target) })
                    if (supported) null else StageRecovery.Failure(target, "not supported by this car")
                } catch (e: CabinRestoreDeferred) {
                    StageRecovery.Failure(target, e.message.orEmpty(), retryWhenParked = true)
                } catch (e: Exception) {
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    if (e is VehicleCallTimeoutException) timeouts++
                    StageRecovery.Failure(target, e.describe())
                }
            }
            check(timeouts < saved.size) { "no restore write answered: $failures" }
            return failures
        }

        /** A raw SDK value as text for the [RestoreJournal]: an enum by name, a number as digits. */
        fun encode(raw: Any): String = when (raw) {
            is Enum<*> -> raw.name
            else -> raw.toString()
        }

        /** The fan level [steps] from the stage's [start], within the car's range and never past the start the other way; 0 is the start. */
        fun fanFor(start: Int, steps: Int, min: Int, max: Int): Int {
            val wanted = (start + steps).coerceIn(min, max)
            return when {
                steps < 0 -> minOf(wanted, start)
                steps > 0 -> maxOf(wanted, start)
                else -> start
            }
        }

        /**
         * The setting for an [offset] from the stage's [start], within the car's range, and never
         * past the start the other way (a car may report a start outside its own range); 0 is the
         * start as the car had it.
         */
        fun temperatureFor(start: Float, offset: Float, min: Float, max: Float): Float {
            val wanted = (start + offset).coerceIn(min, max)
            return when {
                offset < 0f -> minOf(wanted, start)
                offset > 0f -> maxOf(wanted, start)
                else -> start
            }
        }

        fun fromPercent(percent: Int, min: Int, max: Int): Int =
            min + Math.round((max - min) * percent.coerceIn(0, 100) / 100f)

        fun toPercent(raw: Int, min: Int, max: Int): Int {
            val span = max - min
            return if (span <= 0) 100 else Math.round((raw - min) * 100f / span).coerceIn(0, 100)
        }
    }
}
