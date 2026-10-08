package mtgoracle.data

import mtgoracle.core.analysis.Roles
import mtgoracle.core.lookup.CardTypes
import mtgoracle.core.lookup.LikeFeatures
import mtgoracle.core.lookup.Likeness

/**
 * `like:`'s cards: every card's functional oracle tags, weighted by their
 * rarity (Likeness), its kind, mana value and colours, read once when the
 * first `like:` asks and kept with the lookup (a sync builds the lookup
 * again). A query scores every card on what it does, kind, mana value and
 * colours, then the best [WORDED] of them on their rules text too.
 */
class LikeIndex(private val db: MtgDb) {
    private class Indexed(val cards: List<LikeFeatures>, val byName: Map<String, LikeFeatures>, val texts: Map<String, String?>)

    private val index: Indexed by lazy { load() }

    /** The last few answers: a search asks once for its cards and once for their order, and a page turn asks again. */
    private val recent = java.util.Collections.synchronizedMap(object : LinkedHashMap<Pair<String, Set<String>?>, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Set<String>?>, List<String>>) = size > 16
    })

    /**
     * The cards most like [name] (as `cards` names it), likest first, from
     * [among] (as `cards` names them) or every card: at most [LIMIT], none
     * less alike than [LEAST]. Not the card itself, nor its Alchemy
     * rebalance (`A-`), which only repeat it.
     */
    fun similar(name: String, among: Set<String>? = null): List<String> = recent.getOrPut(name.lowercase() to among) { rank(name, among) }

    private fun rank(name: String, among: Set<String>?): List<String> {
        val target = index.byName[name.lowercase()] ?: return emptyList()
        val same = setOf(target.name.lowercase(), "a-${target.name.lowercase()}", target.name.lowercase().removePrefix("a-"))
        val worded = { f: LikeFeatures -> f.copy(text = Likeness.textGrams(index.texts[f.name], f.name)) }
        val subject = worded(target)
        return index.cards.asSequence()
            .filter { (among == null || it.name in among) && it.name.lowercase() !in same }
            .map { it to Likeness.score(target, it) }
            .sortedByDescending { it.second }
            .take(WORDED)
            .map { (card, _) -> card.name to Likeness.score(subject, worded(card)) }
            .filter { it.second >= LEAST }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first.lowercase() })
            .take(LIMIT)
            .map { it.first }
            .toList()
    }

    private fun load(): Indexed = db.read { conn ->
        data class Tagging(val card: String, val tag: String, val strong: Boolean)
        val taggings = conn.query("SELECT card_name, tag, weight FROM card_oracle_tags") {
            Tagging(getString(1), getString(2).lowercase(), getString(3) == "very_strong")
        }.filter { Likeness.functional(it.tag) }
        val cards = conn.query("SELECT name, type_line, mana_value, colors, oracle_text FROM cards") {
            listOf(getString(1), getString(2), getObject(3)?.let { (it as Number).toDouble() }, getString(4), getString(5))
        }
        val carrying = taggings.groupBy { it.tag }.mapValues { (_, rows) -> rows.map { it.card.lowercase() }.toSet().size }
        val readsRole = carrying.keys.associateWith(Roles::readsRole)
        val tagsOf = taggings.groupBy { it.card.lowercase() }
        val features = cards.map { row ->
            val name = row[0] as String
            val tags = tagsOf[name.lowercase()].orEmpty().associate { t ->
                t.tag to Likeness.weight(carrying.getValue(t.tag), cards.size, t.strong, readsRole.getValue(t.tag))
            }
            LikeFeatures(
                name, tags, CardTypes.primary(row[1] as String?), row[2] as Double?,
                (row[3] as String?).orEmpty().split(',').filter { it.isNotBlank() }.toSet(), emptySet(),
            )
        }
        Indexed(features, features.associateBy { it.name.lowercase() }, cards.associate { (it[0] as String) to (it[4] as String?) })
    }

    companion object {
        /** The most cards a `like:` finds. */
        const val LIMIT = 200
        /** Scored on their words too: the likest by everything else. */
        const val WORDED = 600
        /** Less alike than this is no likeness. */
        const val LEAST = 0.25
    }
}
