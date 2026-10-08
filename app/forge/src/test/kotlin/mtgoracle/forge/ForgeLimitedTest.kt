package mtgoracle.forge

import mtgoracle.core.deck.Section
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.PoolRule
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forge's packs and its AI's sealed deck: a seed opens the same packs and
 * builds the same deck every time, in every JVM. Network play depends on it
 * (each side opens its own pool, and the other opens it again to check), so
 * the digests below are recorded: they move only when Forge's packs do, and
 * then both sides of a table need that Forge.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForgeLimitedTest {
    private val home = File(System.getProperty("mtgoracle.testHome"), "forge-home")

    @BeforeAll
    fun start() {
        ForgeRuntime.initialise(ForgeSetup(File(System.getProperty("mtgoracle.forgeAssets")), home))
    }

    private fun digest(pool: OpenedPool): String = MessageDigest.getInstance("SHA-256")
        .digest(pool.packs.joinToString("\n") { pack -> pack.joinToString("|") { "${it.name}/${it.setCode}/${it.collectorNumber}/${it.foil}" } }.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    @Test
    fun `the sets offered are Forge's boosters, newest first, without the digital and Un-sets`() {
        val sets = ForgeLimited.sets
        val codes = sets.map { it.code }
        assertTrue("BLB" in codes && "DOM" in codes && "LEA" in codes && "ISD" in codes, codes.toString())
        assertTrue(codes.none { it in setOf("YBLB", "VMA", "UST", "UNH") }, "digital-only and Un-sets left out")
        assertEquals(sets.sortedByDescending { it.released }.map { it.released }, sets.map { it.released })
        assertEquals("blb", ForgeLimited.set("blb")?.scryfallCode)
        assertNull(ForgeLimited.set("NOPE"))
    }

    @Test
    fun `a seed opens the same packs every time, the play booster's slots included`() {
        for (code in listOf("BLB", "DSK", "DOM", "ISD", "LEA")) {
            val set = assertNotNull(ForgeLimited.set(code))
            val first = ForgeLimited.open(set, 6, seed = 42)
            val again = ForgeLimited.open(set, 6, seed = 42)
            assertEquals(first, again, code)
            assertNotEquals(first.packs, ForgeLimited.open(set, 6, seed = 43).packs, "$code: another seed, other packs")
            assertEquals(6, first.packs.size)
        }
    }

    @Test
    fun `a pack holds what the set's booster holds`() {
        fun sizes(code: String) = ForgeLimited.open(ForgeLimited.set(code)!!, 6, seed = 7).packs.map { it.size }.toSet()
        // Bloomburrow's play booster: 6 commons, a guest slot, 3 uncommons, a rare, a land, two wildcards.
        assertEquals(setOf(14), sizes("BLB"))
        // Dominaria: 10 commons, 3 uncommons, a rare, a basic land (a foil takes a common's place).
        assertEquals(setOf(15), sizes("DOM"))
        assertEquals(setOf(15), sizes("LEA"))
        val blb = ForgeLimited.open(ForgeLimited.set("BLB")!!, 6, seed = 7)
        assertTrue(blb.cards.all { it.setCode.isNotBlank() }, "every card has its printing's set")
    }

    @Test
    fun `the packs are the recorded ones, in any JVM`() {
        val recorded = mapOf("BLB" to "7bf345c6d2a4b048", "DOM" to "2906c42dd7011d14", "LEA" to "49ca822c5d2f6a1f")
        val now = recorded.keys.associateWith { digest(ForgeLimited.open(ForgeLimited.set(it)!!, 6, seed = 20261008)) }
        assertEquals(recorded, now)
    }

    @Test
    fun `the AI builds forty cards from its pool, the rest in the sideboard, the same deck from the same pool`() {
        for (code in listOf("BLB", "DOM")) {
            val pool = ForgeLimited.open(ForgeLimited.set(code)!!, 6, seed = 11)
            val deck = ForgeLimited.build(pool)
            assertEquals(deck, ForgeLimited.build(pool), "$code: the same pool builds the same deck")
            assertEquals(40, deck.filter { it.section == Section.MAIN }.sumOf { it.quantity }, code)
            val held = deck.groupBy { it.forgeName }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }
            val opened = pool.counts
            val basics = setOf("Plains", "Island", "Swamp", "Mountain", "Forest")
            assertEquals(emptyList(), PoolRule.excess(opened, held) { it in basics }, "$code: nothing beyond the pool but basic lands")
            assertEquals(emptyMap(), opened.filter { (name, n) -> (held[name] ?: 0) < n }, "$code: the whole pool is in the deck or its sideboard")
        }
    }
}
