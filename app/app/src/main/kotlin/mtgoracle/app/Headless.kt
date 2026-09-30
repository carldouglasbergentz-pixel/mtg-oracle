package mtgoracle.app

import mtgoracle.data.Library
import mtgoracle.data.MtgDb
import mtgoracle.forge.ForgeRuntime

object Headless {
    /** Offline prefetch: card art (both kinds) for every deck, or the decks whose name contains [deckName]. */
    fun prefetch(paths: AppPaths, deckName: String?): Int {
        val library = Library(MtgDb(paths.db).also { it.checkSchema() })
        ForgeRuntime.initialise(paths.forge)
        val decks = library.decks().filter { deckName == null || it.name.contains(deckName, ignoreCase = true) }
        if (decks.isEmpty()) { System.err.println("no deck matches '$deckName'"); return 1 }
        val keys = decks.flatMap { ImagePrefetch.keysFor(library.deck(it.id)!!) }.distinct()
        println("prefetching ${keys.size} cards x 2 images for ${decks.joinToString { it.name }}")
        val have = ForgeRuntime.images.prefetch(keys) { done, total -> if (done % 25 == 0 || done == total) println("  $done / $total") }
        println("$have of ${keys.size * 2} images on disk in ${ForgeRuntime.setup.cacheDir}")
        return 0
    }
}
