package mtgoracle.data

import mtgoracle.core.lookup.NameFold

/**
 * Every card name, read once, and the tolerant lookup over them — what
 * queries.resolve_card_name does, in memory. Tried in order:
 *
 *  1. the name, ignoring case;
 *  2. `/`, `//` or ` // ` between faces, as ` // ` (the first separator found);
 *  3. the front face alone: `Delver of Secrets` -> `... // Insectile Aberration`
 *     (the shortest such name);
 *  4. folded (NameFold): diacritics, ligatures and quotes dropped, on the
 *     whole name or on its front face; the first card in table order wins.
 *
 * In memory because step 4 has to fold every name: Python scans 35k rows per
 * miss. The names change only when a sync runs, and the app reads them at start.
 */
class CardNames(names: List<String>) {
    /** Sorted as the TUI's suggester sorts them (NOCASE), for completion. */
    val sorted: List<String> = names.sortedWith(String.CASE_INSENSITIVE_ORDER)

    private val byLower: Map<String, String> = buildMap { names.forEach { putIfAbsent(it.lowercase(), it) } }
    /** Folded whole name or folded front face -> the first card, in table order, that has it. */
    private val byFold: Map<String, String> = buildMap {
        for (name in names) {
            putIfAbsent(NameFold.fold(name), name)
            if (" // " in name) putIfAbsent(NameFold.fold(name.substringBefore(" // ")), name)
        }
    }
    private val byFront: Map<String, String> = buildMap {
        names.filter { " // " in it }.sortedBy { it.length }.forEach { putIfAbsent(it.substringBefore(" // ").lowercase(), it) }
    }

    operator fun contains(name: String): Boolean = name.lowercase() in byLower

    /** The canonical name for loosely typed [raw], or null. */
    fun resolve(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        byLower[name.lowercase()]?.let { return it }
        SEPARATORS.firstOrNull { it in name }?.let { sep ->
            byLower[name.split(sep).joinToString(" // ") { it.trim() }.lowercase()]?.let { return it }
        }
        byFront[name.lowercase()]?.let { return it }
        return byFold[NameFold.fold(name)]
    }

    private companion object {
        val SEPARATORS = listOf(" // ", "//", "/")
    }
}
