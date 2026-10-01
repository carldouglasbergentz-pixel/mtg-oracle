package mtgoracle.app

import mtgoracle.data.Substitutions
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.ForgeSupport

import mtgoracle.core.deck.CardPrinting
import mtgoracle.core.deck.DeckChange
import mtgoracle.core.deck.DeckExport
import mtgoracle.core.deck.DeckParser
import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.Folder
import mtgoracle.core.deck.Printing
import mtgoracle.core.lookup.Formats
import mtgoracle.data.DeckWriter
import mtgoracle.data.Library
import mtgoracle.data.LibraryWriter
import mtgoracle.data.Lookup
import mtgoracle.forge.Log
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.library.LibraryIntent
import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.Option
import mtgoracle.ui.lookup.Rendering
import mtgoracle.ui.lookup.Tone
import mtgoracle.ui.lookup.message

/**
 * The library's changes, as the screens' buttons and menus ask for them:
 * each intent becomes a question (a name, a choice, a confirmation), then
 * the write through the engine (LibraryWriter, DeckWriter), then the screen
 * read again. Nothing is written before the user has answered.
 */
/** The folder chooser's "make one now": no folder id is ever this. */
private const val NEW_FOLDER = "+new"

class LibraryActions(
    private val library: Library,
    private val libraryWriter: LibraryWriter,
    private val writer: DeckWriter,
    private val lookup: Lookup,
    private val ui: LookupUi,
    /** Read the deck list and the folders again. */
    private val refresh: () -> Unit,
    /** Deck [id] changed: read it again, and what the workspace shows of it. */
    private val deckChanged: (Int) -> Unit,
    /** Open deck [id] in the workspace. */
    private val openDeck: (Int) -> Unit,
    /** The deck open in the workspace, or null. */
    private val openDeckId: () -> Int?,
    private val leaveDeck: () -> Unit,
    private val say: (String) -> Unit,
    /** A block of text in the output pane: a replace's changes before they are made. */
    private val show: (Rendering) -> Unit,
    private val readClipboard: () -> String?,
    private val writeClipboard: (String) -> Unit,
    /** Forge's printings of a card (empty before Forge is up). */
    private val printingsOf: (String) -> List<CardPrinting> = { emptyList() },
    /** Whether a pasted printing exists (Scryfall's or Forge's); null when that can't be told yet (no printings synced). */
    private val printingKnown: (name: String, setCode: String, collectorNumber: String?) -> Boolean? = { _, _, _ -> null },
    /** A card's face in a printing, for the zoom pane while the chooser is open. */
    private val faceOf: (String, Printing?) -> CardFace? = { _, _ -> null },
    /** The AI copies' substitutions. */
    private val substitutions: Substitutions? = null,
    /** Whether Forge has a card and its AI plays it; null before Forge is up. */
    private val forgeSupport: (String) -> ForgeSupport? = { null },
) {
    fun handle(intent: LibraryIntent) {
        try {
            when (intent) {
                is LibraryIntent.NewDeck -> inFolder(intent.folderId, intent.askFolder, "New deck in") { folderId ->
                    ui.ask = Ask.Text("New deck" + folderLabel(folderId), ok = "Create") { name ->
                        act { val id = libraryWriter.createDeck(name, folderId); refresh(); openDeck(id); "created $name" + (formatOf(id)?.let { " (${formatName(it)}, the folder's default)" } ?: "") }
                    }
                }
                LibraryIntent.NewFolder -> ui.ask = Ask.Text("New folder", ok = "Create") { name ->
                    act { libraryWriter.createFolder(name); refresh(); "folder $name created" }
                }
                is LibraryIntent.RenameDeck -> ui.ask = Ask.Text("Rename ${deckName(intent.deckId)}", deckName(intent.deckId), ok = "Rename") { name ->
                    act { libraryWriter.renameDeck(intent.deckId, name); refresh(); deckChanged(intent.deckId); "renamed to $name" }
                }
                is LibraryIntent.MoveDeck -> ui.ask = Ask.Choose("Move ${deckName(intent.deckId)} to", folderOptions(), { o ->
                    val folderId = o.value.toIntOrNull()
                    if (act { libraryWriter.moveDeck(intent.deckId, folderId); refresh(); "moved to ${o.label}" }) offerFolderFormat(intent.deckId, folderId)
                })
                is LibraryIntent.DeckFormat -> ui.ask = Ask.Choose("Format of ${deckName(intent.deckId)}", formatOptions(), { o ->
                    setFormat(intent.deckId, o.value.ifEmpty { null })
                })
                is LibraryIntent.DeleteDeck -> ui.ask = Ask.Buttons("Delete ${deckName(intent.deckId)}, its considering list and its history? This cannot be undone.", listOf(
                    "Delete" to {
                        act {
                            if (openDeckId() == intent.deckId) leaveDeck()
                            val name = deckName(intent.deckId)
                            libraryWriter.deleteDeck(intent.deckId); refresh(); "deleted $name"
                        }
                    },
                ))
                is LibraryIntent.FolderFormat -> ui.ask = Ask.Choose("Default format of ${folderName(intent.folderId)} (what new decks there get)", formatOptions(), { o ->
                    val format = o.value.ifEmpty { null }
                    val folder = folderName(intent.folderId)
                    val formatless = library.decks().count { it.folderId == intent.folderId && it.format.isNullOrBlank() }
                    when {
                        format == null -> act { libraryWriter.setFolderFormat(intent.folderId, null); refresh(); "$folder: no default format" }
                        // Only a folder with decks lacking a format has anything to ask.
                        formatless == 0 -> act { libraryWriter.setFolderFormat(intent.folderId, format); refresh(); "$folder: new decks get ${o.label}" }
                        else -> ui.ask = Ask.Buttons("Default format for $folder: ${o.label}. $formatless deck(s) in the folder have no format: give them ${o.label} too?", listOf(
                            "Yes, those $formatless too" to { act { val n = libraryWriter.setFolderFormat(intent.folderId, format, applyToDecks = true); refresh(); "$folder: ${o.label}, and $n deck(s) set" } },
                            "No, only new decks" to { act { libraryWriter.setFolderFormat(intent.folderId, format); refresh(); "$folder: new decks get ${o.label}" } },
                        ))
                    }
                })
                is LibraryIntent.DeleteFolder -> {
                    val name = folderName(intent.folderId)
                    val decks = library.decks().count { it.folderId == intent.folderId }
                    ui.ask = if (decks == 0) Ask.Buttons("Delete the empty folder $name?", listOf("Delete" to { act { libraryWriter.deleteFolder(intent.folderId); refresh(); "folder $name deleted" } }))
                    else Ask.Buttons("$name holds $decks deck(s). Delete the folder and move them out of any folder? The decks stay.", listOf(
                        "Delete, keep the decks" to { act { libraryWriter.deleteFolder(intent.folderId, force = true); refresh(); "folder $name deleted; $decks deck(s) now in no folder" } },
                    ))
                }
                is LibraryIntent.Import -> inFolder(intent.folderId, intent.askFolder, "Import a deck into") { importNew(it) }
                is LibraryIntent.ImportInto -> importInto(intent.deckId)
                is LibraryIntent.Export -> ui.ask = Ask.Buttons("Export ${deckName(intent.deckId)} to the clipboard", listOf(
                    "Full names" to { export(intent.deckId, frontFace = false) },
                    "Front faces only" to { export(intent.deckId, frontFace = true) },
                    "Grouped by role" to { export(intent.deckId, frontFace = false, grouped = true) },
                ))
                is LibraryIntent.ChoosePrinting -> choosePrinting(intent)
                is LibraryIntent.AiSubstitute -> {
                    val current = library.deck(intent.deckId)?.substitutions?.firstOrNull { it.cardName.equals(intent.card, ignoreCase = true) }?.substitute
                    ui.ask = Ask.Text("The AI copy of ${deckName(intent.deckId)} plays, instead of ${intent.card}:", current.orEmpty(), ok = "Substitute") { answer ->
                        act { substitute(intent.deckId, intent.card, answer.trim()) }
                    }
                }
                is LibraryIntent.RemoveAiSubstitute -> act {
                    val store = substitutions ?: return@act "substitutions are not available here"
                    val removed = store.remove(intent.deckId, intent.card) ?: return@act "no substitution for ${intent.card} in ${deckName(intent.deckId)}"
                    deckChanged(intent.deckId)
                    "AI copy of ${deckName(intent.deckId)}: plays ${intent.card} again (not $removed)"
                }
            }
        } catch (e: DeckRefusal) {
            say(e.message ?: e.kind.name)
        }
    }

    /** One write, its outcome on the status line, a refusal as the refusal; whether it went through. */
    private fun act(write: () -> String): Boolean = try {
        say(write())
        true
    } catch (e: DeckRefusal) {
        say("refused: ${e.message}")
        false
    } catch (e: Exception) {
        Log.error("library change failed", e)
        say("failed: ${e.message}")
        false
    }

    /** [then] in the folder [folderId], or, when [ask], in the one the user picks (the given one first). */
    private fun inFolder(folderId: Int?, ask: Boolean, title: String, then: (Int?) -> Unit) {
        if (!ask) return then(folderId)
        // The folder it would go in first, then the rest, then a new one made on the spot.
        val options = folderOptions().sortedBy { if (it.value == (folderId?.toString() ?: "")) 0 else 1 } + Option("+ new folder...", NEW_FOLDER)
        ui.ask = Ask.Choose(title, options, { o ->
            if (o.value != NEW_FOLDER) then(o.value.toIntOrNull())
            else ui.ask = Ask.Text("New folder", ok = "Create") { name ->
                var made: Int? = null
                if (act { made = libraryWriter.createFolder(name); refresh(); "folder $name created" }) then(made)
            }
        })
    }

    private fun formatOf(deckId: Int): String? = library.decks().firstOrNull { it.id == deckId }?.format?.takeIf { it.isNotBlank() }

    /** A format as people say it: `duel` is Duel Commander, `canlander` Canadian Highlander. */
    private fun formatName(format: String?): String {
        if (format.isNullOrBlank()) return "no format"
        val info = lookup.formats.resolve(format) ?: return format
        return if (info.custom) info.label else Formats.displayName(info.key)
    }

    /**
     * Moved into [folderId]: a folder whose default differs from the deck's
     * own format offers it. A deck's format is its own, so it is asked, never
     * changed on the move.
     */
    private fun offerFolderFormat(deckId: Int, folderId: Int?) {
        val default = folderId?.let { id -> library.folders().firstOrNull { it.id == id }?.format }?.takeIf { it.isNotBlank() } ?: return
        val own = formatOf(deckId)
        if (own != null && Formats.fold(own) == Formats.fold(default)) return
        ui.ask = Ask.Buttons("Decks in ${folderName(folderId)} default to ${formatName(default)}. Give ${deckName(deckId)} that format? It has ${formatName(own)} now.", listOf(
            "Use ${formatName(default)}" to { setFormat(deckId, default) },
            "Keep ${formatName(own)}" to { say("${deckName(deckId)} keeps ${formatName(own)}") },
        ))
    }

    /** Sets the deck's format; a deck with commanders in a format without a command zone is asked where they go. */
    private fun setFormat(deckId: Int, format: String?) {
        if (!act { libraryWriter.setDeckFormat(deckId, format); refresh(); deckChanged(deckId); "format: ${formatName(format)}" }) return
        val info = lookup.formats.resolve(format) ?: return
        if (info.legalityKey in Formats.COMMANDER_FORMATS) return
        val commanders = library.deck(deckId)?.cards?.filter { it.isCommander && !it.isSideboard }?.map { it.name }.orEmpty()
        if (commanders.isEmpty()) return
        val who = commanders.joinToString(" and ")
        ui.ask = Ask.Buttons("${formatName(format)} has no commander. Put $who in the deck?", listOf(
            "Move to the deck" to { act { commanders.forEach { writer.promote(deckId, it, unset = true) }; deckChanged(deckId); "$who now in the deck" } },
            "Keep as commander" to { say("$who stays commander") },
        ))
    }

    private fun deckName(id: Int) = library.decks().firstOrNull { it.id == id }?.name ?: "deck #$id"
    private fun folderName(id: Int) = library.folders().firstOrNull { it.id == id }?.name ?: "folder #$id"
    private fun folderLabel(id: Int?) = id?.let { " in ${folderName(it)}" }.orEmpty()

    private fun folderOptions(): List<Option> = library.folders().map { f: Folder -> Option(f.name, f.id.toString(), f.format?.let { "default $it" }.orEmpty()) } + Option("(no folder)", "")

    /** Every format the rules know, then none: Scryfall's, and the community ones the database defines. */
    private fun formatOptions(): List<Option> {
        val custom = lookup.vocabulary["f"].filter { it !in Formats.LEGALITY }
        return (custom + Formats.LEGALITY).map { key ->
            val info = lookup.formats.resolve(key)
            Option(formatName(key), key, listOfNotNull(info?.legalityKey?.takeIf { it != key }?.let { "$it pool" }, info?.pointsBudget?.let { "$it points" }, "singleton".takeIf { info?.singleton == true }).joinToString(", "))
        } + Option("(no format: no rules)", "")
    }

    private fun parsedClipboard(): List<mtgoracle.core.deck.ParsedRow>? {
        val text = readClipboard()?.takeIf { it.isNotBlank() }
        val rows = text?.let(DeckParser::parse).orEmpty()
        if (rows.isEmpty()) { say("the clipboard holds no deck list: copy one (Moxfield, Archidekt, MTGO...) and try again"); return null }
        return rows
    }

    private fun summary(rows: List<mtgoracle.core.deck.ParsedRow>): String {
        val cards = rows.filter { it.section != "maybeboard" }.sumOf { it.quantity }
        val considering = rows.filter { it.section == "maybeboard" }.sumOf { it.quantity }
        val unknown = rows.filter { lookup.names.resolve(it.name) == null }.map { it.name }
        return "$cards card(s)" + (if (considering > 0) ", $considering considering" else "") +
            (if (unknown.isNotEmpty()) "; not found: ${unknown.joinToString(", ")}" else "") + unknownPrintings(rows)
    }

    /**
     * A pasted printing no one has (a typo in the set or number): kept on the
     * row as pasted, and drawn in the default art. Only a warning; nothing is
     * refused.
     */
    private fun unknownPrintings(rows: List<mtgoracle.core.deck.ParsedRow>): String {
        val missing = rows.filter { row ->
            val set = row.setCode ?: return@filter false
            val name = lookup.names.resolve(row.name) ?: return@filter false
            printingKnown(name, set, row.collectorNumber) == false
        }.map { "${it.name} (${it.setCode!!.uppercase()})" + (it.collectorNumber?.let { n -> " $n" } ?: "") }.distinct()
        return if (missing.isEmpty()) "" else "; ${missing.size} printing(s) not found, shown in the default art: ${missing.joinToString(", ")}"
    }

    private fun importNew(folderId: Int?) {
        val rows = parsedClipboard() ?: return
        ui.ask = Ask.Text("Import ${summary(rows)} as a new deck" + folderLabel(folderId), "Imported deck", ok = "Import") { name ->
            act {
                val (id, result) = writer.importDeck(name, folderId, null, rows)
                refresh(); openDeck(id)
                "imported $name: ${result.copies} card(s)" + (if (result.considering > 0) ", ${result.considering} considering" else "") +
                    (if (result.unresolved.isNotEmpty()) "; not found, left out: ${result.unresolved.joinToString(", ")}" else "") + unknownPrintings(rows)
            }
        }
    }

    private fun importInto(deckId: Int) {
        val rows = parsedClipboard() ?: return
        ui.ask = Ask.Buttons("Clipboard: ${summary(rows)}.", listOf(
            "Add to the deck" to {
                act {
                    val r = writer.load(deckId, rows)
                    deckChanged(deckId)
                    "added ${r.copies} card(s)" + (if (r.considering > 0) ", ${r.considering} considering" else "") +
                        (if (r.unresolved.isNotEmpty()) "; not found: ${r.unresolved.joinToString(", ")}" else "") + unknownPrintings(rows)
                }
            },
            "Replace the deck..." to { previewReplace(deckId, rows) },
        ))
    }

    /** The replace run dry first: its changes in the output, then the question. */
    private fun previewReplace(deckId: Int, rows: List<mtgoracle.core.deck.ParsedRow>) {
        val unknown = rows.filter { lookup.names.resolve(it.name) == null }.map { it.name }
        val dry = try { writer.replace(deckId, rows, force = true, dryRun = true) } catch (e: DeckRefusal) { return say("refused: ${e.message}") }
        show(diffRendering(deckName(deckId), dry.changes, dry.considering, dry.commandersKept))
        val counts = "+${dry.changes.count { it.before == 0 }} -${dry.changes.count { it.after == 0 }} ~${dry.changes.count { it.before > 0 && it.after > 0 }}"
        val title = "Replace ${deckName(deckId)} with the clipboard ($counts, listed in the output)" + (if (unknown.isNotEmpty()) "; ${unknown.size} name(s) not found are left out" else "") + unknownPrintings(rows) + "?"
        ui.ask = Ask.Buttons(title, listOf("Replace" to {
            act { val r = writer.replace(deckId, rows, force = unknown.isNotEmpty()); deckChanged(deckId); "replaced: $counts" + (r.revisionId?.let { "" } ?: " (nothing changed)") }
        }))
    }

    private fun diffRendering(deck: String, changes: List<DeckChange>, considering: Boolean, commandersKept: List<String> = emptyList()): Rendering {
        val lines = buildList {
            add("replace $deck with the clipboard would change:")
            if (changes.isEmpty()) add("  nothing: the deck is the list already")
            changes.forEach { c ->
                val what = when { c.before == 0 -> "+${c.after}"; c.after == 0 -> "-${c.before}"; c.before != c.after -> "${c.before} -> ${c.after}"; else -> "printing" }
                add("  %-10s %s (%s)".format(what, c.card, c.section.key))
            }
            if (!considering) add("  (the list has no maybeboard, so the considering list stays as it is)")
            if (commandersKept.isNotEmpty()) add("  (the list names no commander, so ${commandersKept.joinToString(", ")} stays the commander)")
        }
        return message(lines.joinToString("\n"), Tone.PLAIN)
    }

    /** [grouped]: each section under `// <role> (N)` comments, which importers skip and people read. */
    private fun export(deckId: Int, frontFace: Boolean, grouped: Boolean = false) {
        val deck = library.deck(deckId) ?: return say("no such deck")
        val pool = if (grouped) lookup.analysis.pool(deck.cards.map { it.name }) else null
        val text = DeckExport.text(deck, frontFace = frontFace, layoutOf = lookup::layout, primaryOf = pool?.let { p -> { n -> p.classify(n)?.primary } })
        writeClipboard(text)
        say("copied ${deck.name} to the clipboard: ${deck.cards.sumOf { it.quantity }} card(s)" +
            (if (frontFace) ", front faces" else "") + (if (grouped) ", grouped by role" else ""))
    }

    /**
     * The AI copy plays [substitute] for [card] (services.forge_add_substitution):
     * judged as `add` judges a card, against the deck with every substitution
     * applied ([DeckWriter.checkSwaps]), then Forge must have it and its AI
     * play it. A card's earlier substitute is replaced.
     */
    private fun substitute(deckId: Int, card: String, substitute: String): String {
        val store = substitutions ?: return "substitutions are not available here"
        if (substitute.isEmpty()) return "no substitute named: nothing changed"
        val canonical = lookup.names.resolve(card) ?: card
        val others = store.list(deckId).filter { !it.cardName.equals(canonical, ignoreCase = true) }.map { it.cardName to it.substitute }
        val (inDeck, sub) = writer.checkSwaps(deckId, others + (card to substitute)).last()
        when (forgeSupport(sub)) {
            null -> return "Forge is still loading: try again in a moment"
            ForgeSupport.UNKNOWN -> throw DeckRefusal(DeckRefusal.Kind.BAD_SUBSTITUTE, "'$sub' is unknown to Forge ${ForgeRuntime.version}")
            ForgeSupport.AI_CANT_PLAY -> throw DeckRefusal(DeckRefusal.Kind.BAD_SUBSTITUTE, "Forge's AI can't play '$sub' either (AI:RemoveDeck:All); pick another substitute")
            ForgeSupport.PLAYABLE -> {}
        }
        val replaced = store.set(deckId, inDeck, sub)
        deckChanged(deckId)
        return "AI copy of ${deckName(deckId)}: plays $sub for $inDeck" + (replaced?.let { " (was $it)" } ?: "")
    }

    private fun choosePrinting(intent: LibraryIntent.ChoosePrinting) {
        val printings = printingsOf(intent.card)
        if (printings.isEmpty()) return say("no printings of ${intent.card} known yet (Forge is still loading, or lacks the card)")
        val options = listOf(Option("default art", "")) + printings.map { p ->
            val detail = listOfNotNull(p.setName, p.date.ifEmpty { null }, p.lang.takeIf { it != "en" }, p.labels.ifEmpty { null }?.replace(",", ", "))
            Option("${p.setCode.uppercase()} ${p.collectorNumber.orEmpty()}".trim(), "${p.setCode}|${p.collectorNumber.orEmpty()}", detail.joinToString(" · "))
        }
        fun printingOf(o: Option) = o.value.takeIf { it.isNotEmpty() }?.let { Printing.of(it.substringBefore('|'), it.substringAfter('|').ifEmpty { null }) }
        ui.ask = Ask.Choose("Printing of ${intent.card}", options,
            onPick = { o -> act { writer.setPrinting(intent.deckId, intent.card, intent.section, printingOf(o)); deckChanged(intent.deckId); "${intent.card}: ${o.label}" } },
            onHover = { o -> ui.hoverFace = faceOf(intent.card, printingOf(o)) },
        )
    }
}
