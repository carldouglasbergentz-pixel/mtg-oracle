package mtgoracle.forge

import forge.StaticData
import forge.localinstance.properties.ForgeConstants
import mtgoracle.core.art.ArtKind
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An image key on a remote seat is the host's to choose, and Forge turns a
 * token key's name into a file name unchecked: a key that steps out of the
 * cache is refused before Forge sees it, and every token Forge has still
 * passes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageKeySafetyTest {
    private val home = File(System.getProperty("mtgoracle.testHome"), "forge-home")

    @BeforeAll
    fun start() {
        ForgeRuntime.initialise(ForgeSetup(File(System.getProperty("mtgoracle.forgeAssets")), home))
    }

    @Test
    fun `every token Forge has keeps its art`() {
        // As PaperToken names them: each edition's token list, its name, set and collector number (Forge's own preload
        // stops at a token script it can't read, so the keys are built from the lists themselves).
        val keys = StaticData.instance().editions.flatMap { edition ->
            edition.tokens.values().map { t -> "t:" + "${t.name()}|${edition.code}|${t.collectorNumber()}|1".replace(" ", "_") }
        }.distinct()
        assertTrue(keys.size > 1000, "Forge's tokens: ${keys.size}")
        val refused = keys.filterNot { ForgeImages.safeToken(it) }
        assertEquals(emptyList(), refused.take(20), "${refused.size} of ${keys.size} token keys refused")
    }

    @Test
    fun `a key that steps out of the cache is refused, and the empty folder it names is left alone`() {
        val victim = File(home, "outside-the-cache-${System.nanoTime()}").also { it.mkdirs() }
        val tokens = File(ForgeConstants.CACHE_TOKEN_PICS_DIR).canonicalFile.also { it.mkdirs() }
        val up = tokens.toPath().relativize(victim.canonicalFile.toPath()).toString().replace('\\', '/')
        assertTrue(up.startsWith(".."), up)
        for (key in listOf("t:$up", "t:x/$up|M21|1", "t:..\\..\\x|M21|1", "t:C:/Windows/x|M21|1", "t:a\u0000b|M21|1")) {
            assertTrue(!ForgeImages.safeToken(key), key)
            assertNull(ForgeRuntime.images.file(key, ArtKind.FULL), key)
        }
        assertTrue(victim.isDirectory, "Forge's lookup deletes an empty folder its key names; this one is still there")
        victim.delete()
    }
}
