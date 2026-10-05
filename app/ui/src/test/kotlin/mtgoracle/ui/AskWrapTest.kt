package mtgoracle.ui

import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.AskBar
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A question with buttons wraps its text beside them: a package import's says what it holds, and cut it said nothing. */
class AskWrapTest {
    @Test
    fun `a long question wraps, whole, beside its buttons`() {
        val title = "library-2026-10-05.mtgoracle (the library, from 0.1.1+b719b21): 2 deck(s) to import: Jori En, Phelia Doggo (2) (another deck of its name is here); " +
            "13 already here, the same; 76 game(s), 41 already here; 3 combo(s), 3 already here. Nothing here is overwritten."
        val ask = Ask.Buttons(title, listOf("Import" to {}, "[x] history (69)" to {}, "[x] games (35 new)" to {}, "[x] combos (0 new)" to {}, "Later" to {}, "Skip" to {}))
        OffscreenDriver(1280, 300) { AskBar(ask) {} }.use { d ->
            d.settle(3)
            val text = d.text.all()
            assertTrue(text.lines().any { it.trimEnd().endsWith("Nothing here is overwritten.") }, text)
            assertFalse(text.lines().any { "…" in it }, "nothing cut: $text")
            d.savePng(File(pngDir, "ask-wrap.png"))
        }
    }
}
