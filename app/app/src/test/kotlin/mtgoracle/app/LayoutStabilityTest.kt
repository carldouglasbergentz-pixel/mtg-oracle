package mtgoracle.app

import androidx.compose.ui.geometry.Rect
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.Lanes
import mtgoracle.ui.board.Looks
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Nothing on the board moves except the cards that changed. A real turn is
 * played — priority, a land, a Bolt aimed and on the stack, the stack empty
 * again, an attack — and at each state every fixed region and every card is
 * measured where it is drawn: regions never move; a card moves only when a
 * card joined or left its row, or it was tapped or untapped (a turned card
 * shifts one cell).
 */
class LayoutStabilityTest {
    private class Shot(val label: String, val rects: Map<ClickTarget, Rect>, val board: BoardState)

    private fun BoardState.ai() = players.first { !it.isSeat }
    private fun BoardState.inHand(name: String) = seat!!.hand.firstOrNull { it.name == name }

    /** The floating stack box and its bar come and go by design; they are not the board's layout. */
    private fun regions(s: Shot) = s.rects.filterKeys { it is ClickTarget.Control && !it.name.startsWith("region:stack-b") }
    private fun cards(s: Shot) = s.rects.filterKeys { it is ClickTarget.Card }.mapKeys { (it.key as ClickTarget.Card).id }

    private fun compare(a: Shot, b: Shot) {
        assertEquals(regions(a).keys, regions(b).keys, "${a.label} -> ${b.label}: the same regions")
        for ((k, r) in regions(a)) assertEquals(r, regions(b)[k], "${a.label} -> ${b.label}: $k moved")
        val ca = cards(a)
        val cb = cards(b)
        fun row(rects: Map<Int, Rect>, top: Float) = rects.filterValues { it.top == top }.keys
        for (id in ca.keys intersect cb.keys) {
            val was = ca.getValue(id)
            val now = cb.getValue(id)
            if (was == now) continue
            val turned = a.board.card(id)?.tapped != b.board.card(id)?.tapped
            val rowChanged = row(ca, was.top) != row(cb, was.top) || row(ca, now.top) != row(cb, now.top)
            if (!turned && !rowChanged) fail("${a.label} -> ${b.label}: card $id (${b.board.card(id)?.name}) moved from $was to $now, and nothing in its row changed")
        }
    }

    @Test
    fun `a whole turn - priority, a land, a target prompt, the stack full and empty, an attack - and nothing moves that didn't change`() {
        val shots = mutableListOf<Shot>()
        lateinit var s: Scenario
        fun shoot(label: String, board: BoardState) {
            if (shots.none { it.label == label }) {
                shots += Shot(label, s.layout(), board)
                s.png("stability-$label")
            }
        }
        s = Scenario("stability", StagedBoards.turn) { prompt, board, _ ->
            val input = prompt as? InputPrompt
            when {
                input?.kind == InputKind.PRIORITY && board.activePlayerId == board.seat?.id && board.stack.isEmpty() && board.inHand("Mountain") != null -> {
                    shoot("priority", board); SeatAction.ClickCard(board.inHand("Mountain")!!.id)
                }
                input?.kind == InputKind.PRIORITY && board.stack.isEmpty() && board.inHand("Lightning Bolt") != null -> {
                    shoot("land-played", board); SeatAction.ClickCard(board.inHand("Lightning Bolt")!!.id)
                }
                input?.kind == InputKind.TARGET -> { shoot("target", board); SeatAction.ClickPlayer(board.ai().id) }
                input?.kind == InputKind.PRIORITY && board.stack.any { it.sourceName == "Lightning Bolt" } -> { shoot("stack", board); SeatAction.Ok }
                input?.kind == InputKind.PRIORITY && board.activePlayerId == board.seat?.id && board.stack.isEmpty() -> { shoot("resolved", board); SeatAction.Ok }
                input?.kind == InputKind.ATTACK -> {
                    val guide = board.seat!!.battlefield.first { it.name == "Goblin Guide" }
                    if (!guide.attacking) { shoot("attack", board); SeatAction.ClickCard(guide.id) } else { shoot("attacking", board); SeatAction.Ok }
                }
                prompt is ChoicePrompt -> null
                else -> null
            }
        }
        s.use {
            s.playUntil { shots.any { it.label == "attacking" } }
            assertEquals(listOf("priority", "land-played", "target", "stack", "resolved", "attack", "attacking"), shots.map { it.label })
            assertTrue(shots.first { it.label == "stack" }.rects.keys.contains(ClickTarget.Control("region:stack-box")), "the stack box was up while Bolt was on the stack")
            shots.zipWithNext().forEach { (a, b) -> compare(a, b) }
        }
    }

