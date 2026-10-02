package mtgoracle.core

import mtgoracle.core.deck.DeckParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A printing as export writes it reads back: every kind of collector number
 * the printings hold. `280†` once stayed in the name, so the card couldn't be
 * resolved and an import lost it (93 printings, all choosable in the art chooser).
 */
class PrintingTailTest {
    @Test
    fun `every kind of collector number survives export and import`() {
        val lines = listOf(
            "Savage Twister" to "280†", "Blaze" to "118†s", "Raise Dead" to "157★s", "Sol Ring" to "76★",
            "Shadowborn Apostle" to "681Φ", "Jaya Ballard, Task Mage" to "a1_2007", "Swamp" to "DDN-64", "Lightning Bolt" to "141",
        )
        for ((name, number) in lines) {
            val row = DeckParser.parse("1 $name (SET) $number").single()
            assertEquals(name to number, row.name to row.collectorNumber, "1 $name (SET) $number")
        }
        assertEquals("76★", DeckParser.parse("1 Sol Ring (C18) 76*").single().collectorNumber, "a trailing * is the star, as some sites write it")
    }
}
