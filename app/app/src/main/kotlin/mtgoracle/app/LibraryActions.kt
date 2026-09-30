package mtgoracle.app

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
    /** A card's face in a printing, for the zoom pane while the chooser is open. */
    private val faceOf: (String, Printing?) -> CardFace? = { _, _ -> null },
) {
    fun handle(intent: LibraryIntent) {
        try {
            when (intent) {
                is LibraryIntent.NewDeck -> ui.ask = Ask.Text("New deck" + folderLabel(intent.folderId), ok = "Create") { name ->
                    act { val id = libraryWriter.createDeck(name, intent.folderId); refresh(); openDeck(id); "created $name" }
                }
                LibraryIntent.NewFolder -> ui.ask = Ask.Text("New folder", ok = "Create") { name ->
                    act { libraryWriter.createFolder(name); refresh(); "folder $name created" }
                }
                is LibraryIntent.RenameDeck -> ui.ask = Ask.Text("Rename ${deckName(intent.deckId)}", deckName(intent.deckId), ok = "Rename") { name ->
                    act { libraryWriter.renameDeck(intent.deckId, name); refresh(); deckChanged(intent.deckId); "renamed to $name" }
                }
                is LibraryIntent.MoveDeck -> ui.ask = Ask.Choose("Move ${deckName(intent.deckId)} to", folderOptions(), { o ->
                    act { libraryWriter.moveDeck(intent.deckId, o.value.toIntOrNull()); refresh(); "moved to ${o.label}" }
                })
                is LibraryIntent.DeckFormat -> ui.ask = Ask.Choose("Format of ${deckName(intent.deckId)}", formatOptions(), { o ->
                    act { libraryWriter.setDeckFormat(intent.deckId, o.value.ifEmpty { null }); refresh(); deckChanged(intent.deckId); "format: ${o.label}" }
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
                is LibraryIntent.FolderFormat -> ui.ask = Ask.Choose("Default format of ${folderName(intent.folderId)}", formatOptions(), { o ->
                    val format = o.value.ifEmpty { null }
                    if (format == null) act { libraryWriter.setFolderFormat(intent.folderId, null); refresh(); "no default format" }
                    else ui.ask = Ask.Buttons("New decks in ${folderName(intent.folderId)} get ${o.label}. Its decks without a format too?", listOf(
                        "Those too" to { act { val n = libraryWriter.setFolderFormat(intent.folderId, format, applyToDecks = true); refresh(); "default ${o.label}; $n deck(s) set" } },
                        "Only new decks" to { act { libraryWriter.setFolderFormat(intent.folderId, format); refresh(); "default ${o.label}" } },
                    ))
                })
                is LibraryIntent.DeleteFolder -> {
                    val name = folderName(intent.folderId)
                    val decks = library.decks().count { it.folderId == intent.folderId }
                    ui.ask = if (decks == 0) Ask.Buttons("Delete the empty folder $name?", listOf("Delete" to { act { libraryWriter.deleteFolder(intent.folderId); refresh(); "folder $name deleted" } }))
                    else Ask.Buttons("$name holds $decks deck(s). Delete the folder and move them out of any folder? The decks stay.", listOf(
                        "Delete, keep the decks" to { act { libraryWriter.deleteFolder(intent.folderId, force = true); refresh(); "folder $name deleted; $decks deck(s) now in no folder" } },
                    ))
                }
                is LibraryIntent.Import -> importNew(intent.folderId)
                is LibraryIntent.ImportInto -> importInto(intent.deckId)
                is LibraryIntent.Export -> ui.ask = Ask.Buttons("Export ${deckName(intent.deckId)} to the clipboard", listOf(
                    "Full names" to { export(intent.deckId, frontFace = false) },
                    "Front faces only" to { export(intent.deckId, frontFace = true) },
                ))
                is LibraryIntent.ChoosePrinting -> choosePrinting(intent)
            }
        } catch (e: DeckRefusal) {
            say(e.message ?: e.kind.name)
        }
    }

    /** One write, its outcome on the status line, a refusal as the refusal. */
    private fun act(write: () -> String) {
        try {
            say(write())
        } catch (e: DeckRefusal) {
            say("refused: ${e.message}")
        } catch (e: Exception) {
            Log.error("library change failed", e)
            say("failed: ${e.message}")
        }
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
            Option(info?.label ?: key, key, listOfNotNull(info?.legalityKey?.takeIf { it != key }?.let { "$it pool" }, info?.pointsBudget?.let { "$it points" }, "singleton".takeIf { info?.singleton == true }).joinToString(", "))
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
            (if (unknown.isNotEmpty()) "; not found: ${unknown.joinToString(", ")}" else "")
    }

    private fun importNew(folderId: Int?) {
        val rows = parsedClipboard() ?: return
        ui.ask = Ask.Text("Import ${summary(rows)} as a new deck" + folderLabel(folderId), "Imported deck", ok = "Import") { name ->
            act {
                val (id, result) = writer.importDeck(name, folderId, null, rows)
                refresh(); openDeck(id)
                "imported $name: ${result.copies} card(s)" + (if (result.considering > 0) ", ${result.considering} considering" else "") +
                    (if (result.unresolved.isNotEmpty()) "; not found, left out: ${result.unresolved.joinToString(", ")}" else "")
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
                        (if (r.unresolved.isNotEmpty()) "; not found: ${r.unresolved.joinToString(", ")}" else "")
                }
            },
            "Replace the deck..." to { previewReplace(deckId, rows) },
        ))
    }

    /** The replace run dry first: its changes in the output, then the question. */
    private fun previewReplace(deckId: Int, rows: List<mtgoracle.core.deck.ParsedRow>) {
        val unknown = rows.filter { lookup.names.resolve(it.name) == null }.map { it.name }
        val dry = try { writer.replace(deckId, rows, force = true, dryRun = true) } catch (e: DeckRefusal) { return say("refused: ${e.message}") }
        show(diffRendering(deckName(deckId), dry.changes, dry.considering))
        val counts = "+${dry.changes.count { it.before == 0 }} -${dry.changes.count { it.after == 0 }} ~${dry.changes.count { it.before > 0 && it.after > 0 }}"
        val title = "Replace ${deckName(deckId)} with the clipboard ($counts, listed in the output)" + (if (unknown.isNotEmpty()) "; ${unknown.size} name(s) not found are left out" else "") + "?"
        ui.ask = Ask.Buttons(title, listOf("Replace" to {
            act { val r = writer.replace(deckId, rows, force = unknown.isNotEmpty()); deckChanged(deckId); "replaced: $counts" + (r.revisionId?.let { "" } ?: " (nothing changed)") }
        }))
    }

    private fun diffRendering(deck: String, changes: List<DeckChange>, considering: Boolean): Rendering {
        val lines = buildList {
            add("replace $deck with the clipboard would change:")
            if (changes.isEmpty()) add("  nothing: the deck is the list already")
            changes.forEach { c ->
                val what = when { c.before == 0 -> "+${c.after}"; c.after == 0 -> "-${c.before}"; c.before != c.after -> "${c.before} -> ${c.after}"; else -> "printing" }
                add("  %-10s %s (%s)".format(what, c.card, c.section.key))
            }
            if (!considering) add("  (the list has no maybeboard, so the considering list stays as it is)")
        }
        return message(lines.joinToString("\n"), Tone.PLAIN)
    }

    private fun export(deckId: Int, frontFace: Boolean) {
        val deck = library.deck(deckId) ?: return say("no such deck")
        val text = DeckExport.text(deck, frontFace = frontFace, layoutOf = lookup::layout)
        writeClipboard(text)
        say("copied ${deck.name} to the clipboard: ${deck.cards.sumOf { it.quantity }} card(s)" + if (frontFace) ", front faces" else "")
    }

    private fun choosePrinting(intent: LibraryIntent.ChoosePrinting) {
        val printings = printingsOf(intent.card)
        if (printings.isEmpty()) return say("no printings of ${intent.card} known yet (Forge is still loading, or lacks the card)")
        val options = listOf(Option("default art", "")) + printings.map { p ->
            Option("${p.setCode.uppercase()} ${p.collectorNumber.orEmpty()}".trim(), "${p.setCode}|${p.collectorNumber.orEmpty()}", "${p.setName} · ${p.date}")
        }
        fun printingOf(o: Option) = o.value.takeIf { it.isNotEmpty() }?.let { Printing.of(it.substringBefore('|'), it.substringAfter('|').ifEmpty { null }) }
        ui.ask = Ask.Choose("Printing of ${intent.card}", options,
            onPick = { o -> act { writer.setPrinting(intent.deckId, intent.card, intent.section, printingOf(o)); deckChanged(intent.deckId); "${intent.card}: ${o.label}" } },
            onHover = { o -> ui.hoverFace = faceOf(intent.card, printingOf(o)) },
        )
    }
}
