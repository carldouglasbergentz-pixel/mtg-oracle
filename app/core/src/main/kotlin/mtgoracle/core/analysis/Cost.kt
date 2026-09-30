package mtgoracle.core.analysis

import java.util.regex.Pattern

/*
 * The least mana that actually gets you a card's effect (roles.effective_mana).
 * Force of Will is not a five-drop and Dig Through Time is not an eight-drop:
 * any on-curve analysis over printed mana value measures the wrong deck. The
 * conventions are uniform, so the result can be argued with as a whole.
 */

/** What a card costs, printed and effective, and why they differ. */
data class Cost(
    val printed: Int,
    val effective: Int,
    val reason: String = "",
    /** Miracle and its kin: an upside, not the norm. */
    val alternative: Int? = null,
    val alternativeReason: String = "",
) {
    val adjusted: Boolean get() = effective != printed
}

/** Python's `re` semantics for str patterns: `\w`, `\b` and case are Unicode-aware. */
internal fun rx(pattern: String, ignoreCase: Boolean = false): Regex =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS or (if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)).toRegex()

private val REMINDER = rx("\\s*\\([^)]*\\)")

/** Oracle text with reminder text stripped, whitespace flattened, lower case. */
internal fun clean(text: String?): String =
    REMINDER.replace(text ?: "", " ").split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

object Costs {
    /**
     * {X} is costed at the smallest X that does the job the card is counted
     * for: 2 when X sizes an answer or a draw (X=0 would make Wrath of the
     * Skies a two-mana sweeper), 1 when it sizes bodies (one 4/4 Angel is a
     * threat already).
     */
    const val X_VALUE = 2
    const val X_VALUE_TOKENS = 1

    /** Cards assumed in the graveyard when a delve spell is cast. */
    const val DELVE_YARD = 6

    /**
     * Second faces castable from hand, each by its rule: adventure CR 715,
     * omen CR 716, modal_dfc CR 712, split CR 709 (whose printed mana value
     * is the SUM of both halves). Absent on purpose: `prepare` (CR 722.3, the
     * inset frame is not castable from hand, corrections table), transform
     * and meld (reached, never cast).
     */
    val CASTABLE_SECOND_FACE: Set<String> = setOf("adventure", "omen", "modal_dfc", "split")

    private val X_MAKES_BODIES = rx("\\bcreate x\\b[^.]{0,60}\\bcreature tokens?\\b|\\bput x \\+1/\\+1 counters\\b", ignoreCase = true)
    // Only "this spell's": "rather than pay ITS mana cost" grants the cost to another spell (Bolas's Citadel).
    private val ALT_COST = rx("([^.]*?)\\brather than pay this spell's mana cost")
    private val SYMBOLS = rx("\\{[^}]+\\}")
    private val SYMBOL_BODY = rx("\\{([^}]+)\\}")
    private val EVOKE_FREE = rx("evoke\\s*[—-]\\s*exile", ignoreCase = true)
    // A cost is a run of symbols: `warp {1}{U}` is two mana, not one.
    private const val COST_RUN = "((?:\\{[^}]+\\})+)"
    private val EVOKE_COST = rx("evoke\\s*$COST_RUN", ignoreCase = true)
    private val DELVE = rx("\\bdelve\\b", ignoreCase = true)
    private val MIRACLE = rx("\\bmiracle\\s*$COST_RUN", ignoreCase = true)
    private val WARP = rx("\\bwarp\\s*$COST_RUN", ignoreCase = true)
    // Phyrexian mana can always be paid with life: Gitaxian Probe is free, Dismember one mana.
    private val PHYREXIAN = rx("\\{[WUBRGC](?:/[WUBRG])?/P\\}", ignoreCase = true)
    private val X_SYMBOL = rx("\\{X\\}", ignoreCase = true)
    private val GENERIC = rx("\\{(\\d+)\\}")

    /** Mana value of one face's cost, {X} as 0; null for no cost at all. */
    internal fun faceManaValue(cost: String): Int? {
        if (cost.isEmpty()) return null
        return SYMBOL_BODY.findAll(cost).sumOf { m ->
            val sym = m.groupValues[1]
            when {
                sym.isDigits() -> sym.toInt()
                sym.uppercase() == "X" -> 0
                else -> 1 // coloured, hybrid, phyrexian, snow: all one
            }
        }
    }

