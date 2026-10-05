package mtgoracle.core.deck

/**
 * What Forge makes of a card in a deck, when it is not plain sailing: its AI
 * won't play it (Forge marks the card `RemoveDeck`), or Forge lacks the card
 * altogether. [substitute] is what the deck's AI copy plays instead, once one
 * is set: then the AI is looked after and the flag only says so.
 */
data class AiFlag(val kind: Kind, val substitute: String? = null) {
    enum class Kind { AI_WONT_PLAY, FORGE_LACKS }

    /** Still a problem for the AI: no substitute yet. */
    val open: Boolean get() = substitute == null

    /** What the flag says beside the card (after its `[!]`): `AI won't play it`, or `AI plays Opt` once substituted. */
    val label: String get() = substitute?.let { "AI plays $it" } ?: when (kind) {
        Kind.AI_WONT_PLAY -> "AI won't play it"
        Kind.FORGE_LACKS -> "not in Forge"
    }

    /** The button beside the card that opens its AI substitute: `[!]` while the AI has none, `[→]` once it has. */
    val button: String get() = if (open) "[!]" else "[→]"

    /** What the zoom pane says of it, and where to fix it. */
    val explanation: String get() = when (kind) {
        Kind.AI_WONT_PLAY -> "Forge's AI won't play this card: it can't judge when to."
        Kind.FORGE_LACKS -> "Forge doesn't have this card, so it can't be played in a game."
    } + (substitute?.let { " The AI copy plays $it instead; [→] changes it." } ?: " [!] beside it gives the AI a card it plays instead.")

    companion object {
        /**
         * The deck's flagged cards, by name: [answer] is Forge's word on a name, null for a card it plays
         * (null when Forge isn't up yet: nothing is flagged rather than everything).
         */
        fun of(deck: Deck, answer: (String) -> Kind?): Map<String, AiFlag> {
            val subs = deck.substitutions.associate { it.cardName.lowercase() to it.substitute }
            return deck.cards.map { it.name }.distinct().mapNotNull { name -> answer(name)?.let { name to AiFlag(it, subs[name.lowercase()]) } }.toMap()
        }
    }
}
