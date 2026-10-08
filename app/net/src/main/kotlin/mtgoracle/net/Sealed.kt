package mtgoracle.net

import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.Sealed
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/*
 * A sealed table (ADR 0003): each side opens its own pool, from a seed
 * neither can steer, and the other can open it again to check a deck.
 *
 * 1. Each side sends the hash of a secret of its own (its envelope).
 * 2. Having the other's envelope, each sends an open number of its own.
 * 3. Each side's pool is opened from its secret and the other's number: its
 *    owner fixed the secret before seeing that number, so can't choose a
 *    good pool, and the other side lacks the secret, so can't see it.
 * 4. A deck goes with its owner's secret: the other side checks it against
 *    the envelope, opens the same packs, and judges the deck against them.
 *
 * The host is ready first, its deck fixed by digest, before the guest's
 * deck (and so the guest's pool) reaches it; the host's secret and deck
 * reach the guest after the match.
 */

/** One side's secret, its envelope (the hash) and its open number. All hex. */
class SealedKeys(random: SecureRandom = SecureRandom()) {
    private val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
    val secret: String = hex(secretBytes)
    val envelope: String = hex(sha256(secretBytes))
    val nonce: String = hex(ByteArray(NONCE_BYTES).also(random::nextBytes))

    companion object {
        const val SECRET_BYTES = 32
        const val NONCE_BYTES = 16
        private val DOMAIN = "mtg-oracle sealed v1".toByteArray()

        /** Whether [secret] is the one [envelope] sealed. */
        fun opens(envelope: String, secret: String): Boolean = unhex(secret, SECRET_BYTES)?.let { hex(sha256(it)) == envelope } == true

        /** The seed a pool is opened from: its owner's [secret] and the other side's [nonce]. Null when either is no such value. */
        fun seed(secret: String, nonce: String): Long? {
            val s = unhex(secret, SECRET_BYTES) ?: return null
            val n = unhex(nonce, NONCE_BYTES) ?: return null
            return sha256(DOMAIN + s + n).take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        }

        /** Whether [value] is an envelope, a hash's length of hex. */
        fun isEnvelope(value: String): Boolean = unhex(value, 32) != null

        /** Whether [value] is an open number. */
        fun isNonce(value: String): Boolean = unhex(value, NONCE_BYTES) != null

        /** A deck's cards, in an order of their own, hashed: what the host holds to when it says it is ready. */
        fun deckDigest(deck: PlayDeck): String = hex(sha256(
            deck.cards.map { "${it.section}|${it.forgeName}|${it.quantity}|${it.setCode.orEmpty()}|${it.collectorNumber.orEmpty()}" }.sorted().joinToString("\n").toByteArray(),
        ))

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** [value] as exactly [bytes] bytes of lower-case hex, or null: a peer's value is checked before anything reads it. */
        private fun unhex(value: String, bytes: Int): ByteArray? {
            if (value.length != bytes * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return ByteArray(bytes) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** What happened at the host's sealed table while it is built, for the lobby to show. */
sealed interface SealedEvent {
    /** The host's own pool, opened: the deck to build is made from it. */
    data class PoolOpened(val pool: OpenedPool) : SealedEvent
    /** The guest's deck was turned away, and why (they were told). */
    data class Refused(val reason: String) : SealedEvent
}

/**
 * The host's side of a sealed table, from the guest's seating to the start
 * of the match: the envelopes and numbers, the host's pool (opened by [open]
 * from its seed), the host's [ready], and the guest's deck judged against
 * the pool [open] makes from the guest's secret. [run] blocks until the
 * guest's deck sits down, the guest leaves, or the link goes.
 */
class SealedHost(
    private val link: Link,
    private val table: LimitedTable,
    private val open: (seed: Long) -> OpenedPool,
    private val keys: SealedKeys = SealedKeys(),
    private val onEvent: (SealedEvent) -> Unit,
) {
    sealed interface Outcome {
        /** The guest's deck sits down, with the pool it came from. */
        data class Seated(val deck: PlayDeck, val pool: OpenedPool) : Outcome
        /** The guest left, or the link went: [reason] says which. */
        data class Gone(val reason: String) : Outcome
    }

    /** The host's secret, for the [HostMessage.Reveal] after the match. */
    val secret: String get() = keys.secret

    /** The host's own pool, once opened. */
    @Volatile var pool: OpenedPool? = null
        private set

    /** The deck the host is ready with; null until it is. */
    @Volatile var deck: PlayDeck? = null
        private set

    @Volatile private var done = false
    private var refused = 0

    /**
     * The host is ready with [deck], which must be a deck of its pool; the
     * guest may now send theirs. Null when it is ready, else why it isn't.
     */
    fun ready(deck: PlayDeck): String? {
        val mine = pool ?: return "Your pool is still being opened."
        if (this.deck != null) return "You are already ready."
        Sealed.judge(deck, mine)?.let { return it }
        this.deck = deck
        send(HostMessage.Ready(SealedKeys.deckDigest(deck)))
        return null
    }

    fun run(): Outcome {
        val pinger = thread(name = "sealed-host-ping", isDaemon = true) {
            while (!done) {
                try { Thread.sleep(Wire.PING_MILLIS) } catch (e: InterruptedException) { return@thread }
                if (!done) send(HostMessage.Ping)
            }
        }
        try {
            send(HostMessage.Envelope(keys.envelope))
            var theirEnvelope: String? = null
            var theirNonce: String? = null
            while (true) {
                val line = link.receive() ?: return Outcome.Gone("the connection to the guest was lost")
                val message = try { Wire.guest(line) } catch (e: WireError) { return gone("the guest sent what this table can't read (${e.message})") }
                when (message) {
                    is GuestMessage.Envelope -> {
                        if (theirEnvelope != null || !SealedKeys.isEnvelope(message.hash)) return gone("the guest's envelope came twice, or was no envelope")
                        theirEnvelope = message.hash
                        send(HostMessage.Nonce(keys.nonce))
                    }
                    is GuestMessage.Nonce -> {
                        // Their number counts only after their envelope: before it, they could have chosen their secret to suit ours.
                        if (theirEnvelope == null || theirNonce != null || !SealedKeys.isNonce(message.value)) return gone("the guest's number came out of turn, or was no number")
                        theirNonce = message.value
                        val mine = open(SealedKeys.seed(keys.secret, message.value)!!)
                        pool = mine
                        onEvent(SealedEvent.PoolOpened(mine))
                    }
                    is GuestMessage.Deck -> {
                        val early = when {
                            deck == null -> "The host isn't ready yet: send your deck once they are."
                            theirEnvelope == null || theirNonce == null -> "Your pool isn't opened yet."
                            !SealedKeys.opens(theirEnvelope, message.secret) -> "That secret isn't the one your envelope sealed."
                            else -> null
                        }
                        // Their pool, opened here from their secret and our number: the deck is judged against it, never against what they say.
                        val theirPool = if (early == null) open(SealedKeys.seed(message.secret, keys.nonce)!!) else null
                        val refusal = early ?: Sealed.judge(message.deck, theirPool!!)
                        if (refusal == null) {
                            send(HostMessage.Verdict(null))
                            return Outcome.Seated(message.deck, theirPool!!)
                        }
                        send(HostMessage.Verdict(refusal))
                        onEvent(SealedEvent.Refused(refusal))
                        // Each deck opens the guest's packs again here: a guest who keeps sending them is sent away.
                        if (++refused >= MAX_REFUSED) return gone("$refused decks were turned away: the table closed")
                    }
                    GuestMessage.Leave -> return Outcome.Gone("the guest left the table")
                    GuestMessage.Ping, is GuestMessage.SetStops, is GuestMessage.Mat -> Unit
                    // Nothing of a match is asked before it starts.
                    is GuestMessage.Hello, is GuestMessage.Answer, is GuestMessage.Command, GuestMessage.Concede -> Unit
                }
            }
        } finally {
            done = true
            pinger.interrupt()
        }
    }

    private fun gone(reason: String): Outcome {
        link.close()
        return Outcome.Gone(reason)
    }

    private fun send(message: HostMessage) { link.send(Wire.encode(message)) }

    companion object {
        /** How many of the guest's decks may be turned away before the table closes. */
        const val MAX_REFUSED = 20
    }
}
