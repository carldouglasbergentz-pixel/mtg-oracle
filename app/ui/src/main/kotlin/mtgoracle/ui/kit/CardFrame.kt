package mtgoracle.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import mtgoracle.core.art.ArtKind
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** How a card on the board or in a list stands out. */
enum class Emphasis { NONE, ACTIONABLE, SELECTABLE }

enum class CardMode { ART, TEXT }

/** Cells a card takes in each mode. */
object FrameSize {
    const val ART_COLS = 20
    const val ART_ROWS = 9
    const val ART_ROWS_OF_ART = 4
    const val TEXT_COLS = 22
    const val TEXT_ROWS = 5
    /** Lands are many: a narrower frame, same height, so a lane stays one height. */
    const val LAND_ART_COLS = 16
    const val LAND_TEXT_COLS = 18
    /** The compact art frame: two rows of art, the credit, and cost, type and stats on one line. */
    const val COMPACT_ROWS = 6
    const val COMPACT_ROWS_OF_ART = 2
    fun cols(mode: CardMode, land: Boolean = false) = cols(tierOf(mode), land)
    fun rows(mode: CardMode) = rows(tierOf(mode))
    fun cols(tier: FrameTier, land: Boolean = false) = when (tier) {
        FrameTier.FULL, FrameTier.COMPACT -> if (land) LAND_ART_COLS else ART_COLS
        FrameTier.TEXT -> if (land) LAND_TEXT_COLS else TEXT_COLS
    }
    fun rows(tier: FrameTier) = when (tier) {
        FrameTier.FULL -> ART_ROWS
        FrameTier.COMPACT -> COMPACT_ROWS
        FrameTier.TEXT -> TEXT_ROWS
    }
    /** A mode's own frame: art mode's full one, text mode's text one. */
    fun tierOf(mode: CardMode) = if (mode == CardMode.ART) FrameTier.FULL else FrameTier.TEXT
}

/**
 * How big a card frame is drawn. Art mode starts at [FULL] and the table
 * steps down — [COMPACT], then [TEXT] — when a half can't hold its cards
 * otherwise (see planTable); text mode is always [TEXT].
 */
enum class FrameTier { FULL, COMPACT, TEXT }

/**
 * A card in our own frame: its name in the top edge, then — in art mode —
 * Scryfall's art crop with its artist credit, then cost and type, and stats. The art
 * is scaled to fit, never cropped further or distorted; the full card image
 * (copyright line included) is the zoom pane's.
 *
 * A legal pick gets the double border in the accent colour, a card you could
 * act with a single accent border, a tapped card dims.
 */
@Composable
fun CardFrame(
    face: CardFace,
    mode: CardMode,
    emphasis: Emphasis = Emphasis.NONE,
    target: ClickTarget? = null,
    onClick: (ClickTarget) -> Unit = {},
    onHover: ((ClickTarget?) -> Unit)? = null,
    /** On the table: a tapped card sits one cell to the right, as if turned (the slot is one cell wider). */
    turnable: Boolean = false,
    /** The narrower land frame. */
    land: Boolean = false,
    /** Identical cards stacked in this one frame (MTGO-style): shown as "×N" in the top edge. */
    stack: Int = 1,
    /** A mark in the top edge, in the accent: `◄` (a stack item points at it), `*` (it just happened). */
    mark: String? = null,
    /**
     * On the table, in art mode: a tapped card is turned a quarter clockwise,
     * as a hand turns it, and scaled — the same factor both ways — to stay
     * inside its upright slot, so tapping moves nothing around it.
     */
    rotate: Boolean = false,
    /** How big (the table steps down when a half is crowded; see planTable). */
    tier: FrameTier = FrameSize.tierOf(mode),
    /** The mouse is on this card through something laid over it (an overlapped card's strip): drawn as if on the frame. */
    hovered: Boolean = false,
) {
    if (turnable) {
        val cells = LocalCells.current
        val slotCols = FrameSize.cols(tier, land) + 1
        val rows = FrameSize.rows(tier)
        Box(Modifier.cells(slotCols, rows)) {
            if (face.tapped && rotate && tier != FrameTier.TEXT) {
                // Turned, the frame is rows tall-by-cols wide in cells: fit its pixels into the slot's.
                val w = FrameSize.cols(tier, land) * cells.width
                val h = rows * cells.height
                val scale = minOf(1f, slotCols * cells.width / h, rows * cells.height / w)
                Box(Modifier.align(Alignment.Center).graphicsLayer { rotationZ = 90f; scaleX = scale; scaleY = scale }) {
                    CardFrame(face, mode, emphasis, target, onClick, onHover, turnable = false, land = land, stack = stack, mark = mark, tier = tier, hovered = hovered)
                }
            } else {
                Box(Modifier.padding(start = if (face.tapped) with(LocalDensity.current) { cells.width.toDp() } else 0.dp)) {
                    CardFrame(face, mode, emphasis, target, onClick, onHover, turnable = false, land = land, stack = stack, mark = mark, tier = tier, hovered = hovered)
                }
            }
        }
        return
    }
    val border = when {
        emphasis == Emphasis.SELECTABLE -> Border.DOUBLE
        face.tapped -> Border.DASHED
        else -> Border.SINGLE
    }
    // Tapped has a tone of its own (Theme.tapped); a pick or a mark outranks it.
    val borderColor = when {
        emphasis != Emphasis.NONE || mark != null -> Palette.accent
        face.tapped -> Palette.tapped
        else -> Palette.dim
    }
    val textColor = if (face.tapped) Palette.dim else Palette.foreground
    val cols = FrameSize.cols(tier, land)
    val inner = cols - 2
    val title = (if (face.quantity > 1) "${face.quantity} " else "") + face.name
    // Name alone in the top edge (names are long); the cost leads the type line.
    val costAndType = listOf(face.manaCost, face.typeLine).filter { it.isNotEmpty() }.joinToString(" ")
    // Hovered, the frame's own background takes the hover tone: a mark drawn behind it would be hidden, and one over it would tint the art.
    // Anew when the click target changes (an overlapped card loses its own): no Exit comes after it is gone.
    var over by remember(target) { mutableStateOf(false) }
    var modifier = Modifier.cells(cols, FrameSize.rows(tier)).chromeShape().background(if (over || hovered) Palette.hover else Palette.background)
        .boxBorder(title = title, right = listOfNotNull(mark, "×$stack".takeIf { stack > 1 }).joinToString(" ").ifEmpty { null },
            border = border, color = borderColor, titleColor = textColor)
    if (target != null) modifier = modifier.clickTarget(target, onClick, onHover, mark = false, onHoverChange = { over = it })
    val cells = LocalCells.current
    val density = LocalDensity.current
    Box(modifier) {
        Column(Modifier.padding(with(density) { cells.width.toDp() }, with(density) { cells.height.toDp() })) {
            if (face.hidden) {
                // A card back: the same cells, a plain pattern, nothing else.
                repeat(FrameSize.rows(tier) - 2) { r -> GridText((if (r % 2 == 0) "/\\" else "\\/").repeat(inner / 2 + 1).take(inner), color = Palette.dim) }
            } else if (tier == FrameTier.FULL) {
                ArtBox(face, inner, FrameSize.ART_ROWS_OF_ART)
                ArtistLine(face, inner)
                GridText(fit(costAndType, inner), color = textColor)
                StatsLine(face, inner, textColor)
            } else if (tier == FrameTier.COMPACT) {
                // A shorter crop with its credit; cost and type share the last line with the stats.
                ArtBox(face, inner, FrameSize.COMPACT_ROWS_OF_ART)
                ArtistLine(face, inner)
                StatsLine(face, inner, textColor, lead = costAndType)
            } else {
                GridText(fit(costAndType, inner), color = textColor)
                StatsLine(face, inner, textColor)
                GridText(fit(face.text, inner), color = Palette.dim)
            }
        }
    }
}

