package mtgoracle.core.lookup

/*
 * What the lookup commands answer with, as the data layer reads it. Plain
 * data: the renderers take these and nothing else.
 */

data class Ruling(val date: String, val text: String)

/** A `card_abilities` row: the oracle text parsed into one ability. */
data class Ability(
    val type: String,
    val cost: String?,
    val effect: String?,
    val hasTarget: Boolean,
    val producesMana: Boolean,
    /** CR 605.1a/b: produces mana, no target, not loyalty. */
    val isManaAbility: Boolean,
)

/** One combo in a list: a Spellbook id (`1234-5678`) or a user combo (`user-3`). */
data class ComboSummary(
    val id: String,
    /** Contiguous letters (`WBG`); Spellbook spells colourless `C`. Null when unknown. */
    val colorIdentity: String?,
    val name: String?,
    val cardCount: Int,
    /** The cards joined with " + ". */
    val cards: String?,
    val source: String,
    /** The steps name a card slot the card list doesn't (`your commander`): it needs more cards than listed. */
    val hasTemplateVars: Boolean,
)

data class ComboCard(val name: String, val quantity: Int)

data class ComboDetail(
    val id: String,
    val name: String?,
    val colorIdentity: String?,
    val description: String?,
    val source: String,
    val cards: List<ComboCard>,
    val prerequisites: List<String>,
    val steps: List<String>,
    val results: List<String>,
)

/** A Comprehensive Rules entry; [children] are its immediate sub-rules in natural order. */
data class Rule(
    val number: String,
    val sectionTitle: String?,
    val text: String,
    val children: List<Rule> = emptyList(),
)

/** A `corrections` row: a factual mistake made once, and what is right. */
data class Correction(
    val id: Int,
    val topic: String,
    val category: String?,
    val incorrectClaim: String?,
    val correctClaim: String,
    val explanation: String?,
    /** The card names it is about (a JSON array in the table; a free-text row gives one element). */
    val relatesTo: List<String>,
    val source: String?,
)

/** Everything `card <name>` shows. */
data class CardProfile(
    val name: String,
    val manaCost: String?,
    val typeLine: String?,
    val oracleText: String?,
    val games: String?,
    val reserved: Boolean,
    val edhrecRank: Int?,
    /** category -> tags, sorted: `keyword` -> [flying, ward]. */
    val tags: Map<String, List<String>>,
    val abilities: List<Ability>,
    val rulings: List<Ruling>,
    /** status -> formats: legal, restricted, no_commander (see Formats.RESTRICTED_MEANS_NO_COMMANDER), banned. */
    val legalities: Map<String, List<String>>,
    /** The ten smallest combos with this card. */
    val combos: List<ComboSummary>,
    /** The deck CI the combos were filtered to (`BG`, `C`), or null when unfiltered. */
    val combosFilteredByCi: String?,
    val corrections: List<Correction>,
)

data class SearchRow(val name: String, val typeLine: String?, val manaCost: String?)

/**
 * One page of a search. [query] is what ran, deck filters included; paging
 * re-runs it, so walking out of the deck mid-paging can't change the result.
 */
data class SearchPage(
    val query: SearchQuery,
    val rows: List<SearchRow>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    /** Labels for the filters a deck added (`ci<=BG`, `f:commander`): announced, never silent. */
    val filters: List<String> = emptyList(),
) {
    val lastPage: Int get() = maxOf(1, (total + pageSize - 1) / pageSize)
    val hasNext: Boolean get() = page < lastPage
    val hasPrev: Boolean get() = page > 1
}
