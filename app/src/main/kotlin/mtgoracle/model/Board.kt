package mtgoracle.model

/*
 * What the UI sees of a game: plain immutable data, no Forge types.
 *
 * Built by the forge boundary from Forge's GameView at a moment when the
 * engine is either paused on a prompt or between events, and handed across
 * threads whole. The UI never holds a Forge object.
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
)

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
    /** Null when the hand is hidden from this seat. */
    val hand: List<CardState>?,
    val libraryCount: Int,
    val battlefield: List<CardState>,
    val graveyard: List<CardState>,
    val exile: List<CardState>,
    /** Floating mana as "W2 R1"; empty when the pool is empty. */
    val manaPool: String,
)

data class StackEntry(val id: Int, val text: String, val sourceName: String, val controllerName: String)

data class BoardState(
    val turn: Int,
    /** Forge's display name for the step, e.g. "Main phase, precombat". */
    val phase: String,
    /** Stable key for logic: Forge's PhaseType constant name, e.g. MAIN1. */
    val phaseKey: String,
    val activePlayerId: Int,
    val activePlayerName: String,
    val players: List<PlayerState>,
    val stack: List<StackEntry>,
    val recentLog: List<String>,
    val gameOver: Boolean,
    val result: String?,
) {
    val seat: PlayerState? get() = players.firstOrNull { it.isSeat }
    fun opponentsOf(player: PlayerState): List<PlayerState> = players.filter { it.id != player.id }
    fun card(id: Int): CardState? = players.asSequence()
        .flatMap { (it.hand.orEmpty() + it.battlefield + it.graveyard + it.exile).asSequence() }
        .firstOrNull { it.id == id }
    fun controllerOf(cardId: Int): PlayerState? = players.firstOrNull { p ->
        p.battlefield.any { it.id == cardId } || p.hand.orEmpty().any { it.id == cardId }
    }
}