/** "2/2 sick", with TAPPED in the tapped tone so it can't be missed. */
@Composable
private fun StatsLine(face: CardFace, inner: Int, color: androidx.compose.ui.graphics.Color, lead: String = "") {
    // A compact frame shares the line with cost and type, stats first (P/T is what a truncated line must not lose): "2/2 {1}{G} Creature".
    val stats = listOf(face.stats.split(' ').filter { it != "TAPPED" }.joinToString(" "), lead).filter { it.isNotBlank() }.joinToString(" ")
    if (!face.tapped) { GridText(fit(stats, inner), color = color); return }
    androidx.compose.foundation.layout.Row {
        GridText("TAPPED", color = Palette.tapped, bold = true)
        GridText(fit(if (stats.isEmpty()) "" else " $stats", maxOf(0, inner - 6)), color = color)
    }
}

/** The artist's credit under the art: Scryfall's crops are shown credited, at every size. */
@Composable
private fun ArtistLine(face: CardFace, inner: Int) {
    val artist = face.imageKey?.let { LocalArt.current?.artist(it) }
    GridText(fit(artist?.let { "illus. $it" } ?: "", inner), color = Palette.dim)
}

/** The art crop, fitted; until it's on disk, the card's text in the same cells. */
@Composable
private fun ArtBox(face: CardFace, cols: Int, rows: Int) {
    val bitmap = rememberArt(face.imageKey, ArtKind.ART_CROP)
    Box(Modifier.cells(cols, rows), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = face.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                wrap(face.text, cols).take(rows).forEach { GridText(fit(it, cols), color = Palette.dim) }
            }
        }
    }
}

/** A land (or any card) as a one-line chip: `[Island T]`. */
@Composable
fun CardChip(
    face: CardFace,
    emphasis: Emphasis,
    target: ClickTarget,
    onClick: (ClickTarget) -> Unit,
    onHover: ((ClickTarget?) -> Unit)?,
    /** As on a frame, written before the chip. */
    mark: String? = null,
) {
    val prefix = mark.orEmpty()
    val color = when {
        emphasis != Emphasis.NONE || mark != null -> Palette.accent
        face.tapped -> Palette.tapped
        face.hidden || face.castLocked -> Palette.dim
        else -> Palette.foreground
    }
    // A tapped chip leans: /Island/ rather than [Island]. A legal pick gets «guillemets».
    val (open, close) = when {
        emphasis == Emphasis.SELECTABLE -> "«" to "»"
        face.tapped -> "/" to "/"
        else -> "[" to "]"
    }
    GridText(
        "$prefix$open${face.name}$close" + if (face.castLocked) " locked" else "",
        modifier = Modifier.clickTarget(target, onClick, onHover),
        color = color,
        bold = emphasis == Emphasis.SELECTABLE || mark != null,
        background = Color.Unspecified,
    )
}
