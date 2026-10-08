package mtgoracle.core.lookup

/** A card's kinds in the order a deck is read, its front face's type line deciding: a Sorcery // Land is a sorcery. */
object CardTypes {
    val ORDER = listOf("Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment", "Land")

    fun primary(typeLine: String?): String {
        val front = typeLine?.split(" // ")?.first().orEmpty()
        return ORDER.firstOrNull { front.contains(it) } ?: "Other"
    }
}

/** One way to lay out a limited deck's pool: by colour, by type, by mana value. */
enum class PoolLayer(val word: String) {
    COLOUR("colour"), TYPE("type"), MV("mv");

    /** Where [row] falls in this layer (lower first), and the group it names. */
    fun rank(row: SearchRow): Int = when (this) {
        COLOUR -> colours(row).let { c -> if (c.isEmpty()) COLOURLESS else if (c.size > 1) MULTICOLOUR else WUBRG.indexOf(c.single()) }
        TYPE -> CardTypes.ORDER.indexOf(CardTypes.primary(row.typeLine)).let { if (it < 0) CardTypes.ORDER.size else it }
        MV -> row.manaValue?.toInt() ?: Int.MAX_VALUE
    }

    fun label(row: SearchRow): String = when (this) {
        COLOUR -> colours(row).let { c -> if (c.isEmpty()) "Colourless" else if (c.size > 1) "Multicolour" else NAMES.getValue(c.single()) }
        TYPE -> CardTypes.primary(row.typeLine)
        MV -> row.manaValue?.let { "mana value ${it.toInt()}" } ?: "no mana value"
    }

    companion object {
        private val WUBRG = listOf("W", "U", "B", "R", "G")
        private val NAMES = mapOf("W" to "White", "U" to "Blue", "B" to "Black", "R" to "Red", "G" to "Green")
        private const val MULTICOLOUR = 5
        private const val COLOURLESS = 6

        private fun colours(row: SearchRow): List<String> = row.colors.orEmpty().split(",").filter { it.isNotBlank() }

        /** `colour` (or `color`), `type`, `mv`. */
        fun of(word: String): PoolLayer? = entries.firstOrNull { it.word == word.lowercase() } ?: COLOUR.takeIf { word.lowercase() == "color" }
    }
}

/**
 * How the search pane lays out a limited deck's pool: three [slots], each a
 * layer or none, sorted by them in turn and then by name; the first layer
 * names the groups. Colour, then type, then mana value, by default.
 */
data class PoolSort(val slots: List<PoolLayer?>) {
    init { require(slots.size == SLOTS && slots.filterNotNull().let { it.size == it.toSet().size }) { "three slots, no layer twice: $slots" } }

    val layers: List<PoolLayer> get() = slots.filterNotNull()

    /** The command that sets this layout: `sort colour type mv`, `-` for an empty slot. */
    val command: String get() = "sort " + slots.joinToString(" ") { it?.word ?: "-" }

    /** [slot] changed to the next layer no other slot holds, and to none after the last of them. */
    fun cycled(slot: Int): PoolSort {
        val taken = slots.filterIndexed { i, _ -> i != slot }.filterNotNull().toSet()
        val options: List<PoolLayer?> = PoolLayer.entries.filter { it !in taken } + null
        val next = options[(options.indexOf(slots[slot]) + 1) % options.size]
        return PoolSort(slots.toMutableList().also { it[slot] = next })
    }

    /** [rows] in this layout, and its groups in order (label, how many), named by the first layer; one group when there is none. */
    fun arrange(rows: List<SearchRow>): Pair<List<SearchRow>, List<Pair<String, Int>>> {
        val sorted = rows.sortedWith(layers.fold(Comparator<SearchRow> { _, _ -> 0 }) { c, layer -> c.thenBy { layer.rank(it) } }.thenBy { it.name.lowercase() })
        val first = layers.firstOrNull() ?: return sorted to listOf("the pool" to sorted.size)
        val groups = mutableListOf<Pair<String, Int>>()
        for (row in sorted) {
            val label = first.label(row)
            if (groups.lastOrNull()?.first == label) groups[groups.lastIndex] = label to groups.last().second + 1 else groups += label to 1
        }
        return sorted to groups
    }

    companion object {
        const val SLOTS = 3
        val DEFAULT = PoolSort(listOf(PoolLayer.COLOUR, PoolLayer.TYPE, PoolLayer.MV))

        /** `colour type mv`, one to three layers or `-`, none twice; null when it isn't one. */
        fun parse(words: List<String>): PoolSort? {
            if (words.isEmpty() || words.size > SLOTS) return null
            val slots = words.map { w -> if (w == "-") null else PoolLayer.of(w) ?: return null }
            if (slots.filterNotNull().let { it.size != it.toSet().size }) return null
            return PoolSort(slots + List(SLOTS - slots.size) { null })
        }
    }
}

/** A limited deck's pool as the search pane lays it out: the sort, and the groups in order (label, how many). */
data class PoolArrangement(val sort: PoolSort, val groups: List<Pair<String, Int>>)
