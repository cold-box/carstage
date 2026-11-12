package com.carstage.app.safety

import com.carstage.app.stage.CabinCommand
import com.carstage.app.vehicle.FailureLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads the car for the [SafetyGuard]; a value it cannot read is null. */
interface DriveReader {
    /** Gear and driving restrictions; read every poll, so it must be quick. */
    suspend fun motion(): DriveState.Motion

    /** Defroster and wipers; read on a slower poll of its own. */
    suspend fun climate(): DriveState.Climate
}

/**
 * Keeps the stage safe: checks the drive state before the stage opens, watches it while the stage
 * runs, and decides which commands may be sent. Reads of one kind are serialized, so a slow read
 * never overwrites a newer one.
 */
class SafetyGuard(
    private val reader: DriveReader,
    log: (String, Throwable?) -> Unit,
    private val pollMs: Long = POLL_MS,
    private val climatePollMs: Long = CLIMATE_POLL_MS,
) {
    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state.asStateFlow()
    private val motionLock = Mutex()
    private val climateLock = Mutex()
    private val failures = FailureLog { line, e -> log("drive state: $line", e) }

    /** Reads gear and restrictions now. */
    suspend fun checkMotion(): DriveState = motionLock.withLock {
        val motion = safely("motion", DriveState.Motion()) { reader.motion() }
        _state.updateAndGet { it.copy(motion = motion) }
    }

    /** Reads defroster and wipers now. */
    suspend fun checkClimate(): DriveState = climateLock.withLock {
        val climate = safely("climate", DriveState.Climate()) { reader.climate() }
        _state.updateAndGet { it.copy(climate = climate) }
    }

    /** Reads everything now. */
    suspend fun check(): DriveState {
        checkClimate()
        return checkMotion()
    }

    /** Whether [command] may be sent under the latest drive state; safe to call from the audio thread. */
    fun allows(command: CabinCommand): Boolean = SafetyRules.allows(command, _state.value)

    /**
     * Watches until the stage must stop and returns that state; cancel to stop watching. Gear and
     * restrictions are polled every [pollMs], starting at once, and climate every [climatePollMs]
     * on its own, so a slow climate read never delays noticing that the car drives. A gear that
     * cannot be read pauses commands at once (see [allows]) and stops the stage only after
     * [UNKNOWN_GEAR_LIMIT] reads in a row, so one lost reply does not end a song. Climate read
     * before this watch may be old, so climate effects wait for a fresh read.
     */
    suspend fun awaitUnsafe(): DriveState = coroutineScope {
        _state.update { it.copy(climate = DriveState.Climate()) }
        val climate = launch { pollClimate() }
        try {
            pollMotion()
        } finally {
            climate.cancel()
        }
    }

    private suspend fun pollMotion(): DriveState {
        var unknownInRow = 0
        while (true) {
            val state = checkMotion()
            when (state.block) {
                null -> unknownInRow = 0
                DriveState.Block.GEAR_UNKNOWN -> if (++unknownInRow >= UNKNOWN_GEAR_LIMIT) return state
                else -> return state
            }
            delay(pollMs)
        }
    }

    private suspend fun pollClimate(): Nothing {
        while (true) {
            checkClimate()
            delay(climatePollMs)
        }
    }

    /**
     * A reader that throws (a bug, since readers report failures as null) counts as knowing
     * nothing, which blocks the stage instead of killing the watch.
     */
    private suspend fun <T> safely(what: String, unknown: T, read: suspend () -> T): T = try {
        read().also { failures.succeeded("$what reader") }
    } catch (e: Throwable) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        failures.failed("$what reader", "threw ${e::class.java.simpleName}: ${e.message}", e)
        unknown
    }

    companion object {
        /** Half a second: at walking pace the car has barely moved before the stage stops. */
        const val POLL_MS = 500L

        /** The defroster and wipers are switched by hand, so a couple of seconds is soon enough. */
        const val CLIMATE_POLL_MS = 2_000L

        const val UNKNOWN_GEAR_LIMIT = 2
    }
}
