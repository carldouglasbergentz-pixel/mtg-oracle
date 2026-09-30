package mtgoracle.core.deck

/*
 * The rules a deck write is held to, and what the history records: a port
 * of mtg_oracle/decks.py's, checked against it by DeckParityTest. Plain
 * data and pure functions; the SQL is data/DeckWriter.kt.
 */

/**
 * A deck write that was refused. [kind] is what callers (and the parity
 * test) tell apart; [message] is what the user reads. A [forceable] refusal
 * is a deck rule the user may override ("add anyway"); the rest are facts.
 */
class DeckRefusal(val kind: Kind, message: String) : IllegalStateException(message) {
    enum class Kind {
        CARD_NOT_FOUND, QUANTITY, NO_SUCH_DECK,
        COLOR_IDENTITY, BANNED, NOT_IN_POOL, BANNED_AS_COMMANDER, RESTRICTED, SINGLETON, POINTS,
        COMMANDER_COPIES, ALREADY_COMMANDER, ALREADY_IN_MAIN,
        NOT_IN_DECK, NOT_A_COMMANDER, BAD_MOVE, NOTHING_TO_UNDO, UNDO_DRIFT,
        // The library's shape: names, folders, pasted lists.
        BAD_NAME, NAME_TAKEN, FOLDER_NOT_EMPTY, UNRESOLVED_CARDS,
    }

    val forceable: Boolean get() = kind in FORCEABLE

    private companion object {
        val FORCEABLE = setOf(
            Kind.COLOR_IDENTITY, Kind.BANNED, Kind.NOT_IN_POOL, Kind.BANNED_AS_COMMANDER, Kind.RESTRICTED,
            Kind.SINGLETON, Kind.POINTS, Kind.COMMANDER_COPIES, Kind.ALREADY_COMMANDER, Kind.ALREADY_IN_MAIN,
        )
    }
}

/** A deck's sections as the history names them, in the order it lists changes. */
enum class DeckSection(val key: String) {
    COMMANDER("commander"), MAIN("main"), SIDEBOARD("sideboard"),
    /** The maybeboard (deck_considering): weighed for the deck, not in it. */
    CONSIDERING("considering");

    companion object {
        fun of(key: String): DeckSection = entries.firstOrNull { it.key == key } ?: throw DeckRefusal(DeckRefusal.Kind.BAD_MOVE, "no section '$key'")
    }
}

/** A printing as Scryfall spells it: lower-case set code, the collector number as given. */
data class Printing(val setCode: String, val collectorNumber: String?) {
    companion object {
        /** decks._normalize_printing: no set code is no printing; a number alone means nothing. */
        fun of(setCode: String?, collectorNumber: String?): Printing? {
            val set = setCode.orEmpty().trim().lowercase()
            if (set.isEmpty()) return null
            return Printing(set, collectorNumber?.trim()?.ifEmpty { null })
        }
    }
}

/** One (card, section) change of a revision; the printings only where that side has copies. */
data class DeckChange(
    val card: String,
    val section: DeckSection,
    val before: Int,
    val after: Int,
    val printingBefore: Printing? = null,
    val printingAfter: Printing? = null,
)

/** One user action that changed a deck's contents: `add`, `remove`, `move`, `consider`, `promote`, `demote`, `undo`, `import`... */
data class DeckRevision(val id: Long, val at: String, val action: String, val note: String?, val changes: List<DeckChange>)

object DeckRules {
    const val MAX_QUANTITY = 999

    private const val UNLIMITED = "a deck can have any number of cards named"
    // Nazgûl ("up to nine"), Seven Dwarves ("up to seven"): always spelled out on the card.
    private val UP_TO = Regex("a deck can have up to (\\w+) cards named")
    private val NUMBER_WORDS = mapOf(
        "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    fun checkQuantity(quantity: Int) {
        if (quantity !in 1..MAX_QUANTITY) throw DeckRefusal(DeckRefusal.Kind.QUANTITY, "quantity must be between 1 and $MAX_QUANTITY")
    }

    fun isBasicLand(typeLine: String?): Boolean = typeLine != null && "Basic" in typeLine && "Land" in typeLine

    /**
     * Copies a singleton format allows; null is no limit (a basic land, a
     * card that says any number). An unknown number word is one: refusing a
     * legal copy is loud and forceable, allowing an illegal one is silent.
     */
    fun singletonLimit(typeLine: String?, oracleText: String?): Int? {
        if (isBasicLand(typeLine)) return null
        val text = oracleText.orEmpty().lowercase()
        if (UNLIMITED in text) return null
        UP_TO.find(text)?.let { return NUMBER_WORDS[it.groupValues[1]] ?: 1 }
        return 1
    }

    /** Where two changes sort in a revision: by section, then by name ignoring case. */
    val CHANGE_ORDER: Comparator<Pair<String, DeckSection>> = compareBy({ it.second.ordinal }, { it.first.lowercase() })
}

/** One printing of a card as the art chooser lists it: Scryfall's set code and number, and what the set is. */
data class CardPrinting(val setCode: String, val collectorNumber: String?, val setName: String, val date: String)
