package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.play.GameMode
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.Log
import mtgoracle.ui.kit.CardMode
import java.io.File

/**
 * `snapshots` mode: the StagedBoards played in real games and rendered with
 * real art — the review pictures of the table layout and hidden information.
 * Writes nothing but PNGs and logs under [out] (and art into the image cache).
 */
object StagedPictures {
    fun run(paths: AppPaths, out: File, only: Set<String> = emptySet()): Int {
        fun wanted(name: String) = only.isEmpty() || name in only
        ForgeRuntime.initialise(paths.forge)
        val staged = listOf(StagedBoards.table, StagedBoards.hiddenInfo, StagedBoards.reveal, StagedBoards.watch,
            StagedBoards.lands, StagedBoards.fetch, StagedBoards.bolt, StagedBoards.crowded, StagedBoards.crowdedSide)
        val names = staged.flatten().filter { it.substringBefore('=').let { k -> k.endsWith("hand") || k.endsWith("battlefield") || k.endsWith("graveyard") || k.endsWith("exile") } }
            .flatMap { it.substringAfter('=').split(';') }.map { it.substringBefore('|') }.filter { it.isNotBlank() }.distinct()
        val keys = names.mapNotNull { ForgeRuntime.images.keyFor(it, null, null) }
        Log.info("art for ${keys.size} staged cards: ${ForgeRuntime.images.prefetch(keys)} images on disk")
        val logs = File(out, "logs")
        fun game(name: String, state: List<String>, mode: GameMode = GameMode.HUMAN_VS_AI, policy: mtgoracle.core.seat.Policy = { _, _, _ -> null }) =
            StagedGame(name, state, logs, out, CardMode.ART, mode, ForgeRuntime.images, policy)
        fun ourPriority(g: StagedGame) = (g.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY && g.board.activePlayerId == g.board.seat?.id

        if (wanted("table")) game("table", StagedBoards.table).use { g ->
            g.playUntil { ourPriority(g) }
            g.png("table-art")
            g.setMode(CardMode.TEXT)
            g.png("table-text")
        }
        if (wanted("themes")) game("themes", StagedBoards.table).use { g ->
            g.playUntil { ourPriority(g) }
            for (theme in listOf(mtgoracle.ui.theme.Themes.ROSE_PINE, mtgoracle.ui.theme.Themes.PAPER)) {
                mtgoracle.ui.theme.Palette.theme = theme
                g.png("theme-${theme.key}")
            }
            mtgoracle.ui.theme.Palette.theme = mtgoracle.ui.theme.Themes.HOUSE
        }
        if (wanted("zones")) for ((w, h) in listOf(1920 to 1080, 1600 to 900, 1280 to 720)) {
            StagedGame("zones-${w}x$h", StagedBoards.crowdedSide, logs, out, CardMode.ART, GameMode.HUMAN_VS_AI, ForgeRuntime.images, { _, _, _ -> null }, w, h).use { g ->
                g.playUntil { ourPriority(g) }
                g.png("zones-${w}x$h-art")
                g.setMode(CardMode.TEXT)
                g.png("zones-${w}x$h-text")
            }
        }
        if (wanted("hidden-info")) game("hidden-info", StagedBoards.hiddenInfo).use { g -> g.playUntil { ourPriority(g) }; g.png("hidden-info") }
        if (wanted("reveal")) game("reveal", StagedBoards.reveal) { prompt, board, _ ->
            when {
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() ->
                    board.seat!!.hand.firstOrNull { it.name == "Thoughtseize" }?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.TARGET -> SeatAction.ClickPlayer(board.players.first { !it.isSeat }.id)
                else -> null
            }
        }.use { g ->
            g.playUntil { (g.match.seat.prompt.value as? ChoicePrompt)?.labels?.any { "Time Walk" in it } == true }
            g.png("reveal-active")
        }
        if (wanted("watch")) game("watch", StagedBoards.watch, GameMode.AI_VS_AI).use { g ->
            g.waitFor { g.match.seat.board.value?.players?.all { it.handCount >= 2 } == true }
            g.png("watch-hands-hidden")
            g.match.seat.setShowAllHands(true)
            g.waitFor { g.board.players.all { p -> p.hand.none { it.hidden } } }
            g.png("watch-hands-shown")
        }
        val priority = { g: StagedGame -> (g.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY }
        val passAll: mtgoracle.core.seat.Policy = { prompt, _, _ -> if (prompt is InputPrompt && prompt.kind == InputKind.PRIORITY) SeatAction.Ok else null }
        if (wanted("lands")) game("lands", StagedBoards.lands) { prompt, board, _ ->
            val colonnade = board.seat?.battlefield?.firstOrNull { it.name == "Celestial Colonnade" }
            when {
                colonnade == null -> null
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && !colonnade.isCreature -> SeatAction.ClickCard(colonnade.id)
                prompt is ChoicePrompt && !prompt.isReveal -> SeatAction.Choose(listOf(prompt.options.indexOfFirst { "until end of turn" in it.label.lowercase() }.coerceAtLeast(0)))
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { g ->
            g.playUntil { g.board.seat!!.battlefield.any { it.name == "Celestial Colonnade" && it.isCreature } && g.board.stack.isEmpty() && priority(g) }
            g.png("lands-art")
            g.setMode(CardMode.TEXT)
            g.png("lands-text")
        }
        if (wanted("fetch")) game("fetch", StagedBoards.fetch, policy = passAll).use { g ->
            g.playUntil { priority(g) && g.board.stack.firstOrNull()?.sourceName == "Polluted Delta" }
            g.png("fetch-on-stack")
            g.playUntil { g.board.players.first { !it.isSeat }.battlefield.count { it.name == "Swamp" } == 3 }
            g.png("fetch-trail")
        }
        if (wanted("bolt")) game("bolt", StagedBoards.bolt, policy = passAll).use { g ->
            g.playUntil(timeoutMillis = 120_000) { priority(g) && g.board.stack.firstOrNull()?.sourceName == "Lightning Bolt" }
            g.png("bolt-target")
        }
        if (wanted("crowded")) {
            lateinit var crowded: StagedGame
            var moved = false
            crowded = game("crowded", StagedBoards.crowded) { prompt, board, _ ->
                val input = prompt as? InputPrompt
                val burn = board.seat?.hand?.firstOrNull { it.name == "Lightning Bolt" || it.name == "Shock" }
                val theirs = board.players.first { !it.isSeat }.battlefield.filter { it.isCreature }
                when {
                    input?.kind == InputKind.PRIORITY && board.activePlayerId == board.seat?.id && burn != null && theirs.size == 8 -> SeatAction.ClickCard(burn.id)
                    input?.kind == InputKind.TARGET -> {
                        // The rightmost creature: where the stack box floats until it steps aside.
                        if (board.stack.isNotEmpty() && !moved) { crowded.png("stack-box-moved"); moved = true }
                        SeatAction.ClickCard(theirs.last().id)
                    }
                    else -> null
                }
            }
            crowded.use { g ->
                g.playUntil { priority(g) && g.board.stack.size == 3 }
                g.png("stack-box-three")
                g.key(androidx.compose.ui.input.key.Key.S)
                g.png("stack-bar")
            }
        }
        Log.info("staged pictures in $out")
        return 0
    }
}
