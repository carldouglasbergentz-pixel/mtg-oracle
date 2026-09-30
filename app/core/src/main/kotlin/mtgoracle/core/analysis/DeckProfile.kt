package mtgoracle.core.analysis

import kotlin.math.pow

/*
 * Archetype analysis (services.profile_deck / compare_decks / rank_cards):
 * a deck classified by what its cards do and really cost, measured against
 * other decks. Pure: the data layer resolves names into a [CardPool] first.
 */

/**
 * Facts and Tagger labels keyed by the name the caller asked for, whatever
 * its spelling. [canonical] folds `Fire/Ice` and `Fire // Ice` into one card,
 * so one card is never counted as two in a reference set.
 */
class CardPool(
    private val facts: Map<String, CardFacts>,
    private val tags: Map<String, Set<String>>,
    /** Classifications by (canonical name, X): a card's only change is a sync, so a caller may share this across pools. */
    private val classified: MutableMap<Pair<String, Int>, Classification> = HashMap(),
) {
    fun facts(name: String): CardFacts? = facts[name]
    fun tags(name: String): Set<String> = tags[name] ?: emptySet()
    fun canonical(name: String): String = facts[name]?.name ?: name

    /** [name] classified, or null when it doesn't resolve. */
    fun classify(name: String, xValue: Int = Costs.X_VALUE): Classification? {
        val fact = facts[name] ?: return null
        return classified.getOrPut(fact.name to xValue) { Roles.classify(fact, xValue, tags(name)) }
    }
}

/** A deck reduced to what the analysis takes: a name and `{card: quantity}` of the main deck. */
data class DeckList(val name: String, val cards: Map<String, Int>)

/**
 * One deck, classified. [counts] is by PRIMARY role, so it sums to the deck
 * size; [roleMv] is by EVERY role a card can fill (a Cryptic Command is a
 * counter and card advantage both).
 */
data class DeckProfile(
    val name: String,
    val size: Int,
    val counts: Map<String, Int>,
    val roleMv: Map<String, Map<Int, Int>>,
    val curve: Map<Int, Int>,
    val lands: Int,
    val rocks: Int,
    val landBacks: Int,
    val unresolved: List<String> = emptyList(),
    /** Per role, the permanents that keep producing it. */
    val engines: Map<String, Int> = emptyMap(),
    /** The category each role's live curve draws from: [roleMv] minus the rocks, except for `mana`. */
    val onCurveMv: Map<String, Map<Int, Int>> = emptyMap(),
    /** The cards counted as [rocks] and as [landBacks], by name: what a report names when it says "2 rocks". */
    val rockNames: List<String> = emptyList(),
    val landBackNames: List<String> = emptyList(),
    /** The spells of [curve], by effective mana value, sorted; and how many of them are permanents. */
    val curveCards: Map<Int, List<String>> = emptyMap(),
    val curvePermanents: Map<Int, Int> = emptyMap(),
) {
    /** P(the mana is there on turn [mv] for a card costing [mv]), if you hold one: see [Probability.manaOnTurn]. */
    fun onCurve(mv: Int, onPlay: Boolean = true): Double = Probability.manaOnTurn(lands, rocks, mv, mv, onPlay, size)

    /** Lands, modal-DFC land backs and rocks. */
    val manaSources: Int get() = lands + rocks

    val avgMv: Double
        get() {
            val total = curve.values.sum()
            return if (total > 0) curve.entries.sumOf { (mv, n) -> mv * n }.toDouble() / total else 0.0
        }

    /**
     * P(this deck can play [role] on each turn). A card sits in exactly one
     * pile: a rock powers every other role's curve and is the category only
     * for `mana`, where no rock pays for another. [size] is the deck's own,
     * never 100: a 60-card list modelled as 100 halves its turn-one odds.
     */
    fun liveCurve(role: String, turns: List<Int> = TURNS, onPlay: Boolean = true): Map<Int, Double> =
        Probability.curve(onCurveMv[role] ?: emptyMap(), lands, if (role == "mana") 0 else rocks, turns, onPlay, deckSize = size)

    /** The same, mana ignored: the upper bound the mana base caps. */
    fun ceiling(role: String, turns: List<Int> = TURNS, onPlay: Boolean = true): Map<Int, Double> =
        Probability.ceiling((roleMv[role] ?: emptyMap()).values.sum(), turns, onPlay, deckSize = size)

    companion object {
        val TURNS: List<Int> = (1..8).toList()
    }
}

/** One role's count in a deck against a reference set. */
data class RoleDelta(val role: String, val subject: Int, val refMean: Double, val refMin: Int, val refMax: Int, val refMedian: Double) {
    val delta: Double get() = Py.round(subject - refMean, 1)

    /** `under`, `in` or `over` the reference RANGE: outside it is a choice nobody in the set made. */
    val verdict: String get() = when {
        subject < refMin -> "under"
        subject > refMax -> "over"
        else -> "in"
    }
}

