package mtgoracle.ui.library

import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSize
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.deck.Substitution
import kotlin.test.Test
import kotlin.test.assertEquals

/** A deck's size and badges, said one way wherever the deck is shown. */
class DeckBadgeTest {
    private fun card(name: String, quantity: Int = 1, commander: Boolean = false, side: Boolean = false) = DeckCard(name, quantity, commander, side)

    @Test
    fun `a commander deck is its library plus its commanders, any other deck its cards`() {
        assertEquals("99 + commander", DeckSize(99, 1).label)
        assertEquals("98 + 2 commanders", DeckSize(98, 2).label)
        assertEquals("60 cards", DeckSize(60, 0).label)
        assertEquals("1 card", DeckSize(1, 0).label)
        assertEquals(listOf("99+1", "60"), listOf(DeckSize(99, 1).short, DeckSize(60, 0).short))
        // The library's list counts the commander in its total; the size takes it out again.
        assertEquals(DeckSize(99, 1), DeckSummary(1, "Savra", "commander", null, null, cardCount = 100, commanders = 1).size)
    }

    @Test
    fun `the library and the workspace draw one badge, the same parts in the same order`() {
        val deck = Deck(7, "Savra", "commander", null, listOf(card("Savra, Queen of the Golgari", commander = true), card("Forest", 99), card("Pithing Needle", side = true)),
            substitutions = listOf(Substitution("Viscera Seer", "Carrion Feeder")))
        assertEquals("EDH · 99 + commander · {B}{G} · AI copy: 1 substitution", deckBadge(deck, DeckBadges(identity = "{B}{G}")))
        assertEquals("EDH · 99 + commander · {B}{G}", deckBadge(deck, DeckBadges(identity = "{B}{G}"), aiCopy = false), "the workspace's AI copy tab counts them")
        val chl = Deck(8, "Blue Moon", "canadianhighlander", null, listOf(card("Island", 100)))
        assertEquals("CHL · 100 cards · 10/10 pts", deckBadge(chl, DeckBadges(points = "10/10 pts")))
        val sealed = Deck(9, "INV sealed", "sealed", null, listOf(card("Island", 40)), poolId = 3)
        assertEquals("sealed · 40 cards · pool 84", deckBadge(sealed, DeckBadges(), pool = "pool 84"))
    }
}
