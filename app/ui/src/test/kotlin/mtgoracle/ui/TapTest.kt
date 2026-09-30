package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Themes
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tapped cards are turned a quarter (art mode), in the tapped tone, and nothing around them moves. */
class TapTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    @AfterTest fun houseTheme() { Palette.theme = Themes.HOUSE }

    private fun board(creaturesTapped: Boolean, landsTapped: Boolean): BoardState = quietBoard().let { b ->
        b.copy(players = b.players.map { p ->
            if (!p.isSeat) p else p.copy(battlefield = listOf(
                card(70, "Grizzly Bears", creature = true, cost = "{1}{G}", tapped = creaturesTapped),
                card(71, "Serra Angel", creature = true, cost = "{3}{W}{W}"),
                card(72, "Island", land = true, tapped = landsTapped), card(73, "Island", land = true, tapped = landsTapped), card(74, "Plains", land = true),
                card(75, "Sol Ring", cost = "{1}", type = "Artifact", tapped = creaturesTapped),
            ))
        })
    }

    private fun driver(seat: FakeSeat, mode: CardMode, layout: BoardLayout = BoardLayout(), onLayout: (BoardLayout) -> Unit = {}) =
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", mode, layout = layout, onLayoutChange = onLayout) } }

    @Test
    fun `a tapped card turns in its own slot - nothing else moves on tap or untap`() {
        val seat = FakeSeat(board(false, false), null)
        driver(seat, CardMode.ART).use { d ->
            d.settle(5)
            val upright = (70..75).associateWith { d.registry[ClickTarget.Card(it)] }
            seat.board.value = board(true, false)
            d.settle(3)
            val turned = d.registry[ClickTarget.Card(70)]!!
            assertTrue(turned.width < turned.height || turned != upright[70], "the Bears turned: $turned from ${upright[70]}")
            assertTrue(turned.width > turned.height * 0.8f, "turned, not squashed: $turned")
            for (id in listOf(71, 72, 74)) assertEquals(upright[id], d.registry[ClickTarget.Card(id)], "card $id did not move")
            val slot = upright.getValue(70)!!
            assertTrue(turned.left >= slot.left - 1 && turned.right <= slot.right + 8, "inside its slot: $turned in $slot")
            seat.board.value = board(false, false)
            d.settle(3)
            assertEquals(upright, (70..75).associateWith { d.registry[ClickTarget.Card(it)] }, "untapped: all back where they were")
        }
    }

    @Test
    fun `a click on a turned card hits it`() {
        val prompt = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), setOf(70, 75))
        val seat = FakeSeat(board(true, true), prompt)
        driver(seat, CardMode.ART).use { d ->
            d.settle(5)
            assertTrue(d.click(ClickTarget.Card(70)))
            assertTrue(d.click(ClickTarget.Card(75)))
            assertEquals(listOf(SeatAction.ClickCard(70), SeatAction.ClickCard(75)), seat.answers.map { it.second })
        }
    }

    @Test
    fun `R keeps tapped cards upright, and the choice goes to the app to keep`() {
        var saved = BoardLayout()
        val seat = FakeSeat(board(true, true), null)
        driver(seat, CardMode.ART, onLayout = { saved = it }).use { d ->
            d.settle(5)
            val turned = d.registry[ClickTarget.Card(70)]!!
            d.key(Key.R)
            assertFalse(saved.rotateTapped)
            val upright = d.registry[ClickTarget.Card(70)]!!
            assertTrue(upright != turned, "now upright, shifted a cell: $upright")
            assertTrue("TAPPED" in d.text.all(), "the label still says so, in its tone")
        }
    }

    @Test
    fun `pictures - tapped creatures and lands, art and text, in two themes`() {
        for (theme in listOf(Themes.HOUSE, Themes.ROSE_PINE)) for (mode in CardMode.entries) {
            Palette.theme = theme
            driver(FakeSeat(board(true, true), null), mode).use { d -> d.settle(5); d.savePng(File(pngDir, "tapped-${theme.key}-${mode.name.lowercase()}.png")) }
        }
    }
}
