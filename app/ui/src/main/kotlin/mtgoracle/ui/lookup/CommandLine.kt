package mtgoracle.ui.lookup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.gridStyle

/** What is typed, the history behind it, and whether the line has the keyboard. Outlives the screen's recompositions. */
class CommandLineState {
    var value by mutableStateOf(TextFieldValue(""))
    var focused by mutableStateOf(false)
    val history = History()

    fun set(text: String) { value = TextFieldValue(text, TextRange(text.length)) }

    /**
     * The key that opened the line (`:`). AWT delivers its character as a
     * separate typed event after the press, which can land in the field the
     * press just focused; the first change that is only that character is it.
     */
    private var opener: Char? = null

    fun openedBy(char: Char?) { opener = char }

    internal fun change(next: TextFieldValue) {
        // Only a change of text counts: gaining focus reports a selection change first.
        if (next.text != value.text) {
            val swallow = opener
            opener = null
            if (swallow != null && value.text.isEmpty() && next.text == swallow.toString()) return
        }
        value = next
    }
}

/**
 * The command line: [prompt] (which deck search follows), the text, and the
 * autofill suggestion in grey after it. Enter runs, Up / Down walk the
 * history, Tab or → at the end takes the suggestion, PgUp / PgDn scroll the
 * output ([onPage]), Esc gives the keyboard back to the screen ([onLeave]).
 */
@Composable
fun CommandLine(
    state: CommandLineState,
    prompt: String,
    suggest: (String) -> String?,
    onSubmit: (String) -> Unit,
    onLeave: () -> Unit,
    onPage: (Int) -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val text = state.value.text
    val suggestion = remember(text) { suggest(text) }?.takeIf { it.length > text.length && it.startsWith(text, ignoreCase = true) }
    fun accept(): Boolean = suggestion?.let { state.set(it); true } ?: false

    Row(modifier.fillMaxWidth().region("command-line")) {
        GridText(prompt, color = Palette.accent, bold = true)
        Box(Modifier.fillMaxWidth()) {
            when {
                suggestion != null -> GridText(" ".repeat(text.length) + suggestion.substring(text.length), color = Palette.dim)
                text.isEmpty() && !state.focused -> GridText(": type a command — `help` lists them", color = Palette.dim)
            }
            BasicTextField(
                value = state.value,
                onValueChange = state::change,
                singleLine = true,
                textStyle = gridStyle.copy(color = Palette.foreground),
                cursorBrush = SolidColor(Palette.foreground),
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { state.focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                val line = state.value.text.trim()
                                state.set("")
                                if (line.isNotEmpty()) { state.history.submit(line); onSubmit(line) }
                                true
                            }
                            Key.DirectionUp -> { state.history.older(state.value.text)?.let(state::set); true }
                            Key.DirectionDown -> { state.history.newer()?.let(state::set); true }
                            Key.Tab -> { accept(); true } // never moves the focus away
                            Key.DirectionRight -> state.value.selection.end == text.length && accept()
                            Key.Escape -> { onLeave(); true }
                            Key.PageUp -> { onPage(-1); true }
                            Key.PageDown -> { onPage(+1); true }
                            else -> false
                        }
                    },
            )
        }
    }
}
