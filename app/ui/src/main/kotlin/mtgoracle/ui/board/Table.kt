package mtgoracle.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import mtgoracle.ui.kit.ControlButton
import mtgoracle.ui.kit.PaneDrag
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.PaneEdge
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.PlayerState
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.Step
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.CardChip
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.Emphasis
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.FrameTier
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.RecordText
import mtgoracle.ui.kit.cellHeight
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.chipText
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.gridStyle

/*
 * The table, as a physical one is laid out, drawn like a terminal — and with
 * a fixed geometry, so nothing moves but the cards that change.
 *
 * Each half runs from its player's edge to the midline in two lanes of one
 * fixed height: the back lane (lands, stacked MTGO-style, then other
 * permanents) at the edge, the front lane (creatures) facing the centre. A
 * lane never wraps; more cards than fit scroll sideways. Under every card one
 * line is reserved for what is attached to it. Beside each half, that
 * player's zone column in playmat order, every section a fixed height.
 */

const val ZONE_COLS = 30

/** A card or player a stack item points at, while it is on the stack. */
const val TARGET_MARK = "◄"
/** A card that changed since your last decision. */
const val FRESH_MARK = "*"
/** Picked so far in the current selection (Gush's Islands): its own pile, so the rest of the pile can still be clicked. */
const val PICKED_MARK = "√"

/** Rows of a lane: a frame and its reserved attachment line. */
fun laneRows(tier: FrameTier, attach: Boolean = true) = FrameSize.rows(tier) + if (attach) 1 else 0

/** How the board looks at the current prompt: which cards are legal picks, which could act. */
class Looks(
    val prompt: Prompt?,
    val highlighted: Set<BoardRef>,
    val mode: CardMode,
    val onClick: (ClickTarget) -> Unit,
    val onHover: (ClickTarget?) -> Unit,
    /** Cards that just changed (the trail since your last decision): marked `*`. */
    val fresh: Set<Int> = emptySet(),
    /** What the stack's items target: marked `◄` while they are on it. */
    val targeted: Set<BoardRef> = emptySet(),
    /** Turn tapped permanents a quarter (art mode). */
    val rotate: Boolean = true,
    /** How big this half's frames are (planTable picks it). */
    val tier: FrameTier = FrameSize.tierOf(mode),
    /** Whether this half keeps a line under each card for attachments (only when one has any). */
    val attach: Boolean = true,
) {
    /** The same looks, for a half drawn at [tier], with or without the attachment line. */
    fun sized(tier: FrameTier, attach: Boolean) = Looks(prompt, highlighted, mode, onClick, onHover, fresh, targeted, rotate, tier, attach)

    fun emphasis(card: CardState): Emphasis = when {
        card.hidden -> Emphasis.NONE
        BoardRef.Card(card.id) in highlighted -> Emphasis.SELECTABLE
        prompt is InputPrompt && card.id in prompt.actionableCardIds -> Emphasis.ACTIONABLE
        else -> Emphasis.NONE
    }

    fun mark(card: CardState): String? = when {
        card.hidden -> null
        // One cell: a card's name is worth more of its top edge than the word would be.
        BoardRef.Card(card.id) in targeted -> TARGET_MARK
        prompt is InputPrompt && card.id in prompt.highlightedCardIds -> PICKED_MARK
        card.id in fresh -> FRESH_MARK
        else -> null
    }
}

/** How many tokens alike make a pile (Lanes.creatures); fewer lie one by one. */
const val TOKEN_PILE_FROM = 5

/**
 * One slot on the table: a card (or a stack of identical ones) and what is
 * attached to it. [cards] share everything visible; the slot clicks as its first.
 */
data class Slot(val cards: List<CardState>, val attached: List<CardState>) {
    val card: CardState get() = cards.first()
}

/**
 * The lanes of a battlefield. Lands in the same visible state stack; a stack
 * splits on anything that differs — tapped, counters, damage, attachments,
 * or how the current prompt treats it — so a card that must be picked on its
 * own is on its own.
 */
class Lanes(cards: List<CardState>, looks: Looks, ownTurn: Boolean = true) {
    // A land is new only on its controller's turn: on yours, the one they played on theirs is no news, and piles as any.
    private val battlefield = if (ownTurn) cards else cards.map { if (it.isLand && !it.isCreature && it.summoningSick) it.copy(summoningSick = false) else it }
    private val ids = battlefield.map { it.id }.toSet()
    private val onHosts = battlefield.filter { it.attachedToId != null && it.attachedToId in ids }
    private val loose = battlefield - onHosts.toSet()
    private fun attachedTo(card: CardState) = onHosts.filter { it.attachedToId == card.id }
    private fun single(cards: List<CardState>) = cards.map { Slot(listOf(it), attachedTo(it)) }

