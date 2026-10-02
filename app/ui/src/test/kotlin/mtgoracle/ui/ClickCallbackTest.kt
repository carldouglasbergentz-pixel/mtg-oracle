package mtgoracle.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.ui.kit.BigButton
import mtgoracle.ui.kit.ClickTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A click runs the handler drawn now, not the one first drawn: a question
 * that follows another with a button of the same name (`ask:0`, Import's
 * "Add to the deck" then "Replace") ran the first question's handler, a
 * write the user didn't ask for.
 */
class ClickCallbackTest {
    @Test
    fun `a button whose handler changed runs the new one`() {
        var question by mutableStateOf("first")
        val ran = mutableListOf<String>()
        OffscreenDriver(400, 200) {
            val asked = question
            BigButton("OK", ClickTarget.Control("ask:0"), true) { ran += asked; question = "second" }
        }.use { d ->
            d.settle(3)
            d.click(ClickTarget.Control("ask:0"))
            d.settle(3)
            d.click(ClickTarget.Control("ask:0"))
            assertEquals(listOf("first", "second"), ran)
        }
    }
}
