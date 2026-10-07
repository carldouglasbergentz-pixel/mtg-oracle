package mtgoracle.net

/**
 * The host's door: says hello, reads the guest's, and lets them in or turns
 * them away. Whether their deck can be played is [judge]'s to say.
 */
object Door {
    /** The guest's hello, their name cleaned, or null when they were turned away (and told why) or left. */
    fun admit(link: Link, hello: HostMessage.Hello, judge: (GuestMessage.Hello) -> String?): GuestMessage.Hello? {
        if (!link.send(Wire.encode(hello))) return null
        val line = link.receive() ?: return null
        val guest = try { Wire.guest(line) } catch (e: WireError) { return refuse(link, "That was no message this table understands (${e.message}).") }
        if (guest !is GuestMessage.Hello) return refuse(link, "The first message must be a hello.")
        val reason = Handshake.refusal(guest) ?: judge(guest)
        return if (reason != null) refuse(link, reason) else guest.copy(name = Handshake.cleanName(guest.name))
    }

    /** Seats the admitted guest under [name], as the table shows it. */
    fun seat(link: Link, name: String): Boolean = link.send(Wire.encode(HostMessage.Accepted(name)))

    private fun refuse(link: Link, reason: String): GuestMessage.Hello? {
        link.send(Wire.encode(HostMessage.Refused(reason)))
        link.close()
        return null
    }
}
