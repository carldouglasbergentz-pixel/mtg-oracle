package mtgoracle.app

import mtgoracle.core.deck.DeckSection
import mtgoracle.data.DbFixture
import mtgoracle.data.DeckWriter
import mtgoracle.data.Library
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import mtgoracle.ui.lookup.DeckTab
import mtgoracle.ui.lookup.EditAction
import mtgoracle.ui.lookup.OutputLink
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workspace's edits without a window, on a copy of the database: what
 * each does to the deck, what a refusal leaves on the screen and how `add
 * anyway` pushes it through, and what the history, the considering flags
 * and the points show afterwards.
 */
class DeckEditingTest {
    private lateinit var data: File
    private lateinit var db: MtgDb
    private lateinit var commands: LookupCommands
    private val notices = mutableListOf<String>()
    private var changed = 0

    /** A fresh deck in [format] on a copy, opened to work on; returns its id. */
    private fun open(format: String?, name: String = "__editing__"): Int {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val id = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            c.prepareStatement("INSERT INTO decks (name, format, created_at, updated_at) VALUES (?, ?, datetime('now'), datetime('now'))").use { st ->
                st.setString(1, name); st.setString(2, format); st.executeUpdate()
            }
            c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getInt(1) } }
        }
        db = MtgDb(copy)
        val lookup = Lookup(db)
        val library = Library(db)
        commands = LookupCommands(lookup, decks = { library.decks() }, faceOf = { null },
            writer = DeckWriter(db, lookup.names, lookup.formats), onDeckChanged = { changed++ }, notify = { notices += it })
        check(commands.enterDeck(id))
        return id
    }

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun deck(id: Int) = Library(db).deck(id)!!
    private fun main(id: Int) = deck(id).cards.filter { !it.isSideboard }.associate { it.name to it.quantity }

    @Test
    fun `a result's buttons add to the deck, the sideboard and the list, each a revision`() {
        val id = open("commander")
        val ui = commands.ui
        ui.edit(EditAction.Promote("Savra, Queen of the Golgari"))
        commands.open(OutputLink.Edit(EditAction.Add("Sol Ring", DeckSection.MAIN)))
        ui.edit(EditAction.Add("Sol Ring", DeckSection.SIDEBOARD))
        ui.edit(EditAction.Add("Counterspell", DeckSection.CONSIDERING))
        assertEquals(1, main(id)["Sol Ring"])
        assertEquals(listOf("Counterspell"), deck(id).considering.map { it.name })
        assertEquals(listOf("consider", "add", "add", "promote"), ui.history.map { it.action })
        assertTrue(changed >= 4, "the screen is told each time")
        assertTrue(notices.last().startsWith("considering +1 Counterspell"), notices.last())
        assertTrue("Counterspell" in ui.flags, "the list marks what the deck's rules would stop: ${ui.flags}")
        assertTrue("identity" in ui.flags.getValue("Counterspell"))
    }

    @Test
    fun `a refusal says why, add anyway pushes it through, and the next change clears it`() {
        val id = open("commander")
        val ui = commands.ui
        ui.edit(EditAction.Promote("Savra, Queen of the Golgari"))
        ui.edit(EditAction.Add("Counterspell", DeckSection.MAIN))
        val refusal = assertNotNull(ui.refusal)
        assertTrue(refusal.forceable && "outside the deck's CI" in refusal.text, refusal.text)
        assertNull(main(id)["Counterspell"], "refused: nothing changed")
        ui.edit(EditAction.Force)
        assertEquals(1, main(id)["Counterspell"])
        assertNull(ui.refusal)
        assertTrue(notices.last().endsWith("(forced)"))
        ui.edit(EditAction.Add("Zzyzx the Unreal", DeckSection.MAIN))
        assertEquals(false, ui.refusal?.forceable, "a card that doesn't exist is not a rule to override")
    }

    @Test
    fun `rows move, lose copies and leave, and undo takes the newest back`() {
        val id = open(null)
        val ui = commands.ui
        ui.edit(EditAction.Add("Forest", DeckSection.MAIN, quantity = 5))
        ui.edit(EditAction.Remove("Forest", DeckSection.MAIN))
        assertEquals(4, main(id)["Forest"])
        ui.edit(EditAction.Move("Forest", DeckSection.MAIN, DeckSection.CONSIDERING))
        assertEquals(3, main(id)["Forest"])
        assertEquals(1, deck(id).considering.single().quantity)
        ui.edit(EditAction.Remove("Forest", DeckSection.MAIN, all = true))
        assertNull(main(id)["Forest"])
        commands.submit("undo")
        assertEquals(3, main(id)["Forest"], "undo brought the three back")
        commands.submit("history")
        assertEquals(DeckTab.HISTORY, ui.deckTab)
        assertEquals("undo", ui.history.first().action)
    }

    @Test
    fun `the deck commands take flags and a count, and a card whose name ends in a number stays one`() {
        val id = open(null)
        commands.submit("add --sb Lightning Bolt 3")
        assertEquals(3, deck(id).cards.single { it.isSideboard }.quantity)
        commands.submit("add Island 4")
        commands.submit("remove Island 1")
        assertEquals(3, main(id)["Island"])
        commands.submit("consider Arcane Signet 2")
        assertEquals(2, deck(id).considering.single().quantity)
        commands.submit("remove --considering Arcane Signet")
        assertTrue(deck(id).considering.isEmpty())
        commands.submit("commander Sol Ring")
        assertTrue(deck(id).cards.single { it.name == "Sol Ring" }.isCommander)
        commands.submit("commander --unset Sol Ring")
        assertTrue(!deck(id).cards.single { it.name == "Sol Ring" }.isCommander)
        commands.submit("add Pain 101")
        assertEquals(1, main(id)["Pain 101"], "Pain 101 is a card, not 101 of Pain")
    }

    @Test
    fun `a points format prices the deck and marks pointed results`() {
        open("canlander")
        val ui = commands.ui
        assertEquals(10, ui.pointsBudget)
        assertEquals(1, ui.pointsOf("mana drain"))
        commands.submit("search n=\"Mana Drain\"")
        val row = commands.output.latestSearch!!.rendering.lines(120)[1].text
        assertTrue("Mana Drain (1)" in row && row.contains("+ sb ?"), row)
        ui.edit(EditAction.Add("Ancestral Recall", DeckSection.MAIN))
        ui.edit(EditAction.Add("Black Lotus", DeckSection.MAIN))
        assertTrue(ui.refusal!!.text.contains("point"), "8 + 7 is over 10: ${ui.refusal}")
    }
}
