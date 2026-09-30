package mtgoracle.core.analysis

/*
 * A deck's mana curve, colour pips and mana sources (analytics.py). Sideboard
 * rows are out; commanders are in, since their pips ask the mana base too.
 * Two-faced cards count by their FRONT face, except that a modal DFC with a
 * land back is a spell AND a mana source (`39 (42 with MDFC)`), and a split
 * card is placed by its cheapest half castable from hand.
 */

/** One deck row as analysis needs it. [facts] is null for a card the database lacks. */
data class AnalysisRow(
    val name: String,
    val quantity: Int,
    val facts: CardFacts?,
    val isCommander: Boolean = false,
    val isSideboard: Boolean = false,
)

data class DeckAnalytics(
    /** 0..6, where 6 means 6+. */
    val manaCurve: List<Int>,
    val mvAvg: Double,
    /** W U B R G C: colour requirements of the non-lands. */
    val colorPips: Map<Char, Int>,
    val pipTotal: Int,
    /** W U B R G C, including modal-DFC land backs. */
    val manaSources: Map<Char, Int>,
    val nonlandCount: Int,
    val landCount: Int,
    val mdfcLandCount: Int,
) {
    /** Every card that can make a land drop: what [manaSources] counts. */
    val landTotal: Int get() = landCount + mdfcLandCount
}

object DeckAnalysis {
    const val COLORS = "WUBRG"
    private const val BUCKETS = "WUBRGC"
    private val SYMBOL = rx("\\{([^}]+)\\}")
    private val BASIC_SUBTYPE = mapOf("Plains" to 'W', "Island" to 'U', "Swamp" to 'B', "Mountain" to 'R', "Forest" to 'G')

    /** The clause that says what a land makes: scanning all text made Kor Haven's activation cost a white source. */
    private val ADD_CLAUSE = rx("\\badd\\b([^.]*)", ignoreCase = true)
    private val ANY_COLOR = rx("\\bany (?:one )?colou?r\\b|\\bany combination of colou?rs\\b|\\bany type\\b", ignoreCase = true)
    private val DECK_COLORS = rx("\\bcommander's colou?r identity\\b|\\bthe chosen colou?r\\b", ignoreCase = true)
    private val FETCH = rx("\\bsearch (?:your|their) library for ([^.]*?)\\bcards?\\b", ignoreCase = true)
    private val LAND = rx("\\bland\\b", ignoreCase = true)
    private val LAND_TYPE = BASIC_SUBTYPE.mapKeys { (name, _) -> rx("\\b$name\\b") }

    /** CR 202.3: `{2/W}` is 2, `{X}` is 0. */
    private fun symbolManaValue(symbol: String): Int {
        val head = symbol.split("/", limit = 2)[0]
        return when {
            head.isDigits() -> head.toInt()
            symbol in setOf("X", "Y", "Z") -> 0
            else -> 1
        }
    }

    private fun costManaValue(cost: String) = SYMBOL.findAll(cost.uppercase()).sumOf { symbolManaValue(it.groupValues[1]) }

    /** (mana value, mana cost) of the face the card is cast as: the front, or a split card's cheapest castable half. */
    private fun castCost(card: CardFacts): Pair<Int, String> {
        if (card.layout == "split") {
            val castable = card.faces.filter { !it.isAftermath() }
            if (castable.isNotEmpty()) {
                val cost = castable.map { it.manaCost ?: "" }.minBy(::costManaValue)
                return costManaValue(cost) to cost
            }
        }
        return card.manaValue to (card.manaCost ?: "").split("//", limit = 2)[0]
    }

    /**
     * Colour requirements of one cost. A hybrid `{G/W}` asks EACH of its
     * colours, so the buckets count requirements, not symbols; twobrid and
     * Phyrexian count their colour; `{C}` is its own bucket.
     */
    fun costPips(cost: String): Map<Char, Int> {
        val pips = BUCKETS.associateWith { 0 }.toMutableMap()
        for (m in SYMBOL.findAll(cost.uppercase())) {
            val symbol = m.groupValues[1]
            if (symbol == "C") {
                pips['C'] = pips.getValue('C') + 1
                continue
            }
            symbol.split("/").filter { it.length == 1 && it[0] in COLORS }.map { it[0] }.toSet()
                .forEach { pips[it] = pips.getValue(it) + 1 }
        }
        return pips
    }

    private fun basicLandColor(typeLine: String?): Char? {
        if (typeLine == null || "Basic" !in typeLine || "Land" !in typeLine) return null
        val parts = typeLine.split("—", limit = 2)
        val subtype = if (parts.size > 1) parts[1].trim() else ""
        if (subtype.isEmpty()) return 'C' // Wastes
        return subtype.split(WHITESPACE).firstNotNullOfOrNull { BASIC_SUBTYPE[it] } ?: 'C'
    }

