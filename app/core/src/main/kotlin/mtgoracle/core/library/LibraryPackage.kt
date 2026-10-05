package mtgoracle.core.library

/**
 * A `.mtgoracle` package: decks with all they carry, the games played with
 * them and the user's own combos, to move a library (or part of one) from one
 * app to another. An export always holds everything; an import chooses what
 * of the history, games and combos it takes (decks always come).
 *
 * Decks are known inside the package by [PackagedDeck.key], `folder/name`
 * (`/name` outside any folder), which games use instead of database ids.
 */
data class LibraryPackage(
    val manifest: PackageManifest,
    val decks: List<PackagedDeck>,
    val games: List<PackagedGame>,
    val combos: List<PackagedCombo>,
)

data class PackageManifest(
    /** The package format's version: a reader refuses a newer one. */
    val version: Int,
    /** The app that wrote it: `0.1.1+b719b21`. */
    val app: String,
    /** Its database's schema version, for the record. */
    val schema: Int,
    /** When it was written (ISO instant). */
    val exportedAt: String,
    /** What was exported: `the library`, `folder Duel Commander`, `deck Jori En`. */
    val scope: String,
) {
    companion object {
        /** The format this build writes and the newest it reads. */
        const val VERSION = 1
        const val FILE_SUFFIX = ".mtgoracle"
    }
}

data class PackagedDeck(
    val folder: String?,
    /** The folder's default format, so a folder made by the import gets it too. */
    val folderFormat: String?,
    val name: String,
    val format: String?,
    val description: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val cards: List<PackagedCard>,
    val considering: List<PackagedConsidering>,
    val substitutions: List<PackagedSubstitution>,
    /** Oldest first: replayed, it ends at [cards]. */
    val history: List<PackagedRevision>,
) {
    val key: String get() = "${folder.orEmpty()}/$name"
    /** The deck's contents as a comparable whole: what tells "the same deck" from "another deck of that name". */
    val content: List<Any?> get() = cards.map { listOf(it.name.lowercase(), it.quantity, it.commander, it.sideboard, it.setCode, it.collectorNumber) }
        .sortedBy { it.toString() } + listOf(format)
}

data class PackagedCard(
    val name: String,
    val quantity: Int,
    val category: String?,
    val commander: Boolean,
    val sideboard: Boolean,
    val addedAt: String?,
    val setCode: String?,
    val collectorNumber: String?,
)

data class PackagedConsidering(val name: String, val quantity: Int, val addedAt: String?)

data class PackagedSubstitution(val card: String, val substitute: String, val addedAt: String?)

data class PackagedRevision(val at: String, val action: String, val note: String?, val changes: List<PackagedChange>)

data class PackagedChange(
    val card: String,
    val section: String,
    val before: Int,
    val after: Int,
    val setBefore: String?,
    val numberBefore: String?,
    val setAfter: String?,
    val numberAfter: String?,
)

/** A `games` row, its decks by their package [PackagedDeck.key] (null when the deck is not in the package). */
data class PackagedGame(
    val playedAt: String,
    val mode: String,
    val deck: String?,
    val deckName: String,
    val opponentDeck: String?,
    val opponentName: String,
    val opponentAiVariant: Int?,
    val seed: Long?,
    val winner: String?,
    val turns: Int?,
    val durationMs: Long?,
    val forgeVersion: String?,
    val matchId: String?,
    val gameNo: Int?,
    val matchFormat: String?,
    val conceded: Int?,
    val deckAiVariant: Int?,
) {
    /** What makes it the same game in another library: imported twice, it is there once. */
    val identity: List<Any?> get() = listOf(playedAt, deckName, opponentName, seed, gameNo, matchId)
}

data class PackagedCombo(val name: String?, val colorIdentity: String?, val description: String, val addedAt: String?, val cards: List<Pair<String, Int>>) {
    /** The same combo: the same cards and the same text. */
    val identity: List<Any?> get() = listOf(cards.map { it.first.lowercase() }.sorted(), description.trim())
}

/** What an import takes besides the decks. */
data class ImportChoice(val history: Boolean = true, val games: Boolean = true, val combos: Boolean = true)

/** What an import would do, before it does it: shown in the dialog, and the plan the write follows. */
data class ImportPlan(
    /** Each deck and what becomes of it. */
    val decks: List<DeckPlan>,
    /** Games and combos not already in this library. */
    val newGames: Int,
    val knownGames: Int,
    val newCombos: Int,
    val knownCombos: Int,
    /** Card names this database doesn't know (yet): a sync may bring them. */
    val missingCards: List<String>,
    val revisions: Int,
) {
    val decksToImport: List<DeckPlan> get() = decks.filter { it.action != DeckAction.SAME }
}

enum class DeckAction { NEW, SAME, RENAMED }

/** [deck] under the name it gets: its own, or `Name (2)` beside a different deck of its name. */
data class DeckPlan(val deck: PackagedDeck, val action: DeckAction, val name: String)

/** What an import did. */
data class ImportResult(val decks: List<String>, val skippedDecks: Int, val games: Int, val combos: Int, val revisions: Int, val backup: String?)

/** What an export takes: the whole library, one folder's decks, or one deck. */
sealed interface PackageScope {
    data object Library : PackageScope
    data class Folder(val id: Int) : PackageScope
    data class Deck(val id: Int) : PackageScope
}
