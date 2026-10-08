package mtgoracle.app

import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.DeckSection
import mtgoracle.core.deck.GameType
import mtgoracle.core.limited.Sealed
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.data.DeckWriter
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import mtgoracle.data.PoolStore
import mtgoracle.forge.ForgeLimited
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.lookup.EditAction
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sealed against the AI, through the whole app on a copy of the database:
 * a pool opened for you and the AI, your deck holding it in its sideboard,
 * the pool rule, Forge's AI's build of it, and a game against the deck the
 * AI builds from its own pool, recorded with no deck id for the AI's.
 */
class LimitedTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    private lateinit var app: AppController
    private var driver: OffscreenDriver? = null

    @AfterTest fun close() {
        driver?.close()
        if (this::data.isInitialized) data.deleteRecursively()
    }

    private fun open() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        waitFor { app.forgeReady }
    }

    private fun waitFor(millis: Long = 60_000, what: String = "", until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (!until()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting $what; notice: ${app.notice}")
            driver?.frame()
            Thread.sleep(20)
        }
    }

    private fun db() = File(data, "mtg.db")
    private fun <T> sql(query: String, read: java.sql.ResultSet.() -> T): List<T> = DriverManager.getConnection("jdbc:sqlite:${db().path}").use { c ->
        c.createStatement().use { st -> st.executeQuery(query).use { rs -> buildList { while (rs.next()) add(rs.read()) } } }
    }

    @Test
    fun `every set the lobby offers opens into cards the database knows`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        Scenario.startForge()
        val names = Lookup(MtgDb(copy)).names
        val unknown = ForgeLimited.sets.associate { set ->
            set.code to ForgeLimited.open(set, 3, seed = 1).cards.map { it.name }.filter { names.resolve(it) == null }.distinct()
        }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), unknown)
    }

    @Test
    fun `a sealed pool is opened for you and the AI, built from the sideboard, and played against the AI's own pool`() {
        open()
        app.play.openLobby(null)
        if (!app.play.limited) app.play.toggleTab()
        val blb = assertNotNull(ForgeLimited.set("BLB"))
        app.chooseSet(blb.code)
        assertEquals(blb.code, app.lobbyLimited()?.chosenSet)
        // The copy is of the user's database, which may hold limited decks of its own.
        val before = app.decks.map { it.id }.toSet()
        app.openSealed()
        waitFor(what = "the packs") { !app.opening && app.decks.any { it.id !in before } }

        val summary = app.decks.single { it.id !in before }
        assertEquals(LimitedControl.FOLDER, summary.folderName)
        val deck = assertNotNull(app.deckById(summary.id))
        assertEquals(GameType.LIMITED, deck.gameType)
        assertEquals(0, deck.mainCount, "nothing in the main deck yet")
        assertEquals(Sealed.PACKS * 14, deck.cards.filter { it.isSideboard }.sumOf { it.quantity }, "six Bloomburrow play boosters in the sideboard")
        assertTrue(deck.cards.all { it.setCode != null }, "each card in the printing it came in")
        val pools = PoolStore(MtgDb(db()))
        val mine = assertNotNull(pools.pool(deck.poolId!!))
        val ai = assertNotNull(pools.pool(mine.rivalPoolId!!))
        assertEquals("me" to "ai", mine.openedBy to ai.openedBy)
        assertTrue(mine.pool.cards != ai.pool.cards, "the AI opened packs of its own")
        assertTrue(summary.id in app.play.tabDecks().map { it.id } && app.play.tabDecks().all { it.format == Sealed.FORMAT }, "the limited tab offers it, the constructed one doesn't")

        // The workspace opens on the pool: the search is what is left of it, and + takes a card from it, - puts it back, in place.
        val ui = assertNotNull(app.lookupUi)
        val output = assertNotNull(app.commands).output
        fun pool() = assertNotNull(output.latestSearch?.page, "the pool's page; notice: ${app.notice}")
        assertEquals(deck.cards.filter { it.isSideboard }.size, pool().total, "every card of the pool, each once")
        assertTrue(pool().filters.any { "pool" in it }, pool().filters.toString())
        OffscreenDriver(1800, 1100) { AppContent(app) {} }.use { d ->
            d.settle(8)
            d.savePng(File(Scenario.pngDir, "limited-workspace.png"))
            assertTrue(d.text.all().lines().none { it.trimStart().startsWith("Sideboard (") }, "the deck pane shows the main deck only")
        }
        val single = deck.cards.first { it.isSideboard && it.quantity == 1 && it.info?.typeLine?.contains("Basic") != true }.name
        val entries = output.entries.size
        ui.edit(EditAction.Add(single, DeckSection.MAIN))
        assertEquals(1, app.deckById(deck.id)!!.mainCount, "taken into the main deck")
        assertEquals(deck.cards.filter { it.isSideboard }.size - 1, pool().total, "and gone from the pool")
        assertEquals(entries, output.entries.size, "the page replaced where it was, not added")
        ui.edit(EditAction.Remove(single, DeckSection.MAIN))
        assertEquals(0, app.deckById(deck.id)!!.mainCount)
        assertEquals(Sealed.PACKS * 14, app.deckById(deck.id)!!.cards.filter { it.isSideboard }.sumOf { it.quantity }, "back in the pool, not out of the deck")
        assertEquals(deck.cards.filter { it.isSideboard }.size, pool().total)

        // The pool rule: a card goes in as often as it was opened; basic lands freely; force says so.
        val lookup = Lookup(MtgDb(db()))
        val writer = DeckWriter(MtgDb(db()), lookup.names, lookup.formats)
        val card = deck.cards.first { it.isSideboard && it.info?.typeLine?.contains("Basic") != true }.name
        assertEquals(DeckRefusal.Kind.NOT_OPENED, assertFailsWith<DeckRefusal> { writer.add(deck.id, card) }.kind)
        writer.move(deck.id, card, DeckSection.SIDEBOARD, DeckSection.MAIN)
        writer.add(deck.id, "Forest", 3)
        writer.add(deck.id, "Lightning Bolt", force = true)
        app.deckChanged(deck.id) // written past the app, as the workspace's own writes are not
        assertEquals("pool 84 · 1 beyond the pool!", app.poolNote(app.deckById(deck.id)!!))

        // Forge's AI's build of the pool, through the replace: forty in the main deck, the rest beside it.
        val suggestion = app.limited.suggestion(app.deckById(deck.id)!!)
        writer.replace(deck.id, suggestion)
        app.deckChanged(deck.id)
        app.play.chooseMe(deck.id)
        val built = app.deckById(deck.id)!!
        assertEquals(40, built.mainCount)
        assertEquals("pool 84", app.poolNote(built))

        val prepared = assertNotNull(app.play.prepared(), "a limited match against the AI's pool")
        assertTrue(!prepared.blocked, prepared.notes.toString())
        assertEquals("AI (BLB sealed)", prepared.opponent.name)
        assertEquals(40, prepared.opponent.cards.filter { it.section == mtgoracle.core.deck.Section.MAIN }.sumOf { it.quantity })

        // One game, won at once from a staged board, recorded against no deck of ours.
        driver = OffscreenDriver(1800, 1400) { AppContent(app) {} }
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=3",
            "humanhand=Lightning Bolt", "humanbattlefield=Mountain", "humanlibrary=" + List(20) { "Mountain" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        app.play.start(startState = state)
        val match = assertNotNull(app.play.match)
        val seat = match.seat
        val scripted = ScriptedSeat(seat, { _, _, _ -> null }, retryAfterMillis = 1500, submit = { p, a -> if (driver!!.perform(p, a) == null) seat.answer(p.id, a) })
        check(scripted.play(timeoutMillis = 90_000, until = { driver!!.frame(); match.games.value.isNotEmpty() })) { "the game did not end" }
        waitFor(what = "the game's row") { sql("SELECT COUNT(*) FROM games") { getInt(1) }.single() > 0 }
        val rows = sql("SELECT mode, deck_id, deck_name, opponent_deck_id, opponent_name, winner, game_no, conceded FROM games") { (1..8).map { getObject(it) } }
        val row = rows.single().take(6)
        assertEquals(listOf<Any?>("human_vs_ai", deck.id, deck.name, null, "AI (BLB sealed)", "me"), row)
    }
}