/** A card one side plays and the other doesn't. */
data class CardDiff(val name: String, val role: String, val mv: Int, val nLists: Int, val ofLists: Int) {
    val share: Double get() = if (ofLists > 0) nLists.toDouble() / ofLists else 0.0
}

/** A deck measured against a reference set: ranges, curve deltas, and the card-level diff. */
data class Comparison(
    val subject: DeckProfile,
    val reference: List<DeckProfile>,
    val roles: List<RoleDelta>,
    val manaSources: RoleDelta,
    /** (subject, reference mean). */
    val avgMv: Pair<Double, Double>,
    val curveDelta: Map<String, Map<Int, Double>>,
    val missing: List<CardDiff>,
    val unique: List<CardDiff>,
    val nearest: List<Pair<String, Double>>,
) {
    val outOfRange: List<RoleDelta> get() = roles.filter { it.verdict != "in" }
    fun role(name: String): RoleDelta? = roles.firstOrNull { it.role == name }
}

/** One row of the most-played ranking. */
data class RankRow(
    val name: String,
    val n: Int,
    val pct: Int,
    val mv: Int,
    val printed: Int,
    val cost: String,
    val primary: Boolean,
    val reason: String,
    val source: String,
)

data class Ranking(val byRole: Map<String, List<RankRow>>, val lowConfidence: Set<String>)

object Archetype {
    /**
     * Classify [deck]. A name that doesn't resolve lands in `unresolved` and
     * out of the analysis: one typo must not throw away the other 99 cards.
     * [miracle] costs miracle cards at their miracle cost, for a pilot who
     * never hardcasts them.
     */
    fun profile(deck: DeckList, pool: CardPool, xValue: Int = Costs.X_VALUE, miracle: Boolean = false): DeckProfile {
        val counts = Roles.ROLES.associateWith { 0 }.toMutableMap()
        val roleMv = Roles.ROLES.associateWith { mutableMapOf<Int, Int>() }
        val onCurve = Roles.ROLES.associateWith { mutableMapOf<Int, Int>() }
        val engines = linkedMapOf<String, Int>()
        val curve = linkedMapOf<Int, Int>()
        var lands = 0
        var rocks = 0
        var landBacks = 0
        var size = 0
        val rockNames = mutableListOf<String>()
        val landBackNames = mutableListOf<String>()
        val curveCards = sortedMapOf<Int, MutableList<String>>()
        val curvePermanents = sortedMapOf<Int, Int>()
        for ((name, qty) in deck.cards) {
            val fact = pool.facts(name) ?: continue
            size += qty
            if (fact.isLand()) {
                counts["land"] = counts.getValue("land") + qty
                lands += qty
                continue
            }
            var cl = pool.classify(name, xValue)!!
            if (miracle && cl.cost.alternative != null) {
                cl = cl.copy(cost = cl.cost.copy(effective = cl.cost.alternative!!, reason = cl.cost.alternativeReason))
            }
            // An MDFC with a land back is a land everywhere, or a role's total exceeds the spell pile.
            if (fact.hasLandBack()) {
                counts["land"] = counts.getValue("land") + qty
                lands += qty
                landBacks += qty
                landBackNames += fact.name
                continue
            }
            counts[cl.primary] = counts.getValue(cl.primary) + qty
            val mv = cl.cost.effective
            val isRock = cl.primary == "mana"
            for (role in cl.roles) {
                val byMv = roleMv[role] ?: continue
                byMv.merge(mv, qty, Int::plus)
                if (cl.engine) engines.merge(role, qty, Int::plus)
                if (!isRock || role == "mana") onCurve.getValue(role).merge(mv, qty, Int::plus)
            }
            if (isRock) {
                rocks += qty
                rockNames += fact.name
            }
            curve.merge(mv, qty, Int::plus)
            curveCards.getOrPut(mv) { mutableListOf() } += fact.name
            val front = fact.frontTypeLine()
            if ("Instant" !in front && "Sorcery" !in front) curvePermanents.merge(mv, qty, Int::plus)
        }
        val missing = deck.cards.keys.filter { pool.facts(it) == null }.sorted()
        return DeckProfile(deck.name, size, counts, roleMv, curve, lands, rocks, landBacks, missing, engines, onCurve, rockNames.sorted(), landBackNames.sorted(),
            curveCards.mapValues { (_, names) -> names.sorted() }, curvePermanents)
    }

