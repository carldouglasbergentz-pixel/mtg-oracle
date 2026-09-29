package mtgoracle.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import mtgoracle.model.ChoicePrompt
import mtgoracle.model.ConfirmPrompt
import mtgoracle.model.GameSeat
import mtgoracle.model.InputPrompt
import mtgoracle.model.Prompt
import mtgoracle.model.SeatAction
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.Closeable
import java.io.File

/**
 * The window's composable ([LiveBoardScreen]) running offscreen, driven by
 * synthetic pointer events.
 *
 * Headless verification goes through this so that an answer takes the same
 * path as a person's click: pixel -> hit test on the laid-out grid ->
 * ClickTarget -> SeatAction -> GameSeat.answer. If the board has nothing to
 * click for a decision, [click] says so instead of quietly answering.
 *
 * Single-threaded: create and use it from one thread.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OffscreenWindow(seat: GameSeat, title: String, private val width: Int = 1720, private val height: Int = 2000) : Closeable {
    @Volatile private var laidOut: LaidOutGrid? = null
    private var clock = 0L
    private val scene = ImageComposeScene(width, height, Density(1f)) {
        LiveBoardScreen(seat, title) { laidOut = it }
    }

    private fun frame() = scene.render(clock).also { clock += 16_000_000 }

    /** Renders until the board shows prompt [promptId]; false if it never does. */
    private fun settle(promptId: Long): LaidOutGrid? {
        repeat(30) {
            frame()
            laidOut?.takeIf { it.promptId == promptId }?.let { return it }
        }
        return null
    }

    /**
     * Performs [action] on [prompt] by clicking the board; false when the
     * board offers no click for it. [beforeClick] hears each click first.
     */
    fun perform(prompt: Prompt, action: SeatAction, beforeClick: (String) -> Unit = {}): Boolean {
        val targets = targetsFor(prompt, action) ?: return false
        for (target in targets) {
            val grid = settle(prompt.id) ?: return false
            val span = grid.grid.spans.firstOrNull { it.target == target } ?: return false
            val at = grid.centreOf(span)
            beforeClick("$target at (${at.x.toInt()},${at.y.toInt()}) line ${span.line} col ${span.start}")
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            frame()
        }
        return true
    }

    private fun targetsFor(prompt: Prompt, action: SeatAction): List<ClickTarget>? = when (prompt) {
        is InputPrompt -> when (action) {
            is SeatAction.ClickCard -> listOf(ClickTarget.Card(action.cardId))
            is SeatAction.ClickPlayer -> listOf(ClickTarget.Player(action.playerId))
            SeatAction.Ok -> listOf(ClickTarget.Ok)
            SeatAction.Cancel -> listOf(ClickTarget.Cancel)
            else -> null
        }
        is ConfirmPrompt -> (action as? SeatAction.Confirm)?.let { listOf(if (it.yes) ClickTarget.Ok else ClickTarget.Cancel) }
        is ChoicePrompt -> (action as? SeatAction.Choose)?.let { choose ->
            when {
                prompt.isReveal -> listOf(ClickTarget.Done)
                choose.indices.isEmpty() -> listOf(ClickTarget.Done)
                prompt.max <= 1 -> listOf(ClickTarget.Option(choose.indices.single()))
                else -> choose.indices.map { ClickTarget.Option(it) } + ClickTarget.Done
            }
        }
    }

    /** What the window shows right now, cropped to the drawn grid. */
    fun savePng(out: File) {
        repeat(3) { frame() }
        val full = frame()
        val contentHeight = laidOut?.let { (it.layout.size.height + 2 * it.originPx).toInt() } ?: height
        val surface = Surface.makeRasterN32Premul(width, minOf(height, contentHeight))
        surface.canvas.drawImage(full, 0f, 0f)
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        out.parentFile.mkdirs()
        out.writeBytes(png.bytes)
    }

    override fun close() = scene.close()
}
