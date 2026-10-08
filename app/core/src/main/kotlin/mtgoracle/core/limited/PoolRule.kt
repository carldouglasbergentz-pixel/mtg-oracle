package mtgoracle.core.limited

/**
 * A deck built from a pool holds no more of a card than was opened. Basic
 * lands are free, as at any limited table. One rule for the deck workspace,
 * for playing the deck and for a host judging a guest's deck.
 */
object PoolRule {

    /** A card the deck holds more of than the pool: [held] in main and sideboard together, [opened] in the pool. */
    data class Excess(val card: String, val held: Int, val opened: Int) {
        override fun toString(): String = "$card: $held in the deck, $opened opened"
    }

    /**
     * Every card in [deck] (name to copies, main and sideboard together)
     * beyond what [pool] holds, names compared ignoring case. [isBasicLand]
     * says which names are free.
     */
    fun excess(pool: Map<String, Int>, deck: Map<String, Int>, isBasicLand: (String) -> Boolean): List<Excess> {
        val opened = pool.entries.groupingBy { it.key.lowercase() }.fold(0) { sum, e -> sum + e.value }
        return deck.entries
            .groupBy { it.key.lowercase() }
            .mapNotNull { (key, rows) ->
                val name = rows.first().key
                val held = rows.sumOf { it.value }
                val have = opened[key] ?: 0
                if (held > have && !isBasicLand(name)) Excess(name, held, have) else null
            }
            .sortedBy { it.card.lowercase() }
    }
}
