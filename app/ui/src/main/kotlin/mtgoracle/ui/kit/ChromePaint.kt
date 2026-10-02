package mtgoracle.ui.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mtgoracle.ui.theme.Chrome
import mtgoracle.ui.theme.ChromeStyle
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import kotlin.math.floor

/*
 * The shapes of each ChromeStyle, drawn into the cells the composables in
 * Chrome.kt give them. A new style is a branch in each function here.
 */

/** One device pixel at least: the edges are hairlines at any scale. */
internal fun Density.hairline(): Float = maxOf(1f, floor(1.dp.toPx()))

/** Luna's rounded corners: as XP drew them, but never more than half a row. */
private fun Density.lunaRadius(cellHeight: Float): Float = minOf(6.dp.toPx(), cellHeight / 2)

/** Where a pane's title bar lies in its top row, [width] wide, for a style. */
internal fun Density.titleBarRect(chrome: Chrome, width: Float, cellHeight: Float): Rect {
    val p = hairline()
    return when (chrome.style) {
        ChromeStyle.Bevel -> Rect(3 * p, 3 * p, width - 3 * p, cellHeight - p)
        is ChromeStyle.Luna -> Rect(0f, 0f, width, cellHeight)
    }
}

/**
 * A pane's frame and the title bar's background (not its text), in the
 * one-cell ring around the content; [bar] is the title's colour.
 */
internal fun DrawScope.paintPaneFrame(chrome: Chrome, bar: Color, cellHeight: Float) {
    val p = hairline()
    when (val style = chrome.style) {
        ChromeStyle.Bevel -> {
            // The field sinks at the frame, not at the text: what lies between is the pane's own background, a margin the characters' glyphs used to give.
            val field = Rect(3 * p, cellHeight - p, size.width - 3 * p, size.height - 3 * p)
            drawRect(chrome.face, Offset.Zero, Size(size.width, field.top))
            drawRect(chrome.face, Offset(0f, field.bottom), Size(size.width, size.height - field.bottom))
            drawRect(chrome.face, Offset(0f, field.top), Size(field.left, field.height))
            drawRect(chrome.face, Offset(field.right, field.top), Size(size.width - field.right, field.height))
            bevel(0f, 0f, size.width, size.height, chrome.midLight, chrome.light, chrome.shadow, chrome.darkShadow)
            val title = titleBarRect(chrome, size.width, cellHeight)
            drawRect(bar, title.topLeft, title.size)
            sunken(chrome, field.left, field.top, field.right, field.bottom)
        }
        is ChromeStyle.Luna -> {
            // The window framed in its title's colour, three lines wide; inside it the pane's own background.
            val frame = 3 * p
            val edge = lerp(bar, chrome.darkShadow, 0.35f)
            drawRect(bar, Offset(0f, cellHeight), Size(frame, size.height - cellHeight))
            drawRect(bar, Offset(size.width - frame, cellHeight), Size(frame, size.height - cellHeight))
            drawRect(bar, Offset(0f, size.height - frame), Size(size.width, frame))
            drawRect(edge, Offset(0f, cellHeight), Size(p, size.height - cellHeight))
            drawRect(edge, Offset(size.width - p, cellHeight), Size(p, size.height - cellHeight))
            drawRect(edge, Offset(0f, size.height - p), Size(size.width, p))
            val r = lunaRadius(cellHeight)
            val title = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, size.width, cellHeight), topLeft = CornerRadius(r), topRight = CornerRadius(r)))
            }
            drawPath(title, Brush.verticalGradient(
                0f to lerp(bar, chrome.light, 0.35f), 0.45f to bar, 1f to lerp(bar, chrome.darkShadow, 0.3f),
                startY = 0f, endY = cellHeight,
            ))
            drawPath(title, edge, style = Stroke(p))
        }
    }
}

/** A title's text at [at]: Luna sets it off with a shadow a line down and right, as XP did. */
internal fun DrawScope.paintTitleText(chrome: Chrome, text: TextLayoutResult, at: Offset, bar: Color) {
    if (chrome.style is ChromeStyle.Luna) {
        val p = hairline()
        drawText(text, color = lerp(bar, chrome.darkShadow, 0.6f), topLeft = at + Offset(p, p))
    }
    drawText(text, topLeft = at)
}

/** A button's face filling the draw area; [over] is the mouse on it. */
internal fun DrawScope.paintButton(chrome: Chrome, enabled: Boolean, over: Boolean) {
    when (val style = chrome.style) {
        ChromeStyle.Bevel -> {
            drawRect(chrome.face)
            bevel(0f, 0f, size.width, size.height, chrome.light, chrome.midLight, chrome.shadow, chrome.darkShadow)
        }
        is ChromeStyle.Luna -> {
            val p = hairline()
            val r = CornerRadius(3.dp.toPx())
            val inset = Offset(p / 2, p / 2)
            val box = Size(size.width - p, size.height - p)
            drawRoundRect(Brush.verticalGradient(listOf(chrome.light, lerp(chrome.face, chrome.shadow, 0.25f))), inset, box, r)
            if (enabled && over) drawRoundRect(style.glow, inset + Offset(p, p), Size(box.width - 2 * p, box.height - 2 * p), r, style = Stroke(2 * p))
            drawRoundRect(if (enabled) style.outline else chrome.shadow, inset, box, r, style = Stroke(p))
        }
    }
}

/** The status line's panel. */
internal fun DrawScope.paintStatusPanel(chrome: Chrome) {
    when (chrome.style) {
        ChromeStyle.Bevel -> sunken(chrome, 0f, 0f, size.width, size.height)
        // XP's status bar is the face itself, set off by a line above.
        is ChromeStyle.Luna -> drawRect(chrome.shadow, Offset.Zero, Size(size.width, hairline()))
    }
}

/**
 * The outline a pane or card frame is cut to: Luna's rounded top corners, so
 * the window behind shows there; square for every other look. Applied before
 * the frame's background, so the background is cut too.
 */
@Composable
fun Modifier.chromeShape(): Modifier {
    val chrome = Palette.chrome ?: return this
    if (chrome.style !is ChromeStyle.Luna) return this
    val r = with(LocalDensity.current) { lunaRadius(LocalCells.current.height).toDp() }
    return clip(RoundedCornerShape(topStart = r, topEnd = r))
}

/** A rectangle's edge two lines deep: [outerLit]/[innerLit] along the top and left, [innerShade]/[outerShade] along the bottom and right. */
private fun DrawScope.bevel(left: Float, top: Float, right: Float, bottom: Float, outerLit: Color, innerLit: Color, innerShade: Color, outerShade: Color) {
    val p = hairline()
    fun edge(inset: Float, lit: Color, shade: Color) {
        drawRect(lit, Offset(left + inset, top + inset), Size(right - left - 2 * inset, p))
        drawRect(lit, Offset(left + inset, top + inset), Size(p, bottom - top - 2 * inset))
        drawRect(shade, Offset(left + inset, bottom - inset - p), Size(right - left - 2 * inset, p))
        drawRect(shade, Offset(right - inset - p, top + inset), Size(p, bottom - top - 2 * inset))
    }
    edge(0f, outerLit, outerShade)
    edge(p, innerLit, innerShade)
}

private fun DrawScope.sunken(chrome: Chrome, left: Float, top: Float, right: Float, bottom: Float) =
    bevel(left, top, right, bottom, chrome.shadow, chrome.darkShadow, chrome.midLight, chrome.light)
