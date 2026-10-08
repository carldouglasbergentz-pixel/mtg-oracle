package mtgoracle.core.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoolSortTest {
    private fun row(name: String, type: String, colors: String, mv: Double) = SearchRow(name, type, null, colors, mv)

    private val pool = listOf(
        row("Lightning Bolt", "Instant", "R", 1.0),
        row("Goblin Guide", "Creature — Goblin Scout", "R", 1.0),
        row("Hill Giant", "Creature — Giant", "R", 4.0),
        row("Serra Angel", "Creature — Angel", "W", 5.0),
        row("Boros Charm", "Instant", "R,W", 2.0),
        row("Mind Stone", "Artifact", "", 2.0),
        row("Plains", "Basic Land — Plains", "", 0.0),
        row("Delver of Secrets // Insectile Aberration", "Creature — Human Wizard // Creature — Human Insect", "U", 1.0),
        row("Fire // Ice", "Instant // Instant", "R,U", 2.0),
    )

    @Test
    fun `colour groups in WUBRG order, multicolour and colourless last, each sorted by type then mana value`() {
        val (rows, groups) = PoolSort.DEFAULT.arrange(pool)
        assertEquals(listOf("White" to 1, "Blue" to 1, "Red" to 3, "Multicolour" to 2, "Colourless" to 2), groups)
        assertEquals(
            listOf("Serra Angel", "Delver of Secrets // Insectile Aberration", "Goblin Guide", "Hill Giant", "Lightning Bolt", "Boros Charm", "Fire // Ice", "Mind Stone", "Plains"),
            rows.map { it.name },
        )
    }

    @Test
    fun `by type then mana value, the front face deciding the type`() {
        val (rows, groups) = PoolSort.parse(listOf("type", "mv"))!!.arrange(pool)
        assertEquals(listOf("Creature" to 4, "Instant" to 3, "Artifact" to 1, "Land" to 1), groups)
        assertEquals(listOf("Delver of Secrets // Insectile Aberration", "Goblin Guide", "Hill Giant", "Serra Angel"), rows.take(4).map { it.name })
    }

    @Test
    fun `no layer is one group in name order`() {
        val (rows, groups) = PoolSort.parse(listOf("-"))!!.arrange(pool)
        assertEquals(listOf("the pool" to pool.size), groups)
        assertEquals(pool.map { it.name }.sortedBy { it.lowercase() }, rows.map { it.name })
    }

    @Test
    fun `a slot turns to the next layer no other slot holds, then to none, and the command says it back`() {
        val sort = PoolSort.DEFAULT
        assertEquals("sort colour type mv", sort.command)
        assertEquals("sort - type mv", sort.cycled(0).command, "type and mv are taken: colour goes to none")
        assertEquals("sort colour type mv", sort.cycled(0).cycled(0).command)
        assertEquals("sort colour type -", sort.cycled(2).command)
        assertEquals("sort colour mv -", PoolSort.parse(listOf("colour", "-", "-"))!!.cycled(1).cycled(1).command)
    }

    @Test
    fun `parse takes one to three layers, color as colour, none twice`() {
        assertEquals("sort colour - -", PoolSort.parse(listOf("color"))!!.command)
        assertNull(PoolSort.parse(listOf("mv", "mv")))
        assertNull(PoolSort.parse(listOf("rarity")))
        assertNull(PoolSort.parse(emptyList()))
        assertNull(PoolSort.parse(listOf("colour", "type", "mv", "colour")))
    }
}
