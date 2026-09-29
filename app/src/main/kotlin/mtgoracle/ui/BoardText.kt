package mtgoracle.ui

import mtgoracle.model.BoardState
import mtgoracle.model.CardState
import mtgoracle.model.ChoicePrompt
import mtgoracle.model.ConfirmPrompt
import mtgoracle.model.InputPrompt
import mtgoracle.model.PlayerState
import mtgoracle.model.Prompt

/*
 * The board as a fixed-width character grid: pure text plus the regions that
 * can be clicked. The Compose layer only paints lines and maps a click's
 * (row, column) to a span — it never re-derives positions from the text,
 * the same rule the TUI follows with renderer.LinkSpan.
 */

sealed interface ClickTarget {
    data class Card(val id: Int) : ClickTarget
    data class Player(val id: Int) : ClickTarget
    data object Ok : ClickTarget
    data object Cancel : ClickTarget
    data class Option(val index: Int) : ClickTarget
    data object Done : ClickTarget
}

enum class Emphasis { NONE, DIM, HEADER, SELECTABLE, ACTIONABLE, BUTTON }

data class Span(val line: Int, val start: Int, val end: Int, val target: ClickTarget?, val emphasis: Emphasis)

data class TextGrid(val lines: List<String>, val spans: List<Span>) {
    fun targetAt(line: Int, column: Int): ClickTarget? =
        spans.lastOrNull { it.target != null && it.line == line && column >= it.start && column < it.end }?.target
}

/** Lines of exactly [width] characters, with spans relative to this pane. */
private class Pane(val width: Int) {
    val lines = mutableListOf<String>()
    val spans = mutableListOf<Span>()

    fun line(text: String = "", emphasis: Emphasis = Emphasis.NONE, target: ClickTarget? = null) {
        val fitted = fit(text, width)
        if (emphasis != Emphasis.NONE || target != null) spans += Span(lines.size, 0, text.length.coerceAtMost(width), target, emphasis)
        lines += fitted
    }

    /** One line assembled from pieces, each with its own span. */
    fun pieces(vararg parts: Triple<String, Emphasis, ClickTarget?>) {
        val sb = StringBuilder()
        for ((text, emphasis, target) in parts) {
            val start = sb.length
            sb.append(text)
            if ((emphasis != Emphasis.NONE || target != null) && start < width) {
                spans += Span(lines.size, start, sb.length.coerceAtMost(width), target, emphasis)
            }
        }
        lines += fit(sb.toString(), width)
    }

    fun section(title: String) = line("─ $title " + "─".repeat(maxOf(0, width - title.length - 3)), Emphasis.HEADER)

    fun wrapped(text: String, emphasis: Emphasis = Emphasis.NONE) {
        for (paragraph in text.split('\n')) wrap(paragraph, width).forEach { line(it, emphasis) }
    }

    /** Card blocks side by side, wrapping onto new rows. */
    fun blocks(cards: List<CardState>, selectable: Set<Int>, actionable: Set<Int>) {
        if (cards.isEmpty()) { line("  (none)", Emphasis.DIM); return }
        val perRow = maxOf(1, (width + 1) / (CARD_WIDTH + 1))
        for (row in cards.chunked(perRow)) {
            val rendered = row.map { cardBlock(it, framed = it.id in selectable) }
            val first = lines.size
            for (i in 0 until CARD_HEIGHT) line(rendered.joinToString(" ") { it[i] })
            row.forEachIndexed { col, card ->
                val start = col * (CARD_WIDTH + 1)
                val emphasis = when {
                    card.id in selectable -> Emphasis.SELECTABLE
                    card.id in actionable -> Emphasis.ACTIONABLE
                    card.tapped -> Emphasis.DIM
                    else -> Emphasis.NONE
                }
                for (i in 0 until CARD_HEIGHT) spans += Span(first + i, start, start + CARD_WIDTH, ClickTarget.Card(card.id), emphasis)
            }
        }
    }

