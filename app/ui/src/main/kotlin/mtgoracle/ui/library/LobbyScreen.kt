package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.ScrollState
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
import kotlinx.coroutines.delay
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.limited.LimitedSet
import mtgoracle.ui.kit.BigButton
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.Toolbar
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/**
 * The lobby's limited tab: the sets Forge opens packs of, the one chosen,
 * the AI's side of the match (its deck, built from its own pool), and whether
 * packs are being opened.
 */
data class LobbyLimited(val sets: List<LimitedSet>, val chosenSet: String?, val opponent: String?, val opening: Boolean = false)

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
    onLibrary: () -> Unit,
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
    /** While Forge starts: when it began (System.nanoTime), and how long it took last time. */
    forgeStartedAt: Long? = null,
    forgeExpectedMillis: Long? = null,
    /** The playmats, yours and the AI's; null leaves the section out. */
    mats: LobbyMats? = null,
    onMat: (MatAction) -> Unit = {},
    /** Network play: host or join a friend's table; null leaves the section out. */
    network: LobbyNetwork? = null,
    onNet: (NetAction) -> Unit = {},
    /** A question open over the lobby (your name at a network table). */
    ask: mtgoracle.ui.lookup.Ask? = null,
    onAskClosed: () -> Unit = {},
    /** The limited tab when it is the one shown; null is the constructed tab. [decks] are the tab's own. */
    limited: LobbyLimited? = null,
    onToggleTab: () -> Unit = {},
    onChooseSet: (String) -> Unit = {},
    onOpenSealed: () -> Unit = {},
    /** The limited tab: the chosen deck opened in the workspace to build, or deleted (after a question). */
    onBuild: (Int) -> Unit = {},
    onDelete: (Int) -> Unit = {},
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
            name == "library" -> onLibrary()
            name == "tab" -> onToggleTab()
            name.startsWith("set:") -> { list = 1; onChooseSet(name.removePrefix("set:")) }
            name == "open-sealed" -> if (limited?.opening == false && forgeReady) onOpenSealed()
            name == "limited-build" -> meId?.let(onBuild)
            name == "limited-delete" -> meId?.let(onDelete)
        }
    }
    fun move(by: Int) {
        if (list == 0) {
            if (decks.isEmpty()) return
            val at = decks.indexOfFirst { it.id == meId }.coerceAtLeast(0)
            onSelectMe(decks[(at + by).coerceIn(0, decks.lastIndex)].id)
        } else if (limited != null) {
            if (limited.sets.isEmpty()) return
            val at = limited.sets.indexOfFirst { it.code == limited.chosenSet }.coerceAtLeast(0)
            onChooseSet(limited.sets[(at + by).coerceIn(0, limited.sets.lastIndex)].code)
        } else {
            if (opponents.isEmpty()) return
            val at = opponents.indexOfFirst { it.deck.id == selectedId }.coerceAtLeast(0)
            onSelect(opponents[(at + by).coerceIn(0, opponents.lastIndex)].deck.id)
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.surface).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || ask != null) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> move(-1)
                Key.DirectionDown -> move(+1)
                Key.Tab -> list = 1 - list
                Key.L -> onToggleTab()
                Key.O -> if (limited?.opening == false && forgeReady) onOpenSealed() else return@onPreviewKeyEvent false
                Key.E -> if (limited != null && meId != null) onBuild(meId) else return@onPreviewKeyEvent false
                Key.Delete -> if (limited != null && meId != null) onDelete(meId) else return@onPreviewKeyEvent false
                Key.A -> if (limited == null) onToggleAiCopy() else return@onPreviewKeyEvent false
                Key.W -> onToggleWatch()
                Key.B -> onCycleFormat()
                Key.N -> if (limited == null) onCycleSimGames() else return@onPreviewKeyEvent false
                Key.S -> if (limited != null) return@onPreviewKeyEvent false else if (simRunning) onStopSimulation() else if (canStart) onSimulate()
                Key.Enter -> if (canStart && !simRunning) onStart()
                Key.Escape -> onLibrary()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        val opponent = opponents.firstOrNull { it.deck.id == selectedId }
        Column(Modifier.fillMaxSize()) {
            // The library's toolbar, as on every screen: Library is where Esc goes, and Theme comes with it.
            Toolbar(listOf("library" to "Library"), onClick)
            Tabs(limited != null, onClick)
            // The decks on the left, the match on the right: the right pane is where options are added.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxPane("decks", Modifier.weight(1f).fillMaxHeight(), right = "Tab switches the list") {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val inner = LocalCells.current.cols(constraints.maxWidth.toFloat())
                        Column(Modifier.fillMaxSize()) {
                            DeckList(inner, "your deck", list == 0, Modifier.weight(1f).region("lobby-me")) {
                                if (decks.isEmpty()) FitText(
                                    if (limited != null) "  no limited deck yet: choose a set below and open a sealed pool" else "  no decks yet: make or import one in the library",
                                    color = Palette.dim,
                                )
                                decks.groupBy { it.folderName ?: "(no folder)" }.forEach { (folder, inFolder) ->
                                    FitText("  $folder", color = Palette.dim)
                                    inFolder.forEach { d -> DeckLine("me:${d.id}", "    ${d.name}  (${d.format ?: "-"}, ${d.cardCount})", d.id == meId, onClick) }
                                }
                            }
                            if (limited != null) SetList(inner, limited, list == 1, Modifier.weight(1f).region("lobby-sets"), onClick)
                            else DeckList(inner, "opponent (the AI)", list == 1, Modifier.weight(1f).region("lobby-opponent")) {
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
                    if (limited != null) LimitedMatch(me, limited, watch, format, notes, canStart, forgeReady, forgeStartedAt, forgeExpectedMillis, onClick) {
                        network?.let { GridText(""); NetworkSection(it, canPlay = forgeReady && !simRunning && limited.chosenSet != null, onNet,
                            sealedSet = limited.sets.firstOrNull { s -> s.code == limited.chosenSet }?.name) }
                    }
                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                        }
                        if (!forgeReady) ForgeStarting(forgeStartedAt, forgeExpectedMillis)
                        network?.let { GridText(""); NetworkSection(it, canPlay = me != null && forgeReady && !simRunning, onNet) }
                        mats?.let { GridText(""); MatsSection(it, onMat) }
                    }
                }
            }
            ask?.let { mtgoracle.ui.lookup.AskBar(it) { onAskClosed(); focus.requestFocus() } }
            StatusLine(
                if (limited != null) listOf("L" to "constructed", "Tab" to "your deck / set", "↑↓" to "choose", "E" to "build", "Del" to "delete", "O" to "open sealed",
                    "W" to "watch", "B" to "best of", "Enter" to "start", "Esc" to "library")
                else listOf("L" to "limited", "Tab" to "your deck / opponent", "↑↓" to "choose", "A" to "AI copy", "W" to "watch", "B" to "best of", "Enter" to "start",
                    "S" to if (simRunning) "stop sim" else "simulate", "N" to "games", "Esc" to "library"),
                null, cols,
            )
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Constructed or limited, the one shown inverted; L switches. A row of its own
 * between the toolbar and the panes, a blank row on each side, so the inverted
 * tab never touches a button; its text starts in the panes' title column.
 */
