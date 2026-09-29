package mtgoracle.forge

import forge.deck.Deck
import forge.game.GameRules
import forge.game.GameType
import forge.game.player.RegisteredPlayer
import forge.gamemodes.match.HostedMatch
import forge.player.GamePlayerUtil
import forge.player.LobbyPlayerHuman
import mtgoracle.model.GameSeat
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumSet

/** A running game and the seat that watches or plays it. */
class RunningMatch(val seat: GameSeat, val recorder: GameRecorder, internal val hosted: HostedMatch)

/**
 * Starts games through Forge's `HostedMatch` — the same entry point its own
 * desktop and mobile GUIs use — so the engine sees a perfectly ordinary
 * match with an ordinary `PlayerControllerHuman` for our seat.
 */
object ForgeMatch {

    fun humanVsAi(humanDeck: Deck, aiDeck: Deck, label: String): RunningMatch {
        val recorder = newRecorder(label)
        val gui = SeatGui(recorder, ForgeRuntime.edt)
        val human = RegisteredPlayer(humanDeck).apply { player = LobbyPlayerHuman("You") }
        val ai = RegisteredPlayer(aiDeck).apply { player = GamePlayerUtil.createAiPlayer("AI (${aiDeck.name})", 1) }
        recorder.note("human seat: ${humanDeck.name}  vs  AI: ${aiDeck.name}")
        val hosted = HostedMatch()
        hosted.startMatch(rules(), EnumSet.of(GameType.Constructed), listOf(human, ai), human, gui)
        return RunningMatch(gui, recorder, hosted)
    }

    /** No human: HostedMatch asks GuiBase for a spectator GUI, which is our seat. */
    fun aiVsAi(deckA: Deck, deckB: Deck, label: String): RunningMatch {
        val recorder = newRecorder(label)
        val spectator = SeatGui(recorder, ForgeRuntime.edt)
        ForgeRuntime.guiBase.spectatorFactory = { spectator }
        val a = RegisteredPlayer(deckA).apply { player = GamePlayerUtil.createAiPlayer("AI 1 (${deckA.name})", 0) }
        val b = RegisteredPlayer(deckB).apply { player = GamePlayerUtil.createAiPlayer("AI 2 (${deckB.name})", 1) }
        recorder.note("AI vs AI: ${deckA.name}  vs  ${deckB.name}")
        val hosted = HostedMatch()
        hosted.startMatch(rules(), EnumSet.of(GameType.Constructed), listOf(a, b), emptyMap(), null)
        return RunningMatch(spectator, recorder, hosted)
    }

    private fun rules() = GameRules(GameType.Constructed).apply { gamesPerMatch = 1 }

    private fun newRecorder(label: String): GameRecorder {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return GameRecorder(File(ForgeRuntime.paths.logDir, "$stamp-$label.log"))
    }
}
