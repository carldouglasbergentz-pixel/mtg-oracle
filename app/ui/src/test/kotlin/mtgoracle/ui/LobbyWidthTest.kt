package mtgoracle.ui

import mtgoracle.core.deck.DeckSummary
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.library.OpponentChoice
import mtgoracle.ui.library.LobbyScreen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lobby: your deck and the AI's in two lists, chosen by a click, and the
 * match's lines take the pane's measured width. The setup screen before it
 * once capped them at 110 columns, and the watch line was cut off with half a
 * 1920-wide window empty beside it.
 */
class LobbyWidthTest {
    private val me = DeckSummary(1, "UW Draw Go - Control", "canadianhighlander", null, null, 100)
    private val opponent = OpponentChoice(DeckSummary(2, "Rakdos Midrange", "canadianhighlander", null, null, 100), substitutions = 2)
    private val watchLine = "[ ] watch: an AI plays UW Draw Go - Control too, and you watch (W toggles; hands stay hidden unless you press H)"

    private val chosen = mutableListOf<String>()

    private fun render(width: Int, height: Int = 900, check: (OffscreenDriver) -> Unit) =
        OffscreenDriver(width, height) {
            LobbyScreen(listOf(me, opponent.deck), 1, listOf(opponent), 2, useAiCopy = true, watch = false,
                notes = listOf("Forge lacks 1 card of this deck (Sol Ring, printing C18), so the game will use another printing of it."),
                forgeReady = true, canStart = true, onSelectMe = { chosen += "me $it" }, onSelect = { chosen += "opponent $it" },
                onToggleAiCopy = {}, onToggleWatch = {}, onStart = {}, onBack = {}, format = "best of 3")
        }.use { d -> d.settle(5); check(d) }

    @Test
    fun `both decks are chosen here, by a click in either list`() = render(1600) { d ->
        assertNotNull(d.registry[ClickTarget.Control("region:lobby-me")], "your deck's list")
        assertNotNull(d.registry[ClickTarget.Control("region:lobby-opponent")], "the opponent's")
        assertTrue(d.click(ClickTarget.Control("me:2")))
        assertTrue(d.click(ClickTarget.Control("opponent:2")))
        assertEquals(listOf("me 2", "opponent 2"), chosen)
        d.savePng(File(pngDir, "lobby-1600.png"))
    }

    /** Everything on screen, with each line's trailing padding dropped. */
    private fun lines(d: OffscreenDriver) = d.text.all().lines().map { it.trimEnd() }

    @Test
    fun `at 1920 the watch line is shown in full on one line`() = render(1920) { d ->
        assertTrue(lines(d).any { it.trim() == watchLine }, lines(d).filter { "watch" in it }.toString())
        assertFalse(lines(d).any { it.endsWith("…") }, "a line was cut: " + lines(d).filter { it.endsWith("…") })
        d.savePng(File(pngDir, "lobby-1920.png"))
    }

    @Test
    fun `at 1280 nothing is cut short of the pane's edge`() = render(1280) { d ->
        val watch = assertNotNull(d.registry[ClickTarget.Control("watch")])
        assertTrue(watch.right <= 1280f, "the watch line runs past the window: $watch")
        assertFalse(lines(d).any { it.endsWith("…") }, "a line was cut: " + lines(d).filter { it.endsWith("…") })
        // Whole or wrapped, it starts and ends where the text does.
        val shown = lines(d).map { it.trim() }
        assertTrue(shown.any { it.startsWith("[ ] watch: an AI plays") }, shown.toString())
        assertTrue(shown.any { it.endsWith("press H)") }, shown.toString())
        d.savePng(File(pngDir, "lobby-1280.png"))
    }

    @Test
    fun `in a narrow window the help lines wrap under their text instead of being cut`() = render(800) { d ->
        val watch = assertNotNull(d.registry[ClickTarget.Control("watch")])
        val oneRow = assertNotNull(d.registry[ClickTarget.Control("opponent:2")]).height
        assertTrue(watch.height >= 2 * oneRow - 1, "the watch line did not wrap: $watch")
        assertTrue(watch.right <= 800f, "the watch line runs past the window: $watch")
        val shown = lines(d).map { it.trim() }
        assertTrue(shown.any { it.endsWith("press H)") }, "the end of the watch line is missing: $shown")
        assertTrue(shown.any { it.endsWith("sideboarding between games)") }, "the end of the match line is missing: $shown")
        // The help lines wrap; the status line of keys may be cut where the window ends, as everywhere.
        assertFalse(lines(d).any { it.endsWith("…") && ("watch:" in it || "match:" in it || "Forge lacks" in it) }, "a help line was cut: " + lines(d).filter { it.endsWith("…") })
        // Continuation lines hang under the text, not under the checkbox.
        val continued = lines(d).first { it.trim().endsWith("press H)") }
        assertTrue(continued.startsWith("      "), "continuation not indented: '$continued'")
        d.savePng(File(pngDir, "lobby-800.png"))
    }
}
