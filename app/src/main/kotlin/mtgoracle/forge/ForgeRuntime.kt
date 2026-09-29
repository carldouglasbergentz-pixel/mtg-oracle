package mtgoracle.forge

import forge.deck.Deck
import forge.deck.io.DeckSerializer
import forge.gamemodes.match.DeclineScope
import forge.gui.GuiBase
import forge.localinstance.properties.ForgeConstants
import forge.localinstance.properties.ForgePreferences
import forge.localinstance.properties.ForgePreferences.FPref
import forge.model.FModel
import forge.util.MyRandom
import java.io.File
import java.util.Random
import java.util.function.Function

/** Where Forge lives and where it may write. All three come from the launcher (build.gradle.kts). */
data class SpikePaths(val forgeDir: File, val sandbox: File, val logDir: File) {
    val decksDir: File get() = sandbox.resolve("decks")

    companion object {
        fun fromSystemProperties() = SpikePaths(
            forgeDir = File(required("mtgoracle.forgeDir")),
            sandbox = File(required("mtgoracle.sandbox")),
            logDir = File(required("mtgoracle.logDir")),
        )

        private fun required(key: String) = System.getProperty(key)
            ?: throw IllegalStateException("system property $key is not set — launch through Gradle (app/build.gradle.kts sets it)")
    }
}

class ForgeSandboxError(message: String) : IllegalStateException(message)

/**
 * Brings Forge up in this process, exactly once.
 *
 * Forge derives its user-data folder from `forge.profile.properties` next to
 * its assets, falling back to %APPDATA%\Forge. We don't touch the install's
 * profile (the user's own Forge reads it too); the launcher points APPDATA at
 * the sandbox instead, and this refuses to start if it hasn't — before any
 * Forge class gets a chance to create directories.
 */
object ForgeRuntime {
    val edt = ForgeEdt()
    lateinit var guiBase: SpikeGuiBase
        private set
    lateinit var paths: SpikePaths
        private set

    @Synchronized
    fun initialise(paths: SpikePaths) {
        if (this::guiBase.isInitialized) return
        this.paths = paths
        requireSandboxed(paths)

        val jar = paths.forgeDir.resolve("forge-gui-desktop-2.0.14-jar-with-dependencies.jar")
        if (!jar.isFile) throw ForgeSandboxError("Forge 2.0.14 is not installed at ${paths.forgeDir}")

        val started = System.nanoTime()
        guiBase = SpikeGuiBase(paths.forgeDir.path + File.separator, edt)
        GuiBase.setInterface(guiBase)
        FModel.initialize(null, Function<ForgePreferences, Void?> { prefs -> tune(prefs); null })

        // Belt and braces: the constants are what Forge will actually write to.
        val userDir = File(ForgeConstants.USER_DIR).canonicalFile
        if (!userDir.startsWith(paths.sandbox.canonicalFile)) {
            throw ForgeSandboxError("Forge resolved its user dir to $userDir, outside the sandbox")
        }
        edt.startTicking(250)
        SpikeLog.info("Forge ${guiBase.currentVersion} initialised in ${(System.nanoTime() - started) / 1_000_000} ms; user dir $userDir")
    }

    /** Forge's shuffles and coin flips all draw from MyRandom (what `sim -s` sets). */
    fun seedRandom(seed: Long) {
        MyRandom.setRandom(Random(seed))
        SpikeLog.info("Forge RNG seeded with $seed")
    }

    fun loadDeck(fileName: String): Deck {
        val file = paths.decksDir.resolve(fileName)
        if (!file.isFile) throw IllegalArgumentException("no deck $file (prepareSandbox copies them)")
        return DeckSerializer.fromFile(file) ?: throw IllegalArgumentException("Forge could not parse $file")
    }

    private fun requireSandboxed(paths: SpikePaths) {
        val sandbox = paths.sandbox.canonicalFile
        for (variable in listOf("APPDATA", "LOCALAPPDATA")) {
            val value = System.getenv(variable)
                ?: throw ForgeSandboxError("$variable is unset; launch through Gradle so it points into $sandbox")
            if (!File(value).canonicalFile.startsWith(sandbox)) {
                throw ForgeSandboxError("$variable=$value is not inside $sandbox — refusing to let Forge write the real profile")
            }
        }
    }

    /** In-memory only: never saved, so the sandbox's prefs file stays Forge's defaults. */
    private fun tune(prefs: ForgePreferences) {
        prefs.setPref(FPref.UI_ENABLE_SOUNDS, "false")
        prefs.setPref(FPref.UI_ENABLE_MUSIC, "false")
        prefs.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, "false")
        prefs.setPref(FPref.UI_DISABLE_CARD_IMAGES, "true")
        prefs.setPref(FPref.CHECK_SNAPSHOT_AT_STARTUP, "false")
        prefs.setPref(FPref.PLAYER_NAME, "You")
        // The "actionable" highlight set tells the seat which hand cards are playable.
        prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "true")
        // No "you have no actions, yield?" suggestion prompts in between real ones.
        prefs.setPref(FPref.YIELD_DECLINE_SCOPE_STACK_YIELD, DeclineScope.NEVER.name)
        prefs.setPref(FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS, DeclineScope.NEVER.name)
        prefs.setPref(FPref.UI_MANA_LOST_PROMPT, "false")
        // Scry/surveil as plain choices (many/confirm) rather than Forge's card-display arranger.
        prefs.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, "false")
    }
}
