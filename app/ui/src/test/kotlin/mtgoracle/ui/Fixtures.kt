package mtgoracle.ui

import kotlinx.coroutines.flow.MutableStateFlow
import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
import mtgoracle.core.model.CombatLine
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.PlayerState
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.core.model.StackEntry
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.TrailEntry
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/** A seat with no engine behind it: the test sets board and prompt, and reads back what the UI answered. */
class FakeSeat(board: BoardState?, prompt: Prompt?, private val watching: Boolean = false) : GameSeat {
    override val showAllHands = MutableStateFlow(false)
    override val canShowAllHands: Boolean get() = watching
    override fun setShowAllHands(show: Boolean) { if (watching) showAllHands.value = show }
    override val board = MutableStateFlow(board)
    override val prompt = MutableStateFlow(prompt)
    override val stops = MutableStateFlow(PhaseStops.DEFAULT)
    override val yieldStatus = MutableStateFlow<String?>(null)
    override val warning = MutableStateFlow<String?>(null)
    val answers = mutableListOf<Pair<Long, SeatAction>>()
    val commands = mutableListOf<SeatCommand>()
    override fun answer(promptId: Long, action: SeatAction) { answers += promptId to action }
    override fun command(command: SeatCommand) { commands += command }
    override fun setStops(stops: PhaseStops) { this.stops.value = stops }
}

/** Art that is always there: a two-colour test image per kind, full cards in card proportions. */
class FakeArt(dir: File) : CardArt {
    private val full = image(File(dir, "full.png"), 488, 680, Color.makeRGB(70, 90, 130))
    private val crop = image(File(dir, "crop.png"), 626, 457, Color.makeRGB(140, 90, 60))
    override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?) = "c:$cardName"
    override fun file(key: String, kind: ArtKind) = if (kind == ArtKind.FULL) full else crop
    override fun artist(key: String) = "Test Artist"
    override fun request(key: String, kind: ArtKind, onReady: () -> Unit) = onReady()

    private fun image(file: File, w: Int, h: Int, color: Int): File {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(Color.makeRGB(20, 20, 20))
        surface.canvas.drawRect(Rect.makeXYWH(8f, 8f, w - 16f, h - 16f), Paint().apply { this.color = color })
        file.parentFile.mkdirs()
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return file
    }
}

fun card(id: Int, name: String, land: Boolean = false, creature: Boolean = false, tapped: Boolean = false, cost: String = "", type: String = "", attachedTo: Int? = null, counters: String = "") = CardState(
    id = id, name = name, manaCost = cost, typeLine = type.ifEmpty { if (land) "Land" else if (creature) "Creature" else "Instant" },
    power = if (creature) 2 else null, toughness = if (creature) 2 else null, loyalty = null, isLand = land, isCreature = creature,
    tapped = tapped, summoningSick = false, attacking = false, blocking = false, damage = 0, isToken = false, attachedToId = attachedTo,
    text = "Test text for $name.", imageKey = "c:$name", counters = counters,
)

/** A small mid-game board: you (seat) against the AI. */
fun sampleBoard(): BoardState {
    val me = PlayerState(
        id = 1, name = "You", life = 17, isAi = false, isSeat = true, hasPriority = true, hasLost = false, handCount = 3,
        hand = listOf(card(20, "Lightning Bolt", cost = "{R}"), card(21, "Counterspell", cost = "{U}{U}"), card(22, "Mountain", land = true)),
        libraryCount = 50, battlefield = listOf(
            card(10, "Mountain", land = true), card(11, "Island", land = true, tapped = true),
            card(12, "Grizzly Bears", creature = true, cost = "{1}{G}", counters = "+1/+1 x2"),
            card(13, "Rancor", cost = "{G}", type = "Enchantment — Aura", attachedTo = 12),
            card(14, "Serra Angel", creature = true, tapped = true, cost = "{3}{W}{W}"),
            card(15, "Swiftfoot Boots", cost = "{2}", type = "Artifact — Equipment", attachedTo = 14),
            card(16, "Sol Ring", cost = "{1}", type = "Artifact"),
        ),
        graveyard = listOf(card(30, "Opt", cost = "{U}"), card(31, "Brainstorm", cost = "{U}")), exile = listOf(card(32, "Ancestral Recall", cost = "{U}")), manaPool = "",
    )
    val ai = PlayerState(
        id = 2, name = "AI (Rakdos)", life = 14, isAi = true, isSeat = false, hasPriority = false, hasLost = false, handCount = 5,
        // The AI's hand as the seat receives it: backs only, stand-in ids, no names.
        hand = List(5) { CardState.back(-(it + 1)) }, libraryCount = 48,
        battlefield = listOf(card(40, "Swamp", land = true), card(41, "Hill Giant", creature = true, cost = "{3}{R}"), card(42, "Swamp", land = true, tapped = true)),
        graveyard = listOf(card(43, "Thoughtseize", cost = "{B}")), exile = listOf(CardState.back(-6)), manaPool = "",
    )
    return BoardState(
        turn = 7, phase = "Main phase, precombat", phaseKey = "MAIN1", activePlayerId = 1, activePlayerName = "You",
        players = listOf(ai, me),
        // Top first: your Counterspell answering their Bolt at your Bears.
        stack = listOf(
            StackEntry(91, "Counter target spell.", "Counterspell", "You", imageKey = "c:Counterspell", controllerId = 1,
                targetNames = listOf("Lightning Bolt")),
            StackEntry(90, "Lightning Bolt deals 3 damage to any target.", "Lightning Bolt", "AI (Rakdos)", imageKey = "c:Lightning Bolt", controllerId = 2,
                targetNames = listOf("Grizzly Bears"), targets = listOf(BoardRef.Card(12))),
        ),
        combat = listOf(CombatLine(41, "You", listOf(12))), recentLog = listOf("Turn 7 (You)", "You played Mountain"),
        gameOver = false, result = null,
        trail = listOf(
            TrailEntry(1, 2, "played Swamp", setOf(42)), TrailEntry(2, 2, "life 15→14"), TrailEntry(3, 2, "cast Lightning Bolt"),
        ),
    )
}

/** [sampleBoard] with nothing on the stack. */
fun quietBoard(): BoardState = sampleBoard().copy(stack = emptyList())

val pngDir: File get() = File(System.getProperty("mtgoracle.pngDir") ?: "build/test-png").also { it.mkdirs() }
