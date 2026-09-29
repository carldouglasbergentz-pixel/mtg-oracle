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

/**
 * Forge's process-wide GUI (`GuiBase.setInterface`): threads, paths, and the
 * handful of app-level dialogs. Everything visual is a no-op — we draw our
 * own board from the game view, not Forge's skins, images or sounds.
 *
 * The app-level dialogs here are *not* the per-game prompts (those are
 * [SeatGui]); if Forge ever routes a real game decision through them we want
 * to know, so each one logs `UNHANDLED`.
 */
class SpikeGuiBase(
    private val assetsDir: String,
    private val edt: ForgeEdt,
) : IGuiBase {
    /** HostedMatch asks for a GUI when a game has no human: that is our spectator seat. */
    @Volatile var spectatorFactory: () -> IGuiGame = { error("no spectator GUI registered") }

    private val noImages = object : ImageFetcher() {
        override fun getDownloadTask(urls: Array<String>, destPath: String, notifyObservers: Runnable): Runnable =
            Runnable { }
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

    override fun getImageFetcher(): ImageFetcher = noImages
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
        // Forge's BugReporter lands here with a stack trace: it must be loud.
        SpikeLog.error("Forge bug report: $title\n$text")
    }
    override fun showImageDialog(image: ISkinImage?, message: String?, title: String?) = unhandled("showImageDialog", message)
    override fun showOptionDialog(message: String?, title: String?, icon: FSkinProp?, options: MutableList<String>?, defaultOption: Int): Int {
        unhandled("showOptionDialog", "$title: $message -> default ${options?.getOrNull(defaultOption)}")
        return defaultOption
    }
    override fun showInputDialog(message: String?, title: String?, icon: FSkinProp?, initialInput: String?, inputOptions: MutableList<String>?, isNumeric: Boolean): String? {
        unhandled("showInputDialog", "$title: $message -> $initialInput")
        return initialInput
    }
    override fun showFileDialog(title: String?, defaultDir: String?): String? = null
    override fun getSaveFile(defaultFile: File?): File? = null
    override fun <T : Any?> order(title: String?, top: String?, remainingObjectsMin: Int, remainingObjectsMax: Int, sourceChoices: MutableList<T>, destChoices: MutableList<T>?): MutableList<T> {
        unhandled("order", title)
        return sourceChoices
    }
    override fun <T : Any?> getChoices(message: String?, min: Int, max: Int, choices: MutableCollection<T>, selected: MutableCollection<T>?, display: FSerializableFunction<T, String>?): MutableList<T> {
        unhandled("getChoices", "$message -> first $min")
        return choices.take(maxOf(min, 0)).toMutableList()
    }
    override fun chooseCard(title: String?, message: String?, list: MutableList<PaperCard>): PaperCard? {
        unhandled("chooseCard", title); return list.firstOrNull()
    }

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

    private fun unhandled(method: String, detail: String?) {
        SpikeLog.warn("UNHANDLED IGuiBase.$method: $detail")
    }
}
