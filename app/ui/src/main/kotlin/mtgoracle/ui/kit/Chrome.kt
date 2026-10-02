package mtgoracle.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import mtgoracle.ui.theme.Cells
import mtgoracle.ui.theme.Chrome
import mtgoracle.ui.theme.ChromeFont
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.gridStyle
import kotlin.math.floor

/** Which title bar a pane gets in a drawn chrome: the one with the user's attention, a tapped card, or any other. */
enum class TitleBar { ACTIVE, TAPPED, INACTIVE }

/** The chrome's text in [family]: sized to sit inside one grid row. */
internal fun chromeTextStyle(family: String, cells: Cells, density: Density, color: Color, bold: Boolean = false): TextStyle = with(density) {
    TextStyle(
        fontFamily = ChromeFont.family(family), color = color, fontWeight = if (bold) FontWeight.Bold else null,
        fontSize = (cells.height * 0.78f).toSp(), lineHeight = (cells.height * 0.8f).toSp(),
    )
}

/**
 * The pane border of a drawn chrome, as a window of its era: a frame, a
 * title bar in the top row, the content inside (ChromePaint has each
 * style's shapes). All of it inside the one-cell ring the character border
 * takes, so content sits where it always did.
 */
@Composable
internal fun Modifier.drawnBorder(chrome: Chrome, title: String?, right: String?, bar: TitleBar): Modifier {
    val cells = LocalCells.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val (barColor, barText) = when (bar) {
        TitleBar.ACTIVE -> chrome.title to chrome.titleText
        TitleBar.TAPPED -> Palette.tapped to chrome.titleText
        TitleBar.INACTIVE -> chrome.inactiveTitle to chrome.inactiveTitleText
    }
    val titleStyle = chromeTextStyle(chrome.titleFont, cells, density, barText, bold = chrome.titleBold)
    val badgeStyle = chromeTextStyle(chrome.titleFont, cells, density, barText)
    return drawWithCache {
        val cols = cells.cols(size.width)
        val rows = cells.rows(size.height)
        val pad = 2 * hairline()
        val barRect = titleBarRect(chrome, size.width, cells.height)
        fun line(text: String?, style: TextStyle, maxWidth: Float): TextLayoutResult? = text?.takeIf { it.isNotBlank() && maxWidth > 0 }?.let {
            measurer.measure(chromeSafe(it), style, TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = maxWidth.toInt()))
        }
        val room = barRect.width - 2 * pad
        val badge = line(right, badgeStyle, room / 2)
        val head = line(title, titleStyle, room - (badge?.let { it.size.width + 2 * pad } ?: 0f))
        onDrawWithContent {
            drawContent()
            if (cols < 2 || rows < 2) return@onDrawWithContent
            paintPaneFrame(chrome, barColor, bar, cells.height)
            head?.let { paintTitleText(chrome, it, Offset(barRect.left + pad, barRect.top + (barRect.height - it.size.height) / 2), barColor) }
            badge?.let { paintTitleText(chrome, it, Offset(barRect.right - pad - it.size.width, barRect.top + (barRect.height - it.size.height) / 2), barColor) }
        }
    }
}

/** What the chrome draws of a title: the guard keeps a glyph the font lacks from becoming a box. */
private fun chromeSafe(text: String) = mtgoracle.ui.theme.GlyphGuard.safe(text.replace('\n', ' '))

/**
 * A control: [text] as written (`[ OK ]`, `[-]`) in the house look, inverted;
 * a button of the chrome's style in a drawn one, its label without the
 * brackets. The same cells either way. A disabled one is dim and can't be clicked.
 */
