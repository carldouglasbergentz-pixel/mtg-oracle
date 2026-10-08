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
        "dc" to "duel", // what players call Duel Commander
        "chl" to "canadianhighlander",
        "limited" to "sealed",
    )

    /** Limited formats: a deck built from opened packs (core/limited), forty cards, any number of each. */
    val LIMITED: Set<String> = setOf("sealed")

    /** In these, `restricted` means "not as your commander", not "one copy". */
    val RESTRICTED_MEANS_NO_COMMANDER: Set<String> = setOf("duel", "tlr")

    val SINGLETON_LEGALITY: Set<String> = setOf(
        "commander", "duel", "oathbreaker", "brawl", "standardbrawl", "competitivebrawl",
        "gladiator", "paupercommander", "predh", "tlr",
    )

    /** The formats with a command zone: a deck in any other has no commander to keep. */
    val COMMANDER_FORMATS: Set<String> = setOf(
        "commander", "duel", "brawl", "standardbrawl", "competitivebrawl", "oathbreaker", "paupercommander", "predh", "tlr",
    )

    /** What people call Scryfall's keys, for menus and questions; a key without one reads as itself. */
    private val NAMES = mapOf(
        "commander" to "Commander (EDH)", "duel" to "Duel Commander (DC)", "tlr" to "Tiny Leaders: Reborn",
        "paupercommander" to "Pauper Commander (PDH)", "competitivebrawl" to "Competitive Brawl", "standardbrawl" to "Standard Brawl",
        "oldschool" to "Old School 93/94", "predh" to "PreDH", "penny" to "Penny Dreadful",
    )

    /** [key] as people say it: `duel` is Duel Commander. */
    fun displayName(key: String): String = NAMES[key] ?: key.replaceFirstChar { it.uppercase() }

    /** The short tags players write, for a folder's `[DC]` and a deck's title. */
    private val SHORT = mapOf(
        "commander" to "EDH", "duel" to "DC", "canadianhighlander" to "CHL", "canlander" to "CHL",
        "paupercommander" to "PDH", "tlr" to "TL", "oathbreaker" to "OB", "competitivebrawl" to "cBrawl",
        "standardbrawl" to "sBrawl", "brawl" to "Brawl", "standard" to "STD", "pioneer" to "PIO", "modern" to "MOD",
        "legacy" to "LEG", "vintage" to "VIN", "pauper" to "PAU", "premodern" to "PREM", "oldschool" to "93/94",
        "predh" to "PreDH", "penny" to "PD", "historic" to "HIST", "timeless" to "TIME", "alchemy" to "ALCH",
        "gladiator" to "GLAD", "future" to "FUT",
    )

    /** [raw] (a format as stored or typed) as its short tag: `duel` and `Duel Commander` are DC; one without a tag is itself. */
    fun shortName(raw: String): String = SHORT[fold(raw)] ?: raw

    /** Singleton community formats no definition file covers, by their folded name (queries.SINGLETON_COMMUNITY_FORMATS). */
    val SINGLETON_COMMUNITY: Set<String> = setOf(
        "highlander", "canadianhighlander", "canlander", "australianhighlander", "ozziehighlander", "ozhighlander", "leviathan",
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

    /**
     * [raw] as a deck or a folder stores it: a format this catalog knows by
     * its key (`canlander` and `CHL` are `canadianhighlander`, `EDH` is
     * `commander`), any other as typed, a label no rule reads; null for none.
     */
    fun canonical(raw: String?): String? {
        val typed = raw?.trim()?.ifEmpty { null } ?: return null
        return resolve(typed)?.key ?: typed
    }

    /** Whether [raw] carries a one-copy rule: its definition says so, else the community list (queries.is_singleton_format). */
    fun isSingleton(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return resolve(raw)?.singleton ?: (Formats.fold(raw) in Formats.SINGLETON_COMMUNITY)
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
