package mtgoracle.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
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
class Lanes(battlefield: List<CardState>, looks: Looks) {
    private val ids = battlefield.map { it.id }.toSet()
    private val onHosts = battlefield.filter { it.attachedToId != null && it.attachedToId in ids }
    private val loose = battlefield - onHosts.toSet()
    private fun attachedTo(card: CardState) = onHosts.filter { it.attachedToId == card.id }
    private fun single(cards: List<CardState>) = cards.map { Slot(listOf(it), attachedTo(it)) }

    /** Animated manlands and artifact creatures included: a creature is a creature. */
    val creatures: List<Slot> = single(loose.filter { it.isCreature })
    val walkers: List<Slot> = single(loose.filter { !it.isCreature && !it.isLand && (it.typeLine.contains("Planeswalker") || it.typeLine.contains("Battle")) })
    val permanents: List<Slot> = single(loose.filter { !it.isCreature && !it.isLand && !it.typeLine.contains("Planeswalker") && !it.typeLine.contains("Battle") })

    /**
     * Lands in piles, in a stable order: names as they first appear on the
     * battlefield, and within a name the untapped pile first, the tapped one
     * right after it, then any pile that differs otherwise. Tapping one card
     * splits or merges that name's piles in place; no other pile moves.
     */
    val lands: List<Slot> = run {
        val landCards = loose.filter { it.isLand && !it.isCreature }
        val firstSeen = landCards.map { it.name }.distinct().withIndex().associate { (i, name) -> name to i }
        landCards
            .groupBy { land ->
                val attached = attachedTo(land)
                if (attached.isNotEmpty()) listOf(land.name, "attached", land.id) // never stack a land with something on it
                else listOf(land.name, land.tapped, land.counters, land.damage, land.faceDown, looks.emphasis(land), looks.mark(land))
            }
            .values.map { group -> Slot(group, attachedTo(group.first())) }
            .sortedWith(compareBy({ firstSeen.getValue(it.card.name) }, { variantRank(it, looks) }, { it.cards.minOf { c -> c.id } }))
    }

    private fun variantRank(slot: Slot, looks: Looks): Int = when {
        slot.attached.isEmpty() && slot.card.counters.isEmpty() && slot.card.damage == 0 && looks.mark(slot.card) == null && looks.emphasis(slot.card) == Emphasis.NONE ->
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

/** One player's half: zone column on the left, their battlefield beside it, mirrored for the far side. */
@Composable
fun Half(
    player: PlayerState, board: BoardState, far: Boolean, stops: PhaseStops?, showHand: Boolean, looks: Looks,
    modifier: Modifier = Modifier, zoneCols: Int = ZONE_COLS, zoneDrag: PaneDrag? = null,
) {
    val side = if (far) "far" else "near"
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.cellWidth(zoneCols).fillMaxHeight()) {
            ZoneColumn(player, board, far, stops, looks, zoneCols, Modifier.fillMaxSize().region("$side-zones"))
            // Its right border is the handle.
            if (zoneDrag != null) PaneEdge(zoneDrag, "$side-zones-edge", Modifier.align(Alignment.CenterEnd))
        }
        Battlefield(player, far, showHand, looks, Modifier.weight(1f).fillMaxHeight().region("$side-field"))
    }
}

/** Rows of one band of a half: its label line, then a lane of cards. */
fun bandRows(tier: FrameTier, attach: Boolean = true) = 1 + laneRows(tier, attach)

/** Columns a slot takes: its frame, and the cell a tapped card turns into. */
fun slotCols(tier: FrameTier, land: Boolean) = FrameSize.cols(tier, land) + 1

/** A battlefield's zones as the planner sees them: slot widths per type zone. */
fun zoneContents(lanes: Lanes, tier: FrameTier) = ZoneKind.entries.map { k -> ZoneContent(k, lanes.zone(k).map { slotCols(tier, k == ZoneKind.LANDS) }) }

