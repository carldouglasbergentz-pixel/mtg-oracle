package mtgoracle.forge

import forge.gamemodes.match.DeclineScope
import forge.gui.GuiBase
import forge.localinstance.properties.ForgeConstants
import forge.localinstance.properties.ForgePreferences
import forge.localinstance.properties.ForgePreferences.FPref
import forge.model.FModel
import forge.util.BuildInfo
import forge.util.MyRandom
import java.io.File
import java.util.Random
import java.util.function.Function

/**
 * Where Forge reads and writes.
 *
 * [assets] is ours: a copy of the install's `res/` (the build's
 * prepareForgeAssets task) plus the `forge.profile.properties` written here.
 * [home] is where Forge's user data (preferences, logs) and image cache go.
 */
data class ForgeSetup(val assets: File, val home: File) {
    val userDir: File get() = home.resolve("user")
    val cacheDir: File get() = home.resolve("cache")
}

class ForgeSetupError(message: String) : IllegalStateException(message)

/**
 * Brings Forge up in this process, exactly once.
 *
 * Forge resolves `res/` and `forge.profile.properties` against the assets
 * directory the GUI reports ([AppGuiBase.getAssetsDir]). Pointing that at our
 * own directory, with our own profile, keeps Forge's user data under [ForgeSetup.home]
 * without redirecting APPDATA — and leaves the install in tools/forge (whose
 * profile the user's own Forge GUI reads) untouched. The user's real
 * %APPDATA%\Forge is refused outright.
 */
object ForgeRuntime {
    val edt = ForgeEdt()
    lateinit var guiBase: AppGuiBase
        private set
    lateinit var setup: ForgeSetup
        private set
    lateinit var images: ForgeImages
        private set

    val version: String get() = BuildInfo.getVersionString()
    val isInitialised: Boolean get() = this::guiBase.isInitialized

    /** A match holds Forge's one seat: a game or a simulated game is on. */
    val busy: Boolean get() = isInitialised && guiBase.seats.isNotEmpty()

    @Synchronized
    fun initialise(setup: ForgeSetup) {
        if (isInitialised) {
            check(setup == this.setup) { "Forge is already running with $this.setup" }
            return
        }
        this.setup = setup
        requireOwnProfile(setup)
        writeProfile(setup)

        val started = System.nanoTime()
        HandlerFailures.install()
        images = ForgeImages(edt)
        guiBase = AppGuiBase(setup.assets.path + File.separator, edt, images.fetcher)
        GuiBase.setInterface(guiBase)
        FModel.initialize(null, Function<ForgePreferences, Void?> { prefs -> tune(prefs); null })

        // What Forge actually resolved is what it will write to.
        for ((what, dir) in listOf("user dir" to ForgeConstants.USER_DIR, "cache dir" to ForgeConstants.CACHE_DIR)) {
            val resolved = File(dir).canonicalFile
            if (!resolved.startsWith(setup.home.canonicalFile)) {
                throw ForgeSetupError("Forge resolved its $what to $resolved, outside ${setup.home}")
            }
        }
        edt.startTicking(250)
        Log.info("Forge $version initialised in ${(System.nanoTime() - started) / 1_000_000} ms; user data in ${setup.home}")
    }

    /** Where Forge's own error reports go (the app shows them on the board); their traces are in the log too. */
    fun onForgeError(handler: (title: String, text: String) -> Unit) { guiBase.onBugReport = handler }

    /** Forge's shuffles and coin flips all draw from MyRandom (what `sim -s` sets). */
    fun seedRandom(seed: Long) {
        MyRandom.setRandom(Random(seed))
    }

    private fun requireOwnProfile(setup: ForgeSetup) {
        val res = setup.assets.resolve("res/cardsfolder/cardsfolder.zip")
        if (!res.isFile) throw ForgeSetupError("no Forge res/ in ${setup.assets}; the build's prepareForgeAssets task copies it from tools/forge")
        val home = setup.home.canonicalFile
        for (variable in listOf("APPDATA", "LOCALAPPDATA")) {
            val real = System.getenv(variable)?.let { File(it, "Forge").canonicalFile } ?: continue
            if (home.startsWith(real) || real.startsWith(home)) {
                throw ForgeSetupError("$home overlaps the real Forge profile $real; refusing to let Forge write there")
            }
        }
    }

    /** Forward slashes: the file is a Java properties file, where a backslash escapes. */
    private fun writeProfile(setup: ForgeSetup) {
        fun dir(f: File) = f.canonicalPath.replace('\\', '/') + "/"
        setup.userDir.mkdirs()
        setup.cacheDir.mkdirs()
        setup.assets.resolve("forge.profile.properties").writeText(
            """
            # Written by MTG Oracle at start-up (mtgoracle.forge.ForgeRuntime); edits are overwritten.
            userDir=${dir(setup.userDir)}
            cacheDir=${dir(setup.cacheDir)}
            decksDir=${dir(setup.userDir.resolve("decks"))}
            """.trimIndent() + "\n",
        )
    }

    /** In-memory only: never saved, so the preferences file stays Forge's defaults. */
    private fun tune(prefs: ForgePreferences) {
        prefs.setPref(FPref.UI_ENABLE_SOUNDS, "false")
        prefs.setPref(FPref.UI_ENABLE_MUSIC, "false")
        prefs.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, "true")
        prefs.setPref(FPref.UI_DISABLE_CARD_IMAGES, "false")
        prefs.setPref(FPref.CHECK_SNAPSHOT_AT_STARTUP, "false")
        prefs.setPref(FPref.PLAYER_NAME, "You")
        // The "actionable" highlight set tells the seat which hand cards are playable.
        prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, "true")
        // No "you have no actions, yield?" suggestion prompts in between real ones.
        prefs.setPref(FPref.YIELD_DECLINE_SCOPE_STACK_YIELD, DeclineScope.NEVER.name)
        prefs.setPref(FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS, DeclineScope.NEVER.name)
        prefs.setPref(FPref.UI_MANA_LOST_PROMPT, "false")
        // Scry/surveil as plain choices (many/order) rather than Forge's card-display arranger.
        prefs.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, "false")
        // F4 ("done for the turn, but stop if they act"): what counts as acting.
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL, "true")
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_ATTACKERS, "true")
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_TARGETING, "true")
        // MTGO: anything the opponent puts on the stack stops you, even when you have nothing to respond with.
        prefs.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, "false")
        prefs.setPref(FPref.MATCH_EXPERIMENTAL_RESTORE, "false")
    }
}
