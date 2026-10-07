package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.Prompt
import mtgoracle.core.play.GameMode
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.forge.ForgeMatch
import mtgoracle.forge.MatchSpec
import mtgoracle.net.HostMessage
import mtgoracle.net.LogReceiver
import mtgoracle.net.LogSender
import mtgoracle.net.Wire
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real games' boards and prompts over the wire, as a remote seat gets them:
 * each board as a board message without its log and a log message of the
 * lines the guest lacks, put back together on the other side. Every one must
 * come out equal to what went in. Measures what a message weighs.
 */
class WireRealGameTest {
    private val out = File(Scenario.home, "wire").also { it.mkdirs() }

    private class Capture(val boards: MutableList<BoardState> = mutableListOf(), val prompts: MutableList<Prompt> = mutableListOf())

    private fun play(name: String, a: PlayDeck, b: PlayDeck, seed: Long, turns: Int): Map<String, Capture> {
        Scenario.startForge()
        val match = ForgeMatch.start(MatchSpec(GameMode.HUMAN_VS_HUMAN, a, b, File(out, "$name.log").also { it.delete() }, seed = seed, seatName = "Alice", guestName = "Bob"))
        val seats = listOf("Alice" to match.seat, "Bob" to match.guest!!)
        val captures = seats.associate { it.first to Capture() }
        val players = seats.map { (who, seat) -> thread(name = "wire-$who") { ScriptedSeat(seat, retryAfterMillis = 1500).play(timeoutMillis = 300_000) { (seat.board.value?.turn ?: 0) > turns } } }
        val watcher = thread(name = "wire-watch") {
            while (players.any { it.isAlive }) {
                for ((who, seat) in seats) collect(seat, captures.getValue(who))
                Thread.sleep(5)
            }
        }
        players.forEach { it.join() }
        watcher.join()
        match.leave()
        return captures
    }

    private fun collect(seat: GameSeat, capture: Capture) {
        seat.board.value?.let { if (capture.boards.lastOrNull() != it) capture.boards += it }
        seat.prompt.value?.let { if (capture.prompts.lastOrNull() != it) capture.prompts += it }
    }

    /** Sends [capture] as the host would and rebuilds it as the guest does; returns a line on the sizes. */
    private fun overTheWire(who: String, capture: Capture): String {
        val sender = LogSender()
        val receiver = LogReceiver()
        val boardSizes = mutableListOf<Int>()
        val logSizes = mutableListOf<Int>()
        for (board in capture.boards) {
            sender.next(board.log)?.let { message ->
                val line = Wire.encode(message)
                logSizes += line.length
                receiver.apply(Wire.host(line) as HostMessage.Log)
            }
            val line = Wire.encode(HostMessage.Board(board.copy(log = emptyList())))
            boardSizes += line.length
            val rebuilt = receiver.join((Wire.host(line) as HostMessage.Board).board!!)
            assertEquals(board, rebuilt, "$who's board on turn ${board.turn} came out changed")
        }
        val promptSizes = capture.prompts.map { prompt ->
            val line = Wire.encode(HostMessage.Ask(prompt))
            assertEquals(prompt, (Wire.host(line) as HostMessage.Ask).prompt, "$who's prompt #${prompt.id} came out changed")
            line.length
        }
        fun kb(sizes: List<Int>) = if (sizes.isEmpty()) "-" else "avg %.1f KB, max %.1f KB".format(sizes.average() / 1024, sizes.max() / 1024.0)
        assertTrue(boardSizes.max() < 128 * 1024, "a board message under 128 KB: ${boardSizes.max()}")
        val wholeLog = capture.boards.last().log.let { Wire.encode(HostMessage.Log(-1, it)).length }
        return "$who: ${capture.boards.size} boards (${kb(boardSizes)}), ${logSizes.size} log messages (${kb(logSizes)}), " +
            "${capture.prompts.size} prompts (${kb(promptSizes)}); the whole log at the end ${"%.1f".format(wholeLog / 1024.0)} KB in ${capture.boards.last().log.size} lines"
    }

    private fun deck(id: Int, name: String, vararg cards: Pair<String, Int>) =
        AiCopy.asBuilt(Deck(id, name, null, null, cards.map { (card, n) -> DeckCard(card, n, false, false) }))

    @Test
    fun `a constructed game's boards and prompts over the wire`() {
        val captures = play("constructed",
            deck(1, "Red", "Mountain" to 24, "Raging Goblin" to 12, "Goblin Piker" to 12, "Shock" to 12),
            deck(2, "Green", "Forest" to 24, "Grizzly Bears" to 12, "Giant Growth" to 12, "Llanowar Elves" to 12), seed = 7, turns = 30)
        val report = captures.map { (who, c) -> overTheWire(who, c) }
        File(out, "constructed-sizes.txt").writeText(report.joinToString("\n"))
        report.forEach(::println)
    }

    @Test
    fun `the user's Duel Commander decks over the wire`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        Scenario.startForge()
        val data = DbFixture.copy().parentFile
        try {
            val app = AppController(AppPaths(data, File(System.getProperty("mtgoracle.forgeAssets")), forgeHome = Scenario.home)).also { it.boot() }
            fun deck(name: String) = app.decks.firstOrNull { it.name == name }?.let { app.deckById(it.id) } ?: error("no deck named $name")
            val prepared = Sessions(null, data).prepare(deck("Jori En"), deck("Phelia Doggo"), useAiCopy = false)
            val captures = play("duel-commander", prepared.seat, prepared.opponent, seed = 11, turns = 12)
            val report = captures.map { (who, c) -> overTheWire(who, c) }
            File(out, "duel-commander-sizes.txt").writeText(report.joinToString("\n"))
            report.forEach(::println)
        } finally { data.deleteRecursively() }
    }
}
