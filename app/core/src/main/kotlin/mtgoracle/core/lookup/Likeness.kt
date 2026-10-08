package mtgoracle.core.lookup

/**
 * What a card does, as far as likeness goes: its functional oracle tags,
 * each weighted ([LikeFeatures.tags]), its kind, mana value and colours,
 * and its rules text as word pairs. Built by `data/LikeIndex.kt`.
 */
data class LikeFeatures(
    val name: String,
    val tags: Map<String, Double>,
    val type: String,
    val manaValue: Double?,
    val colors: Set<String>,
    val text: Set<String>,
) {
    val tagWeight: Double = tags.values.sum()
}

/**
 * `like:`: how much a card is like another. Scryfall Tagger's oracle tags
 * say what a card does (`mana dork`, `spot removal`), but also a good deal
 * of trivia about it (`cycle-lea-slush-art`, `alliteration`, `type errata`),
 * which says nothing about play: [functional] keeps the first. A tag counts
 * by how rare it is (`mana dork`, on 451 cards, says more than `activated
 * ability`, on 9,000), more when Tagger marks it strong and when the roles
 * read it as one ([weight]). Among cards that do the same, the same kind,
 * mana value, colours and words come first ([score]).
 */
object Likeness {
    /** Trivia: what a card is called, which cycle or printing it belongs to, how its type line reads. */
    private val TRIVIA_PREFIXES = listOf("cycle-", "supercycle-", "type errata", "real life", "maro-", "art-", "flavor", "reprint")
    private val TRIVIA = setOf(
        "alliteration", "anagram", "unique type line", "single english word name", "namesake spell", "has identical token",
        "legends retold", "pile card", "cheaper than mv", "more expensive than mv",
    )

    /** Whether [tag] says what the card does in a game, not what it is called or where it was printed. */
    fun functional(tag: String): Boolean {
        val t = tag.lowercase()
        return t !in TRIVIA && TRIVIA_PREFIXES.none { t.startsWith(it) }
    }

    /**
     * A functional tag's weight on a card: its rarity among [cards] cards
     * (on [carrying] of them), doubled when Tagger marks it `very_strong`,
     * half again when the roles read a role from it ([roleLabel]).
     */
    fun weight(carrying: Int, cards: Int, strong: Boolean, roleLabel: Boolean): Double {
        val rarity = kotlin.math.ln((cards + 1.0) / (carrying + 1.0))
        return rarity * (if (strong) 2.0 else 1.0) * (if (roleLabel) 1.5 else 1.0)
    }

    /** [target]'s likeness in [candidate], 0 to 1: what they do together first, then kind, words, mana value and colours. */
    fun score(target: LikeFeatures, candidate: LikeFeatures): Double {
        val shared = target.tags.entries.sumOf { (tag, w) -> if (tag in candidate.tags) minOf(w, candidate.tags.getValue(tag)) else 0.0 }
        val union = target.tagWeight + candidate.tagWeight - shared
        val tags = if (union > 0) shared / union else 0.0
        val text = jaccard(target.text, candidate.text)
        val kind = if (target.type == candidate.type) 1.0 else 0.0
        val mv = if (target.manaValue != null && candidate.manaValue != null) 1.0 / (1.0 + kotlin.math.abs(target.manaValue - candidate.manaValue)) else 0.0
        val colors = if (target.colors.isEmpty() && candidate.colors.isEmpty()) 1.0 else jaccard(target.colors, candidate.colors)
        return 0.55 * tags + 0.20 * text + 0.10 * kind + 0.10 * mv + 0.05 * colors
    }

    /**
     * [oracleText] as its word pairs, the card's own [name] made `~` (as the
     * rules write it), reminder text and punctuation out: what two cards
     * that do the same in the same words have in common.
     */
    fun textGrams(oracleText: String?, name: String): Set<String> {
        if (oracleText.isNullOrBlank()) return emptySet()
        var text = oracleText.lowercase()
        name.split(" // ").forEach { face -> if (face.isNotBlank()) text = text.replace(face.lowercase(), "~") }
        text = text.replace(Regex("\\([^)]*\\)"), " ")
        val words = text.split(Regex("[^a-z0-9{}~+\\-/]+")).filter { it.isNotBlank() }
        return words.zipWithNext { a, b -> "$a $b" }.toSet()
    }

    private fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val both = a.count { it in b }
        return both.toDouble() / (a.size + b.size - both)
    }
}
