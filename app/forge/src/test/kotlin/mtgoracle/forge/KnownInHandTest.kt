package mtgoracle.forge

import forge.game.card.CardView
import forge.game.event.GameEventCardChangeZone
import forge.game.player.PlayerView
import forge.game.zone.ZoneType
import forge.game.zone.ZoneView
import forge.trackable.Tracker
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a seat knows of the other hand: a card that went there in plain sight,
 * until the hand loses a card unseen (Brainstorm's put-back), which could
 * have been it. A card leaving openly forgets only itself.
 */
class KnownInHandTest {
    private val tracker = Tracker()
    private val me = PlayerView(1, tracker)
    private val ai = PlayerView(2, tracker)
    private fun card(id: Int) = CardView(id, tracker, "Card $id")
    private fun zone(p: PlayerView, z: ZoneType) = ZoneView(p, z)
    private fun KnownInHand.move(id: Int, from: ZoneView, to: ZoneView) = onZone(GameEventCardChangeZone(card(id), from, to))

    @Test
    fun `a card returned in plain sight is known until the hand loses a card unseen`() {
        val known = KnownInHand(isViewer = { it == 1 })
        known.move(108, zone(ai, ZoneType.Graveyard), zone(ai, ZoneType.Hand)) // Overlord returns Deep-Cavern Bat
        known.move(109, zone(ai, ZoneType.Battlefield), zone(ai, ZoneType.Hand)) // a bounce
        known.move(110, zone(ai, ZoneType.Library), zone(ai, ZoneType.Hand)) // a draw: not seen
        assertTrue(known.knowsId(108) && known.knowsId(109))
        assertFalse(known.knowsId(110), "a drawn card is not known")
        known.move(109, zone(ai, ZoneType.Hand), zone(ai, ZoneType.Graveyard)) // discarded: seen to leave
        assertFalse(known.knowsId(109))
        assertTrue(known.knowsId(108), "the other known card stays known")
        known.move(110, zone(ai, ZoneType.Hand), zone(ai, ZoneType.Library)) // Brainstorm puts one back, unseen
        assertFalse(known.knowsId(108), "it could have been the Bat: forgotten")
    }

    @Test
    fun `a card shown in the other hand (a tutor's reveal) is known there until the hand loses a card unseen`() {
        val known = KnownInHand(isViewer = { it == 1 })
        known.move(130, zone(ai, ZoneType.Library), zone(ai, ZoneType.Hand)) // Cloud fetches Lion Sash...
        known.revealed(130, ownerId = 2) // ...and reveals it
        known.revealed(7, ownerId = 1) // our own hand is seen anyway
        assertTrue(known.knowsId(130))
        assertFalse(known.knowsId(7))
        known.move(131, zone(ai, ZoneType.Hand), zone(ai, ZoneType.Library))
        assertFalse(known.knowsId(130), "a card put back unseen could have been it")
    }

    @Test
    fun `the seat's own hand is no news, and a card into another hand from a hidden zone is not known`() {
        val known = KnownInHand(isViewer = { it == 1 })
        known.move(5, zone(me, ZoneType.Graveyard), zone(me, ZoneType.Hand))
        known.move(6, zone(ai, ZoneType.Library), zone(ai, ZoneType.Hand))
        assertFalse(known.knowsId(5) || known.knowsId(6))
    }
}
