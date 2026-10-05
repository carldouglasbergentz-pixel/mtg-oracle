package mtgoracle.app

import mtgoracle.core.library.DeckAction
import mtgoracle.core.library.ImportChoice
import mtgoracle.core.library.ImportPlan
import mtgoracle.core.library.LibraryPackage
import mtgoracle.core.library.PackageManifest
import mtgoracle.core.library.PackageScope
import mtgoracle.data.PackageFile
import mtgoracle.data.PackageRefused
import mtgoracle.data.PackageStore
import mtgoracle.forge.Log
import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.LookupUi
import java.io.File
import java.time.LocalDate

/**
 * The library's `.mtgoracle` packages: an export saved to [exports], and an
 * import asked before it is written. A package comes from a path on the
 * clipboard (the library's Import) or dropped in [imports], which is looked
 * through at start and after a sync; a dropped one goes to `done/` once
 * imported, or `skipped/`, and "Later" leaves it for the next look.
 *
 * The question says what the package holds and what of it is already here,
 * names the cards this database lacks, and lets the history, the games and
 * the combos be left out (decks always come).
 */
class PackageActions(
    private val store: PackageStore,
    private val ui: LookupUi,
    private val say: (String) -> Unit,
    /** The library changed: read it again. */
    private val refresh: () -> Unit,
    private val app: String,
    private val exports: File?,
    private val imports: File?,
    private val backups: File?,
) {
    /** Dropped packages answered "Later" since the last look: not asked again until the next one. */
    private val later = mutableSetOf<String>()

    fun export(scope: PackageScope) {
        val dir = exports ?: return say("exports are not available here")
        try {
            val pkg = store.export(scope, app, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
            val base = pkg.manifest.scope.removePrefix("deck ").removePrefix("folder ").replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val file = generateSequence(1) { it + 1 }.map { n -> File(dir, "$base-${LocalDate.now()}${if (n > 1) "-$n" else ""}${PackageManifest.FILE_SUFFIX}") }.first { !it.exists() }
            PackageFile.write(file, pkg)
            say("exported ${pkg.manifest.scope}: ${pkg.decks.size} deck(s), ${pkg.games.size} game(s), ${pkg.combos.size} combo(s) to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.error("package export failed", e)
            say("export failed: ${e.message}")
        }
    }

    /** A clipboard's text (or file) naming a package: its file, else null (a deck list, say). */
    fun packageNamedBy(text: String?): File? {
        val path = text?.trim()?.removeSurrounding("\"")?.takeIf { it.endsWith(PackageManifest.FILE_SUFFIX, ignoreCase = true) && '\n' !in it } ?: return null
        return File(path).takeIf { it.isFile }
    }

    /** The packages waiting in [imports], one question at a time; [afterSync] asks again those left for later. */
    fun lookForDropped(afterSync: Boolean = false) {
        val dir = imports ?: return
        if (afterSync) later.clear()
        if (ui.ask != null) return // one question at a time: the next look asks
        val next = dir.listFiles { f -> f.isFile && f.name.endsWith(PackageManifest.FILE_SUFFIX, ignoreCase = true) }.orEmpty()
            .sortedBy { it.name }.firstOrNull { it.name !in later } ?: return
        offer(next, dropped = true)
    }

    /** [file]'s contents and what would become of them, then the question; nothing is written before the answer. */
    fun offer(file: File, dropped: Boolean = false) {
        val pkg = try { PackageFile.read(file) } catch (e: PackageRefused) {
            say("refused: ${e.message}")
            if (dropped) moveTo(file, "skipped").also { lookForDropped() }
            return
        }
        val plan = try { store.plan(pkg) } catch (e: Exception) {
            Log.error("package plan failed", e)
            return say("the package could not be read against the library: ${e.message}")
        }
        ask(file, pkg, plan, ImportChoice(), dropped)
    }

    private fun ask(file: File, pkg: LibraryPackage, plan: ImportPlan, choice: ImportChoice, dropped: Boolean) {
        fun box(on: Boolean) = if (on) "[x]" else "[ ]"
        val buttons = buildList<Pair<String, () -> Unit>> {
            add((if (plan.missingCards.isEmpty()) "Import" else "Import now") to { import(file, pkg, choice, dropped) })
            if (plan.revisions > 0) add("${box(choice.history)} history (${plan.revisions})" to { ask(file, pkg, plan, choice.copy(history = !choice.history), dropped) })
            if (pkg.games.isNotEmpty()) add("${box(choice.games)} games (${plan.newGames} new)" to { ask(file, pkg, plan, choice.copy(games = !choice.games), dropped) })
            if (pkg.combos.isNotEmpty()) add("${box(choice.combos)} combos (${plan.newCombos} new)" to { ask(file, pkg, plan, choice.copy(combos = !choice.combos), dropped) })
            if (dropped) {
                add((if (plan.missingCards.isEmpty()) "Later" else "After the next sync") to { later += file.name; say("${file.name} waits in ${imports?.path}"); lookForDropped() })
                add("Skip" to { moveTo(file, "skipped"); say("${file.name} skipped: moved to ${File(imports, "skipped")}"); lookForDropped() })
            }
        }
        ui.ask = Ask.Buttons(title(file, pkg, plan), buttons)
    }

    /** What the package holds and what would become of it, in a sentence or three. */
    private fun title(file: File, pkg: LibraryPackage, plan: ImportPlan): String {
        val renamed = plan.decks.filter { it.action == DeckAction.RENAMED }
        val same = plan.decks.count { it.action == DeckAction.SAME }
        val decks = plan.decks.filter { it.action == DeckAction.NEW }.map { it.name } + renamed.map { "${it.name} (another deck of its name is here)" }
        val parts = listOfNotNull(
            "${file.name} (${pkg.manifest.scope}, from ${pkg.manifest.app}): ${decks.size} deck(s) to import" + (if (decks.isEmpty()) "" else ": ${decks.joinToString(", ")}"),
            "$same already here, the same".takeIf { same > 0 },
            "${pkg.games.size} game(s), ${plan.knownGames} already here".takeIf { pkg.games.isNotEmpty() },
            "${pkg.combos.size} combo(s), ${plan.knownCombos} already here".takeIf { pkg.combos.isNotEmpty() },
        )
        val missing = plan.missingCards.takeIf { it.isNotEmpty() }?.let { m ->
            " ${m.size} card(s) are not in this database yet (${m.take(5).joinToString(", ")}${if (m.size > 5) " …" else ""}): a sync may bring them; imported now, they keep their names."
        }.orEmpty()
        return parts.joinToString("; ") + "." + missing + " Nothing here is overwritten."
    }

    private fun import(file: File, pkg: LibraryPackage, choice: ImportChoice, dropped: Boolean) {
        try {
            val r = store.import(pkg, choice, backups)
            refresh()
            if (dropped) moveTo(file, "done")
            say(listOfNotNull(
                "imported ${r.decks.size} deck(s)" + (if (r.decks.isEmpty()) "" else ": ${r.decks.joinToString(", ")}"),
                "${r.skippedDecks} already here".takeIf { r.skippedDecks > 0 },
                "${r.games} game(s)".takeIf { choice.games },
                "${r.combos} combo(s)".takeIf { choice.combos },
                r.backup?.let { "backup $it" },
            ).joinToString(" · "))
        } catch (e: Exception) {
            Log.error("package import failed", e)
            say("import failed, nothing was written: ${e.message}")
        }
        if (dropped) lookForDropped()
    }

    private fun moveTo(file: File, sub: String) {
        val dir = File(file.parentFile, sub).also { it.mkdirs() }
        val target = generateSequence(0) { it + 1 }.map { n -> File(dir, if (n == 0) file.name else "${file.nameWithoutExtension}-$n.${file.extension}") }.first { !it.exists() }
        if (!file.renameTo(target)) Log.warn("could not move ${file.name} to $dir")
    }
}
