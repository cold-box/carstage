package com.carstage.app.stage

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cabin targets the car does not let this app control in this stage ([CabinControlDenied]), so
 * their writes are not sent again until the next stage, which tries each once more (a car may
 * refuse for a reason of its own that passes, not only for a permission an install or update
 * changes). Vehicle Control is "Coming Soon" in the Pleos docs, so a car may refuse some or all of
 * them. Tells [onDenied] at the first denial of a stage, so the screen can say the stage reacts on
 * the screen only for those. [refused] outlives the stage: it holds from a denial until the car
 * takes a write to every target it refused, for as long as the app runs.
 *
 * The stage's vehicle lane calls it one command at a time; [refused] may be read from any thread.
 */
class ControlDenials(private val onDenied: () -> Unit) {
    private val denied = ConcurrentHashMap.newKeySet<CabinTarget>()

    @Volatile
    private var told = false

    /** The targets refused when last tried; kept across stages, so [refused] holds while the next stage tries them again. */
    private val refusing = HashSet<CabinTarget>()

    private val _refused = MutableStateFlow(false)

    /** Whether some target is refused (see the class); the screen then calls the cabin drawing a preview (docs/11 5장). */
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    /** A stage starts: every target is tried again, and its first denial is told. */
    fun stageStarted() {
        denied.clear()
        told = false
    }

    fun isDenied(target: CabinTarget): Boolean = target in denied

    /** The car refused a write to [target] for want of permission. */
    fun denied(target: CabinTarget) {
        denied += target
        synchronized(refusing) {
            refusing += target
            _refused.value = true
        }
        tell()
    }

    /** The car took a write to [target]. */
    fun allowed(target: CabinTarget) {
        synchronized(refusing) {
            if (refusing.remove(target)) _refused.value = refusing.isNotEmpty()
        }
    }

    private fun tell() {
        if (told) return
        told = true
        onDenied()
    }
}