@Composable
private fun Tabs(limited: Boolean, onClick: (ClickTarget) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        GridText("")
        Row(Modifier.fillMaxWidth().region("lobby-tabs").clickTarget(ClickTarget.Control("tab"), onClick)) {
            GridText("  ")
            Tab("constructed", !limited)
            GridText("  ")
            Tab("limited", limited)
            GridText("   (L switches)", color = Palette.dim)
        }
        GridText("")
    }
}

@Composable
private fun Tab(label: String, chosen: Boolean) =
    GridText(" $label ", color = if (chosen) Palette.background else Palette.dim, background = if (chosen) Palette.foreground else Color.Unspecified, bold = chosen)

/** The sets to open packs of, newest first; the chosen one is kept in sight. */
@Composable
private fun SetList(cols: Int, limited: LobbyLimited, active: Boolean, modifier: Modifier, onClick: (ClickTarget) -> Unit) {
    val scroll = rememberScrollState()
    val line = LocalCells.current.height
    val at = limited.sets.indexOfFirst { it.code == limited.chosenSet }
    LaunchedEffect(at) {
        if (at < 0) return@LaunchedEffect
        val top = (at * line).toInt()
        val bottom = top + line.toInt()
        if (top < scroll.value) scroll.scrollTo(top) else if (bottom > scroll.value + scroll.viewportSize) scroll.scrollTo(bottom - scroll.viewportSize)
    }
    Column(modifier.fillMaxWidth()) {
        RuleLine(cols, label = "open a sealed pool: the set", heavy = active, color = if (active) Palette.accent else Palette.dim, bold = active)
        WholeLines(scroll) {
            if (limited.sets.isEmpty()) FitText("  the sets come once Forge is up", color = Palette.dim)
            limited.sets.forEach { set ->
                DeckLine("set:${set.code}", "    ${set.code.padEnd(5)} ${set.name}  ${set.released.take(4)}", set.code == limited.chosenSet, onClick)
            }
        }
    }
}

