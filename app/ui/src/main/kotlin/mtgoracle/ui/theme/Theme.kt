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
    /** Drawn chrome (bevels, title bars, buttons) instead of box-drawing characters; null for the house look. */
    val chrome: Chrome? = null,
    /** Where the theme picker lists it. */
    val era: Era = Era.TERMINAL,
)

/** The theme picker's sections, in the order shown: the house look and its kin, then a journey through the years. */
enum class Era(val label: String) {
    TERMINAL("terminal"),
    Y1995("1995"),
    Y2001("2001"),
    Y2006("2006"),
    Y2009("2009"),
    TODAY("today"),
}

/**
 * A look that draws its own chrome, as a desktop of its era did: the tones
 * of its surfaces and edges and the face its titles and buttons are set in.
 * The chrome is drawn in the cells the character border takes, so a look
 * changes no layout. The panes' content stays on [Theme.background] in the
 * grid font.
 */
data class Chrome(
    /** The window's own surface: around and between panes, behind buttons and the status line. */
    val face: Color,
    /** The edges of a raised or sunken surface, lightest to darkest. */
    val light: Color, val midLight: Color, val shadow: Color, val darkShadow: Color,
    /** The title bar of the pane that has the user's attention (a prompt, a dialog, the command line typing), and of every other. */
    val title: Color, val titleText: Color, val inactiveTitle: Color, val inactiveTitleText: Color,
    /** The families buttons (and titles, unless [titleFont]) are set in, the first installed of a comma-separated list; the grid font when none is. */
    val font: String,
    /** The title bars' families where they differ (XP set them in Trebuchet MS, its buttons in Tahoma). */
    val titleFont: String = font,
    /** Titles in bold (95, XP), or not (Vista and 7 set them in plain Segoe UI). */
    val titleBold: Boolean = true,
    val style: ChromeStyle = ChromeStyle.Bevel,
)

/** How a [Chrome] draws its shapes, with the tones only that shape needs. */
sealed interface ChromeStyle {
    /** Windows 95: edges two lines deep, square corners, a flat title bar. */
    data object Bevel : ChromeStyle

    /**
     * Windows XP's Luna: a title bar with rounded top corners, shaded top to
     * bottom, the window framed in its title's colour; rounded buttons with an
     * [outline] that [glow] under the mouse.
     */
    data class Luna(val outline: Color, val glow: Color) : ChromeStyle

    /**
     * Vista's and 7's Aero: title bars and frames of tinted glass with a
     * sheen on their upper half, the title in black on a white glow; buttons
     * shaded in two halves with an [outline], [hoverFace] and [hoverEdge]
     * under the mouse.
     */
    data class Aero(val outline: Color, val hoverFace: Color, val hoverEdge: Color) : ChromeStyle

    /**
     * KDE's Breeze: flat surfaces, thin rounded outlines; what has the user's
     * attention is outlined in the accent, as Breeze marks focus.
     */
    data object Breeze : ChromeStyle
}

object Themes {
    val HOUSE = Theme("house", "house", Color(0xFF111315), Color(0xFFD7D7D2), Color(0xFF7C8084), Color(0xFFE2B350), Color(0xFFEF5B53))
    /** Rosé Pine's published main palette: base, text, muted, gold; love as the tapped tone. */
    val ROSE_PINE = Theme("rose-pine", "rosé pine", Color(0xFF191724), Color(0xFFE0DEF4), Color(0xFF6E6A86), Color(0xFFF6C177), Color(0xFFEB6F92))
    val PAPER = Theme("paper", "paper (light)", Color(0xFFF7F5EF), Color(0xFF24262A), Color(0xFF74777C), Color(0xFFA24E12), Color(0xFFB42318))
    /** The VS Code Dark+ editor tones, its keyword blue as the accent, its error red for tapped. */
    val CODE_DARK = Theme("code-dark", "code dark", Color(0xFF1E1E1E), Color(0xFFD4D4D4), Color(0xFF808080), Color(0xFF569CD6), Color(0xFFF44747))
    /** Solarized dark: base03, base1, base01, yellow; its red, lightened to read on base03 (4.8:1, where the published red is 3.3:1). */
    val SOLARIZED = Theme("solarized-dark", "solarized dark", Color(0xFF002B36), Color(0xFF93A1A1), Color(0xFF586E75), Color(0xFFB58900), Color(0xFFF2645D))

