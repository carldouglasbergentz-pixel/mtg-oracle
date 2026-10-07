package mtgoracle.net

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.LogLine

/*
 * A board carries the whole match's log, up to thousands of lines, and is
 * sent on every change: the log travels apart, as the lines the guest lacks.
 * Lines are only added, with one exception: two of a kind in a row (two
 * draws) are folded into one, the old line removed and the new one given the
 * next seq (GameRecorder.play's merge). So a log message also says how much
 * of what was sent still stands.
 */

/** The host's side: what of [board]'s log is new since the last message. */
class LogSender {
    private var sentThrough = -1L

    /** The log message that brings the guest up to [log], or null when nothing changed. */
    fun next(log: List<LogLine>): HostMessage.Log? {
        val fresh = log.filter { it.seq > sentThrough }
        // The last line the guest has that still stands: a folded line is gone from the host's log.
        val keepThrough = log.lastOrNull { it.seq <= sentThrough }?.seq ?: -1
        val folded = keepThrough != sentThrough
        if (fresh.isEmpty() && !folded) return null
        sentThrough = log.lastOrNull()?.seq ?: sentThrough
        return HostMessage.Log(keepThrough, fresh)
    }
}

/** The guest's side: the log as the messages have built it, and the board put back together. */
class LogReceiver {
    private val lines = ArrayList<LogLine>()

    fun apply(message: HostMessage.Log) {
        lines.removeAll { it.seq > message.keepThrough }
        lines += message.lines
        // As many as the host keeps (GameRecorder.MAX_LINES): a long match's oldest lines go on both sides.
        if (lines.size > MAX_LINES) lines.subList(0, lines.size - MAX_LINES).clear()
    }

    fun join(board: BoardState): BoardState = board.copy(log = lines.toList())

    companion object {
        const val MAX_LINES = 5000
    }
}
