package mtgoracle.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mtgoracle.model.GameSeat
import kotlin.system.exitProcess

/**
 * The spike's window. [start] brings Forge up and starts the match off the
 * UI thread (Forge takes ~6 s to load 33k cards); the board shows
 * "Starting Forge…" until the seat exists.
 */
fun runWindow(title: String, start: () -> GameSeat, onClose: () -> Unit) = application {
    var seat by remember { mutableStateOf<GameSeat?>(null) }
    LaunchedEffect(Unit) { seat = withContext(Dispatchers.IO) { start() } }
    Window(
        onCloseRequest = {
            onClose()
            // Forge's game threads are not daemons: closing the window ends the process.
            exitProcess(0)
        },
        title = "MTG Oracle — $title",
        state = rememberWindowState(width = 1680.dp, height = 1040.dp),
    ) {
        LiveBoardScreen(seat, title)
    }
}
