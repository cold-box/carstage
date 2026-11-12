package com.carstage.app

import com.carstage.app.stage.ParkedRestore
import com.carstage.app.stage.StageRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Puts back what an earlier stage left in the car (the app ended before it closed, or restoring at
 * close failed): at start, before every open and Lab probe, and once the car is parked for values
 * it refused while driving or a close could not put back ([ParkedRestore]). Runs only while no
 * stage is open ([whileClosed]), so an open stage's journal is never taken for the last one's.
 * Tells the user through [notices], and keeps that notice for the next open ([takeNotice]) so
 * opening right away does not hide it.
 */
class Leftovers(
    scope: CoroutineScope,
    private val recovery: StageRecovery,
    /** Runs a block only while no stage is opening, open or closing; null otherwise. */
    private val whileClosed: suspend (suspend () -> StageRecovery.Result) -> StageRecovery.Result?,
    /** True when parked and the stage may open, false when not, null when the gear is unknown. */
    private val parkedNow: suspend () -> Boolean?,
    private val notices: NoticeBoard,
    private val warn: (String) -> Unit,
) {
    /** What the last recovery found, shown with the next open. */
    @Volatile
    private var notice: String? = null

    /**
     * Set when a recovery forgot values it could not restore while others wait for parking, as the
     * screen is hidden while the car drives; told with the next notice of a run that leaves
     * nothing waiting, and cleared when [parkedRestore] gives up.
     */
    @Volatile
    private var droppedBeforeParking = false

    private val parkedRestore = ParkedRestore(
        scope = scope,
        parked = parkedNow,
        recover = { recover(retried = true) },
        log = warn,
        onGaveUp = {
            droppedBeforeParking = false
            notices.show(NoticeBoard.RECOVERY_GAVE_UP, NoticeTone.ERROR)
        },
    )

    /**
     * Restores what the last stage left, unless a stage is open now. False when the car cannot be
     * reached yet or values wait for the car to be parked; the journal then keeps them.
     */
    suspend fun restore(): Boolean = when (val result = recover()) {
        StageRecovery.Result.Unreachable -> false
        is StageRecovery.Result.Restored -> result.pending.isEmpty()
        else -> true
    }

    /**
     * Restores at start, retrying while the car cannot be reached (the vehicle service may come up
     * later than the app), and tells the user while it waits and if it gives up. Values that wait
     * for parking are left to [parkedRestore].
     */
    suspend fun restoreAtStart() {
        repeat(START_ATTEMPTS) { attempt ->
            if (recover() != StageRecovery.Result.Unreachable) return
            if (attempt == 0) notices.show("지난 무대에서 바뀐 차량 설정을 되돌리려고 차량 연결을 기다리고 있어요.", NoticeTone.INFO)
            delay(START_RETRY_MS)
        }
        warn("gave up restoring the last stage at start; the next open tries again")
        notices.show(NoticeBoard.RECOVERY_GAVE_UP, NoticeTone.ERROR)
    }

    /** A stage that closed left values for when the car is parked; see [ParkedRestore]. */
    fun restoreWhenParked() = parkedRestore.request()

    /** What the last recovery found, once: the next open shows it. */
    fun takeNotice(): String? = notice.also { notice = null }

    /**
     * Runs the recovery unless a stage is open (null then) and tells the user what it did. Values
     * the car refused only because it was driving stay in the journal if it is still not parked
     * after the writes, and [parkedRestore] puts them back once it is; [retried] marks that run.
     */
    private suspend fun recover(retried: Boolean = false): StageRecovery.Result? {
        val result = whileClosed { recovery.run(parked = { parkedNow() == true }) }
        if (result is StageRecovery.Result.Restored && result.pending.isNotEmpty() && result.failures.isNotEmpty()) {
            droppedBeforeParking = true
        }
        val (text, tone) = when (result) {
            null, StageRecovery.Result.NothingToDo, StageRecovery.Result.Unreachable -> return result
            StageRecovery.Result.Unreadable ->
                "지난 무대 기록을 읽지 못했어요. 차량 설정이 바뀌어 있으면 차량 설정에서 되돌려 주세요." to NoticeTone.ERROR
            is StageRecovery.Result.Restored -> restoredNotice(result, retried)
        }
        if (result is StageRecovery.Result.Restored && result.pending.isNotEmpty()) parkedRestore.request()
        // Shown (and its kind recorded) before an open can take it.
        notices.show(text, tone)
        notice = text
        return result
    }

    /** What a recovery that ran says, and of what kind: anything not put back went wrong. */
    private fun restoredNotice(result: StageRecovery.Result.Restored, retried: Boolean): Pair<String, NoticeTone> {
        val what = if (retried) "되돌리지 못했던 차량 설정" else "지난 무대에서 바뀐 차량 설정"
        val said = when {
            result.failures.isNotEmpty() && result.pending.isNotEmpty() ->
                "$what 중 일부는 되돌리지 못했어요. 차량 설정에서 확인해 주세요. 나머지는 정차하면 되돌릴게요." to NoticeTone.ERROR
            result.failures.size == result.targets.size -> "${what}을 되돌리지 못했어요. 차량 설정에서 확인해 주세요." to NoticeTone.ERROR
            result.failures.isNotEmpty() -> "${what}을 되돌렸지만, 일부는 되돌리지 못했어요. 차량 설정에서 확인해 주세요." to NoticeTone.ERROR
            result.pending.isNotEmpty() -> NoticeBoard.RESTORE_AFTER_PARKING to NoticeTone.CAUTION
            else -> "${what}을 원래대로 되돌렸어요." to NoticeTone.INFO
        }
        // The screen is hidden while the car drives, so what was forgotten then is told now.
        if (result.pending.isNotEmpty() || !droppedBeforeParking) return said
        droppedBeforeParking = false
        return "${said.first} 주행 중에 일부 설정은 되돌리지 못했어요. 차량 설정에서 확인해 주세요." to NoticeTone.ERROR
    }

    private companion object {
        const val START_ATTEMPTS = 5
        const val START_RETRY_MS = 30_000L
    }
}
