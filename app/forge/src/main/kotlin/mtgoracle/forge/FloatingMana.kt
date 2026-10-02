package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEventManaPool
import forge.game.event.GameEventPlayerPriority
import forge.player.PlayerControllerHuman

/**
 * Floating mana is a stop. While the seat's pool holds mana that the end of
 * this step will empty, Forge never passes the seat's priority by itself:
 * its yields (F4, F6, "until the stack resolves") are set aside and its
 * per-ability auto-yields switched off, and the phase stops don't skip
 * ([SeatGui.isUiSetToSkipPhase] asks [floating]). At the first priority
 * after the pool empties, the end-of-turn yield comes back if it is still the
 * same turn, so F6 goes on skipping once the mana has been spent.
 *
 * Why here: the user floated {W}{W} in response to Blood Moon and passed; it
 * resolved, the AI passed, and with no stop in the AI's main phase the phase
 * ended and the mana with it, the user never asked. Forge changes the pool on
 * the game thread and posts [GameEventManaPool] there synchronously, so the
 * hold is in place before the engine next decides whether to pass for us.
 */
internal class FloatingMana(private val recorder: GameRecorder) {
    @Volatile var controller: PlayerControllerHuman? = null
    private var game: Game? = null
    /** The end-of-turn yield set aside, and in which turn. */
    private var heldTurn: Int? = null
    /** What the seat's auto-yield switch was before the hold turned it off. */
    private var autoYieldsWereDisabled: Boolean? = null

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        // A hold from the last game is undone, not forgotten: Forge keeps "auto-yields off" on the player for the whole match.
        synchronized(this) {
            autoYieldsWereDisabled?.let { was -> controller?.setDisableAutoYields(was) }
            heldTurn = null; autoYieldsWereDisabled = null
        }
        game.subscribeToEvents(this)
    }

    /**
     * Mana in the seat's pool that the end of this step takes away. Mana that
     * stays (Upwelling, Kruphix) is no reason to stop. A pool that can't be
     * read this instant (the engine is changing it) counts as floating:
     * stopping once too often costs a key press, passing once too often costs the mana.
     */
    fun floating(): Boolean {
        val pool = controller?.player?.manaPool ?: return false
        return runCatching { !pool.isEmpty && pool.willManaBeLostAtEndOfPhase() }.getOrDefault(true)
    }

    @Subscribe
    fun onManaPool(event: GameEventManaPool) {
        if (event.player()?.id == controller?.player?.id) update()
    }

    /**
     * The release, only here: a priority begins (game thread), before Forge
     * works out the seat's available actions and asks whether to auto-pass.
     * That work empties and refills the pool as it tries spells, so a
     * release on a pool event could let a yield loose for an instant, and one
     * from the GUI thread could race the engine outright.
     */
    @Subscribe
    fun onPriority(@Suppress("UNUSED_PARAMETER") event: GameEventPlayerPriority) {
        if (!floating()) release()
    }

    /** Holds the seat's yields while mana floats. Also run whenever a yield is set (F4, F6, Forge's End Turn). Never releases. */
    @Synchronized fun update() {
        val controller = controller ?: return
        if (!floating()) return
        val yields = controller.yieldController
        if (yields.autoPassUntilEndOfTurn()) {
            heldTurn = turn()
            yields.setAutoPassUntilEndOfTurn(false)
            recorder.seat("FLOATING MANA: the end-of-turn yield is held until the pool empties")
        }
        // Forge sets these itself, never our keys: nothing of ours to bring back, so just end them.
        if (yields.autoPassUntilStackEmpty()) yields.setAutoPassUntilStackEmpty(false, false)
        if (yields.autoPassUntilMarker != null) yields.clearMarker()
        if (autoYieldsWereDisabled == null) {
            autoYieldsWereDisabled = yields.disableAutoYields
            controller.setDisableAutoYields(true)
        }
    }

    @Synchronized private fun release() {
        val controller = controller ?: return
        autoYieldsWereDisabled?.let { controller.setDisableAutoYields(it) }
        autoYieldsWereDisabled = null
        val turn = heldTurn ?: return
        heldTurn = null
        if (turn != turn()) return // the turn it was for is over
        controller.yieldController.setAutoPassUntilEndOfTurn(true)
        recorder.seat("FLOATING MANA gone: the end-of-turn yield resumes")
    }

    /** A new choice about yielding (F3, F4, F6): the yield held for an older one is not brought back. F2 passes once and changes nothing. */
    @Synchronized fun forget() { heldTurn = null }

    private fun turn(): Int? = game?.phaseHandler?.turn
}