/**
 * The limited match: your deck against the deck Forge's AI builds from its
 * own pool of the same set, the match's options, and opening a new pool.
 */
@Composable
private fun LimitedMatch(
    me: DeckSummary?, limited: LobbyLimited, watch: Boolean, format: String, notes: List<String>, canStart: Boolean,
    forgeReady: Boolean, forgeStartedAt: Long?, forgeExpectedMillis: Long?, onClick: (ClickTarget) -> Unit,
    /** Network play, under the rest: a sealed table hosted or joined. */
    network: @Composable () -> Unit = {},
) {
    val set = limited.sets.firstOrNull { it.code == limited.chosenSet }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FitText("  ${me?.name ?: "your limited deck"}  vs  ${limited.opponent ?: "-"}", bold = true)
        if (me != null) Row(verticalAlignment = Alignment.CenterVertically) {
            GridText(" ")
            BigButton("Build  (E)", ClickTarget.Control("limited-build"), true, onClick)
            GridText(" ")
            BigButton("Delete", ClickTarget.Control("limited-delete"), true, onClick)
        }
        WrapText(
            "  The AI opened its own six packs of the same set when you opened yours, and builds its deck from them as the match starts. " +
                "Build (E) opens your deck with its pool in the middle: + takes a card into the deck, forty or more. Basic lands are free.",
            color = Palette.dim, hang = 2,
        )
        GridText("")
        Option("watch", "[${if (watch) "x" else " "}] watch  (W)", "an AI plays ${me?.name ?: "your deck"} too, and you watch", onClick)
        Option("format", "match: ${if (watch) "best of 1 (watching)" else format}  (B)",
            "best of 1 / 3 / 5; between games the rest of your pool is your sideboard, with basic lands to swap in", onClick)
        notes.forEach { WrapText("  $it", color = Palette.accent, hang = 2) }
        GridText("")
        Row(verticalAlignment = Alignment.CenterVertically) {
            GridText(" ")
            BigButton("Start", ClickTarget.Control("start"), canStart, onClick)
        }
        if (!forgeReady) ForgeStarting(forgeStartedAt, forgeExpectedMillis)
        GridText("")
        GridText("")
        FitText("  a new sealed pool", bold = true)
        WrapText("  Six packs of ${set?.name ?: "the set chosen on the left"}, as its own booster holds them, and a new deck in the Limited folder.", color = Palette.dim, hang = 2)
        GridText("")
        Row(verticalAlignment = Alignment.CenterVertically) {
            GridText(" ")
            BigButton(if (limited.opening) "Opening…" else "Open sealed", ClickTarget.Control("open-sealed"), set != null && forgeReady && !limited.opening, onClick)
        }
        network()
    }
}

/** Forge's start, counted while it runs: it takes ten seconds or so, and Start waits on it. */
@Composable
private fun ForgeStarting(startedAt: Long?, expectedMillis: Long?) {
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.nanoTime() } }
    val seconds = startedAt?.let { (now - it) / 1_000_000_000 }
    val of = expectedMillis?.let { " of about ${(it + 999) / 1000} s" }.orEmpty()
    FitText("  Forge is starting" + (seconds?.let { ": $it s$of" } ?: "…") + "; Start waits for it", color = Palette.dim)
}

/** One of the lobby's two lists, under a rule that is bright while ↑↓ moves in it; it scrolls by itself. */
@Composable
private fun DeckList(cols: Int, title: String, active: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        RuleLine(cols, label = title, heavy = active, color = if (active) Palette.accent else Palette.dim, bold = active)
        WholeLines(rememberScrollState()) { content() }
    }
}

/**
 * A list that scrolls under its rule, shown in whole lines: its visible height
 * is a whole number of lines, so every place it scrolls to, its end included,
 * starts on a line and no half line stands under the rule.
 */
@Composable
private fun ColumnScope.WholeLines(scroll: ScrollState, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val line = LocalCells.current.height
        val rows = maxOf(1, (constraints.maxHeight / line).toInt())
        val height = with(LocalDensity.current) { (rows * line).toDp() }
        Column(Modifier.fillMaxWidth().height(height).verticalScroll(scroll)) { content() }
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
