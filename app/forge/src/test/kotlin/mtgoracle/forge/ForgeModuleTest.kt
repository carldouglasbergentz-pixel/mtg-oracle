package mtgoracle.forge

import mtgoracle.core.art.ArtKind
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.play.GameMode
import mtgoracle.core.seat.ScriptedSeat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForgeModuleTest {
    private val home = File(System.getProperty("mtgoracle.testHome"), "forge-home")

    @BeforeAll
    fun start() {
        ForgeRuntime.initialise(ForgeSetup(File(System.getProperty("mtgoracle.forgeAssets")), home))
    }

    @Test
    fun `Forge's user data and cache live under our home, via our own profile`() {
        val profile = ForgeRuntime.setup.assets.resolve("forge.profile.properties").readText()
        assertContains(profile, "userDir=" + home.resolve("user").canonicalPath.replace('\\', '/'))
        assertTrue(File(forge.localinstance.properties.ForgeConstants.USER_DIR).canonicalFile.startsWith(home.canonicalFile))
    }

    @Test
    fun `a home inside the real Forge profile is refused`() {
        val appData = System.getenv("APPDATA") ?: return
        val setup = ForgeSetup(ForgeRuntime.setup.assets, File(appData, "Forge/somewhere"))
        // The guard runs before anything else, so try it on a fresh object's check alone.
        val error = assertFailsWith<ForgeSetupError> { guard(setup) }
        assertContains(error.message!!, "refusing")
    }

    private fun guard(setup: ForgeSetup) {
        val m = ForgeRuntime::class.java.getDeclaredMethod("requireOwnProfile", ForgeSetup::class.java)
        m.isAccessible = true
        try { m.invoke(ForgeRuntime, setup) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
    }

    /**
     * The same answers as the Python rule (forge_format.forge_printing, which
     * gave these codes and art indexes over tools/forge/res/editions), and
     * the same PaperCard Forge's own deck reader picks for the `.dck` line
     * the Python export writes (`Name|CODE|[number]`).
     */
    @Test
    fun `a Scryfall printing resolves as the Python rule and Forge's deck reader do`() {
        val cases = listOf(
            // name, set, number -> Forge code, collector number, Python's art index (null: only Forge's deck reader decides it)
            Triple("Sol Ring", "c18", "222") to Triple("C18", "222", 1),
            Triple("Mountain", "m21", "270") to Triple("M21", "270", 2),
            Triple("Mountain", "m21", "271") to Triple("M21", "271", 3),
            Triple("Island", "unf", "236") to Triple("UNF", "236", 1),
            Triple("Fable of the Mirror-Breaker // Reflection of Kiki-Jiki", "neo", "141") to Triple("NEO", "141", 1),
            // The List gains printings with every Forge release (BLC-129 sorts before C18-222 since 2.0.16), so its art index moves.
            Triple("Sol Ring", "plst", "C18-222") to Triple("PLST", "C18-222", null),
        )
        val db = forge.StaticData.instance().commonCards
        for ((input, expected) in cases) {
            val (name, set, number) = input
            val pc = assertNotNull(ForgeCards.paperCard(name, set, number), "$input")
            assertEquals(expected.first, pc.edition, "$input edition")
            assertEquals(expected.second, pc.collectorNumber, "$input collector number")
            expected.third?.let { assertEquals(it, pc.artIndex, "$input art index (Python's)") }
            val viaDck = assertNotNull(db.getCard("${mtgoracle.core.deck.forgeCardName(name)}|${expected.first}|[${expected.second}]"), "Forge reads the .dck form")
            assertEquals(pc.collectorNumber to pc.artIndex, viaDck.collectorNumber to viaDck.artIndex, "$input: .dck form picks the same art")
        }
        // A number Forge doesn't list: that edition, its default art.
        assertEquals("C18", ForgeCards.paperCard("Sol Ring", "c18", "99999")?.edition)
        // A set code nobody knows: Forge's default printing.
        assertNotNull(ForgeCards.paperCard("Sol Ring", "zzz", "1"))
        assertNull(ForgeCards.paperCard("Not A Real Card", null, null))
        assertNotNull(ForgeCards.paperCard("Fire // Ice", null, null))
    }

    @Test
    fun `the deck check names unknown cards and cards the AI won't play`() {
        val deck = Deck(1, "Test", null, null, listOf(
            DeckCard("Lightning Bolt", 4, false, false), DeckCard("Not A Real Card", 1, false, false),
            DeckCard("Mountain", 20, false, false),
        ))
        val check = ForgeCards.check(AiCopy.asBuilt(deck))
        assertEquals(listOf("Not A Real Card"), check.unknown)
        assertEquals(24, ForgeCards.toForgeDeck(AiCopy.asBuilt(deck)).main.countAll())
    }

    @Test
    fun `image keys resolve to Forge's cache paths, and a missing file is null`() {
        val key = assertNotNull(ForgeRuntime.images.keyFor("Lightning Bolt", "m10", null))
        assertTrue(key.startsWith("c:"), key)
        assertEquals(null, ForgeRuntime.images.file(key + "-nonexistent", ArtKind.FULL))
        assertNotNull(ForgeRuntime.images.artist(key))
    }

    @Test
    fun `a whole game runs through the seam, and the result is reported`() {
        val lands = Deck(2, "Mono Red", null, null, listOf(DeckCard("Mountain", 24, false, false), DeckCard("Raging Goblin", 20, false, false), DeckCard("Lightning Bolt", 16, false, false)))
        val log = File(home, "logs/seam-test.log")
        val match = ForgeMatch.start(MatchSpec(GameMode.HUMAN_VS_AI, AiCopy.asBuilt(lands), AiCopy.asBuilt(lands), log, seed = 7))
        val finished = ScriptedSeat(match.seat).play(timeoutMillis = 240_000)
        assertTrue(finished, "the game reached a result")
        val deadline = System.currentTimeMillis() + 10_000
        while (match.result.value == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val result = assertNotNull(match.result.value)
        assertTrue((result.turns ?: 0) > 0)
        match.recorder.close()
        val text = log.readText()
        assertContains(text, " SEAT  PROMPT #")
        assertContains(text, " RESULT ")
    }
}
