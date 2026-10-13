package mtgoracle.ui.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Step
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.Palette

/** The phase bar's rows: the turn across the table, then yours. */
const val PHASE_BAR_ROWS = 2

/** The columns each row's name takes: `their turn`, `your turn`. */
private const val LEAD = 12

/** Short step names, so a row of all thirteen fits the table's width. */
private val SHORT = mapOf(
    Step.UNTAP to "untap", Step.UPKEEP to "upkeep", Step.DRAW to "draw", Step.MAIN1 to "main 1",
    Step.COMBAT_BEGIN to "combat", Step.COMBAT_DECLARE_ATTACKERS to "attackers", Step.COMBAT_DECLARE_BLOCKERS to "blockers",
    Step.COMBAT_FIRST_STRIKE_DAMAGE to "1st strike", Step.COMBAT_DAMAGE to "damage", Step.COMBAT_END to "end combat",
    Step.MAIN2 to "main 2", Step.END_OF_TURN to "end", Step.CLEANUP to "cleanup",
)

/**
 * The turn, step by step, on a bar between the table and your hand, as
 * MTGO draws it: a row for the turn across the table, a row for the near
 * player's (yours). Each step is a stop to toggle
 * (`•` and the accent on, `·` off), and the step being played is lit on the
 * active player's row.
 * Watching (no [stops]) it only follows the turn. Narrow, a step's name is
 * cut to its cell: the bar never wraps, so nothing below it moves.
 */
@Composable
fun PhaseBar(stops: PhaseStops?, board: BoardState, nearId: Int, cols: Int, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    val cell = maxOf(4, (cols - LEAD) / Step.entries.size)
    Column(modifier.cells(cols, PHASE_BAR_ROWS).region("phase-bar")) {
        for (seatsTurn in listOf(false, true)) {
            val activeHere = (board.activePlayerId == nearId) == seatsTurn
            Row {
                GridText(fit(if (seatsTurn) " your turn" else " their turn", LEAD), color = if (activeHere) Palette.foreground else Palette.dim, bold = activeHere)
                for (step in Step.entries) {
                    val on = stops?.stopsAt(seatsTurn, step) == true
                    val current = activeHere && board.step == step
                    val text = if (stops == null) SHORT.getValue(step) else "${if (on) "•" else "·"}${SHORT.getValue(step)}"
                    // A cell apart from the next step, whatever the width.
                    GridText(
                        fit(text, cell - 1) + " ",
                        if (stops == null) Modifier else Modifier.clickTarget(ClickTarget.Stop(seatsTurn, step), onClick),
                        color = when { current -> Palette.background; on -> Palette.accent; else -> Palette.dim },
                        background = if (current) Palette.foreground else Color.Unspecified,
                    )
                }
            }
        }
    }
}
