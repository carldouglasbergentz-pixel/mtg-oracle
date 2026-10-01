package mtgoracle.core

import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.GameType
import kotlin.test.Test
import kotlin.test.assertEquals

/** A commander row makes a commander game; the format says which one. */
class GameTypeTest {
    private fun deck(format: String?, commander: Boolean) =
        Deck(1, "d", format, null, listOfNotNull(DeckCard("Island", 59, false, false), DeckCard("Isamaru, Hound of Konda", 1, true, false).takeIf { commander }))

    @Test
    fun `duel and its aliases are Duel Commander, other commander decks Commander`() {
        for (format in listOf("duel", "Duel Commander", "1v1 commander", "DC")) assertEquals(GameType.DUEL_COMMANDER, deck(format, true).gameType, format)
        for (format in listOf("commander", "EDH", null, "tlr")) assertEquals(GameType.COMMANDER, deck(format, true).gameType, "$format")
    }

    @Test
    fun `without a commander row the format doesn't matter`() {
        assertEquals(GameType.CONSTRUCTED, deck("duel", false).gameType)
        assertEquals(GameType.CONSTRUCTED, deck("modern", false).gameType)
    }
}
