package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.StackKind
import mtgoracle.ui.board.TARGET_MARK
import mtgoracle.ui.kit.ClickTarget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the other side does, on the table and not only in the log, in real
 * Forge games: a stop whenever they put something on the stack (MTGO's rule,
 * F4 included), the floating stack box with the source's art, its text and
 * its targets marked on the board, and the trail for what never waits on
 * the stack.
 */
class OpponentActionsTest {
    private fun BoardState.ai() = players.first { !it.isSeat }
    private fun priority(s: Scenario) = (s.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY
    private fun top(s: Scenario) = s.match.seat.board.value?.stack?.firstOrNull()
    private val stackBox = ClickTarget.Control("region:stack-box")
    private val passAll: mtgoracle.core.seat.Policy = { prompt, _, _ -> if (prompt is InputPrompt && prompt.kind == InputKind.PRIORITY) SeatAction.Ok else null }

    @Test
    fun `the opponent cracks a fetchland - we stop with it on the stack, the box shows it, the trail shows what it did`() {
        Scenario("opponent-fetch", StagedBoards.fetch, policy = passAll).use { s ->
            s.playUntil { priority(s) && top(s)?.sourceName == "Polluted Delta" }
            val item = s.board.stack.first()
            assertEquals(StackKind.ACTIVATED, item.kind)
            assertEquals(s.board.ai().id, item.controllerId)
            val source = assertNotNull(item.source, "the source card travels with the item")
            assertNotNull(source.imageKey, "so its art can be drawn")
            assertTrue(s.board.ai().graveyard.any { it.id == source.id }, "though it is already in the graveyard (sacrificed as a cost)")
            val text = s.screenText() // settles the scene first
            assertTrue(s.registered(stackBox), "the stack box floats over the table")
            assertContains(text, "activated Polluted Delta — respond?")
            assertTrue("▲ AI · activated ability · top" in text || "^ AI · activated ability · top" in text,
                "who and what kind, in the box: ${text.lines().filter { "ability" in it || "▲" in it }}")
            assertContains(text, "Pay 1 life")
            s.png("opponent-fetch-on-stack")

            s.playUntil { s.board.ai().battlefield.count { it.name == "Swamp" } == 3 }
            val trail = s.board.trail.filter { it.actorId == s.board.ai().id }.map { it.text }
            assertContains(trail, "played Polluted Delta")
            assertContains(trail, "Polluted Delta → graveyard")
            assertContains(trail, "life 20→19")
            assertContains(trail, "fetched Swamp")
            val strip = s.tableText()
            for (entry in listOf("Polluted Delta → graveyard", "life 20→19", "fetched Swamp")) assertContains(strip, entry, message = "the midline trail")
            val fetched = s.board.ai().battlefield.filter { it.name == "Swamp" }.map { it.id }.toSet()
            assertTrue(s.board.freshCardIds.any { it in fetched }, "the fetched Swamp is marked until our next decision")
            s.png("opponent-fetch-trail")
        }
    }

    @Test
    fun `an opponent's spell with a target - the target is marked on the table while the spell is on the stack`() {
        Scenario("opponent-bolt", StagedBoards.bolt, policy = passAll).use { s ->
            s.playUntil(timeoutMillis = 120_000) { priority(s) && top(s)?.sourceName == "Lightning Bolt" }
            val bolt = s.board.stack.first()
            assertTrue(bolt.targets.isNotEmpty(), "Bolt names its target: ${bolt.targetNames}")
            val text = s.screenText()
            assertContains(text, "cast Lightning Bolt targeting ${bolt.targetNames.first()} — respond?")
            when (val target = bolt.targets.first()) {
                is BoardRef.Card -> assertTrue(text.lines().any { it.trim() == TARGET_MARK || it.trim() == "<" }, "the targeted card is marked")
                is BoardRef.Player -> assertContains(text, "$TARGET_MARK target")
                else -> error("a Bolt can't target $target")
            }
            assertContains(text, "→ ${bolt.targetNames.first()}")
            s.png("opponent-bolt")
        }
    }

    @Test
    fun `F4 still stops when the opponent puts something on the stack - their trigger in our turn`() {
        Scenario("f4-trigger", StagedBoards.trigger) { prompt, board, _ ->
            val bears = board.seat!!.hand.firstOrNull { it.name == "Grizzly Bears" }
            if (prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && bears != null) SeatAction.ClickCard(bears.id) else null
        }.use { s ->
            s.playUntil { priority(s) && top(s)?.sourceName == "Grizzly Bears" }
            s.key(Key.F4) // done for the turn, while our own creature spell is on the stack
            s.waitFor { priority(s) && top(s)?.sourceName == "Soul Warden" }
            assertEquals(s.board.seat!!.id, s.board.activePlayerId, "still our turn: F4 was interrupted, not finished")
            assertEquals(StackKind.TRIGGERED, s.board.stack.first().kind)
            assertContains(s.screenText(), "Soul Warden triggered — respond?")
        }
    }

    @Test
    fun `the trail counts the opponent's draws and never names a hidden card`() {
        Scenario("hidden-draws", StagedBoards.hiddenDraws, policy = passAll).use { s ->
            s.playUntil { s.board.trail.any { it.actorId == s.board.ai().id && it.text.startsWith("drew") } }
            val draws = s.board.trail.filter { it.text.startsWith("drew") && it.actorId == s.board.ai().id }
            assertTrue(draws.all { it.text == "drew a card" || it.text.matches(Regex("drew \\d+ cards")) }, "a count only: $draws")
            assertTrue(draws.all { it.cardIds.isEmpty() }, "and no id to tie it to a card")
            val everything = s.board.trail.joinToString("\n") { it.text } + "\n" + s.screenText()
            for (name in StagedBoards.hiddenDrawNames) assertFalse(name in everything, "'$name' was named")
        }
    }
}
