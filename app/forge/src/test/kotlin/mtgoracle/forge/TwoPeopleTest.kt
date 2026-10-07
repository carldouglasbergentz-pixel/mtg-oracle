package mtgoracle.forge

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.play.GameMode
import mtgoracle.core.seat.ScriptedSeat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two people at one Forge table (HUMAN_VS_HUMAN): each seat filtered for its
 * own person, a game played to its end, and Forge's static dialogs, which
 * name no player, reaching the person Forge asked last.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoPeopleTest {
    private val home = File(System.getProperty("mtgoracle.testHome"), "forge-home")
    private val logs = File(home, "two-people").also { it.mkdirs() }

    @BeforeAll
    fun start() {
        ForgeRuntime.initialise(ForgeSetup(File(System.getProperty("mtgoracle.forgeAssets")), home))
    }

    private fun deck(id: Int, name: String, vararg cards: Pair<String, Int>) =
        AiCopy.asBuilt(Deck(id, name, null, null, cards.map { (card, n) -> DeckCard(card, n, false, false) }))

    // Nothing here bounces, reveals or looks at a hand: any card of the other's hand a seat can read is a leak.
    private val red = deck(1, "Red", "Mountain" to 24, "Raging Goblin" to 12, "Goblin Piker" to 12, "Shock" to 12)
    private val green = deck(2, "Green", "Forest" to 24, "Grizzly Bears" to 12, "Giant Growth" to 12, "Llanowar Elves" to 12)

    private fun start(name: String, seed: Long) =
        ForgeMatch.start(MatchSpec(GameMode.HUMAN_VS_HUMAN, red, green, File(logs, "$name.log").also { it.delete() }, seed = seed, seatName = "Alice", guestName = "Bob"))

    private fun waitFor(what: String, timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `two people play a game to its end, each seeing only their own hand`() {
        val match = start("game", seed = 7)
        val alice = match.seat
        val bob = match.guest!!
        val leaks = ConcurrentLinkedQueue<String>()
        val seats = ConcurrentLinkedQueue<String>()
        val players = listOf("Alice" to alice, "Bob" to bob).map { (who, seat) ->
            thread(name = "two-people-$who") { ScriptedSeat(seat, retryAfterMillis = 1500).play(timeoutMillis = 180_000) }
        }
        val watcher = thread(name = "two-people-watch") {
            while (players.any { it.isAlive }) {
                for ((who, seat) in listOf("Alice" to alice, "Bob" to bob)) {
                    val board = seat.board.value ?: continue
                    seats += "$who: ${board.players.filter { it.isSeat }.map { it.name }}"
                    board.players.filter { !it.isSeat }.forEach { other ->
                        other.hand.filter { !it.hidden }.forEach { leaks += "$who read ${other.name}'s ${it.name} on turn ${board.turn}" }
                    }
                }
                Thread.sleep(20)
            }
        }
        players.forEach { it.join() }
        watcher.join()

        assertTrue(alice.board.value!!.gameOver && bob.board.value!!.gameOver, "the game ended for both")
        waitFor("the result") { match.games.value.isNotEmpty() }
        assertTrue(match.over, "one game: the match is over")
        assertEquals(setOf("Alice: [Alice]", "Bob: [Bob]"), seats.toSet(), "each seat is its own person's")
        assertEquals(emptyList(), leaks.distinct(), "neither read the other's hand")
        val log = match.recorder.file.readText()
        assertFalse("UNHANDLED" in log, log.lines().filter { "UNHANDLED" in it }.joinToString("\n"))
        assertTrue("[Alice] PROMPT" in log && "[Bob] PROMPT" in log, "the log says whose each prompt was")
        assertFalse(ForgeRuntime.busy, "Forge is free again")
    }

    @Test
    fun `a static dialog goes to the person Forge asked last`() {
        val match = start("static-dialog", seed = 3)
        val people = listOf(match.seat, match.guest!!)
        try {
            // The first person asked (play or draw, a mulligan): a static dialog now is theirs.
            waitFor("a first prompt") { people.any { it.prompt.value != null } }
            val first = people.first { it.prompt.value != null }
            val second = people.first { it !== first }
            assertEquals("first", staticChoice(first, notTo = second))
            // They answer until the other is asked: then it is the other's.
            while (second.prompt.value == null) {
                first.prompt.value?.let { first.answer(it.id, plainAnswer(it)) }
                Thread.sleep(50)
            }
            waitFor("the first person done") { first.prompt.value == null }
            assertEquals("first", staticChoice(second, notTo = first))
        } finally {
            match.leave()
            people.forEach { seat -> repeat(20) { seat.prompt.value?.let { seat.answer(it.id, plainAnswer(it)) }; Thread.sleep(20) } }
        }
    }

    /** Forge's static getChoices from the engine's side, as HumanCostDecision asks "from whose zone?": it must reach [to], never [notTo]. */
    private fun staticChoice(to: GameSeat, notTo: GameSeat): String? {
        var picked: List<String>? = null
        val asking = thread(name = "static-dialog") {
            picked = ForgeRuntime.guiBase.getChoices("From whose graveyard?", 1, 1, mutableListOf("first", "second"), null, null)
        }
        waitFor("the static dialog") { (to.prompt.value as? ChoicePrompt)?.message?.contains("From whose graveyard?") == true }
        assertFalse((notTo.prompt.value as? ChoicePrompt)?.message?.contains("From whose graveyard?") == true, "not to the other person")
        val prompt = to.prompt.value!!
        to.answer(prompt.id, SeatAction.Choose(listOf(0)))
        asking.join(10_000)
        return picked?.single()
    }

    private fun plainAnswer(prompt: Prompt): SeatAction = when (prompt) {
        is ConfirmPrompt -> SeatAction.Confirm(true)
        is ChoicePrompt -> SeatAction.Choose(listOf(0))
        else -> SeatAction.Ok
    }
}