    /**
     * Animated manlands and artifact creatures included: a creature is a
     * creature. Tokens alike (name and everything visible) pile once there
     * are [TOKEN_PILE_FROM] of them, as lands do, at the place of the first:
     * thirteen Angels were a row of their own. Never while attackers or
     * blockers are being declared, and never one in combat: each is then a
     * card to pick, or to block.
     */
    val creatures: List<Slot> = run {
        val all = loose.filter { it.isCreature }
        val declaring = (looks.prompt as? InputPrompt)?.kind.let { it == InputKind.ATTACK || it == InputKind.BLOCK }
        val piles = if (declaring) emptyList() else all
            .filter { it.isToken && !it.attacking && !it.blocking && attachedTo(it).isEmpty() }
            .groupBy { listOf(it.name, it.tapped, it.summoningSick, it.power, it.toughness, it.counters, it.chosen, it.damage, looks.emphasis(it), looks.mark(it)) }
            .values.filter { it.size >= TOKEN_PILE_FROM }
        val pileOf = piles.flatMap { pile -> pile.map { it.id to pile } }.toMap()
        all.mapNotNull { card ->
            val pile = pileOf[card.id] ?: return@mapNotNull Slot(listOf(card), attachedTo(card))
            if (pile.first().id == card.id) Slot(pile, emptyList()) else null
        }
    }
    val walkers: List<Slot> = single(loose.filter { !it.isCreature && !it.isLand && (it.typeLine.contains("Planeswalker") || it.typeLine.contains("Battle")) })
    val permanents: List<Slot> = single(loose.filter { !it.isCreature && !it.isLand && !it.typeLine.contains("Planeswalker") && !it.typeLine.contains("Battle") })

    /**
     * Lands in piles, in a stable order: names as they first appear on the
     * battlefield, and within a name the untapped pile first, the tapped one
     * right after it, then any pile that differs otherwise (a land new this
     * turn among them). Tapping one card splits or merges that name's piles
     * in place; no other pile moves.
     */
    val lands: List<Slot> = run {
        val landCards = loose.filter { it.isLand && !it.isCreature }
        val firstSeen = landCards.map { it.name }.distinct().withIndex().associate { (i, name) -> name to i }
        landCards
            .groupBy { land ->
                val attached = attachedTo(land)
                if (attached.isNotEmpty()) listOf(land.name, "attached", land.id) // never stack a land with something on it
                else listOf(land.name, land.tapped, land.summoningSick, land.counters, land.chosen, land.damage, land.faceDown, looks.emphasis(land), looks.mark(land))
            }
            .values.map { group -> Slot(group, attachedTo(group.first())) }
            .sortedWith(compareBy({ firstSeen.getValue(it.card.name) }, { variantRank(it, looks) }, { it.cards.minOf { c -> c.id } }))
    }

    private fun variantRank(slot: Slot, looks: Looks): Int = when {
        slot.attached.isEmpty() && !slot.card.summoningSick && slot.card.counters.isEmpty() && slot.card.chosen.isEmpty() && slot.card.damage == 0 && looks.mark(slot.card) == null && looks.emphasis(slot.card) == Emphasis.NONE ->
            if (slot.card.tapped) 1 else 0
        else -> 2
    }

    fun zone(kind: ZoneKind): List<Slot> = when (kind) {
        ZoneKind.CREATURES -> creatures
        ZoneKind.WALKERS -> walkers
        ZoneKind.PERMANENTS -> permanents
        ZoneKind.LANDS -> lands
    }
}

/**
 * One player's zones, for the left column: library, graveyard, exile, hand, the stops on their turn and life,
 * mirrored for the far side. [handLine]: the hand's count, left out where the hand pane shows the hand.
 * [ladderRoom]: both columns have room for the whole stop ladder, so both fold or neither does.
 */
@Composable
fun PlayerZones(player: PlayerState, board: BoardState, far: Boolean, stops: PhaseStops?, looks: Looks, cols: Int, modifier: Modifier = Modifier,
                handLine: Boolean = true, ladderRoom: Boolean = true) {
    ZoneColumn(player, board, far, stops, looks, cols, modifier.region("${if (far) "far" else "near"}-zones"), handLine, ladderRoom)
}

/** One player's battlefield, the far one mirrored. [mat] is the player's playmat under it; null for the plain table. */
@Composable
fun PlayerField(player: PlayerState, far: Boolean, showHand: Boolean, looks: Looks, modifier: Modifier = Modifier, mat: Playmat? = null, ownTurn: Boolean = true) {
    Battlefield(player, far, showHand, looks, modifier.region("${if (far) "far" else "near"}-field"), mat, ownTurn)
}

