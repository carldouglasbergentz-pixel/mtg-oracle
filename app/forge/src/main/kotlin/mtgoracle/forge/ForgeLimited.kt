package mtgoracle.forge

import forge.StaticData
import forge.card.CardEdition
import forge.gamemodes.limited.SealedDeckBuilder
import forge.item.PaperCard
import forge.item.generation.BoosterGenerator
import forge.util.MyRandom
import mtgoracle.core.deck.PlayCard
import mtgoracle.core.deck.Section
import mtgoracle.core.limited.LimitedSet
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.PoolCard
import java.text.SimpleDateFormat
import java.util.Random

/**
 * Forge's packs and its AI's limited deck building. A pack is the set's own
 * booster as its edition file describes it (`Booster=`, which for the newer
 * sets is the play booster), opened by Forge's BoosterGenerator.
 *
 * Both draw from Forge's one random number generator (MyRandom), which a
 * game also draws from. So they run only while no game is on, under a
 * generator seeded for the call and put back after it: the same seed opens
 * the same packs, given the same Forge.
 */
object ForgeLimited {

    /** Every set Forge opens packs of, newest first: not the digital-only sets (Alchemy, MTGO's), not the Un-sets, whose cards Forge mostly lacks. */
    val sets: List<LimitedSet> by lazy {
        val dates = SimpleDateFormat("yyyy-MM-dd")
        StaticData.instance().editions
            .filter { it.hasBoosterTemplate() && it.type != CardEdition.Type.ONLINE && it.type != CardEdition.Type.FUNNY }
            .sortedWith(compareByDescending<CardEdition> { it.date }.thenBy { it.code })
            .map { LimitedSet(it.code, ForgeCards.scryfallCode(it), it.name, it.date?.let(dates::format).orEmpty()) }
    }

    /** The set Forge calls [code] (`BLB`), or one Scryfall calls so (`blb`); null when Forge opens no packs of it. */
    fun set(code: String): LimitedSet? =
        sets.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: sets.firstOrNull { it.scryfallCode.equals(code, ignoreCase = true) }

    /** [packs] boosters of [set], opened from [seed]. Card names are Forge's (a two-faced card by its front face). */
    fun open(set: LimitedSet, packs: Int, seed: Long): OpenedPool {
        val template = StaticData.instance().boosters.get(set.code) ?: throw IllegalArgumentException("Forge opens no packs of ${set.name} (${set.code})")
        val opened = seeded(seed) { (1..packs).map { BoosterGenerator.getBoosterPack(template).map(::poolCard) } }
        return OpenedPool(set, seed, opened)
    }

    /**
     * What this app's Forge opens for [set]: [packs] boosters from a fixed
     * seed, hashed. Two apps whose digests differ open other packs from the
     * same seed, and can't check each other's pools at a sealed table.
     */
    fun packsDigest(set: LimitedSet, packs: Int): String {
        val pool = open(set, packs, seed = 0)
        val text = pool.packs.joinToString("\n") { pack -> pack.joinToString("|") { "${it.name}/${it.setCode}/${it.collectorNumber}/${it.foil}" } }
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * The deck Forge's AI builds from [pool] (its SealedDeckBuilder): forty
     * cards in the main deck, the rest of the pool in the sideboard, basic
     * lands from the pool's set when it has them. Seeded, so the same pool
     * builds the same deck.
     */
    fun build(pool: OpenedPool): List<PlayCard> {
        val cards = pool.cards.map { card ->
            ForgeCards.paperCard(card.name, card.setCode, card.collectorNumber) ?: throw IllegalArgumentException("Forge lacks ${card.name}, from the pool")
        }
        val edition = StaticData.instance().editions.get(pool.set.code)
        val landSet = edition?.takeIf { it.hasBasicLands() }?.code
        // The builder takes out of the list it is given the cards it won't play: it gets a copy.
        val deck = seeded(pool.seed) { SealedDeckBuilder(cards.toMutableList()).buildDeck(landSet) }
        val main = deck.main.map { (card, count) -> playCard(card, count, Section.MAIN) }
        // The rest of the pool, as it was opened: the builder's own sideboard drops cards it won't play (Arcane Flight).
        val left = main.groupBy { it.forgeName }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }.toMutableMap()
        val side = pool.cards.filter { card -> (left[card.name] ?: 0).let { if (it > 0) { left[card.name] = it - 1; false } else true } }
            .groupBy { Triple(it.name, it.setCode, it.collectorNumber) }
            .map { (key, copies) -> PlayCard(key.first, copies.size, Section.SIDEBOARD, key.second, key.third) }
        return main + side
    }

    private fun <T> seeded(seed: Long, block: () -> T): T = synchronized(this) {
        check(!ForgeRuntime.busy) { "a game is on: packs are opened only between games, since a game draws from the same random numbers" }
        val previous = MyRandom.getRandom()
        MyRandom.setRandom(Random(seed))
        try {
            block()
        } finally {
            MyRandom.setRandom(previous)
        }
    }

    private fun poolCard(card: PaperCard): PoolCard = PoolCard(card.name, printingSet(card), card.collectorNumber?.takeIf { it.isNotBlank() }, card.isFoil)

    private fun playCard(card: PaperCard, count: Int, section: Section) =
        PlayCard(card.name, count, section, printingSet(card), card.collectorNumber?.takeIf { it.isNotBlank() })

    /** The card's printing as Scryfall spells its set (`blb`, `spg` for a special guest). */
    private fun printingSet(card: PaperCard): String =
        StaticData.instance().editions.get(card.edition)?.let(ForgeCards::scryfallCode) ?: card.edition.lowercase()
}
