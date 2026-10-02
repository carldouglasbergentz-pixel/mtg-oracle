package mtgoracle.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckRevision
import mtgoracle.core.deck.DeckSection
import mtgoracle.core.deck.Section
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.TabButton
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ContextMenu
import mtgoracle.ui.kit.onRightClick
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.hoverBackground
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.lookup.DeckTab
import mtgoracle.ui.lookup.EditAction
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** One line of the deck pane: a card in a section, under a group heading. */
data class DeckRow(val card: DeckCard, val section: DeckSection, val group: String)

/** The deck pane's rows for [tab], in the order they are drawn (and the arrow keys walk). */
fun deckRows(deck: Deck, tab: DeckTab): List<DeckRow> = when (tab) {
    DeckTab.CONSIDERING -> deck.considering.map { DeckRow(it, DeckSection.CONSIDERING, "Considering") }
    DeckTab.HISTORY, DeckTab.AI_COPY -> emptyList()
    DeckTab.DECK -> {
        val order = listOf("Commander") + TYPE_ORDER + listOf("Other", "Sideboard")
        deck.cards.map { c ->
            when (c.section) {
                Section.COMMANDER -> DeckRow(c, DeckSection.COMMANDER, "Commander")
                Section.SIDEBOARD -> DeckRow(c, DeckSection.SIDEBOARD, "Sideboard")
                Section.MAIN -> DeckRow(c, DeckSection.MAIN, primaryType(c))
            }
        }.sortedWith(compareBy({ order.indexOf(it.group) }, { it.card.name.lowercase() }))
    }
}

/** What the right-click menu offers for a row, as the edit it makes. */
fun rowMenu(row: DeckRow): List<Pair<String, EditAction?>> {
    val c = row.card.name
    return when (row.section) {
        DeckSection.MAIN -> listOf(
            "+1 copy" to EditAction.Add(c, DeckSection.MAIN), "-1 copy" to EditAction.Remove(c, DeckSection.MAIN),
            "to sideboard" to EditAction.Move(c, DeckSection.MAIN, DeckSection.SIDEBOARD),
            "to considering" to EditAction.Move(c, DeckSection.MAIN, DeckSection.CONSIDERING),
            "make commander" to EditAction.Promote(c), "remove all" to EditAction.Remove(c, DeckSection.MAIN, all = true),
        )
        DeckSection.SIDEBOARD -> listOf(
            "+1 copy" to EditAction.Add(c, DeckSection.SIDEBOARD), "-1 copy" to EditAction.Remove(c, DeckSection.SIDEBOARD),
            "to main deck" to EditAction.Move(c, DeckSection.SIDEBOARD, DeckSection.MAIN),
            "to considering" to EditAction.Move(c, DeckSection.SIDEBOARD, DeckSection.CONSIDERING),
            "remove all" to EditAction.Remove(c, DeckSection.SIDEBOARD, all = true),
        )
        DeckSection.COMMANDER -> listOf("back to the main deck" to EditAction.Demote(c), "remove" to EditAction.Remove(c, DeckSection.COMMANDER, all = true))
        DeckSection.CONSIDERING -> listOf(
            "into the deck" to EditAction.Move(c, DeckSection.CONSIDERING, DeckSection.MAIN),
            "into the sideboard" to EditAction.Move(c, DeckSection.CONSIDERING, DeckSection.SIDEBOARD),
            "remove from the list" to EditAction.Remove(c, DeckSection.CONSIDERING, all = true),
        )
    } + ("open its profile" to null)
}

