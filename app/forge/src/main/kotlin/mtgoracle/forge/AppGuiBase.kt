package mtgoracle.forge

import forge.gamemodes.match.HostedMatch
import forge.gui.download.GuiDownloadService
import forge.gui.interfaces.IGuiBase
import forge.gui.interfaces.IGuiGame
import forge.item.PaperCard
import forge.localinstance.skin.FSkinProp
import forge.localinstance.skin.ISkinImage
import forge.sound.IAudioClip
import forge.sound.IAudioMusic
import forge.util.BuildInfo
import forge.util.FSerializableFunction
import forge.util.ImageFetcher
import org.jupnp.UpnpServiceConfiguration
import java.io.File
import java.util.function.Consumer

/** No match is being watched: Forge asking for a spectator GUI now is a bug. */
private val NO_SPECTATOR: () -> IGuiGame = { error("no spectator GUI registered") }

/**
 * Forge's process-wide GUI (`GuiBase.setInterface`): threads, paths, the image
 * fetcher, and the handful of app-level dialogs. Everything else visual is a
 * no-op — we draw our own board from the game view, not Forge's skins or sounds.
 *
 * The app-level dialogs here are *not* the per-game prompts (those are
 * [SeatGui]), but Forge's static helpers do route game decisions through
 * them. While people play, they become the prompts of the seat Forge asked
 * last; otherwise they take Forge's default and say so (`UNHANDLED`, and the
 * board's warning line).
 */
