package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.card.CardView
import forge.game.event.GameEventCardChangeZone
import forge.game.event.EventValueChangeType
import forge.game.event.GameEventSpellAbilityCast
import forge.game.event.GameEventZone
import forge.game.staticability.StaticAbilityCantBeCast
import forge.game.staticability.StaticAbilityMode
import forge.game.zone.ZoneType

/**
 * A cast of the seat's that Forge stopped without asking anything: the card
 * went onto the stack and straight back, with no spell cast and no prompt in
 * between. Bilbo's attack trigger offered a graveyard card under the
 * opponent's Drannith Magistrate, and the card went back without a word.
 * [onFailed] makes it said. A cast the seat cancelled had a prompt (the
 * payment, a target) and is the seat's own doing, so it is not reported.
 */
internal class FailedCasts(
    /** How many prompts the seat has been shown so far. */
    private val promptCount: () -> Long,
    private val isSeats: (CardView) -> Boolean,
    private val onFailed: (card: CardView, from: ZoneType, forbiddenBy: String?) -> Unit,
) {
    @Volatile private var game: Game? = null
    /** Cards of the seat's on the stack and not yet cast: where each came from, and the prompt count then. */
    private val pending = HashMap<Int, Pair<ZoneType, Long>>()

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        synchronized(this) { pending.clear(); undone.clear() }
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onZone(event: GameEventCardChangeZone) {
        val card = event.card() ?: return
        val from = event.from()?.zoneType()
        if (event.to()?.zoneType() == ZoneType.Stack && from != null && from != ZoneType.Stack && isSeats(card)) {
            synchronized(this) { pending[card.id] = from to promptCount() }
        }
    }

    /** Cards taken off the stack by an undone cast, until they land again: where they came from. */
    private val undone = HashMap<Int, ZoneType>()

    /**
     * Forge undoes a cast without a zone change event: the stack's own
     * "removed" says the card left it, and the next "added" where it landed.
     * Only then is the card in a zone again, where Forge can test what
     * forbids it.
     */
    @Subscribe
    fun onZoneContents(event: GameEventZone) {
        val card = event.card() ?: return
        if (event.sa() != null) return
        if (event.zoneType() == ZoneType.Stack && event.mode() == EventValueChangeType.Removed) {
            val (origin, prompts) = synchronized(this) { pending.remove(card.id) } ?: return
            if (promptCount() == prompts) synchronized(this) { undone[card.id] = origin }
            return
        }
        if (event.mode() != EventValueChangeType.Added) return
        val origin = synchronized(this) { undone.remove(card.id) } ?: return
        onFailed(card, origin, forbiddenBy(card))
    }

    @Subscribe
    fun onCast(event: GameEventSpellAbilityCast) {
        val id = event.si()?.sourceCard?.id ?: return
        synchronized(this) { pending.remove(id) }
    }

    /**
     * The permanent whose "can't be cast" effect applies to the card now, by
     * Forge's own test: the likely reason, named. Null when none does (the
     * cast failed for another reason), and the warning then names none.
     */
    private fun forbiddenBy(view: CardView): String? = runCatching {
        val g = game ?: return null
        val card = g.findById(view.id) ?: return null
        val caster = card.controller ?: return null
        val spell = card.castSA ?: card.firstSpellAbility ?: return null
        // Only what the table sees may be named: face-up cards on the battlefield or in the command zone.
        g.getCardsIn(listOf(ZoneType.Battlefield, ZoneType.Command)).firstOrNull { host ->
            host.id != card.id && !host.isFaceDown && host.staticAbilities.any { st ->
                st.checkConditions(StaticAbilityMode.CantBeCast) && StaticAbilityCantBeCast.applyCantBeCastAbility(st, spell, card, caster)
            }
        }?.name
    }.getOrNull()
}
