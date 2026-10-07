package mtgoracle.ui.library

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** Where network play stands, as the lobby shows it. */
sealed interface NetState {
    data object Idle : NetState
    /** Asking the router to open a port. */
    data object Opening : NetState
    /** The room is open: its invite, and how many strangers were turned away while it waits. */
    data class Hosting(val invite: String, val strangers: Int, val lastRefused: String? = null) : NetState
    /** Knocking on a friend's room. */
    data object Joining : NetState
    /** Why the last try didn't work, said as it is. */
    data class Failed(val reason: String) : NetState
}

/** Network play in the lobby: your name at a table, your playmat choices, and where it stands. */
data class LobbyNetwork(val name: String, val shareMat: Boolean, val showTheirMat: Boolean, val state: NetState)

/** What the network controls ask. */
sealed interface NetAction {
    data object Rename : NetAction
    data object Host : NetAction
    data object CloseRoom : NetAction
    data object CopyInvite : NetAction
    /** Join the room whose invite is on the clipboard. */
    data object Join : NetAction
    data object ToggleShareMat : NetAction
    data object ToggleShowTheirMat : NetAction
}

/**
 * Network play in the lobby's match pane: host a room with your deck (the
 * invite to send a friend), or join one with the invite you were sent, and
 * the name and playmat choices that go with you.
 */
@Composable
internal fun NetworkSection(net: LobbyNetwork, canPlay: Boolean, onNet: (NetAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().region("lobby-network")) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        Column {
            RuleLine(cols, label = "network play")
            GridText("")
            Row {
                GridText("  your name  ")
                GridText(net.name, color = Palette.accent)
                GridText("  ")
                LinkButton("[ change ]", ClickTarget.Control("net:rename")) { onNet(NetAction.Rename) }
            }
            Row {
                GridText("  ")
                LinkButton("[${if (net.shareMat) "x" else " "}] send my playmat", ClickTarget.Control("net:share-mat")) { onNet(NetAction.ToggleShareMat) }
                GridText("   ")
                LinkButton("[${if (net.showTheirMat) "x" else " "}] show the other's playmat", ClickTarget.Control("net:show-mat")) { onNet(NetAction.ToggleShowTheirMat) }
            }
            WrapText("      A playmat crosses as pixels, never as a file. The other's stays hidden until you show it.", color = Palette.dim, hang = 6)
            GridText("")
            when (val state = net.state) {
                NetState.Idle, is NetState.Failed -> {
                    Row {
                        GridText("  ")
                        if (canPlay) {
                            LinkButton("[ Host a room ]", ClickTarget.Control("net:host")) { onNet(NetAction.Host) }
                            GridText("   ")
                            LinkButton("[ Join from the clipboard ]", ClickTarget.Control("net:join")) { onNet(NetAction.Join) }
                        } else GridText("choose your deck first", color = Palette.dim)
                    }
                    WrapText("      Host: your router opens a port (UPnP) and you get an invite to send. Join: copy the invite you were sent, then Join. " +
                        "You play the deck chosen above; the host's match format counts.", color = Palette.dim, hang = 6)
                    if (state is NetState.Failed) WrapText("  ${state.reason}", color = Palette.accent, hang = 2)
                }
                NetState.Opening -> FitText("  asking your router to open a port…", color = Palette.dim)
                NetState.Joining -> FitText("  knocking on your friend's room…", color = Palette.dim)
                is NetState.Hosting -> {
                    FitText("  invite: ${state.invite}", color = Palette.accent, bold = true)
                    Row {
                        GridText("  ")
                        LinkButton("[ Copy the invite ]", ClickTarget.Control("net:copy")) { onNet(NetAction.CopyInvite) }
                        GridText("   ")
                        LinkButton("[ Close the room ]", ClickTarget.Control("net:close")) { onNet(NetAction.CloseRoom) }
                    }
                    WrapText("      Send it to your friend, who copies it and joins. Waiting for them" +
                        (if (state.strangers > 0) "; ${state.strangers} connection${if (state.strangers == 1) "" else "s"} without the invite turned away" else "") + ". " +
                        "Windows may ask whether the app may accept connections: allow it, or your friend can't reach you.", color = Palette.dim, hang = 6)
                    state.lastRefused?.let { WrapText("  your friend was turned away: $it", color = Palette.accent, hang = 2) }
                }
            }
        }
    }
}
