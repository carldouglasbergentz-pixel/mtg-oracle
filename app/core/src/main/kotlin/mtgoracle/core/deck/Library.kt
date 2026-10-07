package mtgoracle.core.deck

import kotlinx.serialization.Serializable
import mtgoracle.core.lookup.Formats

/*
 * The user's decks as the data layer reads them from data/mtg.db. Plain data;
 * the database, not this app, owns them (the Textual TUI edits them).
 */

data class Folder(val id: Int, val name: String, val format: String?)

data class DeckSummary(
    val id: Int,
    val name: String,
    val format: String?,
    /** Null for decks outside any folder ("unsorted"). */
    val folderId: Int?,
    val folderName: String?,
    val cardCount: Int,
)

/** What `cards` knows about a card, for the text-mode view. Null fields are missing upstream. */
data class CardInfo(
    val manaCost: String?,
    val typeLine: String?,
    val oracleText: String?,
    val power: String?,
    val toughness: String?,
)

data class DeckCard(
    val name: String,
    val quantity: Int,
    val isCommander: Boolean,
    val isSideboard: Boolean,
    /** Scryfall's lowercase set code (`c18`), when the user chose a printing. */
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val info: CardInfo? = null,
) {
    val section: Section get() = when {
        isCommander -> Section.COMMANDER
        isSideboard -> Section.SIDEBOARD
        else -> Section.MAIN
    }
}

enum class Section { COMMANDER, MAIN, SIDEBOARD }

@Serializable
data class Substitution(val cardName: String, val substitute: String)

data class Deck(
    val id: Int,
    val name: String,
    val format: String?,
    val folderName: String?,
    val cards: List<DeckCard>,
    val substitutions: List<Substitution> = emptyList(),
    /** The considering list (deck_considering): weighed for the deck, not in it — never counted, exported or played. */
    val considering: List<DeckCard> = emptyList(),
) {
    /**
     * A commander row makes a commander game: Forge can't run one without it, whatever `format` says.
     * The format then picks which: `duel` (or any alias of it) is Duel Commander, anything else Commander.
     */
    val gameType: GameType get() = when {
        cards.none { it.isCommander } -> GameType.CONSTRUCTED
        format != null && Formats.fold(format) == "duel" -> GameType.DUEL_COMMANDER
        else -> GameType.COMMANDER
    }
    val mainCount: Int get() = cards.filter { it.section == Section.MAIN }.sumOf { it.quantity }
}

enum class GameType(val label: String) { CONSTRUCTED("constructed"), COMMANDER("Commander"), DUEL_COMMANDER("Duel Commander") }
