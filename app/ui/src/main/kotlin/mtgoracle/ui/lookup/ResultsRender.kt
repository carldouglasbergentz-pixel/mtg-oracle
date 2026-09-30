package mtgoracle.ui.lookup

import mtgoracle.core.analysis.Py
import mtgoracle.core.analysis.left
import mtgoracle.core.analysis.right
import mtgoracle.core.play.Matchup
import mtgoracle.core.play.Tally

/**
 * `results`: each deck's record against each opponent, the games you played
 * apart from the ones the AI played (simulated or watched), wins–losses–draws
 * from that deck's side.
 */
fun renderResults(records: List<Pair<String, List<Matchup>>>): Rendering = Rendering { width ->
    val out = mutableListOf<OutLine>()
    fun add(text: String = "", tone: Tone = Tone.PLAIN) { out += OutLine(text.trimEnd(), tone) }
    if (records.all { it.second.isEmpty() }) {
        wrapWords("(no games recorded yet: play one, or simulate from the setup screen, P then S)", width).forEach { add(it, Tone.DIM) }
        return@Rendering out
    }
    val numbers = 11 + 11 + 11
    val nameWidth = (records.flatMap { (_, m) -> m.map { it.opponent.name.length } } + "opponent".length).max().coerceAtMost(maxOf(12, width - numbers))
    fun cell(t: Tally) = (if (t.games == 0) "-" else t.score()).right(11)
    for ((deck, matchups) in records) {
        if (matchups.isEmpty()) continue
        if (out.isNotEmpty()) add()
        add(deck, Tone.BOLD)
        add("opponent".left(nameWidth) + "you play".right(11) + "AI plays".right(11) + "avg turns".right(11))
        for (m in matchups) {
            val name = if (m.opponent.name.length > nameWidth) m.opponent.name.take(nameWidth - 1) + "…" else m.opponent.name
            add(name.left(nameWidth) + cell(m.played) + cell(m.simulated) + (m.total.averageTurns?.let { Py.fixed(it, 1) } ?: "-").right(11))
        }
        if (matchups.size > 1) {
            val played = matchups.fold(Tally()) { a, m -> Tally(a.wins + m.played.wins, a.losses + m.played.losses, a.draws + m.played.draws) }
            val simulated = matchups.fold(Tally()) { a, m -> Tally(a.wins + m.simulated.wins, a.losses + m.simulated.losses, a.draws + m.simulated.draws) }
            add("total".left(nameWidth) + cell(played) + cell(simulated))
        }
    }
    wrapWords("You play: your games against the AI. AI plays: AI against AI, simulated or watched. " +
        "Wins–losses–draws from the named deck's side; its AI copy counts as the deck, and a game stopped at the time limit is a draw.",
        width, "  ", "    ").forEach { add(it, Tone.DIM) }
    out
}
