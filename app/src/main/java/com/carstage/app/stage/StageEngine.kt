package com.carstage.app.stage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends cabin commands to every output (the car and the simulator) and puts back what it changed
 * when the stage ends.
 *
 * - Each output has its own queue and sender, and outputs are captured and restored in parallel,
 *   so a slow car never holds up the simulator.
 * - A sender sends at most one batch every [minIntervalMs]; when several commands for the same
 *   target are queued, only the newest is sent. Two cue steps for one target closer than that
 *   interval therefore merge.
 * - [start] captures each output's current values. A target that could not be captured is never
 *   changed on that output, because it could not be restored.
 * - [stop] drops commands still queued, then restores every target that changed or may have
 *   changed. It works even when called from a cancelled coroutine; call it in a `finally` block.
 *   [start] and [stop] are serialized. Restores an output keeps for later ([CabinRestoreDeferred])
 *   are reported apart from failures.
 */
class StageEngine(
    private val outputs: List<CabinOutput>,
    private val report: (String) -> Unit,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    /**
     * What [stop] could not restore: [failures], and [deferred] restores an output keeps to do
     * later ([CabinRestoreDeferred]). Both are empty when everything was put back or nothing was
     * running.
     */
    data class StopResult(val failures: List<String>, val deferred: List<String> = emptyList()) {
        val restoredAll: Boolean get() = failures.isEmpty() && deferred.isEmpty()

        /** Nothing failed outright; only restores the car takes back once it is parked are left. */
        val onlyDeferred: Boolean get() = failures.isEmpty() && deferred.isNotEmpty()
    }

    private class Restores(val failures: List<String>, val deferred: List<String>)

    private inner class Lane(val output: CabinOutput, val restorable: Set<CabinTarget>) {
        val queue = Channel<CabinCommand>(Channel.UNLIMITED)

        @Volatile
        var stopping = false

        /** Changed or possibly changed targets; written only by this lane's sender, read after it ends. */
        val touched = LinkedHashSet<CabinTarget>()
        private val problems = LinkedHashMap<String, Int>()
        lateinit var sender: Job

        suspend fun send() {
            for (first in queue) {
                if (stopping) break
                val batch = LinkedHashMap<CabinTarget, CabinCommand>()
                batch[first.target] = first
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch.remove(next.target)
                    batch[next.target] = next
                }
                batch.values.forEach { apply(it) }
                delay(minIntervalMs)
            }
        }

        private suspend fun apply(command: CabinCommand) {
            val target = command.target
            if (target !in restorable) {
                problem("$target skipped: its value could not be captured, so it could not be restored")
                return
            }
            // Marked before the write: a write that times out or is cancelled may still have landed.
            val firstChange = touched.add(target)
            try {
                if (output.apply(command) == ApplyResult.UNSUPPORTED) {
                    if (firstChange) touched.remove(target)
                    problem("$target unsupported")
                }
            } catch (e: CabinWriteRejected) {
                if (firstChange) touched.remove(target)
                problem("$target rejected: ${e.message}")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                problem("$target failed: ${e.describe()}")
            }
        }

        /** Restores every touched target, the ambient switch last, and returns what failed or was deferred. */
        suspend fun restore(): Restores {
            val failures = mutableListOf<String>()
            val deferred = mutableListOf<String>()
            touched.sortedBy { it == CabinTarget.AmbientSwitch }.forEach { target ->
                try {
                    output.restore(target)
                } catch (e: CabinRestoreDeferred) {
                    deferred += "${output.name}: restore $target deferred: ${e.message}"
                } catch (e: Exception) {
                    failures += "${output.name}: restore $target failed: ${e.describe()}"
                }
            }
            return Restores(failures, deferred)
        }

        /** Tells the output the stage has ended; returns what failed. */
        suspend fun end(): List<String> = try {
            output.ended()
            emptyList()
        } catch (e: Exception) {
            listOf("${output.name}: ending the stage failed: ${e.describe()}")
        }

        /** Reports a problem the first time and counts repeats, so a persistent failure does not flood the log. */
        private fun problem(message: String) {
            val count = problems.merge(message, 1, Int::plus)!!
            if (count == 1) report("${output.name}: $message")
        }

        fun repeatedProblems(): List<String> =
            problems.filterValues { it > 1 }.map { (message, count) -> "${output.name}: $message ($count times)" }
    }

    private val lifecycle = Mutex()

    @Volatile
    private var lanes: List<Lane>? = null

    @Volatile
    private var accepting = false

    val isRunning: Boolean get() = lanes != null

    /**
     * Captures every output in parallel and starts their senders in [scope]. Outputs that cannot
     * be captured are left out; returns the names of the outputs being driven.
     */
    suspend fun start(scope: CoroutineScope): List<String> = lifecycle.withLock {
        check(lanes == null) { "stage already running" }
        val started = coroutineScope {
            outputs.map { output -> async { capture(output) } }.awaitAll().filterNotNull()
        }
        if (started.isEmpty()) report("no output can be driven this stage")
        started.forEach { lane ->
            lane.sender = scope.launch { lane.send() }
            lane.sender.invokeOnCompletion { cause ->
                if (cause != null && !lane.stopping && lanes?.contains(lane) == true) {
                    report("${lane.output.name}: sender ended before stop() (${cause.describe()}); the cabin stays changed until stop() runs")
                }
            }
        }
        lanes = started
        accepting = true
        started.map { it.output.name }
    }

    private suspend fun capture(output: CabinOutput): Lane? = try {
        Lane(output, output.capture().targets)
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        report("${output.name}: not driven this stage, capture failed: ${e.describe()}")
        null
    }

    /** Queues [command] for every output; returns false when the stage is not running or is stopping. */
    fun send(command: CabinCommand): Boolean {
        val running = lanes
        if (!accepting || running == null) return false
        running.forEach { it.queue.trySend(command) }
        return true
    }

    /** Drops queued commands and restores every output in parallel to its captured values. */
    suspend fun stop(): StopResult = withContext(NonCancellable) {
        lifecycle.withLock {
            val running = lanes ?: return@withLock StopResult(emptyList())
            accepting = false
            running.forEach {
                it.stopping = true
                it.queue.close()
            }
            val results = coroutineScope {
                running.map { lane ->
                    async {
                        lane.sender.join()
                        val restores = lane.restore()
                        lane.repeatedProblems().forEach(report)
                        Restores(restores.failures + lane.end(), restores.deferred)
                    }
                }.awaitAll()
            }
            val result = StopResult(results.flatMap { it.failures }, results.flatMap { it.deferred })
            (result.failures + result.deferred).forEach(report)
            lanes = null
            result
        }
    }

    companion object {
        /** Ambient writes were accepted every 150 ms on the emulator; stay a little faster than that. */
        const val DEFAULT_MIN_INTERVAL_MS = 120L

        private fun Throwable.describe() = "${this::class.java.simpleName}: $message"
    }
}
