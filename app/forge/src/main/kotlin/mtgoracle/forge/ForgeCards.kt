package mtgoracle.forge

import forge.StaticData
import forge.card.CardEdition
import forge.deck.Deck
import forge.deck.DeckSection
import forge.item.PaperCard
import mtgoracle.core.deck.PlayCard
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.deck.Section
import mtgoracle.core.deck.forgeCardName

/** What building a Forge deck found: cards Forge lacks, and cards its AI won't play (`AI:RemoveDeck:All`). */
data class DeckCheck(val unknown: List<String>, val aiUnplayable: List<String>)

/**
 * Card names and printings, resolved against Forge's card database.
 *
 * A printing is a Scryfall set code plus collector number (`c18` / `222`,
 * from `deck_cards.set_code` / `collector_number`). The mapping is the one in
 * CLAUDE.md "Printings in Forge", which the Python export implements
 * (`forge_format.forge_printing`); here it runs over Forge's loaded editions
 * and PaperCards instead of re-reading res/editions:
 *
 * 1. Candidates: editions whose ScryfallCode (its Code when it has none)
 *    equals the set code, ignoring case — own Code first, then by date, then code.
 * 2. Among those that have the card, the first listing that collector number
 *    (ignoring case) gives that exact PaperCard.
 * 3. Else the first that has the card at all: Forge's default art there.
 * 4. Else Forge's default printing, as for a row with no printing.
 *
 * Forge makes PaperCards only from the collector-number sections of an
 * edition file, never from [tokens] or booster sheets, which is the Python
 * rule's card-match restriction.
 */
object ForgeCards {

    private val editionsByScryfallCode: Map<String, List<CardEdition>> by lazy {
        StaticData.instance().editions
            .groupBy { edition -> scryfallCode(edition) }
            .mapValues { (code, group) ->
                group.sortedWith(compareBy<CardEdition>({ !it.code.equals(code, ignoreCase = true) }, { it.date }, { it.code }))
            }
    }

    // getScryfallCode() lowercases a field that is null for editions without one.
    private fun scryfallCode(edition: CardEdition): String =
        (runCatching { edition.scryfallCode }.getOrNull()?.takeIf { it.isNotBlank() } ?: edition.code).lowercase()

    /** Forge editions for a Scryfall set code, in the order the printing rule tries them. */
    fun editions(scryfallCode: String): List<CardEdition> = editionsByScryfallCode[scryfallCode.lowercase()].orEmpty()

    /** The printing when Forge has it, else the card in Forge's default art; null when Forge lacks the card. */
    fun paperCard(name: String, setCode: String?, collectorNumber: String?): PaperCard? {
        val db = StaticData.instance().commonCards
        val forgeName = forgeCardName(name)
        val printings = db.getAllCards(forgeName).ifEmpty { db.getAllCards(name) }
        if (printings.isEmpty()) return null
        if (setCode != null) {
            val holding = editions(setCode).mapNotNull { edition ->
                printings.filter { it.edition.equals(edition.code, ignoreCase = true) }.takeIf { it.isNotEmpty() }
            }
            if (collectorNumber != null) {
                holding.firstNotNullOfOrNull { cards -> cards.firstOrNull { it.collectorNumber.equals(collectorNumber, ignoreCase = true) } }
                    ?.let { return it }
            }
            holding.firstOrNull()?.let { cards -> return db.getCard(forgeName, cards.first().edition) ?: cards.minBy { it.artIndex } }
        }
        return db.getCard(forgeName) ?: printings.first()
    }

    fun check(play: PlayDeck): DeckCheck {
        val unknown = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        val unplayable = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        for (card in play.cards) {
            val pc = paperCard(card.forgeName, card.setCode, card.collectorNumber)
            if (pc == null) unknown += card.forgeName
            else if (pc.rules.aiHints.remAIDecks) unplayable += card.forgeName
        }
        return DeckCheck(unknown.toList(), unplayable.toList())
    }

    /** The Forge deck for [play]; cards Forge lacks are left out (the caller has shown [check] first). */
    fun toForgeDeck(play: PlayDeck): Deck {
        val deck = Deck(play.name)
        for (card in play.cards) {
            val pc = paperCard(card.forgeName, card.setCode, card.collectorNumber) ?: continue
            deck.getOrCreate(section(card)).add(pc, card.quantity)
        }
        return deck
    }

    private fun section(card: PlayCard) = when (card.section) {
        Section.COMMANDER -> DeckSection.Commander
        Section.MAIN -> DeckSection.Main
        Section.SIDEBOARD -> DeckSection.Sideboard
    }
}
