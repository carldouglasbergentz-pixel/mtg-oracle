package mtgoracle.app

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
import mtgoracle.core.art.NoArt
import mtgoracle.forge.ForgeRuntime
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.MatchControls
import mtgoracle.ui.board.MatchStatus
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.kit.WrapText
import androidx.compose.foundation.layout.fillMaxWidth
import mtgoracle.ui.library.LibraryScreen
import mtgoracle.ui.library.SetupScreen
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
    val art = remember(app.forgeReady) { ArtImages(if (app.forgeReady) ForgeRuntime.images else NoArt) }
    HouseTheme {
        CompositionLocalProvider(LocalArt provides art, LocalGlobalHints provides listOf("F8" to "theme: ${Palette.theme.label}")) {
            // F8 cycles the theme on every screen: seen here before any screen's own keys.
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.F8) { app.cycleTheme(); true } else false
            }) { Screens(app, onQuit) }
        }
    }
}

@Composable
private fun Screens(app: AppController, onQuit: () -> Unit) {
    LaunchedEffect(app.quitRequested) { if (app.quitRequested) onQuit() }
    when (val screen = app.screen) {
        Screen.Crashed -> CrashScreen(app)
        Screen.Loading -> Message("MTG Oracle", "Opening the database…")
        is Screen.Blocked -> Message("MTG Oracle can't start", screen.message)
        Screen.Library -> LibraryScreen(
            decks = app.decks, selectedId = app.selectedId, deck = app.deck, keyFor = app::keyFor, mode = app.mode,
            notice = app.notice ?: if (!app.forgeReady) "Forge is loading…" else null,
            onSelect = app::select, onPlay = app::openSetup, onToggleMode = app::toggleMode, onPrefetch = app::prefetch, onQuit = onQuit,
            lookup = app.lookupUi,
        )
        Screen.Setup -> {
            val me = app.decks.first { it.id == app.selectedId }
            val prepared = app.prepared()
            SetupScreen(
                me = me, opponents = app.opponents(), selectedId = app.opponentId, useAiCopy = app.useAiCopy, watch = app.watch,
                notes = prepared?.notes.orEmpty(), forgeReady = app.forgeReady, canStart = prepared != null && !prepared.blocked,
                onSelect = { app.opponentId = it }, onToggleAiCopy = { app.useAiCopy = !app.useAiCopy }, onToggleWatch = { app.watch = !app.watch },
                onStart = app::start, onBack = app::backToLibrary,
                format = app.format.label, onCycleFormat = app::cycleFormat,
            )
        }
        Screen.Playing -> {
            val match = app.match ?: return
            LaunchedEffect(match) { match.seat.stops.collect(app::saveStops) }
            val games by match.games.collectAsState()
            val latest by match.result.collectAsState()
            val status = MatchStatus(match.spec.format.label, match.spec.format.games, games, betweenGames = latest != null, over = games.lastOrNull()?.matchOver == true)
            BoardScreen(
                seat = match.seat,
                title = "${match.spec.seat.name} vs ${match.spec.opponent.name}" + if (match.spec.format.games > 1) " · ${match.spec.format.label}" else "",
                mode = app.mode,
                extraHints = listOf("T" to "text/art"),
                notice = app.notice,
                layout = remember { app.settings.boardLayout },
                onLayoutChange = { app.settings.boardLayout = it },
                onExtraKey = { key -> if (key == Key.T) { app.toggleMode(); true } else false },
                match = status,
                matchControls = MatchControls(
                    onContinue = { match.continueMatch() },
                    onConcedeGame = { match.concede() },
                    onLeaveMatch = app::leaveMatch,
                    onBackToLibrary = app::backToLibrary,
                ),
            )
        }
    }
}

/**
 * The board broke. What happened and where the trace is, and two ways on:
 * back to the library (the game is recorded as unfinished — never as a
 * concession), or the board again, since the game itself is still running.
 */
@Composable
private fun CrashScreen(app: AppController) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Box(Modifier.fillMaxSize().background(Palette.background)
        .focusRequester(focus).focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Enter, Key.NumPadEnter, Key.Escape -> { app.leaveAfterCrash(); true }
                Key.R -> { app.retryBoard(); true }
                else -> false
            }
        }) {
        BoxPane("the board hit an error", Modifier.fillMaxSize(), borderColor = Palette.tapped) {
            Column(Modifier.fillMaxWidth()) {
                WrapText("Something went wrong drawing the game: ${app.crash ?: "?"}", bold = true)
                GridText("")
                WrapText("The full trace is in ${app.appLogPath} and in this game's log.", color = Palette.dim)
                GridText("")
                WrapText("Enter or Esc: back to the library — this game is recorded as unfinished, not as a loss", hang = 2)
                WrapText("R: back to the board — the game is still running", hang = 2)
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
