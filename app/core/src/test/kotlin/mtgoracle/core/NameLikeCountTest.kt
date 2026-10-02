package mtgoracle.core

import mtgoracle.core.deck.DeckParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one card whose name starts with a number reads as a card, counted or not, and a count is still a count. */
class NameLikeCountTest {
    @Test
    fun `1996 World Champion is a card, not 1996 copies of World Champion`() {
        fun read(line: String) = DeckParser.parse(line).single().let { it.name to it.quantity }
        assertEquals("1996 World Champion" to 1, read("1996 World Champion"))
        assertEquals("1996 World Champion" to 2, read("2 1996 World Champion"))
        assertEquals("Island" to 12, read("12 Island"))
    }
}