/** Rows of one band of a half: its label line, then a lane of cards. */
fun bandRows(tier: FrameTier, attach: Boolean = true) = 1 + laneRows(tier, attach)

/**
 * Columns a slot takes: its frame, and the cell a tapped card turns into.
 * Every card on the table has the same frame, the narrow one lands had,
 * shaped as a card is: a creature beside a land is the same size as it.
 */
fun slotCols(tier: FrameTier) = FrameSize.cols(tier, land = true) + 1

/** A battlefield's zones as the planner sees them: slot widths per type zone. */
fun zoneContents(lanes: Lanes, tier: FrameTier) = ZoneKind.entries.map { k ->
    val slots = lanes.zone(k)
    // A pile of a name already on the row split off from it (tapped, picked, new): the centring leaves it out.
    val splits = slots.indices.filter { i -> k == ZoneKind.LANDS && slots.take(i).any { it.card.name == slots[i].card.name } }.toSet()
    ZoneContent(k, slots.map { slotCols(tier) }, splits)
}

/** The fewest rows a zone column needs, border included: see planZoneColumn. */
const val ZONE_COLUMN_MIN_ROWS = ZONE_FIXED_ROWS + 3 + 2

/**
 * One player's battlefield, drawn from its plan (HalfPlan.kt): type zones in
 * bands from the midline out, creatures nearest it, lands at the player's
 * edge (mirrored for the far side), each band centred across. Every card
 * sits at a computed place, so a tap, a hover or a click moves nothing;
 * only a card arriving or leaving can re-plan its band.
 */
@Composable
private fun Battlefield(player: PlayerState, far: Boolean, showHand: Boolean, looks: Looks, modifier: Modifier, mat: Playmat? = null, ownTurn: Boolean = true) {
    val lanes = Lanes(player.battlefield, looks, ownTurn)
    // No frame of its own: the midline and the zone column bound it, and a border here only took a row each side (a
    // drawn look made it an empty title bar). Clipped, so a scrolled half stops at its edge.
    Box(modifier.background(Palette.background).clipToBounds()) {
        // The mat under everything on the battlefield; the cards' frames keep their own background over it.
        mat?.let { PlaymatLayer(it) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cells = LocalCells.current
            val density = LocalDensity.current
            val cols = cells.cols(constraints.maxWidth.toFloat())
            // Watching with hands shown, the far hand takes a lane at that player's edge.
            val handRows = if (far && showHand) FrameSize.rows(looks.tier) else 0
            // The emblems' row sits at the midline edge: the top of the near half, the bottom of the far one.
            val emblems = emblemRows(player)
            val rows = cells.rows(constraints.maxHeight.toFloat()) - handRows - emblems
            val band = bandRows(looks.tier, looks.attach)
            // As many bands as the cards need, never more: a row with room above it overlaps rather than spill onto a second band.
            val contents = zoneContents(lanes, looks.tier)
            val plan = planHalf(contents, cols, minOf(maxOf(1, rows / band), bandsNeeded(contents, cols)), centred = true)
            // More bands than rows is the last resort: then the half scrolls. Otherwise it fits exactly.
            val contentRows = maxOf(rows, plan.bands * band)
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().cellHeight(contentRows + handRows + emblems)) {
                    if (handRows > 0) HandLane(player, looks)
                    if (emblems > 0) Box(Modifier.offset(y = with(density) { ((if (far) handRows + contentRows else 0) * cells.height).toDp() })) {
                        EmblemRow(player, looks, if (far) "far" else "near", onMat = mat != null)
                    }
                    for (b in 0 until plan.bands) {
                        // Rows from the midline: nonland bands pack outwards from it (band 0 touches it); the
                        // lands bands pack inwards from the player's edge, so free rows fall between the two.
                        val fromMidline = if (b < plan.landsFrom) b * band else contentRows - (plan.bands - b) * band
                        val y = if (far) handRows + contentRows - fromMidline - band else emblems + fromMidline
                        BandView(b, plan, lanes, looks, cols, if (far) "far" else "near", Modifier.offset(y = with(density) { (y * cells.height).toDp() }), onMat = mat != null)
                    }
                }
            }
        }
    }
}

