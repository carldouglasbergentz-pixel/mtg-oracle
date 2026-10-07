package mtgoracle.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.LogLine
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand

/**
 * The protocol's version. Host and guest must speak exactly the same one, so
 * any change to a message, or to a type it carries, raises it.
 */
const val PROTOCOL_VERSION = 1

/** What the host sends a remote seat: the seat's [mtgoracle.core.model.GameSeat] flows, as they change. */
@Serializable
sealed interface HostMessage {
    /** First, on connecting: who hosts, and what they speak. */
    @Serializable @SerialName("hello")
    data class Hello(val protocol: Int, val app: String, val host: String) : HostMessage

    /** The guest sits down under [name], made unique at the table. */
    @Serializable @SerialName("accepted")
    data class Accepted(val name: String) : HostMessage

    /** The guest may not sit down, and why, said to them as it is. The link closes after it. */
    @Serializable @SerialName("refused")
    data class Refused(val reason: String) : HostMessage

    /** The guest's board, without its log: [Log] carries that, a few lines at a time. */
    @Serializable @SerialName("board")
    data class Board(val board: BoardState?) : HostMessage

    /** The guest keeps its log lines up to [keepThrough] (a seq; -1 for none) and adds [lines] after them. */
    @Serializable @SerialName("log")
    data class Log(val keepThrough: Long, val lines: List<LogLine>) : HostMessage

    @Serializable @SerialName("prompt")
    data class Ask(val prompt: Prompt?) : HostMessage

    @Serializable @SerialName("stops")
    data class Stops(val stops: PhaseStops) : HostMessage

    @Serializable @SerialName("yield")
    data class YieldStatus(val text: String?) : HostMessage

    @Serializable @SerialName("warning")
    data class Warning(val text: String?) : HostMessage

    /** The table closes: the match is over, or the host left. */
    @Serializable @SerialName("end")
    data class End(val reason: String) : HostMessage
}

/** What a remote seat sends the host: its hello, then the [mtgoracle.core.model.GameSeat] calls its person makes. */
@Serializable
sealed interface GuestMessage {
    /** The answer to the host's hello: the name the guest goes by and the deck they bring. */
    @Serializable @SerialName("hello")
    data class Hello(val protocol: Int, val app: String, val name: String, val deck: PlayDeck) : GuestMessage

    @Serializable @SerialName("answer")
    data class Answer(val promptId: Long, val action: SeatAction) : GuestMessage

    @Serializable @SerialName("command")
    data class Command(val command: SeatCommand) : GuestMessage

    @Serializable @SerialName("stops")
    data class SetStops(val stops: PhaseStops) : GuestMessage

    /** The guest concedes the game being played. */
    @Serializable @SerialName("concede")
    data object Concede : GuestMessage

    /** The guest leaves the table. */
    @Serializable @SerialName("leave")
    data object Leave : GuestMessage
}

/** A line that is no message of this protocol: malformed, cut off, or from another version. */
class WireError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One message per line of JSON, both ways. */
object Wire {
    private val json = Json {
        classDiscriminator = "type"
        // A board is mostly defaults (untapped, no counters, not hidden): leaving them out halves it.
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: HostMessage): String = json.encodeToString(HostMessage.serializer(), message)
    fun encode(message: GuestMessage): String = json.encodeToString(GuestMessage.serializer(), message)

    fun host(line: String): HostMessage = decode(HostMessage.serializer(), line)
    fun guest(line: String): GuestMessage = decode(GuestMessage.serializer(), line)

    private fun <T> decode(serializer: KSerializer<T>, line: String): T = try {
        json.decodeFromString(serializer, line)
    } catch (e: SerializationException) {
        throw WireError("not a message of protocol $PROTOCOL_VERSION: ${e.message?.take(200)}", e)
    } catch (e: IllegalArgumentException) {
        throw WireError("not a message of protocol $PROTOCOL_VERSION: ${e.message?.take(200)}", e)
    }
}
