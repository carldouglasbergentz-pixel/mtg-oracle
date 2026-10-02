package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.MIN_FIELD_COLS
import mtgoracle.ui.board.MIN_SIDE_COLS
import mtgoracle.ui.board.MIN_ZONE_COLS
import mtgoracle.ui.board.PANE_STEP
import mtgoracle.ui.board.SIDE_COLS
import mtgoracle.ui.board.clampedTo
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Themes
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

import kotlin.test.assertTrue

/** Themes reach every pixel the UI draws; the side panes resize, within the window. */
class ThemeAndPanesTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))
    private val zoom = ClickTarget.Control("region:zoom")
    private val nearZones = ClickTarget.Control("region:near-zones")

    @AfterTest fun houseTheme() { Palette.theme = Themes.HOUSE }

    private fun board(seat: FakeSeat, layout: BoardLayout = BoardLayout(), width: Int = 1800, onLayout: (BoardLayout) -> Unit = {}) =
        OffscreenDriver(width, 1600) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART, layout = layout, onLayoutChange = onLayout) }
        }

    @Test
    fun `every theme draws the whole board in its own colours, and no other theme's`() {
        for (theme in Themes.ALL) {
            Palette.theme = theme
            val file = File(pngDir, "theme-${theme.key}.png")
            board(FakeSeat(sampleBoard(), null)).use { it.settle(5); it.savePng(file) }
            val image = ImageIO.read(file)
            assertEquals(theme.background.toArgb(), image.getRGB(900, 300), "${theme.key}: the table's background")
            // A colour any other theme defines (and this one doesn't), drawn exactly, would be a colour the theme didn't reach.
            fun tones(t: mtgoracle.ui.theme.Theme) = listOf(t.background, t.foreground, t.accent).map { it.toArgb() }
            val own = (tones(theme) + listOfNotNull(theme.chrome).flatMap { c -> listOf(c.face, c.light, c.midLight, c.shadow, c.darkShadow, c.title, c.titleText, c.inactiveTitle, c.inactiveTitleText).map { it.toArgb() } }).toSet()
            // An antialiased edge between two of the theme's own tones can land on any colour between them (black text on white makes every grey).
            fun blend(c: Int) = own.any { a -> own.any { b -> between(a, b, c) } }
            val others = (Themes.ALL.filter { it != theme }.flatMap(::tones).toSet() - own).filterNot(::blend).toSet()
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val strays = pixels.count { it in others }
            assertEquals(0, strays, "${theme.key}: $strays pixels in another theme's colours")
        }
    }

    /** Whether [c] lies on the line from [a] to [b], one step of rounding allowed per channel. */
    private fun between(a: Int, b: Int, c: Int): Boolean {
        fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
        val ts = listOf(16, 8, 0).mapNotNull { sh -> (ch(b, sh) - ch(a, sh)).takeIf { it != 0 }?.let { (ch(c, sh) - ch(a, sh)).toFloat() / it } }
        val t = ts.firstOrNull() ?: return a == c
        return t in 0f..1f && listOf(16, 8, 0).all { sh -> kotlin.math.abs(ch(a, sh) + t * (ch(b, sh) - ch(a, sh)) - ch(c, sh)) <= 1.5f }
    }

    @Test
    fun `a drawn chrome moves nothing - every region and card sits where the house look puts it`() {
        fun layout(theme: mtgoracle.ui.theme.Theme): Map<ClickTarget, androidx.compose.ui.geometry.Rect> {
            Palette.theme = theme
            return board(FakeSeat(sampleBoard(), null)).use { d -> d.settle(5); d.registry.targets.associateWith { d.registry[it]!! } }
        }
        val house = layout(Themes.HOUSE)
        assertTrue(house.size > 20, "the board registers its regions and cards: ${house.size}")
        for (theme in Themes.ALL.filter { it.chrome != null }) assertEquals(house, layout(theme), "${theme.key}: the layout")
    }

    @Test
    fun `every theme's tapped tone reads against its background - at least four and a half to one`() {
        fun lum(c: androidx.compose.ui.graphics.Color) = listOf(c.red, c.green, c.blue)
            .map { if (it <= 0.03928f) it / 12.92f else Math.pow(((it + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
            .let { (r, g, b) -> 0.2126f * r + 0.7152f * g + 0.0722f * b }
        for (t in Themes.ALL) {
            val (hi, lo) = listOf(lum(t.tapped), lum(t.background)).sortedDescending()
            val ratio = (hi + 0.05f) / (lo + 0.05f)
            assertTrue(ratio >= 4.5f, "${t.key}: tapped tone ${"%.2f".format(ratio)}:1")
            assertTrue(t.tapped != t.accent && t.tapped != t.dim, "${t.key}: a tone of its own")
        }
    }

    @Test
    fun `the UI's only colour literals are the theme definitions`() {
        val kit = File(System.getProperty("mtgoracle.repoRoot"), "app/ui/src/main/kotlin")
        val literal = Regex("""Color\(0x|Color\.(White|Black|Red|Green|Blue|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta)\b|Color\(\s*\d""")
        val found = kit.walkTopDown().filter { it.extension == "kt" && it.name != "Theme.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (literal.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null } }
            .toList()
        assertEquals(emptyList(), found)
    }

    @Test
    fun `pane widths clamp to their bounds and to the window`() {
        assertEquals(BoardLayout(), BoardLayout().clampedTo(257), "the defaults fit a normal window")
        val huge = BoardLayout(zoneCols = 500, sideCols = 500).clampedTo(257)
        assertTrue(huge.zoneCols + huge.sideCols + MIN_FIELD_COLS <= 257, "the table keeps its room: $huge")
        val tiny = BoardLayout(zoneCols = 1, sideCols = 1).clampedTo(257)
        assertEquals(MIN_ZONE_COLS, tiny.zoneCols); assertEquals(MIN_SIDE_COLS, tiny.sideCols)
        val narrow = BoardLayout(sideCols = 80).clampedTo(160)
        assertEquals(160 - MIN_ZONE_COLS - MIN_FIELD_COLS, narrow.sideCols, "a narrow window takes it from the side column")
    }

    @Test
    fun `Ctrl+arrows and dragging a border resize the panes, and the new widths go to the app`() {
        var saved = BoardLayout()
        board(FakeSeat(quietBoard(), null), onLayout = { saved = it }).use { d ->
            d.settle(5)
            val cell = d.registry[zoom]!!.width / SIDE_COLS
            d.key(Key.DirectionLeft, ctrl = true)
            assertEquals(SIDE_COLS + PANE_STEP, saved.sideCols, "Ctrl+← moves the right column's edge left")
            assertEquals((SIDE_COLS + PANE_STEP) * cell, d.registry[zoom]!!.width, 0.5f)
            val zones = d.registry[nearZones]!!.width
            d.key(Key.DirectionRight, ctrl = true, shift = true)
            assertEquals(zones + PANE_STEP * cell, d.registry[nearZones]!!.width, 0.5f, "Ctrl+Shift+→ widens the zone columns")
            val before = saved.sideCols
            d.drag(ClickTarget.Control("region:side-edge"), Offset(-10 * cell, 0f))
            assertEquals(before + 10, saved.sideCols, "dragging the right column's edge ten cells left")
        }
        // A remembered width a smaller window can't hold is clamped where it is drawn.
        board(FakeSeat(quietBoard(), null), BoardLayout(sideCols = 150), width = 1400).use { d ->
            d.settle(5)
            val cell = d.registry[nearZones]!!.width / MIN_ZONE_COLS
            assertTrue(d.registry[zoom]!!.right <= 1400f, "the right column stays in the window")
            assertTrue(d.registry[ClickTarget.Control("region:near-field")]!!.width >= MIN_FIELD_COLS * cell - 1, "and the table keeps its room")
        }
    }
}
