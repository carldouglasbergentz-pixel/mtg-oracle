package mtgoracle.core.model

/**
 * Where the seat gets priority with an empty stack (MTGO's phase stops).
 *
 * Anywhere else the game flows past, unless something goes on the stack or a
 * decision is needed (attackers, blockers, targets) — those always stop.
 */
data class PhaseStops(val ownTurn: Set<Step>, val opponentTurn: Set<Step>) {

    fun stopsAt(seatsTurn: Boolean, step: Step): Boolean =
        step in (if (seatsTurn) ownTurn else opponentTurn)

    fun toggled(seatsTurn: Boolean, step: Step): PhaseStops =
        if (seatsTurn) copy(ownTurn = ownTurn.flip(step)) else copy(opponentTurn = opponentTurn.flip(step))

    /** "own=MAIN1,MAIN2;opponent=END_OF_TURN" — the settings-file form. */
    fun serialise(): String = "own=${ownTurn.names()};opponent=${opponentTurn.names()}"

    companion object {
        /** Main phases on your turn; attackers and end step on theirs — the draw-go windows. */
        val DEFAULT = PhaseStops(
            ownTurn = setOf(Step.MAIN1, Step.MAIN2),
            opponentTurn = setOf(Step.COMBAT_DECLARE_ATTACKERS, Step.END_OF_TURN),
        )

        /** Unknown step names are dropped rather than failing: a settings file outlives a build. */
        fun parse(text: String?): PhaseStops {
            if (text.isNullOrBlank()) return DEFAULT
            val parts = text.split(';').associate { part ->
                part.substringBefore('=').trim() to part.substringAfter('=', "")
            }
            fun steps(key: String) = parts[key].orEmpty().split(',')
                .mapNotNull { name -> Step.entries.firstOrNull { it.name == name.trim() } }.toSet()
            if ("own" !in parts || "opponent" !in parts) return DEFAULT
            return PhaseStops(steps("own"), steps("opponent"))
        }

        private fun Set<Step>.flip(step: Step) = if (step in this) this - step else this + step
        private fun Set<Step>.names() = sortedBy { it.ordinal }.joinToString(",") { it.name }
    }
}
