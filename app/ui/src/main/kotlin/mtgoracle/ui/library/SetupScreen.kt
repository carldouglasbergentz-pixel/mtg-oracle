package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import mtgoracle.core.deck.DeckSummary
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** A simulation as the setup screen shows it: its status line, and whether it is still going. */
data class SimLine(val line: String, val running: Boolean)

/** An opponent the setup screen offers, with what its AI copy changes. */
data class OpponentChoice(
    val deck: DeckSummary,
    val substitutions: Int,
    /** Your deck's record against this one: `you 3–2 · AI 12–7`, or null before a game. */
    val record: String? = null,
)

/**
 * Pick the opponent for [me]. The AI plays its AI copy (the deck's
 * `forge_substitutions` applied) when there is one, unless switched off.
 * [notes] are the deck checks: cards Forge lacks, cards its AI won't play.
 */
@Composable
fun SetupScreen(
    me: DeckSummary,
    opponents: List<OpponentChoice>,
    selectedId: Int?,
    useAiCopy: Boolean,
    watch: Boolean,
    notes: List<String>,
    forgeReady: Boolean,
    canStart: Boolean,
    onSelect: (Int) -> Unit,
    onToggleAiCopy: () -> Unit,
    onToggleWatch: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    /** "best of 3"; B cycles it (watching is always one game). */
    format: String = "best of 1",
    onCycleFormat: () -> Unit = {},
    /** How many games S simulates; N cycles it. */
    simGames: Int = 10,
    onCycleSimGames: () -> Unit = {},
    /** The simulation running (or the last one): its line, and whether S stops it. */
    simulation: SimLine? = null,
    onSimulate: () -> Unit = {},
    onStopSimulation: () -> Unit = {},
) {
    val simRunning = simulation?.running == true
    val focus = remember { FocusRequester() }
    val onClick: (ClickTarget) -> Unit = { t ->
        val name = (t as? ClickTarget.Control)?.name.orEmpty()
        when {
            name.startsWith("opponent:") -> onSelect(name.removePrefix("opponent:").toInt())
            name == "ai-copy" -> onToggleAiCopy()
            name == "watch" -> onToggleWatch()
            name == "format" -> onCycleFormat()
            name == "start" -> if (canStart && !simRunning) onStart()
            name == "simulate" -> if (simRunning) onStopSimulation() else if (canStart) onSimulate()
            name == "sim-games" -> onCycleSimGames()
            name == "back" -> onBack()
        }
    }
    fun move(by: Int) {
        if (opponents.isEmpty()) return
        val at = opponents.indexOfFirst { it.deck.id == selectedId }.coerceAtLeast(0)
        onSelect(opponents[(at + by).coerceIn(0, opponents.lastIndex)].deck.id)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.background).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> move(-1)
                Key.DirectionDown -> move(+1)
                Key.A -> onToggleAiCopy()
                Key.W -> onToggleWatch()
                Key.B -> onCycleFormat()
                Key.N -> onCycleSimGames()
                Key.S -> if (simRunning) onStopSimulation() else if (canStart) onSimulate()
                Key.Enter -> if (canStart && !simRunning) onStart()
                Key.Escape -> onBack()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        Column(Modifier.fillMaxSize()) {
            BoxPane("new game", Modifier.weight(1f).fillMaxWidth(), right = "you play ${me.name}") {
                // Every line takes the pane's measured width: facts are cut where the pane ends, instructions wrap.
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    FitText("You: ${me.name}  (${me.format ?: "no format"}, ${me.cardCount} cards)", bold = true)
                    GridText("")
                    FitText("Opponent (the AI):", color = Palette.dim)
                    if (opponents.isEmpty()) FitText("  no deck of the same game type", color = Palette.accent)
                    opponents.forEach { o ->
                        val selected = o.deck.id == selectedId
                        val copy = if (o.substitutions > 0) "  [AI copy: ${o.substitutions} substitutions]" else ""
                        val record = o.record?.let { "  $it" }.orEmpty()
                        FitText(
                            "  ${o.deck.name}  (${o.deck.format ?: "-"}, ${o.deck.cardCount})$copy$record",
                            Modifier.clickTarget(ClickTarget.Control("opponent:${o.deck.id}"), onClick),
                            color = if (selected) Palette.background else Palette.foreground,
                            background = if (selected) Palette.foreground else Color.Unspecified,
                        )
                    }
                    GridText("")
                    val subs = opponents.firstOrNull { it.deck.id == selectedId }?.substitutions ?: 0
                    WrapText(
                        if (subs == 0) "  The AI plays the deck as built (it has no substitutions)."
                        else "  [${if (useAiCopy) "x" else " "}] the AI plays its AI copy (A toggles)",
                        if (subs > 0) Modifier.clickTarget(ClickTarget.Control("ai-copy"), onClick) else Modifier,
                        color = if (subs > 0) Palette.accent else Palette.dim, hang = 4,
                    )
                    WrapText(
                        "  [${if (watch) "x" else " "}] watch: an AI plays ${me.name} too, and you watch (W toggles; hands stay hidden unless you press H)",
                        Modifier.clickTarget(ClickTarget.Control("watch"), onClick), color = Palette.accent, hang = 4,
                    )
                    WrapText(
                        "  match: ${if (watch) "best of 1 (watching)" else format}  (B cycles 1 / 3 / 5; sideboarding between games)",
                        Modifier.clickTarget(ClickTarget.Control("format"), onClick), color = Palette.accent, hang = 7,
                    )
                    WrapText(
                        "  simulate: $simGames games, AI vs AI with no board, both AIs on their AI copies when that is on" +
                            " (N cycles 1 / 5 / 10 / 20 / 50; S starts and stops). Each game is recorded as it ends; one past 150 s is a draw.",
                        Modifier.clickTarget(ClickTarget.Control("sim-games"), onClick), color = Palette.accent, hang = 4,
                    )
                    simulation?.let { WrapText("  ${it.line}", color = if (it.running) Palette.foreground else Palette.dim, hang = 4) }
                    GridText("")
                    notes.forEach { WrapText(it, color = Palette.accent) }
                    GridText("")
                    Row {
                        val startable = canStart && !simRunning
                        GridText("[ Start ]", Modifier.clickTarget(ClickTarget.Control("start"), onClick),
                            color = if (startable) Palette.background else Palette.dim, background = if (startable) Palette.foreground else Color.Unspecified)
                        GridText("  ")
                        val simulable = canStart || simRunning
                        GridText(if (simRunning) "[ Stop simulation ]" else "[ Simulate $simGames ]", Modifier.clickTarget(ClickTarget.Control("simulate"), onClick),
                            color = if (simulable) Palette.background else Palette.dim, background = if (simulable) Palette.foreground else Color.Unspecified)
                        GridText("  ")
                        GridText("[ Back ]", Modifier.clickTarget(ClickTarget.Control("back"), onClick), color = Palette.background, background = Palette.foreground)
                        GridText(if (forgeReady) "" else "   Forge is loading…", color = Palette.dim)
                    }
                }
            }
            StatusLine(listOf("↑↓" to "opponent", "A" to "AI copy", "W" to "watch", "B" to "best of", "Enter" to "start",
                "S" to if (simRunning) "stop sim" else "simulate", "N" to "games", "Esc" to "back"), null, cols)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
