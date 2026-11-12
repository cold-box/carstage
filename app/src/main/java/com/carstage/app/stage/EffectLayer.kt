package com.carstage.app.stage

/**
 * One part of a stage effect, such as the light following the voices or the reading lights
 * answering a cheer.
 * A layer hears every audio event and says what it wants now for the targets it cares about;
 * [LayeredDirector] merges the layers per target. A layer keeps its values steady until they move
 * enough to see, since the director sends every change of the merged value.
 */
interface EffectLayer {
    /** Hears one event; called for every event, before [wants]. */
    fun hear(event: AudioEvent)

    /** What the layer wants now, at most one command per target; a target left out has no opinion from it. */
    fun wants(): List<CabinCommand>
}

/**
 * A [Director] made of [layers], merged per target:
 * - brightness, fan and seat ventilation: the highest wins, as lighting consoles merge brightness;
 * - temperature: the coolest (lowest offset) wins;
 * - the ambient switch and the reading lights: on if any layer wants them on;
 * - the ambient color: the first layer in [layers] that wants one.
 *
 * What [exclusive] wants replaces the merged value for those targets, for a show that must also
 * lower the light (the duel's announcement); the other layers still hear every event meanwhile.
 * The ambient brightness is then scaled into 0..[ceiling] ([dimmed]), read at each event, so at
 * night the light keeps the shape of its movement in a narrower range, and by day its full range.
 *
 * Sends a target only when its merged (and scaled) value changed, and again after the safety or battery
 * rules held it back ([dropped]). A target no layer has an opinion on is left as it is, except a
 * target set as a change from the stage's start ([startOf]: the fan, the temperature): that one
 * goes back to the start once nobody wants a change, and keeps asking until the rules let it out;
 * and a change back to the start is never sent for one the rules never let leave it, so a "back"
 * cannot write the car while, say, the defroster runs. Create one per stage; the layers carry on
 * from one song to the next.
 */
class LayeredDirector(
    private val layers: List<EffectLayer>,
    private val exclusive: EffectLayer? = null,
    private val ceiling: () -> Percent = { Percent(100) },
) : Director {
    private val sent = mutableMapOf<CabinTarget, CabinCommand>()

    /** For targets changed from the start: the change the rules let out (none is the start), and the one before it. */
    private val inEffect = mutableMapOf<CabinTarget, CabinCommand>()
    private val before = mutableMapOf<CabinTarget, CabinCommand?>()

    override fun direct(event: AudioEvent): List<CabinCommand> {
        layers.forEach { it.hear(event) }
        exclusive?.hear(event)
        val wanted = merge(layers.flatMap { it.wants() })
        val overriding = exclusive?.wants().orEmpty()
        val top = ceiling()
        val merged = (if (overriding.isEmpty()) wanted else wanted.filter { w -> overriding.none { it.target == w.target } } + overriding)
            .map { dimmed(it, top) }
        val back = inEffect.keys.filter { target -> merged.none { it.target == target } }.mapNotNull(::startOf)
        return (merged + back).filter { command ->
            when (val start = startOf(command.target)) {
                null -> sent[command.target] != command
                else -> (inEffect[command.target] ?: start) != command
            }
        }.onEach { command ->
            if (startOf(command.target) == null) {
                sent[command.target] = command
            } else {
                before[command.target] = inEffect[command.target]
                inEffect[command.target] = command
            }
        }
    }

    /** Forgets what was held back, so the next event sends the merged value again. */
    override fun dropped(command: CabinCommand) {
        if (startOf(command.target) == null) {
            if (sent[command.target] == command) sent.remove(command.target)
        } else if (inEffect[command.target] == command) {
            // The car still has what it had before this one.
            when (val previous = before[command.target]) {
                null -> inEffect.remove(command.target)
                else -> inEffect[command.target] = previous
            }
        }
    }

    companion object {
        /** The stage's start for a target set as a change from it; null for a target set to a value. */
        fun startOf(target: CabinTarget): CabinCommand? = when (target) {
            CabinTarget.AmbientSwitch, CabinTarget.AmbientColor, CabinTarget.AmbientBrightness -> null
            is CabinTarget.ReadingLight, is CabinTarget.SeatVentilation -> null
            CabinTarget.Fan -> CabinCommand.FanStep(0)
            is CabinTarget.Temperature -> CabinCommand.Temperature(target.side, 0f)
        }

        /** [command] scaled into 0..[ceiling] if it is a brightness, rounded; any other command as it is. */
        fun dimmed(command: CabinCommand, ceiling: Percent): CabinCommand = when {
            command !is CabinCommand.AmbientBrightness || ceiling.value == 100 -> command
            else -> CabinCommand.AmbientBrightness(Percent((command.level.value * ceiling.value + 50) / 100))
        }

        /** One command per target, in the order the targets first appear. */
        fun merge(wanted: List<CabinCommand>): List<CabinCommand> = wanted.groupBy { it.target }.values.map { options ->
            when (val first = options.first()) {
                is CabinCommand.AmbientSwitch -> options.firstOrNull { (it as CabinCommand.AmbientSwitch).on } ?: first
                is CabinCommand.AmbientColor -> first
                is CabinCommand.AmbientBrightness -> options.maxBy { (it as CabinCommand.AmbientBrightness).level.value }
                is CabinCommand.ReadingLight -> options.firstOrNull { (it as CabinCommand.ReadingLight).on } ?: first
                is CabinCommand.FanStep -> options.maxBy { (it as CabinCommand.FanStep).steps }
                is CabinCommand.SeatVentilation -> options.maxBy { (it as CabinCommand.SeatVentilation).level }
                // The coolest: a cool celebration is the only one asked for.
                is CabinCommand.Temperature -> options.minBy { (it as CabinCommand.Temperature).offset }
            }
        }
    }
}
