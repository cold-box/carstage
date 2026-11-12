package com.carstage.app.stage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Puts the car back to the originals a stage left in the [RestoreJournal]: the app ended before
 * the stage closed, restoring at close failed, or an earlier run was refused while the car drove.
 * Run it only while no stage is open (see [StageController.whileClosed]), so it never undoes the
 * stage that is running.
 */
class StageRecovery(
    private val journal: RestoreJournal,
    /** Writes the originals back and returns what failed; throws when the car cannot be reached yet. */
    private val restore: suspend (Map<CabinTarget, String>) -> List<Failure>,
    private val log: (String, Throwable?) -> Unit,
) {
    /**
     * A target that could not be put back. [retryWhenParked] when the car refused it only because
     * it was driving (reading lights in gear D on the emulator): unless the car is parked when the
     * run ends, it stays in the journal for a run once it is.
     */
    data class Failure(val target: CabinTarget, val reason: String, val retryWhenParked: Boolean = false) {
        override fun toString() = "$target: $reason"
    }

    sealed interface Result {
        data object NothingToDo : Result

        /** The car could not be reached; the journal is kept for the next try. */
        data object Unreachable : Result

        /** The journal was damaged and has been discarded; the car may still be changed. */
        data object Unreadable : Result

        /**
         * The journal's [targets] were tried. All were put back (or found at their original)
         * except [failures], which are forgotten, and [pending], which stay in the journal for a
         * run once the car is parked.
         */
        data class Restored(
            val targets: Set<CabinTarget>,
            val failures: List<String>,
            val pending: Set<CabinTarget> = emptySet(),
        ) : Result
    }

    /**
     * Writes back what the journal holds. [parked] reads whether the car is parked; it is asked
     * after the writes, and only when one was refused for driving. A refusal while parked cannot
     * be undone by parking, so it is forgotten like any failure; otherwise it is kept.
     */
    suspend fun run(parked: suspend () -> Boolean = { false }): Result {
        val saved = try {
            withContext(Dispatchers.IO) { journal.entries() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // IOException, or IllegalArgumentException from a damaged file: it cannot be trusted.
            log("restore journal unreadable; discarding it", e)
            clear()
            return Result.Unreadable
        }
        if (saved.isEmpty()) return Result.NothingToDo
        log("the last stage left values in the car (the app ended, restoring at close failed, or the car refused it while driving); restoring $saved", null)
        val failures = try {
            restore(saved)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("cannot restore the last stage yet", e)
            return Result.Unreachable
        }
        // The car changed; finish the bookkeeping even if the caller gives up waiting now.
        return withContext(NonCancellable) {
            // Other failures are forgotten: kept, they would be retried and reported at every start
            // and open. Refusals for driving are kept only if the car is still not parked now.
            val parkedNow = failures.any { it.retryWhenParked } && parked()
            val keep = failures.filter { it.retryWhenParked && !parkedNow }
            val pending = keep.map { it.target }.toSet()
            val dropped = (failures - keep.toSet()).map { if (it.retryWhenParked) "$it (refused although parked)" else it.toString() }
            if (pending.isEmpty()) clear() else forget(saved.keys - pending)
            log(
                when {
                    failures.isEmpty() -> "restored the values the last stage left"
                    pending.isEmpty() -> "could not restore from the last stage: $dropped"
                    else -> "restoring $pending once the car is parked" + if (dropped.isEmpty()) "" else "; could not restore: $dropped"
                },
                null,
            )
            Result.Restored(saved.keys, dropped, pending)
        }
    }

    private suspend fun forget(targets: Set<CabinTarget>) {
        try {
            withContext(Dispatchers.IO) { journal.forget(targets) }
        } catch (e: IOException) {
            log("restore journal could not be updated; the next run writes these values again: $targets", e)
        }
    }

    private suspend fun clear() {
        try {
            withContext(Dispatchers.IO) { journal.clear() }
        } catch (e: IOException) {
            log("restore journal could not be cleared; the next run writes its values again", e)
        }
    }
}
