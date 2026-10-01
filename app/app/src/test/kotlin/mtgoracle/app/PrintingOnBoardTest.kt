package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A printing Forge lacks, chosen in the deck: the seat's own copies on the
 * board carry the key the art layer fetches from Scryfall, and the
 * opponent's copies of the same card keep Forge's art.
 */
class PrintingOnBoardTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `the seat's cards are drawn in the chosen printing, the opponent's are not`() {
        // A set Forge has never heard of: Forge plays its default Island, the board asks for Scryfall's art.
        val deck = AiCopy.asBuilt(Deck(1, "Island deck", null, null, listOf(DeckCard("Island", 60, false, false, setCode = "zzz", collectorNumber = "7"))))
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Island", "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=Island", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        Scenario("printing-on-board", state, seatDeck = deck) { _, _, _ -> null }.use { s ->
            s.playUntil { s.match.seat.board.value?.players?.all { p -> p.battlefield.any { it.name == "Island" } } == true }
            val mine = s.board.seat!!.battlefield.first { it.name == "Island" }
            val theirs = s.board.players.first { !it.isSeat }.battlefield.first { it.name == "Island" }
            assertEquals("printing:zzz/7/Island", mine.imageKey)
            assertTrue(theirs.imageKey?.startsWith("printing:") == false, "the opponent's Island keeps Forge's art: ${theirs.imageKey}")
        }
    }
}
