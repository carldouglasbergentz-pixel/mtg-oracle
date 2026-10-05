package mtgoracle.core.deck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A card Forge's AI won't play is flagged until the AI copy has a substitute, and then the flag says what it plays. */
class AiFlagTest {
    private fun card(name: String) = DeckCard(name, quantity = 1, isCommander = false, isSideboard = false)

    @Test
    fun `the flagged cards of a deck, with their substitutes`() {
        val deck = Deck(1, "Blue Moon", null, null, listOf(card("Gitaxian Probe"), card("Island"), card("Unknown Card")),
            substitutions = listOf(Substitution("gitaxian probe", "Opt")))
        val flags = AiFlag.of(deck) { name ->
            when (name) { "Gitaxian Probe" -> AiFlag.Kind.AI_WONT_PLAY; "Unknown Card" -> AiFlag.Kind.FORGE_LACKS; else -> null }
        }
        assertEquals(setOf("Gitaxian Probe", "Unknown Card"), flags.keys)
        assertEquals("AI plays Opt", flags.getValue("Gitaxian Probe").label, "a substitute matched by name, any case")
        assertEquals("[→]", flags.getValue("Gitaxian Probe").button)
        assertTrue(!flags.getValue("Gitaxian Probe").open)
        assertEquals("not in Forge", flags.getValue("Unknown Card").label)
        assertEquals("[!]", flags.getValue("Unknown Card").button)
        assertTrue("[!] beside it" in flags.getValue("Unknown Card").explanation, "it says where to fix it")
    }
}