    @Test
    fun `tapping one of two Islands by hand - the untapped pile stays put and the tapped one sits right after it`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=Opt", "humanbattlefield=Island;Island;Plains;Swamp;Mountain", "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        lateinit var s: Scenario
        var before: Map<ClickTarget, Rect>? = null
        var tappedId = -1
        s = Scenario("pile-order", state) { prompt, board, _ ->
            val islands = board.seat?.battlefield?.filter { it.name == "Island" }.orEmpty()
            when {
                (prompt as? InputPrompt)?.kind == InputKind.PRIORITY && islands.size == 2 && islands.none { it.tapped } && before == null -> {
                    before = s.layout()
                    // Tap the one Forge lists first: an order that followed Forge's list would put its pile first.
                    tappedId = islands.first().id
                    SeatAction.ClickCard(tappedId)
                }
                else -> null
            }
        }
        s.use {
            s.playUntil { s.board.seat!!.battlefield.any { it.id == tappedId && it.tapped } }
            val after = s.layout()
            val pileBefore = before!!.getValue(ClickTarget.Card(tappedId))
            val other = s.board.seat!!.battlefield.first { it.name == "Island" && it.id != tappedId }.id
            assertEquals(pileBefore.left, after.getValue(ClickTarget.Card(other)).left, "the untapped Island takes the pile's place")
            val tapped = after.getValue(ClickTarget.Card(tappedId))
            val plains = s.board.seat!!.battlefield.first { it.name == "Plains" }.id
            assertTrue(tapped.left > pileBefore.left && tapped.left < after.getValue(ClickTarget.Card(plains)).left, "the tapped one right after it, before the Plains")
            // (The piles after it make room for the new one: a split adds a slot. Their order holds.)
            val order = listOf("Plains", "Swamp", "Mountain").map { n -> after.getValue(ClickTarget.Card(s.board.seat!!.battlefield.first { it.name == n }.id)).left }
            assertEquals(order.sorted(), order, "the other names keep their order")
        }
    }

    @Test
    fun `lands stack by state, and an animated manland stands in the creature row`() {
        Scenario("lands", StagedBoards.lands, mode = CardMode.ART) { prompt, board, _ ->
            // Null until the staged board is in place (the opening prompts come first).
            val colonnade = board.seat?.battlefield?.firstOrNull { it.name == "Celestial Colonnade" }
            when {
                colonnade == null -> null
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && !colonnade.isCreature -> SeatAction.ClickCard(colonnade.id)
                prompt is ChoicePrompt && !prompt.isReveal -> SeatAction.Choose(listOf(prompt.options.indexOfFirst { "until end of turn" in it.label.lowercase() }.coerceAtLeast(0)))
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.seat!!.battlefield.any { it.name == "Celestial Colonnade" && it.isCreature } && s.board.stack.isEmpty() && (s.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY }
            val mine = s.board.seat!!.battlefield
            val lanes = Lanes(mine, Looks(null, emptySet(), CardMode.ART, {}, {}))
            assertTrue(lanes.creatures.any { it.card.name == "Celestial Colonnade" }, "animated: in the creature row")
            val islands = lanes.lands.filter { it.card.name == "Island" }
            assertTrue(islands.any { it.cards.size > 1 }, "identical Islands share a frame: ${islands.map { it.cards.size }}")
            assertTrue(islands.all { slot -> slot.cards.map { it.tapped }.distinct().size == 1 }, "and a stack never mixes tapped with untapped")
            assertTrue(islands.map { it.card.tapped }.distinct().size == 2, "tapped Islands are their own stack")
            val colonnade = mine.first { it.name == "Celestial Colonnade" }
            val island = islands.first().card
            assertTrue(s.rect(ClickTarget.Card(colonnade.id))!!.top < s.rect(ClickTarget.Card(island.id))!!.top, "our creature row is nearer the midline than our lands")
            assertTrue(s.screenText().contains("×"), "the stack count is drawn")
            s.png("lands-art")
            s.setMode(CardMode.TEXT)
            s.png("lands-text")
        }
    }
}