/** The fewest rows a zone column needs, border included: see planZoneColumn. */
const val ZONE_COLUMN_MIN_ROWS = ZONE_FIXED_ROWS + 3 + 2

/**
 * One player's battlefield, drawn from its plan (HalfPlan.kt): type zones in
 * bands from the midline out, creatures nearest it, lands at the player's
 * edge (mirrored for the far side). Every card sits at a computed place, so
 * a tap, a hover or a click moves nothing; only a card arriving or leaving
 * can re-plan its band.
 */
@Composable
private fun Battlefield(player: PlayerState, far: Boolean, showHand: Boolean, looks: Looks, modifier: Modifier) {
    val lanes = Lanes(player.battlefield, looks)
    BoxPane(null, modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cells = LocalCells.current
            val density = LocalDensity.current
            val cols = cells.cols(constraints.maxWidth.toFloat())
            // Watching with hands shown, the far hand takes a lane at that player's edge.
            val handRows = if (far && showHand) FrameSize.rows(looks.tier) else 0
            val rows = cells.rows(constraints.maxHeight.toFloat()) - handRows
            val band = bandRows(looks.tier, looks.attach)
            val plan = planHalf(zoneContents(lanes, looks.tier), cols, maxOf(1, rows / band))
            // More bands than rows is the last resort: then the half scrolls. Otherwise it fits exactly.
            val contentRows = maxOf(rows, plan.bands * band)
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().cellHeight(contentRows + handRows)) {
                    if (handRows > 0) HandLane(player, looks)
                    for (b in 0 until plan.bands) {
                        // Rows from the midline: nonland bands pack outwards from it (band 0 touches it); the
                        // lands bands pack inwards from the player's edge, so free rows fall between the two.
                        val fromMidline = if (b < plan.landsFrom) b * band else contentRows - (plan.bands - b) * band
                        val y = if (far) handRows + contentRows - fromMidline - band else fromMidline
                        BandView(b, plan, lanes, looks, cols, if (far) "far" else "near", Modifier.offset(y = with(density) { (y * cells.height).toDp() }))
                    }
                }
            }
        }
    }
}

