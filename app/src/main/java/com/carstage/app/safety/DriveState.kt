package com.carstage.app.safety

import com.carstage.app.NoticeTone
import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.CabinTarget

/**
 * What the car is doing, as far as the stage's safety rules care. Null means unknown or
 * unsupported: an unknown gear stops the stage and unknown climate pauses climate effects.
 * Unknown restrictions do not block, since the gear rule already covers driving.
 */
data class DriveState(val motion: Motion = Motion(), val climate: Climate = Climate()) {
    /** Gear and driving restrictions: whether the stage may run at all. */
    data class Motion(
        val parked: Boolean? = null,
        /** The gear as the car reported it, for the logs. */
        val gear: String? = null,
        /** Why the gear is unknown, for the logs. */
        val gearError: String? = null,
        val restricted: Boolean? = null,
    )

    /** Defroster and wipers: whether climate effects may run. */
    data class Climate(val defrosting: Boolean? = null, val wiping: Boolean? = null)

    /**
     * Why the stage may not run, with what to tell the user (in Korean) when opening or stopping
     * it, and of what kind: a gear that cannot be read went wrong; driving and the car's
     * restrictions are the rules working, a stop for them only says what happened.
     */
    enum class Block(val refusal: String, val stopNotice: String, val refusalTone: NoticeTone, val stopTone: NoticeTone) {
        GEAR_UNKNOWN(
            "차의 기어 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.",
            "차의 기어 상태를 확인하지 못해서 무대를 멈췄어요. 정차 중이면 다시 열어 주세요.",
            NoticeTone.ERROR,
            NoticeTone.ERROR,
        ),
        NOT_PARKED(
            "정차 중에만 무대를 열 수 있어요.",
            "주행을 시작해서 무대를 멈췄어요. 정차하면 다시 열 수 있어요.",
            NoticeTone.CAUTION,
            NoticeTone.INFO,
        ),
        RESTRICTED(
            "지금은 차량이 앱 사용을 제한하고 있어서 무대를 열 수 없어요.",
            "차량이 앱 사용을 제한해서 무대를 멈췄어요.",
            NoticeTone.CAUTION,
            NoticeTone.INFO,
        ),
    }

    /** The stage runs only when the car is known to be parked and no driving restriction is active. */
    val block: Block?
        get() = when {
            motion.parked == null -> Block.GEAR_UNKNOWN
            motion.parked == false -> Block.NOT_PARKED
            motion.restricted == true -> Block.RESTRICTED
            else -> null
        }

    val stageAllowed: Boolean get() = block == null

    /** Climate effects run only while the defroster and the wipers are known to be off. */
    val climateAllowed: Boolean get() = climate.defrosting == false && climate.wiping == false
}

/** The product's safety rules for single commands (CLAUDE.md, Product principles). */
object SafetyRules {
    /**
     * Whether [command] may be sent in [state]. Nothing is sent unless the stage may run, so a
     * command between a gear change and the stage closing is dropped; then the rule is decided per
     * target, exhaustively, so a new target forces a safety decision.
     */
    fun allows(command: CabinCommand, state: DriveState): Boolean = state.stageAllowed && when (command.target) {
        CabinTarget.AmbientSwitch, CabinTarget.AmbientColor, CabinTarget.AmbientBrightness -> true
        is CabinTarget.ReadingLight, is CabinTarget.SeatVentilation -> true
        // Climate: not while the car clears the windscreen (defroster, wipers) or cannot tell. A change
        // back to the start waits too; the director keeps asking for it (LayeredDirector).
        CabinTarget.Fan, is CabinTarget.Temperature -> state.climateAllowed
    }
}
