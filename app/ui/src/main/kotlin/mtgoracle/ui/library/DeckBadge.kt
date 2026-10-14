package mtgoracle.ui.library

import mtgoracle.core.deck.Deck
import mtgoracle.core.lookup.Formats

/** What the database adds to a deck's title: its commander's colour identity (`{U}{W}`) and its points spent of its budget (`9/10 pts`). */
data class DeckBadges(val identity: String? = null, val points: String? = null)

/**
 * The deck pane's title badge, the same parts in the same order in the
 * library and the workspace, the most telling first, since a narrow title
 * drops parts from the end: `EDH · 99 + commander · {U}{W}`, `CHL · 100 cards ·
 * 9/10 pts`, then a limited deck's [pool] and, with [aiCopy], the AI copy's
 * substitutions: the workspace leaves those to its AI copy tab, which counts
 * them, and keeps its narrow title for the deck's name.
 */
fun deckBadge(deck: Deck, badges: DeckBadges, pool: String? = null, aiCopy: Boolean = true): String = listOfNotNull(
    deck.format?.let(Formats::shortName),
    deck.size.label,
    badges.points,
    badges.identity,
    pool,
    deck.substitutions.size.takeIf { it > 0 && aiCopy }?.let { "AI copy: $it substitution${if (it == 1) "" else "s"}" },
).joinToString(" · ")
