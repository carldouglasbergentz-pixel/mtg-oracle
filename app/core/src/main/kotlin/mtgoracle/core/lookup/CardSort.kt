package mtgoracle.core.lookup

/** A card's kinds in the order a deck is read, its front face's type line deciding: a Sorcery // Land is a sorcery. */
object CardTypes {
    val ORDER = listOf("Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment", "Land")

    fun primary(typeLine: String?): String {
        val front = typeLine?.split(" // ")?.first().orEmpty()
        return ORDER.firstOrNull { front.contains(it) } ?: "Other"
    }
}

/**
 * One way to lay out cards: by colour, by type, by mana value. A search
 * ranks them in SQL the same way (`data/SearchSql.sortLayers`), page by page.
 */
enum class SortLayer(val word: String) {
    COLOUR("colour"), TYPE("type"), MV("mv");

    /** Where [row] falls in this layer (lower first). */
    fun rank(row: SearchRow): Int = when (this) {
        COLOUR -> colours(row).let { c -> if (c.isEmpty()) COLOURLESS else if (c.size > 1) MULTICOLOUR else WUBRG.indexOf(c.single()) }
        TYPE -> CardTypes.ORDER.indexOf(CardTypes.primary(row.typeLine)).let { if (it < 0) CardTypes.ORDER.size else it }
        MV -> row.manaValue?.toInt() ?: NO_MV
    }

    /** The group [row] falls in. */
    fun label(row: SearchRow): String = labelOf(rank(row))

    /** The group a [rank] names, whether a row's or one the database counted. */
    fun labelOf(rank: Int): String = when (this) {
        COLOUR -> when (rank) {
            COLOURLESS -> "Colourless"
            MULTICOLOUR -> "Multicolour"
            else -> NAMES.getOrElse(rank) { "Colourless" }
        }
        TYPE -> CardTypes.ORDER.getOrElse(rank) { "Other" }
        MV -> if (rank == NO_MV) "no mana value" else "mana value $rank"
    }

    companion object {
        val WUBRG = listOf("W", "U", "B", "R", "G")
        private val NAMES = listOf("White", "Blue", "Black", "Red", "Green")
        const val MULTICOLOUR = 5
        const val COLOURLESS = 6
        /** A card with no mana value comes last. */
        const val NO_MV = Int.MAX_VALUE

        private fun colours(row: SearchRow): List<String> = row.colors.orEmpty().split(",").filter { it.isNotBlank() }

        /** `colour` (or `color`), `type`, `mv`. */
        fun of(word: String): SortLayer? = entries.firstOrNull { it.word == word.lowercase() } ?: COLOUR.takeIf { word.lowercase() == "color" }
    }
}

/**
 * How the search pane lays out cards, a limited deck's pool and a search
 * each with its own: three [slots], each a layer or none, sorted by them in
 * turn and then by name; the first layer names the groups. Colour, then
 * type, then mana value, by default.
 */
data class CardSort(val slots: List<SortLayer?>) {
    init { require(slots.size == SLOTS && slots.filterNotNull().let { it.size == it.toSet().size }) { "three slots, no layer twice: $slots" } }

    val layers: List<SortLayer> get() = slots.filterNotNull()

    /** The command that sets this layout: `sort colour type mv`, `-` for an empty slot. */
    val command: String get() = "sort " + slots.joinToString(" ") { it?.word ?: "-" }

    /** [slot] changed to the next layer no other slot holds, and to none after the last of them. */
    fun cycled(slot: Int): CardSort {
        val taken = slots.filterIndexed { i, _ -> i != slot }.filterNotNull().toSet()
        val options: List<SortLayer?> = SortLayer.entries.filter { it !in taken } + null
        val next = options[(options.indexOf(slots[slot]) + 1) % options.size]
        return CardSort(slots.toMutableList().also { it[slot] = next })
    }

    /** [rows] in this layout, and its groups in order (label, how many), named by the first layer; one group, [whole], when there is none. */
    fun arrange(rows: List<SearchRow>, whole: String = "the pool"): Pair<List<SearchRow>, List<Pair<String, Int>>> {
        val sorted = rows.sortedWith(layers.fold(Comparator<SearchRow> { _, _ -> 0 }) { c, layer -> c.thenBy { layer.rank(it) } }.thenBy { it.name.lowercase() })
        return sorted to groups(sorted, whole)
    }

    /** The groups of [sorted], rows already in this layout (a search page, sorted by the database), as [arrange] names them. */
    fun groups(sorted: List<SearchRow>, whole: String = "the pool"): List<Pair<String, Int>> {
        val first = layers.firstOrNull() ?: return listOf(whole to sorted.size)
        val groups = mutableListOf<Pair<String, Int>>()
        for (row in sorted) {
            val label = first.label(row)
            if (groups.lastOrNull()?.first == label) groups[groups.lastIndex] = label to groups.last().second + 1 else groups += label to 1
        }
        return groups
    }

    companion object {
        const val SLOTS = 3
        val DEFAULT = CardSort(listOf(SortLayer.COLOUR, SortLayer.TYPE, SortLayer.MV))

        /** `colour type mv`, one to three layers or `-`, none twice; null when it isn't one. */
        fun parse(words: List<String>): CardSort? {
            if (words.isEmpty() || words.size > SLOTS) return null
            val slots = words.map { w -> if (w == "-") null else SortLayer.of(w) ?: return null }
            if (slots.filterNotNull().let { it.size != it.toSet().size }) return null
            return CardSort(slots + List(SLOTS - slots.size) { null })
        }
    }
}

/**
 * Cards as the search pane lays them out: the sort, and the groups in order
 * (label, how many on this page), each with how many the whole search holds
 * ([totals], by label; a pool is one page, so it needs none). [pool] says
 * whose sort it is: a limited deck's pool, or any other search.
 */
data class SortArrangement(
    val sort: CardSort,
    val groups: List<Pair<String, Int>>,
    val totals: Map<String, Int> = emptyMap(),
    val pool: Boolean = true,
) {
    fun total(label: String, onPage: Int): Int = totals[label] ?: onPage
}
