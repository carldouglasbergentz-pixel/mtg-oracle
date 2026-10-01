package mtgoracle.app

import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import mtgoracle.data.FixtureDb
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Forge's art first, Scryfall's for a printing Forge lacks: keys, files, artists and the one download. */
class PrintingArtTest {
    private val cache = createTempDirectory("mtg-oracle-scryfall-art-").toFile()
    @AfterTest fun clean() { cache.deleteRecursively() }

    private val printings = Lookup(MtgDb(FixtureDb.file)).printings

    /** Forge with a default key per card, and nothing on disk. */
    private object FakeForge : CardArt {
        override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?) = "forge:$cardName:${setCode.orEmpty()}"
        override fun file(key: String, kind: ArtKind): File? = null
        override fun artist(key: String) = "forge artist"
        override fun request(key: String, kind: ArtKind, onReady: () -> Unit) {}
    }

    @Test
    fun `Forge's own printing stays Forge's, one it lacks comes from Scryfall, and an unknown one falls back`() {
        val sol = printings.forCard("Sol Ring").first()
        val set = sol.printing.setCode
        val number = sol.printing.collectorNumber
        val forgeHasIt = PrintingArt(FakeForge, { printings }, cache, forgeHas = { _, _, _ -> true })
        assertEquals("forge:Sol Ring:$set", forgeHasIt.keyFor("Sol Ring", set, number))
        val forgeLacksIt = PrintingArt(FakeForge, { printings }, cache, forgeHas = { _, _, _ -> false })
        assertEquals("printing:$set/$number/Sol Ring", forgeLacksIt.keyFor("Sol Ring", set, number))
        assertEquals("forge:Sol Ring:zzz", forgeLacksIt.keyFor("Sol Ring", "zzz", "1"), "Scryfall has no such printing: Forge's default")
        assertEquals("forge:Sol Ring:", forgeLacksIt.keyFor("Sol Ring", null, null), "no printing chosen: Forge's default")
    }

    @Test
    fun `a Scryfall printing's art is fetched once, by kind, and credited`() {
        val sol = printings.forCard("Sol Ring").first { !it.artist.isNullOrBlank() }
        val fetched = mutableListOf<String>()
        val art = PrintingArt(FakeForge, { printings }, cache, forgeHas = { _, _, _ -> false }) { url, target ->
            synchronized(fetched) { fetched += url }
            target.parentFile.mkdirs(); target.writeText("jpg"); true
        }
        val key = art.keyFor("Sol Ring", sol.printing.setCode, sol.printing.collectorNumber)!!
        assertNull(art.file(key, ArtKind.ART_CROP), "nothing on disk yet")
        assertEquals(sol.artist, art.artist(key))

        for (kind in ArtKind.entries) {
            val done = CountDownLatch(1)
            art.request(key, kind) { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS), "$kind arrives")
        }
        assertNotNull(art.file(key, ArtKind.ART_CROP))
        assertNotNull(art.file(key, ArtKind.FULL))
        val id = sol.scryfallId
        assertEquals(setOf("https://cards.scryfall.io/art_crop/front/${id[0]}/${id[1]}/$id.jpg", "https://cards.scryfall.io/large/front/${id[0]}/${id[1]}/$id.jpg"), fetched.toSet())

        var again = false
        art.request(key, ArtKind.ART_CROP) { again = true }
        assertTrue(again, "cached: ready at once")
        assertEquals(2, fetched.size, "and not fetched again")
    }
}
