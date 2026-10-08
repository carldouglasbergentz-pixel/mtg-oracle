package mtgoracle.net

/**
 * The host's door: says hello, reads the guest's, and lets them in or turns
 * them away. Whether their deck can be played is [judge]'s to say. Until the
 * guest is in, a line may be [KNOCK_LINE] at most and must come within
 * [KNOCK_MILLIS]: a stranger who found the port can't make the host hold or
 * wait for more.
 */
object Door {
    const val KNOCK_LINE = 64 * 1024
    const val KNOCK_MILLIS = 10_000

    /** What came of a knock. */
    sealed interface Outcome {
        /** In, under their cleaned name; the link is no longer bounded as a knock is. */
        data class Admitted(val hello: GuestMessage.Hello) : Outcome
        /** One who holds the invite but may not sit down (another protocol, a deck the host can't play): told why. */
        data class Refused(val reason: String) : Outcome
        /** One without the invite, or who said nothing in time: hung up on, told nothing. */
        data class Stranger(val why: String) : Outcome
    }

    fun admit(link: Link, hello: HostMessage.Hello, judge: (GuestMessage.Hello) -> String?): Outcome {
        link.bound(KNOCK_LINE, KNOCK_MILLIS)
        if (!link.send(Wire.encode(hello))) return stranger(link, (link as? SecureLink)?.failure ?: "gone before the hello")
        val line = link.receive()
            ?: return stranger(link, (link as? SecureLink)?.failure ?: "said nothing within ${KNOCK_MILLIS / 1000} s, or hung up")
        // The line opened, so they hold the invite: whatever is wrong now, they are told.
        val guest = try { Wire.guest(line) } catch (e: WireError) { return refuse(link, "That was no message this table understands (${e.message}).") }
        if (guest !is GuestMessage.Hello) return refuse(link, "The first message must be a hello.")
        (Handshake.refusal(guest) ?: judge(guest))?.let { return refuse(link, it) }
        // In, and from now on pinged ([Wire.PING_MILLIS]): silence this long means the link died without a word.
        link.bound(TcpLink.MAX_LINE, Wire.SILENCE_MILLIS)
        return Outcome.Admitted(guest.copy(name = Handshake.cleanName(guest.name)))
    }

    /** Seats the admitted guest under [name], as the table shows it. */
    fun seat(link: Link, name: String): Boolean = link.send(Wire.encode(HostMessage.Accepted(name)))

    private fun refuse(link: Link, reason: String): Outcome = turnAway(link, reason).let { Outcome.Refused(reason) }

    /** Tells a guest at the door, or one let in but not yet seated, why they can't sit down, and closes their link. */
    fun turnAway(link: Link, reason: String) {
        link.send(Wire.encode(HostMessage.Refused(reason)))
        link.close()
    }

    private fun stranger(link: Link, why: String): Outcome {
        link.close()
        return Outcome.Stranger(why)
    }
}
