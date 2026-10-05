package mtgoracle.app

import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.INPUT_GUARD_MILLIS
import mtgoracle.ui.board.MatchControls
import mtgoracle.ui.board.MatchStatus
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.kit.LocalThemeMenu
import mtgoracle.ui.kit.ThemePicker
import mtgoracle.ui.kit.WrapText
import androidx.compose.foundation.layout.fillMaxWidth
import mtgoracle.ui.library.DeckWorkspace
import mtgoracle.ui.library.LibraryScreen
import mtgoracle.ui.library.LobbyScreen
import mtgoracle.ui.theme.HouseTheme
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.LocalGlobalHints
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Everything the window shows, by screen. Also what the offscreen driver renders in the app's tests. */
@Composable
fun AppContent(app: AppController, onQuit: () -> Unit) {
    // Anew when Forge comes up: what was asked for before it could be fetched is asked again.
    val art = remember(app.forgeReady) { ArtImages(app.shownArt) }
    // The text size scales the density, so the grid's cell is measured at it and everything sized in cells follows.
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * app.textScale, base.fontScale)) {
        HouseTheme {
            CompositionLocalProvider(
                LocalArt provides art, LocalGlobalHints provides listOf("F7" to "text/art", "F8" to "theme: ${Palette.theme.label}", "Ctrl+=/-" to "size"),
                LocalThemeMenu provides app::openThemePicker,
            ) {
                // F8 opens the theme picker, F7 switches text/art and Ctrl+= / Ctrl+- / Ctrl+0 size the window on every
                // screen, typing or not: seen here before any screen's keys.
                Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.F8 -> { app.openThemePicker(); true }
                        e.key == Key.F7 -> { app.toggleMode(); true }
                        e.isCtrlPressed && e.key in TEXT_UP -> { app.stepTextScale(1); true }
                        e.isCtrlPressed && e.key in TEXT_DOWN -> { app.stepTextScale(-1); true }
                        e.isCtrlPressed && e.key in TEXT_RESET -> { app.stepTextScale(0); true }
                        else -> false
                    }
                }) {
                    Screens(app, onQuit)
                    app.themePickerFrom?.let { from -> ThemePicker(from, app::previewTheme, app::keepTheme, app::cancelThemePicker) }
                }
            }
        }
    }
}

private val TEXT_UP = setOf(Key.Equals, Key.Plus, Key.NumPadAdd)
private val TEXT_DOWN = setOf(Key.Minus, Key.NumPadSubtract)
private val TEXT_RESET = setOf(Key.Zero, Key.NumPad0)

