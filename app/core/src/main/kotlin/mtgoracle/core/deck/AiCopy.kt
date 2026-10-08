package mtgoracle.core.deck

import kotlinx.serialization.Serializable

/*
 * The deck Forge plays, and the AI's copy of it — the same rules as the
 * retired Python `forge export` (services._export_deck, forge_format):
 *
 * - Forge knows a multi-face card by its front face, split cards included
 *   (`Fire // Ice` is `Fire`), so every name is reduced to that.
 * - The AI copy applies the deck's `forge_substitutions`, matched case-
 *   insensitively. A substitution for a card no longer in the deck is
 *   skipped with a note.
 * - Two rows that land on one Forge name in the same section (a substitute
 *   that is already in the deck, typically a basic) become one line with the
 *   summed quantity. A substituted row loses its printing: the printing was
 *   chosen for the original card.
 */

/** The name Forge knows a card by: its front face. */
fun forgeCardName(name: String): String = name.split(" // ", limit = 2)[0].trim()

@Serializable
data class PlayCard(
    val forgeName: String,
    val quantity: Int,
    val section: Section,
    val setCode: String?,
    val collectorNumber: String?,
)

@Serializable
data class PlayDeck(
    val deckId: Int,
    /** What the game calls it: the deck's name, with " (AI)" for the AI copy. */
    val name: String,
    val gameType: GameType,
    val cards: List<PlayCard>,
    val isAiCopy: Boolean,
    /** (card, substitute) actually applied. */
    val applied: List<Substitution>,
    val notes: List<String>,
) {
    /** The deck's row in `decks`, or null for one with none (the AI's sealed deck): what a game is recorded against. */
    val storedId: Int? get() = deckId.takeIf { it != UNSTORED }

    companion object {
        /** The [deckId] of a deck with no row in `decks`: the one the AI builds from its sealed pool. Recorded with no deck id. */
        const val UNSTORED = 0
    }
}

object AiCopy {

    /** The deck as the user built it, in Forge's naming. */
    fun asBuilt(deck: Deck): PlayDeck =
        PlayDeck(deck.id, deck.name, deck.gameType, merge(deck.cards), isAiCopy = false, applied = emptyList(), notes = emptyList())

    /**
     * The AI's copy: [Deck.substitutions] applied. Null when the deck has no
     * substitution that applies — then the AI plays the deck as built, just as
     * `forge export` writes no `(AI).dck` without substitutions.
     */
    fun aiCopy(deck: Deck): PlayDeck? {
        val inDeck = deck.cards.map { it.name.lowercase() }.toSet()
        val (live, stale) = deck.substitutions.partition { it.cardName.lowercase() in inDeck }
        if (live.isEmpty()) return null
        val byCard = live.associateBy { it.cardName.lowercase() }
        val swapped = deck.cards.map { card ->
            byCard[card.name.lowercase()]?.let { card.copy(name = it.substitute, setCode = null, collectorNumber = null) } ?: card
        }
        val notes = stale.map { "substitution for ${it.cardName} skipped: the card is no longer in the deck" }
        return PlayDeck(deck.id, "${deck.name} (AI)", deck.gameType, merge(swapped), isAiCopy = true, applied = live, notes = notes)
    }

    /** One line per (section, Forge name, printing), quantities summed, sorted like a .dck. */
    fun merge(cards: List<DeckCard>): List<PlayCard> {
        val merged = LinkedHashMap<Triple<Section, String, Pair<String?, String?>>, Int>()
        for (card in cards) {
            val key = Triple(card.section, forgeCardName(card.name), card.setCode to card.collectorNumber)
            merged[key] = (merged[key] ?: 0) + card.quantity
        }
        return merged.entries
            .map { (key, qty) -> PlayCard(key.second, qty, key.first, key.third.first, key.third.second) }
            .sortedWith(compareBy<PlayCard>({ it.section.ordinal }, { it.forgeName.lowercase() }))
    }
}
