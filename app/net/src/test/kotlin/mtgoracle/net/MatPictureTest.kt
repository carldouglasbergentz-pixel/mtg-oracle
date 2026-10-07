package mtgoracle.net

import mtgoracle.core.play.MatchFormat
import mtgoracle.core.play.Winner
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A playmat crosses as pixels, and a peer's pixels are checked, never trusted. */
class MatPictureTest {
    private fun deflate(bytes: ByteArray): String {
        val d = Deflater().apply { setInput(bytes); finish() }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
        d.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun `pixels come out as they went in, place, zoom and dim with them`() {
        val rgb = IntArray(320 * 180) { i -> (i * 2654435761L).toInt() and 0xFFFFFF }
        val sent = MatPicture.of(rgb, 320, 180, dim = 0.3f, x = 0.2f, y = 0.9f, zoom = 1.5f)
        val received = Wire.host(Wire.encode(HostMessage.Mat(sent))) as HostMessage.Mat
        assertEquals(sent, received.mat)
        assertContentEquals(rgb, received.mat!!.rgb())
        assertEquals(Triple(0.2f, 0.9f, 1.5f), Triple(received.mat!!.x, received.mat!!.y, received.mat!!.zoom))
        // A plain picture compresses: the largest one stays far under a line's limit.
        val big = MatPicture.of(IntArray(MatPicture.MAX_WIDTH * MatPicture.MAX_HEIGHT) { 0x336699 }, MatPicture.MAX_WIDTH, MatPicture.MAX_HEIGHT, 0f, 0.5f, 0.5f, 1f)
        assertTrue(Wire.encode(GuestMessage.Mat(big)).length < TcpLink.MAX_LINE / 4)
        assertFailsWith<IllegalArgumentException>("too big to send unscaled") { MatPicture.of(IntArray(1000 * 600), 1000, 600, 0f, 0f, 0f, 1f) }
    }

    @Test
    fun `pixels that aren't what the picture claims are no picture`() {
        val six = ByteArray(2 * 1 * 3) { 7 }
        assertEquals(2, MatPicture(2, 1, deflate(six), 0f, 0f, 0f, 1f).rgb()?.size)
        listOf(
            "a size past the limit" to MatPicture(MatPicture.MAX_WIDTH + 1, 1, deflate(ByteArray((MatPicture.MAX_WIDTH + 1) * 3)), 0f, 0f, 0f, 1f),
            "no size" to MatPicture(0, 1, deflate(ByteArray(0)), 0f, 0f, 0f, 1f),
            "too few pixels" to MatPicture(2, 1, deflate(ByteArray(5)), 0f, 0f, 0f, 1f),
            "more than it says" to MatPicture(2, 1, deflate(ByteArray(7)), 0f, 0f, 0f, 1f),
            // A bomb: a few hundred bytes that inflate to 100 MB, claiming to be 2 x 2. Never inflated past 12 bytes.
            "a bomb" to MatPicture(2, 2, deflate(ByteArray(100 * 1024 * 1024)), 0f, 0f, 0f, 1f),
            "not base64" to MatPicture(2, 1, "%%%", 0f, 0f, 0f, 1f),
            "not deflated" to MatPicture(2, 1, Base64.getEncoder().encodeToString(six), 0f, 0f, 0f, 1f),
        ).forEach { (name, picture) -> assertNull(picture.rgb(), name) }
    }

    @Test
    fun `the match and its results travel too`() {
        listOf(
            HostMessage.Match(MatchFormat.BO3, "Jori En"),
            HostMessage.Result(GameOutcome(Winner.OPPONENT, gameNo = 1, wins = 0, losses = 1, matchOver = false, summary = "Host won", turns = 9)),
            HostMessage.Result(null),
            HostMessage.Mat(null),
        ).forEach { assertEquals(it, Wire.host(Wire.encode(it))) }
        assertEquals(GuestMessage.Mat(null), Wire.guest(Wire.encode(GuestMessage.Mat(null))))
    }
}
