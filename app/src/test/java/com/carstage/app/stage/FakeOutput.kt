package com.carstage.app.stage

import kotlinx.coroutines.delay

/** An output whose failures and delays are switched on per test. */
internal class FakeOutput(
    override val name: String,
    var state: CabinState = SimulatedCabin.DEFAULT,
    private val unreadable: Set<CabinTarget> = emptySet(),
    private val unsupported: Set<CabinTarget> = emptySet(),
    private val captureDelayMs: Long = 0,
    private val applyDelayMs: Long = 0,
    private val captureFails: Boolean = false,
    private val captureError: Boolean = false,
    private val failApply: (CabinCommand) -> Boolean = { false },
    private val rejectApply: (CabinCommand) -> Boolean = { false },
    private val failRestore: (CabinTarget) -> Boolean = { false },
    private val deferRestore: (CabinTarget) -> Boolean = { false },
    private val failEnd: Boolean = false,
) : CabinOutput {
    val applied = mutableListOf<CabinCommand>()
    val restored = mutableListOf<CabinTarget>()

    /** What had been restored each time the stage ended. */
    val endedAfter = mutableListOf<List<CabinTarget>>()
    private var captured = CabinState()

    override suspend fun capture(): CabinState {
        delay(captureDelayMs)
        if (captureError) throw AssertionError("capture crashed")
        if (captureFails) error("not ready")
        captured = CabinState.of(*state.restoreCommands().filter { it.target !in unreadable }.toTypedArray())
        return captured
    }

    override suspend fun apply(command: CabinCommand): ApplyResult {
        delay(applyDelayMs)
        if (command.target in unsupported) return ApplyResult.UNSUPPORTED
        if (rejectApply(command)) throw CabinWriteRejected("NOT_AVAILABLE")
        if (failApply(command)) error("write rejected")
        applied += command
        state = state.with(command)
        return ApplyResult.APPLIED
    }

    override suspend fun restore(target: CabinTarget) {
        if (failRestore(target)) error("restore rejected")
        if (deferRestore(target)) throw CabinRestoreDeferred("refused while driving")
        restored += target
        state = state.with(captured[target]!!)
    }

    override suspend fun ended() {
        endedAfter += restored.toList()
        if (failEnd) error("journal not cleared")
    }
}