/** One band: each zone's label on the rule line ([onMat]: over a playmat), its cards under it, overlapped when the plan says so. */
@Composable
private fun BandView(band: Int, plan: HalfPlan, lanes: Lanes, looks: Looks, cols: Int, side: String, modifier: Modifier, onMat: Boolean = false) {
    val cells = LocalCells.current
    val density = LocalDensity.current
    fun dx(c: Int) = with(density) { (c * cells.width).toDp() }
    val places = plan.slots.filter { it.band == band }
    val segments = plan.segments.filter { it.band == band }
    val contentCols = maxOf(cols, places.maxOfOrNull { it.x + slotCols(looks.tier) } ?: 0)
    var hovered by remember { mutableStateOf<Int?>(null) }
    var pointed by remember { mutableStateOf<Int?>(null) } // the overlapped card whose strip the mouse is on
    Box(modifier.cells(cols, bandRows(looks.tier, looks.attach)).region("$side-band-$band")) {
        Box(Modifier.fillMaxSize().then(if (band in plan.scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
            Box(Modifier.cells(contentCols, bandRows(looks.tier, looks.attach))) {
                segments.forEach { seg ->
                    val label = zoneLabel(seg.zone, lanes.zone(seg.zone).map { it.card })
                    // A label longer than its cards takes the free cells after them, up to the next zone: cut, it read
                    // `artifacts · enchantm…` beside an empty half. No card moves for it.
                    val next = segments.filter { it.x > seg.x }.minOfOrNull { it.x } ?: contentCols
                    val wanted = label.length + 4
                    RuleLine(maxOf(1, seg.width - 1, minOf(wanted, next - seg.x - 1)), Modifier.offset(x = dx(seg.x)), label = label, onPicture = onMat)
                }
                places.forEachIndexed { order, place ->
                    val slot = lanes.zone(place.zone)[place.index]
                    val overlapped = place.visible < slotCols(looks.tier)
                    val onHover: (ClickTarget?) -> Unit = { hovered = slot.card.id; looks.onHover(it) }
                    // Later cards lie over earlier ones; the one under the mouse comes to the top.
                    Box(Modifier.offset(x = dx(place.x), y = with(density) { cells.height.toDp() }).zIndex(if (hovered == slot.card.id) 1000f else order.toFloat())) {
                        SlotView(slot, looks, clickable = !overlapped, onHover = onHover, pointed = pointed == slot.card.id)
                    }
                    // An overlapped card is clicked by the strip of it still showing: never the card on top of it.
                    // The strip lies over the frame, so the frame shows the hover, never the strip (that would tint the art).
                    if (overlapped) Box(
                        Modifier.offset(x = dx(place.x), y = with(density) { cells.height.toDp() }).zIndex(2000f + order)
                            .cells(place.visible, FrameSize.rows(looks.tier))
                            .clickTarget(ClickTarget.Card(slot.card.id), looks.onClick, onHover, mark = false, onHoverChange = { on -> pointed = pointedAfter(pointed, slot.card.id, on) }),
                    )
                }
            }
        }
        // Out of view to the right: say how many, for the whole band.
        segments.sumOf { it.hiddenRight }.takeIf { it > 0 }?.let { hidden ->
            GridText(" +$hidden ▸", Modifier.align(Alignment.TopEnd), color = Palette.accent, background = Palette.background, bold = true)
        }
    }
}

@Composable
private fun SlotView(slot: Slot, looks: Looks, clickable: Boolean = true, onHover: (ClickTarget?) -> Unit = looks.onHover, pointed: Boolean = false) {
    val cols = slotCols(looks.tier)
    Column(Modifier.cells(cols, laneRows(looks.tier, looks.attach))) {
        CardFrame(slot.card.face(), looks.mode, looks.emphasis(slot.card), if (clickable) ClickTarget.Card(slot.card.id) else null, looks.onClick, onHover,
            turnable = true, land = true, stack = slot.cards.size, mark = looks.mark(slot.card), rotate = looks.rotate, tier = looks.tier, hovered = pointed)
        // The attachment line, when this half keeps one (a card of it has something attached): the same
        // under every card, so an aura moving between creatures moves nothing.
        if (looks.attach) Row(Modifier.cells(cols, 1)) { ChipLine(slot.attached, cols, looks, lead = "+") }
    }
}

/** The midline between the halves: a plain rule, one row. */
const val MIDLINE_ROWS = 1
/** The header over the table: its rule line, and two lines of trail. */
const val HEADER_ROWS = 3
const val TRAIL_LINES = HEADER_ROWS - 1

/** The midline: the heavy rule between the halves, nothing on it — the news is in the header. */
@Composable
fun MidRule(cols: Int, modifier: Modifier = Modifier) {
    RuleLine(cols, modifier.cellHeight(MIDLINE_ROWS), heavy = true, color = Palette.accent)
}

/**
 * The header over the table, fixed at the top so the middle of the board
 * stays still: turn, phase, whose turn, priority, what is on the stack and
 * in combat, the match — and under it the trail, the last few things the
 * other side did (new since your last decision in the accent, older dim;
 * older still are in the log).
 */
@Composable
fun Header(title: String, board: BoardState?, cols: Int, modifier: Modifier = Modifier) {
    val text = if (board == null) "starting" else headerNews(board)
    // The folded stack's bar sits on this line just before the title (StackBar): the news stops short of it.
    val bar = if (board != null && board.stack.isNotEmpty()) stackBarText(board.stack.size).length + 1 else 0
    val label = fit(text, maxOf(1, cols - headerEndCols(title) - bar - 5)).trimEnd()
    Column(modifier.cellHeight(HEADER_ROWS)) {
        RuleLine(cols, label = label, end = title, heavy = true, color = Palette.accent, bold = true)
        Column(Modifier.cells(cols, TRAIL_LINES).region("trail")) { if (board != null) TrailStrip(board, cols) }
    }
}

/** The header's news: turn, phase, whose turn, who holds priority (no one while attackers or blockers are declared), stack, combat. */
fun headerNews(board: BoardState): String = buildString {
    append("turn ${board.turn} · ${board.phase} · active: ${board.activePlayerName}")
    board.players.firstOrNull { it.hasPriority }?.let { append(" · priority: ${it.name}") }
    if (board.stack.isNotEmpty()) append(" · STACK ${board.stack.size}: ${board.stack.first().sourceName}")
    if (board.combat.isNotEmpty()) append(" · COMBAT " + combatPhrase(board))
    if (board.gameOver) append(" · GAME OVER: ${board.result}")
}

/** The cells the header's end title takes at the line's right end: ` MTG Oracle ═══`. */
fun headerEndCols(title: String) = title.length + 5

/**
 * Combat in one phrase: who attacks what, and who blocks. Attackers alike
 * are counted, `6× Raging Goblin → Guest`: one by one, six of them ran the
 * header off its end.
 */
fun combatPhrase(board: BoardState): String {
    val lines = board.combat.map { line ->
        val blockers = line.blockerIds.mapNotNull { board.card(it)?.name }
        "${board.card(line.attackerId)?.name ?: "?"} → ${line.defender}" + if (blockers.isEmpty()) "" else " (blocked: ${blockers.joinToString(", ")})"
    }
    return lines.distinct().joinToString("; ") { l -> lines.count { it == l }.let { n -> if (n > 1) "$n× $l" else l } }
}

/**
 * A type zone's label: the types it holds, `artifacts`, `enchantment`,
 * `artifacts · enchantments` only when both are there; a creature or a land
 * row as ever.
 */
fun zoneLabel(zone: ZoneKind, cards: List<CardState>): String {
    fun named(word: String, n: Int) = if (n == 1) word else "${word}s"
    fun of(vararg types: String): String {
        val counts = types.map { t -> t to cards.count { t in it.typeLine } }.filter { it.second > 0 }
        val other = cards.count { c -> types.none { it in c.typeLine } }
        val words = counts.map { (t, n) -> named(t.lowercase(), n) } + if (other > 0) listOf(named("other", 1)) else emptyList()
        return words.joinToString(" · ").ifEmpty { zone.label }
    }
    return when (zone) {
        ZoneKind.PERMANENTS -> of("Artifact", "Enchantment")
        ZoneKind.WALKERS -> of("Planeswalker", "Battle")
        else -> zone.label
    }
}

/** How many of the other side's latest actions the trail keeps to show. */
private const val TRAIL_SHOWN = 10

/**
 * `AI: played Polluted Delta · Polluted Delta → graveyard · life 20→19 ·
 * fetched Swamp`, on up to [TRAIL_LINES] lines: filled from the newest back,
 * read top to bottom, older first. What doesn't fit drops off (it is in the log).
 */
@Composable
private fun TrailStrip(board: BoardState, cols: Int) {
    val seat = board.seat
    val shown = board.trail.filter { seat == null || it.actorId != seat.id }.takeLast(TRAIL_SHOWN)
    val fresh = board.freshEntries.map { it.seq }.toSet()
    fun who(id: Int) = board.players.firstOrNull { it.id == id }?.name?.substringBefore(" (") ?: "?"
    // Segments, newest last; a new actor opens with its name.
    val segments = shown.mapIndexed { i, e ->
        val sep = if (i == 0) "" else if (shown[i - 1].actorId == e.actorId) " · " else "  |  "
        val lead = if (i == 0 || shown[i - 1].actorId != e.actorId) "${who(e.actorId)}: " else ""
        Triple(sep + lead, e.text, e.seq in fresh)
    }
    if (segments.isEmpty()) { GridText(fit(" nothing yet", cols), color = Palette.dim); return }
    // Lines filled from the newest back: the last line ends with the latest entry.
    val lines = ArrayDeque<List<Int>>()
    var line = mutableListOf<Int>()
    var used = 0
    for (i in segments.indices.reversed()) {
        // An entry that opens a line names its actor, whatever came before it.
        val width = segments[i].let { (lead, what, _) -> maxOf(lead.length, "${who(shown[i].actorId)}: ".length) + what.length }
        if (used + width > cols - 2 && line.isNotEmpty()) {
            lines.addFirst(line.reversed()); line = mutableListOf(); used = 0
            if (lines.size == TRAIL_LINES) break
        }
        line += i; used += width
    }
    if (line.isNotEmpty() && lines.size < TRAIL_LINES) lines.addFirst(line.reversed())
    lines.forEach { indices ->
        Row(Modifier.cells(cols, 1)) {
            GridText(" ", color = Palette.dim)
            indices.forEachIndexed { n, i ->
                val (lead, what, isFresh) = segments[i]
                GridText(if (n == 0) "${who(shown[i].actorId)}: " else lead, color = Palette.dim)
                GridText(what, color = if (isFresh) Palette.accent else Palette.dim, bold = isFresh)
            }
        }
    }
}

/** A player's zone column, in playmat order, every section a fixed height; mirrored for the far side. */
@Composable
private fun ZoneColumn(player: PlayerState, board: BoardState, far: Boolean, stops: PhaseStops?, looks: Looks, cols: Int, modifier: Modifier,
                       handLine: Boolean, ladderRoom: Boolean) {
    val inner = cols - 2
    // Titled with the player's name, said once: "AI" over "AI (Rakdos)" said it twice, and a guest's "player" over "Host".
    BoxPane(player.name, modifier, right = if (stops != null) "stops" else null) {
        BoxWithConstraints {
            val viewport = maxHeight
            // Every section a set number of rows, planned for the height there is (planZoneColumn).
            val plan = planZoneColumn(LocalCells.current.rows(constraints.maxHeight.toFloat()), player.graveyard.size,
                player.command.size + player.exile.size, ladderWanted = ladderRoom, handLine = handLine)
            // Shorter than even the minimum: it scrolls, anchored at the prompt's side, so life and stops stay in view.
            Column(Modifier.verticalScroll(rememberScrollState(), reverseScrolling = far).heightIn(min = viewport), verticalArrangement = Arrangement.SpaceBetween) {
                // At the player's edge: library (outermost), graveyard, exile and command zone.
                // Toward the prompt between the two columns: hand, phase stops, life.
                val zones = listOf<@Composable () -> Unit>(
                    { GridText(fit("library ${player.libraryCount}", inner)) },
                    { ZoneList("graveyard ${player.graveyard.size}", panelCards(player, PileZone.GRAVEYARD), inner, plan.graveyardRows, looks, ZonePanelKey(far, PileZone.GRAVEYARD)) }, // top card first
                    { ZoneList(exileTitle(player), panelCards(player, PileZone.EXILE), inner, plan.exileRows, looks, ZonePanelKey(far, PileZone.EXILE)) },
                )
                val self = listOfNotNull<@Composable () -> Unit>(
                    if (handLine) ({ HandCount(player, inner, looks) }) else null,
                    { StopLadder(!far, stops, board, inner, looks.onClick, plan.ladderRows) },
                    { LifeBlock(player, board, inner, looks) },
                )
                if (far) {
                    Column { zones.forEach { it() } }
                    Column { self.forEach { it() } }
                } else {
                    Column { self.asReversed().forEach { it() } }
                    Column { zones.asReversed().forEach { it() } }
                }
            }
        }
    }
}

private fun exileTitle(player: PlayerState) =
    "exile ${player.exile.size}" + if (player.command.isNotEmpty()) " · command ${player.command.size}" else ""

/**
 * The life total, two rows high, and the player's to click (a target, an
 * attack) — the name is the column's title. Beside it: `life`, poison, and
 * `◄ target` while a stack item targets the player, else `> active` on the
 * active player's turn. Then the mana pool.
 */
@Composable
private fun LifeBlock(player: PlayerState, board: BoardState, inner: Int, looks: Looks) {
    val targeting = BoardRef.Player(player.id) in looks.highlighted || (looks.prompt as? InputPrompt)?.kind == InputKind.TARGET
    val targeted = BoardRef.Player(player.id) in looks.targeted
    Row(Modifier.cells(inner, 2).clickTarget(ClickTarget.Player(player.id), looks.onClick).background(if (targeting) Palette.accent else Color.Unspecified)) {
        RecordText(player.life.toString())
        BasicText(" ${player.life}", style = gridStyle.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
            color = when { targeting -> Palette.background; player.life <= 5 -> Palette.accent; else -> Palette.foreground }))
        Column {
            GridText("  life" + (if (player.poison > 0) " · poison ${player.poison}" else ""), color = if (targeting) Palette.background else Palette.dim)
            when {
                targeted -> GridText("  $TARGET_MARK target", color = if (targeting) Palette.background else Palette.accent, bold = true)
                board.activePlayerId == player.id -> GridText("  > active", color = if (targeting) Palette.background else Palette.accent)
                else -> GridText("")
            }
        }
    }
    ManaPoolLine(player, inner, looks)
}

/** "W2 U1" (the snapshot's form) as colour letter and amount, in WUBRG then C order. */
fun poolParts(pool: String): List<Pair<Char, Int>> = pool.split(' ').filter { it.length >= 2 }
    .mapNotNull { part -> part.drop(1).toIntOrNull()?.let { part[0] to it } }

/** One colour of a pool in the costs' own symbols: `{U}{U}`, or `{C}×5` past three. */
fun poolSymbols(colour: Char, amount: Int): String = if (amount <= 3) "{$colour}".repeat(amount) else "{$colour}×$amount"

/**
 * The floating mana, on a line of its own under the life total, always
 * there: `pool  {U}{U} {C}×3`, or `pool  —`. While your payment prompt is up,
 * each colour is a button that spends one of it, as Forge's pool does.
 */
@Composable
private fun ManaPoolLine(player: PlayerState, inner: Int, looks: Looks) {
    val parts = poolParts(player.manaPool)
    val paying = player.isSeat && (looks.prompt as? InputPrompt)?.kind == InputKind.PAY_MANA
    Row(Modifier.cells(inner, 1).region(if (player.isSeat) "pool-near" else "pool-far")) {
        GridText("  pool  ", color = Palette.dim)
        if (parts.isEmpty()) GridText("—", color = Palette.dim)
        parts.forEach { (colour, amount) ->
            val symbols = poolSymbols(colour, amount)
            if (paying) ControlButton("[$symbols]", ClickTarget.Mana(colour), true, looks.onClick)
            else GridText(symbols, color = Palette.foreground, bold = true)
            GridText(" ")
        }
    }
}

@Composable
private fun HandCount(player: PlayerState, inner: Int, looks: Looks) {
    // The count, and a back per card this seat can't see. ASCII backs: shade blocks draw taller than a line.
    val backs = player.hand.count { it.hidden }
    // One line: the count and backs, then the opponent's cards we know: shown us (Thoughtseize, Telepathy) or seen
    // going there (a return, a bounce: KnownInHand). On the same line, so they move nothing; cut to the column.
    val revealed = if (player.isSeat) emptyList() else player.hand.filter { !it.hidden }
    val count = "hand ${player.handCount}" + if (backs > 0) " " + "[#]".repeat(minOf(backs, (inner - 8) / 3)) else ""
    Row(Modifier.cells(inner, 1)) {
        GridText(fit(count, if (revealed.isEmpty()) inner else minOf(count.length, inner)), color = if (player.isSeat) Palette.dim else Palette.foreground)
        if (revealed.isNotEmpty() && inner - count.length > KNOWN.length + 4) {
            GridText(KNOWN, color = Palette.accent)
            ChipLine(revealed, inner - count.length - KNOWN.length, looks)
        }
    }
}

private const val KNOWN = " known "

/**
 * [cards] as chips on one line of [cols] cells, after [lead] each: whole
 * while they fit, the first that doesn't cut with `…`, and `+N` for any
 * left over. A chip past the edge was clipped mid-name with no sign of it.
 */
@Composable
private fun ChipLine(cards: List<CardState>, cols: Int, looks: Looks, lead: String = "") {
    var used = 0
    for ((i, card) in cards.withIndex()) {
        val face = card.face()
        val mark = looks.mark(card)
        val after = cards.size - i - 1
        val more = if (after > 0) " +$after".length else 0
        val whole = lead.length + chipText(face, looks.emphasis(card), mark).length
        val room = cols - used - lead.length - more
        val width = if (used + whole + more <= cols) whole - lead.length else room.takeIf { it >= MIN_CHIP } ?: run {
            GridText(fit("+${cards.size - i}", maxOf(0, cols - used)).trimEnd(), color = Palette.dim)
            return
        }
        if (lead.isNotEmpty()) GridText(lead, color = Palette.dim)
        CardChip(face, looks.emphasis(card), ClickTarget.Card(card.id), looks.onClick, looks.onHover, mark, maxCols = width)
        used += lead.length + width
        if (width < whole - lead.length) {
            if (after > 0) GridText(" +$after", color = Palette.dim)
            return
        }
    }
}

/** The narrowest a cut chip is drawn: `[Ti…]`; narrower, it is only counted. */
private const val MIN_CHIP = 5

/** A titled list of [rows] fixed rows; it scrolls inside when longer. */
@Composable
private fun ZoneList(title: String, cards: List<CardState>, inner: Int, rows: Int, looks: Looks, panel: ZonePanelKey) {
    // The header opens the zone in a panel of its own, every card in its frame (ZonePanel).
    // In the foreground, as the library's line is: dim on the hover's tint (or an open panel's) could hardly be read.
    GridText(fit("$title ▸", inner), Modifier.clickTarget(panel.toggle, looks.onClick).region("zone-header-${panel.id}"), color = Palette.foreground, bold = true)
    Box(Modifier.cells(inner, rows)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            cards.forEach { card -> CardChip(card.face(), looks.emphasis(card), ClickTarget.Card(card.id), looks.onClick, looks.onHover, looks.mark(card)) }
        }
    }
}

