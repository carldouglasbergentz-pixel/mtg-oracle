package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.forge.Log
import mtgoracle.net.MatPicture
import mtgoracle.ui.board.MatPixels
import mtgoracle.ui.board.Playmat
import mtgoracle.ui.library.LobbyMats
import mtgoracle.ui.library.MatAction
import mtgoracle.ui.library.MatSide
import java.io.File
import javax.imageio.ImageIO

/**
 * The playmats: pictures in [dir] (data\playmats\), each with its own dim and
 * the part of it that shows, and the one on each side of the table, chosen in
 * the lobby. A new one comes from the clipboard (a picture, its file, or its
 * path) and is copied into [dir]; one put there by hand is offered as well.
 */
class Playmats(private val dir: File, private val settings: Settings, private val readClipboard: () -> String?, private val clipboardImage: () -> java.awt.Image?) {
    /** Each side's mat by its file name; null for the plain table. */
    var mine by mutableStateOf(settings.matMine?.takeIf { File(dir, it).isFile })
        private set
    var theirs by mutableStateOf(settings.matTheirs?.takeIf { File(dir, it).isFile })
        private set
    /** Bumped when a mat's dim or part changes, so what reads them is read again. */
    private var version by mutableStateOf(0)

    /** The pictures there are, by name. */
    fun names(): List<String> = dir.listFiles { f -> f.isFile && f.extension.lowercase() in PICTURES }.orEmpty().map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** [name] as the table draws it, or null for none (or a file since gone). */
    fun mat(name: String?): Playmat? {
        version // read: a change to a mat's settings is seen
        val file = name?.let { File(dir, it) }?.takeIf { it.isFile } ?: return null
        val (x, y, zoom) = settings.matFrame(name)
        return Playmat(file, settings.matDim(name) / 10f, x, y, zoom)
    }

    /**
     * Your mat as it crosses a network table: your own picture, decoded and scaled here to fit
     * [MatPicture.MAX_WIDTH] × [MatPicture.MAX_HEIGHT], sent as pixels with its place, zoom and dim; null for none.
     */
    fun picture(name: String?): MatPicture? {
        val mat = mat(name) ?: return null
        val image = runCatching { ImageIO.read(mat.file) }.getOrNull() ?: return null
        val scale = minOf(1.0, MatPicture.MAX_WIDTH.toDouble() / image.width, MatPicture.MAX_HEIGHT.toDouble() / image.height)
        val w = (image.width * scale).toInt().coerceIn(1, MatPicture.MAX_WIDTH)
        val h = (image.height * scale).toInt().coerceIn(1, MatPicture.MAX_HEIGHT)
        val scaled = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
        return MatPicture.of(scaled.getRGB(0, 0, w, h, null, 0, w).map { it and 0xFFFFFF }.toIntArray(), w, h, mat.dim, mat.x, mat.y, mat.zoom)
    }

    fun lobby(): LobbyMats = LobbyMats(names(), MatSide(mine, mat(mine)), MatSide(theirs, mat(theirs)), "data\\playmats\\")

    /** What the lobby's controls ask; the answer, if any, for the status line. */
    fun act(action: MatAction): String? = when (action) {
        is MatAction.Cycle -> {
            // None, then each picture, round: < and > step through them.
            val choices = listOf<String?>(null) + names()
            val now = if (action.mine) mine else theirs
            val next = choices[Math.floorMod(choices.indexOf(now).coerceAtLeast(0) + action.by, choices.size)]
            if (action.mine) { mine = next; settings.matMine = next } else { theirs = next; settings.matTheirs = next }
            null
        }
        is MatAction.Dim -> side(action.mine)?.let { settings.setMatDim(it, action.tenths); version++; null }
        is MatAction.Frame -> side(action.mine)?.let { settings.setMatFrame(it, action.x, action.y, action.zoom); version++; null }
        MatAction.Add -> add()
    }

    private fun side(mine: Boolean) = if (mine) this.mine else theirs

    /**
     * A picture from the clipboard into [dir]: a file copied in Explorer or its path (copied as it is),
     * or a picture copied from a page or an editor (saved as PNG). It becomes your mat. Refused, and
     * said why, when it isn't a picture this app can draw.
     */
    fun add(): String = try {
        dir.mkdirs()
        val path = readClipboard()?.trim()?.removeSurrounding("\"")?.takeIf { '\n' !in it }?.let(::File)?.takeIf { it.isFile }
        val saved = when {
            path != null -> {
                if (path.extension.lowercase() !in PICTURES) throw IllegalArgumentException("${path.name} is no picture (png, jpg, gif, bmp, webp)")
                if (path.length() > MAX_BYTES) throw IllegalArgumentException("${path.name} is larger than ${MAX_BYTES / 1_000_000} MB")
                if (path.extension.lowercase() != "webp" && ImageIO.read(path) == null) throw IllegalArgumentException("${path.name} can't be read as a picture")
                free(path.nameWithoutExtension, path.extension.lowercase()).also { path.copyTo(it) }
            }
            else -> {
                val image = clipboardImage() ?: throw IllegalArgumentException("the clipboard holds no picture: copy one (or its file in Explorer) and Add again")
                val buffered = java.awt.image.BufferedImage(image.getWidth(null), image.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_RGB)
                buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                free("playmat", "png").also { ImageIO.write(buffered, "png", it) }
            }
        }
        mine = saved.name
        settings.matMine = saved.name
        "playmat ${saved.name} added, and on your side; < and > choose another"
    } catch (e: IllegalArgumentException) {
        "no playmat added: ${e.message}"
    } catch (e: Exception) {
        Log.warn("adding a playmat failed: $e")
        "no playmat added: ${e.message}"
    }

    /** A name in [dir] no file has: `Forest.png`, else `Forest-2.png`. */
    private fun free(base: String, ext: String): File =
        generateSequence(1) { it + 1 }.map { n -> File(dir, if (n == 1) "$base.$ext" else "$base-$n.$ext") }.first { !it.exists() }

    private companion object {
        val PICTURES = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        const val MAX_BYTES = 40_000_000L
    }
}

/**
 * The other side's mat as the board draws it: its pixels checked against what it claims (none at all
 * when they aren't), its place, zoom and dim kept within what a mat may have.
 */
fun MatPicture.toPlaymat(): Playmat? = rgb()?.let { pixels ->
    Playmat(null, dim.coerceIn(0f, 0.9f), x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), zoom.coerceIn(1f, Playmat.MAX_ZOOM), MatPixels(width, height, pixels))
}
