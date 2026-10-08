package mtgoracle.core.lookup

/**
 * A deck as lookup sees it after `cd`: what it can play. Both restrictions
 * are hard, and both are announced (services.deck_search_scope).
 */
data class DeckScope(
    val deckId: Int,
    val deckName: String,
    /** The union of the commanders' identities, letters sorted (`B`, `G`); empty = colourless; null = no commander. */
    val commanderCi: List<String>?,
    /** The deck's own format, resolved; null for none or one nothing defines. */
    val format: FormatInfo?,
    /** Built from a pool (limited): its search is what is left of the pool, the cards in its sideboard. */
    val fromPool: Boolean = false,
) {
    /** The deck's filters, each with the label the search announces it by. */
    val filters: List<Pair<SearchNode, String>> get() = buildList {
        commanderCi?.let { ci ->
            // An empty identity is colourless, which the language spells `c`.
            add(SearchNode.Term("ci", "<=", ci.joinToString("").ifEmpty { "c" }) to "ci<=${ci.joinToString("").ifEmpty { "C" }}")
        }
        if (fromPool) add(SearchNode.Term("pool", ":", deckId.toString()) to "the pool (what is left of it)")
        format?.legalityKey?.let { key ->
            // The deck's own format name, and the pool it inherits: "f:Canadian Highlander" alone
            // would read as legality data we have, and we have Vintage's.
            add(SearchNode.Term("f", ":", key) to "f:${format.label}" + if (format.custom) " (=$key pool)" else "")
        }
    }

    /** [query] restricted to this deck, and the labels of what was added. */
    fun restrict(query: SearchQuery): Pair<SearchQuery, List<String>> =
        filters.fold(query) { q, (node, _) -> q.and(node) } to filters.map { it.second }
}
