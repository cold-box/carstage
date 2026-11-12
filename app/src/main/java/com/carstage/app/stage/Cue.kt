package com.carstage.app.stage

import kotlinx.coroutines.delay

/** A timed sequence of cabin commands, such as the finale. */
class Cue(steps: List<Step>) {
    data class Step(val atMs: Long, val command: CabinCommand) {
        init {
            require(atMs >= 0) { "step time $atMs ms is negative" }
        }
    }

    /** Sorted by time; steps at the same time keep their order. */
    val steps: List<Step> = steps.sortedBy { it.atMs }

    val durationMs: Long get() = steps.lastOrNull()?.atMs ?: 0

    class Builder {
        private val steps = mutableListOf<Step>()

        fun at(atMs: Long, vararg commands: CabinCommand) {
            commands.forEach { steps += Step(atMs, it) }
        }

        fun build() = Cue(steps)
    }

    companion object {
        fun build(block: Builder.() -> Unit): Cue = Builder().apply(block).build()
    }
}

/**
 * Sends each step of [cue] at its time, measured from the call. Steps for one target closer than
 * the engine's minimum interval merge into the later one.
 */
suspend fun StageEngine.play(cue: Cue) {
    check(isRunning) { "start the stage before playing a cue" }
    var elapsed = 0L
    for (step in cue.steps) {
        if (step.atMs > elapsed) {
            delay(step.atMs - elapsed)
            elapsed = step.atMs
        }
        send(step.command)
    }
}
