package mtgoracle.app

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A library search asks once, with only what may be taken: Ash Barrens'
 * basic landcycling offers the basics. The library Forge reveals for the
 * search was a prompt of its own first, every card of it, and a click more.
 */
class LibrarySearchTest {
    private fun priority(p: Prompt) = p is InputPrompt && p.kind == InputKind.PRIORITY
    private fun BoardState.me() = seat!!

    @Test
    fun `basic landcycling offers the basic lands, in one choice`() {
        val library = (listOf("Island", "Mountain") + List(15) { "Lightning Bolt" } + List(5) { "Counterspell" }).joinToString(";")
        val setup = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Ash Barrens", "humanbattlefield=Island", "humangraveyard=", "humanlibrary=$library",
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        val choices = mutableListOf<ChoicePrompt>()
        Scenario("search-ash-barrens", setup) { p, b, _ ->
            val barrens = b.me().hand.firstOrNull { it.name == "Ash Barrens" }
            when {
                priority(p) && b.stack.isEmpty() && barrens != null -> SeatAction.ClickCard(barrens.id)
                p is ChoicePrompt && p.options.any { "landcycling" in it.label.lowercase() } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "landcycling" in it.label.lowercase() }))
                p is ChoicePrompt -> { choices += p; SeatAction.Choose(listOf(p.options.indexOfFirst { "Mountain" in it.label })) }
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> null
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().hand.any { it.name == "Mountain" } }
            assertEquals(1, choices.size, "one choice: ${choices.map { it.message }}")
            assertEquals(listOf("Island", "Mountain"), choices.single().options.map { it.label.substringBefore(" (") }.sorted(), "only the basics")
            assertTrue("also looked at" in choices.single().message, "and the rest of the library is said, not offered: ${choices.single().message}")
        }
    }
}
