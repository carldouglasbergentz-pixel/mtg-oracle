package mtgoracle.ui.lookup

import mtgoracle.core.lookup.CardProfile
import mtgoracle.core.lookup.Ruling

private const val INDENT = "  "
/** Wide enough that 'not as cmdr' still leaves a gap before the body. */
private const val LABEL_W = 13

/**
 * `card <name>`: the card, what it is tagged and parsed as, where it is
 * legal, its rulings, its smallest combos and any correction about it.
 * (renderer.render_card; the combo rows and their cards are links here.)
 */
fun renderCard(card: CardProfile): Rendering = Rendering { width ->
    Lines(width).apply {
        rule()
        val cost = card.manaCost.orEmpty().trim()
        // The cost beside the name, as the zoom pane and the deck have it: at the far edge of a wide pane it read as another column.
        if (cost.isNotEmpty() && card.name.length + 2 + cost.length <= width) add("${card.name}  $cost", Tone.BOLD)
        else {
            add(card.name, Tone.BOLD)
            if (cost.isNotEmpty()) add(cost, Tone.BOLD)
        }
        card.typeLine?.let { add(it) }
        rule()
        card.oracleText?.takeIf { it.isNotBlank() }?.let { wrap(it) }

        if (card.tags.isNotEmpty()) {
            section("Tags:")
            for (category in listOf("supertype", "type", "subtype", "keyword")) {
                card.tags[category]?.let { wrap(it.joinToString(", "), INDENT + category.padEnd(10) + " ", INDENT + " ".repeat(11)) }
            }
        }

        if (card.abilities.isNotEmpty()) {
            section("Parsed abilities:")
            for (a in card.abilities) {
                val flags = listOfNotNull("target".takeIf { a.hasTarget }, "produces mana".takeIf { a.producesMana }, "IS MANA ABILITY".takeIf { a.isManaAbility })
                add("$INDENT- ${a.type}" + if (flags.isEmpty()) "" else " [${flags.joinToString(", ")}]")
                a.cost?.takeIf { it.isNotBlank() }?.let { wrap(it, "$INDENT  cost:   ", "$INDENT          ") }
                a.effect?.takeIf { it.isNotBlank() }?.let { wrap(it, "$INDENT  effect: ", "$INDENT          ") }
            }
        }

        if (card.legalities.isNotEmpty()) {
            section("Legality:")
            // A format with no row is not legal; these are the facts that change a decision.
            for ((status, label) in listOf("legal" to "legal", "restricted" to "1 copy only", "no_commander" to "not as cmdr", "banned" to "banned")) {
                card.legalities[status]?.let { wrap(it.joinToString(", "), INDENT + label.padEnd(LABEL_W), INDENT + " ".repeat(LABEL_W)) }
            }
        }
        // Where and how much it is played: not legality, so a section of its own, each fact under its own name.
        val played = listOfNotNull(
            card.games?.takeIf { it.isNotBlank() }?.let { "games" to it.replace(",", "/") },
            card.edhrecRank?.let { "EDHREC rank" to String.format(java.util.Locale.ROOT, "#%,d", it) }, // ROOT: sv groups with a no-break space
            ("reserved" to "on the Reserved List: never reprinted").takeIf { card.reserved },
        )
        if (played.isNotEmpty()) {
            section("Played:")
            for ((label, value) in played) wrap(value, INDENT + label.padEnd(LABEL_W), INDENT + " ".repeat(LABEL_W))
        }

        if (card.rulings.isNotEmpty()) {
            section("Rulings (${card.rulings.size}):")
            rulings(card.rulings)
        }

        val filter = card.combosFilteredByCi
        if (card.combos.isNotEmpty()) {
            section("Top combos featuring this card (${card.combos.size}" + (filter?.let { ", filtered to deck CI $it" } ?: "") + "):")
            // Padded to the longest id here, not a fixed 14: four-card ids are longer and broke the column.
            val idW = card.combos.maxOf { it.id.length } + 2
            card.combos.forEach { comboRow(it, "[${it.id}]", idW) }
        } else if (filter != null) {
            // Nothing passed the filter: say so rather than show nothing.
            section("Top combos featuring this card: 0 applicable to deck CI $filter (card may be unplayable here)")
        }

        if (card.corrections.isNotEmpty()) {
            section("NOTE - ${card.corrections.size} correction(s) apply to this card:")
            for (c in card.corrections) {
                add("$INDENT#${c.id} [${c.source.orEmpty()}] ${c.topic}")
                wrap(c.correctClaim, "$INDENT$INDENT-> ", "$INDENT$INDENT   ")
            }
        }
    }.out
}

/** `ruling <name>`. */
fun renderRulings(cardName: String, rulings: List<Ruling>): Rendering = Rendering { width ->
    Lines(width).apply {
        if (rulings.isEmpty()) add("(no rulings found for $cardName)", Tone.DIM)
        else {
            add("$cardName - ${rulings.size} ruling(s):", Tone.BOLD)
            rulings(rulings)
        }
    }.out
}

private fun Lines.rulings(rulings: List<Ruling>) {
    for (r in rulings) {
        add("$INDENT[${r.date}]", Tone.DIM)
        wrap(r.text, INDENT + INDENT)
    }
}
