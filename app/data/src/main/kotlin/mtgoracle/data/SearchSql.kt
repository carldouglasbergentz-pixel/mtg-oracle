package mtgoracle.data

import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.NameFold
import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchFields
import mtgoracle.core.lookup.closest
import mtgoracle.core.lookup.SearchNode
import mtgoracle.core.lookup.SortKey
import mtgoracle.core.lookup.SortLayer
import mtgoracle.core.lookup.CardTypes

/** A WHERE fragment over `cards c` and its parameters, in order. */
data class Sql(val text: String, val params: List<Any> = emptyList())

/**
 * The search language's syntax tree as SQL over `cards c`: what each field
 * means. Always parameterised; the only text spliced in is our own. A port
 * of scryfall_search.compile_term / _build_order_by, operator for operator.
 */
class SearchSql(
    private val formats: FormatCatalog,
    /** The rarities and layouts the cards have, which `r:` and `layout:` must name; empty, anything goes (no cards yet). */
    private val rarities: Set<String> = emptySet(),
    private val layouts: Set<String> = emptySet(),
    /** `like:`'s cards for a card's name, likest first; a SearchError for a name that is no card. */
    private val like: (String) -> List<String> = { throw SearchError("like: isn't available here") },
) {

    fun where(node: SearchNode?): Sql = if (node == null) Sql("1=1") else compile(node)

    private fun compile(node: SearchNode): Sql = when (node) {
        is SearchNode.Term -> term(node)
        // Names compare folded (fold(), MtgDb): `eowyn` finds Éowyn, as `card eowyn` does.
        is SearchNode.Free -> if (node.value.isBlank()) throw SearchError("empty quotes: put what to find between them")
        else contains(node.value).let { p ->
            Sql("(fold(c.name) LIKE ? ESCAPE '!' OR c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE " +
                "OR c.oracle_text LIKE ? ESCAPE '!' COLLATE NOCASE)", listOf(contains(NameFold.fold(node.value)), p, p))
        }
        // A comparison against NULL is NULL, and NOT NULL is still NULL — so
        // `-pow>=4` dropped every non-creature. Unknown is "no match".
        is SearchNode.Not -> compile(node.expr).let { Sql("NOT COALESCE((${it.text}), 0)", it.params) }
        is SearchNode.And -> join(node.parts, " AND ")
        is SearchNode.Or -> join(node.parts, " OR ")
    }

    /** [value] as one of [known], or a SearchError naming the likely one: a typo found nothing, silently. */
    private fun oneOf(what: String, field: String, value: String, known: Set<String>): String {
        val v = value.trim().lowercase()
        if (known.isNotEmpty() && v !in known) throw SearchError("unknown $what: '$value'" +
            (closest(v, known)?.let { " — did you mean $field:$it?" } ?: "") + ". Valid: ${known.sorted().joinToString(", ")}")
        return v
    }

    private fun join(parts: List<SearchNode>, op: String): Sql {
        val compiled = parts.map(::compile)
        return Sql(compiled.joinToString(op, "(", ")") { it.text }, compiled.flatMap { it.params })
    }

    private fun term(t: SearchNode.Term): Sql {
        val field = SearchFields.ALIAS[t.field.lowercase()]
            ?: throw SearchError("unknown field: '${t.field}'" + (closest(t.field, SearchFields.ALIAS.keys)?.let { " — did you mean $it${t.op}?" } ?: ""))
        val op = t.op
        val value = t.value
        // Typed as far as `ci:` or `t:`, the live hint read every card (contains '') or the colourless ones (no colours).
        if (value.isBlank()) throw SearchError("'${t.field}${t.op}' needs a value")
        fun textOnly(what: String) { if (op != ":" && op != "=") throw SearchError("$what supports only ':' or '=', got '$op'") }
        return when (field) {
            "o" -> { textOnly("oracle text"); Sql("c.oracle_text LIKE ? ESCAPE '!' COLLATE NOCASE", listOf(contains(value))) }
            "t" -> { textOnly("type line"); Sql("c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE", listOf(contains(value))) }
            // `=` is "exactly" everywhere in this language (c=, ci=), so for names too;
            // `n:"Lightning Bolt"` also matches 'Emeritus of Conflict // Lightning Bolt'.
            "n" -> when (op) {
                "=" -> Sql("(c.name = ? COLLATE NOCASE OR fold(c.name) = ?)", listOf(value, NameFold.fold(value)))
                ":" -> Sql("fold(c.name) LIKE ? ESCAPE '!'", listOf(contains(NameFold.fold(value))))
                else -> throw SearchError("name supports only ':' or '=', got '$op'")
            }
            "kw" -> {
                textOnly("kw")
                Sql("EXISTS (SELECT 1 FROM card_tags kt WHERE kt.card_name = c.name AND kt.category='keyword' AND kt.tag=?)", listOf(value.lowercase()))
            }
            "c" -> {
                if (value.trim().lowercase() in MULTICOLOR) {
                    if (op != ":") throw SearchError("c:m (multicolored) supports only ':'")
                    return Sql("c.colors LIKE '%,%'")
                }
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
            "r" -> { textOnly("rarity"); Sql("c.rarity = ?", listOf(oneOf("rarity", "r", value, rarities))) }
            "layout" -> { textOnly("layout"); Sql("c.layout = ?", listOf(oneOf("layout", "layout", value, layouts))) }
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
                    ?: throw SearchError("unknown is: predicate '$value'" + (closest(value.trim(), IS_PREDICATES.keys)?.let { " — did you mean is:$it?" } ?: "") +
                        ". Valid: ${IS_PREDICATES.keys.sorted().joinToString(", ")}")
                Sql(predicate)
            }
            "m" -> manaCost(op, value)
            "like" -> {
                textOnly("like")
                val names = like(value.trim())
                if (names.isEmpty()) Sql("0") else Sql(names.joinToString(", ", "c.name IN (", ")") { "?" }, names)
            }
            "pool" -> {
                textOnly("pool")
                val deckId = value.trim().toIntOrNull() ?: throw SearchError("pool: takes a deck's id, got '$value'")
                Sql("EXISTS (SELECT 1 FROM deck_cards dc WHERE dc.deck_id = ? AND dc.is_sideboard = 1 AND dc.card_name = c.name)", listOf(deckId))
            }
            "otag" -> {
                textOnly("otag")
                // Stored with spaces ('mana rock'), written by Scryfall with hyphens ('mana-rock'). A tag also
                // finds its children, which Tagger names `<tag>-<kind>`: there is no plain 'removal' tag at all.
                val written = value.trim().lowercase()
                val spaced = written.replace('-', ' ')
                Sql(
                    "EXISTS (SELECT 1 FROM card_oracle_tags ot WHERE ot.card_name = c.name " +
                        "AND (ot.tag IN (?, ?) OR ot.tag LIKE ? ESCAPE '!' OR ot.tag LIKE ? ESCAPE '!'))",
                    listOf(written, spaced, likeLiteral(written) + "-%", likeLiteral(spaced) + "-%"),
                )
            }
            else -> throw SearchError("unknown field mapping: '$field'")
        }
    }

    /** `m:` has at least these symbols (counted, so {U}{1} finds {1}{U}); `m=` exactly these and no others. */
    private fun manaCost(op: String, value: String): Sql {
        if (op != ":" && op != "=") throw SearchError("mana cost supports only ':' or '=', got '$op'")
        val symbols = manaSymbols(value)
        if (symbols.isEmpty()) throw SearchError("m: needs at least one mana symbol")
        // How often `?` occurs in the cost: the length it loses without it, over its own length.
        val occurs = "((LENGTH(COALESCE(c.mana_cost, '')) - LENGTH(REPLACE(COALESCE(c.mana_cost, ''), ?, ''))) / ?)"
        val cmp = if (op == ":") ">=" else "="
        val parts = mutableListOf<String>()
        val params = mutableListOf<Any>()
        for ((symbol, n) in symbols.groupingBy { it }.eachCount()) {
            parts += "$occurs $cmp ?"
            params.addAll(listOf(symbol, symbol.length, n))
        }
        if (op == "=") {
            // ...and nothing else: the cost holds as many `{` as there are symbols.
            parts += "$occurs = ?"
            params.addAll(listOf("{", 1, symbols.size))
        }
        return Sql(parts.joinToString(" AND ", "(", ")"), params)
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

    /**
     * ORDER BY for a search laid out in [layers] (CardSort), each ranked as
     * `SortLayer.rank` ranks a row, then the name; [named] (exactName) first.
     */
    fun sortLayers(layers: List<SortLayer>, named: Sql? = null): Sql {
        val first = named?.let { listOf("CASE WHEN ${it.text} THEN 0 ELSE 1 END") }.orEmpty()
        return Sql((first + layers.map(::rankOf) + "c.name COLLATE NOCASE ASC").joinToString(", "), named?.params.orEmpty())
    }

    /** The card named [phrase], as typed: the whole name, or a two-faced card's front face. */
    fun exactName(phrase: String): Sql =
        Sql("(c.name = ? COLLATE NOCASE OR c.name LIKE ? ESCAPE '!' COLLATE NOCASE)", listOf(phrase, likeLiteral(phrase) + " // %"))

    /** [layer]'s rank of a card as SQL: what `SortLayer.rank` says of its row, case for case. */
    fun rankOf(layer: SortLayer): String = when (layer) {
        SortLayer.COLOUR -> "(CASE WHEN COALESCE(c.colors, '') = '' THEN ${SortLayer.COLOURLESS} " +
            "WHEN instr(c.colors, ',') > 0 THEN ${SortLayer.MULTICOLOUR} ELSE instr('${SortLayer.WUBRG.joinToString("")}', c.colors) - 1 END)"
        // The first of the kinds, in their order, that the front face's type line holds.
        SortLayer.TYPE -> CardTypes.ORDER.withIndex().joinToString(" ", "(CASE ", " ELSE ${CardTypes.ORDER.size} END)") { (i, kind) ->
            "WHEN instr($FRONT_TYPE, '$kind') > 0 THEN $i"
        }
        SortLayer.MV -> "COALESCE(CAST(c.mana_value AS INTEGER), ${SortLayer.NO_MV})"
    }

    /**
     * ORDER BY for the sort keys, NULLs last whatever the direction; the name
     * breaks every tie. With no sort asked for, [freeWords] rank by relevance.
     */
    fun orderBy(keys: List<SortKey>, freeWords: List<String> = emptyList(), likeTarget: String? = null): Sql {
        // `like:` with no order of its own: likest first.
        if (keys.isEmpty() && likeTarget != null) {
            val names = like(likeTarget.trim())
            if (names.isNotEmpty()) return Sql(names.indices.joinToString(" ", "CASE c.name ", " ELSE ${names.size} END, c.name COLLATE NOCASE ASC") { "WHEN ? THEN $it" }, names)
        }
        if (keys.isEmpty()) {
            if (freeWords.isEmpty()) return Sql("c.name COLLATE NOCASE ASC")
            val rank = relevance(freeWords)
            return Sql("${rank.text}, c.name COLLATE NOCASE ASC", rank.params)
        }
        return Sql(keys.flatMap { key ->
            val expr = when (key.field) {
                // Numeric where the column holds a plain number ('3'), NULL otherwise ('*', '1+*').
                "pow", "power" -> "CASE WHEN ${integerOnly("power")} THEN CAST(c.power AS REAL) END"
                "tou", "toughness" -> "CASE WHEN ${integerOnly("toughness")} THEN CAST(c.toughness AS REAL) END"
                else -> SORT_FIELDS[key.field] ?: (SORT_FIELDS.keys + setOf("power", "toughness")).let { valid ->
                    throw SearchError("unknown sort field: '${key.field}'" + (closest(key.field, valid)?.let { " — did you mean $it?" } ?: "") +
                        ". Valid: ${valid.sorted().joinToString(", ")}")
                }
            }
            listOf("($expr) IS NULL", "($expr) ${if (key.descending) "DESC" else "ASC"}")
        }.plus("c.name COLLATE NOCASE ASC").joinToString(", "))
    }

    /** Name matches first — exact, prefix, every word somewhere in it — then type-line matches, then the rest. */
    private fun relevance(words: List<String>): Sql {
        val phrase = words.joinToString(" ")
        val inName = words.joinToString(" AND ") { "c.name LIKE ? ESCAPE '!' COLLATE NOCASE" }
        val inType = words.joinToString(" AND ") { "c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE" }
        return Sql(
            "CASE WHEN c.name = ? COLLATE NOCASE THEN 0 WHEN c.name LIKE ? ESCAPE '!' COLLATE NOCASE THEN 1 " +
                "WHEN $inName THEN 2 WHEN $inType THEN 3 ELSE 4 END",
            listOf<Any>(phrase, likeLiteral(phrase) + "%") + words.map(::contains) + words.map(::contains),
        )
    }

    companion object {
        private val MULTICOLOR = setOf("m", "multicolor", "multicolored")

        /** The front face's type line: a two-faced card is what its front is, so a Sorcery // Land is a spell. */
        private const val FRONT_TYPE = "(CASE WHEN instr(c.type_line, ' // ') > 0 " +
            "THEN substr(c.type_line, 1, instr(c.type_line, ' // ') - 1) ELSE COALESCE(c.type_line, '') END)"
        private val GAMES = SearchFields.GAMES.toSet()
        private val IS_PREDICATES = mapOf(
            "reserved" to "c.reserved = 1",
            // A legendary creature, or a card that says it can be your commander.
            "commander" to "(($FRONT_TYPE LIKE '%Legendary%' AND $FRONT_TYPE LIKE '%Creature%') OR c.oracle_text LIKE '%can be your commander%')",
            "permanent" to "($FRONT_TYPE LIKE '%Artifact%' OR $FRONT_TYPE LIKE '%Creature%' OR $FRONT_TYPE LIKE '%Enchantment%' " +
                "OR ' ' || $FRONT_TYPE || ' ' LIKE '% Land %' OR $FRONT_TYPE LIKE '%Planeswalker%' OR $FRONT_TYPE LIKE '%Battle%')",
            // The Land type, the word: Lander Rizzi is a Lander, and a spell.
            "spell" to "(' ' || $FRONT_TYPE || ' ' NOT LIKE '% Land %')",
            "historic" to "($FRONT_TYPE LIKE '%Legendary%' OR $FRONT_TYPE LIKE '%Artifact%' OR $FRONT_TYPE LIKE '%Saga%')",
            "dfc" to "c.layout IN ('transform', 'modal_dfc', 'reversible_card')",
            "mdfc" to "c.layout = 'modal_dfc'",
            "split" to "c.layout = 'split'",
        )

        /** The flags this compiler knows, which SearchFields.IS_FLAGS must list (SearchSqlTest). */
        val IS_FLAGS: Set<String> get() = IS_PREDICATES.keys

        private val MANA_SYMBOL = Regex("\\{[^{}]+\\}")

        /** '{2}{U}{U}' or the shorthand '2uu' -> [{2}, {U}, {U}]. */
        fun manaSymbols(raw: String): List<String> {
            val s = raw.trim()
            if ('{' in s) {
                val symbols = MANA_SYMBOL.findAll(s).map { it.value.uppercase() }.toList()
                if (symbols.joinToString("") != s.uppercase().replace(" ", "")) throw SearchError("can't read mana cost '$raw'")
                return symbols
            }
            return Regex("[0-9]+|.").findAll(s.lowercase()).map { it.value }.map { part ->
                when {
                    part[0].isDigit() -> "{$part}"
                    part in listOf("w", "u", "b", "r", "g", "c", "x", "s") -> "{${part.uppercase()}}"
                    else -> throw SearchError("unknown mana symbol '$part' in '$raw'")
                }
            }.toList()
        }
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
