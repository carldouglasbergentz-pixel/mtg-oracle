package mtgoracle.core.model

import kotlinx.serialization.Serializable

/** What a play-by-play line tells: the log pane colours a line by it. */
enum class LogKind { TURN, PHASE, MANA, CAST, RESOLVE, LAND, DRAW, COMBAT, DAMAGE, LIFE_LOST, LIFE_GAINED, DISCARD, REVEAL, COUNTERED, ZONE, OUTCOME, OTHER }

/**
 * A card a log line names, at `[start, end)` of its text. [id] is the card
 * when the log said which one (Forge's `Swamp (159)`), and [card] the card
 * as printed, for the zoom pane once it has left the table; null when Forge
 * lacks it (a token).
 */
@Serializable
data class LogCard(val start: Int, val end: Int, val name: String, val id: Int? = null, val card: CardState? = null)

/**
 * One line of the play-by-play, Forge's game log or one of ours. [seq]
 * counts from the match's first line, so a line keeps its place while the log
 * grows. [seenBy] is null for a public line, which every viewer reads; else
 * the players it was shown to (a card revealed to you, which Forge can't tell
 * from one you only looked at), and only their seats get it.
 */
@Serializable
data class LogLine(val seq: Long, val kind: LogKind, val text: String, val cards: List<LogCard> = emptyList(), val seenBy: Set<Int>? = null) {
    /** Whether a seat of [players] may read it. */
    fun readableBy(players: Set<Int>): Boolean = seenBy == null || seenBy.any { it in players }
}
