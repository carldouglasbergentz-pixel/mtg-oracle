package mtgoracle.ui.library

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import mtgoracle.ui.kit.BigButton
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.clickTarget
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
    /**
     * At a sealed table of [set] with [friend], before the match: the [deck] this side builds (once its pool is
     * opened), whether this side is [ready], and where it stands.
     */
    data class Building(val set: String, val friend: String, val deck: String?, val ready: Boolean, val note: String?) : NetState
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
    data object CancelJoin : NetAction
    /** A sealed table: this side's deck is built (the host holds to it, the guest sends it). */
    data object Ready : NetAction
    /** A sealed table: leave it before the match; the pool stays. */
    data object LeaveTable : NetAction
    data object ToggleShareMat : NetAction
    data object ToggleShowTheirMat : NetAction
}

/**
 * Network play in the lobby's match pane: host a room with your deck (the
 * invite to send a friend), or join one with the invite you were sent, and
 * the name and playmat choices that go with you.
 */
@Composable
internal fun NetworkSection(net: LobbyNetwork, canPlay: Boolean, onNet: (NetAction) -> Unit, sealedSet: String? = null) {
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
            GridText("")
            Row {
                GridText("  ")
                Toggle(net.shareMat, "send my playmat", "net:share-mat") { onNet(NetAction.ToggleShareMat) }
                GridText("   ")
                Toggle(net.showTheirMat, "show the other's playmat", "net:show-mat") { onNet(NetAction.ToggleShowTheirMat) }
            }
            WrapText("      A playmat crosses as pixels, never as a file. The other's stays hidden until you show it.", color = Palette.dim)
            GridText("")
            when (val state = net.state) {
                NetState.Idle, is NetState.Failed -> {
                    // The section's two choices, as big as the match's Start and Simulate above them.
                    if (canPlay) Row(verticalAlignment = Alignment.CenterVertically) {
                        GridText(" ")
                        BigButton("Host a room", ClickTarget.Control("net:host"), true) { onNet(NetAction.Host) }
                        GridText(" ")
                        BigButton("Join from the clipboard", ClickTarget.Control("net:join"), true) { onNet(NetAction.Join) }
                    } else FitText(if (sealedSet != null) "  choose a set first" else "  choose your deck first", color = Palette.dim)
                    WrapText("      Host: your router opens a port (UPnP) and you get an invite to send. Join: copy the invite you were sent, then Join. " +
                        (if (sealedSet != null) "A sealed table of $sealedSet when you host it: each of you opens six packs in your own app, builds, then Ready; " +
                            "neither sees the other's pool, and each checks the other's deck against it."
                        else "You play the deck chosen above; the host's match format counts."), color = Palette.dim)
                    if (state is NetState.Failed) WrapText("  ${state.reason}", color = Palette.accent)
                }
                NetState.Opening -> FitText("  asking your router to open a port…", color = Palette.dim)
                is NetState.Building -> {
                    FitText("  a sealed table of ${state.set} with ${state.friend}", color = Palette.accent, bold = true)
                    FitText("  your deck: ${state.deck ?: "(your packs are being opened)"}", color = if (state.deck != null) Palette.foreground else Palette.dim)
                    state.note?.let { WrapText("  $it", color = Palette.accent, hang = 2) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GridText(" ")
                        BigButton(if (state.ready) "Ready ✓" else "Ready", ClickTarget.Control("net:ready"), state.deck != null && !state.ready) { onNet(NetAction.Ready) }
                        GridText(" ")
                        BigButton("Leave the table", ClickTarget.Control("net:leave"), true) { onNet(NetAction.LeaveTable) }
                    }
                    WrapText("      Build in the library (your deck is in the Limited folder): the pool is in the middle, + takes a card. " +
                        "Ready checks the deck against your pool (forty or more) and holds you to it; the match begins when both are ready.", color = Palette.dim)
                }
                NetState.Joining -> Row {
                    GridText("  knocking on your friend's room…  ", color = Palette.dim)
                    LinkButton("[ Cancel ]", ClickTarget.Control("net:cancel-join")) { onNet(NetAction.CancelJoin) }
                }
                is NetState.Hosting -> {
                    FitText("  invite: ${state.invite}", color = Palette.accent, bold = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GridText(" ")
                        BigButton("Copy the invite", ClickTarget.Control("net:copy"), true) { onNet(NetAction.CopyInvite) }
                        GridText(" ")
                        BigButton("Close the room", ClickTarget.Control("net:close"), true) { onNet(NetAction.CloseRoom) }
                    }
                    WrapText("      Send it to your friend, who copies it and joins. Waiting for them" +
                        (if (state.strangers > 0) "; ${state.strangers} connection${if (state.strangers == 1) "" else "s"} without the invite turned away" else "") + ". " +
                        "Windows may ask whether the app may accept connections: allow it, or your friend can't reach you.", color = Palette.dim)
                    state.lastRefused?.let { WrapText("  your friend was turned away: $it", color = Palette.accent) }
                }
            }
        }
    }
}

/**
 * A setting that is on or off, `[x] name`, clicked as text as the match's
 * options above are: a drawn chrome's button takes its label without the
 * brackets, and made `[x] send` read `x] send`.
 */
@Composable
private fun Toggle(on: Boolean, label: String, target: String, onClick: () -> Unit) =
    GridText("[${if (on) "x" else " "}] $label", Modifier.clickTarget(ClickTarget.Control(target), onClick = { onClick() }).pointerHoverIcon(PointerIcon.Hand), color = Palette.accent)
