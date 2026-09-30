package mtgoracle.data

import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchNode
import mtgoracle.core.lookup.SortKey

/** A WHERE fragment over `cards c` and its parameters, in order. */
data class Sql(val text: String, val params: List<Any> = emptyList())

/**
 * The search language's syntax tree as SQL over `cards c`: what each field
 * means. Always parameterised; the only text spliced in is our own. A port
 * of scryfall_search.compile_term / _build_order_by, operator for operator.
 */
class SearchSql(private val formats: FormatCatalog) {

    fun where(node: SearchNode?): Sql = if (node == null) Sql("1=1") else compile(node)

    private fun compile(node: SearchNode): Sql = when (node) {
        is SearchNode.Term -> term(node)
        // A comparison against NULL is NULL, and NOT NULL is still NULL — so
        // `-pow>=4` dropped every non-creature. Unknown is "no match".
        is SearchNode.Not -> compile(node.expr).let { Sql("NOT COALESCE((${it.text}), 0)", it.params) }
        is SearchNode.And -> join(node.parts, " AND ")
        is SearchNode.Or -> join(node.parts, " OR ")
    }

    private fun join(parts: List<SearchNode>, op: String): Sql {
        val compiled = parts.map(::compile)
        return Sql(compiled.joinToString(op, "(", ")") { it.text }, compiled.flatMap { it.params })
    }

    private fun term(t: SearchNode.Term): Sql {
        val field = FIELD_ALIAS[t.field.lowercase()] ?: throw SearchError("unknown field: '${t.field}'")
        val op = t.op
        val value = t.value
        fun textOnly(what: String) { if (op != ":" && op != "=") throw SearchError("$what supports only ':' or '=', got '$op'") }
        return when (field) {
            "o" -> { textOnly("oracle text"); Sql("c.oracle_text LIKE ? ESCAPE '!' COLLATE NOCASE", listOf(contains(value))) }
            "t" -> { textOnly("type line"); Sql("c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE", listOf(contains(value))) }
            // `=` is "exactly" everywhere in this language (c=, ci=), so for names too;
            // `n:"Lightning Bolt"` also matches 'Emeritus of Conflict // Lightning Bolt'.
            "n" -> when (op) {
                "=" -> Sql("c.name = ? COLLATE NOCASE", listOf(value))
                ":" -> Sql("c.name LIKE ? ESCAPE '!' COLLATE NOCASE", listOf(contains(value)))
                else -> throw SearchError("name supports only ':' or '=', got '$op'")
            }
            "kw" -> {
                textOnly("kw")
                Sql("EXISTS (SELECT 1 FROM card_tags kt WHERE kt.card_name = c.name AND kt.category='keyword' AND kt.tag=?)", listOf(value.lowercase()))
            }
            "c" -> {
                val colors = parseColors(value)
                when (op) {
                    "=" -> Sql("c.colors = ?", listOf(colors.joinToString(",")))
                    ":" -> if (colors.isEmpty()) Sql("c.colors = ''")
                    else Sql(colors.joinToString(" AND ", "(", ")") { "c.colors LIKE ?" }, colors.map { "%$it%" })
                    else -> throw SearchError("color supports ':' or '=', got '$op'")
                }
            }
            "ci" -> {
                val colors = parseColors(value)
                when (op) {
                    "=" -> Sql("c.color_identity = ?", listOf(colors.joinToString(",")))
                    // Subset: the card has none of the colours the query leaves out.
                    ":", "<=" -> {
                        val excluded = ALL_COLORS.filter { it !in colors }
                        if (excluded.isEmpty()) Sql("1=1")
                        else Sql(excluded.joinToString(" AND ", "(", ")") { "(c.color_identity IS NULL OR c.color_identity NOT LIKE ?)" }, excluded.map { "%$it%" })
                    }
                    ">=" -> if (colors.isEmpty()) Sql("1=1")
                    else Sql(colors.joinToString(" AND ", "(", ")") { "c.color_identity LIKE ?" }, colors.map { "%$it%" })
                    else -> throw SearchError("color identity supports ':', '=', '<=', '>=', got '$op'")
                }
            }
            "mv" -> {
                val n = value.toIntOrNull() ?: throw SearchError("expected integer for numeric comparison, got '$value'")
                val sqlOp = NUMERIC_OPS[op] ?: throw SearchError("unsupported op '$op' on numeric field")
                Sql("c.mana_value $sqlOp ?", listOf(n))
            }
            "pow" -> powerOrToughness("power", op, value)
            "tou" -> powerOrToughness("toughness", op, value)
            "r" -> { textOnly("rarity"); Sql("c.rarity = ?", listOf(value.lowercase())) }
            "layout" -> { textOnly("layout"); Sql("c.layout = ?", listOf(value.lowercase())) }
            // Restricted cards are legal to play (one copy), so `f:vintage` includes them, as on Scryfall.
            "f" -> legality(op, value, listOf("legal", "restricted"))
            "banned" -> legality(op, value, listOf("banned"))
            "restricted" -> legality(op, value, listOf("restricted"))
            "game" -> {
                textOnly("game")
                val game = value.trim().lowercase()
                if (game !in GAMES) throw SearchError("unknown game: '$value'. Valid: ${GAMES.sorted().joinToString(", ")}")
                // games is a sorted CSV; no game's name is inside another's.
                Sql("c.games LIKE ?", listOf("%$game%"))
            }
            "is" -> {
                textOnly("is:")
                val predicate = IS_PREDICATES[value.trim().lowercase()]
                    ?: throw SearchError("unknown is: predicate '$value'. Valid: ${IS_PREDICATES.keys.sorted().joinToString(", ")}")
                Sql(predicate)
            }
            else -> throw SearchError("unknown field mapping: '$field'")
        }
    }

