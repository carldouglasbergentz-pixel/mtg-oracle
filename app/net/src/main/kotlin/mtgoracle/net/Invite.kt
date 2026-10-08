package mtgoracle.net

import java.net.InetAddress
import java.security.SecureRandom

/** An invite that can't be read: mistyped, cut short, or from another version. */
class InviteError(message: String) : Exception(message)

/**
 * What a host sends a friend to join: where the room is (an address and a
 * port) and a secret only the two of them hold, from which the link's keys
 * come ([SecureLink]). One string to copy, `MTG-` and groups of four.
 * Whoever has it can join, so it is never written to a log.
 */
class Invite(val address: InetAddress, val port: Int, val secret: ByteArray) {
    init {
        require(port in 1..65535) { "no port $port" }
        require(secret.size == SECRET_BYTES) { "a secret is $SECRET_BYTES bytes" }
    }

    /** The string to copy. */
    val code: String get() {
        val addr = address.address
        val bytes = byteArrayOf(VERSION, addr.size.toByte()) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()) + secret
        return "MTG-" + Base32.encode(bytes).chunked(4).joinToString("-")
    }

    /** The guest's end: a link to the room, encrypted with the invite's secret. */
    fun join(timeoutMillis: Int = 10_000): Link = SecureLink(TcpLink.connect(address, port, timeoutMillis), secret, SecureLink.Role.GUEST)

    /** Where it points, never the secret. */
    override fun toString(): String = "invite to ${address.hostAddress} port $port"

    companion object {
        private const val VERSION: Byte = 1
        const val SECRET_BYTES = 16
        private val random = SecureRandom()

        /** A new room's invite at [address] and [port], with a fresh secret. */
        fun create(address: InetAddress, port: Int): Invite = Invite(address, port, ByteArray(SECRET_BYTES).also(random::nextBytes))

        /** An invite as a friend pasted it: case, spaces and the dashes don't matter. */
        fun parse(text: String): Invite {
            // An invite is some sixty characters: whatever else the clipboard holds is not read through.
            if (text.length > 200) throw InviteError("That is longer than any invite: copy just the invite your friend sent.")
            val plain = text.trim().uppercase().removePrefix("MTG").filter { it.isLetterOrDigit() }
            if (plain.isEmpty()) throw InviteError("That is no invite: an invite starts with MTG- and is groups of four letters and digits.")
            val bytes = Base32.decode(plain) ?: throw InviteError("That invite has a character no invite uses: check that it was copied whole.")
            if (bytes.size < 2) throw InviteError("That invite is cut short: check that it was copied whole.")
            if (bytes[0] != VERSION) throw InviteError("That invite is from another version of the app (${bytes[0]}): both of you need the same one (`update`).")
            val addrSize = bytes[1].toInt()
            if (addrSize != 4 && addrSize != 16) throw InviteError("That invite doesn't hold an address: check that it was copied whole.")
            if (bytes.size != 2 + addrSize + 2 + SECRET_BYTES) throw InviteError("That invite is cut short or has extra letters: check that it was copied whole.")
            val address = InetAddress.getByAddress(bytes.copyOfRange(2, 2 + addrSize))
            val port = ((bytes[2 + addrSize].toInt() and 0xFF) shl 8) or (bytes[3 + addrSize].toInt() and 0xFF)
            if (port == 0) throw InviteError("That invite has no port: check that it was copied whole.")
            return Invite(address, port, bytes.copyOfRange(4 + addrSize, bytes.size))
        }
    }
}

/** RFC 4648's base32 (A–Z, 2–7) without padding: no letters that read alike in either case, nothing a chat client mangles. */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) { out.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    /** The bytes, or null for a character outside the alphabet. */
    fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}
