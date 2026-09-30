package mtgoracle.core.analysis

/*
 * The columns analysis reads from `cards`, as plain data. The data layer
 * parses `card_faces` (JSON) into [CardFace]s, so everything here stays pure.
 */

/** One face of a multi-face card, as Scryfall's `card_faces` has it. Null fields are absent upstream. */
data class CardFace(
    val name: String? = null,
    val manaCost: String? = null,
    val typeLine: String? = null,
    val oracleText: String? = null,
    val power: String? = null,
    val toughness: String? = null,
)

data class CardFacts(
    val name: String,
    val manaCost: String? = null,
    val manaValue: Int = 0,
    val typeLine: String? = null,
    val oracleText: String? = null,
    /** CSV, `B,G`. */
    val colorIdentity: String? = null,
    val layout: String? = null,
    val faces: List<CardFace> = emptyList(),
    val power: String? = null,
    val toughness: String? = null,
)

/** Splits a combined name or type line at ` // `, however it is spaced. */
internal val FACE_SPLIT = Regex("\\s*//\\s*")

internal fun String.frontPart(): String = FACE_SPLIT.split(this, 2)[0]

/** The type line of the face you look at: what the card *is*. */
fun CardFacts.frontTypeLine(): String =
    if (faces.isNotEmpty()) faces[0].typeLine ?: "" else (typeLine ?: "").frontPart()

/** `Land` as a card type, not a substring: `Woodland Cemetery` has no `Land` word, `Sorcery // Land` has one per face. */
fun isLandWord(typeLine: String?): Boolean =
    (typeLine ?: "").replace("—", " ").split(WHITESPACE).any { it == "Land" }

internal val WHITESPACE = Regex("\\s+")

/** A land by the face you look at. */
fun CardFacts.isLand(): Boolean = isLandWord(frontTypeLine())

/** CR 702.127a: an aftermath half is cast only from the graveyard. */
internal fun CardFace.isAftermath(): Boolean = (oracleText ?: "").trimStart().lowercase().startsWith("aftermath")

/**
 * A spell whose back face is a land you may choose to play: modal DFCs only,
 * since a `transform` back is reached by transforming, never by playing it.
 */
fun CardFacts.hasLandBack(): Boolean {
    if (layout != "modal_dfc" || faces.size < 2 || isLand()) return false
    return faces.drop(1).any { isLandWord(it.typeLine) }
}