    private fun legality(op: String, value: String, statuses: List<String>): Sql {
        if (op != ":" && op != "=") throw SearchError("format filters support only ':' or '=', got '$op'")
        val key = formats.legalityKey(value)
        return Sql(
            "EXISTS (SELECT 1 FROM card_legalities cl WHERE cl.card_name = c.name AND cl.format = ? " +
                "AND cl.status IN (${statuses.joinToString(",") { "?" }}))",
            listOf(key) + statuses,
        )
    }

    /** Power / toughness are TEXT: string equality for `:`/`=`, numbers only where the column is one. */
    private fun powerOrToughness(column: String, op: String, value: String): Sql {
        if (op == ":" || op == "=") return Sql("c.$column = ?", listOf(value))
        val n = value.toIntOrNull() ?: throw SearchError("expected integer for $column $op comparison, got '$value'")
        val sqlOp = NUMERIC_OPS[op]?.takeIf { op != ":" && op != "=" } ?: throw SearchError("unsupported op '$op' on $column")
        return Sql("(${integerOnly(column)} AND CAST(c.$column AS INTEGER) $sqlOp ?)", listOf(n))
    }

    /** ORDER BY for the sort keys, NULLs last whatever the direction; the name breaks every tie. */
    fun orderBy(keys: List<SortKey>): String {
        if (keys.isEmpty()) return "c.name COLLATE NOCASE ASC"
        return keys.flatMap { key ->
            val expr = when (key.field) {
                // Numeric where the column holds a plain number ('3'), NULL otherwise ('*', '1+*').
                "pow", "power" -> "CASE WHEN ${integerOnly("power")} THEN CAST(c.power AS REAL) END"
                "tou", "toughness" -> "CASE WHEN ${integerOnly("toughness")} THEN CAST(c.toughness AS REAL) END"
                else -> SORT_FIELDS[key.field]
                    ?: throw SearchError("unknown sort field: '${key.field}'. Valid: ${(SORT_FIELDS.keys + setOf("power", "toughness")).sorted().joinToString(", ")}")
            }
            listOf("($expr) IS NULL", "($expr) ${if (key.descending) "DESC" else "ASC"}")
        }.plus("c.name COLLATE NOCASE ASC").joinToString(", ")
    }

    companion object {
        private val FIELD_ALIAS = mapOf(
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
        )
        private val GAMES = setOf("paper", "mtgo", "arena", "astral", "sega")
        private val IS_PREDICATES = mapOf("reserved" to "c.reserved = 1")
        private val NUMERIC_OPS = mapOf(":" to "=", "=" to "=", "!=" to "!=", ">" to ">", "<" to "<", ">=" to ">=", "<=" to "<=")
        private val ALL_COLORS = listOf("W", "U", "B", "R", "G")
        private val COLOR_WORDS = mapOf(
            "white" to "W", "w" to "W", "blue" to "U", "u" to "U", "black" to "B", "b" to "B",
            "red" to "R", "r" to "R", "green" to "G", "g" to "G", "colorless" to "", "c" to "",
        )
        private val SORT_FIELDS = mapOf(
            "mv" to "c.mana_value",
            "cmc" to "c.mana_value",
            "name" to "c.name COLLATE NOCASE",
            "rarity" to "CASE c.rarity WHEN 'common' THEN 1 WHEN 'uncommon' THEN 2 WHEN 'rare' THEN 3 " +
                "WHEN 'mythic' THEN 4 WHEN 'bonus' THEN 5 WHEN 'special' THEN 6 ELSE 7 END",
            "color" to "COALESCE(c.colors, '')",
            // The number of colours in the identity, so mono to multicolour reads in order.
            "ci" to "CASE WHEN COALESCE(c.color_identity, '') = '' THEN 0 ELSE " +
                "LENGTH(c.color_identity) - LENGTH(REPLACE(c.color_identity, ',', '')) + 1 END",
            // EDHREC rank: 1 is the most played, so asc_edhrec is most popular first.
            "edhrec" to "c.edhrec_rank",
        )

        /** `%value%` with the user's own `%` and `_` literal: `n:_____` matched every long name. */
        fun contains(value: String): String = "%${likeLiteral(value)}%"

        /** For `LIKE ? ESCAPE '!'`. */
        fun likeLiteral(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

        /**
         * The column holds a plain integer, one leading '-' allowed (Spinal
         * Parasite is -1/-1). Checking only the first character let '1+*'
         * through, and CAST('1+*') is 1: Tarmogoyf matched `pow<=1`.
         */
        private fun integerOnly(column: String): String {
            val digits = "(CASE WHEN c.$column LIKE '-%' THEN substr(c.$column, 2) ELSE c.$column END)"
            return "($digits <> '' AND $digits NOT GLOB '*[^0-9]*')"
        }

        /** 'uw', 'U,W', '{U}{W}', 'blue white' -> sorted unique letters; empty for colourless. */
        fun parseColors(raw: String): List<String> {
            val s = raw.trim().lowercase()
            COLOR_WORDS[s]?.let { return if (it.isEmpty()) emptyList() else listOf(it) }
            val words = s.split(Regex("[\\s,;/]+")).filter { it.isNotEmpty() }
            if (words.size > 1 && words.all { it in COLOR_WORDS }) {
                return words.mapNotNull { COLOR_WORDS[it]?.takeIf(String::isNotEmpty) }.toSortedSet().toList()
            }
            return s.filter { it !in "{},;/ \t" }.map { ch ->
                if (ch in "wubrg") ch.uppercase() else throw SearchError("unknown color token: '$ch' in '$raw'")
            }.toSortedSet().toList()
        }
    }
}