/** Short step names, so the ladder fits two columns of the zone column. */
private val SHORT = mapOf(
    Step.UNTAP to "untap", Step.UPKEEP to "upkeep", Step.DRAW to "draw", Step.MAIN1 to "main 1",
    Step.COMBAT_BEGIN to "combat", Step.COMBAT_DECLARE_ATTACKERS to "attackers", Step.COMBAT_DECLARE_BLOCKERS to "blockers",
    Step.COMBAT_FIRST_STRIKE_DAMAGE to "1st strike", Step.COMBAT_DAMAGE to "damage", Step.COMBAT_END to "end combat",
    Step.MAIN2 to "main 2", Step.END_OF_TURN to "end", Step.CLEANUP to "cleanup",
)

/**
 * This player's turn, step by step: where you stop with an empty stack on
 * their turn (your own ladder: your turn; theirs: their turn). Click a step to
 * toggle it; the step being played is lit on the active player's ladder.
 * Watching (no [stops]) it keeps its place, blank. Too short for it, the
 * column says the stops that are on in one line, and a click on that line
 * opens the whole ladder in a box over the table ([LadderBox]).
 */
@Composable
private fun StopLadder(seatsTurn: Boolean, stops: PhaseStops?, board: BoardState, inner: Int, onClick: (ClickTarget) -> Unit, rows: Int = LADDER_FULL) {
    if (rows < LADDER_ROWS) {
        val on = Step.entries.filter { stops?.stopsAt(seatsTurn, it) == true }.joinToString(" ") { SHORT.getValue(it) }
        GridText(fit(if (stops == null) "" else "stops: ${on.ifEmpty { "none" }} ▸", inner),
            if (stops == null) Modifier else Modifier.clickTarget(ladderToggle(seatsTurn), onClick), color = Palette.dim)
        return
    }
    Box(Modifier.cells(inner, LADDER_ROWS)) {
        if (stops != null) LadderGrid(seatsTurn, stops, board, inner, onClick)
    }
}

