package mtgoracle.app

import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import mtgoracle.ui.board.Lanes
import mtgoracle.ui.board.Looks
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.face
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A land played this turn is new, and has a pile of its own: of two Mishra's
 * Factories, the one that can be animated and attack is the old one. Forge's
 * `isSick` is creatures only, so the two read alike and stacked as one.
 */
class NewLandTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `a manland played this turn is new, and piled apart from the one that was there`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Mishra's Factory;Island", "humanbattlefield=Mishra's Factory", "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        var played = false
        Scenario("new-land", state) { p, b, _ ->
            val me = b.seat!!
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && !played -> { played = true; SeatAction.ClickCard(me.hand.first { it.name == "Mishra's Factory" }.id) }
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.seat!!.battlefield.count { it.name == "Mishra's Factory" } == 2 }
            val factories = s.board.seat!!.battlefield.filter { it.name == "Mishra's Factory" }
            assertEquals(listOf(false, true), factories.map { it.summoningSick }.sorted(), "the one played this turn is new: $factories")
            val piles = Lanes(s.board.seat!!.battlefield, Looks(null, emptySet(), CardMode.ART, {}, {})).lands.filter { it.card.name == "Mishra's Factory" }
            assertEquals(2, piles.size, "two piles, not one of two: $piles")
            assertTrue("new" in piles.single { it.card.summoningSick }.card.face().stats.split(' '), "the new one says so")
            assertTrue("new" !in piles.single { !it.card.summoningSick }.card.face().stats.split(' '))
            // Forge keeps the flag on a card in any zone: a land still in hand is not new on the table.
            val island = s.board.seat!!.hand.single { it.name == "Island" }
            assertTrue(!island.summoningSick && "new" !in island.face().stats.split(' '), "a land in hand says nothing: $island")
        }
    }
}
