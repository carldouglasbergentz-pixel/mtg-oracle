package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.Winner
import mtgoracle.ui.kit.BUTTON_ROWS
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** Where a match stands, for the between-games panel and the concede menu. */
data class MatchStatus(
    /** "best of 3". */
    val format: String,
    val gamesInMatch: Int,
    /** Every finished game, in order. */
    val games: List<MatchResult>,
    /** A game has just ended and the next has not begun (or the match is over). */
    val betweenGames: Boolean,
    val over: Boolean,
)

/** What the match panels do. */
class MatchControls(
    val onContinue: () -> Unit,
    val onConcedeGame: () -> Unit,
    val onLeaveMatch: () -> Unit,
    val onBackToLibrary: () -> Unit,
)

/** Named controls of the match panels (the driver clicks them like any other). */
object MatchTargets {
    val CONTINUE = ClickTarget.Control("match:continue")
    val LIBRARY = ClickTarget.Control("match:library")
    val CONCEDE_GAME = ClickTarget.Control("match:concede-game")
    val LEAVE = ClickTarget.Control("match:leave")
    val CANCEL = ClickTarget.Control("match:cancel")
    val OPEN_MENU = ClickTarget.Control("match:menu")
}

private fun outcome(r: MatchResult) = when (r.winner) {
    Winner.ME -> "you won"
    Winner.OPPONENT -> if (r.conceded) "you conceded" else "you lost"
    Winner.DRAW -> "a draw"
}

/**
 * Between games: game N's result, the score, Continue. When the match is
 * over: its result and the way back to the library. A solid panel over the
 * table, like the stack box; the table under it is the last game's.
 */
@Composable
fun ResultPanel(status: MatchStatus, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    val last = status.games.lastOrNull() ?: return
    val cols = 64
    val lines = buildList {
        add("game ${last.gameNo} of ${status.format}: ${outcome(last)}" + (last.turns?.let { " on turn $it" } ?: ""))
        add("match ${last.wins}–${last.losses}")
        if (status.over) add(when {
            last.wins > last.losses -> "you won the match"
            last.losses > last.wins -> "you lost the match"
            else -> "the match is drawn"
        })
    }
    Box(modifier.cells(cols, lines.size + 4 + BUTTON_ROWS).chromeShape().background(Palette.background)
        .boxBorder(if (status.over) "match over" else "game over", border = Border.DOUBLE, color = Palette.accent)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .region("match-result")) {
        Column(Modifier.padding(start = with(LocalDensity.current) { LocalCells.current.width.toDp() * 2 }, top = with(LocalDensity.current) { LocalCells.current.height.toDp() })) {
            lines.forEachIndexed { i, line -> GridText(fit(line, cols - 4), color = if (i == 0) Palette.foreground else Palette.dim, bold = i == 0) }
            GridText("")
            Row {
                if (status.over) GridButton("Back to the library", MatchTargets.LIBRARY, true, onClick)
                else GridButton("Continue to game ${last.gameNo + 1}", MatchTargets.CONTINUE, true, onClick)
            }
            GridText(fit(if (status.over) "Enter or Esc: the library" else "Enter: continue · sideboarding and play/draw come next", cols - 4), color = Palette.dim)
        }
    }
}

/** Ctrl+Q (or Esc with nothing to cancel): leave this game, or the whole match. */
@Composable
fun ConcedeMenu(status: MatchStatus?, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    val cols = 68
    // In a match with games still to come, conceding a game is not leaving.
    val gamesLeft = status != null && status.gamesInMatch > 1
    Box(modifier.cells(cols, (if (gamesLeft) 3 else 2) * (BUTTON_ROWS + 1) + 4).chromeShape().background(Palette.background)
        .boxBorder("leave the game?", border = Border.DOUBLE, color = Palette.accent)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .region("concede-menu")) {
        Column(Modifier.padding(start = with(LocalDensity.current) { LocalCells.current.width.toDp() * 2 }, top = with(LocalDensity.current) { LocalCells.current.height.toDp() })) {
            // A row between the buttons: two of them touching read as one.
            if (gamesLeft) { Row(verticalAlignment = Alignment.CenterVertically) { GridButton("1  concede this game", MatchTargets.CONCEDE_GAME, true, onClick); GridText("then sideboarding, next game", color = Palette.dim) }; GridText("") }
            Row(verticalAlignment = Alignment.CenterVertically) { GridButton("${if (gamesLeft) 2 else 1}  concede the match", MatchTargets.LEAVE, true, onClick); GridText("back to the library", color = Palette.dim) }
            GridText("")
            Row { GridButton("Esc  cancel", MatchTargets.CANCEL, true, onClick) }
            GridText("")
            GridText(fit("a conceded game is recorded as a loss", cols - 4), color = Palette.dim)
        }
    }
}

/**
 * Sideboarding between games, drawn where the table is: the deck and the
 * sideboard side by side, each card with its count; a click moves one copy
 * across. The prompt pane below says whether the deck is legal, and has Done.
 */
@Composable
fun SideboardView(prompt: SideboardPrompt, deck: Map<String, Int>, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    val names = (prompt.main + prompt.side).map { it.name }.distinct().sorted()
    val inMain = names.mapNotNull { n -> (deck[n] ?: 0).takeIf { it > 0 }?.let { n to it } }
    val inSide = names.mapNotNull { n -> (prompt.total(n) - (deck[n] ?: 0)).takeIf { it > 0 }?.let { n to it } }
    Row(modifier.region("sideboard")) {
        SideList("deck (${inMain.sumOf { it.second }}) · click to side out", inMain, true, onClick, Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(with(LocalDensity.current) { LocalCells.current.width.toDp() }))
        SideList("sideboard (${inSide.sumOf { it.second }}) · click to bring in", inSide, false, onClick, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SideList(title: String, rows: List<Pair<String, Int>>, main: Boolean, onClick: (ClickTarget) -> Unit, modifier: Modifier) {
    BoxPane(title, modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            rows.forEach { (name, n) ->
                FitText(" %2d  %s".format(n, name), Modifier.clickTarget(sideboardTarget(main, name), onClick))
            }
            if (rows.isEmpty()) GridText(" (empty)", color = Palette.dim)
        }
    }
}
