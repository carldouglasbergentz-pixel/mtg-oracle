package mtgoracle.core.lookup

/**
 * The search language's field names, shared by the compiler (data/SearchSql)
 * and autofill (ui): one list, so a new alias completes the day it compiles.
 */
object SearchFields {
    /** Every name a field is written by -> the field it is. */
    val ALIAS: Map<String, String> = mapOf(
        "oracle" to "o", "o" to "o",
        "type" to "t", "t" to "t",
        "name" to "n", "n" to "n",
        "keyword" to "kw", "kw" to "kw",
        "color" to "c", "c" to "c",
        "ci" to "ci", "coloridentity" to "ci", "color_identity" to "ci", "id" to "ci",
        "mv" to "mv", "cmc" to "mv",
        "pow" to "pow", "power" to "pow",
        "tou" to "tou", "toughness" to "tou",
        "rarity" to "r", "r" to "r",
        "layout" to "layout",
        "f" to "f", "format" to "f", "legal" to "f",
        "banned" to "banned",
        "restricted" to "restricted",
        "game" to "game",
        "is" to "is",
        "m" to "m", "mana" to "m",
        "otag" to "otag", "function" to "otag", "oracletag" to "otag",
        // What is left of a limited deck's pool: the cards in its sideboard. The workspace adds it, by the deck's id.
        "pool" to "pool",
        // The cards most like a card (data/LikeIndex.kt), likest first unless the search orders them.
        "like" to "like", "alike" to "like", "similar" to "like",
    )

    /** `is:` flags. data/SearchSql defines what each means; SearchSqlTest holds the two lists equal. */
    val IS_FLAGS: List<String> = listOf("commander", "dfc", "historic", "mdfc", "permanent", "reserved", "spell", "split")

    val GAMES: List<String> = listOf("paper", "arena", "mtgo", "astral", "sega")

    /** What `order:` sorts by, as written after `asc_` / `desc_`. */
    val SORT_FIELDS: List<String> = listOf("mv", "name", "edhrec", "power", "toughness", "rarity", "color", "ci")
}

/**
 * The values autofill offers after a field (`t:` types, `kw:` keywords,
 * `otag:` Tagger tags...), keyed by the field they complete (SearchFields'
 * canonical names), the most used first. Read from the database once.
 */
data class SearchVocabulary(val values: Map<String, List<String>> = emptyMap()) {
    operator fun get(field: String): List<String> = values[field].orEmpty()
}
