package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import mtgoracle.core.play.Achievement
import mtgoracle.core.play.AchievementGroup
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.Toolbar
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.ZoomPane
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/**
 * Forge's achievements: the collections on the left, each earned one bright
 * with the highest level it reached, and the one chosen on the right, with
 * every level, the best so far and, for one about a card, the card. [groups]
 * is null while Forge is still starting.
 */
@Composable
fun AchievementsScreen(
    groups: List<AchievementGroup>?,
    faceOf: (String) -> CardFace?,
    onLibrary: () -> Unit,
    textMode: Boolean = false,
) {
    // E: only the earned ones; most of Forge's are not, and the list is long.
    var earnedOnly by remember { mutableStateOf(false) }
    val shown = groups.orEmpty().map { g -> g to g.achievements.filter { !earnedOnly || it.earned } }.filter { it.second.isNotEmpty() }
    val all = shown.flatMap { it.second }
    // The list's rows: a rule per collection, then its achievements, each by its place in [all].
    val rows = buildList {
        var at = 0
        shown.forEach { (group, items) -> add(Row.Header(group)); items.forEach { add(Row.Item(at++, it)) } }
    }
    var chosen by remember { mutableStateOf(0) }
    if (chosen > all.lastIndex) chosen = maxOf(0, all.lastIndex)
    val listState = rememberLazyListState()
    // The chosen one stays in view as the arrows move it.
    LaunchedEffect(chosen, earnedOnly) {
        val row = rows.indexOfFirst { it is Row.Item && it.index == chosen }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || row <= visible.first().index || row >= visible.last().index) listState.scrollToItem(maxOf(0, row - 3))
    }
    val focus = remember { FocusRequester() }
    val onClick: (ClickTarget) -> Unit = { t ->
        val name = (t as? ClickTarget.Control)?.name.orEmpty()
        when {
            name == "library" -> onLibrary()
            name.startsWith("achievement:") -> chosen = name.removePrefix("achievement:").toInt()
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.surface).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> chosen = (chosen - 1).coerceAtLeast(0)
                Key.DirectionDown -> chosen = (chosen + 1).coerceAtMost(maxOf(0, all.lastIndex))
                Key.E -> earnedOnly = !earnedOnly
                Key.Escape -> onLibrary()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        val earned = groups.orEmpty().sumOf { it.earned }
        Column(Modifier.fillMaxSize()) {
            Toolbar(listOf("library" to "Library"), onClick)
            androidx.compose.foundation.layout.Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxPane("achievements", Modifier.weight(1f).fillMaxHeight().region("achievements"),
                    right = groups?.let { "$earned of ${it.sumOf { g -> g.achievements.size }} earned" + if (earnedOnly) " · earned only" else "" }) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val inner = LocalCells.current.cols(constraints.maxWidth.toFloat())
                        if (groups == null) FitText("  Forge is starting: its achievements come with it", color = Palette.dim)
                        else if (all.isEmpty()) FitText("  None earned yet: E shows them all", color = Palette.dim)
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(rows.size) { i ->
                                when (val row = rows[i]) {
                                    is Row.Header -> RuleLine(inner, label = row.group.name, end = "${row.group.earned}/${row.group.achievements.size}")
                                    is Row.Item -> AchievementLine(row.achievement, row.index, row.index == chosen, onClick)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val picked = all.getOrNull(chosen)
                    val face = picked?.card?.let(faceOf)
                    val sideCols = cols / 2
                    if (face != null) ZoomPane(face, sideCols, imageRows = 20, textMode = textMode, modifier = Modifier.fillMaxWidth())
                    BoxPane(picked?.name ?: "achievement", Modifier.fillMaxWidth().weight(1f).region("achievement")) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            if (picked != null) Details(picked)
                        }
                    }
                }
            }
            StatusLine(listOf("↑↓" to "choose", "E" to if (earnedOnly) "all" else "earned only", "Esc" to "library"), null, cols)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A row of the list: a collection's rule, or one of its achievements. */
private sealed interface Row {
    data class Header(val group: AchievementGroup) : Row
    data class Item(val index: Int, val achievement: Achievement) : Row
}

/** One achievement in the list: bright with its level once earned, dim before; inverted when chosen. */
@Composable
private fun AchievementLine(a: Achievement, index: Int, chosen: Boolean, onClick: (ClickTarget) -> Unit) {
    val reached = a.tier?.label ?: if (a.special && a.earned) "earned" else null
    val text = "  ${if (a.earned) "[x]" else "[ ]"} ${a.name}" + (reached?.let { "  $it" }.orEmpty())
    val color = if (a.earned) Palette.accent else Palette.dim
    FitText(
        text,
        Modifier.clickTarget(ClickTarget.Control("achievement:$index"), onClick),
        color = if (chosen) Palette.background else color,
        background = if (chosen) color else Color.Unspecified,
    )
}

/** What the chosen achievement asks, level by level, and how close you have come. */
@Composable
private fun Details(a: Achievement) {
    if (a.description.isNotBlank()) WrapText("  ${a.description}", hang = 2)
    a.flavor?.let { WrapText("  $it", color = Palette.dim, hang = 2) }
    GridText("")
    if (a.special) FitText(if (a.earned) "  [x] earned" else "  [ ] not yet", color = if (a.earned) Palette.accent else Palette.dim)
    a.levels.forEach { level ->
        WrapText("  ${if (level.earned) "[x]" else "[ ]"} ${level.tier.label}: ${level.text}",
            color = if (level.earned) Palette.accent else Palette.dim, hang = 6)
    }
    a.best?.let { GridText(""); FitText("  $it") }
}