/**
 * The workspace's deck pane: the tabs, then the deck (lines with `-`/`+`
 * around the count, or frames), the considering list (with `!` where a rule
 * would stop a card in the deck) or the history. A right-click on a card
 * opens its menu. [selected] is the arrow keys' row of [tab].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun EditableDeck(
    deck: Deck,
    tab: DeckTab,
    mode: CardMode,
    cols: Int,
    selected: Int?,
    keyFor: (DeckCard) -> String?,
    points: (String) -> Int?,
    flags: Map<String, String>,
    history: List<DeckRevision>,
    /** More of a row's menu, from the owner: choose a printing. */
    extraMenu: (DeckRow) -> List<Pair<String, () -> Unit>> = { emptyList() },
    onTab: (DeckTab) -> Unit,
    onEdit: (EditAction) -> Unit,
    onOpen: (String) -> Unit,
    onHover: (CardFace) -> Unit,
    /** Stop the AI copy substituting for a card (the AI copy tab's [x]). */
    onUnsubstitute: (String) -> Unit = {},
) {
    var menu by remember { mutableStateOf<Pair<DeckRow, Offset>?>(null) }
    Column {
        Tabs(deck, tab, history.size, onTab)
        when (tab) {
            DeckTab.HISTORY -> History(history, cols)
            DeckTab.AI_COPY -> AiCopyTab(deck, cols, onOpen, onUnsubstitute)
            else -> {
                val rows = deckRows(deck, tab)
                if (rows.isEmpty()) GridText(fit(if (tab == DeckTab.CONSIDERING) "Nothing yet: a result's [?] puts a card here." else "No cards yet: a result's [+] adds one.", cols), color = Palette.dim)
                var group: String? = null
                if (mode == CardMode.ART && tab == DeckTab.DECK) {
                    val gap = with(LocalDensity.current) { LocalCells.current.width.toDp() }
                    rows.groupBy { it.group }.forEach { (g, inGroup) ->
                        RuleLine(cols, label = "$g (${inGroup.sumOf { it.card.quantity }})", bold = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            inGroup.forEach { row ->
                                val face = row.card.face(keyFor(row.card))
                                Column(Modifier.onRightClick { menu = row to it }) {
                                    CardFrame(face, mode, target = ClickTarget.Link(OutputLink.Card(row.card.name), linkAt(row, 0)), onClick = { onOpen(row.card.name) },
                                        onHover = { onHover(face) }, mark = points(row.card.name)?.let { "($it)" },
                                        emphasis = if (rows.indexOf(row) == selected) mtgoracle.ui.kit.Emphasis.SELECTABLE else mtgoracle.ui.kit.Emphasis.NONE)
                                    Row { Controls(row, onEdit) }
                                }
                            }
                        }
                    }
                } else rows.forEachIndexed { i, row ->
                    if (row.group != group) {
                        group = row.group
                        RuleLine(cols, label = "${row.group} (${rows.filter { it.group == row.group }.sumOf { it.card.quantity }})", bold = true)
                    }
                    Line(row, i == selected, cols, keyFor, points, flags[row.card.name], onEdit, onOpen, onHover) { at -> menu = row to at }
                }
            }
        }
    }
    menu?.let { (row, at) ->
        ContextMenu(row.card.name, rowMenu(row).map { (label, action) ->
            label to { if (action == null) onOpen(row.card.name) else onEdit(action) }
        } + extraMenu(row), at) { menu = null }
    }
}

/** A distinct click-target number per row and control (see ClickTarget.Link). */
private fun linkAt(row: DeckRow, k: Int): Long = linkAt(row.card.name, row.section, k)

private fun linkAt(card: String, section: DeckSection, k: Int): Long = (card.hashCode().toLong() shl 8) + section.ordinal * 16 + k

/** The click target of a card's name in the deck pane: what a test right-clicks. */
fun deckRowTarget(card: String, section: DeckSection): ClickTarget.Link = ClickTarget.Link(OutputLink.Card(card), linkAt(card, section, 0))

@Composable
private fun Tabs(deck: Deck, tab: DeckTab, revisions: Int, onTab: (DeckTab) -> Unit) {
    Row {
        for (t in DeckTab.entries) {
            val count = when (t) {
                DeckTab.DECK -> deck.mainCount
                DeckTab.CONSIDERING -> deck.considering.sumOf { it.quantity }
                DeckTab.HISTORY -> revisions
                DeckTab.AI_COPY -> deck.substitutions.size
            }
            // One cell either side, the same width chosen or not: four tabs fit the 58-column deck pane.
            val label = if (t == tab) "[${t.label} $count]" else " ${t.label} $count "
            TabButton(label, ClickTarget.Control("tab:${t.name}"), chosen = t == tab) { onTab(t) }
            GridText(" ")
        }
    }
}

