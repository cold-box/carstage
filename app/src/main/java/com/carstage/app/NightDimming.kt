package com.carstage.app

import com.carstage.app.stage.GlowDirector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Night consideration (docs/07 "야간 밝기 배려"): decides whether it is night ([dim]) from the car
 * ([night]; unknown is day) or a test, for each stage's director, which then scales the ambient
 * brightness into 0..[GlowDirector.NIGHT_CEILING]%. When the stage turns [open] at night, or night
 * comes while it is open, the screen is told ([show]); the notice comes down ([hide]) when it is
 * day again or the stage closes. Like the battery notice, a notice that replaces the whole line
 * takes it down until one of those changes.
 */
class NightDimming(
    scope: CoroutineScope,
    open: Flow<Boolean>,
    night: Flow<Boolean?>,
    private val show: (String) -> Unit,
    private val hide: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    /** Night or day whatever the car says, set by debug builds' tests (DevLab); null follows the car. */
    private val forced = MutableStateFlow<Boolean?>(null)

    /**
     * Whether a stage's light is dimmed for the night; read from the audio thread. Kept while no
     * stage is open too, so the next stage's first light is already dim.
     */
    @Volatile
    var dim = false
        private set

    init {
        scope.launch {
            combine(open, night, forced) { isOpen, car, test -> Triple(isOpen, car, test) }
                .distinctUntilChanged()
                .collect { (isOpen, car, test) ->
                    val now = test ?: (car == true)
                    if (now != dim) {
                        dim = now
                        val why = when {
                            test != null -> "by a test"
                            car == null -> "as the car does not say"
                            else -> "by the car"
                        }
                        log(if (now) "night $why: the stage's light dims to ${GlowDirector.NIGHT_CEILING}% at most" else "day $why: the stage's light uses its full range")
                    }
                    if (isOpen && now) show(NOTICE) else hide(NOTICE)
                }
        }
    }

    /** Makes it night ([night] true) or day for tests whatever the car says, or follows the car again (null). */
    fun force(night: Boolean?) {
        forced.value = night
    }

    companion object {
        const val NOTICE = "밤이라 눈부시지 않게 조명을 낮춰서 비출게요."
    }
}
