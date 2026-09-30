package mtgoracle.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.seat.Policy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Face down" means face down, and a card exiled face up is seen at once.
 * The user saw "face-down" flicker past in a Bo3 where nothing was face down:
 * the AI's Laelia exiled its own library's top card face up, Ragavan exiled
 * ours and the AI cast it, Deep-Cavern Bat exiled a card from our hand.
 */
class HiddenOrFaceDownTest {
    private fun BoardState.ai() = players.first { !it.isSeat }
    private fun library(vararg top: String, rest: String) = (top.toList() + List(20) { rest }).joinToString(";")
    private val passAll: Policy = { p, _, _ ->
        when {
            p is InputPrompt && (p.kind == InputKind.PRIORITY || p.kind == InputKind.CONFIRM) -> SeatAction.Ok
            p is ChoicePrompt && p.isReveal -> SeatAction.Choose(emptyList())
            else -> null
        }
    }

    /**
     * Plays [state] until [until], recording every board this seat is shown
     * (the collector runs in the publisher's thread, so none is skipped) and
     * every line drawn on screen, and returns everything that called a
     * face-up card face-down or hid a card in a public zone.
     */
    private fun faceDownClaims(name: String, state: List<String>, until: (Scenario, List<BoardState>) -> Boolean): List<String> {
        val claims = linkedSetOf<String>()
        Scenario(name, state, policy = passAll).use { s ->
            val boards = CopyOnWriteArrayList<BoardState>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            scope.launch { s.match.seat.board.collect { b -> if (b != null) boards += b } }
            try {
                s.playUntil(timeoutMillis = 120_000) {
                    s.screenText().lines().filter { "face-down" in it.lowercase() || "face down" in it.lowercase() }.forEach { claims += "screen: ${it.trim()}" }
                    until(s, boards)
                }
            } finally { scope.cancel() }
            for (b in boards) {
                for (p in b.players) for ((zone, cards) in listOf("exile" to p.exile, "graveyard" to p.graveyard, "command" to p.command)) {
                    cards.filter { it.hidden || it.faceDown }.forEach { claims += "${p.name}'s $zone: hidden=${it.hidden} faceDown=${it.faceDown}" }
                }
                b.stack.filter { it.sourceName == "face-down" || "face-down" in it.text || it.targetNames.any { t -> "face-down" in t } }
                    .forEach { claims += "stack: ${it.sourceName}: ${it.text}" }
                b.trail.filter { "face-down" in it.text }.forEach { claims += "trail: ${it.text}" }
            }
            assertTrue(boards.isNotEmpty())
        }
        return claims.toList()
    }

    @Test
    fun `Laelia exiles the top of its own library face up - named at once, never face-down`() {
        val state = listOf(
            "turn=3", "activeplayer=ai", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Opt", "humanbattlefield=Island", "humanlibrary=${library(rest = "Island")}",
            "aihand=", "aibattlefield=Laelia, the Blade Reforged;Swamp;Mountain;Mountain",
            "ailibrary=${library("Duress", rest = "Mountain")}",
        )
        var named = false
        var trailNamed = false
        val claims = faceDownClaims("laelia-exile", state) { s, _ ->
            named = named || s.board.ai().exile.any { it.name == "Duress" }
            trailNamed = trailNamed || s.board.trail.any { it.text == "Duress library → exile" }
            named && s.board.activePlayerId != s.board.ai().id
        }
        assertTrue(named, "the exiled Duress was shown by name")
        assertTrue(trailNamed, "and the trail named it: exiled face up is public")
        assertEquals(emptyList(), claims)
    }

