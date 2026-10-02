package mtgoracle.app

import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Before Forge is up, the window draws what an earlier run's answers say is
 * on disk; once it is up, Forge answers and the index learns.
 */
class ArtIndexTest {
    private val dir: File = Files.createTempDirectory("art-index-").toFile()
    private val crop = File(dir, "Sol Ring.artcrop.jpg").apply { writeText("jpeg") }

    /** Forge as far as these tests need it: one card, one cached art crop. */
    private object Forge : CardArt {
        lateinit var crop: File
        override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?) = if (cardName == "Sol Ring") "c:Sol Ring|C18|1" else null
        override fun file(key: String, kind: ArtKind) = crop.takeIf { kind == ArtKind.ART_CROP && key == "c:Sol Ring|C18|1" }
        override fun artist(key: String) = "Mike Bierek"
        override fun request(key: String, kind: ArtKind, onReady: () -> Unit) {}
    }

    @Test
    fun `what Forge answered once is drawn before it is up next time`() {
        Forge.crop = crop
        val indexFile = File(dir, "art-index.tsv")
        val firstRun = ArtIndex(indexFile)
        var up: CardArt? = null
        val art = IndexedArt(firstRun) { up }
        assertNull(art.keyFor("Sol Ring", "c18", "222"), "nothing known yet, and Forge isn't up")

        up = Forge
        val key = art.keyFor("Sol Ring", "c18", "222")!!
        assertEquals(crop, art.file(key, ArtKind.ART_CROP))
        assertEquals("Mike Bierek", art.artist(key))
        assertNull(art.keyFor("Not A Card", null, null), "a card Forge lacks is not remembered")
        firstRun.flush()

        val nextRun = IndexedArt(ArtIndex(indexFile)) { null } // the window has opened, Forge is still starting
        assertEquals(key, nextRun.keyFor("Sol Ring", "c18", "222"))
        assertEquals(crop, nextRun.file(key, ArtKind.ART_CROP))
        assertEquals("Mike Bierek", nextRun.artist(key))
        assertNull(nextRun.keyFor("Sol Ring", "cmd", "261"), "another printing is another row")
        assertNull(nextRun.file(key, ArtKind.FULL), "a kind never seen is not guessed")

        crop.delete()
        assertNull(nextRun.file(key, ArtKind.ART_CROP), "a file gone from the cache is not offered")
    }

    @Test
    fun `an unreadable index is started afresh`() {
        val indexFile = File(dir, "broken.tsv").apply { writeBytes(byteArrayOf(0, 1, 2, 10, 75, 9)) }
        assertNull(IndexedArt(ArtIndex(indexFile)) { null }.keyFor("Sol Ring", null, null))
    }
}
