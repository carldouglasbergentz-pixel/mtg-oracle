package mtgoracle.core.analysis

import mtgoracle.core.deck.DeckSize

/**
 * What the analysis block above a deck shows, computed once per change:
 * the mana base (curve, pips, sources), what the cards do (primary roles and
 * when each is castable), how many combos the deck holds whole, and the
 * cards the classifier could not place.
 */
data class DeckInsight(
    val deckId: Int,
    val analytics: DeckAnalytics,
    val profile: DeckProfile,
    val combos: Int,
    val unsure: List<String>,
    /** Every card's count, the names the database lacks included: the size the library and the deck say. */
    val size: DeckSize,
)