    /** Lands as one-line chips — a board is mostly lands, and they carry little text. */
    fun chips(label: String, cards: List<CardState>, selectable: Set<Int>, actionable: Set<Int>) {
        if (cards.isEmpty()) return
        val sb = StringBuilder(label)
        val pending = mutableListOf<Span>()
        for (card in cards) {
            val chip = "[${card.name}${if (card.tapped) " T" else ""}]"
            if (sb.length + chip.length + 1 > width) {
                spans += pending.map { it.copy(line = lines.size) }; pending.clear()
                lines += fit(sb.toString(), width); sb.clear().append(" ".repeat(label.length))
            }
            val start = sb.length
            sb.append(chip).append(' ')
            val emphasis = when {
                card.id in selectable -> Emphasis.SELECTABLE
                card.id in actionable -> Emphasis.ACTIONABLE
                card.tapped -> Emphasis.DIM
                else -> Emphasis.NONE
            }
            pending += Span(0, start, start + chip.length, ClickTarget.Card(card.id), emphasis)
        }
        spans += pending.map { it.copy(line = lines.size) }
        lines += fit(sb.toString(), width)
    }
}

const val CARD_WIDTH = 22
private const val CARD_HEIGHT = 5

/** A card as a compact text block: name / cost + type / stats. [framed] (a legal pick) draws it double-lined. */
internal fun cardBlock(card: CardState, framed: Boolean = false): List<String> {
    val (tl, h, tr, v, bl, br) = if (framed) BoxChars("╔", "═", "╗", "║", "╚", "╝") else BoxChars("┌", "─", "┐", "│", "└", "┘")
    val inner = CARD_WIDTH - 2
    val cost = card.manaCost.replace(" ", "")
    val stats = buildList {
        if (card.power != null) add("${card.power}/${card.toughness}")
        card.loyalty?.let { add("L$it") }
        if (card.damage > 0) add("dmg ${card.damage}")
        if (card.tapped) add("TAPPED")
        if (card.summoningSick && card.isCreature) add("sick")
        if (card.attacking) add("ATK")
        if (card.blocking) add("BLK")
        if (card.isToken) add("token")
    }.joinToString(" ")
    val costAndType = if (cost.isEmpty()) card.typeLine else "$cost ${card.typeLine}"
    return listOf(
        tl + h.repeat(inner) + tr,
        v + fit(card.name, inner) + v,
        v + fit(costAndType, inner) + v,
        v + fit(stats, inner) + v,
        bl + h.repeat(inner) + br,
    )
}

private data class BoxChars(val tl: String, val h: String, val tr: String, val v: String, val bl: String, val br: String)

object BoardText {

    fun render(board: BoardState?, prompt: Prompt?, width: Int, chosen: Set<Int> = emptySet(), title: String = "MTG Oracle · Forge 2.0.14 embedded"): TextGrid {
        val total = maxOf(width, 100)
        val rightWidth = (total / 3).coerceIn(36, 60)
        val leftWidth = total - rightWidth - 3
        val left = Pane(leftWidth)
        val right = Pane(rightWidth)
        val selectable = (prompt as? InputPrompt)?.selectableCardIds.orEmpty()
        val actionable = (prompt as? InputPrompt)?.actionableCardIds.orEmpty()

        if (board == null) {
            left.line("Starting Forge…", Emphasis.DIM)
        } else {
            // Our seat at the bottom, as at a table; a spectator sees player 1 on top.
            val seat = board.seat
            val (upper, lower) =
                if (seat == null) board.players.take(1) to board.players.drop(1)
                else board.opponentsOf(seat) to listOf(seat)
            upper.forEach { player(left, it, board, selectable, actionable, mirrored = true) }
            left.section("stack" + if (board.stack.isEmpty()) ": empty" else " (${board.stack.size}, top first)")
            board.stack.forEachIndexed { i, s -> left.wrapped("${i + 1}. ${s.controllerName}: ${s.text}") }
            lower.forEach { player(left, it, board, selectable, actionable, mirrored = false) }
            promptPane(left, prompt, board, chosen)
            sidePane(right, board)
        }
        if (board == null) promptPane(left, prompt, null, chosen)

        return frame(title, board, left, right, leftWidth, rightWidth)
    }

