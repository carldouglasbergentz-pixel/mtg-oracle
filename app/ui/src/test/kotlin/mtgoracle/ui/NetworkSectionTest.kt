package mtgoracle.ui

import mtgoracle.core.deck.DeckSummary
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.library.LobbyNetwork
import mtgoracle.ui.library.LobbyScreen
import mtgoracle.ui.library.NetAction
import mtgoracle.ui.library.NetState
import mtgoracle.ui.library.OpponentChoice
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Theme
import mtgoracle.ui.theme.Themes
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lobby's network play, in every look. Its playmat toggles were chrome
 * buttons, which take their label without the brackets, so a drawn look
 * read `x] send my playmat`; and its help lines hung twice as deep as they
 * began, so the second line of "Host: … Join: …" started under nothing.
 */
class NetworkSectionTest {
    private val me = DeckSummary(1, "UW Draw Go - Control", "canadianhighlander", null, null, 100)
    private val hostHelp = "Host: your router opens a port (UPnP) and you get an invite to send. Join: copy the invite you were sent, then Join. " +
        "You play the deck chosen above; the host's match format counts."
    private val opponent = OpponentChoice(DeckSummary(2, "Rakdos Midrange", "canadianhighlander", null, null, 100), substitutions = 0)

    @AfterTest fun house() { Palette.theme = Themes.HOUSE }

    private fun render(theme: Theme, state: NetState, check: (OffscreenDriver) -> Unit) {
        Palette.theme = theme
        val asked = mutableListOf<NetAction>()
        OffscreenDriver(1100, 1300) {
            LobbyScreen(listOf(me, opponent.deck), 1, listOf(opponent), 2, useAiCopy = false, watch = false, notes = emptyList(),
                forgeReady = true, canStart = true, onSelectMe = {}, onSelect = {}, onToggleAiCopy = {}, onToggleWatch = {}, onStart = {}, onLibrary = {},
                network = LobbyNetwork("Player", shareMat = true, showTheirMat = false, state = state), onNet = { asked += it })
        }.use { d ->
            d.settle(5)
            d.savePng(File(pngDir, "lobby-network-${theme.key}-${state::class.simpleName!!.lowercase()}.png"))
            check(d)
            assertTrue(d.click(ClickTarget.Control("net:share-mat")) && d.click(ClickTarget.Control("net:show-mat")), "the toggles are clicked")
            assertEquals(listOf(NetAction.ToggleShareMat, NetAction.ToggleShowTheirMat), asked)
        }
    }

    /** Each text drawn, a wrapped line on its own, trailing padding dropped. */
    private fun lines(d: OffscreenDriver) = d.text.all().lines().map { it.trimEnd() }

    /**
     * How far in each drawn line of [help] starts. The recorder keeps each wrapped line as a
     * text of its own, in no screen order, so a line is known by being a piece of [help].
     */
    private fun indents(d: OffscreenDriver, help: String): List<Int> =
        lines(d).filter { it.isNotBlank() && it.trim().length > 8 && it.trim() in help }.map { line -> line.indexOfFirst { it != ' ' } }

    @Test
    fun `in every look the toggles keep their boxes and the help lines start under each other`() {
        for (theme in Themes.ALL) {
            render(theme, NetState.Idle) { d ->
                val all = lines(d)
                assertTrue("[x] send my playmat" in all && "[ ] show the other's playmat" in all, "${theme.key}: " + all.filter { "playmat" in it })
                val help = indents(d, hostHelp)
                // Wrapped in the house look at this width, so the column check bites; a look with smaller cells may fit it on one line.
                if (theme == Themes.HOUSE) assertTrue(help.size >= 2, "the help wraps at this width: $help")
                assertEquals(setOf(6), help.toSet(), "${theme.key}: every line of the help starts in one column: $help")
            }
        }
    }

    @Test
    fun `an open room's invite and its note in a drawn look`() {
        for (theme in listOf(Themes.HOUSE) + Themes.ALL.filter { it.chrome != null }.take(1)) {
            render(theme, NetState.Hosting("MTG-ABCD-EFGH-IJKL-MNOP", strangers = 2)) { d ->
                val help = indents(d, "Send it to your friend, who copies it and joins. Waiting for them; 2 connections without the invite turned away. " +
                    "Windows may ask whether the app may accept connections: allow it, or your friend can't reach you.")
                assertTrue(help.size >= 2, "${theme.key}: $help")
                assertEquals(setOf(6), help.toSet(), "${theme.key}: $help")
            }
        }
    }
}
