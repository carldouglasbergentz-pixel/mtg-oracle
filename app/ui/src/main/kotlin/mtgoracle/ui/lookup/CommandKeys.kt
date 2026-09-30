package mtgoracle.ui.lookup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** On a screen's start: the command line if typing is under way (see CommandLineState.typing), else the screen. */
fun startFocus(lookup: LookupUi?, screenFocus: FocusRequester, commandFocus: FocusRequester) {
    if (lookup?.command?.typing == true) commandFocus.requestFocus() else screenFocus.requestFocus()
}

/** Where a key goes on a screen with a command line. */
enum class KeyRoute { HANDLED, TO_LINE, TO_SCREEN }

/**
 * The keys every screen with a command line shares, seen (in the screen's
 * onPreviewKeyEvent) before its own: Ctrl+L clears the output anywhere;
 * while the line has the keyboard every other key is the line's, so typing
 * `quit` never quits on its `q`; otherwise `:` or Ctrl+K open the line.
 * What is left is the screen's.
 */
fun routeKey(e: KeyEvent, lookup: LookupUi, commandFocus: FocusRequester): KeyRoute {
    // An open question has the keyboard: its field, its buttons, its Esc.
    if (lookup.ask != null) return KeyRoute.TO_LINE
    if (e.isCtrlPressed && e.key == Key.L) { lookup.output.clear(); return KeyRoute.HANDLED }
    if (lookup.command.focused) return KeyRoute.TO_LINE
    if (e.utf16CodePoint == ':'.code || (e.isCtrlPressed && e.key == Key.K)) {
        lookup.command.openedBy(if (e.isCtrlPressed) null else ':')
        commandFocus.requestFocus()
        return KeyRoute.HANDLED
    }
    return KeyRoute.TO_SCREEN
}

/**
 * A press anywhere in this element ends typing, as clicking outside a text
 * field does, so the screen's keys answer again. Seen first (Initial) and
 * never consumed: the press still clicks what it is on.
 */
fun Modifier.endsTyping(lookup: LookupUi?, screenFocus: FocusRequester): Modifier = composed {
    val current by rememberUpdatedState(lookup)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val line = current?.command
                if (e.type == PointerEventType.Press && line?.focused == true) {
                    line.typing = false
                    screenFocus.requestFocus()
                }
            }
        }
    }
}
