package com.carstage.app

import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.CabinTarget
import com.carstage.app.vehicle.BatteryState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The engine's gate: a command goes out only when the [safety] rules and the [battery] rules both
 * allow it. The battery rules are asked only after the safety rules allowed a command, so they see
 * exactly what is sent.
 */
fun cabinGate(safety: (CabinCommand) -> Boolean, battery: (CabinCommand) -> Boolean): (CabinCommand) -> Boolean =
    { safety(it) && battery(it) }

/**
 * Battery protection while the stage is [open] (docs/07 "배터리·공회전 보호"):
 * - An EV battery read under [LOW_PERCENT] and not charging holds back climate and seat effects
 *   that turn something on or up, or move a temperature further from where the stage found it
 *   ([allows]), so the lights alone answer the voices; climate and seat commands that turn
 *   something off, down or keep it (at most the level or offset last let through on this stage),
 *   and a temperature back to the start, still go through, since they cost no more battery.
 *   On the charger the effects run, as power comes from it. The screen is told once per stage
 *   ([show]); the notice comes down ([hide]) when the battery is no longer low and when the
 *   stage closes. How many commands were held back is logged when the stage closes.
 * - A car whose engine [mayIdle] (see [com.carstage.app.vehicle.CarKind.mayIdle]) is warned once
 *   per stage when the stage has been open for [IDLE_WARNING_MS]; the warning comes down when the
 *   stage closes.
 *
 * A battery that cannot be read keeps the last judgement on this stage; each stage judges anew
 * from the first reading it gets.
 */
class BatterySaver(
    scope: CoroutineScope,
    open: Flow<Boolean>,
    battery: Flow<BatteryState?>,
    private val mayIdle: () -> Boolean,
    private val show: (String) -> Unit,
    private val hide: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    /** Whether the battery was last read as low (and not charging) on the open stage; read from the audio thread. */
    @Volatile
    var low = false
        private set

    private val heldBack = AtomicInteger()

    /** The climate and seat levels last let through on this stage; going below them only saves the battery. */
    private val levels = ConcurrentHashMap<CabinTarget, Int>()

    /** The temperature offsets last let through on this stage; closer to the start only saves the battery. */
    private val offsets = ConcurrentHashMap<CabinTarget, Float>()

    init {
        scope.launch {
            open.distinctUntilChanged().collectLatest { isOpen ->
                low = false
                levels.clear()
                offsets.clear()
                if (!isOpen) {
                    heldBack.getAndSet(0).takeIf { it > 0 }?.let { log("battery rules held back $it commands in this stage") }
                    hide(LOW_NOTICE)
                    hide(IDLE_NOTICE)
                    return@collectLatest
                }
                heldBack.set(0)
                coroutineScope {
                    launch { judge(battery) }
                    delay(IDLE_WARNING_MS)
                    if (mayIdle()) {
                        log("stage open for over $IDLE_MINUTES min in a car that may idle: warning about idling")
                        show(IDLE_NOTICE)
                    }
                }
            }
        }
    }

    private suspend fun judge(battery: Flow<BatteryState?>) {
        var told = false
        battery.collect { state ->
            val percent = state?.percent ?: return@collect
            val now = percent < LOW_PERCENT && state.charging != true
            if (now == low) return@collect
            low = now
            log(
                when {
                    now -> "battery low at $percent%: holding back climate and seat effects"
                    state.charging == true -> "battery charging at $percent%: climate and seat effects again"
                    else -> "battery at $percent%: climate and seat effects again"
                },
            )
            if (now && !told) {
                told = true
                show(LOW_NOTICE)
            }
            if (!now) hide(LOW_NOTICE)
        }
    }

    /**
     * Whether [command] may be sent with the battery as last read: everything while it is not low;
     * when low, the lights, and climate and seat commands that turn something off, down or keep
     * it. Decided per command, exhaustively, so a new kind of command forces a decision. Called
     * for every command the safety rules let through, so it knows the levels sent.
     */
    fun allows(command: CabinCommand): Boolean {
        val allowed = when (command) {
            is CabinCommand.AmbientSwitch, is CabinCommand.AmbientColor, is CabinCommand.AmbientBrightness, is CabinCommand.ReadingLight -> true
            is CabinCommand.FanStep -> allowsLevel(command.target, command.steps)
            is CabinCommand.SeatVentilation -> allowsLevel(command.target, command.level)
            is CabinCommand.Temperature -> allowsOffset(command.target, command.offset)
        }
        if (!allowed) heldBack.incrementAndGet()
        return allowed
    }

    /**
     * Any level while the battery is not low; when low, off or at most the level last let through,
     * so a director that sends the same level again settles instead of being refused every event.
     */
    private fun allowsLevel(target: CabinTarget, level: Int): Boolean {
        val last = levels[target]
        // A fan below where the stage found it (negative steps) only saves the battery.
        val allowed = !low || level <= 0 || (last != null && level <= last)
        if (allowed) levels[target] = level
        return allowed
    }

    /**
     * Any temperature while the battery is not low; when low, back to the stage's start (0) or no
     * further from it than the offset last let through, as heating and cooling both draw on it.
     */
    private fun allowsOffset(target: CabinTarget, offset: Float): Boolean {
        val last = offsets[target]
        val allowed = !low || offset == 0f || (last != null && abs(offset) <= abs(last))
        if (allowed) offsets[target] = offset
        return allowed
    }

    companion object {
        /** The plan's example floor (docs/07 "배터리·공회전 보호"); under it the stage leaves the battery for driving. */
        const val LOW_PERCENT = 20

        /** Whole minutes; the notice states them. */
        const val IDLE_WARNING_MS = 30 * 60_000L
        private const val IDLE_MINUTES = IDLE_WARNING_MS / 60_000

        val LOW_NOTICE = "배터리가 $LOW_PERCENT%보다 적어서 공조와 시트 연출은 쉬고 조명으로만 반응할게요."
        val IDLE_NOTICE = "무대를 연 지 ${IDLE_MINUTES}분이 넘었어요. 정차한 채로 공회전이 길어지지 않게 주의해 주세요."
    }
}