/** The ladder's rows: its steps in two columns. */
val LADDER_ROWS = (Step.entries.size + 1) / 2

/** The folded ladder's line: a click opens the whole ladder over the table, or closes it. */
fun ladderToggle(seatsTurn: Boolean) = ClickTarget.Control("ladder:${if (seatsTurn) "mine" else "theirs"}")

/** The ladder's steps in two columns of [inner] cells, each a stop to click; the step being played lit on the active player's ladder. */
@Composable
internal fun LadderGrid(seatsTurn: Boolean, stops: PhaseStops, board: BoardState, inner: Int, onClick: (ClickTarget) -> Unit) {
    val half = LADDER_ROWS
    val activeHere = (board.activePlayerId == board.seat?.id) == seatsTurn
    // Each column exactly its cells, so the right one can never run past the border.
    val widths = listOf(inner / 2, inner - inner / 2)
    Column {
        for (row in 0 until half) {
            Row {
                for ((column, step) in listOfNotNull(Step.entries.getOrNull(row), Step.entries.getOrNull(row + half)).withIndex()) {
                    val on = stops.stopsAt(seatsTurn, step)
                    val current = activeHere && board.step == step
                    GridText(
                        fit("${if (on) "[x]" else "[ ]"} ${SHORT[step]}", widths[column]),
                        Modifier.cellWidth(widths[column]).clickTarget(ClickTarget.Stop(seatsTurn, step), onClick),
                        color = when { current -> Palette.background; on -> Palette.accent; else -> Palette.dim },
                        background = if (current) Palette.foreground else Color.Unspecified,
                    )
                }
            }
        }
    }
}

/** Which card's strip the mouse is on, after [id]'s strip reports entering ([on]) or leaving: a late exit from one card never clears the next. */
internal fun pointedAfter(current: Int?, id: Int, on: Boolean): Int? = if (on) id else if (current == id) null else current