class AppGuiBase(
    private val assetsDir: String,
    private val edt: ForgeEdt,
    private val imageFetcher: ImageFetcher,
) : IGuiBase {
    /** HostedMatch asks for a GUI when a game has no human: that is our spectator seat. */
    @Volatile var spectatorFactory: () -> IGuiGame = NO_SPECTATOR

    /** Forgets [gui] as the spectator GUI once its match is over, so the old seat isn't kept alive. */
    fun releaseSpectator(gui: IGuiGame) {
        val current = spectatorFactory
        if (current !== NO_SPECTATOR && runCatching { current() }.getOrNull() === gui) spectatorFactory = NO_SPECTATOR
    }

    override fun isRunningOnDesktop() = true
    override fun isLibgdxPort() = false
    override fun getCurrentVersion(): String = BuildInfo.getVersionString()
    override fun getAssetsDir() = assetsDir

    override fun invokeInEdtNow(runnable: Runnable) = runnable.run()
    override fun invokeInEdtLater(runnable: Runnable) = edt.later(runnable)
    override fun invokeInEdtAndWait(proc: Runnable) = edt.andWait(proc)
    override fun runBackgroundTask(message: String?, task: Runnable) {
        Thread(task, "forge-background").apply { isDaemon = true }.start()
    }
    override fun isGuiThread() = edt.isCurrent()

    override fun getImageFetcher(): ImageFetcher = imageFetcher
    override fun getSkinIcon(skinProp: FSkinProp?): ISkinImage? = null
    override fun getUnskinnedIcon(path: String?): ISkinImage? = null
    override fun getCardArt(card: PaperCard?, backFace: Boolean): ISkinImage? = null
    override fun createLayeredImage(card: PaperCard?, background: FSkinProp?, overlayFilename: String?, opacity: Float): ISkinImage? = null
    override fun clearImageCache() {}
    override fun encodeSymbols(str: String, formatReminderText: Boolean): String = str

    override fun getAvatarCount() = 0
    override fun getSleevesCount() = 0
    override fun getScreenScale() = 1f
    override fun preventSystemSleep(preventSleep: Boolean) {}
    override fun download(service: GuiDownloadService?, callback: Consumer<Boolean>?) { callback?.accept(false) }
    override fun copyToClipboard(text: String?) {}
    override fun browseToUrl(url: String?) {}

    override fun showCardList(title: String?, message: String?, list: MutableList<PaperCard>?) = unhandled("showCardList", title)
    override fun showBoxedProduct(title: String?, message: String?, list: MutableList<PaperCard>?): Boolean {
        unhandled("showBoxedProduct", title); return false
    }
    override fun showBugReportDialog(title: String?, text: String?, showExitAppBtn: Boolean) {
        // Forge's BugReporter lands here with a stack trace: it must be loud — logged, and on the board.
        Log.error("Forge bug report: $title\n$text")
        onBugReport?.invoke(title ?: "Forge reported an error", text.orEmpty())
    }

    /** Set by the app: a Forge error report reaches the board's error line (the trace is in the app log). */
    @Volatile var onBugReport: ((title: String, text: String) -> Unit)? = null
    // Forge's achievement pop-up (its name over what it is for): nothing to answer; said in the game's log and the
    // result panel (SeatGui.achievement).
    override fun showImageDialog(image: ISkinImage?, message: String?, title: String?) {
        Log.info("Forge shows: ${listOfNotNull(title, message).joinToString(": ")}")
        message?.let { seats.firstOrNull()?.achievement(it) }
    }
    // Forge's static dialogs (GuiChoose, SOptionPane) land here, outside any game's GUI. During a game a
    // person is playing they go to that seat's prompts; otherwise they answer with Forge's default, loudly.
    override fun showOptionDialog(message: String?, title: String?, icon: FSkinProp?, options: MutableList<String>?, defaultOption: Int): Int {
        human()?.let { if (options != null) return it.showOptionDialog(message.orEmpty(), title, icon, options, defaultOption) }
        unhandled("showOptionDialog", "$title: $message -> default ${options?.getOrNull(defaultOption)}")
        return defaultOption
    }
    override fun showInputDialog(message: String?, title: String?, icon: FSkinProp?, initialInput: String?, inputOptions: MutableList<String>?, isNumeric: Boolean): String? {
        human()?.let { return it.showInputDialog(message, title, icon, initialInput, inputOptions, isNumeric) }
        unhandled("showInputDialog", "$title: $message -> $initialInput")
        return initialInput
    }
    override fun showFileDialog(title: String?, defaultDir: String?): String? = null
    override fun getSaveFile(defaultFile: File?): File? = null
    override fun <T : Any?> order(title: String?, top: String?, remainingObjectsMin: Int, remainingObjectsMax: Int, sourceChoices: MutableList<T>, destChoices: MutableList<T>?): MutableList<T> {
        human()?.let { return it.order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, null, false, false).ordered() }
        unhandled("order", "$title -> as given")
        return sourceChoices
    }
    override fun <T : Any?> getChoices(message: String?, min: Int, max: Int, choices: MutableCollection<T>, selected: MutableCollection<T>?, display: FSerializableFunction<T, String>?): MutableList<T> {
        human()?.let { return it.getChoices(message.orEmpty(), min, max, choices.toMutableList(), selected?.toMutableList(), display) }
        unhandled("getChoices", "$message -> first $min")
        return choices.take(maxOf(min, 0)).toMutableList()
    }
    override fun chooseCard(title: String?, message: String?, list: MutableList<PaperCard>): PaperCard? {
        human()?.let { return it.getChoices(listOfNotNull(title, message).joinToString(": "), 1, 1, list, null, null).firstOrNull() }
        unhandled("chooseCard", "$title -> ${list.firstOrNull()}"); return list.firstOrNull()
    }

    /**
     * The seats of the game in progress, the host's first (its achievements are
     * Forge's, kept in the host's profile); two when two people play. Set by ForgeMatch.
     */
    @Volatile var seats: List<SeatGui> = emptyList()

    /**
     * The person a static dialog is for: of the people at the table, the one
     * Forge asked last. The only static dialog a game reaches (a cost's "from
     * whose zone", HumanCostDecision) comes in the middle of the payer's own decision.
     */
    private fun human(): SeatGui? = seats.filter { it.isHumanSeat }.maxByOrNull { it.lastAskedAt }

    override fun isSupportedAudioFormat(file: File?) = false
    override fun createAudioClip(filename: String?): IAudioClip? = null
    override fun createAudioMusic(filename: String?): IAudioMusic? = null
    override fun startAltSoundSystem(filename: String?, isSynchronized: Boolean) {}

    override fun showSpellShop() {}
    override fun showBazaar() {}

    override fun getNewGuiGame(): IGuiGame = spectatorFactory()
    override fun hostMatch() = HostedMatch()

    override fun getUpnpPlatformService(): UpnpServiceConfiguration? = null
    override fun hasNetGame() = false

    /** Loud: in the game's log and on its board when there is one, the app log always. */
    private fun unhandled(method: String, detail: String?) {
        (human() ?: seats.firstOrNull())?.autoAnswered("IGuiBase.$method", detail.orEmpty()) ?: Log.warn("UNHANDLED IGuiBase.$method: $detail")
    }
}
