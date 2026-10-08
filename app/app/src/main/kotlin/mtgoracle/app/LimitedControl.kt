package mtgoracle.app

import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckRules
import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.ParsedRow
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.deck.Section
import mtgoracle.core.limited.LimitedSet
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.PoolRule
import mtgoracle.core.limited.Sealed
import mtgoracle.data.CardNames
import mtgoracle.data.DeckWriter
import mtgoracle.data.Library
import mtgoracle.data.LibraryWriter
import mtgoracle.data.PoolStore
import mtgoracle.forge.ForgeLimited
import mtgoracle.forge.ForgeRuntime
import java.security.SecureRandom
import java.time.LocalDate

/**
 * Limited against the AI: a sealed event opens your packs and the AI's, of
 * one set, and makes your deck in the `Limited` folder with the whole pool
 * in its sideboard. The AI's pool is stored, never shown: its deck is built
 * from it by Forge's AI when a match starts.
 */
class LimitedControl(
    private val library: () -> Library,
    private val libraryWriter: () -> LibraryWriter,
    private val writer: () -> DeckWriter?,
    private val names: () -> CardNames?,
    private val pools: () -> PoolStore,
) {
    private val random = SecureRandom()

    /** What a sealed event made: your deck, and its name. */
    data class Event(val deckId: Int, val name: String, val pool: OpenedPool)

    /** Opens [Sealed.PACKS] packs of [set] for you and as many for the AI; the AI's deck is built later, from its pool. */
    fun openSealed(set: LimitedSet): Event {
        val writer = writer() ?: error("the database is still opening")
        val names = names() ?: error("the database is still opening")
        val mine = resolved(ForgeLimited.open(set, Sealed.PACKS, random.nextLong()), names)
        val ai = resolved(ForgeLimited.open(set, Sealed.PACKS, random.nextLong()), names)
        val folder = folderId()
        val name = freeName("${set.code} sealed ${LocalDate.now()}", folder)
        val id = writer.createFromPool(name, folder, Sealed.FORMAT, "sealed", mine, ai, rivalOpenedBy = "ai", forgeVersion = ForgeRuntime.version)
        return Event(id, name, mine)
    }

    /** The AI's deck against [deck], built from the pool opened against its own; null when it has none. */
    fun opponentFor(deck: Deck): PlayDeck? {
        val poolId = deck.poolId ?: return null
        val rivalId = pools().pool(poolId)?.rivalPoolId ?: return null
        val rival = pools().pool(rivalId) ?: return null
        val cards = ForgeLimited.build(rival.pool)
        return PlayDeck(PlayDeck.UNSTORED, "AI (${rival.pool.set.code} sealed)", GameType.LIMITED, cards, isAiCopy = false, applied = emptyList(), notes = emptyList())
    }

    /** The cards in [deck] beyond what its pool opened (basic lands are free); empty for a deck with no pool. */
    fun excess(deck: Deck): List<PoolRule.Excess> {
        val poolId = deck.poolId ?: return emptyList()
        val pool = pools().pool(poolId) ?: return emptyList()
        val held = deck.cards.groupBy { it.name }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }
        val basic = deck.cards.associate { it.name to DeckRules.isBasicLand(it.info?.typeLine) }
        return PoolRule.excess(pool.pool.counts, held) { basic[it] == true }
    }

    /** [deck]'s pool as its title says it: `pool 84`, and `3 beyond the pool!` when it holds more than was opened; null for none. */
    fun poolNote(deck: Deck): String? {
        val poolId = deck.poolId ?: return null
        val opened = pools().pool(poolId)?.pool?.cards?.size ?: return "pool gone!"
        val beyond = excess(deck).sumOf { it.held - it.opened }
        return "pool $opened" + if (beyond > 0) " · $beyond beyond the pool!" else ""
    }

    /**
     * The deck Forge's AI would build from [deck]'s pool, as a list to
     * replace the deck with (`DeckWriter.replace`, shown first as a dry run):
     * forty in the main deck, the rest of the pool in the sideboard.
     */
    fun suggestion(deck: Deck): List<ParsedRow> {
        val poolId = deck.poolId ?: error("${deck.name} is built from no pool")
        val pool = pools().pool(poolId) ?: error("${deck.name}'s pool is gone")
        val names = names() ?: error("the database is still opening")
        return ForgeLimited.build(pool.pool).map { card ->
            val name = names.resolve(card.forgeName) ?: error("the database lacks ${card.forgeName}")
            ParsedRow(name, card.quantity, if (card.section == Section.MAIN) "main" else "sideboard", card.setCode, card.collectorNumber)
        }
    }

    /** Forge's names (a two-faced card by its front face) as `cards` names them; a card the database lacks stops the event, said by name. */
    private fun resolved(pool: OpenedPool, names: CardNames): OpenedPool {
        val unknown = pool.cards.map { it.name }.filter { names.resolve(it) == null }.distinct()
        if (unknown.isNotEmpty()) error("the database lacks ${unknown.joinToString()} from ${pool.set.name}: a sync may bring them")
        return pool.copy(packs = pool.packs.map { pack -> pack.map { it.copy(name = names.resolve(it.name)!!) } })
    }

    private fun folderId(): Int =
        library().folders().firstOrNull { it.name.equals(FOLDER, ignoreCase = true) }?.id ?: libraryWriter().createFolder(FOLDER)

    private fun freeName(base: String, folder: Int): String {
        val taken = library().decks().filter { it.folderId == folder }.map { it.name.lowercase() }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it.lowercase() !in taken }
    }

    companion object {
        /** Where limited decks go. */
        const val FOLDER = "Limited"
    }
}
