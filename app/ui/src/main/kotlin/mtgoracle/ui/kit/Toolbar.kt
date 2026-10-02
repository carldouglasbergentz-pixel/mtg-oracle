package mtgoracle.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The controls along a screen's top, as Explorer's command bar: [buttons]
 * as `name` to label, [BigButton]s a cell apart, each a `Control(name)`.
 */
@Composable
fun Toolbar(buttons: List<Pair<String, String>>, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().cellHeight(BUTTON_ROWS).region("toolbar")) {
        GridText(" ")
        buttons.forEach { (name, label) ->
            BigButton(label, ClickTarget.Control(name), true, onClick)
            GridText(" ")
        }
    }
}
