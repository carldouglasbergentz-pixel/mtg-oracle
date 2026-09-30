package mtgoracle.app

import mtgoracle.core.deck.CardPrinting
import mtgoracle.core.deck.DeckSection
import mtgoracle.data.DbFixture
import mtgoracle.data.DeckWriter
import mtgoracle.data.Library
import mtgoracle.data.LibraryWriter
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import mtgoracle.ui.library.LibraryIntent
import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.LookupUi
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library's changes without a window, on a copy of the database: each
 * intent asks its question, nothing is written before it is answered, and
 * the answer makes the write.
 */
class LibraryActionsTest {
    private lateinit var data: File
    private lateinit var db: MtgDb
    private lateinit var library: Library
    private lateinit var ui: LookupUi
    private lateinit var actions: LibraryActions
    private var clipboard: String? = null
    private val said = mutableListOf<String>()
    private val opened = mutableListOf<Int>()

    @BeforeTest
    fun open() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        db = MtgDb(copy)
        library = Library(db)
        val lookup = Lookup(db)
        val writer = DeckWriter(db, lookup.names, lookup.formats)
        val commands = LookupCommands(lookup, decks = { library.decks() }, faceOf = { null }, writer = writer)
        ui = commands.ui
        actions = LibraryActions(
            library, LibraryWriter(db), writer, lookup, ui,
            refresh = {}, deckChanged = {}, openDeck = { opened += it }, openDeckId = { null }, leaveDeck = {},
            say = { said += it }, show = { r -> said += r.lines(100).joinToString("\n") { it.text } },
            readClipboard = { clipboard }, writeClipboard = { clipboard = it },
            printingsOf = { listOf(CardPrinting("c21", "263", "Commander 2021", "2021-04-23"), CardPrinting("lea", "270", "Limited Edition Alpha", "1993-08-05")) },
        )
    }

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun text(intent: LibraryIntent, answer: String) {
        actions.handle(intent)
        val ask = assertIs<Ask.Text>(ui.ask, "a name is asked for")
        ui.ask = null
        ask.onOk(answer)
    }

    private fun choose(intent: LibraryIntent, label: String) {
        actions.handle(intent)
        val ask = assertIs<Ask.Choose>(ui.ask)
        ui.ask = null
        ask.onPick(ask.options.first { it.label == label })
    }

    private fun press(intent: LibraryIntent?, button: String) {
        intent?.let(actions::handle)
        val ask = assertIs<Ask.Buttons>(ui.ask)
        ui.ask = null
        ask.buttons.first { it.first == button }.second()
    }

    private fun deck(name: String) = library.decks().single { it.name == name }

    @Test
    fun `a folder with a default format, a deck in it that inherits it, renamed, moved, deleted`() {
        text(LibraryIntent.NewFolder, "__Actions__")
        val folder = library.folders().single { it.name == "__Actions__" }
        choose(LibraryIntent.FolderFormat(folder.id), "Canadian Highlander")
        press(null, "Only new decks")
        assertEquals("canadianhighlander", library.folders().single { it.id == folder.id }.format)

        text(LibraryIntent.NewDeck(folder.id), "__New__")
        val made = deck("__New__")
        assertEquals("canadianhighlander", made.format, "a new deck takes its folder's default")
        assertEquals(listOf(made.id), opened, "and opens to edit")

        text(LibraryIntent.RenameDeck(made.id), "__Renamed__")
        assertEquals("__Renamed__", library.decks().single { it.id == made.id }.name)
        choose(LibraryIntent.MoveDeck(made.id), "(no folder)")
        assertNull(library.decks().single { it.id == made.id }.folderId)
        choose(LibraryIntent.DeckFormat(made.id), "(no format: no rules)")
        assertNull(library.decks().single { it.id == made.id }.format)

        actions.handle(LibraryIntent.DeleteDeck(made.id))
        assertTrue(library.decks().any { it.id == made.id }, "nothing is deleted before the answer")
        press(null, "Delete")
        assertTrue(library.decks().none { it.id == made.id })
        press(LibraryIntent.DeleteFolder(folder.id), "Delete")
        assertTrue(library.folders().none { it.id == folder.id })
    }

    @Test
    fun `a refused name says why and changes nothing`() {
        val taken = library.decks().first()
        text(LibraryIntent.NewDeck(taken.folderId), taken.name.uppercase())
        assertTrue(said.last().startsWith("refused: a deck named"), said.last())
        text(LibraryIntent.NewFolder, "a/b")
        assertTrue("'/'" in said.last())
    }

    @Test
    fun `import from the clipboard - a new deck, then added to, then replaced after a preview`() {
        clipboard = "Commander\n1 Tymna the Weaver\nDeck\n1 Sol Ring (C18) 263\n4 Plains\nMaybeboard\n1 Opt"
        text(LibraryIntent.Import(null), "__Imported__")
        val made = deck("__Imported__")
        val full = library.deck(made.id)!!
        assertEquals(6, full.cards.sumOf { it.quantity })
        assertEquals("c18", full.cards.single { it.name == "Sol Ring" }.setCode)
        assertEquals(listOf("Opt"), full.considering.map { it.name })
        assertEquals("commander", made.format, "a commander in the list makes it a Commander deck")

        clipboard = "1 Swords to Plowshares"
        press(LibraryIntent.ImportInto(made.id), "Add to the deck")
        assertEquals(1, library.deck(made.id)!!.cards.count { it.name == "Swords to Plowshares" })

        clipboard = "Commander\n1 Tymna the Weaver\nDeck\n2 Plains\n1 Zzyzx the Unreal"
        val beforePreview = library.deck(made.id)!!.cards
        press(LibraryIntent.ImportInto(made.id), "Replace the deck...")
        assertTrue(said.any { "replace __Imported__ with the clipboard would change:" in it }, "the preview is in the output")
        assertEquals(beforePreview, library.deck(made.id)!!.cards, "the preview changed nothing")
        val ask = assertIs<Ask.Buttons>(ui.ask)
        assertTrue("not found are left out" in ask.title, ask.title)
        press(null, "Replace")
        val after = library.deck(made.id)!!
        assertEquals(setOf("Tymna the Weaver", "Plains"), after.cards.map { it.name }.toSet())
        assertEquals(listOf("Opt"), after.considering.map { it.name }, "no maybeboard in the list: the list stays")
    }

    @Test
    fun `export to the clipboard, and a printing chosen from the list`() {
        clipboard = "1 Sol Ring\n1 Fire // Ice\n1 Delver of Secrets"
        text(LibraryIntent.Import(null), "__Export__")
        val id = deck("__Export__").id
        press(LibraryIntent.Export(id), "Front faces only")
        val exported = assertNotNull(clipboard)
        assertTrue(exported.startsWith("Deck\n"), exported)
        assertTrue("1 Fire // Ice" in exported && "1 Delver of Secrets\n" in exported, "a split card keeps both halves, a DFC its front: $exported")

        choose(LibraryIntent.ChoosePrinting(id, "Sol Ring", DeckSection.MAIN), "C21 263")
        val sol = library.deck(id)!!.cards.single { it.name == "Sol Ring" }
        assertEquals("c21" to "263", sol.setCode to sol.collectorNumber)
        choose(LibraryIntent.ChoosePrinting(id, "Sol Ring", DeckSection.MAIN), "default art")
        assertNull(library.deck(id)!!.cards.single { it.name == "Sol Ring" }.setCode)
    }
}
