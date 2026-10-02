package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.BoardState
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.emblemLabel
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Emblems have a place of their own: a row at the right end of each half's
 * midline edge (the top of yours, the bottom of the opponent's), never in the
 * exile list and never over a card.
 */
class EmblemsTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    private fun board(): BoardState = quietBoard().let { b ->
        val elspeth = card(80, "Emblem — Elspeth, Knight-Errant", type = "Emblem").copy(text = "Artifacts, creatures, enchantments, and lands you control are indestructible.")
        val liliana = card(81, "Emblem — Liliana of the Dark Realms", type = "Emblem").copy(text = "Swamps you control have \"{T}: Add {B}{B}{B}{B}.\"")
        b.copy(players = b.players.map { p -> if (p.isSeat) p.copy(emblems = listOf(elspeth)) else p.copy(emblems = listOf(liliana)) })
    }

    @Test
    fun `the label drops Forge's Emblem prefix`() {
        assertEquals("Elspeth, Knight-Errant", emblemLabel("Emblem — Elspeth, Knight-Errant"))
        assertEquals("Elspeth, Knight-Errant", emblemLabel("Emblem - Elspeth, Knight-Errant"))
        assertEquals("The Ring", emblemLabel("The Ring"))
    }

    @Test
    fun `each half's emblems sit at its midline edge, on the right, over no card`() {
        val seat = FakeSeat(board(), null)
        OffscreenDriver(1800, 1200) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            d.savePng(File(pngDir, "emblems.png"))
            val near = d.registry[ClickTarget.Control("region:near-field")]!!
            val far = d.registry[ClickTarget.Control("region:far-field")]!!
            val mine = d.registry[ClickTarget.Card(80)]!!
            val theirs = d.registry[ClickTarget.Card(81)]!!
            assertTrue(mine.top - near.top < near.height * 0.1f && mine.right > near.center.x, "mine at the top right of my half: $mine in $near")
            assertTrue(far.bottom - theirs.bottom < far.height * 0.1f && theirs.right > far.center.x, "theirs at the bottom right of theirs: $theirs in $far")
            val cards = board().players.flatMap { it.battlefield }.mapNotNull { d.registry[ClickTarget.Card(it.id)] }
            for (e in listOf(mine, theirs)) assertTrue(cards.none { it.overlaps(e) }, "$e covers no card")
            assertTrue("Ancestral Recall" in d.text.all() && "exile 1" in d.text.all() && "command" !in d.text.all(), "the exile list holds no emblem")
            d.hover(ClickTarget.Card(80))
            d.settle(3)
            assertTrue("indestructible" in d.text.all(), "a hover shows the emblem's text")
        }
    }
}
