package mtgoracle.ui

import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.AskBar
import mtgoracle.ui.lookup.Toggle
import mtgoracle.ui.kit.ClickTarget
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
        var toggled = false
        val ask = Ask.Buttons(title, listOf("Import" to {}, "Later" to {}, "Skip" to {}),
            toggles = listOf(Toggle("history (69)", true) { toggled = true }, Toggle("games (35 new)", true) {}, Toggle("combos (0 new)", false) {}))
        OffscreenDriver(1280, 300) { AskBar(ask) {} }.use { d ->
            d.settle(3)
            val text = d.text.all()
            assertTrue(text.lines().any { it.trimEnd().endsWith("Nothing here is overwritten.") }, text)
            assertFalse(text.lines().any { "…" in it }, "nothing cut: $text")
            // The toggles are check boxes under the question, not buttons among the actions.
            d.savePng(File(pngDir, "ask-wrap.png"))
            assertTrue("[x] history (69)" in text && "[ ] combos (0 new)" in text, text)
            assertTrue(d.click(ClickTarget.Control("ask-toggle:0")) && toggled, "a check box flips")
        }
    }
}
