package mtgoracle.net

/**
 * What either side decides on the other's hello, before any rule of the
 * game: the same protocol, and a name the table can show. Whether the deck
 * can be played is the host's to say (Forge knows what it can play).
 */
object Handshake {
    const val MAX_NAME = 24

    /** The guest's name as the table shows it: trimmed, no control characters, at most [MAX_NAME] characters; "Guest" when nothing is left. */
    fun cleanName(raw: String): String =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_NAME).trim().ifEmpty { "Guest" }

    /** Why the host turns [hello] away, or null when it may sit down. */
    fun refusal(hello: GuestMessage.Hello): String? =
        mismatch(theirs = hello.protocol, them = "your app", us = "the host's app")
            ?: if (hello.deck.cards.isEmpty()) "Your deck has no cards." else null

    /** Why the guest can't sit at a table that said [hello], or null when it can. */
    fun refusal(hello: HostMessage.Hello): String? = mismatch(theirs = hello.protocol, them = "the host's app", us = "your app")

    private fun mismatch(theirs: Int, them: String, us: String): String? = when {
        theirs == PROTOCOL_VERSION -> null
        theirs < PROTOCOL_VERSION -> "$them is older than $us (network protocol $theirs, not $PROTOCOL_VERSION): it needs an update (`update`)."
        else -> "$us is older than $them (network protocol $PROTOCOL_VERSION, not $theirs): it needs an update (`update`)."
    }
}
