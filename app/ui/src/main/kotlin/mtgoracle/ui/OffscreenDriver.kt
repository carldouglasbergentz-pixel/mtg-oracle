package mtgoracle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.ui.board.sideboardTarget
import mtgoracle.ui.board.default
import mtgoracle.ui.kit.ClickRegistry
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalClickRegistry
import mtgoracle.ui.kit.LocalTextRecorder
import mtgoracle.ui.kit.TextRecorder
import mtgoracle.ui.theme.HouseTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.Closeable
import java.io.File

/**
 * The app's composables running offscreen, driven by synthetic pointer and
 * key events — so tests and the headless evidence run answer a prompt the
 * way a person does: find the control on screen (ClickRegistry, reported by
 * the component that draws it), press and release the mouse over its middle.
 *
 * Single-threaded: create and use it from one thread.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OffscreenDriver(
    private val width: Int = 1800,
    private val height: Int = 2400,
    private val density: Float = 1f,
    /**
     * An error while rendering goes here, and the scene is built afresh — as
     * the app's window handler opens a fresh window. Null: the error is thrown.
     */
    private val onError: ((Throwable) -> Unit)? = null,
    private val content: @Composable () -> Unit,
) : Closeable {
    val registry = ClickRegistry()
    /** Every string on screen: what the hidden-information tests search. */
    val text = TextRecorder()
    private var clock = 0L
    private var dispatcher = RenderThreadDispatcher()
    // Every coroutine of this scene (StateFlow collectors, effects) runs on the thread that renders it,
    // inside frame(). The default, unconfined, resumed them on whichever Forge thread emitted — Compose
    // internals then ran concurrently with rendering, and scenes lost updates or corrupted their layout.
    // An error in composition doesn't come out of render(): it ends the recomposer's coroutine. Catch it there.
    @Volatile private var pendingError: Throwable? = null
    private fun newScene() = ImageComposeScene(width, height, Density(density),
        dispatcher + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> pendingError = e }) {
        CompositionLocalProvider(LocalClickRegistry provides registry, LocalTextRecorder provides text) { HouseTheme(content) }
    }
    private var scene = newScene()

    /** Renders one frame and frees it: each rendered image is native memory the GC can't see. */
    fun frame() {
        val t = System.nanoTime()
        try {
            renderOnce()
        } catch (e: Exception) {
            val handler = onError ?: throw e
            handler(e)
            // The old composition is not to be trusted after an error in it: start again.
            runCatching { scene.close() }
            dispatcher = RenderThreadDispatcher()
            scene = newScene()
            renderOnce()
        }
        clock += 16_000_000
        frames++
        frameNanos += System.nanoTime() - t
    }

    private fun renderOnce() {
        dispatcher.drain()
        scene.render(clock).close()
        dispatcher.drain()
        pendingError?.let { pendingError = null; throw (it as? Exception ?: RuntimeException(it)) }
    }
    var frames = 0L
        private set
    var frameNanos = 0L
        private set

    fun settle(frames: Int = 3) = repeat(frames) { frame() }

    /**
     * Clicks [target] where it is drawn; false when nothing on screen is that
     * target within [waitMillis] (a new prompt can land a moment before the
     * scene has composed it, so this waits in wall-clock time, not frames).
     */
    fun click(target: ClickTarget, waitMillis: Long = 3_000, until: () -> Boolean = { true }): Boolean {
        val deadline = System.currentTimeMillis() + waitMillis
        var scrolls = 0
        do {
            frame()
            var rect = registry[target]
            // Laid out but clipped away (a long option list): scroll the prompt, as a mouse wheel would.
            while (rect != null && (rect.width <= 0f || rect.height <= 0f) && scrolls < 40) {
                val pane = registry[ClickTarget.Control("region:prompt-content")] ?: break
                scene.sendPointerEvent(PointerEventType.Scroll, pane.center, scrollDelta = Offset(0f, 1f))
                scrolls++
                frame()
                rect = registry[target]
            }
            if (rect != null && rect.width > 0f && rect.height > 0f && until()) {
                val at = rect.center
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                frame()
                return true
            }
            Thread.sleep(5)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    /** Presses on [target], moves the mouse by [by] in small steps, releases: a drag, as a hand makes it. */
    fun drag(target: ClickTarget, by: Offset, steps: Int = 12): Boolean {
        frame()
        val rect = registry[target] ?: return false
        val start = Offset(rect.left + 4, rect.center.y)
        scene.sendPointerEvent(PointerEventType.Move, start)
        scene.sendPointerEvent(PointerEventType.Press, start)
        for (i in 1..steps) {
            scene.sendPointerEvent(PointerEventType.Move, start + by * (i / steps.toFloat()))
            frame()
        }
        scene.sendPointerEvent(PointerEventType.Release, start + by)
        settle()
        return true
    }

    /** A right-click on [target] (the secondary button), as a context menu wants. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun rightClick(target: ClickTarget): Boolean {
        frame()
        val rect = registry[target] ?: return false
        val at = rect.center
        val secondary = androidx.compose.ui.input.pointer.PointerButtons(isSecondaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = secondary, button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
        scene.sendPointerEvent(PointerEventType.Release, at, button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
        settle()
        return true
    }

    fun hover(target: ClickTarget): Boolean {
        frame()
        val rect = registry[target] ?: return false
        scene.sendPointerEvent(PointerEventType.Move, Offset(rect.left + 1, rect.top + 1))
        scene.sendPointerEvent(PointerEventType.Move, rect.center)
        frame()
        return true
    }

    /**
     * A key press and release through Compose's key handling, as the window
     * delivers them. Compose's KeyEvent factory is marked internal-unstable;
     * acceptable in this driver only, with Compose pinned in libs.versions.toml.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun key(key: Key, char: Int = 0, ctrl: Boolean = false, shift: Boolean = false) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key, type, codePoint = char, isCtrlPressed = ctrl, isShiftPressed = shift))
        }
        frame()
    }

    /**
     * The clicks that give [action] as the answer to [prompt], in order; null
     * when no sequence of on-screen controls can express it.
     */
    fun clicksFor(prompt: Prompt, action: SeatAction): List<ClickTarget>? = when (prompt) {
        is SideboardPrompt -> (action as? SeatAction.Sideboard)?.let { want ->
            val have = prompt.main.associate { it.name to it.count }
            val names = (have.keys + want.main.keys)
            names.flatMap { n -> List(maxOf(0, (have[n] ?: 0) - (want.main[n] ?: 0))) { sideboardTarget(true, n) } } +
                names.flatMap { n -> List(maxOf(0, (want.main[n] ?: 0) - (have[n] ?: 0))) { sideboardTarget(false, n) } } + ClickTarget.Done
        }
        is InputPrompt -> when (action) {
            is SeatAction.ClickCard -> listOf(ClickTarget.Card(action.cardId))
            is SeatAction.ClickPlayer -> listOf(ClickTarget.Player(action.playerId))
            is SeatAction.UseMana -> listOf(ClickTarget.Mana(action.colour))
            SeatAction.Ok -> listOf(ClickTarget.Ok)
            SeatAction.Cancel -> listOf(ClickTarget.Cancel)
            else -> null
        }
        is ConfirmPrompt -> (action as? SeatAction.Confirm)?.let { listOf(if (it.yes) ClickTarget.Ok else ClickTarget.Cancel) }
        is ChoicePrompt -> (action as? SeatAction.Choose)?.let { choose ->
            when {
                prompt.isReveal || choose.indices.isEmpty() -> listOf(ClickTarget.Done)
                prompt.max <= 1 -> listOf(ClickTarget.Option(choose.indices.single()))
                else -> choose.indices.map { ClickTarget.Option(it) } + ClickTarget.Done
            }
        }
        is OrderPrompt -> (action as? SeatAction.Order)?.let { order -> order.indices.map { ClickTarget.Option(it) } + ClickTarget.Done }
        is DistributePrompt -> when (action) {
            SeatAction.Cancel -> listOf(ClickTarget.Cancel)
            is SeatAction.Distribute -> {
                // From Forge's suggested split: decreases first, so the running total never exceeds it.
                val deltas = action.amounts.zip(prompt.suggested).map { (want, have) -> want - have }
                deltas.flatMapIndexed { i, d -> List(maxOf(0, -d)) { ClickTarget.Less(i) } } +
                    deltas.flatMapIndexed { i, d -> List(maxOf(0, d)) { ClickTarget.More(i) } } + ClickTarget.Done
            }
            else -> null
        }
        is NumberPrompt -> (action as? SeatAction.Number)?.let { n ->
            val value = n.value ?: return@let listOf(ClickTarget.Cancel)
            val from = prompt.default()
            List(maxOf(0, value - from)) { ClickTarget.More(0) } + List(maxOf(0, from - value)) { ClickTarget.Less(0) } + ClickTarget.Done
        }
    }

    /** Answers [prompt] with [action] by clicking; returns the clicks made, or null if it couldn't. */
    fun perform(prompt: Prompt, action: SeatAction, beforeClick: (ClickTarget) -> Unit = {}): List<ClickTarget>? {
        val clicks = clicksFor(prompt, action) ?: return null
        settle() // let the board catch up with the prompt before the first click
        for (target in clicks) {
            beforeClick(target)
            if (!click(target)) return null
        }
        return clicks
    }

    /** What the scene shows now, cropped to [cropHeight] when given. */
    /** The rendered colours (ARGB) inside [rect], in window pixels: what a test of tones reads. */
    fun pixels(rect: androidx.compose.ui.geometry.Rect): List<Int> {
        settle()
        val full = scene.render(clock).also { clock += 16_000_000 }
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImage(full, 0f, 0f)
        val snapshot = surface.makeImageSnapshot()
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(snapshot)
        val xs = rect.left.toInt().coerceIn(0, width) until rect.right.toInt().coerceIn(0, width)
        val ys = rect.top.toInt().coerceIn(0, height) until rect.bottom.toInt().coerceIn(0, height)
        val colours = xs.flatMap { x -> ys.map { y -> bitmap.getColor(x, y) } }
        listOf(bitmap, snapshot, surface, full).forEach { it.close() }
        return colours
    }

    fun savePng(out: File, cropHeight: Int? = null) {
        settle()
        val full = scene.render(clock).also { clock += 16_000_000 }
        val h = cropHeight?.coerceIn(1, height) ?: height
        val surface = Surface.makeRasterN32Premul(width, h)
        surface.canvas.drawImage(full, 0f, 0f)
        val snapshot = surface.makeImageSnapshot()
        val png = snapshot.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        listOf(snapshot, surface, full).forEach { it.close() }
        out.parentFile.mkdirs()
        // Write beside, then move over: Windows refuses to truncate a PNG another process (a
        // viewer, the indexer) has mapped, but a rename usually goes through; retry briefly if not.
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.writeBytes(png.bytes)
        var attempt = 0
        while (true) {
            try {
                java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                break
            } catch (e: java.nio.file.FileSystemException) {
                if (++attempt >= 20) throw e
                Thread.sleep(250)
            }
        }
    }

    override fun close() = scene.close()
}

/** Queues coroutine work until the render thread drains it. */
private class RenderThreadDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() { while (true) (queue.poll() ?: return).run() }
}
