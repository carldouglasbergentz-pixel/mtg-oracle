package mtgoracle.ui.lookup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.lookup.SearchPage
import mtgoracle.core.lookup.SearchRow
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.Emphasis
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.displayCost
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.kit.GridText
import mtgoracle.core.deck.DeckSection

/** Where a grid's frames number their click targets within an entry: past any line's (`line * 100 + span`). */
const val GRID_TARGETS = 90_000

/** Cards per row of the grid in a pane [cols] wide: what Up / Down move by. */
fun gridColumns(cols: Int): Int = maxOf(1, (cols + 1) / (FrameSize.cols(CardMode.ART) + 1))

/**
 * A search page as Scryfall shows one: the cards, as the house frames with
 * their art, between the page's own header and paging lines (the text
 * rendering's first and last, links included). A click opens the card, a
 * hover zooms it; [selected] (the arrow keys) is marked and kept in view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchGrid(
    page: SearchPage,
    lines: List<OutLine>,
    at: Long,
    selected: Int?,
    faceOf: (String) -> CardFace?,
    onOpen: (OutputLink) -> Unit,
    onHover: (OutputLink) -> Unit,
    /** The deck workspace: `[+] [sb] [?]` under each card. */
    actions: Boolean = false,
    points: (String) -> Int? = { null },
) {
    val gap = with(LocalDensity.current) { LocalCells.current.width.toDp() }
    Column {
        lines.firstOrNull()?.let { OutputLineView(it, at, onOpen, onHover) }
        // A page laid out in groups: each under its heading, a row of cards of its own; a page in no order is one group.
        val groups = page.arrangement?.groups ?: listOf("" to page.rows.size)
        var start = 0
        for ((label, count) in groups) {
            val first = start
            start += count
            if (label.isNotEmpty()) OutputLineView(OutLine(groupHeading(label, page.arrangement?.total(label, count) ?: count), Tone.BOLD), at, onOpen, onHover)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                page.rows.subList(first, first + count).forEachIndexed { offset, row ->
                    val i = first + offset
                    val face = remember(row.name) { faceOf(row.name) ?: row.face() }
                    val link = OutputLink.Card(row.name)
                    val keep = remember { BringIntoViewRequester() }
                    if (i == selected) LaunchedEffect(i) { keep.bringIntoView() }
                    Column(Modifier.bringIntoViewRequester(keep)) {
                        CardFrame(
                            face, CardMode.ART,
                            emphasis = if (i == selected) Emphasis.SELECTABLE else Emphasis.NONE,
                            target = ClickTarget.Link(link, at + GRID_TARGETS + i),
                            onClick = { onOpen(link) },
                            onHover = { onHover(link) },
                            mark = points(row.name)?.let { "($it)" },
                        )
                        if (actions) GridActions(row.name, at + GRID_TARGETS + 100 + i * 3, onOpen)
                    }
                }
            }
        }
        if (lines.size > 1) OutputLineView(lines.last(), at + 50, onOpen, onHover)
    }
}

/** `[+] [sb] [?]` under a result: into the deck, the sideboard, the considering list. */
@Composable
private fun GridActions(card: String, at: Long, onOpen: (OutputLink) -> Unit) {
    androidx.compose.foundation.layout.Row {
        listOf("[+]" to DeckSection.MAIN, "[sb]" to DeckSection.SIDEBOARD, "[?]" to DeckSection.CONSIDERING).forEachIndexed { k, (label, section) ->
            val link = OutputLink.Edit(EditAction.Add(card, section))
            LinkButton(label, ClickTarget.Link(link, at + k)) { onOpen(link) }
            GridText(" ")
        }
    }
}

/** What a result row alone knows of a card, until the database's face arrives. */
private fun SearchRow.face() = CardFace(name, displayCost(manaCost), typeLine.orEmpty().substringBefore(" // "), "", "", null)
