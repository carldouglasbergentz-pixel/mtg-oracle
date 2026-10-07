package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A card Forge shows you (Enlightened Tutor's find) is drawn over the table,
 * large, with what Forge said of it; OK answers the reveal, and a hover puts
 * the card in the zoom pane.
 */
class RevealPanelTest {
    @Test
    fun `a revealed card is drawn over the table until OK`() {
        val sylvan = card(900, "Sylvan Library", cost = "{1}{G}", type = "Enchantment").copy(text = "At the beginning of your draw step, you may draw two additional cards.")
        val reveal = ChoicePrompt(7, "Looking at cards in AI (Rakdos)'s library", listOf(ChoiceOption("Sylvan Library (900)", card = sylvan)), -1, -1)
        val seat = FakeSeat(quietBoard(), reveal)
        val art = ArtImages(FakeArt(File(pngDir, "fake-art")), decoder = { it.run() })
        OffscreenDriver(1600, 900) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.TEXT) }
        }.use { d ->
            d.settle(5)
            assertNotNull(d.registry[ClickTarget.Control("region:reveal")], "the panel")
            val text = d.text.all()
            assertTrue("shown to you" in text && "Looking at cards in AI (Rakdos)'s library" in text && "Sylvan Library" in text, text)
            d.savePng(File(pngDir, "reveal-panel.png"))
            assertTrue(d.hover(ClickTarget.Control("reveal:0")))
            d.settle(3)
            d.savePng(File(pngDir, "reveal-panel-hover.png"))
            assertTrue("draw two additional cards" in d.text.all(), "the hovered card in the zoom pane")
            assertTrue(d.click(ClickTarget.Done))
            assertEquals(listOf(7L to (SeatAction.Choose(emptyList()) as SeatAction)), seat.answers.toList(), "OK answers the reveal")
        }
    }
}
