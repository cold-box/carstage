package com.carstage.app.stage

/**
 * Known cabin values, stored as the command that would set each one. A target without a value is
 * unknown or unsupported. Because the value is a command, restoring a target is just sending it.
 */
class CabinState private constructor(private val values: Map<CabinTarget, CabinCommand>) {
    constructor() : this(emptyMap())

    val targets: Set<CabinTarget> get() = values.keys

    operator fun get(target: CabinTarget): CabinCommand? = values[target]

    fun with(command: CabinCommand) = CabinState(values + (command.target to command))

    /** Commands that set [targets] back to this state; the ambient switch goes last so color and brightness are set first. */
    fun restoreCommands(targets: Set<CabinTarget> = this.targets): List<CabinCommand> =
        values.filterKeys { it in targets }.values.sortedBy { it.target == CabinTarget.AmbientSwitch }

    val ambientOn: Boolean? get() = (values[CabinTarget.AmbientSwitch] as CabinCommand.AmbientSwitch?)?.on
    val ambientColor: Rgb? get() = (values[CabinTarget.AmbientColor] as CabinCommand.AmbientColor?)?.color
    val ambientBrightness: Percent? get() = (values[CabinTarget.AmbientBrightness] as CabinCommand.AmbientBrightness?)?.level
    /** The fan as steps from the stage's start (see [CabinCommand.FanStep]). */
    val fanSteps: Int? get() = (values[CabinTarget.Fan] as CabinCommand.FanStep?)?.steps

    fun readingLight(side: Side): Boolean? = (values[CabinTarget.ReadingLight(side)] as CabinCommand.ReadingLight?)?.on

    fun seatVentilation(side: Side): Int? =
        (values[CabinTarget.SeatVentilation(side)] as CabinCommand.SeatVentilation?)?.level

    /** A side's temperature as an offset from the stage's start (see [CabinCommand.Temperature]). */
    fun temperatureOffset(side: Side): Float? =
        (values[CabinTarget.Temperature(side)] as CabinCommand.Temperature?)?.offset

    override fun equals(other: Any?) = other is CabinState && other.values == values

    override fun hashCode() = values.hashCode()

    override fun toString() = values.values.joinToString(prefix = "CabinState(", postfix = ")")

    companion object {
        fun of(vararg commands: CabinCommand) = commands.fold(CabinState()) { state, command -> state.with(command) }
    }
}
