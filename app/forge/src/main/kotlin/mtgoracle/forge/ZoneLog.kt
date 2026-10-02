package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEventCardChangeZone
import forge.game.zone.ZoneType

/**
 * A line in the log pane when a card leaves a public zone for a hidden one:
 * "Zone Change: Deep-Cavern Bat: graveyard → AI (Rakdos)'s hand." Forge logs
 * a mill but not what it returns (Overlord of the Balemurk), nor a bounce or a tuck,
 * and the trail's line in the header was gone at the next decision. The card
 * was in plain sight where it was, so every viewer may read its name: the
 * log pane is public (HiddenInfoTest). A face-down card is never named.
 */
internal class ZoneLog(private val recorder: GameRecorder) {
    @Volatile private var game: Game? = null

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onZone(event: GameEventCardChangeZone) {
        val from = event.from() ?: return
        val to = event.to() ?: return
        if (from.zoneType() !in PUBLIC || to.zoneType() !in HIDDEN) return
        val card = event.card() ?: return
        val what = if (card.isFaceDown) "a face-down card" else card.currentState?.name ?: return
        // Forge calls the human seat "You" (ForgeMatch).
        val owner = to.player()?.name?.let { if (it == "You") "your " else "$it's " }.orEmpty()
        recorder.play("Zone Change: $what: ${from.zoneType().name.lowercase()} → $owner${to.zoneType().name.lowercase()}.")
    }

    private companion object {
        // Not the stack: a cast cancelled at its payment goes back to the hand, and that is no news.
        val PUBLIC = setOf(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command)
        val HIDDEN = setOf(ZoneType.Hand, ZoneType.Library)
    }
}
