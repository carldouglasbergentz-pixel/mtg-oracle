package mtgoracle.ui

import androidx.compose.ui.input.key.Key
import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.AskBar
import mtgoracle.ui.lookup.Option
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A long choice, an Island's printings, has a filter: the words typed narrow it, and Enter takes the first left. */
class ChooseFilterTest {
    private val islands = listOf(Option("default art", "")) + (1..40).map { n ->
        Option("SET$n ${250 + n}", "set$n|${250 + n}", if (n == 17) "Unhinged · 2004-11-19" else "Core Set $n · ${1990 + n}-01-01")
    }

    @Test
    fun `typing narrows the list, and Enter picks the first left`() {
        var picked: Option? = null
        var closed = false
        val ask = Ask.Choose("Printing of Island", islands, onPick = { picked = it })
        OffscreenDriver(1280, 900) { AskBar(ask) { closed = true } }.use { d ->
            d.settle(3)
            assertTrue("find: _" in d.text.all() && "41/41" in d.text.all(), d.text.all())
            for (c in "unh") d.key(Key.U.takeIf { c == 'u' } ?: if (c == 'n') Key.N else Key.H, c.code)
            d.settle(2)
            val text = d.text.all()
            assertTrue("find: unh_  1/41" in text && "Unhinged" in text, text)
            assertFalse("Core Set 3 " in text, "the rest are hidden: $text")
            d.savePng(File(pngDir, "choose-filter.png"))
            d.key(Key.Enter, 10)
        }
        assertTrue(closed)
        assertEquals("set17|267", picked?.value)
    }

    @Test
    fun `a short list has no filter`() {
        val ask = Ask.Choose("Folder", islands.take(5), onPick = {})
        OffscreenDriver(1280, 600) { AskBar(ask) {} }.use { d ->
            d.settle(3)
            assertFalse("find:" in d.text.all(), d.text.all())
        }
    }
}