/** `-` / `+` for the deck and sideboard, `>deck` `>sb` `x` for the considering list, `v` (demote) for a commander. ASCII: the grid must not shift. */
@Composable
private fun Controls(row: DeckRow, onEdit: (EditAction) -> Unit) {
    val c = row.card.name
    val controls = when (row.section) {
        DeckSection.CONSIDERING -> listOf(">deck" to EditAction.Move(c, DeckSection.CONSIDERING, DeckSection.MAIN),
            ">sb" to EditAction.Move(c, DeckSection.CONSIDERING, DeckSection.SIDEBOARD), "x" to EditAction.Remove(c, DeckSection.CONSIDERING, all = true))
        DeckSection.COMMANDER -> listOf("v" to EditAction.Demote(c))
        else -> listOf("-" to EditAction.Remove(c, row.section), "+" to EditAction.Add(c, row.section))
    }
    controls.forEachIndexed { k, (label, action) ->
        LinkButton("[$label]", ClickTarget.Link(OutputLink.Edit(action), linkAt(row, k + 1))) { onEdit(action) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Line(
    row: DeckRow, chosen: Boolean, cols: Int, keyFor: (DeckCard) -> String?, points: (String) -> Int?, flag: String?,
    onEdit: (EditAction) -> Unit, onOpen: (String) -> Unit, onHover: (CardFace) -> Unit, onMenu: (Offset) -> Unit,
) {
    val face = row.card.face(keyFor(row.card))
    val controls = if (row.section == DeckSection.CONSIDERING) 16 else if (row.section == DeckSection.COMMANDER) 3 else 6
    val name = row.card.name + (points(row.card.name)?.let { " ($it)" } ?: "")
    val rest = maxOf(8, cols - controls - 4)
    // The whole row is marked under the mouse, controls and all: it is the card an edit or a right-click acts on.
    Row(Modifier.onRightClick(onMenu).hoverBackground()) {
        Controls(row, onEdit)
        GridText(" %2d ".format(row.card.quantity), color = if (chosen) Palette.accent else Palette.foreground, bold = chosen)
        val text = fit("%-30s %s".format(name, face.manaCost) + (flag?.let { "  ! $it" } ?: ""), rest)
        GridText(text, Modifier.clickTarget(ClickTarget.Link(OutputLink.Card(row.card.name), linkAt(row, 0)), { onOpen(row.card.name) }, { onHover(face) }, mark = false)
            .pointerHoverIcon(PointerIcon.Hand), color = if (flag != null) Palette.tapped else if (chosen) Palette.accent else Palette.foreground, bold = chosen)
    }
}

private val STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** A revision's UTC stamp (`2026-09-30T11:00:03Z`) in the viewer's own time, as the TUI shows it. */
fun localTime(utc: String): String =
    runCatching { java.time.Instant.parse(utc).atZone(java.time.ZoneId.systemDefault()).format(STAMP) }.getOrElse { utc }

/**
 * What the AI copy plays instead: one line per substitution, the card and its
 * substitute both links, and an [x] that stops it. The deck itself is untouched.
 */
@Composable
private fun AiCopyTab(deck: Deck, cols: Int, onOpen: (String) -> Unit, onUnsubstitute: (String) -> Unit) {
    GridText(fit("Forge's AI plays these instead, in games and simulations; the deck keeps its own cards.", cols), color = Palette.dim)
    GridText(fit("Right-click a card in the Deck tab > AI substitute... to add one.", cols), color = Palette.dim)
    if (deck.substitutions.isEmpty()) { GridText(fit("No substitutions: the AI plays the deck as built.", cols), color = Palette.dim); return }
    deck.substitutions.forEachIndexed { i, s ->
        Row {
            LinkButton("[x]", ClickTarget.Control("unsubstitute:$i")) { onUnsubstitute(s.cardName) }
            GridText(" ")
            val room = maxOf(8, (cols - 8) / 2)
            GridText(fit(s.cardName, room), Modifier.clickTarget(ClickTarget.Link(OutputLink.Card(s.cardName), linkAt(s.cardName, DeckSection.MAIN, 9)), { onOpen(s.cardName) }).pointerHoverIcon(PointerIcon.Hand))
            GridText(" -> ", color = Palette.dim)
            GridText(fit(s.substitute, room), Modifier.clickTarget(ClickTarget.Link(OutputLink.Card(s.substitute), linkAt(s.substitute, DeckSection.MAIN, 10)), { onOpen(s.substitute) }).pointerHoverIcon(PointerIcon.Hand))
        }
    }
}

/** The history: every revision, newest first, each change on its own line. */
@Composable
private fun History(history: List<DeckRevision>, cols: Int) {
    if (history.isEmpty()) { GridText(fit("No changes recorded yet.", cols), color = Palette.dim); return }
    GridText(fit("newest first · `undo` reverts the newest", cols), color = Palette.dim)
    for (rev in history) {
        val at = localTime(rev.at)
        GridText(fit("#${rev.id}  $at  ${rev.action}" + (rev.note?.let { "  $it" } ?: ""), cols), bold = true)
        for (c in rev.changes) {
            val delta = c.after - c.before
            val what = when {
                delta > 0 -> "+$delta"
                delta < 0 -> "$delta"
                else -> "printing"
            }
            GridText(fit("    $what ${c.card} (${c.section.key})", cols), color = if (delta < 0) Palette.dim else Palette.foreground)
        }
    }
}