@Composable
private fun Screens(app: AppController, onQuit: () -> Unit) {
    LaunchedEffect(app.quitRequested) { if (app.quitRequested) onQuit() }
    when (val screen = app.screen) {
        Screen.Crashed -> CrashScreen(app)
        Screen.Loading -> Message("MTG Oracle", "Opening the database…")
        is Screen.Blocked -> Message("MTG Oracle can't start", screen.message)
        Screen.Library -> app.editing?.let { open ->
            DeckWorkspace(
                deck = app.deckById(open.deckId), filters = open.filters.map { it.second }, keyFor = app::keyFor,
                deckMode = app.deckPaneMode, lookup = app.lookupUi!!, notice = app.notice ?: app.sync.warning ?: app.updates.notice,
                insight = app.insight?.takeIf { it.deckId == open.deckId },
                onLeave = app::leaveEdit, onToggleResults = app::toggleMode, onToggleDeckMode = app::toggleDeckPaneMode,
                onPlay = { app.openLobby(open.deckId) }, onQuit = onQuit,
                columns = app.settings.workspaceColumns(app.deckPaneMode),
                onColumnsChange = { app.settings.keepWorkspaceColumns(app.deckPaneMode, it) },
            )
        } ?: LibraryScreen(
            decks = app.decks, selectedId = app.selectedId, deck = app.deck, keyFor = app::keyFor, mode = app.mode,
            notice = app.notice ?: if (!app.forgeReady) "Forge is loading…" else app.sync.warning ?: app.updates.notice,
            onSelect = app::select, onPlay = { app.openLobby() }, onToggleMode = app::toggleMode, onPrefetch = app::prefetch, onQuit = onQuit,
            lookup = app.lookupUi,
            onEdit = app::edit,
            folders = app.folders,
            insight = app.insight?.takeIf { it.deckId == app.selectedId },
            pointsOf = { name -> app.deckPoints[name.lowercase()] },
            badges = app.deckBadges,
            columns = remember { app.settings.libraryColumns },
            onColumnsChange = { app.settings.libraryColumns = it },
        )
        Screen.Lobby -> {
            val prepared = app.prepared()
            LobbyScreen(
                decks = app.decks, meId = app.lobbyMeId, opponents = app.opponents(), selectedId = app.opponentId, useAiCopy = app.useAiCopy, watch = app.watch,
                notes = prepared?.notes.orEmpty(), forgeReady = app.forgeReady, canStart = prepared != null && !prepared.blocked,
                onSelectMe = app::chooseMe, onSelect = { app.opponentId = it },
                forgeStartedAt = app.forgeStartedAt, forgeExpectedMillis = app.settings.forgeStartMillis, onToggleAiCopy = { app.useAiCopy = !app.useAiCopy }, onToggleWatch = { app.watch = !app.watch },
                onStart = app::start, onLibrary = app::backToLibrary,
                format = app.format.label, onCycleFormat = app::cycleFormat,
                simGames = app.simGames, onCycleSimGames = app::cycleSimGames, simulation = app.simulation?.let { mtgoracle.ui.library.SimLine(it.line(), it.running) },
                onSimulate = app::simulate, onStopSimulation = app::stopSimulation,
            )
        }
        Screen.Playing -> {
            val match = app.match ?: return
            LaunchedEffect(match) { match.seat.stops.collect(app::saveStops) }
            val games by match.games.collectAsState()
            val latest by match.result.collectAsState()
            val achievements by match.achievements.collectAsState()
            val status = MatchStatus(match.spec.format.label, match.spec.format.games, games, betweenGames = latest != null, over = games.lastOrNull()?.matchOver == true,
                achievements = achievements)
            BoardScreen(
                seat = match.seat,
                inputGuardMillis = System.getProperty("mtgoracle.inputGuardMillis")?.toLongOrNull() ?: INPUT_GUARD_MILLIS,
                title = "${match.spec.seat.name} vs ${match.spec.opponent.name}" + if (match.spec.format.games > 1) " · ${match.spec.format.label}" else "",
                mode = app.boardMode,
                extraHints = listOf("T" to "text/art: ${app.boardMode.name.lowercase()}"),
                notice = app.notice,
                layout = remember { app.settings.boardLayout },
                onLayoutChange = { app.settings.boardLayout = it },
                onExtraKey = { key -> if (key == Key.T) { app.toggleMode(); true } else false },
                match = status,
                matchControls = MatchControls(
                    onContinue = { match.continueMatch() },
                    onConcedeGame = { match.concede() },
                    onLeaveMatch = app::leaveMatch,
                    onBackToLobby = app::backToLobby,
                ),
            )
        }
    }
}

/**
 * Something broke in the window. What happened and where the trace is, and
 * the ways on: back to the library, and during a game (recorded as
 * unfinished — never as a concession) the board again, since the game itself
 * is still running. With no game on, nothing is said about one.
 */
@Composable
private fun CrashScreen(app: AppController) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Box(Modifier.fillMaxSize().background(Palette.surface)
        .focusRequester(focus).focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Enter, Key.NumPadEnter, Key.Escape -> { app.leaveAfterCrash(); true }
                Key.R -> { app.retryBoard(); true }
                else -> false
            }
        }) {
        val inGame = app.match != null
        BoxPane(if (inGame) "the board hit an error" else "the app hit an error", Modifier.fillMaxSize(), borderColor = Palette.tapped) {
            Column(Modifier.fillMaxWidth()) {
                WrapText("Something went wrong ${if (inGame) "drawing the game" else "in the window"}: ${app.crash ?: "?"}", bold = true)
                GridText("")
                WrapText("The full trace is in ${app.appLogPath}" + if (inGame) " and in this game's log." else ".", color = Palette.dim)
                GridText("")
                if (inGame) {
                    WrapText("Enter or Esc: back to the library — this game is recorded as unfinished, not as a loss", hang = 2)
                    WrapText("R: back to the board — the game is still running", hang = 2)
                } else WrapText("Enter or Esc: back to the library", hang = 2)
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Message(title: String, text: String) {
    BoxPane(title, Modifier.fillMaxSize().background(Palette.background)) {
        Column(Modifier.fillMaxWidth()) { WrapText(text) }
    }
}
