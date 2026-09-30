package mtgoracle.core.deck

import mtgoracle.core.analysis.Roles

/**
 * A deck as a `N Card Name` list for a deck site (services.export_deck_text,
 * held to it by DeckParityTest): sections under the headers the parser reads
 * back, names sorted, and each row's printing as ` (SET) number` so export ->
 * import keeps the art. The considering list stays out: it is not the deck.
 */
object DeckExport {
    private val ORDER = listOf("commander" to "Commander", "main" to "Deck", "sideboard" to "Sideboard")

    /**
     * [frontFace] shortens two-faced names to the front face, except split
     * cards, whose front is no card (`Fire` is not one; `Fire // Ice` is).
     * [layoutOf] tells a split card from the others. With [primaryOf] (a
     * card's primary role, null for one the database lacks), each section is
     * grouped under `// Counterspells (15)` comments, which importers skip.
     */
    fun text(
        deck: Deck,
        frontFace: Boolean = false,
        headers: Boolean = true,
        layoutOf: (String) -> String? = { null },
        primaryOf: ((String) -> String?)? = null,
    ): String {
        val buckets = ORDER.associate { it.first to LinkedHashMap<String, Int>() }
        val printings = HashMap<Pair<String, String>, String>()
        for (row in deck.cards) {
            // The sideboard wins, as in the history: a sideboard row flagged commander is not in the command zone.
            val section = when {
                row.isSideboard -> "sideboard"
                row.isCommander -> "commander"
                else -> "main"
            }
            val entries = buckets.getValue(section)
            entries[row.name] = (entries[row.name] ?: 0) + row.quantity
            val key = section to row.name
            if (row.setCode != null && key !in printings) {
                printings[key] = " (${row.setCode.uppercase()})" + (row.collectorNumber?.let { " $it" } ?: "")
            }
        }
        fun display(name: String): String =
            if (!frontFace || " // " !in name || layoutOf(name) == "split") name else name.substringBefore(" // ")
        val lines = mutableListOf<String>()
        for ((key, header) in ORDER) {
            val entries = buckets.getValue(key)
            if (entries.isEmpty()) continue
            if (headers) {
                if (lines.isNotEmpty()) lines += ""
                lines += header
            }
            fun row(n: String) = "${entries.getValue(n)} ${display(n)}${printings[key to n].orEmpty()}"
            if (primaryOf == null) {
                entries.keys.sortedWith(CODE_POINT_ORDER).forEach { lines += row(it) }
                continue
            }
            val byRole = entries.keys.groupBy { primaryOf(it) ?: "utility" }
            for (role in Roles.ROLES) {
                val names = byRole[role]?.sortedWith(CODE_POINT_ORDER) ?: continue
                lines += "// ${Roles.LABELS.getValue(role)} (${names.sumOf { entries.getValue(it) }})"
                names.forEach { lines += row(it) }
            }
        }
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    /** Python's `sorted()` over str: by code point, not by UTF-16 unit. */
    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return@Comparator x[i].compareTo(y[i])
        x.size.compareTo(y.size)
    }
}
