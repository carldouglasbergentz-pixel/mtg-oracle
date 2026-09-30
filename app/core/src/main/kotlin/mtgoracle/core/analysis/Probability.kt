package mtgoracle.core.analysis

import java.math.BigInteger

/*
 * Draw probabilities, exact: hypergeometric sums over integer binomials, no
 * approximation or simulation (probability.py).
 *
 * "Can I play category X on turn T" is not "hold a T-cost card AND T mana
 * sources" — that curve falls after turn three and answers nothing. The mana
 * you actually have decides which cards qualify: min(T, lands drawn) plus one
 * per rock drawn, and the category is live if any of its cards costs no more.
 */
object Probability {
    const val OPENING_HAND = 7

    /** Cards you have looked at by your turn [turn]; the player on the play skips the first draw. */
    fun cardsSeen(turn: Int, onPlay: Boolean = true): Int =
        if (turn < 1) OPENING_HAND else OPENING_HAND + turn - (if (onPlay) 1 else 0)

    private val binomials = HashMap<Long, BigInteger>()

    @Synchronized
    private fun comb(n: Int, k: Int): BigInteger {
        if (k < 0 || n < 0 || k > n) return BigInteger.ZERO
        val key = n.toLong() shl 32 or k.toLong()
        return binomials.getOrPut(key) {
            var r = BigInteger.ONE
            val kk = minOf(k, n - k)
            for (i in 0 until kk) r = r.multiply(BigInteger.valueOf((n - i).toLong())).divide(BigInteger.valueOf((i + 1).toLong()))
            r
        }
    }

    /** You cannot look at more cards than the deck has: a small deck late on has seen all of it. */
    private fun clampSeen(seen: Int, deckSize: Int) = maxOf(0, minOf(seen, deckSize))

    /** P(at least one of [hits] among [seen]): the ceiling on any on-curve number. */
    fun holdAny(hits: Int, seen: Int, deckSize: Int = 100): Double {
        val s = clampSeen(seen, deckSize)
        if (hits <= 0 || s <= 0) return 0.0
        val misses = deckSize - hits
        if (misses < s) return 1.0
        return 1.0 - Py.ratio(comb(misses, s), comb(deckSize, s))
    }

    /** P(at least [k] of [hits] among [seen]). */
    fun holdAtLeast(hits: Int, k: Int, seen: Int, deckSize: Int = 100): Double {
        val s = clampSeen(seen, deckSize)
        if (k <= 0) return 1.0
        if (hits < k || s < k) return 0.0
        val misses = deckSize - hits
        var acc = BigInteger.ZERO
        for (i in k..minOf(hits, s)) if (s - i <= misses) acc += comb(hits, i) * comb(misses, s - i)
        return Py.ratio(acc, comb(deckSize, s))
    }

    /**
     * P(at least one castable card of a category on [turn]). [mvCounts] maps
     * effective mana value to count; lands and rocks are disjoint from the
     * category. Sums over every split of the cards seen into lands, rocks
     * and the rest, each exact given its split.
     */
    fun categoryLive(mvCounts: Map<Int, Int>, lands: Int, rocks: Int, turn: Int, seen: Int, deckSize: Int = 100): Double {
        val s = clampSeen(seen, deckSize)
        val spells = deckSize - lands - rocks
        val totalCat = mvCounts.values.sum()
        if (totalCat == 0 || s <= 0) return 0.0
        require(totalCat <= spells) { "category ($totalCat) cannot exceed the non-mana pile ($spells)" }

        val maxMana = turn + rocks
        val affordable = IntArray(maxMana + 1) { m -> mvCounts.entries.sumOf { (mv, c) -> if (mv <= m) c else 0 } }
        val totalWays = comb(deckSize, s).toDouble()
        var acc = 0.0
        for (l in 0..minOf(lands, s)) {
            for (r in 0..minOf(rocks, s - l)) {
                val rest = s - l - r
                if (rest > spells) continue
                val ways = comb(lands, l) * comb(rocks, r) * comb(spells, rest)
                if (ways.signum() == 0 || rest == 0) continue
                val mana = minOf(turn, l) + r
                val live = if (mana < affordable.size) affordable[mana] else 0
                if (live == 0) continue
                val pHit = if (spells - live < rest) 1.0 else 1.0 - Py.ratio(comb(spells - live, rest), comb(spells, rest))
                acc += ways.toDouble() * pHit
            }
        }
        return acc / totalWays
    }

    /**
     * P(the lands and rocks drawn by [turn] pay for a card costing [cost]),
     * given you hold one: Moxfield's "on curve", exact, with rocks counted.
     * Lands pay one each up to one per turn played; a rock pays from the turn
     * it is drawn, as in [categoryLive].
     */
    fun manaOnTurn(lands: Int, rocks: Int, turn: Int, cost: Int, onPlay: Boolean = true, deckSize: Int = 100): Double {
        if (cost <= 0) return 1.0
        val s = clampSeen(cardsSeen(turn, onPlay), deckSize)
        if (s <= 0) return 0.0
        val others = deckSize - lands - rocks
        var ways = BigInteger.ZERO
        for (l in 0..minOf(lands, s)) for (r in 0..minOf(rocks, s - l)) {
            if (minOf(turn, l) + r < cost || s - l - r > others) continue
            ways += comb(lands, l) * comb(rocks, r) * comb(others, s - l - r)
        }
        return Py.ratio(ways, comb(deckSize, s))
    }

    /** [categoryLive] across [turns]. */
    fun curve(mvCounts: Map<Int, Int>, lands: Int, rocks: Int, turns: List<Int>, onPlay: Boolean = true, deckSize: Int = 100): Map<Int, Double> =
        turns.associateWith { categoryLive(mvCounts, lands, rocks, it, cardsSeen(it, onPlay), deckSize) }

    /** [holdAny] across [turns]: the mana-free upper bound. The gap to [curve] is what the mana base costs. */
    fun ceiling(total: Int, turns: List<Int>, onPlay: Boolean = true, deckSize: Int = 100): Map<Int, Double> =
        turns.associateWith { holdAny(total, cardsSeen(it, onPlay), deckSize) }
}
