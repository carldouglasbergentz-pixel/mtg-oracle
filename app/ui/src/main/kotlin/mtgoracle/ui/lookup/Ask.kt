package mtgoracle.ui.lookup

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.BigButton
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.gridStyle

/**
 * A question the screen asks before it changes the library: a name to type,
 * one of a list to pick, or which of a few buttons. While one is open it has
 * the keyboard (see [routeKey]); Esc or Cancel drops it and changes nothing.
 */
sealed interface Ask {
    val title: String

    /** A line of text: a new deck's or folder's name, a rename. */
    data class Text(override val title: String, val initial: String = "", val ok: String = "OK", val onOk: (String) -> Unit) : Ask

    /** One of [options]; [onHover] hears the one under the mouse (a printing shows its art). */
    data class Choose(
        override val title: String,
        val options: List<Option>,
        val onPick: (Option) -> Unit,
        val onHover: (Option) -> Unit = {},
    ) : Ask

    /** A few buttons, the first the default (Enter): Delete / Cancel, Add / Replace / Cancel. */
    data class Buttons(override val title: String, val buttons: List<Pair<String, () -> Unit>>) : Ask
}

/** One choice of an [Ask.Choose]: what it reads as, and the value behind it. */
data class Option(val label: String, val value: String, val detail: String = "")

/**
 * The open [ask] as a bar above the command line (a name, buttons), or a
 * list over the screen (a choice). [onClose] runs after an answer or a cancel.
 */
@Composable
fun AskBar(ask: Ask, onClose: () -> Unit) {
    when (ask) {
        is Ask.Text -> TextAsk(ask, onClose)
        is Ask.Buttons -> ButtonsAsk(ask, onClose)
        is Ask.Choose -> ChooseAsk(ask, onClose)
    }
}

@Composable
private fun TextAsk(ask: Ask.Text, onClose: () -> Unit) {
    var value by remember(ask) { mutableStateOf(TextFieldValue(ask.initial, TextRange(0, ask.initial.length))) }
    val focus = remember { FocusRequester() }
    fun ok() { val v = value.text.trim(); if (v.isNotEmpty()) { onClose(); ask.onOk(v) } }
    BoxPane(ask.title, Modifier.fillMaxWidth().region("ask"), borderColor = Palette.accent) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                textStyle = gridStyle.copy(color = Palette.foreground), cursorBrush = SolidColor(Palette.foreground),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { ok(); true }
                        Key.Escape -> { onClose(); true }
                        else -> false
                    }
                },
            )
            Button(ask.ok, "ask:ok") { ok() }
            Button("Cancel", "ask:cancel", onClose)
        }
    }
    LaunchedEffect(ask) { focus.requestFocus() }
}

@Composable
private fun ButtonsAsk(ask: Ask.Buttons, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    BoxPane(null, Modifier.fillMaxWidth().region("ask"), borderColor = Palette.accent) {
        Row(Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Enter, Key.NumPadEnter -> { onClose(); ask.buttons.first().second(); true }
                Key.Escape -> { onClose(); true }
                else -> false
            }
        }.focusable(), verticalAlignment = Alignment.CenterVertically) {
            // A question wraps beside its buttons, as instructions do: a package's says what it holds, and cut it said nothing.
            WrapText(ask.title, Modifier.weight(1f), bold = true)
            ask.buttons.forEachIndexed { i, (label, act) -> Button(label, "ask:$i") { onClose(); act() } }
            Button("Cancel", "ask:cancel", onClose)
        }
    }
    LaunchedEffect(ask) { focus.requestFocus() }
}

@Composable
private fun ChooseAsk(ask: Ask.Choose, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    // A long list (an Island's hundreds of printings) is searched as the board's long choices are:
    // what is typed goes to the filter, every word of it in the label or the detail.
    val filtering = ask.options.size > FILTER_FROM
    var filter by remember(ask) { mutableStateOf("") }
    val shown = ask.options.withIndex().filter { (_, o) -> matches(o, filter) }
    Popup(alignment = Alignment.Center, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        val cols = 64
        BoxPane(ask.title, Modifier.cellWidth(cols).region("ask"), borderColor = Palette.accent) { Column {
            if (filtering) GridText(mtgoracle.ui.kit.fit(" find: ${filter}_  ${shown.size}/${ask.options.size}  (type to narrow, Enter takes the first)", cols - 2), color = Palette.dim)
            Column(Modifier.heightIn(max = with(androidx.compose.ui.platform.LocalDensity.current) { (LocalCells.current.height * 24).toDp() })
                .verticalScroll(rememberScrollState()).focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val typed = e.utf16CodePoint.takeIf { it in 32 until 0xFFFF && it != 127 }?.toChar()?.takeIf { !it.isISOControl() && Character.isDefined(it) }
                    when {
                        e.key == Key.Escape -> { onClose(); true }
                        !filtering -> false
                        e.key == Key.Backspace -> { filter = filter.dropLast(1); true }
                        e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                            shown.firstOrNull()?.takeIf { filter.isNotBlank() }?.let { onClose(); ask.onPick(it.value) }
                            true
                        }
                        typed != null -> { filter += typed; true }
                        else -> false
                    }
                }.focusable()) {
                shown.forEach { (i, option) ->
                    GridText(
                        mtgoracle.ui.kit.fit(" ${option.label}" + if (option.detail.isEmpty()) "" else "  ${option.detail}", cols - 2),
                        Modifier.clickTarget(ClickTarget.Control("choose:$i"), { onClose(); ask.onPick(option) }, { ask.onHover(option) }).pointerHoverIcon(PointerIcon.Hand),
                    )
                }
                if (shown.isEmpty()) GridText(" (nothing matches '$filter')", color = Palette.dim)
                GridText(" Cancel (Esc)", Modifier.clickTarget(ClickTarget.Control("ask:cancel"), { onClose() }).pointerHoverIcon(PointerIcon.Hand), color = Palette.dim)
            }
        } }
    }
    LaunchedEffect(ask) { focus.requestFocus() }
}

/** A list longer than this has a filter. */
private const val FILTER_FROM = 10

/** Whether [option] holds every word of [filter], in its label or its detail, any case. */
internal fun matches(option: Option, filter: String): Boolean {
    val text = "${option.label} ${option.detail}".lowercase()
    return filter.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in text }
}

@Composable
private fun Button(label: String, name: String, onClick: () -> Unit) {
    GridText(" ")
    BigButton(label, ClickTarget.Control(name), true) { onClick() }
}

