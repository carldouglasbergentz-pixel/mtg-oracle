package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.poolParts
import mtgoracle.ui.board.poolSymbols
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Floating mana: a line of its own by the life total, in the costs' symbols, spendable by clicking while you pay. */
class ManaPoolTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    private fun withPool(pool: String) = quietBoard().let { b -> b.copy(players = b.players.map { if (it.isSeat) it.copy(manaPool = pool) else it }) }

    private fun OffscreenDriver.regions() = registry.targets.filterIsInstance<ClickTarget.Control>()
        .filter { it.name.startsWith("region:") && !it.name.startsWith("region:stack-b") }.associateWith { registry[it]!! }

    @Test
    fun `the pool reads like a cost, and a placeholder when empty`() {
        assertEquals(listOf('U' to 2, 'C' to 5), poolParts("U2 C5"))
        assertEquals("{U}{U}", poolSymbols('U', 2))
        assertEquals("{C}×5", poolSymbols('C', 5))
        val seat = FakeSeat(withPool(""), null)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            assertTrue(d.text.all().lines().any { it.trim() == "—" }, "an empty pool says so")
            val empty = d.regions()
            seat.board.value = withPool("U2 C5")
            d.settle(3)
            val text = d.text.all()
            assertTrue("{U}{U}" in text && "{C}×5" in text, "live: $text")
            assertEquals(empty, d.regions(), "the pool has its own line: nothing moves when it fills")
            assertNull(d.registry[ClickTarget.Mana('U')], "not a button outside a payment")
            d.savePng(File(pngDir, "mana-pool.png"))
            seat.board.value = withPool("")
            d.settle(3)
            assertEquals(empty, d.regions(), "or when it empties")
        }
    }

    @Test
    fun `while paying, a click on a pool colour spends one of it`() {
        val pay = InputPrompt(4, "Pay {B}", InputKind.PAY_MANA, "InputPayManaOfCostPayment", 0, "Auto", "Cancel", true, true, emptySet(), emptySet())
        val seat = FakeSeat(withPool("B2"), pay)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            assertTrue(d.click(ClickTarget.Mana('B')))
            assertEquals(listOf(SeatAction.UseMana('B')), seat.answers.map { it.second })
            d.savePng(File(pngDir, "mana-pool-paying.png"))
        }
    }
}
