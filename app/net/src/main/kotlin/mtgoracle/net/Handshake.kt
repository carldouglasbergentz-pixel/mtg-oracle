package mtgoracle.net

/**
 * What either side decides on the other's hello, before any rule of the
 * game: the same protocol, and a name the table can show. Whether the deck
 * can be played is the host's to say (Forge knows what it can play).
 */
object Handshake {
    const val MAX_NAME = 24

    /**
     * The guest's name as the table shows it: trimmed, at most [MAX_NAME] characters, "Guest" when nothing is left.
     * No control characters, and none of Unicode's invisible ones (zero-width, direction overrides, line separators),
     * with which a name could pass for the host's own.
     */
    fun cleanName(raw: String): String =
        raw.filterNot { it.isISOControl() || Character.getType(it).toByte() in INVISIBLE }.trim().take(MAX_NAME).trim().ifEmpty { "Guest" }

    private val INVISIBLE = setOf(Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR)

    /**
     * Why the host, having said [host], turns [hello] away, or null when they may sit down: the same protocol
     * and the same app, then a deck with cards (constructed) or the same packs (a sealed table).
     */
    fun refusal(hello: GuestMessage.Hello, host: HostMessage.Hello): String? =
        mismatch(theirs = hello.protocol, them = "your app", us = "the host's app")
            ?: otherApp(theirs = hello.app, ours = host.app, them = "Your app", us = "the host's")
            ?: when (val table = host.table) {
                null -> if (hello.deck == null || hello.deck.cards.isEmpty()) "Your deck has no cards." else null
                else -> if (hello.packsDigest != table.packsDigest) "Your app opens other packs of ${table.set.name} than the host's: both need the same version." else null
            }

    /** Why the guest, running [app], can't sit at a table that said [hello], or null when they can. */
    fun refusal(hello: HostMessage.Hello, app: String): String? =
        mismatch(theirs = hello.protocol, them = "the host's app", us = "your app")
            ?: otherApp(theirs = hello.app, ours = app, them = "The host's app", us = "yours")
            // The guest's app opens the packs the host names: a changed host can't make it open a thousand.
            ?: hello.table?.packs?.takeIf { it !in 1..MAX_PACKS }?.let { "The host's table asks for $it packs each; a table opens 1 to $MAX_PACKS." }

    /** The most packs a sealed table opens for each side: a prerelease is six, a long event no more than twelve. */
    const val MAX_PACKS = 12

    /** Network play is between the same versions: two of them could open other packs, or read a card otherwise. */
    private fun otherApp(theirs: String, ours: String, them: String, us: String): String? =
        if (theirs == ours) null else "$them is ${theirs.take(40)} and $us is ${ours.take(40)}: network play needs the same version on both sides (`update`)."

    private fun mismatch(theirs: Int, them: String, us: String): String? = when {
        theirs == PROTOCOL_VERSION -> null
        theirs < PROTOCOL_VERSION -> "$them is older than $us (network protocol $theirs, not $PROTOCOL_VERSION): it needs an update (`update`)."
        else -> "$us is older than $them (network protocol $PROTOCOL_VERSION, not $theirs): it needs an update (`update`)."
    }
}
