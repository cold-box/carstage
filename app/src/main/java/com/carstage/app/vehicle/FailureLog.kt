package com.carstage.app.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Logs failures without flooding the log and without hiding them: when something starts failing
 * or fails differently (with [cause], if given), every [EVERY]th failure in a row, and when it
 * works again.
 */
internal class FailureLog(private val log: (String, Throwable?) -> Unit) {
    private class Streak(var kind: String, var count: Int)

    private val streaks = HashMap<String, Streak>()

    @Synchronized
    fun failed(what: String, kind: String, cause: Throwable? = null) {
        val streak = streaks[what]
        when {
            streak == null -> {
                streaks[what] = Streak(kind, 1)
                log("$what failed: $kind", cause)
            }
            streak.kind != kind -> {
                streak.kind = kind
                streak.count++
                log("$what failed differently (${streak.count} in a row): $kind", cause)
            }
            ++streak.count % EVERY == 0 -> log("$what failed ${streak.count} times in a row: $kind", null)
        }
    }

    @Synchronized
    fun succeeded(what: String) {
        streaks.remove(what)?.let { log("$what works again after ${it.count} failures", null) }
    }

    /** How [what] is failing right now, or null when its last attempt worked. */
    @Synchronized
    fun current(what: String): String? = streaks[what]?.kind

    /**
     * Runs [block]; a failure or a missing value ([missing]) is logged under [what] and becomes null,
     * and a success after failures logs that it works again. Cancellation of the caller is rethrown.
     */
    suspend fun <T : Any> attempt(what: String, missing: String = "no value", block: suspend () -> T?): T? {
        val failure = try {
            block()?.let { value ->
                succeeded(what)
                return value
            }
            missing
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            e.describe()
        }
        failed(what, failure)
        return null
    }

    companion object {
        /** About every 10 s for the gear (read twice a second), about every minute for the seats (read every 3 s). */
        const val EVERY = 20
    }
}
