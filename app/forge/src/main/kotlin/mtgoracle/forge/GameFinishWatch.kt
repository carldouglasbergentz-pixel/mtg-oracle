package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEvent
import forge.game.event.GameEventGameFinished

/**
 * Hears a game finish on Forge's own event bus, so a seat's end of game
 * doesn't hang on Forge's GUI event handler alone. That handler queues its
 * work behind a flag two threads share without making it visible to each
 * other (FControlGameEventHandler.processEventsQueued), and now and then the
 * game's end is never processed: with two people at the table the match
 * then waited for a result that never came. [onFinished] may run twice with
 * Forge's own call; the seat reports a game once.
 */
internal class GameFinishWatch(private val onFinished: () -> Unit) {
    private val attached = HashSet<Int>()

    fun attach(game: Game) {
        if (!attached.add(game.id)) return
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onGameEvent(event: GameEvent) {
        if (event is GameEventGameFinished) onFinished()
    }
}
