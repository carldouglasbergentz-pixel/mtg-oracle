package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.card.CardView
import forge.game.event.GameEventCardChangeZone
import forge.game.zone.ZoneType

/**
 * The cards this seat knows are in another player's hand, as a player at a
 * table keeps track: one that went there from a public zone (Overlord of the
 * Balemurk's return, a bounce), face up, was seen by everyone.
 *
 * It stays known only while nothing could have hidden it. A card leaving
 * that hand openly (cast, discarded, put onto the battlefield) shows which
 * card left, so only that one is forgotten. One leaving unseen (Brainstorm's
 * put-back, a hand shuffled into the library, exiled face down) could have
 * been any of them, so the hand's every known card is forgotten.
 *
 * [isViewer] says which players are this seat's own: their hands are seen anyway.
 */
internal class KnownInHand(private val isViewer: (playerId: Int) -> Boolean) {
    @Volatile private var game: Game? = null
    /** Per hand's owner, the ids of the cards known to be in it. */
    private val known = HashMap<Int, MutableSet<Int>>()

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        synchronized(this) { known.clear() } // card ids restart each game
        game.subscribeToEvents(this)
    }

    /** Whether [card] is in a hand and known to be there. */
    fun knows(card: CardView): Boolean = card.zone == ZoneType.Hand && knowsId(card.id)

    @Synchronized
    internal fun knowsId(id: Int): Boolean = known.values.any { id in it }

    @Subscribe
    fun onZone(event: GameEventCardChangeZone) {
        val card = event.card() ?: return
        val from = event.from()
        val to = event.to()
        synchronized(this) {
            if (from?.zoneType() == ZoneType.Hand) from.player()?.id?.let { owner ->
                val ids = known[owner] ?: return@let
                val openly = to != null && to.zoneType() in OPEN && !card.isFaceDown
                if (openly) ids.remove(card.id) else ids.clear()
            }
            if (to?.zoneType() == ZoneType.Hand && from != null && from.zoneType() in OPEN && !card.isFaceDown) {
                val owner = to.player()?.id ?: return
                if (!isViewer(owner)) known.getOrPut(owner) { HashSet() } += card.id
            }
        }
    }

    private companion object {
        /** Where a card is in plain sight: what goes in or out through these is seen. Casting shows the card too. */
        val OPEN = setOf(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack)
    }
}