    private fun player(pane: Pane, p: PlayerState, board: BoardState, selectable: Set<Int>, actionable: Set<Int>, mirrored: Boolean) {
        val header = { ->
            val marker = when {
                p.hasLost -> "LOST"
                board.activePlayerId == p.id -> ">" // ASCII: a fallback-font glyph would break the fixed grid
                else -> " "
            }
            pane.pieces(
                Triple("$marker ", Emphasis.HEADER, null),
                Triple(p.name, Emphasis.BUTTON, ClickTarget.Player(p.id)),
                Triple("   life ${p.life}   hand ${p.handCount}   library ${p.libraryCount}   graveyard ${p.graveyard.size}   exile ${p.exile.size}" +
                    (if (p.manaPool.isNotEmpty()) "   pool ${p.manaPool}" else "") +
                    (if (p.hasPriority) "   · priority" else ""), Emphasis.HEADER, null),
            )
        }
        val lands = p.battlefield.filter { it.isLand && it.attachedToId == null }
        val others = p.battlefield.filter { !it.isLand }
        if (mirrored) {
            pane.section(p.name + if (p.isAi) " (AI)" else "")
            header()
            p.hand?.takeIf { it.isNotEmpty() && !p.isSeat }?.let { pane.line("  hand:", Emphasis.DIM); pane.blocks(it, selectable, actionable) }
            pane.chips("  lands  ", lands, selectable, actionable)
            pane.blocks(others, selectable, actionable)
        } else {
            pane.section(p.name + if (p.isSeat) " (your seat)" else if (p.isAi) " (AI)" else "")
            pane.blocks(others, selectable, actionable)
            pane.chips("  lands  ", lands, selectable, actionable)
            header()
            p.hand?.let { pane.section("hand (${it.size})"); pane.blocks(it, selectable, actionable) }
        }
    }

    private fun promptPane(pane: Pane, prompt: Prompt?, board: BoardState?, chosen: Set<Int>) {
        pane.section("prompt")
        when (prompt) {
            null -> pane.line(
                when {
                    board?.gameOver == true -> "Game over: ${board.result}."
                    board?.seat == null && board != null -> "Watching AI vs AI."
                    else -> "Waiting for Forge…"
                },
                Emphasis.DIM,
            )
            is InputPrompt -> {
                pane.wrapped(prompt.message)
                if (prompt.selectableElsewhere.isNotEmpty()) {
                    pane.line("choose from:", Emphasis.DIM)
                    pane.blocks(prompt.selectableElsewhere, prompt.selectableCardIds, emptySet())
                }
                val buttons = mutableListOf<Triple<String, Emphasis, ClickTarget?>>()
                if (prompt.okLabel.isNotBlank()) buttons += button(prompt.okLabel, prompt.okEnabled, ClickTarget.Ok)
                if (prompt.cancelLabel.isNotBlank()) buttons += button(prompt.cancelLabel, prompt.cancelEnabled, ClickTarget.Cancel)
                pane.pieces(*buttons.toTypedArray())
                pane.line("${prompt.kind.name.lowercase()} · click a card or a player name · Enter = ${prompt.okLabel.ifBlank { "-" }} · Esc = ${prompt.cancelLabel.ifBlank { "-" }}", Emphasis.DIM)
            }
            is ChoicePrompt -> {
                pane.wrapped(prompt.message)
                prompt.options.forEachIndexed { i, option ->
                    val mark = when {
                        prompt.isReveal -> "  "
                        prompt.max > 1 -> if (i in chosen) "[x]" else "[ ]"
                        else -> "  "
                    }
                    pane.line(fit(" $mark ${i + 1}. $option", pane.width), if (prompt.isReveal) Emphasis.NONE else Emphasis.ACTIONABLE,
                        if (prompt.isReveal) null else ClickTarget.Option(i))
                }
                when {
                    prompt.isReveal -> pane.pieces(button("OK", true, ClickTarget.Done))
                    prompt.max > 1 || prompt.min == 0 -> pane.pieces(
                        button(if (prompt.max > 1) "Done (${chosen.size})" else "None", chosen.size in prompt.min..prompt.max, ClickTarget.Done),
                    )
                }
                pane.line(
                    if (prompt.isReveal) "shown for information · Enter or OK to continue"
                    else "choose ${if (prompt.min == prompt.max) "${prompt.min}" else "${prompt.min}–${prompt.max}"} · click or press 1–9",
                    Emphasis.DIM,
                )
            }
            is ConfirmPrompt -> {
                pane.wrapped(prompt.message)
                pane.pieces(button(prompt.yesLabel, true, ClickTarget.Ok), button(prompt.noLabel, true, ClickTarget.Cancel))
                pane.line("Enter = ${prompt.yesLabel} · Esc = ${prompt.noLabel}", Emphasis.DIM)
            }
        }
    }

