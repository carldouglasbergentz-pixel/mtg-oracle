package mtgoracle.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import mtgoracle.ui.theme.Palette
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Which part of a playmat shows when it is wider than its half is tall (a half is long and low; a mat is not). */
enum class MatAnchor(val label: String) { TOP("top"), MIDDLE("middle"), BOTTOM("bottom") }

/** A picture behind one half of the table: [dim] (0 to 0.9) is how much of the background lies over it, so the cards read. */
data class Playmat(val file: File, val dim: Float, val anchor: MatAnchor = MatAnchor.MIDDLE)

/** Decoded once per file and its time: a mat is drawn on every frame of a game. */
private object MatImages {
    private val cache = ConcurrentHashMap<Pair<String, Long>, ImageBitmap>()

    fun of(file: File): ImageBitmap? {
        if (!file.isFile) return null
        val key = file.absolutePath to file.lastModified()
        cache[key]?.let { return it }
        val image = runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull() ?: return null
        cache.keys.removeIf { it.first == key.first }
        cache[key] = image
        return image
    }
}

/**
 * [mat] filling the space it is given, cropped rather than stretched: scaled
 * until it covers, the part [Playmat.anchor] names kept, then the
 * background laid over it at [Playmat.dim]. A file that can't be read draws
 * nothing (the plain table).
 */
@Composable
fun PlaymatLayer(mat: Playmat, modifier: Modifier = Modifier.fillMaxSize()) {
    val image = remember(mat.file, mat.file.lastModified()) { MatImages.of(mat.file) } ?: return
    val shade = Palette.background.copy(alpha = mat.dim.coerceIn(0f, 0.9f))
    // Clipped to its own space: cropping is the point, and a mat scaled to cover drew past it where nothing else clipped.
    Canvas(modifier.clipToBounds()) {
        val scale = max(size.width / image.width, size.height / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val x = (size.width - w) / 2
        val y = when (mat.anchor) {
            MatAnchor.TOP -> 0f
            MatAnchor.MIDDLE -> (size.height - h) / 2
            MatAnchor.BOTTOM -> size.height - h
        }
        drawImage(image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
        drawRect(shade)
    }
}
