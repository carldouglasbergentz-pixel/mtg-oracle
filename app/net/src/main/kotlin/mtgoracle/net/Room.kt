package mtgoracle.net

import java.io.IOException
import java.net.InetAddress

/**
 * The host's open room: a listener, and the invite that leads to it. Every
 * link to it is a [SecureLink] under the invite's secret, so a stranger who
 * finds the port (a scan does) can't say a word the door understands: they
 * are hung up on and the room waits on. The guest with the invite is let in,
 * and then the listener closes: no one else.
 */
class Room private constructor(private val listener: TcpLink.Listener, val invite: Invite) : AutoCloseable {
    /** The guest let in: their link and their hello. */
    data class Guest(val link: Link, val hello: GuestMessage.Hello)

    /** Why the room closed on its own, or null. */
    @Volatile var closedBecause: String? = null
        private set

    /**
     * Waits for the guest who holds the invite. A stranger is hung up on and a
     * refused guest told why (they may knock again with another deck), each
     * said to [onKnock]; after [MAX_STRANGERS] strangers the room closes and
     * says why. Null once the room is closed.
     */
    fun awaitGuest(hello: HostMessage.Hello, judge: (GuestMessage.Hello) -> String?, onKnock: (Door.Outcome) -> Unit = {}): Guest? {
        var strangers = 0
        while (true) {
            val raw = try { listener.accept(timeoutMillis = 0) } catch (e: IOException) { return null }
            val link = SecureLink(raw, invite.secret, SecureLink.Role.HOST)
            when (val outcome = Door.admit(link, hello, judge)) {
                is Door.Outcome.Admitted -> { listener.close(); return Guest(link, outcome.hello) }
                is Door.Outcome.Refused -> onKnock(outcome)
                is Door.Outcome.Stranger -> {
                    onKnock(outcome)
                    if (++strangers >= MAX_STRANGERS) {
                        closedBecause = "$strangers connections came without the invite: the room closed, so as not to stay open to whoever is trying. Open a new one for your friend."
                        close()
                        return null
                    }
                }
            }
        }
    }

    override fun close() = listener.close()

    companion object {
        const val MAX_STRANGERS = 20

        /** A room on this machine's loopback address: local play, and the tests. */
        fun local(): Room = TcpLink.listenLocal().let { Room(it, Invite.create(InetAddress.getLoopbackAddress(), it.port)) }
    }
}