    private fun button(label: String, enabled: Boolean, target: ClickTarget) =
        Triple("[ $label ]  ", if (enabled) Emphasis.BUTTON else Emphasis.DIM, if (enabled) target else null)

    private fun sidePane(pane: Pane, board: BoardState) {
        pane.section("log")
        val logRoom = 26
        val wrappedLog = board.recentLog.flatMap { wrap(it, pane.width) }
        wrappedLog.takeLast(logRoom).forEach { pane.line(it) }
        repeat(maxOf(0, logRoom - wrappedLog.size)) { pane.line() }
        for (p in board.players) {
            pane.section("${p.name}: graveyard ${p.graveyard.size}")
            val names = p.graveyard.reversed().joinToString(" · ") { it.name }
            if (names.isEmpty()) pane.line("  (empty)", Emphasis.DIM) else wrap(names, pane.width).take(6).forEach { pane.line(it, Emphasis.DIM) }
            if (p.exile.isNotEmpty()) {
                pane.line("exile ${p.exile.size}:", Emphasis.DIM)
                wrap(p.exile.joinToString(" · ") { it.name }, pane.width).take(3).forEach { pane.line(it, Emphasis.DIM) }
            }
        }
    }

    private fun frame(title: String, board: BoardState?, left: Pane, right: Pane, leftWidth: Int, rightWidth: Int): TextGrid {
        val lines = mutableListOf<String>()
        val spans = mutableListOf<Span>()
        val status = board?.let {
            "Turn ${it.turn} · ${it.phase} · ${it.activePlayerName}" + if (it.gameOver) " · GAME OVER: ${it.result}" else ""
        } ?: "starting"
        val head = "─ $title ─ $status "
        lines += "┌" + fit(head + "─".repeat(maxOf(0, leftWidth + rightWidth + 1 - head.length)), leftWidth + rightWidth + 1) + "┐"
        spans += Span(0, 0, lines[0].length, null, Emphasis.HEADER)
        val rows = maxOf(left.lines.size, right.lines.size)
        for (i in 0 until rows) {
            val l = left.lines.getOrElse(i) { " ".repeat(leftWidth) }
            val r = right.lines.getOrElse(i) { " ".repeat(rightWidth) }
            lines += "│$l│$r│"
        }
        lines += "└" + "─".repeat(leftWidth) + "┴" + "─".repeat(rightWidth) + "┘"
        left.spans.forEach { spans += it.copy(line = it.line + 1, start = it.start + 1, end = it.end + 1) }
        right.spans.forEach { spans += it.copy(line = it.line + 1, start = it.start + leftWidth + 2, end = it.end + leftWidth + 2) }
        return TextGrid(lines, spans)
    }
}

internal fun fit(text: String, width: Int): String {
    val flat = text.replace('\n', ' ')
    return if (flat.length <= width) flat.padEnd(width) else flat.take(maxOf(0, width - 1)) + "…"
}

internal fun wrap(text: String, width: Int): List<String> {
    if (text.length <= width) return listOf(text)
    val out = mutableListOf<String>()
    var line = StringBuilder()
    for (word in text.split(' ')) {
        if (line.isNotEmpty() && line.length + 1 + word.length > width) { out += line.toString(); line = StringBuilder() }
        if (line.isNotEmpty()) line.append(' ')
        line.append(if (word.length > width) word.take(width) else word)
    }
    if (line.isNotEmpty()) out += line.toString()
    return out
}