/** One band: each zone's label on the rule line, its cards under it, overlapped when the plan says so. */
@Composable
private fun BandView(band: Int, plan: HalfPlan, lanes: Lanes, looks: Looks, cols: Int, side: String, modifier: Modifier) {
    val cells = LocalCells.current
    val density = LocalDensity.current
    fun dx(c: Int) = with(density) { (c * cells.width).toDp() }
    val places = plan.slots.filter { it.band == band }
    val segments = plan.segments.filter { it.band == band }
    val contentCols = maxOf(cols, places.maxOfOrNull { it.x + slotCols(looks.tier, it.zone == ZoneKind.LANDS) } ?: 0)
    var hovered by remember { mutableStateOf<Int?>(null) }
    var pointed by remember { mutableStateOf<Int?>(null) } // the overlapped card whose strip the mouse is on
    Box(modifier.cells(cols, bandRows(looks.tier, looks.attach)).region("$side-band-$band")) {
        Box(Modifier.fillMaxSize().then(if (band in plan.scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
            Box(Modifier.cells(contentCols, bandRows(looks.tier, looks.attach))) {
                segments.forEach { seg ->
                    RuleLine(maxOf(1, seg.width - 1), Modifier.offset(x = dx(seg.x)), label = seg.zone.label)
                }
                places.forEachIndexed { order, place ->
                    val slot = lanes.zone(place.zone)[place.index]
                    val land = place.zone == ZoneKind.LANDS
                    val overlapped = place.visible < slotCols(looks.tier, land)
                    val onHover: (ClickTarget?) -> Unit = { hovered = slot.card.id; looks.onHover(it) }
                    // Later cards lie over earlier ones; the one under the mouse comes to the top.
                    Box(Modifier.offset(x = dx(place.x), y = with(density) { cells.height.toDp() }).zIndex(if (hovered == slot.card.id) 1000f else order.toFloat())) {
                        SlotView(slot, land, looks, clickable = !overlapped, onHover = onHover, pointed = pointed == slot.card.id)
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
private fun SlotView(slot: Slot, land: Boolean, looks: Looks, clickable: Boolean = true, onHover: (ClickTarget?) -> Unit = looks.onHover, pointed: Boolean = false) {
    val cols = slotCols(looks.tier, land)
    Column(Modifier.cells(cols, laneRows(looks.tier, looks.attach))) {
        CardFrame(slot.card.face(), looks.mode, looks.emphasis(slot.card), if (clickable) ClickTarget.Card(slot.card.id) else null, looks.onClick, onHover,
            turnable = true, land = land, stack = slot.cards.size, mark = looks.mark(slot.card), rotate = looks.rotate, tier = looks.tier, hovered = pointed)
        // The attachment line, when this half keeps one (a card of it has something attached): the same
        // under every card, so an aura moving between creatures moves nothing.
        if (looks.attach) Row(Modifier.cells(cols, 1).horizontalScroll(rememberScrollState())) {
            slot.attached.forEach { a ->
                GridText("+", color = Palette.dim)
                CardChip(a.face(), looks.emphasis(a), ClickTarget.Card(a.id), looks.onClick, looks.onHover, looks.mark(a))
            }
        }
    }
}

/**
 * A hand as one lane: your own (or, watching with hands shown, the far
 * player's). Too many cards overlap as a band of the table does; past that it scrolls.
 */
@Composable
fun HandLane(player: PlayerState, looks: Looks) {
    BoxWithConstraints(Modifier.fillMaxWidth().cellHeight(FrameSize.rows(looks.tier))) {
        val cells = LocalCells.current
        val density = LocalDensity.current
        val cols = cells.cols(constraints.maxWidth.toFloat())
        val width = FrameSize.cols(looks.tier) + 1
        val plan = planHalf(listOf(ZoneContent(ZoneKind.CREATURES, player.hand.map { width })), cols, 1)
        var hovered by remember { mutableStateOf<Int?>(null) }
        var pointed by remember { mutableStateOf<Int?>(null) } // the card whose strip the mouse is on
        val contentCols = maxOf(cols, plan.slots.maxOfOrNull { it.x + width } ?: 0)
        Box(Modifier.fillMaxSize().then(if (0 in plan.scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
            Box(Modifier.cells(contentCols, FrameSize.rows(looks.tier))) {
                plan.slots.forEachIndexed { order, place ->
                    val card = player.hand[place.index]
                    val overlapped = place.visible < width
                    val onHover: (ClickTarget?) -> Unit = { hovered = card.id; looks.onHover(it) }
                    val x = with(density) { (place.x * cells.width).toDp() }
                    Box(Modifier.offset(x = x).zIndex(if (hovered == card.id) 1000f else order.toFloat())) {
                        CardFrame(card.face(), looks.mode, looks.emphasis(card), if (overlapped) null else ClickTarget.Card(card.id), looks.onClick, onHover,
                            mark = looks.mark(card), tier = looks.tier, hovered = pointed == card.id)
                    }
                    if (overlapped) Box(Modifier.offset(x = x).zIndex(2000f + order).cells(place.visible, FrameSize.rows(looks.tier))
                        .clickTarget(ClickTarget.Card(card.id), looks.onClick, onHover, mark = false, onHoverChange = { on -> pointed = pointedAfter(pointed, card.id, on) }))
                }
            }
        }
        plan.segments.firstOrNull { it.hiddenRight > 0 }?.let { GridText(" +${it.hiddenRight} ▸", Modifier.align(Alignment.TopEnd), color = Palette.accent, background = Palette.background, bold = true) }
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
    val text = if (board == null) "starting" else buildString {
        append("turn ${board.turn} · ${board.phase} · active: ${board.activePlayerName}")
        board.players.firstOrNull { it.hasPriority }?.let { append(" · priority: ${it.name}") }
        if (board.stack.isNotEmpty()) append(" · STACK ${board.stack.size}: ${board.stack.first().sourceName}")
        // Combat in one phrase: who attacks what, and who blocks.
        if (board.combat.isNotEmpty()) append(" · COMBAT " + board.combat.joinToString("; ") { line ->
            val blockers = line.blockerIds.mapNotNull { board.card(it)?.name }
            "${board.card(line.attackerId)?.name ?: "?"} → ${line.defender}" + if (blockers.isEmpty()) "" else " (blocked: ${blockers.joinToString(", ")})"
        })
        if (board.gameOver) append(" · GAME OVER: ${board.result}")
    }
    Column(modifier.cellHeight(HEADER_ROWS)) {
        RuleLine(cols, label = text, end = title, heavy = true, color = Palette.accent, bold = true)
        Column(Modifier.cells(cols, TRAIL_LINES).region("trail")) { if (board != null) TrailStrip(board, cols) }
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
private fun ZoneColumn(player: PlayerState, board: BoardState, far: Boolean, stops: PhaseStops?, looks: Looks, cols: Int, modifier: Modifier) {
    val inner = cols - 2
    val role = if (player.isSeat) "you" else if (player.isAi) "AI" else "player"
    var ladderOpen by remember { mutableStateOf(true) }
    BoxPane(role, modifier, right = if (stops != null) "stops" else null) {
        BoxWithConstraints {
            val viewport = maxHeight
            // Every section a set number of rows, planned for the height there is (planZoneColumn).
            val plan = planZoneColumn(LocalCells.current.rows(constraints.maxHeight.toFloat()), player.graveyard.size,
                player.command.size + player.exile.size, ladderWanted = ladderOpen)
            // Shorter than even the minimum: it scrolls, anchored at the midline, so life and stops stay in view.
            Column(Modifier.verticalScroll(rememberScrollState(), reverseScrolling = far).heightIn(min = viewport), verticalArrangement = Arrangement.SpaceBetween) {
                // At the player's edge: library (outermost), graveyard, exile and command zone.
                // At the midline: hand, phase stops, life.
                val zones = listOf<@Composable () -> Unit>(
                    { GridText(fit("library ${player.libraryCount}", inner)) },
                    { ZoneList("graveyard ${player.graveyard.size}", player.graveyard.asReversed(), inner, plan.graveyardRows, looks) }, // top card first
                    { ZoneList(exileTitle(player), player.command + player.exile, inner, plan.exileRows, looks) },
                )
                val self = listOf<@Composable () -> Unit>(
                    { HandCount(player, inner, looks) },
                    { StopLadder(!far, stops, board, inner, looks.onClick, plan.ladderRows) { ladderOpen = !ladderOpen } },
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

@Composable
private fun LifeBlock(player: PlayerState, board: BoardState, inner: Int, looks: Looks) {
    val targeting = BoardRef.Player(player.id) in looks.highlighted || (looks.prompt as? InputPrompt)?.kind == InputKind.TARGET
    Row(Modifier.cells(inner, 1)) {
        GridText(if (board.activePlayerId == player.id) "> " else "  ", color = Palette.accent)
        val targeted = BoardRef.Player(player.id) in looks.targeted
        val name = fit(player.name, inner - 4 - if (targeted) 9 else 0).trimEnd()
        GridText(" $name ", Modifier.clickTarget(ClickTarget.Player(player.id), looks.onClick),
            color = if (targeting) Palette.background else Palette.foreground, background = if (targeting) Palette.accent else Palette.dim.copy(alpha = 0.35f), bold = true)
        if (targeted) GridText(" $TARGET_MARK target", color = Palette.accent, bold = true)
    }
    Row(Modifier.cells(inner, 2)) {
        RecordText(player.life.toString())
        BasicText(player.life.toString(), style = gridStyle.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
            color = if (player.life <= 5) Palette.accent else Palette.foreground))
        Column {
            GridText("  life" + (if (player.poison > 0) " · poison ${player.poison}" else ""), color = Palette.dim)
            // Reserved whether or not there is anything to say.
            GridText(fit(if (player.hasPriority) "  priority" else "", inner - 6), color = Palette.dim)
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
            if (paying) GridText("[$symbols]", Modifier.clickTarget(ClickTarget.Mana(colour), looks.onClick), color = Palette.background, background = Palette.accent, bold = true)
            else GridText(symbols, color = Palette.foreground, bold = true)
            GridText(" ")
        }
    }
}

@Composable
private fun HandCount(player: PlayerState, inner: Int, looks: Looks) {
    // The count, and a back per card this seat can't see. ASCII backs: shade blocks draw taller than a line.
    val backs = player.hand.count { it.hidden }
    // One line: the count and backs, then any of an opponent's cards Forge shows us (Thoughtseize, Telepathy),
    // on the same line so a reveal moves nothing; it scrolls sideways when long.
    val revealed = if (player.isSeat) emptyList() else player.hand.filter { !it.hidden }
    val count = "hand ${player.handCount}" + if (backs > 0) " " + "[#]".repeat(minOf(backs, (inner - 8) / 3)) else ""
    Row(Modifier.cells(inner, 1).horizontalScroll(rememberScrollState())) {
        GridText(if (revealed.isEmpty()) fit(count, inner) else count, color = if (player.isSeat) Palette.dim else Palette.foreground)
        if (revealed.isNotEmpty()) {
            GridText(" revealed ", color = Palette.accent)
            revealed.forEach { CardChip(it.face(), looks.emphasis(it), ClickTarget.Card(it.id), looks.onClick, looks.onHover, looks.mark(it)) }
        }
    }
}

/** A titled list of [rows] fixed rows; it scrolls inside when longer. */
@Composable
private fun ZoneList(title: String, cards: List<CardState>, inner: Int, rows: Int, looks: Looks) {
    GridText(fit(title, inner), color = Palette.dim, bold = true)
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
 * Watching (no [stops]) it keeps its place, blank.
 */
@Composable
private fun StopLadder(seatsTurn: Boolean, stops: PhaseStops?, board: BoardState, inner: Int, onClick: (ClickTarget) -> Unit, rows: Int = LADDER_FULL, onToggle: () -> Unit = {}) {
    val half = (Step.entries.size + 1) / 2
    if (rows < half) {
        // Too short for the ladder: one line naming the stops that are on; a click gives the ladder the room.
        val on = Step.entries.filter { stops?.stopsAt(seatsTurn, it) == true }.joinToString(" ") { SHORT.getValue(it) }
        GridText(fit(if (stops == null) "" else "stops: ${on.ifEmpty { "none" }} ▾", inner),
            Modifier.clickTarget(ClickTarget.Control("ladder:${if (seatsTurn) "mine" else "theirs"}"), { onToggle() }), color = Palette.dim)
        return
    }
    Box(Modifier.cells(inner, half)) {
        if (stops == null) return@Box
        val activeHere = (board.activePlayerId == board.seat?.id) == seatsTurn
        val cell = inner / 2
        Column {
            for (row in 0 until half) {
                Row {
                    for (step in listOfNotNull(Step.entries.getOrNull(row), Step.entries.getOrNull(row + half))) {
                        val on = stops.stopsAt(seatsTurn, step)
                        val current = activeHere && board.step == step
                        GridText(
                            fit("${if (on) "[x]" else "[ ]"} ${SHORT[step]}", cell),
                            Modifier.clickTarget(ClickTarget.Stop(seatsTurn, step), onClick),
                            color = when { current -> Palette.background; on -> Palette.accent; else -> Palette.dim },
                            background = if (current) Palette.foreground else Color.Unspecified,
                        )
                    }
                }
            }
        }
    }
}

/** Which card's strip the mouse is on, after [id]'s strip reports entering ([on]) or leaving: a late exit from one card never clears the next. */
private fun pointedAfter(current: Int?, id: Int, on: Boolean): Int? = if (on) id else if (current == id) null else current
