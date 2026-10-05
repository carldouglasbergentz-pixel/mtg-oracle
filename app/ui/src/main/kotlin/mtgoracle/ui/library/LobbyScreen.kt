package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import mtgoracle.ui.kit.BigButton
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** A simulation as the lobby shows it: its status line, and whether it is still going. */
data class SimLine(val line: String, val running: Boolean)

/** An opponent the lobby offers, with what its AI copy changes. */
data class OpponentChoice(
    val deck: DeckSummary,
    val substitutions: Int,
    /** Your deck's record against this one: `you 3–2 · AI 12–7`, or null before a game. */
    val record: String? = null,
)

/**
 * The lobby: your deck and the AI's in one pane, and how the match goes in
 * the other.
 * Your deck is chosen here, not by what the library has selected; the
 * opponents are the decks of its game type. The AI plays its AI copy (the
 * deck's `forge_substitutions` applied) when there is one, unless switched
 * off. [notes] are the deck checks: cards Forge lacks, cards its AI won't play.
 */
@Composable
fun LobbyScreen(
    decks: List<DeckSummary>,
    meId: Int?,
    opponents: List<OpponentChoice>,
    selectedId: Int?,
    useAiCopy: Boolean,
    watch: Boolean,
    notes: List<String>,
    forgeReady: Boolean,
    canStart: Boolean,
    onSelectMe: (Int) -> Unit,
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
    // Which list ↑↓ moves in: yours (0) or the opponent's (1).
    var list by remember { mutableStateOf(1) }
    val me = decks.firstOrNull { it.id == meId }
    val onClick: (ClickTarget) -> Unit = { t ->
        val name = (t as? ClickTarget.Control)?.name.orEmpty()
        when {
            name.startsWith("me:") -> { list = 0; onSelectMe(name.removePrefix("me:").toInt()) }
            name.startsWith("opponent:") -> { list = 1; onSelect(name.removePrefix("opponent:").toInt()) }
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
        if (list == 0) {
            if (decks.isEmpty()) return
            val at = decks.indexOfFirst { it.id == meId }.coerceAtLeast(0)
            onSelectMe(decks[(at + by).coerceIn(0, decks.lastIndex)].id)
        } else {
            if (opponents.isEmpty()) return
            val at = opponents.indexOfFirst { it.deck.id == selectedId }.coerceAtLeast(0)
            onSelect(opponents[(at + by).coerceIn(0, opponents.lastIndex)].deck.id)
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.surface).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> move(-1)
                Key.DirectionDown -> move(+1)
                Key.Tab -> list = 1 - list
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
        val opponent = opponents.firstOrNull { it.deck.id == selectedId }
        Column(Modifier.fillMaxSize()) {
            // The decks on the left, the match on the right: the right pane is where options are added.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxPane("decks", Modifier.weight(1f).fillMaxHeight(), right = "Tab switches the list") {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val inner = LocalCells.current.cols(constraints.maxWidth.toFloat())
                        Column(Modifier.fillMaxSize()) {
                            DeckList(inner, "your deck", list == 0, Modifier.weight(1f).region("lobby-me")) {
                                if (decks.isEmpty()) FitText("  no decks yet: make or import one in the library", color = Palette.dim)
                                decks.groupBy { it.folderName ?: "(no folder)" }.forEach { (folder, inFolder) ->
                                    FitText("  $folder", color = Palette.dim)
                                    inFolder.forEach { d -> DeckLine("me:${d.id}", "    ${d.name}  (${d.format ?: "-"}, ${d.cardCount})", d.id == meId, onClick) }
                                }
                            }
                            DeckList(inner, "opponent (the AI)", list == 1, Modifier.weight(1f).region("lobby-opponent")) {
                                if (me == null) FitText("  choose your deck first", color = Palette.dim)
                                else if (opponents.isEmpty()) FitText("  no deck of the same game type as ${me.name}", color = Palette.accent)
                                opponents.forEach { o ->
                                    val copy = if (o.substitutions > 0) "  [AI copy: ${o.substitutions}]" else ""
                                    val record = o.record?.let { "  $it" }.orEmpty()
                                    DeckLine("opponent:${o.deck.id}", "    ${o.deck.name}  (${o.deck.format ?: "-"}, ${o.deck.cardCount})$copy$record", o.deck.id == selectedId, onClick)
                                }
                            }
                        }
                    }
                }
                BoxPane("the match", Modifier.weight(1f).fillMaxHeight()) {
                    // Every line takes the pane's measured width: facts are cut where the pane ends, instructions wrap.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FitText("  ${me?.name ?: "your deck"}  vs  ${opponent?.deck?.name ?: "-"}", bold = true)
                        GridText("")
                        val subs = opponent?.substitutions ?: 0
                        if (subs == 0) Option(null, "[-] AI copy", "the AI plays the deck as built: it has no substitutions", onClick)
                        else Option("ai-copy", "[${if (useAiCopy) "x" else " "}] AI copy  (A)",
                            "the AI plays its copy of the deck, with $subs substitution${if (subs == 1) "" else "s"} for cards it can't play", onClick)
                        Option("watch", "[${if (watch) "x" else " "}] watch  (W)",
                            "an AI plays ${me?.name ?: "your deck"} too, and you watch; hands stay hidden unless you press H", onClick)
                        Option("format", "match: ${if (watch) "best of 1 (watching)" else format}  (B)",
                            "best of 1 / 3 / 5, sideboarding between games", onClick)
                        Option("sim-games", "simulate: $simGames games  (N, S)",
                            "AI vs AI with no board, both AIs on their AI copies when that is on. N cycles 1 / 5 / 10 / 20 / 50, " +
                                "S starts and stops. Each game is recorded as it ends; one past 150 s is a draw.", onClick)
                        simulation?.let { WrapText("  ${it.line}", color = if (it.running) Palette.foreground else Palette.dim, hang = 4) }
                        notes.forEach { WrapText("  $it", color = Palette.accent, hang = 2) }
                        GridText("")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val startable = canStart && !simRunning
                            GridText(" ")
                            BigButton("Start", ClickTarget.Control("start"), startable, onClick)
                            GridText(" ")
                            BigButton(if (simRunning) "Stop simulation" else "Simulate $simGames", ClickTarget.Control("simulate"), canStart || simRunning, onClick)
                            GridText(" ")
                            BigButton("Back", ClickTarget.Control("back"), true, onClick)
                        }
                        if (!forgeReady) FitText("  Forge is loading…", color = Palette.dim)
                    }
                }
            }
            StatusLine(listOf("Tab" to "your deck / opponent", "↑↓" to "choose", "A" to "AI copy", "W" to "watch", "B" to "best of", "Enter" to "start",
                "S" to if (simRunning) "stop sim" else "simulate", "N" to "games", "Esc" to "back"), null, cols)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** One of the lobby's two lists, under a rule that is bright while ↑↓ moves in it; it scrolls by itself. */
@Composable
private fun DeckList(cols: Int, title: String, active: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        RuleLine(cols, label = title, heavy = active, color = if (active) Palette.accent else Palette.dim, bold = active)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
    }
}

/** A match option: its state and key on one line (clickable when [target] is set), what it means wrapped under it. */
@Composable
private fun Option(target: String?, line: String, help: String, onClick: (ClickTarget) -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (target != null) Modifier.clickTarget(ClickTarget.Control(target), onClick) else Modifier)) {
        FitText("  $line", color = if (target != null) Palette.accent else Palette.dim)
        WrapText("      $help", color = Palette.dim)
        GridText("")
    }
}

/** One deck in a lobby list, inverted when chosen. */
@Composable
private fun DeckLine(target: String, text: String, chosen: Boolean, onClick: (ClickTarget) -> Unit) {
    FitText(
        text,
        Modifier.clickTarget(ClickTarget.Control(target), onClick),
        color = if (chosen) Palette.background else Palette.foreground,
        background = if (chosen) Palette.foreground else Color.Unspecified,
    )
}
