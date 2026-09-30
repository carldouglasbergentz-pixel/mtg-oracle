package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.data.Library
import mtgoracle.data.Lookup
import mtgoracle.ui.lookup.OutputLink
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The command line's commands, without a window: what each prints, what it
 * remembers, and what it refuses. On the real database, read-only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupCommandsTest {
    private lateinit var lookup: Lookup
    private lateinit var library: Library
    private lateinit var commands: LookupCommands
    private val copied = mutableListOf<String>()
    private val entered = mutableListOf<Int>()
    private var quits = 0
    private var realStamp = 0L

    @BeforeAll
    fun open() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        realStamp = DbFixture.realDb.lastModified()
        lookup = Lookup(DbFixture.readOnly())
        library = Library(DbFixture.readOnly())
    }

    @AfterAll
    fun untouched() {
        if (::lookup.isInitialized) assertEquals(realStamp, DbFixture.realDb.lastModified(), "the real database must not be touched")
    }

    @BeforeTest
    fun fresh() {
        if (!::lookup.isInitialized) return
        copied.clear(); entered.clear(); quits = 0
        commands = LookupCommands(lookup, decks = { library.decks() }, faceOf = { null },
            onEnterDeck = { entered += it }, copyToClipboard = { copied += it }, onQuit = { quits++ })
    }

    /** What [line] printed (its echo included), as text at 100 columns. */
    private fun run(line: String): String {
        val before = commands.output.entries.size
        commands.submit(line)
        return commands.output.entries.drop(before).flatMap { it.rendering.lines(100) }.joinToString("\n") { it.text }
    }

    private fun links(): List<OutputLink> = commands.output.entries.flatMap { it.rendering.lines(100) }.flatMap { it.spans }.map { it.link }

    @Test
    fun `card shows the profile, tolerantly, and says when nothing matches`() {
        val out = run("card lightning bolt")
        assertTrue(out.startsWith("> card lightning bolt"), out)
        assertTrue("Lightning Bolt" in out && "Legality:" in out && "Top combos featuring this card" in out, out)
        assertTrue("(card not found: Zzyzx the Unreal)" in run("card Zzyzx the Unreal"))
        assertTrue("usage: card <name>" in run("card"))
        assertTrue(commands.ui.showOutput, "a command shows the output pane")
    }

    @Test
    fun `card N expands a row of the last search page`() {
        run("search n:\"lightning bolt\" order:asc_name")
        val out = run("card 1")
        assertTrue("\nLightning Bolt" in out, out)
        assertTrue("(no row #99 in last search; valid range is 1.." in run("card 99"))
    }

    @Test
    fun `search pages, and says where the ends are`() {
        val first = run("search t:goblin order:asc_mv")
        assertTrue(first.contains(Regex("\\d+ card\\(s\\) — showing 1-50 \\(page 1 of \\d+\\)")), first)
        assertTrue("(already on first page)" in run("prev"))
        assertTrue("showing 51-100 (page 2 of" in run("next"))
        assertTrue("(valid pages are 1.." in run("page 999"))
        assertTrue("usage: page <N>" in run("page x"))
        assertTrue("showing 1-50" in run("page 1"))
        assertTrue(OutputLink.Run("next") in links())
    }

    @Test
    fun `a search error says what is wrong and where the syntax is`() {
        val out = run("search pow!3")
        assertTrue("search error: unsupported op '!' on power" in out, out)
        assertTrue("(type `search help` for syntax)" in out)
        assertTrue("Scryfall-style search" in run("search help"))
        assertTrue("(no prior search" in LookupCommands(lookup, { emptyList() }, { null }).let { c -> c.submit("next"); c.output.allText() })
    }

    @Test
    fun `cd into a commander deck filters search and names the filters, cd dot-dot lifts them`() {
        val deckId = DbFixture.readOnly().read { c ->
            c.prepareStatement("SELECT deck_id FROM deck_cards WHERE is_commander = 1 LIMIT 1").use { s -> s.executeQuery().use { r -> if (r.next()) r.getInt(1) else null } }
        }
        assumeTrue(deckId != null, "a commander deck")
        val deck = library.decks().first { it.id == deckId }
        val named = if (library.decks().count { it.name.equals(deck.name, true) } > 1) "${deck.folderName ?: "(unsorted)"}/${deck.name}" else deck.name
        assertTrue("search is limited to ci<=" in run("cd $named"))
        assertEquals("${deck.name}> ", commands.ui.prompt)
        assertEquals(listOf(deckId), entered, "the deck is selected in the list too")
        val scoped = run("search t:creature order:asc_mv")
        assertTrue(scoped.lines()[1].startsWith("[deck filter: ci<="), scoped)
        val total = { text: String -> Regex("(\\d+) card\\(s\\)").find(text)!!.groupValues[1].toInt() }
        run("cd ..")
        assertEquals("> ", commands.ui.prompt)
        assertTrue(total(run("search t:creature order:asc_mv")) > total(scoped))
        assertTrue("(no deck named 'No Such Deck'" in run("cd No Such Deck"))
    }

    @Test
    fun `paging keeps the deck's filters after leaving the deck`() {
        val deck = library.decks().firstOrNull { d -> lookup.deckScope(d.id)?.filters?.isNotEmpty() == true && library.decks().count { it.name == d.name } == 1 }
        assumeTrue(deck != null, "a deck with filters")
        run("cd ${deck!!.name}")
        val scoped = run("search t:creature")
        run("cd ..")
        val again = run("page 1")
        assertEquals(Regex("\\d+ card\\(s\\)").find(scoped)!!.value, Regex("\\d+ card\\(s\\)").find(again)!!.value)
    }

    @Test
    fun `one combo opens at once, a list numbers its rows for combo-info`() {
        val list = run("combo Thassa's Oracle")
        assertTrue("combo(s) featuring Thassa's Oracle:" in list, list)
        assertTrue("[  1]" in list)
        val detail = run("combo-info 1")
        assertTrue("Cards:" in detail && "Steps:" in detail, detail)
        assertTrue("(no combo #9999 in last list" in run("combo-info 9999"))
        assertTrue("(combo not found: nope)" in run("combo-info nope"))
        assertTrue("need at least 2 cards separated by ';'" in run("combos Thassa's Oracle"))
        assertTrue("containing ALL of: Thassa's Oracle + Demonic Consultation" in run("combos Thassa's Oracle; Demonic Consultation") ||
            "Cards:" in run("combos Thassa's Oracle; Demonic Consultation"))
    }

    @Test
    fun `rules, rule search, rulings and corrections`() {
        val rule = run("rule 702")
        assertTrue("[702]" in rule && "Child rules:" in rule, rule)
        assertTrue(OutputLink.Rule("702.2") in links())
        assertTrue("(rule not found: 999.9)" in run("rule 999.9"))
        assertTrue("rule(s) matching 'deathtouch'" in run("search-rules deathtouch"))
        val rulings = run("ruling delver of secrets")
        assertTrue("Delver of Secrets // Insectile Aberration - " in rulings, "the header names the card as it is: $rulings")
        assertTrue("correction(s):" in run("correction"))
        assertTrue("(no corrections)" in run("correction %"))
    }

    @Test
    fun `a click runs the value, not text - a two-faced name, a rule, a page turn`() {
        commands.open(OutputLink.Card("Fire // Ice"))
        val out = commands.output.allText()
        assertTrue(out.startsWith("> card Fire // Ice") && "Legality:" in out, out)
        commands.open(OutputLink.Rule("702.2"))
        assertTrue("[702.2]" in commands.output.allText())
        commands.open(OutputLink.Run("next"))
        assertTrue("(no prior search" in commands.output.allText())
    }

    @Test
    fun `copy takes the previous command's output, clear empties, quit asks to quit`() {
        run("rule 100.1")
        run("copy")
        val text = copied.single()
        assertTrue(text.startsWith("[100.1]") && "> " !in text.lines().first(), text)
        run("copy all")
        assertTrue(copied.last().startsWith("> rule 100.1"))
        assertTrue("usage: copy [last|all]" in run("copy nav"))
        run("clear")
        assertEquals(0, commands.output.entries.size)
        run("quit")
        assertEquals(1, quits)
    }

    @Test
    fun `a line that is no command is a search, and a command typo is named when nothing matches`() {
        val bolt = run("lightning bolt")
        assertTrue(bolt.lines()[1].startsWith("8 card(s)") || "card(s) — showing" in bolt, bolt)
        assertTrue(bolt.lines()[2].contains("] Lightning Bolt "), "an exact name ranks first: $bolt")
        val typo = run("crad sol ring")
        assertTrue("(no card matches, and 'crad' is no command — did you mean `card`?)" in typo, typo)
        assertTrue("did you mean" !in run("goblin mv=1 c:r"), "a search that finds cards needs no hint")
    }

    @Test
    fun `the preview reads a query back, counts it, and says what is wrong`() {
        val p = commands.preview("t:instant c:u mv<=2 counter")!!
        assertEquals("type has \"instant\" · colours include U · mana value ≤ 2 · \"counter\" in name, type or text", p.text)
        assertTrue(p.isSearch && !p.error)
        assertTrue(commands.count("t:instant c:u mv<=2 counter")!! > 0)
        val bad = commands.preview("typ:instant")!!
        assertTrue(bad.error && "did you mean type:?" in bad.text, bad.text)
        assertEquals(null, commands.count("typ:instant"))
        assertTrue("combos <card>; <card>" in commands.preview("combos ")!!.text, "a command gets its usage")
        assertEquals(null, commands.count("rule 702"), "a command is not counted")
        assertEquals(null, commands.preview(""))
    }

    @Test
    fun `help lists the commands and has a search topic`() {
        val help = run("help")
        for (command in listOf("card <name>", "combos <card1>; <card2>", "search <query>", "cd <deck>", "copy [last|all]")) assertTrue(command in help, command)
        assertTrue("Scryfall-style search" in run("help search"))
        assertTrue("(no help topic 'decks'" in run("help decks"))
    }

    @Test
    fun `autofill knows the decks for cd`() {
        val prefix = library.decks().first().name.take(3)
        val suggestion = assertNotNull(commands.ui.suggest("cd ${prefix.lowercase()}"))
        // Another deck may share the prefix and come first; it must be a deck either way.
        assertTrue(library.decks().any { "cd ${it.name}" == suggestion && it.name.startsWith(prefix, ignoreCase = true) }, suggestion)
        assertNull(commands.ui.suggest("cd"), "a whole command is not re-offered")
    }
}
