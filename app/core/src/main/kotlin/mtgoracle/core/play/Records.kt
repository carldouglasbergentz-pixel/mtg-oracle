package mtgoracle.core.play

/*
 * Win-loss records from the `games` table (services.forge_results): per deck
 * and opponent, the games a person played apart from the simulated ones.
 * A deck's AI copy is that deck; a deck renamed since is its current name.
 */

/** A deck in the record: its id while it exists, else the name the row kept. */
data class DeckKey(val id: Int?, val name: String) {
    /** The same deck: by id when both have one, else by name. */
    fun sameAs(other: DeckKey): Boolean =
        if (id != null && other.id != null) id == other.id else name.equals(other.name, ignoreCase = true)
}

/** One finished or broken-off game, as a row gives it. `deck` is seat A: the human, or the first AI. */
data class PlayedGame(val mode: GameMode, val deck: DeckKey, val opponent: DeckKey, val winner: Winner?, val turns: Int?)

data class Tally(val wins: Int = 0, val losses: Int = 0, val draws: Int = 0, val turnsTotal: Int = 0, val decided: Int = 0) {
    val games: Int get() = wins + losses + draws
    /** Mean length of the decided games, the draws (often a stopped game) left out. */
    val averageTurns: Double? get() = if (decided > 0) turnsTotal.toDouble() / decided else null

    fun add(won: Boolean?, turns: Int?): Tally = when (won) {
        true -> copy(wins = wins + 1, turnsTotal = turnsTotal + (turns ?: 0), decided = decided + if (turns != null) 1 else 0)
        false -> copy(losses = losses + 1, turnsTotal = turnsTotal + (turns ?: 0), decided = decided + if (turns != null) 1 else 0)
        null -> copy(draws = draws + 1)
    }

    /** `3–2`, with `–1` more for draws: `12–7–1`. */
    fun score(): String = "$wins–$losses" + if (draws > 0) "–$draws" else ""
}

/** A deck against one opponent: games you played (against the AI or a person), and games the AI played for you. */
data class Matchup(val opponent: DeckKey, val played: Tally, val simulated: Tally) {
    val total: Tally get() = Tally(played.wins + simulated.wins, played.losses + simulated.losses, played.draws + simulated.draws,
        played.turnsTotal + simulated.turnsTotal, played.decided + simulated.decided)
}

object Records {
    /**
     * [deck]'s record against each opponent, the most games first. A game is
     * counted from whichever side [deck] sat on, so a simulation of A against
     * B is a win for one and a loss for the other; a deck against itself is
     * counted once. A game without a winner (the app broke off) counts for nothing.
     */
    fun of(deck: DeckKey, games: List<PlayedGame>): List<Matchup> {
        val byOpponent = linkedMapOf<DeckKey, Pair<Tally, Tally>>()
        fun key(k: DeckKey) = byOpponent.keys.firstOrNull { it.sameAs(k) } ?: k
        for (g in games) {
            val winner = g.winner ?: continue
            val (opponent, won) = when {
                g.deck.sameAs(deck) -> g.opponent to when (winner) { Winner.ME -> true; Winner.OPPONENT -> false; Winner.DRAW -> null }
                g.opponent.sameAs(deck) -> g.deck to when (winner) { Winner.ME -> false; Winner.OPPONENT -> true; Winner.DRAW -> null }
                else -> continue
            }
            val k = key(opponent)
            val (played, simulated) = byOpponent[k] ?: (Tally() to Tally())
            byOpponent[k] = if (g.mode != GameMode.AI_VS_AI) played.add(won, g.turns) to simulated else played to simulated.add(won, g.turns)
        }
        return byOpponent.map { (k, t) -> Matchup(k, t.first, t.second) }.sortedByDescending { it.total.games }
    }

    /** Every deck that has a game, as seat A or B, each once. */
    fun decks(games: List<PlayedGame>): List<DeckKey> {
        val out = mutableListOf<DeckKey>()
        for (g in games) for (k in listOf(g.deck, g.opponent)) if (out.none { it.sameAs(k) }) out += k
        return out
    }
}
