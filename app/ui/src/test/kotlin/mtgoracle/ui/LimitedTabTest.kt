package mtgoracle.ui

import androidx.compose.ui.input.key.Key
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.limited.LimitedSet
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.library.LobbyLimited
import mtgoracle.ui.library.LobbyScreen
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Theme
import mtgoracle.ui.theme.Themes
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lobby's limited tab, in every look: the tabs, the sets to open packs
 * of (the chosen one kept in sight as ↑↓ goes down a long list), Open sealed,
 * and the match against the AI's pool; L switches the tab.
 */
class LimitedTabTest {
    private val me = DeckSummary(1, "BLB sealed 2026-10-08", "sealed", 7, "Limited", 40)
    private val sets = (1..80).map { LimitedSet("S%02d".format(it), "s%02d".format(it), "Set number $it", "20%02d-01-01".format(99 - it)) } +
        LimitedSet("BLB", "blb", "Bloomburrow", "2024-08-02")

    @AfterTest fun house() { Palette.theme = Themes.HOUSE }

    private class Asked { val sets = mutableListOf<String>(); var opened = 0; var tabs = 0; var started = 0; val built = mutableListOf<Int>(); val deleted = mutableListOf<Int>() }

    private fun render(theme: Theme, chosen: String, asked: Asked = Asked(), check: (OffscreenDriver) -> Unit) {
        Palette.theme = theme
        OffscreenDriver(1100, 900) {
            LobbyScreen(listOf(me), 1, emptyList(), null, useAiCopy = false, watch = false, notes = emptyList(),
                forgeReady = true, canStart = true, onSelectMe = {}, onSelect = {}, onToggleAiCopy = {}, onToggleWatch = {}, onStart = { asked.started++ }, onLibrary = {},
                limited = LobbyLimited(sets, chosen, opponent = "AI (BLB sealed)"),
                onToggleTab = { asked.tabs++ }, onChooseSet = { asked.sets += it }, onOpenSealed = { asked.opened++ },
                onBuild = { asked.built += it }, onDelete = { asked.deleted += it })
        }.use { d ->
            d.settle(5)
            d.savePng(File(pngDir, "lobby-limited-${theme.key}.png"))
            check(d)
        }
    }

    @Test
    fun `in every look the tabs, the sets, the match and Open sealed are there and answer`() {
        for (theme in Themes.ALL) {
            val asked = Asked()
            render(theme, chosen = "S01", asked) { d ->
                val all = d.text.all()
                assertTrue("BLB sealed 2026-10-08  vs  AI (BLB sealed)" in all, "${theme.key}: the match")
                assertTrue("constructed" in all && "limited" in all, "${theme.key}: the tabs")
                assertTrue(d.click(ClickTarget.Control("open-sealed")), "${theme.key}: Open sealed")
                assertTrue(d.click(ClickTarget.Control("set:S02")), "${theme.key}: a set")
                assertTrue(d.click(ClickTarget.Control("tab")), "${theme.key}: the tabs switch")
                assertTrue(d.click(ClickTarget.Control("start")), "${theme.key}: Start")
                d.key(Key.L)
                assertEquals(listOf(1, listOf("S02"), 2, 1), listOf(asked.opened, asked.sets, asked.tabs, asked.started), theme.key)
                assertTrue(d.click(ClickTarget.Control("limited-build")) && d.click(ClickTarget.Control("limited-delete")), "${theme.key}: Build and Delete")
                assertEquals(listOf(1) to listOf(1), asked.built to asked.deleted, "${theme.key}: on the chosen deck")
            }
        }
    }

    @Test
    fun `a sealed table being built shows the deck, where it stands, Ready and Leave, and the playmats, in every look`() {
        for (theme in Themes.ALL) {
            Palette.theme = theme
            val asked = mutableListOf<mtgoracle.ui.library.NetAction>()
            val building = mtgoracle.ui.library.NetState.Building("Bloomburrow", "Bob", "BLB sealed vs Bob 2026-10-08", ready = false, note = "the host is ready: build your deck from the pool, then Ready")
            OffscreenDriver(1100, 1400) {
                LobbyScreen(listOf(me), 1, emptyList(), null, useAiCopy = false, watch = false, notes = emptyList(),
                    forgeReady = true, canStart = false, onSelectMe = {}, onSelect = {}, onToggleAiCopy = {}, onToggleWatch = {}, onStart = {}, onLibrary = {},
                    network = mtgoracle.ui.library.LobbyNetwork("Alice", shareMat = false, showTheirMat = false, state = building), onNet = { asked += it },
                    mats = mtgoracle.ui.library.LobbyMats(emptyList(), mtgoracle.ui.library.MatSide(null, null), mtgoracle.ui.library.MatSide(null, null), "data/playmats"),
                    limited = LobbyLimited(sets, "BLB", opponent = null))
            }.use { d ->
                d.settle(5)
                d.savePng(File(pngDir, "lobby-sealed-table-${theme.key}.png"))
                val all = d.text.all()
                assertTrue("a sealed table of Bloomburrow with Bob" in all && "your deck: BLB sealed vs Bob 2026-10-08" in all, "${theme.key}: the table")
                assertTrue("playmats" in all, "${theme.key}: the playmats, as under the constructed tab")
                assertTrue(d.click(ClickTarget.Control("net:ready")) && d.click(ClickTarget.Control("net:leave")), "${theme.key}: Ready and Leave")
                assertEquals(listOf(mtgoracle.ui.library.NetAction.Ready, mtgoracle.ui.library.NetAction.LeaveTable), asked, theme.key)
            }
        }
    }

    @Test
    fun `the chosen set is kept in sight at the bottom of a long list`() {
        render(Themes.HOUSE, chosen = "BLB") { d ->
            val list = assertNotNull(d.registry[ClickTarget.Control("region:lobby-sets")], "the list's region")
            val line = assertNotNull(d.registry[ClickTarget.Control("set:BLB")], "the chosen set is drawn")
            assertTrue(line.top >= list.top && line.bottom <= list.bottom, "BLB, last of 81, is scrolled into the list: $line in $list")
        }
    }
}