    @Test
    fun `Ragavan exiles the top of our library and the AI may cast it - named at once, never face-down`() {
        val state = listOf(
            "turn=3", "activeplayer=ai", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Island", "humanlibrary=${library("Brainstorm", rest = "Island")}",
            "aihand=", "aibattlefield=Ragavan, Nimble Pilferer;Mountain", "ailibrary=${library(rest = "Mountain")}",
        )
        var seen = false
        val claims = faceDownClaims("ragavan-exile", state) { s, _ ->
            val me = s.board.seat!!
            seen = seen || me.exile.any { it.name == "Brainstorm" } || s.board.stack.any { it.sourceName == "Brainstorm" }
            seen && s.board.activePlayerId != s.board.ai().id
        }
        assertTrue(seen, "Brainstorm was shown by name in exile (or on the stack)")
        assertEquals(emptyList(), claims)
    }

    @Test
    fun `the AI's hideaway card is face down to us - a face-down back, and its name nowhere`() {
        val top = listOf("Lightning Bolt", "Hymn to Tourach", "Dark Confidant", "Opposition Agent")
        val state = listOf(
            "turn=3", "activeplayer=ai", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Island", "humanlibrary=${library(rest = "Island")}",
            "aihand=Shelldock Isle", "aibattlefield=", "ailibrary=${library(*top.toTypedArray(), rest = "Swamp")}",
        )
        Scenario("hideaway-theirs", state, policy = passAll).use { s ->
            s.playUntil(timeoutMillis = 120_000) { s.board.ai().exile.isNotEmpty() }
            s.screenText() // settle
            val exiled = s.board.ai().exile.single()
            assertTrue(exiled.hidden && exiled.faceDown, "a back, and face down: $exiled")
            assertEquals("", exiled.name)
            val text = s.screenText()
            assertTrue("face-down card" in text, "the exile list says face-down")
            for (name in top) assertTrue(name !in text, "'$name' was named: ${text.lines().filter { name in it }}")
            // The trail once named it: Forge turns a hideaway card face down only after the move event.
            val trail = s.board.trail.map { it.text }
            assertTrue("a face-down card library → exile" in trail, "the trail says what the table saw: $trail")
        }
    }

    @Test
    fun `our own hideaway card is face down, and we may look at it`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=Shelldock Isle", "humanbattlefield=", "humanlibrary=${library("Opt", "Brainstorm", "Ponder", "Preordain", rest = "Island")}",
            "aihand=", "aibattlefield=", "ailibrary=${library(rest = "Swamp")}",
        )
        Scenario("hideaway-ours", state) { p, b, _ ->
            val isle = b.seat!!.hand.firstOrNull { it.name == "Shelldock Isle" }
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && isle != null -> SeatAction.ClickCard(isle.id)
                p is ChoicePrompt && !p.isReveal -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Opt" in it.label }.coerceAtLeast(0)))
                p is ChoicePrompt -> SeatAction.Choose(emptyList())
                p is InputPrompt && p.kind == InputKind.SELECT_CARDS -> b.seat!!.let { null }
                else -> null
            }
        }.use { s ->
            s.playUntil(timeoutMillis = 120_000) { s.board.seat!!.exile.isNotEmpty() }
            val exiled = s.board.seat!!.exile.single()
            assertTrue(exiled.faceDown, "face down: $exiled")
            assertTrue(!exiled.hidden && "Opt" in exiled.name, "and ours to look at: '${exiled.name}'")
        }
    }

    @Test
    fun `Deep-Cavern Bat exiles a card from our hand - named at once, never face-down`() {
        val state = listOf(
            "turn=3", "activeplayer=ai", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=Personal Tutor;Island", "humanbattlefield=Island", "humanlibrary=${library(rest = "Island")}",
            "aihand=Deep-Cavern Bat", "aibattlefield=Swamp;Swamp", "ailibrary=${library(rest = "Swamp")}",
        )
        var seen = false
        val claims = faceDownClaims("bat-exile", state) { s, _ ->
            seen = seen || s.board.seat!!.exile.any { it.name == "Personal Tutor" }
            seen && s.board.activePlayerId != s.board.ai().id
        }
        assertTrue(seen, "Personal Tutor was shown by name in exile")
        assertEquals(emptyList(), claims)
    }
}
