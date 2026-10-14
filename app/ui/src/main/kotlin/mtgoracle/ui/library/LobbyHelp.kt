package mtgoracle.ui.library

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.wrap
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** Where the lobby's help text starts, and the margin it keeps from the pane's right border. */
internal const val HELP_INDENT = 4

/** The widest a line of help is wrapped to: in a 1920-wide window a 150-column line is hard to follow back. */
internal const val HELP_COLS = 100

/**
 * A paragraph of the lobby's help (or a note in [color]): every line at
 * [HELP_INDENT], wrapped with as wide a margin on the right and at most
 * [HELP_COLS] across, so no paragraph starts at one column and goes on at another.
 */
@Composable
internal fun Help(text: String, color: Color = Palette.dim) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        val width = maxOf(1, minOf(cols - 2 * HELP_INDENT, HELP_COLS))
        val indent = " ".repeat(HELP_INDENT)
        Column { wrap(text, width).forEach { GridText(indent + it, color = color) } }
    }
}

/** A label before its value (`your name  Alice`, `yours      Forest`), so the lobby's values start in one column. */
internal const val LOBBY_LABEL = "  %-11s"
