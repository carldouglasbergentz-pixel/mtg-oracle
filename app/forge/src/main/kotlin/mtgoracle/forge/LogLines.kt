package mtgoracle.forge

import forge.game.GameLogEntryType
import mtgoracle.core.model.CardState
import mtgoracle.core.model.LogCard
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine

/**
 * Turns the play-by-play into [LogLine]s: Forge's `(70)` ids taken out, the
 * cards named marked, the kind of line said.
 *
 * A name is marked where the log said which card it was (`Swamp (159)`), and
 * elsewhere only if it is a card the log has already named openly this match
 * (cast, played, a card with its id). Matching every card name there is
 * would mark the rules words that are also cards (Exile, Flashback,
 * Sacrifice), and matching the cards in the game would mark a name in the
 * opponent's library, which is hidden.
 *
 * [nameOf] is the card with that id now (null when it is gone or face down),
 * [oracle] the card as printed, null for a name Forge doesn't know.
 */
internal class LogLines(
    private val nameOf: (Int) -> String?,
    private val oracle: (String) -> CardState?,
) {
    private val seen = HashSet<String>()
    private var seq = 0L

    /** A line of Forge's game log. */
    @Synchronized
    fun forge(type: GameLogEntryType, message: String, players: List<String>): LogLine {
        val kind = kindOf(type, message)
        val body = if (type == GameLogEntryType.LIFE) message.removePrefix("Life: ") else message
        return line(kind, body, players)
    }

    /**
     * One of ours, which knows the cards it names: [names] are marked as Forge's ids would be. A
     * [private] line (shown to one player) marks them in itself only: remembered, they would be
     * marked in public lines after it, and say to everyone which card that player was shown.
     */
    @Synchronized
    fun ours(kind: LogKind, text: String, players: List<String>, names: List<String> = emptyList(), private: Boolean = false): LogLine {
        val known = names.filter { oracle(it) != null }
        if (!private) seen += known
        return line(kind, text, players, extra = if (private) known.toSet() else emptySet())
    }

    private fun line(kind: LogKind, raw: String, players: List<String>, extra: Set<String> = emptySet()): LogLine {
        val masked = players.flatMap { name -> occurrences(raw, name) }
        val (text, byId) = withoutIds(raw, masked, nameOf)
        byId.forEach { seen += it.name }
        castName(kind, text)?.takeIf { oracle(it) != null }?.let { seen += it }
        val taken = players.flatMap { occurrences(text, it) } + byId.map { it.start until it.end }
        val free = scan(text, seen + extra, taken).map { (start, end) -> LogCard(start, end, text.substring(start, end)) }
        val cards = (byId + free).sortedBy { it.start }.map { it.copy(card = oracle(it.name)) }
        return LogLine(seq++, kind, text, cards)
    }

    internal companion object {
        private val ID = Regex(""" \((\d+)\)""")
        private val CAST = Regex("""^.+? (?:cast|activated|triggered) (.+?)(?: targeting \[.*)?$""")
        private val LIFE = Regex("""(\d+) > (\d+)""")
        private val TOKEN = Regex("""\S+""")
        /** Leading and trailing characters a name is written between: `[Hawkeye, Master Marksman]`, `Opt.`, `Chandra's`. */
        private const val OPEN = "[("
        private const val CLOSE = ".,:;)]"

        fun kindOf(type: GameLogEntryType, message: String): LogKind = when (type) {
            GameLogEntryType.GAME_OUTCOME, GameLogEntryType.MATCH_RESULTS -> LogKind.OUTCOME
            GameLogEntryType.TURN -> LogKind.TURN
            GameLogEntryType.PHASE -> LogKind.PHASE
            GameLogEntryType.MANA -> LogKind.MANA
            GameLogEntryType.STACK_ADD -> LogKind.CAST
            GameLogEntryType.STACK_RESOLVE -> if ("reveal" in message.lowercase()) LogKind.REVEAL else LogKind.RESOLVE
            GameLogEntryType.LAND -> LogKind.LAND
            GameLogEntryType.COMBAT -> LogKind.COMBAT
            GameLogEntryType.DAMAGE -> LogKind.DAMAGE
            GameLogEntryType.LIFE -> LIFE.find(message)?.let { m -> if (m.groupValues[2].toInt() < m.groupValues[1].toInt()) LogKind.LIFE_LOST else LogKind.LIFE_GAINED } ?: LogKind.OTHER
            GameLogEntryType.DISCARD -> LogKind.DISCARD
            GameLogEntryType.ZONE_CHANGE -> LogKind.ZONE
            else -> LogKind.OTHER
        }

        fun occurrences(text: String, name: String): List<IntRange> =
            if (name.isEmpty()) emptyList() else generateSequence(text.indexOf(name).takeIf { it >= 0 }) { at -> text.indexOf(name, at + name.length).takeIf { it >= 0 } }
                .map { it until it + name.length }.toList()

        /** [raw] without its ` (70)` ids (none inside a player's name), and the cards they named where the name stands before one. */
        fun withoutIds(raw: String, masked: List<IntRange>, nameOf: (Int) -> String?): Pair<String, List<LogCard>> {
            val out = StringBuilder()
            val cards = mutableListOf<LogCard>()
            var last = 0
            for (m in ID.findAll(raw)) {
                if (masked.any { m.range.first in it }) continue
                out.append(raw, last, m.range.first)
                last = m.range.last + 1
                val id = m.groupValues[1].toInt()
                val name = nameOf(id) ?: continue
                if (out.endsWith(name)) cards += LogCard(out.length - name.length, out.length, name, id)
            }
            out.append(raw, last, raw.length)
            return out.toString() to cards
        }

        /** The card a stack line names: `You cast Lórien Revealed`, `AI triggered Overlord of the Balemurk targeting [...]`. */
        fun castName(kind: LogKind, text: String): String? = if (kind == LogKind.CAST) CAST.find(text)?.groupValues?.get(1) else null

        /** Where [known] names stand in [text], longest first, outside [taken]. */
        fun scan(text: String, known: Set<String>, taken: List<IntRange>): List<Pair<Int, Int>> {
            if (known.isEmpty()) return emptyList()
            val maxWords = known.maxOf { it.count { c -> c == ' ' } + 1 }
            val tokens = TOKEN.findAll(text).map { it.range }.toList()
            val found = mutableListOf<Pair<Int, Int>>()
            var i = 0
            while (i < tokens.size) {
                val hit = (minOf(tokens.size, i + maxWords) downTo i + 1).firstNotNullOfOrNull { j ->
                    var start = tokens[i].first
                    var end = tokens[j - 1].last + 1
                    while (start < end && text[start] in OPEN) start++
                    if (text.startsWith("'s", end - 2) && end - 2 > start) end -= 2
                    while (end > start && text[end - 1] in CLOSE) end--
                    val name = text.substring(start, end)
                    (j to (start to end)).takeIf { name in known && taken.none { r -> start <= r.last && r.first < end } }
                }
                if (hit == null) { i++; continue }
                found += hit.second
                i = hit.first
            }
            return found
        }
    }
}

/** The card as printed, by name, in Forge's own words: what the zoom pane shows of a card the log named. */
internal fun oracleCard(name: String): CardState? {
    val paper = ForgeCards.paperCard(name, null, null) ?: return null
    val face = paper.rules?.mainPart ?: return null
    val type = face.type
    val creature = type?.isCreature == true
    return CardState(
        id = 0, name = paper.name, manaCost = face.manaCost?.takeUnless { it.isNoCost }?.toString().orEmpty(),
        typeLine = type?.toString().orEmpty(), power = if (creature) face.power?.toIntOrNull() else null,
        toughness = if (creature) face.toughness?.toIntOrNull() else null,
        loyalty = face.initialLoyalty?.takeIf { it.isNotBlank() && type?.isPlaneswalker == true },
        isLand = type?.isLand == true, isCreature = creature, tapped = false, summoningSick = false, attacking = false, blocking = false,
        damage = 0, isToken = false, attachedToId = null, text = face.oracleText.orEmpty().replace("\\n", "\n"), imageKey = paper.cardImageKey,
    )
}
