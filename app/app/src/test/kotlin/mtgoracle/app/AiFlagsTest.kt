package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.lookup.Ask
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A card Forge's AI won't play is flagged red in the deck, with a [!] that
 * asks for its AI substitute at once; substituted, the flag says what the AI
 * plays and the button turns [→]. In the workspace and in the library alike.
 */
class AiFlagsTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    @Test
    fun `a card the AI won't play is flagged, and its button asks for a substitute`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        // Gitaxian Probe: a card Forge marks RemoveDeck (the lobby warned of it in the user's Blue Moon).
        val deckId = DriverManager.getConnection("jdbc:sqlite:${File(data, "mtg.db").path}").use { c ->
            val id = c.createStatement().use { it.executeQuery("SELECT d.id FROM decks d WHERE d.format IN ('canlander', 'canadianhighlander') ORDER BY d.id LIMIT 1").use { rs -> rs.next(); rs.getInt(1) } }
            c.createStatement().use { it.executeUpdate("DELETE FROM forge_substitutions WHERE deck_id = $id AND card_name = 'Gitaxian Probe'") }
            c.createStatement().use { it.executeUpdate("DELETE FROM deck_cards WHERE deck_id = $id AND card_name = 'Gitaxian Probe'") }
            c.createStatement().use { it.executeUpdate("INSERT INTO deck_cards (deck_id, card_name, quantity, is_commander, is_sideboard, added_at) VALUES ($id, 'Gitaxian Probe', 1, 0, 0, '2026-10-05T00:00:00Z')") }
            id
        }
        Scenario.startForge()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
        val deadline = System.currentTimeMillis() + 30_000
        while (!app.forgeReady && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("AI won't play it", app.aiFlags(app.deckById(deckId)!!)["Gitaxian Probe"]?.label)

        app.select(deckId)
        app.edit()
        OffscreenDriver(1800, 1100) { AppContent(app) {} }.use { d ->
            d.settle(8)
            // The workspace's deck pane is narrow beside the search: the [!] and the red, the words where they fit.
            assertTrue("[!]" in d.text.all(), "the [!] beside the card in the workspace")
            assertTrue(d.text.all().lines().none { it.trimEnd().endsWith("AI …") }, "no half a label")
            assertTrue(d.click(ClickTarget.Control("ai-flag:Gitaxian Probe")), "its [!]")
            d.settle(3)
            val ask = assertNotNull(app.lookupUi!!.ask as? Ask.Text, "the AI substitute is asked for at once")
            assertTrue("instead of Gitaxian Probe" in ask.title, ask.title)
            app.lookupUi!!.ask = null
            // One the deck doesn't hold: a singleton deck refuses a second copy, substitute or not.
            val held = app.deckById(deckId)!!.cards.map { it.name }.toSet()
            val sub = listOf("Serum Visions", "Sleight of Hand", "Preordain", "Brainstorm", "Opt", "Consider", "Impulse", "Thought Scour", "Lightning Bolt", "Shock", "Island")
                .first { it !in held && mtgoracle.forge.ForgeCards.support(it) == mtgoracle.forge.ForgeSupport.PLAYABLE }
            ask.onOk(sub)
            d.settle(8)
            assertTrue("[→]" in d.text.all(), "substituted: the button turns [→]. ${app.notice}")
            d.savePng(File(Scenario.pngDir, "ai-flags-workspace.png"))

            app.leaveEdit()
            d.settle(8)
            d.savePng(File(Scenario.pngDir, "ai-flags-library.png"))
            assertTrue("AI plays $sub" in d.text.all() || app.mode == mtgoracle.ui.kit.CardMode.ART, "and in the library's view of the deck, its words with it: " + d.text.all().lines().filter { "Gitaxian" in it })
            assertTrue(d.registry[ClickTarget.Control("ai-flag:Gitaxian Probe")] != null, "with its button there too")
        }
    }
}
