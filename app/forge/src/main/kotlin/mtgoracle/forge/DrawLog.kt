package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEvent

/**
 * A line in the log pane for every draw, as MTGO has: "Draw: AI (Rakdos)
 * draws a card". Forge's own log says "Draw step" and nothing more, so a
 * stop there did not show that the card was already drawn.
 *
 * A draw is what Forge counts as one (Player.numDrawnThisTurn), so a card
 * searched for and put into a hand is not one. Forge counts it after the
 * card has moved, so the count is compared on the event after. The line
 * says how many, never which: the log pane is public (HiddenInfoTest).
 */
internal class DrawLog(private val recorder: GameRecorder) {
    @Volatile private var game: Game? = null
    /** Each player's draws this turn as last seen. */
    private val seen = HashMap<Int, Int>()

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        synchronized(this) { seen.clear() }
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onEvent(event: GameEvent) {
        val players = game?.players ?: return
        for (player in players) {
            val now = player.numDrawnThisTurn
            val before = synchronized(this) { seen.put(player.id, now) } ?: 0
            if (now > before) recorder.play(line(player.name, now - before), ::merged)
        }
    }

    private companion object {
        val LINE = Regex("Draw: (.+) draws? (a card|(\\d+) cards)\\.")

        /** Forge calls the human seat "You" (ForgeMatch): "You draw", "AI (Rakdos) draws". */
        fun line(name: String, n: Int) =
            "Draw: $name ${if (name == "You") "draw" else "draws"} ${if (n == 1) "a card" else "$n cards"}."

        /** The previous line was this player's draw too (Brainstorm's three): one line counting them all. */
        fun merged(last: String, next: String): String? {
            val a = LINE.matchEntire(last) ?: return null
            val b = LINE.matchEntire(next) ?: return null
            if (a.groupValues[1] != b.groupValues[1]) return null
            fun count(m: MatchResult) = m.groupValues[3].ifEmpty { "1" }.toInt()
            return line(a.groupValues[1], count(a) + count(b))
        }
    }
}
