package mtgoracle.core

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.Section
import mtgoracle.core.deck.Substitution
import mtgoracle.core.deck.forgeCardName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same rules as the Python `forge export` (tests/test_forge.py covered that side until 2026-10). */
class AiCopyTest {
    private fun card(name: String, qty: Int = 1, side: Boolean = false, commander: Boolean = false, set: String? = null, cn: String? = null) =
        DeckCard(name, qty, commander, side, set, cn)

    private val rakdos = Deck(
        id = 784, name = "Rakdos Midrange", format = "canlander", folderName = "Canadian Highlander",
        cards = listOf(
            card("City of Traitors"), card("Gemstone Caverns"), card("Swamp", 8),
            card("Fable of the Mirror-Breaker // Reflection of Kiki-Jiki", set = "neo", cn = "141"),
            card("Deadpool, Trading Card"),
        ),
        substitutions = listOf(
            Substitution("city of traitors", "Barbarian Ring"),
            Substitution("Gemstone Caverns", "Swamp"),
            Substitution("Deadpool, Trading Card", "Harvester of Misery"),
            Substitution("Jeweled Lotus", "Mox Jet"), // no longer in the deck
        ),
    )

    @Test
    fun `forge names a multi-face card by its front face`() {
        assertEquals("Fire", forgeCardName("Fire // Ice"))
        assertEquals("Fable of the Mirror-Breaker", forgeCardName("Fable of the Mirror-Breaker // Reflection of Kiki-Jiki"))
        assertEquals("Lightning Bolt", forgeCardName("Lightning Bolt"))
    }

    @Test
    fun `the AI copy applies substitutions case-insensitively and merges onto basics`() {
        val copy = AiCopy.aiCopy(rakdos)!!
        assertEquals("Rakdos Midrange (AI)", copy.name)
        val byName = copy.cards.associate { it.forgeName to it.quantity }
        assertEquals(9, byName["Swamp"], "Gemstone Caverns -> Swamp merges into the 8 Swamps")
        assertEquals(1, byName["Barbarian Ring"])
        assertEquals(1, byName["Harvester of Misery"])
        assertTrue("City of Traitors" !in byName && "Deadpool, Trading Card" !in byName)
        assertEquals(3, copy.applied.size)
        assertEquals(listOf("substitution for Jeweled Lotus skipped: the card is no longer in the deck"), copy.notes)
    }

    @Test
    fun `the printing survives on unchanged rows and front-face naming`() {
        val fable = AiCopy.asBuilt(rakdos).cards.single { it.forgeName == "Fable of the Mirror-Breaker" }
        assertEquals("neo" to "141", fable.setCode to fable.collectorNumber)
    }

    @Test
    fun `no applicable substitution means no AI copy`() {
        assertNull(AiCopy.aiCopy(rakdos.copy(substitutions = listOf(Substitution("Jeweled Lotus", "Mox Jet")))))
        assertNull(AiCopy.aiCopy(rakdos.copy(substitutions = emptyList())))
    }

    @Test
    fun `a commander row makes it a Commander game and keeps its section`() {
        val deck = Deck(9, "Savra", "commander", "Commander", listOf(card("Savra, Queen of the Golgari", commander = true), card("Forest", 30)))
        assertEquals(GameType.COMMANDER, deck.gameType)
        assertEquals(Section.COMMANDER, AiCopy.asBuilt(deck).cards.first().section)
    }
}
