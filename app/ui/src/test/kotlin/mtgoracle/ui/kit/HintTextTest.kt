package mtgoracle.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hint lines and cut text: whole parts, no part left dangling. */
class HintTextTest {
    @Test
    fun `a wrapped hint line breaks between its parts, never on a lone separator`() {
        val lines = wrap("↑↓ or the mouse: shown at once · Enter or a click: keep · Esc: back", 36)
        assertEquals(listOf("↑↓ or the mouse: shown at once", "Enter or a click: keep · Esc: back"), lines)
        assertTrue(lines.none { it.trimEnd().endsWith("·") || it.trimStart().startsWith("·") }, "$lines")
        assertEquals(listOf("main 100 (at least 60)", "sideboard 15"), wrap("main 100 (at least 60) · sideboard 15", 24))
        assertEquals(listOf("a · b"), wrap("a · b", 10), "what fits stays as written")
        assertEquals(lines, wrapHanging("↑↓ or the mouse: shown at once · Enter or a click: keep · Esc: back", 36), "WrapText's wrap, too")
    }

    @Test
    fun `the status line drops whole hints from the end, never half of one`() {
        val hints = listOf(": command", "Tab deck/output", "Ctrl+=/- size")
        assertEquals(": command  Tab deck/output  Ctrl+=/- size", wholeHints(hints, 80))
        assertEquals(": command  Tab deck/output", wholeHints(hints, 35))
        assertEquals("", wholeHints(hints, 5))
    }

    @Test
    fun `a pane's badge leaves its title half the edge, and drops whole parts to fit`() {
        // 58 cells, the workspace's deck pane: the deck's name keeps its 27, the badge loses its colours.
        assertEquals("EDH · 99 + commander", badgeOf("EDH · 99 + commander · {B}{G}", 58, "Savra, Queen of the Golgari"))
        assertEquals("EDH · 99 + commander · {B}{G}", badgeOf("EDH · 99 + commander · {B}{G}", 120, "Savra, Queen of the Golgari"))
        assertEquals("(3) AI!", badgeOf("(3) AI!", 18, "Tamiyo, Inquisitive Student"), "a frame's mark stays whole")
    }

    @Test
    fun `text cut to its rows ends in an ellipsis, and text that fits is whole`() {
        val text = "Flying. When this creature enters, draw a card. At the beginning of your upkeep, sacrifice it unless you pay its cost."
        val cut = cutText(text, 20, 3)
        assertEquals(3, cut.size)
        assertTrue(cut.last().trimEnd().endsWith("…"), "$cut")
        assertTrue(cut.all { it.length == 20 }, "$cut")
        assertEquals(listOf("Flying.".padEnd(20)), cutText("Flying.", 20, 3))
    }
}