@Composable
fun ControlButton(text: String, target: ClickTarget, enabled: Boolean, onClick: (ClickTarget) -> Unit) {
    val modifier = if (enabled) Modifier.clickTarget(target, onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier
    val chrome = Palette.chrome
    if (chrome == null) {
        if (enabled) GridText(text, modifier, color = Palette.background, background = Palette.foreground)
        else GridText(text, modifier, color = Palette.dim)
        return
    }
    val label = text.removePrefix("[").removeSuffix("]").trim()
    val style = chromeTextStyle(chrome.font, LocalCells.current, LocalDensity.current, if (enabled) Palette.foreground else chrome.shadow)
    Box(modifier.drawBehind { paintButton(chrome, enabled, over = false) }, contentAlignment = Alignment.Center) {
        // Sized by the very line the house look draws, unseen: cells × width rounds differently, and a row of buttons drifted a pixel each.
        GridText(text, color = Color.Transparent)
        BasicText(chromeSafe(label), style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** How tall a [BigButton] is, in rows: big enough to hit without aiming. */
const val BUTTON_ROWS = 2

/**
 * A button for what is pressed often (a toolbar's, a prompt's OK, a dialog's):
 * [BUTTON_ROWS] tall and two cells of room either side of [label]. In the
 * house look an inverted block that takes the accent under the mouse; in a
 * drawn chrome a button of its style. The same cells either way.
 */
@Composable
fun BigButton(label: String, target: ClickTarget, enabled: Boolean, onClick: (ClickTarget) -> Unit) {
    var over by remember { mutableStateOf(false) }
    val chrome = Palette.chrome
    var modifier = Modifier.cells(label.length + 4, BUTTON_ROWS)
    if (enabled) modifier = modifier.clickTarget(target, onClick, mark = false, onHoverChange = { over = it }).pointerHoverIcon(PointerIcon.Hand)
    RecordText(label)
    if (chrome == null) {
        val fill = when { !enabled -> Color.Unspecified; over -> Palette.accent; else -> Palette.foreground }
        Box(modifier.drawBehind { if (fill != Color.Unspecified) drawRect(fill) else drawRect(Palette.dim, style = Stroke(hairline())) }, contentAlignment = Alignment.Center) {
            BasicText(chromeSafe(label), style = gridStyle.copy(color = if (enabled) Palette.background else Palette.dim), softWrap = false, maxLines = 1)
        }
        return
    }
    val style = chromeTextStyle(chrome.font, LocalCells.current, LocalDensity.current, if (enabled) Palette.foreground else chrome.shadow)
    Box(modifier.drawBehind { paintButton(chrome, enabled, over) }, contentAlignment = Alignment.Center) {
        BasicText(chromeSafe(label), style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * A rule across [cols] cells, [label] at its start and [end] at its end:
 * `─ lands ────`, `═══ turn 7 … ═══` ([heavy]) in the house look. A drawn
 * chrome keeps every character but the rule's own, and etches a line (a
 * group box's edge) under the cells they took, so the text stays put.
 */
@Composable
fun RuleLine(cols: Int, modifier: Modifier = Modifier, label: String? = null, end: String? = null, heavy: Boolean = false, color: Color = Palette.dim, bold: Boolean = false) {
    val h = if (heavy) '═' else '─'
    val lead = h.toString().repeat(if (heavy) 3 else 1)
    val head = label?.let { "$lead $it " }.orEmpty()
    val tail = end?.let { " $it $lead" }.orEmpty()
    val line = fit(head + h.toString().repeat(maxOf(0, cols - head.length - tail.length)) + tail, cols)
    val chrome = Palette.chrome
    if (chrome == null) { GridText(line, modifier, color = color, bold = bold); return }
    val cells = LocalCells.current
    GridText(line.replace(h, ' '), modifier.drawBehind {
        val p = hairline()
        val mid = floor(size.height / 2)
        var i = 0
        while (i < line.length) {
            if (line[i] != h) { i++; continue }
            val from = i
            while (i < line.length && line[i] == h) i++
            for (y in if (heavy) listOf(mid - 2 * p, mid + p) else listOf(mid)) paintRule(chrome, from * cells.width, y, (i - from) * cells.width)
        }
    }, color = color, bold = bold)
}

/** The status line's panel in a drawn chrome; nothing in the house look. */
fun Modifier.statusPanel(): Modifier = drawBehind {
    val chrome = Palette.chrome ?: return@drawBehind
    paintStatusPanel(chrome)
}
