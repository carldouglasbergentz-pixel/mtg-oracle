package mtgoracle.core.art

import java.io.File

/**
 * Card images: the forge module implements this with Forge's image keys,
 * fetcher and cache; the UI only sees keys and files.
 *
 * Two kinds, because of Scryfall's terms: the [FULL] card image is shown whole
 * (never cropped, distorted or recoloured — the copyright and artist lines
 * stay), and the art inside our frames is Scryfall's own [ART_CROP], always
 * shown with its artist credit.
 */
enum class ArtKind { FULL, ART_CROP }

interface CardArt {
    /** Key for a deck row: its printing when given, otherwise Forge's default art. Null if Forge lacks the card. */
    fun keyFor(cardName: String, setCode: String?, collectorNumber: String?): String?

    /** The cached image file, or null when it isn't on disk (yet). */
    fun file(key: String, kind: ArtKind): File?

    /** The artist, for the credit beside an art crop. */
    fun artist(key: String): String?

    /** Fetch in the background if missing; [onReady] runs (on any thread) once the file exists. */
    fun request(key: String, kind: ArtKind, onReady: () -> Unit)
}

/** For tests and the text-only mode: no art at all. */
object NoArt : CardArt {
    override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?): String? = null
    override fun file(key: String, kind: ArtKind): File? = null
    override fun artist(key: String): String? = null
    override fun request(key: String, kind: ArtKind, onReady: () -> Unit) {}
}
