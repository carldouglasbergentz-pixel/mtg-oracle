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
import mtgoracle.ui.kit.RuleLine
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
internal val TYPE_ORDER = mtgoracle.core.lookup.CardTypes.ORDER

fun primaryType(card: DeckCard): String = mtgoracle.core.lookup.CardTypes.primary(card.info?.typeLine)

/**
 * A deck grouped by type, the commander first and the sideboard last: art
 * frames, or one line per card in text mode. Shared by the library's preview
 * and the deck workspace.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeckView(
    deck: Deck, keyFor: (DeckCard) -> String?, mode: CardMode, cols: Int, onHover: (CardFace) -> Unit, points: (String) -> Int? = { null },
    /** Cards Forge's AI won't play or Forge lacks: red beside them until the AI copy has a substitute. */
    aiFlags: Map<String, mtgoracle.core.deck.AiFlag> = emptyMap(),
    /** A flag's `[!]` / `[→]`: that card's AI substitute, asked for at once. */
    onAiFlag: (String) -> Unit = {},
) {
    val gap = with(LocalDensity.current) { LocalCells.current.width.toDp() }
    for (section in Section.entries) {
        val cards = deck.cards.filter { it.section == section }
        if (cards.isEmpty()) continue
        val groups = cards.groupBy { if (section == Section.MAIN) primaryType(it) else section.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val order = (listOf("Commander", "Sideboard") + TYPE_ORDER + "Other")
        for ((group, groupCards) in groups.entries.sortedBy { order.indexOf(it.key) }) {
            RuleLine(cols, label = "$group (${groupCards.sumOf { it.quantity }})", bold = true)
            if (mode == CardMode.TEXT) {
                groupCards.forEachIndexed { i, card ->
                    val flag = aiFlags[card.name]
                    val face = card.face(keyFor(card)).flagged(flag)
                    val printing = card.setCode?.let { " (${it.uppercase()}) ${card.collectorNumber.orEmpty()}" }.orEmpty()
                    val name = card.name + (points(card.name)?.let { " ($it)" } ?: "")
                    androidx.compose.foundation.layout.Row {
                        flag?.let { f -> AiFlagButton(f, card.name, onAiFlag); GridText(" ") }
                        val width = cols - if (flag != null) 4 else 0
                        GridText(fit(withLabel("%2d %-${if (flag != null) 28 else 32}s %-10s %s".format(card.quantity, name, face.manaCost, face.typeLine) + printing, flag, width), width),
                            Modifier.clickTarget(ClickTarget.Control("card:${section}:$group:$i"), {}) { onHover(face) },
                            color = if (flag?.open == true) Palette.tapped else Palette.foreground)
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    groupCards.forEachIndexed { i, card ->
                        val flag = aiFlags[card.name]
                        val face = card.face(keyFor(card)).flagged(flag)
                        androidx.compose.foundation.layout.Column {
                            CardFrame(face, mode, target = ClickTarget.Control("card:${section}:$group:$i"), onHover = { onHover(face) },
                                mark = frameMark(points(card.name), flag), alert = flag?.open == true)
                            flag?.let { f -> AiFlagButton(f, card.name, onAiFlag) }
                        }
                    }
                }
            }
        }
    }
}
