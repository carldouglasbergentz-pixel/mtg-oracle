package mtgoracle.app

import mtgoracle.core.play.GameMode
import mtgoracle.core.seat.Policy
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.ForgeSetup
import mtgoracle.ui.kit.CardMode
import java.io.File

/** A staged game for the tests: the test Forge home, no art, PNGs in build/test-png. */
class Scenario(
    name: String,
    startState: List<String>?,
    mode: CardMode = CardMode.TEXT,
    gameMode: GameMode = GameMode.HUMAN_VS_AI,
    seatDeck: mtgoracle.core.deck.PlayDeck = basics(1, "Island"),
    policy: Policy,
) : StagedGame(name, startState.also { startForge() }, File(home, "scenario-logs"), pngDir, mode, gameMode, policy = policy, seatDeck = seatDeck) {
    companion object {
        val home = File(System.getProperty("mtgoracle.testHome"), "forge-home")
        val pngDir = File(System.getProperty("mtgoracle.pngDir")).also { it.mkdirs() }

        fun startForge() = ForgeRuntime.initialise(ForgeSetup(File(System.getProperty("mtgoracle.forgeAssets")), home))
    }
}
