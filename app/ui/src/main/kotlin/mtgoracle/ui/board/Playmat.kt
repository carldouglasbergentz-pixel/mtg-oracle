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

/**
 * A picture behind one half of the table. It fills the half and is cropped,
 * never stretched: [x] and [y] (0 to 1) say where the visible part sits in
 * what the crop leaves over (0: the left or top edge, 1: the right or
 * bottom), [zoom] (1 to 3) enlarges it past filling, to frame a part of the
 * picture. [dim] (0 to 0.9) is how much of the background lies over it, so
 * the cards read.
 */
data class Playmat(val file: File, val dim: Float, val x: Float = 0.5f, val y: Float = 0.5f, val zoom: Float = 1f) {
    companion object {
        const val MAX_ZOOM = 3f
    }
}

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

    /** The picture's size in pixels, for a drag to turn into a move of the crop; null when it can't be read. */
    fun size(file: File): IntSize? = of(file)?.let { IntSize(it.width, it.height) }
}

/** The picture's size, for the lobby's drag: how far a move of the mouse moves the crop. */
fun playmatSize(mat: Playmat): IntSize? = MatImages.size(mat.file)

/**
 * The picture's drawn size in a space of [w] × [h]: scaled until it covers,
 * then by the mat's zoom. What is left over each way is where [Playmat.x] and
 * [Playmat.y] move it.
 */
fun coverSize(image: IntSize, w: Float, h: Float, zoom: Float): Pair<Float, Float> {
    val scale = max(w / image.width, h / image.height) * zoom.coerceIn(1f, Playmat.MAX_ZOOM)
    return image.width * scale to image.height * scale
}

/**
 * [mat] filling the space it is given, cropped rather than stretched, at its
 * place and zoom, the background laid over it at [Playmat.dim]. A file that
 * can't be read draws nothing (the plain table).
 */
@Composable
fun PlaymatLayer(mat: Playmat, modifier: Modifier = Modifier.fillMaxSize()) {
    val image = remember(mat.file, mat.file.lastModified()) { MatImages.of(mat.file) } ?: return
    val shade = Palette.background.copy(alpha = mat.dim.coerceIn(0f, 0.9f))
    // Clipped to its own space: cropping is the point, and a mat scaled to cover drew past it where nothing else clipped.
    Canvas(modifier.clipToBounds()) {
        val (w, h) = coverSize(IntSize(image.width, image.height), size.width, size.height, mat.zoom)
        val x = (size.width - w) * mat.x.coerceIn(0f, 1f)
        val y = (size.height - h) * mat.y.coerceIn(0f, 1f)
        drawImage(image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
        drawRect(shade)
    }
}
