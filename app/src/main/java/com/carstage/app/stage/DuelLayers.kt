package com.carstage.app.stage

import com.carstage.app.stage.GlowDirector.Companion.FRAMES_PER_SECOND
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * While a duel's turn runs, the ambient light takes the singer's color (blue for the driver, red
 * for the passenger); brightness stays with the voices. Put it after the finale, so a turn's finale
 * still shows its grade, and before the layers that color the light with the voices, so the side's
 * color wins over theirs.
 */
class DuelTurnLayer(private val duel: () -> DuelState?) : EffectLayer {
    override fun hear(event: AudioEvent) = Unit

    override fun wants(): List<CabinCommand> {
        val state = duel() ?: return emptyList()
        if (state.step != DuelState.Step.TURN) return emptyList()
        return listOf(CabinCommand.AmbientColor(DuelState.colorOf(state.turn)))
    }
}

/**
 * The duel's announcement (docs/04 "발표 모드 연출 순서"), [TOTAL_FRAMES] long, given to
 * [LayeredDirector] as its exclusive layer so it can dim the light:
 * - for a second the ambient light dims and the reading lights go off;
 * - for about four seconds the two sides take turns, blue with the driver's reading light and red
 *   with the passenger's, faster and faster ([RUNS]): from a second down to
 *   [FASTEST_SWITCH_SECONDS] a side, so a side lights again no sooner than 0.7 s after it last
 *   came on, well under the three flashes a second that can trigger seizures;
 * - for a second everything is dim and the reading lights off;
 * - for four seconds the winner's color at full, the winner's reading light on and their seat
 *   ventilated and their temperature set [WINNER_COOLING] down (a tie: purple, both sides); the
 *   other side's seat is left as it is;
 * - then the reading lights and the ventilation it turned on go off, the temperature goes back to
 *   where the stage found it, and the other layers take the ambient light back. The lights and
 *   ventilation stay off until the stage closes and puts back what was there before.
 *
 * The drumroll starts with the sides taking turns and the fanfare with the crown ([takeCue]). The
 * MC ([takeLine], [DuelLines]) opens the announcement as it starts and names the winner
 * [CROWN_LINE_SECONDS] into the crown, as the fanfare ends, so the words are not lost under it.
 */
class DuelAnnouncement : EffectLayer {
    private var winner: Side? = null
    private var frame = 0
    private var running = false

    /** Frames left to want the end, so that an end the safety rules held back is sent again. */
    private var ending = 0

    /** The sound the frame just heard starts, until [takeCue] takes it. */
    private var cue: SoundCue? = null

    /** How the frame just heard changes the sound playing, until [takeChange] takes it. */
    private var change: SoundChange? = null

    /** What the MC says now, until [takeLine] takes it. */
    private var line: Line? = null

    /** The MC's lines ([DuelLines]): the opening as the light dims, the winner with the crown. */
    enum class Line { OPENING, CROWN }

    /** Starts the announcement for [winner] (null for a tie). */
    fun start(winner: Side?) {
        this.winner = winner
        frame = 0
        running = true
        ending = 0
        cue = null
        change = null
        line = Line.OPENING
    }

    /** Ends a running announcement now, turning off what it turned on, as for a new duel; true if one was running. */
    fun stop(): Boolean {
        if (!running) return false
        running = false
        ending = END_FRAMES
        cue = null
        change = null
        line = null
        return true
    }

    /** The sound to start now, once: the drumroll as the sides start taking turns, the fanfare with the crown. */
    fun takeCue(): SoundCue? = cue.also { cue = null }

    /**
     * How to change the sound playing now, once: as each turn of the lights starts, the drumroll
     * moves toward the side lit and plays a little faster ([DRUMROLL_RATES]); it comes back to both
     * sides for the hush, so it is not left on one side if it outlasts the turns; with the crown,
     * the fanfare moves to the winner's side (the move lands before its first note), and a tie's
     * stays in the middle.
     */
    fun takeChange(): SoundChange? = change.also { change = null }

    /** The line the MC says now, once: the opening as the announcement starts, the winner with the crown. */
    fun takeLine(): Line? = line.also { line = null }

    /** True once the announcement ran to its end or was stopped; it may still want its end for a moment. */
    val finished: Boolean get() = !running

    /** True from the crown's first frame, when the winner's light comes on, until the announcement is [finished]. */
    val crowned: Boolean get() = running && frame >= CROWN_FRAME

    override fun hear(event: AudioEvent) {
        if (event !is AudioEvent.Frame) return
        if (!running) {
            if (ending > 0) ending--
        } else if (++frame >= TOTAL_FRAMES) {
            running = false
            ending = END_FRAMES
        } else {
            when (frame) {
                DIM_FRAMES -> cue = SoundCue.DRUMROLL
                CROWN_FRAME -> {
                    cue = SoundCue.FANFARE
                    winner?.let { change = SoundChange(SoundPan.toward(it)) }
                }
                CROWN_FRAME + CROWN_LINE_FRAMES -> line = Line.CROWN
            }
            val shown = frame - DIM_FRAMES
            when {
                shown in SHOWN.indices && (shown == 0 || SHOWN[shown] != SHOWN[shown - 1]) ->
                    change = SoundChange(SoundPan.toward(SHOWN[shown]), DRUMROLL_RATES[TURN_OF[shown]])
                // At the last turn's rate, so a drumroll that outlasts the turns does not drop in pitch.
                shown == SHOWN.size -> change = SoundChange(SoundPan.CENTER, DRUMROLL_RATES.last())
            }
        }
    }

