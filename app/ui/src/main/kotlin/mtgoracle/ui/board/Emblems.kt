package mtgoracle.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mtgoracle.core.model.PlayerState
import mtgoracle.ui.kit.CardChip
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.cellHeight
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.Palette

/** The rows a half gives its emblems: one, at the midline edge, while it has any. */
fun emblemRows(player: PlayerState): Int = if (player.emblems.isEmpty()) 0 else 1

/** An emblem's name without Forge's `Emblem — ` before it: the row already says what they are. */
fun emblemLabel(name: String): String = name.replace(Regex("^Emblem\\s*[—–-]\\s*"), "")

/**
 * A player's emblems, at the right end of their battlefield's midline edge,
 * always there while they have any: permanent effects nothing interacts with,
 * which read as exiled cards in the exile list. A hover shows the emblem's
 * text in the zoom pane, as any card's.
 */
@Composable
fun EmblemRow(player: PlayerState, looks: Looks, side: String) {
    Row(Modifier.fillMaxWidth().cellHeight(1).region("$side-emblems"), horizontalArrangement = Arrangement.End) {
        GridText("emblem${if (player.emblems.size > 1) "s" else ""} ", color = Palette.dim)
        player.emblems.forEach { e ->
            CardChip(e.face().let { it.copy(name = emblemLabel(it.name)) }, looks.emphasis(e), ClickTarget.Card(e.id), looks.onClick, looks.onHover)
            GridText(" ")
        }
    }
}
