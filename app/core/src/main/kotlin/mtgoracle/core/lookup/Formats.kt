package mtgoracle.core.lookup

/*
 * "What is this format string", as queries.py answers it: Scryfall's
 * legality keys, the names people type for them, and the community formats
 * the database defines (custom_formats), which inherit another format's pool.
 */

/** A `custom_formats` row. [aliases] are as written; they are folded on lookup. */
data class CustomFormat(
    val key: String,
    val name: String,
    val aliases: List<String>,
    /** The Scryfall format whose legality this one inherits (`canadianhighlander` -> `vintage`). */
    val derivesFrom: String?,
    val pointsBudget: Int?,
    val singleton: Boolean,
)

/** Everything a caller needs to know about a format name (queries.resolve_format). */
data class FormatInfo(
    val key: String,
    val label: String,
    /** The `card_legalities.format` to check against, or null. */
    val legalityKey: String?,
    val pointsBudget: Int?,
    val singleton: Boolean,
    val custom: Boolean,
)

object Formats {
    /** Scryfall's legality keys: every value `card_legalities.format` can hold. */
    val LEGALITY: Set<String> = sortedSetOf(
        "alchemy", "brawl", "commander", "competitivebrawl", "duel", "future",
        "gladiator", "historic", "legacy", "modern", "oathbreaker", "oldschool",
        "pauper", "paupercommander", "penny", "pioneer", "predh", "premodern",
        "standard", "standardbrawl", "timeless", "tlr", "vintage",
    )

    /** What people type -> Scryfall's key, after [fold] has removed spaces, hyphens and underscores. */
    private val ALIAS = mapOf(
        "edh" to "commander",
        "duelcommander" to "duel", // Scryfall's `duel` is Duel Commander
        "1v1commander" to "duel",
        "historicbrawl" to "brawl", // Arena renamed Historic Brawl to Brawl
        "pdh" to "paupercommander",
        "pennydreadful" to "penny",
        "cbrawl" to "competitivebrawl",
        "tinyleaders" to "tlr", // `tlr` is Tiny Leaders: Reborn
        "tinyleadersreborn" to "tlr",
    )

    /** In these, `restricted` means "not as your commander", not "one copy". */
    val RESTRICTED_MEANS_NO_COMMANDER: Set<String> = setOf("duel", "tlr")

    val SINGLETON_LEGALITY: Set<String> = setOf(
        "commander", "duel", "oathbreaker", "brawl", "standardbrawl", "competitivebrawl",
        "gladiator", "paupercommander", "predh", "tlr",
    )

    private val SEPARATORS = Regex("[\\s_-]+")

    /** Lower case, no spaces / hyphens / underscores, aliases resolved. */
    fun fold(raw: String): String {
        val folded = SEPARATORS.replace(raw.trim().lowercase(), "")
        return ALIAS[folded] ?: folded
    }
}

/**
 * The formats this database knows: Scryfall's plus [custom]. Built once from
 * `custom_formats`, which only a sync changes.
 */
class FormatCatalog(custom: List<CustomFormat>) {
    /** By key, then by every folded alias (a key wins over another format's alias). */
    private val byName: Map<String, CustomFormat> = buildMap {
        custom.forEach { put(it.key, it) }
        custom.forEach { f -> f.aliases.forEach { putIfAbsent(Formats.fold(it), f) } }
    }

    fun resolve(raw: String?): FormatInfo? {
        if (raw.isNullOrBlank()) return null
        val key = Formats.fold(raw)
        if (key in Formats.LEGALITY) return FormatInfo(key, key, key, null, key in Formats.SINGLETON_LEGALITY, custom = false)
        val spec = byName[key] ?: return null
        return FormatInfo(spec.key, spec.name, spec.derivesFrom, spec.pointsBudget, spec.singleton, custom = true)
    }

    /**
     * The legality key for the search language, strictly: a typo is an
     * error, never an empty result that reads as "nothing is legal".
     * (queries.normalize_format)
     */
    fun legalityKey(raw: String): String {
        val key = Formats.fold(raw)
        if (key in Formats.LEGALITY) return key
        byName[key]?.derivesFrom?.takeIf { it in Formats.LEGALITY }?.let { return it }
        val valid = (Formats.LEGALITY + byName.keys).sorted()
        throw SearchError("unknown format: '$raw'" + (closest(key, valid)?.let { " — did you mean $it?" } ?: "") + ". Valid: ${valid.joinToString(", ")}")
    }
}
