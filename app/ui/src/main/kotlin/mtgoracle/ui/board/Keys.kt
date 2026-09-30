package mtgoracle.ui.board

import androidx.compose.ui.input.key.Key

/** Compose's key to the board's; null for keys the board ignores. */
fun Key.toUiKey(): UiKey? = when (this) {
    Key.Enter, Key.NumPadEnter -> UiKey.ENTER
    Key.Escape -> UiKey.ESCAPE
    Key.F2 -> UiKey.F2
    Key.F3 -> UiKey.F3
    Key.F4 -> UiKey.F4
    Key.F6 -> UiKey.F6
    Key.Plus, Key.NumPadAdd, Key.Equals -> UiKey.PLUS
    Key.Minus, Key.NumPadSubtract -> UiKey.MINUS
    Key.Backspace -> UiKey.BACKSPACE
    Key.One, Key.NumPad1 -> UiKey.D1
    Key.Two, Key.NumPad2 -> UiKey.D2
    Key.Three, Key.NumPad3 -> UiKey.D3
    Key.Four, Key.NumPad4 -> UiKey.D4
    Key.Five, Key.NumPad5 -> UiKey.D5
    Key.Six, Key.NumPad6 -> UiKey.D6
    Key.Seven, Key.NumPad7 -> UiKey.D7
    Key.Eight, Key.NumPad8 -> UiKey.D8
    Key.Nine, Key.NumPad9 -> UiKey.D9
    Key.Zero, Key.NumPad0 -> UiKey.D0
    else -> null
}