    /**
     * What one land face produces. Printed symbols are never clipped; "any
     * colour" and fetched colours are clipped to [deckColors] when known
     * (City of Brass in Rakdos is not a green source). A fetch that can find
     * nothing the deck uses is no source at all.
     */
    private fun manaFromLand(typeLine: String, oracleText: String, fallbackCi: String, deckColors: Set<Char>): Set<Char> {
        basicLandColor(typeLine)?.let { return setOf(it) }
        val found = mutableSetOf<Char>()
        val flexible = mutableSetOf<Char>()
        for (m in ADD_CLAUSE.findAll(oracleText)) {
            val clause = m.groupValues[1]
            SYMBOL.findAll(clause.uppercase()).map { it.groupValues[1] }.filter { it.length == 1 && it[0] in BUCKETS }.forEach { found += it[0] }
            if (DECK_COLORS.containsMatchIn(clause)) found += deckColors
            else if (ANY_COLOR.containsMatchIn(clause)) flexible += COLORS.toSet()
        }
        for (m in FETCH.findAll(oracleText)) {
            val target = m.groupValues[1]
            val named = LAND_TYPE.filterKeys { it.containsMatchIn(target) }.values.toSet()
            if (named.isNotEmpty()) flexible += named
            else if (LAND.containsMatchIn(target)) found += deckColors
        }
        found += if (deckColors.isNotEmpty()) flexible intersect deckColors else flexible
        if (flexible.isNotEmpty() && found.isEmpty()) return emptySet()
        if (found.isNotEmpty()) return found
        return fallbackCi.split(",").filter { it.isNotEmpty() }.map { it[0] }.toSet().ifEmpty { setOf('C') }
    }

    /** Colours a card with a land front produces: a Pathway both its faces, a transform land only its front. */
    private fun landSources(card: CardFacts, deckColors: Set<Char>): Set<Char> {
        val front = card.frontTypeLine()
        if (!isLandWord(front)) return emptySet()
        val ci = card.colorIdentity ?: ""
        if (card.faces.isEmpty()) return manaFromLand(front, card.oracleText ?: "", ci, deckColors)
        var landFaces = card.faces.filter { isLandWord(it.typeLine) }
        if (card.layout != "modal_dfc") landFaces = landFaces.take(1)
        return landFaces.flatMap { manaFromLand(it.typeLine ?: "", it.oracleText ?: "", ci, deckColors) }.toSet()
    }

    private fun identity(card: CardFacts?) = (card?.colorIdentity ?: "").split(",").filter { it.isNotEmpty() }.map { it[0] }.toSet()

    /**
     * The colours the mana is for: the commanders' identity, else every main
     * card's, lands included (a UW Canadian Highlander list with a B/G
     * surveil land really wants black and green for converge).
     */
    fun deckColors(rows: List<AnalysisRow>): Set<Char> {
        val main = rows.filter { !it.isSideboard }
        val commanders = main.filter { it.isCommander }
        return commanders.ifEmpty { main }.flatMap { identity(it.facts) }.toSet() intersect COLORS.toSet()
    }

    fun compute(rows: List<AnalysisRow>): DeckAnalytics {
        val curve = IntArray(7)
        var mvTotal = 0
        var nonland = 0
        var lands = 0
        var mdfc = 0
        val pips = BUCKETS.associateWith { 0 }.toMutableMap()
        val sources = BUCKETS.associateWith { 0 }.toMutableMap()
        val colours = deckColors(rows)
        for (row in rows) {
            if (row.isSideboard) continue
            val qty = if (row.quantity == 0) 1 else row.quantity
            val card = row.facts ?: CardFacts(row.name)
            if (isLandWord(card.frontTypeLine())) {
                lands += qty
                landSources(card, colours).forEach { sources[it] = sources.getValue(it) + qty }
                continue
            }
            // A spell, including an MDFC with a land back: the front is what you cast.
            nonland += qty
            val (mv, cost) = castCost(card)
            mvTotal += mv * qty
            curve[minOf(6, mv)] += qty
            costPips(cost).forEach { (c, n) -> pips[c] = pips.getValue(c) + n * qty }
            // ...and still a mana source, since that face may be played as a land instead.
            if (card.hasLandBack()) {
                val back = card.faces.drop(1).first { isLandWord(it.typeLine) }
                mdfc += qty
                manaFromLand(back.typeLine ?: "", back.oracleText ?: "", card.colorIdentity ?: "", colours)
                    .forEach { sources[it] = sources.getValue(it) + qty }
            }
        }
        return DeckAnalytics(
            manaCurve = curve.toList(),
            mvAvg = if (nonland > 0) mvTotal.toDouble() / nonland else 0.0,
            colorPips = pips,
            pipTotal = pips.values.sum(),
            manaSources = sources,
            nonlandCount = nonland,
            landCount = lands,
            mdfcLandCount = mdfc,
        )
    }
}
