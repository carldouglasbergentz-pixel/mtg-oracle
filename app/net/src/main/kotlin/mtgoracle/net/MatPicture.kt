package mtgoracle.net

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A player's playmat as it crosses the table: pixels, never a file. The
 * sender decodes and scales its own picture; the receiver builds an image
 * from these values alone, so no image decoder ever reads a byte a peer
 * chose. Three bytes a pixel (red, green, blue), deflated, at most
 * [MAX_WIDTH] × [MAX_HEIGHT]; with the mat's place, zoom and dim.
 */
@Serializable
data class MatPicture(
    val width: Int,
    val height: Int,
    /** The pixels, row by row, deflated and in base64. */
    val pixels: String,
    val dim: Float,
    val x: Float,
    val y: Float,
    val zoom: Float,
) {
    /**
     * The pixels as 0xRRGGBB values, or null when they aren't what the size
     * says: a peer's picture is checked, never trusted. Never inflates past
     * the size it claims, however the data was made.
     */
    fun rgb(): IntArray? {
        if (width !in 1..MAX_WIDTH || height !in 1..MAX_HEIGHT) return null
        val packed = runCatching { Base64.getDecoder().decode(pixels) }.getOrNull() ?: return null
        val expected = width * height * 3
        val bytes = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            var filled = 0
            while (filled < expected && !inflater.finished()) {
                val n = inflater.inflate(bytes, filled, expected - filled)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                filled += n
            }
            // Exactly the size it claims: short is broken, and anything left over is more than it said.
            if (filled != expected || !inflater.finished()) return null
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
        return IntArray(width * height) { i ->
            ((bytes[i * 3].toInt() and 0xFF) shl 16) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() and 0xFF)
        }
    }

    companion object {
        const val MAX_WIDTH = 960
        const val MAX_HEIGHT = 540

        /** [rgb] (0xRRGGBB per pixel, row by row) as a picture to send; the size must be within the limits. */
        fun of(rgb: IntArray, width: Int, height: Int, dim: Float, x: Float, y: Float, zoom: Float): MatPicture {
            require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT) { "a mat is at most ${MAX_WIDTH}x$MAX_HEIGHT to send: scale it first" }
            require(rgb.size == width * height) { "${rgb.size} pixels for ${width}x$height" }
            val raw = ByteArray(rgb.size * 3)
            rgb.forEachIndexed { i, p -> raw[i * 3] = (p shr 16).toByte(); raw[i * 3 + 1] = (p shr 8).toByte(); raw[i * 3 + 2] = p.toByte() }
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            val out = ByteArrayOutputStream()
            try {
                deflater.setInput(raw)
                deflater.finish()
                val chunk = ByteArray(64 * 1024)
                while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
            } finally {
                deflater.end()
            }
            return MatPicture(width, height, Base64.getEncoder().encodeToString(out.toByteArray()), dim, x, y, zoom)
        }
    }
}
