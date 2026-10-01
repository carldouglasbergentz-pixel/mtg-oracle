package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.data.Library
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import mtgoracle.ui.lookup.Ask
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `combo add` / `combo remove` and `prune` from the command line, on a copy. */
class MaintenanceCommandsTest {
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    @Test
    fun `a combo of your own is asked for, shown in lookups, and removed after a yes`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val db = MtgDb(copy)
        val lookup = Lookup(db)
        val library = Library(db)
        var changed = 0
        val commands = LookupCommands(lookup, decks = { library.decks() }, faceOf = { null }, onCardsChanged = { changed++ })
        val text = { commands.output.entries.flatMap { it.rendering.lines(120) }.joinToString("\n") { it.text } }

        commands.submit("combo add Sol Ring; not a card")
        assertTrue("card(s) not found: not a card" in text(), text())
        commands.submit("combo add sol ring; Mana Vault")
        assertIs<Ask.Text>(commands.ui.ask).onOk("Tap both for six mana on turn one.")
        assertIs<Ask.Text>(commands.ui.ask).onOk("Fast mana")
        val id = Regex("added your combo (user-\\d{3})").find(text())?.groupValues?.get(1)
        assertTrue(id != null, text())
        commands.submit("combos Sol Ring; Mana Vault")
        assertTrue("Tap both for six mana" in text(), "one match opens it, beside Spellbook's: ${text()}")

        commands.submit("combo remove $id")
        assertIs<Ask.Buttons>(commands.ui.ask).buttons.single { it.first == "Remove" }.second()
        commands.ui.ask = null // what the ask bar does once a button is pressed
        assertTrue("removed your combo $id" in text())
        commands.submit("combo remove 1234-5678")
        assertTrue("(combo not found" in text() || "only your own" in text(), text())

        commands.submit("prune")
        assertTrue("stale card row" in text(), text())
        assertNull(commands.ui.ask, "a dry run asks nothing")
        assertTrue(changed == 0, "nothing deleted: nothing to rebuild")
    }
}
