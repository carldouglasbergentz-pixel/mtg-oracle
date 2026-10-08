package mtgoracle.core.limited

import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.deck.Section
import kotlin.test.Test
import kotlin.test.assertEquals

class PoolRuleTest {
    private val basics = setOf("Forest", "Island")

    @Test
    fun `a deck may hold what was opened, and basic lands freely`() {
        val pool = mapOf("Lightning Bolt" to 2, "Counterspell" to 1)
        assertEquals(emptyList(), PoolRule.excess(pool, mapOf("Lightning Bolt" to 2, "Forest" to 17)) { it in basics })
    }

    @Test
    fun `more than was opened is named, with both counts, names compared ignoring case`() {
        val pool = mapOf("Lightning Bolt" to 1)
        val deck = mapOf("lightning bolt" to 1, "Lightning Bolt" to 1, "Sol Ring" to 1, "Island" to 9)
        assertEquals(
            listOf(PoolRule.Excess("lightning bolt", 2, 1), PoolRule.Excess("Sol Ring", 1, 0)),
            PoolRule.excess(pool, deck) { it in basics },
        )
    }

    @Test
    fun `a sealed deck is a limited game, a commander still makes a commander game`() {
        fun deck(format: String?, commander: Boolean = false) =
            Deck(1, "d", format, null, listOf(DeckCard("Ezuri", 1, isCommander = commander, isSideboard = false)))
        assertEquals(GameType.LIMITED, deck("sealed").gameType)
        assertEquals(GameType.LIMITED, deck("Limited").gameType)
        assertEquals(GameType.CONSTRUCTED, deck(null).gameType)
        assertEquals(GameType.COMMANDER, deck("sealed", commander = true).gameType)
    }

    @Test
    fun `the copy Forge plays has basic lands in its sideboard to swap in`() {
        val deck = PlayDeck(1, "d", GameType.LIMITED, emptyList(), isAiCopy = false, applied = emptyList(), notes = emptyList())
        val side = Sealed.withSideboardBasics(deck).cards.filter { it.section == Section.SIDEBOARD }
        assertEquals(listOf("Plains", "Island", "Swamp", "Mountain", "Forest"), side.map { it.forgeName })
        assertEquals(setOf(Sealed.SIDEBOARD_BASICS), side.map { it.quantity }.toSet())
    }
}