    /** How many of [decks] play each card, by canonical name: two spellings in one list is still one list. */
    private fun listsPlaying(decks: List<DeckList>, pool: CardPool): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (deck in decks) deck.cards.keys.map(pool::canonical).toSet().forEach { counts.merge(it, 1, Int::plus) }
        return counts
    }

    /** Euclidean distance over raw role counts: the widest-spread role defines the build, so it may dominate. */
    private fun distance(a: DeckProfile, b: DeckProfile): Double =
        Py.round(Roles.ROLES.sumOf { r -> ((a.counts[r] ?: 0) - (b.counts[r] ?: 0)).let { it * it } }.toDouble().pow(0.5), 2)

    private data class Stats(val mean: Double, val min: Int, val max: Int, val median: Double)

    private fun stats(values: List<Int>): Stats {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        return Stats(values.sum().toDouble() / values.size, values.min(), values.max(), median)
    }

    /**
     * [subject] against [reference]. [minShare] filters `missing`: 0.5 is
     * "only cards half the set plays"; the default reports every lead.
     */
    fun compare(
        subject: DeckList,
        reference: List<DeckList>,
        pool: CardPool,
        turns: List<Int> = DeckProfile.TURNS,
        onPlay: Boolean = true,
        minShare: Double = 0.0,
        xValue: Int = Costs.X_VALUE,
        miracle: Boolean = false,
    ): Comparison {
        require(reference.isNotEmpty()) { "nothing to compare against" }
        val subj = profile(subject, pool, xValue, miracle)
        val refs = reference.map { profile(it, pool, xValue, miracle) }
        val n = refs.size

        val roleDeltas = Roles.ROLES.map { role ->
            val s = stats(refs.map { it.counts[role] ?: 0 })
            RoleDelta(role, subj.counts[role] ?: 0, Py.round(s.mean, 1), s.min, s.max, s.median)
        }
        val ms = stats(refs.map { it.manaSources })
        val mana = RoleDelta("mana_sources", subj.manaSources, Py.round(ms.mean, 1), ms.min, ms.max, ms.median)

        val curveDelta = Roles.ROLES.filter { it != "land" }.associateWith { role ->
            val mine = subj.liveCurve(role, turns, onPlay)
            val theirs = refs.map { it.liveCurve(role, turns, onPlay) }
            turns.associateWith { t -> Py.round(mine.getValue(t) - Py.sum(theirs.map { it.getValue(t) }) / n, 4) }
        }

        val subjCards = subject.cards.keys.map(pool::canonical).toSet()
        val counts = listsPlaying(reference, pool)
        val facts = (listOf(subject) + reference).flatMap { it.cards.keys }.associateBy(pool::canonical)
        fun describe(name: String, lists: Int): CardDiff {
            val asked = facts[name]
            val cl = asked?.let { pool.classify(it, xValue) } ?: return CardDiff(name, "?", 99, lists, n)
            return CardDiff(pool.canonical(asked), cl.primary, cl.cost.effective, lists, n)
        }
        val missing = counts.filter { (name, c) -> name !in subjCards && c.toDouble() / n >= minShare }
            .map { (name, c) -> describe(name, c) }
            .sortedWith(compareBy<CardDiff>({ -it.nLists }, { it.mv }, { it.name }))
        val unique = subjCards.filter { it !in counts }.map { describe(it, 0) }
            .sortedWith(compareBy<CardDiff>({ it.role }, { it.mv }, { it.name }))
        val nearest = refs.map { it.name to distance(subj, it) }.sortedBy { it.second }

        return Comparison(
            subject = subj, reference = refs, roles = roleDeltas, manaSources = mana,
            avgMv = Py.round(subj.avgMv, 2) to Py.round(Py.sum(refs.map { it.avgMv }) / n, 2),
            curveDelta = curveDelta, missing = missing, unique = unique, nearest = nearest,
        )
    }

    /**
     * Per role, which cards the most lists play, sorted by effective cost and
     * then by popularity; plus the cards that fell through to `utility`.
     * Lands are skipped: the mana base is measured by [profile] instead.
     */
    fun rank(decks: List<DeckList>, pool: CardPool, roleOrder: List<String> = Roles.ROLES): Ranking {
        val out = roleOrder.associateWith { mutableListOf<RankRow>() }
        val n = decks.size
        if (n == 0) return Ranking(out, emptySet())
        val played = listsPlaying(decks, pool)
        val asked = decks.flatMap { it.cards.keys }.associateBy(pool::canonical)
        val low = sortedSetOf<String>()
        for (name in played.keys.sorted()) {
            val spelling = asked.getValue(name)
            val fact = pool.facts(spelling) ?: continue
            if (fact.isLand()) continue
            val cl = pool.classify(spelling)!!
            if (cl.lowConfidence) low += name
            for (role in cl.roles) {
                val rows = out[role] ?: continue
                rows += RankRow(
                    name = name, n = played.getValue(name), pct = Py.roundInt(100.0 * played.getValue(name) / n),
                    mv = cl.cost.effective, printed = cl.cost.printed, cost = fact.manaCost ?: "",
                    primary = cl.primary == role, reason = if (cl.cost.adjusted) cl.cost.reason else "", source = cl.source,
                )
            }
        }
        return Ranking(out.mapValues { (_, rows) -> rows.sortedWith(compareBy<RankRow>({ it.mv }, { -it.n }, { it.name })) }, low)
    }
}
