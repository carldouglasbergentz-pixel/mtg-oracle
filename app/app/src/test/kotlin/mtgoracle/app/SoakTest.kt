package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.core.play.MatchFormat
import mtgoracle.core.seat.Policy
import mtgoracle.data.DbFixture
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whole best-of-three matches with the user's own decks, as the app plays
 * them, to shake out what only a long game shows. Asked for, never part of
 * the suite: `gradlew :app:test --tests mtgoracle.app.SoakTest
 * -PsoakSeeds=1,2,3 [-PsoakDecks="Jori En:Phelia Doggo;A:B"]`.
 *
 * A scripted seat plays every land and spell it can, cracks its fetchlands,
 * casts its commander and attacks with everything. Afterwards each match's
 * game log is read for what should never happen:
 *  - **miscount**: Forge's count of a player's earlier spells this turn
 *    differs from the casts the log has (Jori En and Cori-Steel Cutter missed
 *    a second spell in a real game because Forge counted one too many);
 *  - **unhandled**: a decision our seat answered itself (`UNHANDLED`);
 *  - **stall**: the match stopped moving.
 * Reported, not failed on (they are often the script's doing): clicks Forge
 * ignored, and decisions that could not be made by clicking the board.
 * Everything goes to `build/soak/`, a report per match and a summary.
 */
class SoakTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private val out = File(System.getProperty("mtgoracle.pngDir")).resolveSibling("soak").also { it.mkdirs() }
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    /** Cards already clicked this turn ("turn:id"): a spell it can't pay for is tried once (and cancelled), a fetchland cracked once. */
    private val tried = mutableSetOf<String>()
    /** Answers given to each Forge Input (by its serial): past [LOOP] the seat gives up on it. */
    private val answers = HashMap<Int, Int>()
    private val LOOP = 8

    private val aggressive: Policy = policy@{ prompt, board, _ ->
        val me = board.seat ?: return@policy null
        if (prompt is InputPrompt) {
            // A loop breaker: Fire's divided targets toggled the same target forever.
            val n = answers.merge(prompt.inputSerial, 1, Int::plus)!!
            if (n > LOOP) return@policy if (prompt.okEnabled) SeatAction.Ok else if (prompt.cancelEnabled) SeatAction.Cancel else SeatAction.Ok
        }
        val turn = (prompt as? InputPrompt)?.let { Regex("""Turn: (\d+) \(You\)""").find(it.message)?.groupValues?.get(1) }
        when {
            prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && turn != null && "Main phase" in prompt.message && board.stack.isEmpty() -> {
                val act = prompt.actionableCardIds
                val candidates = me.hand.filter { it.id in act }.sortedByDescending { it.isLand } +
                    me.command.filter { it.id in act } + me.battlefield.filter { it.id in act && it.isLand }
                candidates.firstOrNull { "$turn:${it.id}" !in tried }?.let { card ->
                    tried += "$turn:${card.id}"
                    SeatAction.ClickCard(card.id)
                } ?: SeatAction.Ok
            }
            prompt is InputPrompt && prompt.kind == InputKind.ATTACK && prompt.cancelLabel == "Alpha Strike" -> SeatAction.Cancel
            prompt is InputPrompt && prompt.kind == InputKind.ATTACK -> SeatAction.Ok
            prompt is ChoicePrompt && "Play land" in prompt.labels -> SeatAction.Choose(listOf(prompt.labels.indexOf("Play land")))
            // "As True-Name Nemesis enters, choose a player": no card to pick, no button on; a person clicks the player.
            prompt is InputPrompt && prompt.kind == InputKind.SELECT_CARDS && prompt.selectableCardIds.isEmpty() && !prompt.okEnabled && !prompt.cancelEnabled ->
                board.players.firstOrNull { !it.isSeat }?.let { SeatAction.ClickPlayer(it.id) }
            else -> null
        }
    }

    /** What a match's game log says went wrong, by kind. */
    private fun findings(log: File): Map<String, List<String>> {
        val cast = HashMap<String, Int>()
        val found = linkedMapOf<String, MutableList<String>>()
        fun add(kind: String, line: String) { found.getOrPut(kind) { mutableListOf() } += line.trim() }
        log.forEachLine { line ->
            when {
                " LOG   Turn: Turn " in line -> cast.clear()
                " LOG   Add To Stack: " in line && " cast " in line -> {
                    val who = line.substringAfter("Add To Stack: ").substringBefore(" cast ")
                    cast[who] = (cast[who] ?: 0) + 1
                }
                " NOTE  " in line && ": earlier spells this turn, as Forge counts them: " in line -> {
                    val who = line.substringAfter(" NOTE  ").substringBefore(": earlier")
                    val forge = line.substringAfter("counts them: ").substringBefore(" ").toInt()
                    val logged = (cast[who] ?: 0) - 1
                    if (forge != logged) add("miscount", "$line   <- the log has $logged earlier")
                }
                "UNHANDLED" in line -> add("unhandled", line)
                "ignored the click" in line -> add("ignored click", line)
                "UI-FALLBACK" in line -> add("not clickable", line.take(200))
            }
        }
        return found
    }

    @Test
    fun `whole matches with the user's decks - Forge's counts hold and nothing is left unhandled`() {
        val seeds = System.getProperty("mtgoracle.soakSeeds")?.split(',')?.map { it.trim().toLong() }
        assumeTrue(seeds != null, "asked for with -PsoakSeeds=...")
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
        fun deck(name: String) = app.decks.firstOrNull { it.name == name }?.let { app.deckById(it.id) } ?: error("no deck named $name")
        val pairs = (System.getProperty("mtgoracle.soakDecks") ?: "Jori En:Phelia Doggo").split(';').map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val failing = mutableListOf<String>()
        val summary = StringBuilder()
        for ((mine, theirs) in pairs) for (seed in seeds!!) {
            val prepared = Sessions(null, data).prepare(deck(mine), deck(theirs), useAiCopy = true)
            val name = "soak-${mine}-vs-${theirs}-$seed".replace(Regex("[^A-Za-z0-9-]+"), "_")
            tried.clear(); answers.clear()
            var stall: String? = null
            Scenario(name, null, seatDeck = prepared.seat, opponentDeck = prepared.opponent, policy = aggressive, seed = seed, format = MatchFormat.BO3).use { s ->
                try {
                    var game = 1
                    while (true) {
                        s.playUntil(timeoutMillis = 600_000, anyFallbacks = true) { s.match.games.value.size >= game || (s.match.seat.board.value?.turn ?: 0) > 40 }
                        // The result is recorded a moment after the board says game over.
                        if (s.match.seat.board.value?.gameOver == true) s.waitFor(30_000) { s.match.games.value.size >= game }
                        if (s.match.games.value.size < game || s.match.games.value.last().matchOver) break
                        game++
                        s.match.continueMatch()
                        // Between games the old board still says game over, which stops a scripted seat: answer the sideboard (as it is) and play-or-draw here.
                        s.waitFor(120_000) {
                            when (val p = s.match.seat.prompt.value) {
                                is SideboardPrompt -> s.match.seat.answer(p.id, SeatAction.Sideboard(p.main.associate { it.name to it.count }))
                                is InputPrompt -> if (p.kind == InputKind.CONFIRM) s.match.seat.answer(p.id, SeatAction.Ok)
                                else -> {}
                            }
                            s.match.seat.board.value?.gameOver == false
                        }
                    }
                } catch (e: IllegalStateException) {
                    stall = e.message?.lineSequence()?.first()
                }
                val found = findings(s.log).toMutableMap()
                stall?.let { found["stall"] = listOf(it) }
                val casts = s.log.readLines().count { "earlier spells this turn" in it }
                val line = "$mine vs $theirs, seed $seed: ${s.match.games.value.size} game(s), $casts casts checked" +
                    found.entries.joinToString("") { (k, v) -> ", ${v.size} $k" }
                println(line)
                summary.appendLine(line)
                File(out, "$name.txt").writeText(line + "\n" + found.entries.joinToString("\n") { (k, v) -> "\n== $k\n" + v.joinToString("\n") } +
                    "\n\nlog: ${s.log.absolutePath}\n")
                s.log.copyTo(File(out, "$name.log"), overwrite = true)
                failing += listOf("miscount", "unhandled", "stall").flatMap { k -> found[k].orEmpty().map { "$name: $k: $it" } }
            }
        }
        File(out, "summary.txt").writeText(summary.toString())
        assertEquals(emptyList(), failing, "see ${out.absolutePath}")
    }
}
