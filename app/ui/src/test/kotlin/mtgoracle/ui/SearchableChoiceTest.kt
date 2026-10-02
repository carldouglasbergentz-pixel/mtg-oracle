package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Choose a card name" offers every card there is. Drawn whole, its tens of
 * thousands of rows crashed the board (Compose: "LayoutNode not found in
 * RectList", found by SoakTest). A long choice is searched instead: typing
 * narrows it, and only the matches that fit are drawn.
 */
class SearchableChoiceTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    @Test
    fun `a choice of thirty thousand card names is searched, typed past the board's letter keys, and answered`() {
        val names = List(30_000) { "Card %05d".format(it) } + listOf("Sol Ring", "Sol Talisman", "Solemn Simulacrum")
        val prompt = ChoicePrompt(9, "Choose a card name", names.map { ChoiceOption(it, null) }, 1, 1)
        val seat = FakeSeat(sampleBoard(), prompt)
        var arranged: BoardLayout? = null
        OffscreenDriver(1720, 1060) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART, onLayoutChange = { arranged = it }) }
        }.use { d ->
            d.settle(5)
            val drawn = d.registry.targets.count { it is ClickTarget.Option }
            assertTrue(drawn in 1..200, "only what fits is drawn: $drawn options")
            // S folds the stack and R turns tapped cards on the board: here they are letters of the name.
            // Enter with nothing typed picks nothing; a capital comes with a Shift, which has no character (0xFFFF).
            d.key(Key.Enter, 10)
            assertEquals(emptyList(), seat.answers)
            d.key(Key.ShiftLeft, 0xFFFF)
            for ((key, char) in listOf(Key.S to 'S', Key.O to 'o', Key.L to 'l', Key.Spacebar to ' ', Key.R to 'r')) d.key(key, char.code)
            d.settle(3)
            assertTrue("find: Sol r_" in d.text.all(), d.text.all().lines().first { "find:" in it })
            assertEquals(null, arranged, "the board's S and R did nothing")
            assertTrue(d.registry[ClickTarget.Option(names.indexOf("Sol Ring"))] != null, "Sol Ring is drawn")
            assertTrue(d.registry[ClickTarget.Option(names.indexOf("Sol Talisman"))] == null, "Sol Talisman has no r: not drawn")
            d.savePng(File(pngDir, "choice-searched.png"))
            d.key(Key.Enter, 10)
            assertEquals(listOf(SeatAction.Choose(listOf(names.indexOf("Sol Ring")))), seat.answers.map { it.second }, "Enter takes the first match")
        }
    }
}
