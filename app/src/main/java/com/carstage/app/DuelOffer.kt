package com.carstage.app

import com.carstage.app.vehicle.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Offers a duel when both front seats are taken (docs/04 "기능 2", "시작"): while the stage is
 * [open], reads the front seats at once and then [POLL_MS] after each read, and [offered] is true
 * while both are taken. It is false while
 * the stage is not open, when nothing is read, and while the seats cannot be read ([bothSeated]
 * null, or throwing, which it should not). Each change of the offer on an open stage is logged.
 */
class DuelOffer(
    scope: CoroutineScope,
    open: Flow<Boolean>,
    private val bothSeated: suspend () -> Boolean?,
    private val log: (String) -> Unit,
) {
    private val _offered = MutableStateFlow(false)
    val offered: StateFlow<Boolean> = _offered.asStateFlow()

    init {
        scope.launch {
            open.distinctUntilChanged().collectLatest { isOpen ->
                if (!isOpen) {
                    _offered.value = false
                    return@collectLatest
                }
                while (true) {
                    val seated = readSeats()
                    val offer = seated == true
                    if (offer != _offered.value) {
                        log(
                            when (seated) {
                                true -> "offering a duel: both front seats are taken"
                                false -> "no longer offering a duel: a front seat is free"
                                null -> "no longer offering a duel: the front seats cannot be read"
                            },
                        )
                    }
                    _offered.value = offer
                    delay(POLL_MS)
                }
            }
        }
    }

    /** [bothSeated], with a failure it should have caught logged and read as unknown, so the offer keeps watching. */
    private suspend fun readSeats(): Boolean? = try {
        bothSeated()
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        log("reading the front seats failed: ${e.describe()}")
        null
    }

    companion object {
        /** Someone sitting down or getting out shows within a few seconds. */
        const val POLL_MS = 3_000L
    }
}