    /** The cheapest "rather than pay this spell's mana cost" option: (mana, symbols); pitch or life is 0. */
    private fun alternativeCost(text: String): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (m in ALT_COST.findAll(text)) {
            val symbols = SYMBOLS.findAll(m.groupValues[1]).joinToString("") { it.value }.uppercase()
            val mv = faceManaValue(symbols) ?: 0
            if (best == null || mv < best.first) best = mv to symbols
        }
        return best
    }

    /** A `{X}` creature printed with a body survives at X=0; a 0/0 that needs the counters does not. */
    private fun hasPrintedBody(card: CardFacts): Boolean {
        val face = card.faces.firstOrNull()
        val power = ((face?.power).orEmpty().ifEmpty { card.power.orEmpty() }).trim()
        val tough = ((face?.toughness).orEmpty().ifEmpty { card.toughness.orEmpty() }).trim()
        if (power.isEmpty() || tough.isEmpty()) return false
        if (!power.trimStart('+', '-').isDigits() || !tough.trimStart('+', '-').isDigits()) return false
        return tough.toInt() > 0
    }

    /**
     * The least mana that gets you this card's main effect: a free
     * alternative cost or a free evoke is 0, warp and a cheaper evoke their
     * cost, delve the coloured pips, {X} at [xValue], a cheaper castable
     * second face that face. Miracle is NOT applied, only reported as the
     * alternative: it depends on draw order, and a one-mana Terminus claims
     * a turn-one wrath.
     */
    fun effective(card: CardFacts, xValue: Int = X_VALUE): Cost {
        val printed = card.manaValue
        val costStr = card.manaCost ?: ""
        val raw = card.oracleText ?: ""
        val text = clean(card.oracleText)
        val layout = card.layout ?: "normal"
        fun xCount(s: String) = X_SYMBOL.findAll(s).count()
        val xEach = if (X_MAKES_BODIES.containsMatchIn(text)) X_VALUE_TOKENS else xValue

        var alt: Int? = null
        var altReason = ""
        MIRACLE.find(raw)?.let { m ->
            faceManaValue(m.groupValues[1])?.let { mv ->
                alt = mv + xEach * xCount(m.groupValues[1])
                altReason = "miracle ${m.groupValues[1]}"
            }
        }
        fun out(effective: Int, reason: String) = Cost(printed, effective, reason, alt, altReason)

        alternativeCost(text)?.let { (mv, symbols) ->
            if (mv < printed) return out(mv, if (mv == 0) "free alternative cost" else "alternative cost $symbols")
        }
        if (EVOKE_FREE.containsMatchIn(raw)) return out(0, "evoke — exile a card")
        WARP.find(raw)?.let { w ->
            val mv = faceManaValue(w.groupValues[1])
            if (mv != null && mv < printed) return out(mv, "warp ${w.groupValues[1]}")
        }
        EVOKE_COST.find(raw)?.let { e ->
            val mv = faceManaValue(e.groupValues[1])
            if (mv != null && mv < printed) return out(mv, "evoke ${e.groupValues[1]}")
        }

        // A split card's halves are both candidates (its printed value is their sum); an aftermath half never is.
        if (layout in CASTABLE_SECOND_FACE) {
            val candidates = if (layout == "split") card.faces else card.faces.drop(1)
            var cheapest: Pair<Int, String>? = null
            for (face in candidates) {
                if (face.isAftermath()) continue
                val mv = faceManaValue(face.manaCost ?: "") ?: continue
                if (cheapest == null || mv < cheapest.first) cheapest = mv to (face.name?.ifEmpty { null } ?: "back face")
            }
            cheapest?.let { (mv, name) -> if (mv < printed) return out(mv, "$name costs $mv") }
        }

        if (DELVE.containsMatchIn(text)) {
            val pips = SYMBOL_BODY.findAll(costStr).count { val s = it.groupValues[1]; !s.isDigits() && s.uppercase() != "X" }
            val generic = GENERIC.findAll(costStr).sumOf { it.groupValues[1].toInt() }
            val eff = pips + maxOf(0, generic - DELVE_YARD)
            if (eff < printed) return out(eff, "delve with $DELVE_YARD cards in the yard")
        }

        // The front face's symbols only: the other face is priced above, if castable at all.
        val phyrexian = PHYREXIAN.findAll(costStr.frontPart()).count()
        val life = if (phyrexian > 0) " — Phyrexian mana paid with life" else ""
        val base = maxOf(0, printed - phyrexian)
        val xs = xCount(costStr)
        if (xs > 0) {
            // Wan Shi Tong is {X}{U}{U} on a 1/1: X=0 is a real mode. A base 0/0 (Walking Ballista) dies there.
            if ("Creature" in card.frontTypeLine() && hasPrintedBody(card)) {
                return out(base, "{X} creature with a printed body — X=0 is castable$life")
            }
            return out(base + xEach * xs, "{X} at X=$xEach" + (if (xs > 1) " (×$xs)" else "") + life)
        }
        if (phyrexian > 0) return out(base, "Phyrexian mana — payable with life")
        return out(printed, "")
    }
}

/** Python's `str.isdigit()`, which is false for an empty string. */
internal fun String.isDigits(): Boolean = isNotEmpty() && all { it.isDigit() }
