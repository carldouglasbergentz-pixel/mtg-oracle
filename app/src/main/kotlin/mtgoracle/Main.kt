package mtgoracle

import mtgoracle.forge.ForgeMatch
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.RunningMatch
import mtgoracle.forge.SpikeLog
import mtgoracle.forge.SpikePaths
import mtgoracle.model.BoardState
import mtgoracle.model.InputKind
import mtgoracle.model.InputPrompt
import mtgoracle.model.Prompt
import mtgoracle.model.SeatAction
import mtgoracle.seat.ScriptedSeat
import mtgoracle.ui.OffscreenWindow
import mtgoracle.ui.runWindow
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

const val HUMAN_DECK = "Rakdos Midrange.dck"
const val AI_DECK = "UW Draw Go - Control.dck"
const val AI_MIRROR_DECK = "Rakdos Midrange (AI).dck"

/**
 * Modes (see build.gradle.kts for the Gradle tasks):
 *   window human   — you play Rakdos Midrange against the AI's UW Draw Go
 *   window ai      — watch AI vs AI
 *   scripted       — headless: ScriptedSeat plays the human seat; writes spike-board.png
 *   headless-ai    — headless AI vs AI
 */
fun main(args: Array<String>) {
    val paths = SpikePaths.fromSystemProperties()
    System.getProperty("mtgoracle.seed")?.toLongOrNull()?.let { ForgeRuntime.seedRandom(it) }
    when (args.getOrElse(0) { "window" }) {
        "window" -> runWindowMode(paths, ai = args.getOrNull(1) == "ai")
        "scripted" -> { ForgeRuntime.initialise(paths); exitProcess(runScripted()) }
        "headless-ai" -> { ForgeRuntime.initialise(paths); exitProcess(runHeadlessAi()) }
        else -> error("unknown mode ${args[0]}")
    }
}

private fun runWindowMode(paths: SpikePaths, ai: Boolean) {
    val running = AtomicReference<RunningMatch?>()
    val title = if (ai) "AI vs AI" else "You (Rakdos Midrange) vs AI (UW Draw Go)"
    runWindow(
        title,
        start = {
            ForgeRuntime.initialise(paths)
            val match = if (ai) ForgeMatch.aiVsAi(ForgeRuntime.loadDeck(AI_MIRROR_DECK), ForgeRuntime.loadDeck(AI_DECK), "window-ai")
            else ForgeMatch.humanVsAi(ForgeRuntime.loadDeck(HUMAN_DECK), ForgeRuntime.loadDeck(AI_DECK), "window-human")
            running.set(match)
            SpikeLog.info("recording to ${match.recorder.file}")
            match.seat
        },
        onClose = { running.get()?.recorder?.close() },
    )
}

private fun runScripted(): Int {
    val match = ForgeMatch.humanVsAi(ForgeRuntime.loadDeck(HUMAN_DECK), ForgeRuntime.loadDeck(AI_DECK), "scripted")
    val png = File(System.getProperty("mtgoracle.pngOut"))
    val window = OffscreenWindow(match.seat, "You (Rakdos Midrange) vs AI (UW Draw Go) · scripted seat")
    var viaUi = 0
    var fallbacks = 0
    // Every decision is a click on the window's own composable; a decision the
    // board can't express is answered directly and counted, never hidden.
    val pictures = BoardPictures(window, png, match)
    val submit: (Prompt, SeatAction) -> Unit = { prompt, action ->
        pictures.maybeTake(match.seat.board.value, prompt) // the prompt is current: the picture matches it
        if (window.perform(prompt, action) { match.recorder.seat("  UI click #${prompt.id}: $it") }) {
            viaUi++
        } else {
            fallbacks++
            match.recorder.seat("  UI-FALLBACK: the board had nothing to click for $action; answered directly")
            match.seat.answer(prompt.id, action)
        }
    }
    val seat = ScriptedSeat(match.seat, onDecision = { SpikeLog.info("seat $it") }, submit = submit)
    val finished = seat.playUntilGameOver()
    window.close()
    return report(match, finished, "scripted seat made ${seat.decisions} decisions: $viaUi by UI click, $fallbacks answered directly")
}

/** Two pictures of the live window: a busy mid-game main phase, and the first targeting prompt. */
private class BoardPictures(private val window: OffscreenWindow, private val midGame: File, private val match: RunningMatch) {
    private val targeting = File(midGame.parentFile, midGame.nameWithoutExtension + "-target.png")
    private var midGameTaken = false
    private var targetingTaken = false

    fun maybeTake(board: BoardState?, prompt: Prompt) {
        if (board == null || prompt !is InputPrompt) return
        val permanents = board.players.sumOf { p -> p.battlefield.count { !it.isLand } }
        if (!midGameTaken && prompt.kind == InputKind.PRIORITY && board.turn >= 7 && permanents >= 3 &&
            board.activePlayerId == board.seat?.id && board.phaseKey == "MAIN1") {
            midGameTaken = true
            save(midGame, board)
        }
        if (!targetingTaken && prompt.kind == InputKind.TARGET) {
            targetingTaken = true
            save(targeting, board)
        }
    }

    private fun save(file: File, board: BoardState) {
        window.savePng(file)
        SpikeLog.info("board at turn ${board.turn} (${board.phase}) rendered to $file")
        match.recorder.note("PNG of turn ${board.turn} (${board.phase}) written to $file")
    }
}

private fun runHeadlessAi(): Int {
    val match = ForgeMatch.aiVsAi(ForgeRuntime.loadDeck(AI_MIRROR_DECK), ForgeRuntime.loadDeck(AI_DECK), "ai-vs-ai")
    val deadline = System.currentTimeMillis() + 20 * 60_000
    while (match.seat.board.value?.gameOver != true && System.currentTimeMillis() < deadline) Thread.sleep(200)
    return report(match, match.seat.board.value?.gameOver == true, "spectated")
}

private fun report(match: RunningMatch, finished: Boolean, detail: String): Int {
    Thread.sleep(500) // let the last events and the final snapshot land
    val board = match.seat.board.value
    SpikeLog.info("game ${if (finished) "finished" else "DID NOT finish"}: ${board?.result} on turn ${board?.turn}; $detail")
    SpikeLog.info("log: ${match.recorder.file}")
    match.recorder.close()
    return if (finished) 0 else 1
}
