package mtgoracle.app

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.art.NoArt
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.play.GameMode
import mtgoracle.core.seat.Policy
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.forge.ForgeMatch
import mtgoracle.forge.MatchSpec
import mtgoracle.forge.RunningMatch
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import mtgoracle.core.art.CardArt
import java.io.File

private fun fail(message: String): Nothing = throw IllegalStateException(message)

/**
 * One real Forge game set to an exact situation (a Forge GameState, applied
 * as turn one begins — see StagedBoards), played by a scripted seat whose
 * every answer is a click, or a key, on the real board composable offscreen.
 * The tests assert on it; the `snapshots` mode renders it with real art.
 * Forge must be initialised first.
 */
open class StagedGame(
    name: String,
    startState: List<String>?,
    logDir: File,
    private val pngDir: File,
    mode: CardMode = CardMode.TEXT,
    gameMode: GameMode = GameMode.HUMAN_VS_AI,
    art: CardArt = NoArt,
    private val policy: Policy,
    /** The offscreen window, in pixels. */
    width: Int = 1800,
    height: Int = 2400,
    /** The seat's deck; the staged cards are what matter, so 60 Islands unless a test needs more. */
    seatDeck: mtgoracle.core.deck.PlayDeck = basics(1, "Island"),
    /** The AI's deck; it must be of the seat deck's game type. */
    opponentDeck: mtgoracle.core.deck.PlayDeck = basics(2, "Swamp"),
) : AutoCloseable {
    companion object {
        /** 60 basics: a deck that never does anything on its own, so only the staged cards matter. */
        fun basics(id: Int, land: String) = AiCopy.asBuilt(Deck(id, "$land deck", null, null, listOf(DeckCard(land, 60, false, false))))
    }

    val log: File = File(logDir, "$name.log")
    private val modeState = androidx.compose.runtime.mutableStateOf(mode)
    private val images = ArtImages(art, decoder = { it.run() }) // the snapshots mode saves PNGs: art decoded before the frame

    /** Art or text frames, from now on. */
    fun setMode(mode: CardMode) { modeState.value = mode; driver.settle(5) }
    val match: RunningMatch
    private val driver: OffscreenDriver
    val answered = mutableListOf<Pair<Prompt, SeatAction>>()
    private var fallbacks = 0

    init {
        log.delete()
        match = ForgeMatch.start(MatchSpec(gameMode, seatDeck, opponentDeck, log, seed = 1, startState = startState))
        driver = OffscreenDriver(width, height) {
            CompositionLocalProvider(LocalArt provides images) { BoardScreen(match.seat, name, modeState.value) }
        }
    }

    val board: BoardState get() = match.seat.board.value ?: fail("no board yet")
    fun logText(): String = log.takeIf { it.isFile }?.readText().orEmpty()

    /** Plays until [until] holds; every decision must be expressible by clicking the board. */
    fun playUntil(timeoutMillis: Long = 60_000, until: () -> Boolean) {
        val seat = ScriptedSeat(match.seat, policy, retryAfterMillis = 1500, submit = { prompt, action ->
            answered += prompt to action
            // A dialog option that stands for a stack object: click the stack entry itself.
            val ref = ((prompt as? ChoicePrompt)?.options?.getOrNull((action as? SeatAction.Choose)?.indices?.singleOrNull() ?: -1))?.ref
            val clicked = if (ref is BoardRef.StackItem) driver.click(ClickTarget.StackItem(ref.id)).let { if (it) listOf(ClickTarget.StackItem(ref.id)) else null }
            else driver.perform(prompt, action)
            if (clicked == null && match.seat.prompt.value?.id != prompt.id) {
                match.recorder.seat("  (prompt #${prompt.id} moved on before the click; not answered)")
            } else if (clicked == null) {
                fallbacks++
                match.recorder.seat("  UI-FALLBACK: nothing on the board to click for $action; ${driver.frames} frames, avg ${if (driver.frames > 0) driver.frameNanos / driver.frames / 1_000_000 else 0} ms; on screen: ${driver.registry.targets.size} targets, text '${driver.text.all().take(300).replace('\n', '|')}'")
                match.seat.answer(prompt.id, action)
            }
        })
        if (!seat.play(timeoutMillis = timeoutMillis, until = { driver.frame(); until() })) fail("scenario timed out; log:\n${logText().lines().filter { " SEAT " in it || " LOG " in it }.takeLast(40).joinToString("\n")}")
        check(fallbacks == 0) { "$fallbacks decisions could not be made by clicking the board" }
    }

    /** Everything drawn as text on the board right now, and the same without the log pane's lines. */
    fun screenText(): String { driver.settle(3); return driver.text.all() }
    fun tableText(): String {
        val log = board.recentLog.flatMap { mtgoracle.ui.kit.wrap(it, 44) }.toSet()
        return screenText().lines().filter { it.trim() !in log.map(String::trim) }.joinToString(separator = "\n")
    }
    fun hover(target: ClickTarget) = driver.hover(target)
    fun registered(target: ClickTarget) = driver.registry[target] != null
    fun rect(target: ClickTarget) = driver.registry[target]

    /** Every fixed region (`region:*`) and every card on screen, where each is drawn right now. */
    fun layout(): Map<ClickTarget, androidx.compose.ui.geometry.Rect> {
        driver.settle(3)
        return driver.registry.targets
            .filter { it is ClickTarget.Card || (it is ClickTarget.Control && it.name.startsWith("region:")) }
            .mapNotNull { t -> driver.registry[t]?.let { t to it } }.toMap()
    }

    /** Renders frames until [until] holds, answering nothing (watching, or holding a prompt open). */
    fun waitFor(timeoutMillis: Long = 60_000, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!until()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting")
            driver.frame(); Thread.sleep(20)
        }
    }

    /** Presses a key on the board, as the keyboard would. */
    fun key(key: androidx.compose.ui.input.key.Key) = driver.key(key)

    fun png(name: String) = driver.savePng(File(pngDir.also { it.mkdirs() }, "$name.png"))

    override fun close() {
        match.concede()
        // Conceding ends the game only once the engine thread is free, and the end of the game
        // reveals face-down permanents (a dialog that blocks Forge's GUI thread). Answer and move on.
        val deadline = System.currentTimeMillis() + 20_000
        while (match.result.value == null && System.currentTimeMillis() < deadline) {
            // Any dialog still open (the engine thread may be waiting in one) gets its plainest answer.
            when (val p = match.seat.prompt.value) {
                is ChoicePrompt -> match.seat.answer(p.id, SeatAction.Choose(if (p.isReveal) emptyList() else (0 until p.min).toList()))
                is mtgoracle.core.model.ConfirmPrompt -> match.seat.answer(p.id, SeatAction.Confirm(false))
                is mtgoracle.core.model.OrderPrompt -> match.seat.answer(p.id, SeatAction.Order(emptyList()))
                is mtgoracle.core.model.DistributePrompt -> match.seat.answer(p.id, SeatAction.Distribute(p.suggested))
                is mtgoracle.core.model.NumberPrompt -> match.seat.answer(p.id, SeatAction.Number(p.min))
                else -> {}
            }
            driver.frame(); Thread.sleep(20)
        }
        check(match.result.value != null) { "the staged game did not end after conceding" }
        driver.close()
        match.recorder.close()
    }
}
