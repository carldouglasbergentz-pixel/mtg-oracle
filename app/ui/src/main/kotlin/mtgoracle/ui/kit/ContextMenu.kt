package mtgoracle.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import mtgoracle.ui.theme.Palette

/** A right-click (the secondary button) here, told in window coordinates: where a menu opens. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onRightClick(onClick: (Offset) -> Unit): Modifier = composed {
    var where by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { where = it }.onPointerEvent(PointerEventType.Press) { e ->
        if (e.buttons.isSecondaryPressed) {
            val local = e.changes.first().position
            onClick(where?.localToWindow(local) ?: local)
        }
    }
}

/**
 * A menu at [at] (window coordinates), kept inside the window: [items] as
 * `label` to what it does. A click runs one and closes the menu; a click
 * elsewhere or Esc closes it. Items are `Control("menu:<i>")` for the tests.
 */
@Composable
fun ContextMenu(title: String, items: List<Pair<String, () -> Unit>>, at: Offset, onDismiss: () -> Unit) {
    val place = remember(at) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
                at.x.toInt().coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
                at.y.toInt().coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
            )
        }
    }
    Popup(popupPositionProvider = place, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        BoxPane(title, borderColor = Palette.accent) {
            Column {
                items.forEachIndexed { i, (label, act) ->
                    GridText(" $label ", Modifier.clickTarget(ClickTarget.Control("menu:$i"), { onDismiss(); act() }).pointerHoverIcon(PointerIcon.Hand))
                }
            }
        }
    }
}
