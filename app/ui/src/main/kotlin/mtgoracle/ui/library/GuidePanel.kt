package mtgoracle.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.theme.Palette

/**
 * One step of the getting-started checklist, as the app finds things now:
 * [done] is read from the app's own state (cards synced, Forge up, a deck,
 * a game), never ticked by hand. [actions] are buttons, `label to name`; a
 * name is a `guide:` control the screen acts on. An [info] step has no box:
 * it is a pointer, not a thing to do.
 */
data class GuideStep(val title: String, val status: String, val done: Boolean, val actions: List<Pair<String, String>> = emptyList(), val info: Boolean = false)

/** The checklist's own controls, which LibraryScreen acts on. */
object GuideControls {
    const val SYNC = "guide:sync"
    const val NEW_DECK = "guide:new-deck"
    const val IMPORT = "guide:import"
    const val LOBBY = "guide:lobby"
    const val HELP = "guide:help"
    const val TOUR = "guide:tour"
    const val HIDE = "guide:hide"
}

/**
 * Getting started, in the library's middle column: the steps from no data
 * to a first game, each ticking itself as the app gets there, with the button
 * that does it. Hide puts it away for good; Guide in the toolbar (or `guide`)
 * brings it back.
 */
@Composable
internal fun GuidePanel(steps: List<GuideStep>, cols: Int, onClick: (ClickTarget) -> Unit) {
    Column {
        WrapText("From no card data to your first game. Each step ticks itself as the app gets there.", color = Palette.dim)
        GridText("")
        var n = 0
        steps.forEach { step ->
            val mark = when { step.info -> "  ·"; step.done -> "[x]"; else -> "[ ]" }
            val title = if (step.info) step.title else "${++n}. ${step.title}"
            FitText("$mark $title", color = if (step.done) Palette.dim else Palette.accent, bold = !step.done)
            WrapText("      ${step.status}", color = if (step.done) Palette.dim else Palette.foreground)
            if (step.actions.isNotEmpty() && !step.done) Row {
                GridText("      ")
                step.actions.forEach { (label, name) -> LinkButton("[ $label ]", ClickTarget.Control(name), onClick); GridText(" ") }
            }
            GridText("")
        }
        RuleLine(cols)
        Row {
            LinkButton("[ Show me around ]", ClickTarget.Control(GuideControls.TOUR), onClick)
            GridText("  ")
            LinkButton("[ Hide the guide ]", ClickTarget.Control(GuideControls.HIDE), onClick)
            GridText("  Guide in the toolbar, or `guide`, brings it back", color = Palette.dim)
        }
    }
}
