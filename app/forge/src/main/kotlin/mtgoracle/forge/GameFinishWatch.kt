package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEvent
import forge.game.event.GameEventGameFinished
import forge.game.event.GameEventGameOutcome

/**
 * Hears a game end on Forge's own event bus, so a seat's end of game
 * doesn't hang on Forge's GUI event handler alone. That handler queues its
 * work behind a flag two threads share without making it visible to each
 * other (FControlGameEventHandler.processEventsQueued), and now and then a
 * game's end is never processed. Two steps hang on it: the outcome releases
 * the Inputs Forge's game thread waits on ([onDecided]; without it a game
 * conceded while it waited on the other person stood still), and the
 * finish reports the game ([onFinished]; without it the match waited for a
 * result that never came). Each may also run with Forge's own call: both
 * are harmless twice.
 */
internal class GameFinishWatch(private val onDecided: () -> Unit, private val onFinished: () -> Unit) {
    private val attached = HashSet<Int>()

    fun attach(game: Game) {
        if (!attached.add(game.id)) return
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onGameEvent(event: GameEvent) {
        if (event is GameEventGameOutcome) onDecided()
        if (event is GameEventGameFinished) onFinished()
    }
}
