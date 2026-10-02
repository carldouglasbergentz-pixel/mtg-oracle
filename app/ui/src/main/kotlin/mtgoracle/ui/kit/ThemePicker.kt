package mtgoracle.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import mtgoracle.ui.theme.Era
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Theme
import mtgoracle.ui.theme.Themes

/** How wide the picker is, in cells. */
private const val PICKER_COLS = 40

/**
 * The themes in a floating window, a section per [Era]. What the mouse is on,
 * or what ↑↓ lands on, is shown on the whole app at once ([onPreview]); a
 * click or Enter keeps it ([onKeep]); Esc or a click outside goes back to
 * [kept] ([onCancel]). Rows are `Control("theme:<key>")`.
 */
@Composable
fun ThemePicker(kept: Theme, onPreview: (Theme) -> Unit, onKeep: (Theme) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    val order = Era.entries.flatMap { era -> Themes.ALL.filter { it.era == era } }
    fun step(by: Int) {
        val at = order.indexOf(Palette.theme).coerceAtLeast(0)
        onPreview(order[(at + by).mod(order.size)])
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onCancel, properties = PopupProperties(focusable = true)) {
        BoxPane("theme", Modifier.cellWidth(PICKER_COLS).region("theme-picker"), borderColor = Palette.accent) {
            Column(Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> step(-1)
                    Key.DirectionDown -> step(+1)
                    Key.Enter, Key.NumPadEnter -> onKeep(Palette.theme)
                    Key.Escape, Key.F8 -> onCancel()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }) {
                for (era in Era.entries) {
                    val themes = Themes.ALL.filter { it.era == era }
                    if (themes.isEmpty()) continue
                    RuleLine(PICKER_COLS - 2, label = era.label)
                    themes.forEach { theme ->
                        val shown = theme == Palette.theme
                        GridText(
                            fit("  ${theme.label}" + if (theme == kept) "  (kept)" else "", PICKER_COLS - 2),
                            // The row's own background, not the text's: trailing spaces take no text background.
                            Modifier.clickTarget(ClickTarget.Control("theme:${theme.key}"), { onKeep(theme) }, { onPreview(theme) })
                                .then(if (shown) Modifier.background(Palette.accent) else Modifier)
                                .pointerHoverIcon(PointerIcon.Hand),
                            color = if (shown) Palette.background else Palette.foreground,
                        )
                    }
                }
                GridText("")
                WrapText("↑↓ or the mouse: shown at once · Enter or a click: keep · Esc: back", color = Palette.dim)
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
