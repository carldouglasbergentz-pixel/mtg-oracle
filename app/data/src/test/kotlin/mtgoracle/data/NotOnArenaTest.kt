package mtgoracle.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which cards MTG Arena lacks, for an Arena export: told by Arena's own
 * formats, not by `cards.games`, which is the games of one printing only
 * (Eternal Witness's lacks Arena, Gush's lacks MTGO). On the user's database.
 */
class NotOnArenaTest {
    @Test
    fun `Arena's formats tell, a ban there included`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val lookup = Lookup(DbFixture.readOnly())
        assertEquals(
            listOf("Sol Ring", "Wasteland"),
            lookup.notOnArena(listOf("Sol Ring", "Eternal Witness", "Lightning Bolt", "Wasteland", "sol ring")),
            "Eternal Witness is on Arena whatever its printing says; Lightning Bolt is, banned in Historic",
        )
        assertEquals(emptyList(), lookup.notOnArena(emptyList()))
    }
}
