package mtgoracle.core

import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckExport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A deck as MTG Arena's Import button and Magic Online's `.txt` import read
 * it: Arena with `Commander` / `Deck` / `Sideboard` and `Fire // Ice`; MTGO
 * with no headers, the commander in the sideboard and `Fire/Ice`. Neither
 * takes a printing, and a two-faced card is its front face in both.
 */
class ExportFormatsTest {
    private val layouts = mapOf(
        "Fire // Ice" to "split", "Delver of Secrets // Insectile Aberration" to "transform",
        "Bonecrusher Giant // Stomp" to "adventure", "Sol Ring" to "normal",
    )
    private val deck = Deck(1, "Test", "commander", null, listOf(
        DeckCard("Kediss, Emberclaw Familiar", 1, isCommander = true, isSideboard = false),
        DeckCard("Krark, the Thumbless", 1, isCommander = true, isSideboard = false),
        DeckCard("Sol Ring", 1, isCommander = false, isSideboard = false, setCode = "c18", collectorNumber = "263"),
        DeckCard("Fire // Ice", 2, isCommander = false, isSideboard = false),
        DeckCard("Delver of Secrets // Insectile Aberration", 4, isCommander = false, isSideboard = false),
        DeckCard("Bonecrusher Giant // Stomp", 1, isCommander = false, isSideboard = false),
        DeckCard("Pyroblast", 3, isCommander = false, isSideboard = true),
    ))

    @Test
    fun `Arena - sections, split cards whole, other faces front, no printings`() {
        assertEquals(
            """
            Commander
            1 Kediss, Emberclaw Familiar
            1 Krark, the Thumbless

            Deck
            1 Bonecrusher Giant
            4 Delver of Secrets
            2 Fire // Ice
            1 Sol Ring

            Sideboard
            3 Pyroblast

            """.trimIndent(),
            DeckExport.arena(deck, layouts::get),
        )
    }

    @Test
    fun `MTGO - main, a blank line, the commanders then the sideboard, split cards with one slash`() {
        assertEquals(
            """
            1 Bonecrusher Giant
            4 Delver of Secrets
            2 Fire/Ice
            1 Sol Ring

            1 Kediss, Emberclaw Familiar
            1 Krark, the Thumbless
            3 Pyroblast

            """.trimIndent(),
            DeckExport.mtgo(deck, layouts::get),
        )
    }

    @Test
    fun `a deck with only a main deck has no blank line, and an empty deck is empty`() {
        val main = deck.copy(cards = deck.cards.filter { !it.isCommander && !it.isSideboard })
        assertEquals("1 Bonecrusher Giant\n4 Delver of Secrets\n2 Fire/Ice\n1 Sol Ring\n", DeckExport.mtgo(main, layouts::get))
        assertEquals("", DeckExport.arena(deck.copy(cards = emptyList()), layouts::get))
    }
}
