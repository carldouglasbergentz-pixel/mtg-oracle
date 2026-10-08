package mtgoracle.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.ui.theme.LocalCells

/** Opens the theme picker: provided by the app window, so every toolbar ends with the Theme button. */
val LocalThemeMenu = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The controls along a screen's top, as Explorer's command bar: [buttons]
 * as `name` to label, [BigButton]s a cell apart, each a `Control(name)`;
 * at the right end the Theme button (`Control("theme")`). A third of a row's
 * air above and below them, so they don't sit on the panes' edge.
 */
@Composable
fun Toolbar(buttons: List<Pair<String, String>>, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    val themeMenu = LocalThemeMenu.current
    val air = with(LocalDensity.current) { (LocalCells.current.height / 3).toDp() }
    Row(modifier.fillMaxWidth().region("toolbar").padding(vertical = air).cellHeight(BUTTON_ROWS)) {
        GridText(" ")
        buttons.forEach { (name, label) ->
            BigButton(label, ClickTarget.Control(name), true, onClick)
            GridText(" ")
        }
        if (themeMenu != null) {
            Spacer(Modifier.weight(1f))
            BigButton("Theme", ClickTarget.Control("theme"), true) { themeMenu() }
            GridText(" ")
        }
    }
}
