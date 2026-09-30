package mtgoracle.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.Section
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** The order the deck view groups by, front face's type deciding. */
internal val TYPE_ORDER = listOf("Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment", "Land")

fun primaryType(card: DeckCard): String {
    val front = card.info?.typeLine?.split(" // ")?.first().orEmpty()
    return TYPE_ORDER.firstOrNull { front.contains(it) } ?: "Other"
}

/**
 * A deck grouped by type, the commander first and the sideboard last: art
 * frames, or one line per card in text mode. Shared by the library's preview
 * and the deck workspace.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeckView(deck: Deck, keyFor: (DeckCard) -> String?, mode: CardMode, cols: Int, onHover: (CardFace) -> Unit) {
    val gap = with(LocalDensity.current) { LocalCells.current.width.toDp() }
    for (section in Section.entries) {
        val cards = deck.cards.filter { it.section == section }
        if (cards.isEmpty()) continue
        val groups = cards.groupBy { if (section == Section.MAIN) primaryType(it) else section.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val order = (listOf("Commander", "Sideboard") + TYPE_ORDER + "Other")
        for ((group, groupCards) in groups.entries.sortedBy { order.indexOf(it.key) }) {
            GridText(fit("─ $group (${groupCards.sumOf { it.quantity }})", cols), color = Palette.dim, bold = true)
            if (mode == CardMode.TEXT) {
                groupCards.forEachIndexed { i, card ->
                    val face = card.face(keyFor(card))
                    val printing = card.setCode?.let { " (${it.uppercase()}) ${card.collectorNumber.orEmpty()}" }.orEmpty()
                    GridText(fit("%2d %-32s %-10s %s".format(card.quantity, card.name, face.manaCost, face.typeLine) + printing, cols),
                        Modifier.clickTarget(ClickTarget.Control("card:${section}:$group:$i"), {}) { onHover(face) })
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    groupCards.forEachIndexed { i, card ->
                        val face = card.face(keyFor(card))
                        CardFrame(face, mode, target = ClickTarget.Control("card:${section}:$group:$i"), onHover = { onHover(face) })
                    }
                }
            }
        }
    }
}
