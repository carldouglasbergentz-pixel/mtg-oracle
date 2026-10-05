package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.LogCard
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.logRows
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log pane holds the whole match and follows its newest line; a card it
 * names is bold and shows in the zoom pane on a hover, even once it has left
 * the table.
 */
class LogPaneTest {
    private val recall = card(0, "Ancestral Recall").copy(text = "Target player draws three cards.", typeLine = "Instant")
    private fun line(seq: Long, kind: LogKind, text: String, vararg cards: LogCard) = LogLine(seq, kind, text, cards.toList())

    @Test
    fun `a wrapped line keeps its cards where they fall, split ones in both rows`() {
        val text = "You cast Ancestral Recall targeting [You]"
        val l = line(0, LogKind.CAST, text, LogCard(9, 25, "Ancestral Recall", card = recall))
        val rows = logRows(l, 20)
        assertEquals(listOf("You cast Ancestral", "  Recall targeting", "  [You]"), rows.map { it.text })
        assertEquals("Ancestral", rows[0].text.substring(rows[0].spans.single().start, rows[0].spans.single().end))
        assertEquals("Recall", rows[1].text.substring(rows[1].spans.single().start, rows[1].spans.single().end))
        assertEquals(listOf(0), rows[1].spans.map { it.card }, "both pieces are the line's first card")
        assertEquals(listOf("x"), logRows(line(1, LogKind.OTHER, "x"), 20).map { it.text })
        // Forge's several lines in one (a block per attacker) are rows of their own, nothing lost.
        val blocks = line(2, LogKind.COMBAT, "You blocked Bears.\nYou blocked Giant.", LogCard(31, 36, "Giant"))
        val shown = logRows(blocks, 40)
        assertEquals(listOf("You blocked Bears.", "  You blocked Giant."), shown.map { it.text })
        assertEquals("Giant", shown[1].text.substring(shown[1].spans.single().start, shown[1].spans.single().end))
    }

    @Test
    fun `the whole match is there, the newest line in view, and a named card zooms`() {
        val many = (0L until 300L).map { line(it, LogKind.PHASE, "Your Upkeep step $it") }
        val last = line(300, LogKind.DISCARD, "AI discards Ancestral Recall.", LogCard(12, 28, "Ancestral Recall", card = recall))
        val seat = FakeSeat(quietBoard().copy(log = many), null)
        val art = ArtImages(FakeArt(File(pngDir, "fake-art")), decoder = { it.run() })
        OffscreenDriver(1600, 900) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) }
        }.use { d ->
            d.settle(5)
            assertTrue("Your Upkeep step 299" in d.text.all(), "the newest line is in view")
            assertFalse("Your Upkeep step 0\n" in d.text.all() + "\n", "the oldest has scrolled out")
            seat.board.value = seat.board.value!!.copy(log = many + last)
            d.settle(5)
            assertTrue("AI discards Ancestral Recall." in d.text.all(), "it follows a new line")
            assertFalse("draws three cards" in d.text.all(), "the zoom pane shows something else first")
            val name = d.registry[ClickTarget.LogCard(300, 0, 12)]!!
            val pane = d.registry[ClickTarget.Control("region:log")]!!
            val cell = name.width / "Ancestral Recall".length
            assertTrue(kotlin.math.abs(name.left - (pane.left + cell * (1 + 12))) < cell / 2, "the target lies over the name, 12 columns in: $name in $pane")
            assertTrue(d.hover(ClickTarget.LogCard(300, 0, 12)), "the name is a hover target")
            d.settle(3)
            assertTrue("draws three cards" in d.text.all(), "hovering the name zooms the card as printed")
            d.savePng(File(pngDir, "log-pane.png"))
            // L: only what happened. The steps go, the events stay, and the pane says it is filtered.
            d.key(androidx.compose.ui.input.key.Key.L)
            d.settle(3)
            assertFalse("Your Upkeep step 299" in d.text.all(), "the steps are hidden")
            assertTrue("AI discards Ancestral Recall." in d.text.all() && "events only" in d.text.all())
            d.key(androidx.compose.ui.input.key.Key.L)
            d.settle(3)
            assertTrue("Your Upkeep step 299" in d.text.all(), "and back")
        }
    }
}