    override fun wants(): List<CabinCommand> {
        if (!running) return if (ending > 0) end() else emptyList()
        return when {
            frame < DIM_FRAMES -> dim()
            frame < DIM_FRAMES + ALTERNATING_FRAMES -> {
                val side = SHOWN[frame - DIM_FRAMES]
                listOf(
                    CabinCommand.AmbientSwitch(true),
                    CabinCommand.AmbientColor(DuelState.colorOf(side)),
                    CabinCommand.AmbientBrightness(Percent(ALTERNATING_BRIGHTNESS)),
                ) + Side.entries.map { CabinCommand.ReadingLight(it, it == side) }
            }
            frame < CROWN_FRAME -> dim()
            else -> listOf(
                CabinCommand.AmbientSwitch(true),
                CabinCommand.AmbientColor(DuelState.colorOf(winner)),
                CabinCommand.AmbientBrightness(Percent(100)),
            ) + Side.entries.map { CabinCommand.ReadingLight(it, it in celebrated()) } +
                celebrated().map { CabinCommand.SeatVentilation(it, WINNER_VENTILATION) } +
                celebrated().map { CabinCommand.Temperature(it, WINNER_COOLING) }
        }
    }

    /** The winner's side, or both for a tie. */
    private fun celebrated(): List<Side> = winner?.let { listOf(it) } ?: Side.entries

    /**
     * Both reading lights off, and where the winner's part turned them on, the ventilation off and
     * the temperature back to where the stage found it.
     */
    private fun end(): List<CabinCommand> =
        Side.entries.map { CabinCommand.ReadingLight(it, false) } +
            if (frame >= CROWN_FRAME) {
                celebrated().map { CabinCommand.SeatVentilation(it, 0) } + celebrated().map { CabinCommand.Temperature(it, 0f) }
            } else {
                emptyList()
            }

    private fun dim(): List<CabinCommand> =
        listOf(CabinCommand.AmbientSwitch(true), CabinCommand.AmbientBrightness(Percent(DIM_BRIGHTNESS))) +
            Side.entries.map { CabinCommand.ReadingLight(it, false) }

    companion object {
        /** The quickest a side stays lit; each run is [SPEED_UP] of the one before until then. */
        const val FASTEST_SWITCH_SECONDS = 0.35
        const val SPEED_UP = 0.8

        /** [FASTEST_SWITCH_SECONDS] in whole frames, rounded up. */
        private val FASTEST_RUN = ceil(FASTEST_SWITCH_SECONDS * FRAMES_PER_SECOND).toInt()

        /**
         * How many frames each side stays lit in turn, the driver first: a second, then
         * [SPEED_UP] of the run before but no shorter than [FASTEST_RUN], for as many runs as end
         * closest to four seconds.
         */
        val RUNS: List<Int> = buildList {
            val target = 4 * FRAMES_PER_SECOND
            var run = FRAMES_PER_SECOND.roundToInt()
            while (abs(sum() + run - target) < abs(sum() - target)) {
                add(run)
                run = maxOf(FASTEST_RUN, (run * SPEED_UP).roundToInt())
            }
        }

        val DIM_FRAMES = FRAMES_PER_SECOND.roundToInt()
        val ALTERNATING_FRAMES = RUNS.sum()
        val HUSH_FRAMES = FRAMES_PER_SECOND.roundToInt()
        val WINNER_FRAMES = (4 * FRAMES_PER_SECOND).roundToInt()
        val TOTAL_FRAMES = DIM_FRAMES + ALTERNATING_FRAMES + HUSH_FRAMES + WINNER_FRAMES

        /** The first frame of the winner's part. */
        private val CROWN_FRAME = DIM_FRAMES + ALTERNATING_FRAMES + HUSH_FRAMES

        /** How long into the crown the MC names the winner: as the fanfare (about 1.75 s) ends, with time left to say it. */
        const val CROWN_LINE_SECONDS = 1.8
        val CROWN_LINE_FRAMES = (CROWN_LINE_SECONDS * FRAMES_PER_SECOND).roundToInt()

        /**
         * About two seconds: how long the end is wanted after the announcement, longer than the
         * safety rules hold commands back for one unknown gear (a 0.5 s poll and a read of up to 1 s).
         */
        val END_FRAMES = (2 * FRAMES_PER_SECOND).roundToInt()

        /** Which side each frame of the alternating part shows. */
        private val SHOWN: List<Side> = RUNS.flatMapIndexed { i, run -> List(run) { if (i % 2 == 0) Side.DRIVER else Side.PASSENGER } }

        /** Which turn ([RUNS] index) each frame of the alternating part belongs to. */
        private val TURN_OF: List<Int> = RUNS.flatMapIndexed { i, run -> List(run) { i } }

        /**
         * How fast the drumroll plays in the last turn. Faster playing ends it sooner: at 1.15 the
         * four-second recording ends about 0.2 s before the hush, halfway through the last turn
         * (at 1.25 it ended 0.3 s early, leaving that turn nearly silent).
         */
        const val FASTEST_DRUMROLL = 1.15f

        /** How fast the drumroll plays in each turn, from as recorded to [FASTEST_DRUMROLL] in even steps, so it speeds up as the lights do. */
        val DRUMROLL_RATES: List<Float> = RUNS.indices.map { 1f + (FASTEST_DRUMROLL - 1f) * it / (RUNS.size - 1).coerceAtLeast(1) }

        const val DIM_BRIGHTNESS = 5
        const val ALTERNATING_BRIGHTNESS = 80

        /** The emulator's highest step; a car with fewer steps clamps it to its own range. */
        const val WINNER_VENTILATION = 3

        /**
         * The winner's "cool celebration" (docs/04 "좌우 연출 레이어" 5): their side's temperature set
         * this far below where it was for the crown, and back after it. The air itself barely
         * changes in four seconds; the setting is what a car's climate display can show.
         */
        const val WINNER_COOLING = -2f
    }
}
