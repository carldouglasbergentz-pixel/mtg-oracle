package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A number with no upper bound (Forge's X, "pay any amount"): typed, stepped, answered — never enumerated. */
class NumberPromptTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    @Test
    fun `an X prompt of 0 to Int MAX renders, steps, takes typed digits, and answers`() {
        for (density in listOf(1f, 1.25f)) {
            val prompt = NumberPrompt(7, "Choose X for Wrath of the Skies", 0, Int.MAX_VALUE, cancellable = true)
            val seat = FakeSeat(sampleBoard(), prompt)
            OffscreenDriver((1720 * density).toInt(), (1060 * density).toInt(), density) {
                CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) }
            }.use { d ->
                d.settle(5)
                assertTrue("Choose X for Wrath of the Skies" in d.text.all())
                assertTrue(d.click(ClickTarget.More(0)))
                assertTrue(d.click(ClickTarget.More(0)))
                d.key(Key.Backspace); d.key(Key.Four, '4'.code); d.key(Key.Two, '2'.code)
                d.key(Key.Enter, 10)
                assertEquals(listOf(SeatAction.Number(42)), seat.answers.map { it.second }, "density $density")
                d.savePng(File(pngDir, "number-unbounded-$density.png"))
            }
        }
    }
}
