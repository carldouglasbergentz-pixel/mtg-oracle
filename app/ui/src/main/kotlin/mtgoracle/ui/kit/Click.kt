package mtgoracle.ui.kit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import mtgoracle.core.model.Step
import mtgoracle.ui.theme.Palette
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
    /** A card named in the log pane: line [seq]'s [index]th card, [part] telling apart the pieces of a name the wrap split. */
    data class LogCard(val seq: Long, val index: Int, val part: Int = 0) : ClickTarget
    /** A link in the output pane; [at] tells apart the same link drawn twice (a card named in two blocks). */
    data class Link(val link: mtgoracle.ui.lookup.OutputLink, val at: Long) : ClickTarget
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

/**
 * Makes this element [target]: clickable, hoverable, and findable by the
 * offscreen driver. While the mouse is on it, it says so with [Palette.hover]
 * behind it ([hoverBackground]); a card frame paints its own background, so
 * it passes `mark = false` and takes [onHoverChange] to tint itself instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.clickTarget(
    target: ClickTarget,
    onClick: (ClickTarget) -> Unit,
    onHover: ((ClickTarget?) -> Unit)? = null,
    mark: Boolean = true,
    onHoverChange: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val registry = LocalClickRegistry.current
    DisposableEffect(target, registry) { onDispose { registry?.remove(target) } }
    // The handlers drawn now, not the ones first drawn: the tap detector lives as long as the target, and two
    // questions in a row with a button of the same name (`ask:0`) ran the first question's handler.
    val click by rememberUpdatedState(onClick)
    val hover by rememberUpdatedState(onHover)
    val hoverChange by rememberUpdatedState(onHoverChange)
    var m = this
        .onGloballyPositioned { registry?.put(target, it.boundsInWindow()) }
        .pointerInput(target) { detectTapGestures { click(target) } }
    if (onHover != null) {
        m = m.onPointerEvent(PointerEventType.Enter) { hover?.invoke(target) }
    }
    if (onHoverChange != null) {
        m = m.onPointerEvent(PointerEventType.Enter) { hoverChange?.invoke(true) }.onPointerEvent(PointerEventType.Exit) { hoverChange?.invoke(false) }
    }
    if (mark) m = m.hoverBackground()
    m
}

/** [Palette.hover] behind this element while the mouse is on it: a row, a chip, an option. Behind, never over: card art is never tinted. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.hoverBackground(): Modifier = composed {
    var over by remember { mutableStateOf(false) }
    this.onPointerEvent(PointerEventType.Enter) { over = true }
        .onPointerEvent(PointerEventType.Exit) { over = false }
        .drawBehind { if (over) drawRect(Palette.hover) }
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
