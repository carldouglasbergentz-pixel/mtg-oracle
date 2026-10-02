package mtgoracle.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * A colour scheme in the house rule (docs/app-design.md): two tones — a
 * background and a foreground, with a dimmed foreground between them — and
 * one accent. Card art is the only other colour. These definitions are the
 * only colour literals in the UI (ThemeTest enforces it).
 */
data class Theme(
    val key: String, val label: String, val background: Color, val foreground: Color, val dim: Color, val accent: Color,
    /** A status tone beside the two tones: tapped (its label and frame). At least 4.5:1 against [background] (ThemeAndPanesTest). */
    val tapped: Color,
)

object Themes {
    val HOUSE = Theme("house", "house", Color(0xFF111315), Color(0xFFD7D7D2), Color(0xFF7C8084), Color(0xFFE2B350), Color(0xFFEF5B53))
    /** Rosé Pine's published main palette: base, text, muted, gold; love as the tapped tone. */
    val ROSE_PINE = Theme("rose-pine", "rosé pine", Color(0xFF191724), Color(0xFFE0DEF4), Color(0xFF6E6A86), Color(0xFFF6C177), Color(0xFFEB6F92))
    val PAPER = Theme("paper", "paper (light)", Color(0xFFF7F5EF), Color(0xFF24262A), Color(0xFF74777C), Color(0xFFA24E12), Color(0xFFB42318))
    /** The VS Code Dark+ editor tones, its keyword blue as the accent, its error red for tapped. */
    val CODE_DARK = Theme("code-dark", "code dark", Color(0xFF1E1E1E), Color(0xFFD4D4D4), Color(0xFF808080), Color(0xFF569CD6), Color(0xFFF44747))
    /** Solarized dark: base03, base1, base01, yellow; its red, lightened to read on base03 (4.8:1, where the published red is 3.3:1). */
    val SOLARIZED = Theme("solarized-dark", "solarized dark", Color(0xFF002B36), Color(0xFF93A1A1), Color(0xFF586E75), Color(0xFFB58900), Color(0xFFF2645D))

    val ALL = listOf(HOUSE, ROSE_PINE, PAPER, CODE_DARK, SOLARIZED)

    fun byKey(key: String?): Theme? = ALL.firstOrNull { it.key == key }

    /**
     * The retired Python TUI's saved Textual theme (`data/config.json` → "theme"),
     * as the nearest of ours: its own name when we have it, a light one for
     * Textual's light themes, else null.
     */
    fun fromTextual(name: String?): Theme? = when {
        name == null -> null
        byKey(name) != null -> byKey(name)
        name.startsWith("solarized") && !name.endsWith("light") -> SOLARIZED
        name.endsWith("light") || name.endsWith("dawn") || name.endsWith("latte") -> PAPER
        name.startsWith("rose-pine") -> ROSE_PINE
        else -> null
    }
}

/**
 * The colours of the current [Theme]. Read in composition, so switching
 * [theme] redraws every screen; there is one window and one theme.
 */
object Palette {
    var theme: Theme by mutableStateOf(Themes.HOUSE)
    val background: Color get() = theme.background
    val foreground: Color get() = theme.foreground
    val dim: Color get() = theme.dim
    val accent: Color get() = theme.accent
    val tapped: Color get() = theme.tapped
    /** Behind whatever the mouse is on: the background leaning toward the accent, so every tone still reads on it. */
    val hover: Color get() = lerp(theme.background, theme.accent, 0.25f)
}

/** Key hints every screen's status line ends with (the theme key), provided by the app window. */
val LocalGlobalHints = staticCompositionLocalOf<List<Pair<String, String>>> { emptyList() }

/**
 * The one monospace face everything is drawn in, and the Skia typeface the
 * [GlyphGuard] checks against — the same object, so "the guard passed" means
 * "this font draws it". The first installed of the preferred families wins.
 */
object HouseFont {
    private val PREFERRED = arrayOf("Consolas", "JetBrains Mono", "Cascadia Mono", "DejaVu Sans Mono", "Menlo", "Courier New")

    val typeface: org.jetbrains.skia.Typeface by lazy {
        PREFERRED.firstNotNullOfOrNull { name ->
            FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL)?.takeIf { it.familyName.equals(name, ignoreCase = true) }
        } ?: FontMgr.default.matchFamiliesStyle(arrayOf("monospace"), FontStyle.NORMAL)
            ?: error("no monospace font installed")
    }

    val family: FontFamily by lazy { FontFamily(Typeface(typeface)) }
}

val gridStyle: TextStyle
    get() = TextStyle(fontFamily = HouseFont.family, fontSize = 13.sp, lineHeight = 16.sp, color = Palette.foreground)

/** One character cell of the grid, in pixels. Every pane and card frame is sized in cells. */
data class Cells(val width: Float, val height: Float) {
    fun cols(px: Float): Int = (px / width).toInt()
    fun rows(px: Float): Int = (px / height).toInt()
}

val LocalCells = staticCompositionLocalOf { Cells(7f, 16f) }

/** Measures the cell once for this density and provides it to everything below. */
@Composable
fun HouseTheme(content: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    val cells = remember(measurer) {
        val layout = measurer.measure("M".repeat(100), gridStyle)
        Cells(layout.size.width / 100f, layout.size.height.toFloat())
    }
    CompositionLocalProvider(LocalCells provides cells, content = content)
}
