package mtgoracle.ui.kit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import mtgoracle.core.model.Step
import java.util.concurrent.ConcurrentHashMap

/** Everything on screen that can be clicked, as the thing it stands for (never as text to re-parse). */
sealed interface ClickTarget {
    data class Card(val id: Int) : ClickTarget
    data class Player(val id: Int) : ClickTarget
    data class StackItem(val id: Int) : ClickTarget
    data object Ok : ClickTarget
    data object Cancel : ClickTarget
    data class Option(val index: Int) : ClickTarget
    data object Done : ClickTarget
    data class More(val index: Int) : ClickTarget
    data class Less(val index: Int) : ClickTarget
    data class Stop(val seatsTurn: Boolean, val step: Step) : ClickTarget
    /** One colour of your mana pool (W U B R G C). */
    data class Mana(val colour: Char) : ClickTarget
    /** A named control on a screen (library, setup): "deck:12", "start", "toggle-ai". */
    data class Control(val name: String) : ClickTarget
}

/**
 * Where each click target was last laid out, in window pixels. The offscreen
 * driver clicks the middle of a region the way a mouse would — the same rule
 * as the TUI's renderer.LinkSpan: the component that draws a target reports
 * its bounds, nobody re-derives positions from what was drawn.
 */
class ClickRegistry {
    private val regions = ConcurrentHashMap<ClickTarget, Rect>()
    operator fun get(target: ClickTarget): Rect? = regions[target]
    internal fun put(target: ClickTarget, rect: Rect) { regions[target] = rect }
    internal fun remove(target: ClickTarget) { regions.remove(target) }
    val targets: Set<ClickTarget> get() = regions.keys
}

val LocalClickRegistry = staticCompositionLocalOf<ClickRegistry?> { null }

/** Makes this element [target]: clickable, hoverable, and findable by the offscreen driver. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.clickTarget(target: ClickTarget, onClick: (ClickTarget) -> Unit, onHover: ((ClickTarget?) -> Unit)? = null): Modifier = composed {
    val registry = LocalClickRegistry.current
    DisposableEffect(target, registry) { onDispose { registry?.remove(target) } }
    var m = this
        .onGloballyPositioned { registry?.put(target, it.boundsInWindow()) }
        .pointerInput(target) { detectTapGestures { onClick(target) } }
    if (onHover != null) {
        m = m.onPointerEvent(PointerEventType.Enter) { onHover(target) }
    }
    m
}

/**
 * Names a fixed region of a screen (a half, the prompt pane...) in the
 * ClickRegistry as `Control("region:<name>")`, without making it clickable —
 * so a test can assert the layout never moves.
 */
fun Modifier.region(name: String): Modifier = composed {
    val registry = LocalClickRegistry.current
    val target = ClickTarget.Control("region:$name")
    DisposableEffect(target, registry) { onDispose { registry?.remove(target) } }
    this.onGloballyPositioned { registry?.put(target, it.boundsInWindow()) }
}
