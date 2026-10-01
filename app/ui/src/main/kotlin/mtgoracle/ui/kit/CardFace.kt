package mtgoracle.ui.kit

import mtgoracle.core.deck.DeckCard
import mtgoracle.core.model.CardState

/** A card as the kit draws it, from the board or from a deck list. */
data class CardFace(
    val name: String,
    val manaCost: String,
    val typeLine: String,
    /** "2/2 sick ATK", "L4", "" */
    val stats: String,
    val text: String,
    val imageKey: String?,
    val tapped: Boolean = false,
    val quantity: Int = 1,
    /** A card back: this seat may not see the card. */
    val hidden: Boolean = false,
    /** A commander that may not be cast from the command zone this game (CardState.castLocked). */
    val castLocked: Boolean = false,
)

/** A mana cost for display: compact (`{1}{R}`), and empty for a card with none (Forge says "no cost"). */
fun displayCost(raw: String?): String {
    val compact = raw.orEmpty().substringBefore(" // ").replace(" ", "")
    return if (compact.equals("nocost", ignoreCase = true)) "" else compact
}

/**
 * What a card this seat may not see shows, everywhere (frame, chip, zoom):
 * nothing but that it is there. "Face-down" only for one that is (a
 * hideaway card): a card in the opponent's hand is hidden, not face down,
 * and calling every back face-down had the user looking for one.
 */
val HIDDEN_FACE = CardFace("hidden card", "", "", "", "", null, hidden = true)
val FACE_DOWN_FACE = CardFace("face-down card", "", "", "", "", null, hidden = true)

fun CardState.face(): CardFace = if (hidden) (if (faceDown) FACE_DOWN_FACE else HIDDEN_FACE) else CardFace(
    name = name,
    manaCost = displayCost(manaCost),
    typeLine = typeLine,
    stats = buildList {
        if (power != null) add("$power/$toughness")
        loyalty?.let { add("L$it") }
        if (damage > 0) add("dmg $damage")
        if (tapped) add("TAPPED")
        if (summoningSick && isCreature) add("sick")
        if (attacking) add("ATK")
        if (blocking) add("BLK")
        if (isToken) add("token")
        if (castLocked) add("LOCKED")
        // Loyalty is already "L3"; the rest of the counters as "+1/+1×2".
        val shown = counters.split(", ").filter { it.isNotBlank() && !(loyalty != null && it.startsWith("Loyalty", ignoreCase = true)) }
        if (shown.isNotEmpty()) add(shown.joinToString(" ") { it.replace(" x", "×") })
    }.joinToString(" "),
    text = text,
    imageKey = imageKey,
    tapped = tapped,
    castLocked = castLocked,
)

/** A deck row's card, front face first: `cards` stores both faces joined with " // ". */
fun DeckCard.face(imageKey: String?): CardFace = CardFace(
    name = name,
    manaCost = displayCost(info?.manaCost),
    typeLine = info?.typeLine.orEmpty().substringBefore(" // "),
    stats = listOfNotNull(info?.let { i -> i.power?.let { "$it/${i.toughness}" } }, setCode?.let { "${it.uppercase()} ${collectorNumber.orEmpty()}".trim() }).joinToString("  "),
    text = info?.oracleText.orEmpty(),
    imageKey = imageKey,
    quantity = quantity,
)
