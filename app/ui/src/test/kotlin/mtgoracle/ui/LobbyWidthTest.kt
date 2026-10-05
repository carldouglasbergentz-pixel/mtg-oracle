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
 * The lobby: both decks chosen by a click in the left pane, the match's
 * options in the right, and their lines take the pane's measured width. The
 * setup screen before it once capped them at 110 columns, and the watch line
 * was cut off with half a 1920-wide window empty beside it.
 */
class LobbyWidthTest {
    private val me = DeckSummary(1, "UW Draw Go - Control", "canadianhighlander", null, null, 100)
    private val opponent = OpponentChoice(DeckSummary(2, "Rakdos Midrange", "canadianhighlander", null, null, 100), substitutions = 2)
    private val watchHelp = "an AI plays UW Draw Go - Control too, and you watch; hands stay hidden unless you press H"

    private val chosen = mutableListOf<String>()

    private fun render(width: Int, height: Int = 900, check: (OffscreenDriver) -> Unit) =
        OffscreenDriver(width, height) {
            LobbyScreen(listOf(me, opponent.deck), 1, listOf(opponent), 2, useAiCopy = true, watch = false,
                notes = listOf("Forge lacks 1 card of this deck (Sol Ring, printing C18), so the game will use another printing of it."),
                forgeReady = true, canStart = true, onSelectMe = { chosen += "me $it" }, onSelect = { chosen += "opponent $it" },
                onToggleAiCopy = {}, onToggleWatch = {}, onStart = {}, onLibrary = { chosen += "library" }, format = "best of 3")
        }.use { d -> d.settle(5); check(d) }

    @Test
    fun `both decks are chosen in the left pane, the match is set in the right, and Library leads back`() = render(1600) { d ->
        val mine = assertNotNull(d.registry[ClickTarget.Control("region:lobby-me")], "your deck's list")
        val theirs = assertNotNull(d.registry[ClickTarget.Control("region:lobby-opponent")], "the opponent's")
        val watch = assertNotNull(d.registry[ClickTarget.Control("watch")])
        assertTrue(theirs.top >= mine.bottom - 1, "the opponents are under your decks: $mine $theirs")
        assertTrue(watch.left >= mine.right, "the options are right of the lists: $watch")
        assertTrue(d.click(ClickTarget.Control("me:2")))
        assertTrue(d.click(ClickTarget.Control("opponent:2")))
        assertTrue(d.click(ClickTarget.Control("library")), "the toolbar's Library, as on the other screens")
        assertEquals(listOf("me 2", "opponent 2", "library"), chosen)
        d.savePng(File(pngDir, "lobby-1600.png"))
    }

    @Test
    fun `while Forge starts the lobby counts it, against how long it took last time`() =
        OffscreenDriver(1600, 900) {
            LobbyScreen(listOf(me, opponent.deck), 1, listOf(opponent), 2, useAiCopy = true, watch = false, notes = emptyList(),
                forgeReady = false, canStart = false, onSelectMe = {}, onSelect = {}, onToggleAiCopy = {}, onToggleWatch = {}, onStart = {}, onLibrary = {},
                forgeStartedAt = System.nanoTime() - 4_200_000_000, forgeExpectedMillis = 10_600)
        }.use { d ->
            d.settle(5)
            assertTrue("Forge is starting: 4 s of about 11 s; Start waits for it" in d.text.all(), d.text.all())
        }

    /** Everything on screen, with each line's trailing padding dropped. */
    private fun lines(d: OffscreenDriver) = d.text.all().lines().map { it.trimEnd() }

    @Test
    fun `at 1920 the watch option's help is shown in full on one line`() = render(1920) { d ->
        assertTrue(lines(d).any { watchHelp in it }, lines(d).filter { "watch" in it }.toString())
        assertFalse(lines(d).any { it.endsWith("…") }, "a line was cut: " + lines(d).filter { it.endsWith("…") })
        d.savePng(File(pngDir, "lobby-1920.png"))
    }

    @Test
    fun `at 1280 nothing is cut short of the pane's edge`() = render(1280) { d ->
        val watch = assertNotNull(d.registry[ClickTarget.Control("watch")])
        assertTrue(watch.right <= 1280f, "the watch option runs past the window: $watch")
        assertFalse(lines(d).any { it.endsWith("…") }, "a line was cut: " + lines(d).filter { it.endsWith("…") })
        // Whole or wrapped, it starts and ends where the text does.
        assertTrue(lines(d).any { "[ ] watch  (W)" in it }, lines(d).toString())
        assertTrue(lines(d).any { "an AI plays" in it } && lines(d).any { it.endsWith("press H") }, lines(d).toString())
        d.savePng(File(pngDir, "lobby-1280.png"))
    }

    @Test
    fun `in a narrow window the help lines wrap under their text instead of being cut`() = render(800) { d ->
        val oneRow = assertNotNull(d.registry[ClickTarget.Control("opponent:2")]).height
        val watch = assertNotNull(d.registry[ClickTarget.Control("watch")])
        assertTrue(watch.height >= 4 * oneRow - 1, "the watch help did not wrap: $watch")
        assertTrue(watch.right <= 800f, "the watch option runs past the window: $watch")
        assertTrue(lines(d).any { it.endsWith("press H") }, "the end of the watch help is missing")
        assertTrue(lines(d).any { it.endsWith("sideboarding between games") }, "the end of the match help is missing")
        // The help lines wrap; the status line of keys may be cut where the window ends, as everywhere.
        assertFalse(lines(d).any { it.endsWith("…") && ("you watch" in it || "best of 1 /" in it || "Forge lacks" in it || "AI vs AI" in it) }, "a help line was cut: " + lines(d).filter { it.endsWith("…") })
        // Continuation lines stay under the help's first word, not under the option.
        val first = lines(d).first { "an AI plays" in it }
        val continued = lines(d).first { it.endsWith("press H") }
        val at = first.indexOf("an AI plays")
        assertTrue(continued.length > at && continued[at] != ' ' && continued[at - 1] == ' ', "continuation not under the help: '$first' / '$continued'")
        d.savePng(File(pngDir, "lobby-800.png"))
    }
}