    /**
     * Windows 95's standard scheme: the white of its list views for the panes, its button face around them, navy title bars.
     * One departure: an inactive title is white, not 95's light grey on grey (1.9:1), because a card's name is in its title.
     */
    val WIN95 = Theme(
        "win95", "windows 95", Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF6B6B6B), Color(0xFF000080), Color(0xFFC00000),
        Chrome(
            face = Color(0xFFC0C0C0), light = Color(0xFFFFFFFF), midLight = Color(0xFFDFDFDF), shadow = Color(0xFF808080), darkShadow = Color(0xFF000000),
            title = Color(0xFF000080), titleText = Color(0xFFFFFFFF), inactiveTitle = Color(0xFF808080), inactiveTitleText = Color(0xFFFFFFFF),
            font = "Microsoft Sans Serif",
        ),
        Era.Y1995,
    )

    /**
     * Windows XP's Luna in blue: the beige face, the blue title bars, Tahoma and Trebuchet MS, XP's selection blue as the accent.
     * One departure: an inactive title is a deeper blue than Luna's (3.9:1 against white, not 2.9:1), because a card's name is in it.
     */
    val XP = Theme(
        "xp", "windows xp", Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF6B6B6B), Color(0xFF316AC5), Color(0xFFC00000),
        Chrome(
            face = Color(0xFFECE9D8), light = Color(0xFFFFFFFF), midLight = Color(0xFFF1EFE2), shadow = Color(0xFFACA899), darkShadow = Color(0xFF716F64),
            title = Color(0xFF0054E3), titleText = Color(0xFFFFFFFF), inactiveTitle = Color(0xFF5F7FD6), inactiveTitleText = Color(0xFFFFFFFF),
            font = "Tahoma", titleFont = "Trebuchet MS",
            style = ChromeStyle.Luna(outline = Color(0xFF003C74), glow = Color(0xFFF8B330)),
        ),
        Era.Y2001,
    )

    /** Aero's glass buttons and its blue, shared by Vista and 7: they differ in the glass. */
    private val AERO = ChromeStyle.Aero(outline = Color(0xFF707070), hoverFace = Color(0xFFBEE6FD), hoverEdge = Color(0xFF3C7FB1))

    /**
     * Windows Vista: its smoky glass, the light face of its dialogs, Segoe UI; 7's link blue as the accent
     * (Aero's selection blue, #3399FF, reads at 2.9:1 as text on white).
     */
    val VISTA = Theme(
        "vista", "windows vista", Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF6B6B6B), Color(0xFF0066CC), Color(0xFFC00000),
        Chrome(
            face = Color(0xFFF0F0F0), light = Color(0xFFFFFFFF), midLight = Color(0xFFF7F7F7), shadow = Color(0xFFA0A0A0), darkShadow = Color(0xFF3A3A3A),
            title = Color(0xFF52707F), titleText = Color(0xFF000000), inactiveTitle = Color(0xFF8B9DA8), inactiveTitleText = Color(0xFF3A3A3A),
            font = "Segoe UI", titleBold = false, style = AERO,
        ),
        Era.Y2006,
    )

    /** Windows 7: Vista's look under its default "Sky" glass. */
    val SEVEN = VISTA.copy(
        key = "seven", label = "windows 7",
        chrome = VISTA.chrome!!.copy(title = Color(0xFF5B8ED0), inactiveTitle = Color(0xFF9DB8DC)),
        era = Era.Y2009,
    )

    /**
     * KDE Plasma's Breeze Dark, the desktop of SteamOS and Bazzite: its window and view greys, its blue, Noto Sans (Segoe UI where it isn't installed).
     * Breeze's negative red (#DA4453) is lightened to read on the view (5.6:1, where Breeze's own is 4.0:1).
     */
    val BREEZE = Theme(
        "breeze-dark", "breeze dark (KDE)", Color(0xFF1B1E20), Color(0xFFFCFCFC), Color(0xFFA1A9B1), Color(0xFF3DAEE9), Color(0xFFEE6A75),
        Chrome(
            face = Color(0xFF2A2E32), light = Color(0xFFFCFCFC), midLight = Color(0xFF3B4045), shadow = Color(0xFF4D5257), darkShadow = Color(0xFF232629),
            title = Color(0xFF31363B), titleText = Color(0xFFFCFCFC), inactiveTitle = Color(0xFF2A2E32), inactiveTitleText = Color(0xFFA1A9B1),
            font = "Noto Sans, Segoe UI", style = ChromeStyle.Breeze,
        ),
        Era.TODAY,
    )

    val ALL = listOf(HOUSE, ROSE_PINE, PAPER, CODE_DARK, SOLARIZED, WIN95, XP, VISTA, SEVEN, BREEZE)

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
    val chrome: Chrome? get() = theme.chrome
    /** The window behind the panes: the chrome's face, or the background where the panes are drawn in characters. */
    val surface: Color get() = theme.chrome?.face ?: theme.background
    /** Text set straight on [surface] (the status line): dim in the house look, where it sits on the background; full on a face, where dim would not read. */
    val surfaceText: Color get() = if (theme.chrome != null) theme.foreground else theme.dim
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

/** The chrome's own faces (titles, buttons), by family name, each looked up once. */
object ChromeFont {
    private val families = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

    /** The first installed of [names] (comma-separated), or the grid font. */
    fun family(names: String): FontFamily = families.getOrPut(names) {
        names.split(',').map { it.trim() }.firstNotNullOfOrNull { name ->
            FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL)?.takeIf { it.familyName.equals(name, ignoreCase = true) }
        }?.let { FontFamily(Typeface(it)) } ?: HouseFont.family
    }
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
