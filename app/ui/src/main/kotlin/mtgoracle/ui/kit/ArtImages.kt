package mtgoracle.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import org.jetbrains.skia.Image
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Decoded card images, and the fetches for ones not on disk yet.
 *
 * The bytes are drawn exactly as Scryfall serves them; the kit only scales
 * them to fit (never crops, stretches or tints). A missing image is fetched in
 * the background once, and everything showing art recomposes when it lands.
 * Decoding is off the UI thread too: a JPEG takes about 7 ms, and a deck's
 * hundred of them held the window still for most of a second.
 */
class ArtImages(val art: CardArt, private val capacity: Int = 400, private val decoder: Executor = DECODER) {
    private val decoded = object : LinkedHashMap<Pair<String, ArtKind>, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, ArtKind>, ImageBitmap>) = size > capacity
    }
    private val requested = ConcurrentHashMap.newKeySet<Pair<String, ArtKind>>()
    private val decoding = ConcurrentHashMap.newKeySet<Pair<String, ArtKind>>()
    internal val arrivals = mutableIntStateOf(0)

    /** The decoded image, or null while it is being fetched or decoded (everything showing art recomposes when it is ready). */
    fun load(key: String, kind: ArtKind): ImageBitmap? {
        val id = key to kind
        synchronized(decoded) { decoded[id]?.let { return it } }
        val file = art.file(key, kind)
        if (file == null) {
            if (requested.add(id)) art.request(key, kind) { arrivals.intValue++ }
            return null
        }
        if (decoding.add(id)) decoder.execute {
            try {
                val bitmap = runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
                if (bitmap != null) {
                    synchronized(decoded) { decoded[id] = bitmap }
                    arrivals.intValue++
                }
            } finally {
                decoding.remove(id)
            }
        }
        return synchronized(decoded) { decoded[id] } // a direct executor has it already
    }

    fun artist(key: String): String? = art.artist(key)

    companion object {
        /** Two threads: decoding is CPU-bound, and the game and Forge's own work need the rest. */
        private val DECODER: Executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "art-decode").apply { isDaemon = true } }
    }
}

val LocalArt = staticCompositionLocalOf<ArtImages?> { null }

/** The image for [key], or null while it isn't on disk (it is then being fetched). */
@Composable
fun rememberArt(key: String?, kind: ArtKind): ImageBitmap? {
    val images = LocalArt.current ?: return null
    if (key == null) return null
    val arrivals = images.arrivals.intValue // recompose when any fetch lands
    return remember(key, kind, arrivals) { images.load(key, kind) }
}
