package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A question raised by a card floats beside it, answered there: a
 * planeswalker clicked on the right had its abilities listed in the left
 * column. A choice among cards shows their frames. The left column says the
 * question, and where it is answered.
 */
class ChoiceBoxTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))
    private val priority = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), setOf(16))

    @Test
    fun `the abilities of a card clicked float beside it, and are picked there`() {
        val seat = FakeSeat(sampleBoard(), priority)
        OffscreenDriver(1800, 1200) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            assertNull(d.registry[ClickTarget.Control("region:choice-box")], "no box while nothing is asked")
            val ring = assertNotNull(d.registry[ClickTarget.Card(16)])
            assertTrue(d.click(ClickTarget.Card(16)))
            seat.prompt.value = ChoicePrompt(2, "Choose an ability of Sol Ring", listOf(ChoiceOption("{T}: Add {C}{C}."), ChoiceOption("Cancel")), 1, 1)
            d.settle(4)
            val box = assertNotNull(d.registry[ClickTarget.Control("region:choice-box")], "the box is up")
            assertTrue(kotlin.math.abs(box.center.y - ring.center.y) < box.height, "beside the card: $box by $ring")
            assertTrue(box.left >= ring.right || box.right <= ring.left, "and not over it: $box, $ring")
            val option = assertNotNull(d.registry[ClickTarget.Option(0)])
            assertTrue(box.contains(option.center), "the options are in the box")
            assertTrue("answer in the box" in d.text.all(), "the left column says where to answer")
            d.savePng(File(pngDir, "choice-box-abilities.png"))
            assertTrue(d.click(ClickTarget.Option(0)))
            assertEquals(SeatAction.Choose(listOf(0)), seat.answers.last().second)
        }
    }

    @Test
    fun `a choice among cards shows their frames`() {
        val lands = listOf(card(200, "Volcanic Island", land = true), card(201, "Plateau", land = true), card(202, "Badlands", land = true))
        val seat = FakeSeat(sampleBoard(), ChoicePrompt(3, "Search your library for a Mountain or Island card", lands.map { ChoiceOption(it.name, card = it) }, 0, 1))
        OffscreenDriver(1800, 1200) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val box = assertNotNull(d.registry[ClickTarget.Control("region:choice-box")])
            val frames = (0..2).map { assertNotNull(d.registry[ClickTarget.Option(it)]) }
            assertTrue(frames.all { box.contains(it.center) && it.height > 5 * 16f }, "each land in its frame, in the box: $frames")
            d.savePng(File(pngDir, "choice-box-cards.png"))
            assertTrue(d.click(ClickTarget.Option(1)))
            assertTrue(seat.answers.isNotEmpty() || "√" in d.text.all(), "Plateau picked: ${seat.answers}")
        }
    }
}
