package mtgoracle.core.model

/*
 * What the UI sees of a game: plain immutable data, no Forge types.
 *
 * Built by the forge module from Forge's game view at a moment when the engine
 * is either parked on a prompt or between events, and handed across threads
 * whole. The UI never holds a Forge object.
 */

data class CardState(
    val id: Int,
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val power: Int?,
    val toughness: Int?,
    val loyalty: String?,
    val isLand: Boolean,
    val isCreature: Boolean,
    val tapped: Boolean,
    val summoningSick: Boolean,
    val attacking: Boolean,
    val blocking: Boolean,
    val damage: Int,
    val isToken: Boolean,
    val attachedToId: Int?,
    val text: String,
    /** Opaque key for [mtgoracle.core.art.CardArt]; null when the card has no art (face-down). */
    val imageKey: String? = null,
    /** "+1/+1 x2, loyalty x3" */
    val counters: String = "",
    /**
     * A card this seat may not see (an opponent's hand, a card exiled face
     * down): a back only. Name, cost, text and art are blank, and the id is a
     * stand-in, so nothing about it reaches the UI. Hidden is not face down:
     * a hand card is hidden and face up; [faceDown] says which a back is.
     */
    val hidden: Boolean = false,
    /** Face down, which the table sees: a morph's 2/2 on the battlefield, a hideaway card in exile. */
    val faceDown: Boolean = false,
    /** A commander in the command zone that may not be cast from there this game (Duel Commander 404: its partner was cast first). */
    val castLocked: Boolean = false,
) {
    companion object {
        /** The back of a card this seat may not see; [stand] is a stand-in id unique on the board. */
        fun back(stand: Int, faceDown: Boolean = false) = CardState(
            id = stand, name = "", manaCost = "", typeLine = "", power = null, toughness = null, loyalty = null,
            isLand = false, isCreature = false, tapped = false, summoningSick = false, attacking = false, blocking = false,
            damage = 0, isToken = false, attachedToId = null, text = "", hidden = true, faceDown = faceDown,
        )
    }
}

data class PlayerState(
    val id: Int,
    val name: String,
    val life: Int,
    val isAi: Boolean,
    /** This window's seat — the player whose prompts we answer. */
    val isSeat: Boolean,
    val hasPriority: Boolean,
    val hasLost: Boolean,
    val handCount: Int,
    /** Every card in the hand; the ones this seat may not see are [CardState.hidden] backs. */
    val hand: List<CardState>,
    val libraryCount: Int,
    val battlefield: List<CardState>,
    val graveyard: List<CardState>,
    val exile: List<CardState>,
    /** Floating mana as "W2 R1"; empty when the pool is empty. */
    val manaPool: String,
    /** The seat's own: the pool empties when this step ends (false for mana that stays, as with Upwelling). */
    val manaEmpties: Boolean = true,
    val poison: Int = 0,
    /** The command zone: commanders, emblems. */
    val command: List<CardState> = emptyList(),
)

enum class StackKind(val verb: String) { SPELL("cast"), ACTIVATED("activated"), TRIGGERED("triggered") }

data class StackEntry(
    val id: Int,
    val text: String,
    val sourceName: String,
    val controllerName: String,
    val sourceCardId: Int? = null,
    val imageKey: String? = null,
    val controllerId: Int = -1,
    val kind: StackKind = StackKind.SPELL,
    /** What it targets, by name, and the same as board refs for highlighting (visible cards and players only). */
    val targetNames: List<String> = emptyList(),
    val targets: List<BoardRef> = emptyList(),
    /**
     * The source card as it is now, wherever it is: an activated fetchland's
     * source sits in the graveyard, and the stack box still shows its art.
     * Null when this seat may not see it.
     */
    val source: CardState? = null,
)

/**
 * Something that happened, as the table saw it: "Polluted Delta → graveyard",
 * "fetched Swamp", "life 20→19", "drew a card". Built under the same
 * visibility rule as the board, so a hidden card is never named — and
 * [cardIds] holds only cards this seat may see.
 */
data class TrailEntry(val seq: Long, val actorId: Int, val text: String, val cardIds: Set<Int> = emptySet())

/** One attacker, what it attacks, and who blocks it (in damage order). */
data class CombatLine(val attackerId: Int, val defender: String, val blockerIds: List<Int>)

data class BoardState(
    val turn: Int,
    /** Forge's display name for the step, e.g. "Main phase, precombat". */
    val phase: String,
    /** Stable key for logic: a [Step] name, e.g. MAIN1; "-" before the first turn. */
    val phaseKey: String,
    val activePlayerId: Int,
    val activePlayerName: String,
    val players: List<PlayerState>,
    val stack: List<StackEntry>,
    val combat: List<CombatLine>,
    val recentLog: List<String>,
    val gameOver: Boolean,
    val result: String?,
    /** The last few things that happened, oldest first. */
    val trail: List<TrailEntry> = emptyList(),
    /** The trail's seq at the seat's last decision: entries after it are new since you last acted. */
    val decisionSeq: Long = 0,
) {
    val seat: PlayerState? get() = players.firstOrNull { it.isSeat }
    val step: Step? get() = Step.entries.firstOrNull { it.name == phaseKey }
    fun opponentsOf(player: PlayerState): List<PlayerState> = players.filter { it.id != player.id }

    /**
     * Across the table: the far player (the opponent; watching, player one)
     * and the near one (you; player two). Null while there aren't two —
     * Forge drops a player who has lost from its list, mid-setup included.
     */
    val sides: Pair<PlayerState, PlayerState>? get() = seat?.let { me -> opponentsOf(me).firstOrNull()?.let { it to me } }
        ?: players.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
    fun card(id: Int): CardState? = players.asSequence()
        .flatMap { (it.hand + it.battlefield + it.graveyard + it.exile + it.command).asSequence() }
        .firstOrNull { it.id == id && !it.hidden }
    /** New since your last decision and not your own doing (watching: the last few). */
    val freshEntries: List<TrailEntry> get() = if (seat == null) trail.takeLast(FRESH_WATCHING)
        else trail.filter { it.seq > decisionSeq && it.actorId != seat?.id }
    val freshCardIds: Set<Int> get() = freshEntries.flatMap { it.cardIds }.toSet()
    /** Cards and players the stack's items target: highlighted while the item is shown. */
    val stackTargets: Set<BoardRef> get() = stack.flatMap { it.targets }.toSet()
    fun controllerOf(cardId: Int): PlayerState? = players.firstOrNull { p ->
        p.battlefield.any { it.id == cardId } || p.hand.any { it.id == cardId }
    }
}

private const val FRESH_WATCHING = 5

/** The steps of a turn, named as Forge's PhaseType constants so the forge module maps by name. */
enum class Step(val label: String) {
    UNTAP("untap"),
    UPKEEP("upkeep"),
    DRAW("draw"),
    MAIN1("main 1"),
    COMBAT_BEGIN("begin combat"),
    COMBAT_DECLARE_ATTACKERS("attackers"),
    COMBAT_DECLARE_BLOCKERS("blockers"),
    COMBAT_FIRST_STRIKE_DAMAGE("first strike"),
    COMBAT_DAMAGE("damage"),
    COMBAT_END("end combat"),
    MAIN2("main 2"),
    END_OF_TURN("end"),
    CLEANUP("cleanup"),
}
