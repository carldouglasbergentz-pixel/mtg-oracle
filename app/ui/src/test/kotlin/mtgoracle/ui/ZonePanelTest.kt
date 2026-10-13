package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A graveyard or exile pops out over the table from its zone column's
 * header: every card in its frame, clickable and hoverable as on the table,
 * dragged by its top edge to a place that is kept, closed by its `×`. It
 * shows what the snapshot holds: a face-down exiled card stays a back.
 */
class ZonePanelTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    @Test
    fun `the graveyard pops out, card by card, moves where it is dragged, and closes`() {
        val board = sampleBoard()
        val me = board.players.first { it.isSeat }
        val them = board.players.first { !it.isSeat }
        assertTrue(me.graveyard.size >= 2, "the sample has a graveyard: ${me.graveyard}")
        val seat = FakeSeat(board, null)
        var layout: BoardLayout? = null
        OffscreenDriver(1800, 1200) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART, onLayoutChange = { layout = it }) }
        }.use { d ->
            d.settle(5)
            assertNull(d.registry[ClickTarget.Control("region:zone-panel-near-graveyard")])
            assertTrue(d.click(ClickTarget.Control("zone-panel:near-graveyard")), "the header opens it")
            d.settle(3)
            val panel = assertNotNull(d.registry[ClickTarget.Control("region:zone-panel-near-graveyard")], "the panel is up")
            for (card in me.graveyard) {
                val at = d.registry.targets.filterIsInstance<ClickTarget.Card>().filter { it.id == card.id }.mapNotNull { d.registry[it] }
                assertTrue(at.any { panel.contains(it.center) }, "${card.name} is in the panel, in its frame: $at in $panel")
            }
            assertTrue("graveyard ${me.graveyard.size}" in d.text.all(), d.text.all())
            d.savePng(File(pngDir, "zone-panel-graveyard.png"))

            assertTrue(d.drag(ClickTarget.Control("region:zone-panel-near-graveyard-handle"), Offset(140f, -160f)))
            d.settle(2)
            val moved = assertNotNull(layout?.panels?.get("near-graveyard"), "the place is kept: $layout")
            val after = assertNotNull(d.registry[ClickTarget.Control("region:zone-panel-near-graveyard")])
            assertTrue(after.left > panel.left && after.top < panel.top, "it went where it was dragged: $panel -> $after ($moved)")

            assertTrue(d.click(ClickTarget.Control("zone-panel-close:near-graveyard")), "the × closes it")
            d.settle(2)
            assertNull(d.registry[ClickTarget.Control("region:zone-panel-near-graveyard")])

            // Theirs too; a face-down exiled card is a back, its name nowhere.
            assertTrue(d.click(ClickTarget.Control("zone-panel:far-exile")))
            d.settle(3)
            val theirs = assertNotNull(d.registry[ClickTarget.Control("region:zone-panel-far-exile")])
            val back = them.exile.single { it.hidden }
            assertTrue(d.registry[ClickTarget.Card(back.id)]?.let { theirs.contains(it.center) } == true, "the face-down card is in it, as its back")
            d.savePng(File(pngDir, "zone-panel-their-exile.png"))
        }
    }
}
