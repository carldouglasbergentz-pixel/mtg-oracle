package mtgoracle.app

import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import java.io.File

/**
 * The art the window shows: [live] once Forge is up (it is null before),
 * recording each answer in [index]; before that, only what the index knows,
 * for files still on disk. Nothing is fetched until Forge is up.
 */
class IndexedArt(private val index: ArtIndex, private val live: () -> CardArt?) : CardArt {
    override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?): String? {
        val art = live() ?: return index.keyOf(cardName, setCode, collectorNumber)
        return art.keyFor(cardName, setCode, collectorNumber)?.also { index.rememberKey(cardName, setCode, collectorNumber, it) }
    }

    override fun file(key: String, kind: ArtKind): File? {
        val art = live() ?: return index.fileOf(key, kind)?.takeIf { it.isFile }
        return art.file(key, kind)?.also { index.rememberFile(key, kind, it) }
    }

    override fun artist(key: String): String? {
        val art = live() ?: return index.artistOf(key)
        return art.artist(key)?.also { index.rememberArtist(key, it) }
    }

    override fun request(key: String, kind: ArtKind, onReady: () -> Unit) {
        live()?.request(key, kind, onReady)
    }
}
