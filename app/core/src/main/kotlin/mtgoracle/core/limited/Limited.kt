package mtgoracle.core.limited

import kotlinx.serialization.Serializable
import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.PlayCard
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.deck.Section
import mtgoracle.core.deck.forgeCardName

/*
 * Limited: packs opened from a set, and the pool a deck is built from.
 * Forge opens the packs (forge/ForgeLimited.kt); the pool is ours, in
 * limited_pools / limited_pool_cards (data/PoolStore.kt). Plain data, so a
 * pool can cross a network table as it is.
 */

/**
 * A set Forge can open packs of. [code] is Forge's edition code (`BLB`),
 * [scryfallCode] how Scryfall and `printings` spell it (`blb`).
 */
@Serializable
data class LimitedSet(val code: String, val scryfallCode: String, val name: String, val released: String)

/** One card out of a pack: its name in `cards`, the printing it came in (Scryfall's spelling), and whether it was foil. */
@Serializable
data class PoolCard(val name: String, val setCode: String, val collectorNumber: String?, val foil: Boolean = false)

/**
 * What was opened: each pack in the order it was opened, and the [seed]
 * Forge's random numbers were seeded with. The cards are the record; the
 * seed is the proof, since the same Forge opens the same packs from it.
 */
@Serializable
data class OpenedPool(val set: LimitedSet, val seed: Long, val packs: List<List<PoolCard>>) {
    val cards: List<PoolCard> get() = packs.flatten()

    /** How many of each card, by name. */
    val counts: Map<String, Int> get() = cards.groupingBy { it.name }.eachCount()
}

/** A sealed event: six packs of the set's own booster, as Forge's sealed block of one set opens. */
object Sealed {
    const val PACKS = 6
    /** The `decks.format` of a deck built from a sealed pool. */
    const val FORMAT = "sealed"

    /** Basic lands are free at a limited table: the copy Forge plays has these in its sideboard, to swap in between games. */
    const val SIDEBOARD_BASICS = 10
    private val BASICS = listOf("Plains", "Island", "Swamp", "Mountain", "Forest")

    /** [deck] as Forge plays it: [SIDEBOARD_BASICS] of each basic land added to its sideboard (never stored in the deck). */
    fun withSideboardBasics(deck: PlayDeck): PlayDeck =
        deck.copy(cards = deck.cards + BASICS.map { PlayCard(it, SIDEBOARD_BASICS, Section.SIDEBOARD, setCode = null, collectorNumber = null) })

    /** The basic lands, by name: free at a limited table, as many as a deck wants. */
    val BASIC_LANDS: Set<String> = (BASICS + "Wastes").flatMap { listOf(it, "Snow-Covered $it") }.toSet()

    /** The least a limited deck's main deck holds. */
    const val MAIN_LEAST = 40

    /**
     * Why [deck] can't sit down as a deck of [pool], or null when it can:
     * a limited deck of [MAIN_LEAST] cards or more, no commander, and no card
     * beyond what the pool opened (main deck and sideboard together; basic
     * lands free). Names compare as Forge names them, the deck's way. The
     * same judgement on both sides of a network table.
     */
    fun judge(deck: PlayDeck, pool: OpenedPool): String? {
        if (deck.gameType != GameType.LIMITED) return "${deck.name} is a ${deck.gameType.label} deck, not a limited one."
        if (deck.cards.any { it.section == Section.COMMANDER }) return "${deck.name} has a commander, which a limited deck has not."
        val main = deck.cards.filter { it.section == Section.MAIN }.sumOf { it.quantity }
        if (main < MAIN_LEAST) return "${deck.name} has $main cards in its main deck, fewer than $MAIN_LEAST."
        val held = deck.cards.groupBy { forgeCardName(it.forgeName) }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }
        val opened = pool.cards.groupingBy { forgeCardName(it.name) }.eachCount()
        val excess = PoolRule.excess(opened, held) { it in BASIC_LANDS }
        return excess.takeIf { it.isNotEmpty() }?.let { "${deck.name} holds more than its pool opened: ${it.joinToString("; ")}." }
    }
}
