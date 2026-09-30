package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
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

/** Piles of the same card stay together, and a tap moves no other pile. */
class PileOrderTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    private fun withLands(lands: List<CardState>): BoardState = quietBoard().let { b ->
        b.copy(players = b.players.map { if (it.isSeat) it.copy(battlefield = lands) else it })
    }

    private fun drive(board: BoardState, block: (OffscreenDriver, FakeSeat) -> Unit) {
        val seat = FakeSeat(board, null)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5); block(d, seat)
        }
    }

    @Test
    fun `two Islands, one tapped - the untapped pile stays put, the tapped one sits right after it, and untapping merges them back`() {
        val a = card(10, "Island", land = true)
        val b = card(11, "Island", land = true)
        drive(withLands(listOf(a, b, card(12, "Plains", land = true)))) { d, seat ->
            val pile = d.registry[ClickTarget.Card(10)]!!
            val plains = d.registry[ClickTarget.Card(12)]!!
            // Tap the first Island by hand (Forge lists it first, so an unstable order would put the tapped pile first).
            seat.board.value = withLands(listOf(a.copy(tapped = true), b, card(12, "Plains", land = true)))
            d.settle(3)
            val untapped = assertNotNull(d.registry[ClickTarget.Card(11)])
            val tapped = assertNotNull(d.registry[ClickTarget.Card(10)])
            assertEquals(pile.left, untapped.left, "the untapped pile keeps the pile's place")
            assertTrue(tapped.left > untapped.left && tapped.left < d.registry[ClickTarget.Card(12)]!!.left, "the tapped Island right after it, before the Plains")
            // Back as it was: one pile again, where it was.
            seat.board.value = withLands(listOf(a, b, card(12, "Plains", land = true)))
            d.settle(3)
            assertEquals(pile, d.registry[ClickTarget.Card(10)], "merged back into the same slot")
            assertNull(d.registry[ClickTarget.Card(11)], "one pile, one frame")
            assertEquals(plains, d.registry[ClickTarget.Card(12)])
        }
    }

    @Test
    fun `five different lands, the middle one tapped - the other four keep their places`() {
        val names = listOf("Island", "Plains", "Hallowed Fountain", "Flooded Strand", "Mystic Sanctuary")
        val lands = names.mapIndexed { i, n -> card(60 + i, n, land = true) }
        drive(withLands(lands)) { d, seat ->
            val before = lands.associate { it.id to d.registry[ClickTarget.Card(it.id)]!! }
            seat.board.value = withLands(lands.map { if (it.id == 62) it.copy(tapped = true) else it })
            d.settle(3)
            for (l in lands.filter { it.id != 62 }) assertEquals(before[l.id], d.registry[ClickTarget.Card(l.id)], "${l.name} did not move")
            val turned = d.registry[ClickTarget.Card(62)]!!
            assertTrue(turned.left > before.getValue(62).left, "the tapped one turns within its own slot")
        }
    }
}
