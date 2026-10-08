package mtgoracle.app

import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `a key from across a network table can't write lines of its own, or name a file outside the app's folders`() {
        val indexFile = File(dir, "guarded.tsv")
        val index = ArtIndex(indexFile, roots = listOf(dir))
        // A host's board can carry any key: one with a carriage return would start a line of its own on reading.
        val forged = "c:Lightning Bolt|M10|1|x\rF\tc:Sol Ring|C18|1\tART_CROP\t\\\\attacker\\s\\a.jpg\r"
        index.rememberArtist(forged, "Christopher Moeller")
        index.rememberFile(forged, ArtKind.ART_CROP, crop)
        index.rememberKey("Sol Ring", "c18", "1", "c:Sol Ring|C18|1")
        index.flush()
        val text = indexFile.readText()
        assertTrue("attacker" !in text && '\r' !in text, "nothing of the forged key was written: $text")
        val next = ArtIndex(indexFile, roots = listOf(dir))
        assertNull(next.fileOf("c:Sol Ring|C18|1", ArtKind.ART_CROP))
        assertEquals("c:Sol Ring|C18|1", next.keyOf("Sol Ring", "c18", "1"), "an ordinary key still is")

        // A file the index points outside its roots is not served.
        val outside = Files.createTempDirectory("art-outside-").toFile().also { it.deleteOnExit() }
        val elsewhere = File(outside, "x.jpg").apply { writeText("jpeg") }
        val loose = ArtIndex(File(dir, "loose.tsv"), roots = listOf(dir))
        loose.rememberFile("c:Sol Ring|C18|1", ArtKind.ART_CROP, elsewhere)
        assertNull(loose.fileOf("c:Sol Ring|C18|1", ArtKind.ART_CROP))
        loose.rememberFile("c:Sol Ring|C18|1", ArtKind.ART_CROP, crop)
        assertEquals(crop, loose.fileOf("c:Sol Ring|C18|1", ArtKind.ART_CROP))
        outside.deleteRecursively()
    }

    @Test
    fun `an unreadable index is started afresh`() {
        val indexFile = File(dir, "broken.tsv").apply { writeBytes(byteArrayOf(0, 1, 2, 10, 75, 9)) }
        assertNull(IndexedArt(ArtIndex(indexFile)) { null }.keyFor("Sol Ring", null, null))
    }
}
