package mtgoracle.net

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [Link] whose lines only the invite's holders can read or write. Each
 * side first sends 16 random bytes in the clear; the keys, one per
 * direction, come from the invite's secret and both sides' bytes
 * (HKDF-SHA256), so every connection has keys of its own, even a second
 * knock with the same invite. Each line is then AES-GCM sealed with its
 * number as the nonce. A line that was changed, sent again (on this
 * connection or from another), dropped or put out of order fails to open,
 * and the link closes: nothing from it is ever read as a message. Only the JDK.
 */
class SecureLink(private val inner: Link, private val secret: ByteArray, private val role: Role) : Link {
    enum class Role { HOST, GUEST }

    private class Keys(val send: SecretKeySpec, val receive: SecretKeySpec)

    private val lock = Any()
    @Volatile private var keys: Keys? = null
    private var sent = 0L
    private var received = 0L

    /** Why the link closed on a line that wouldn't open, or null. */
    @Volatile var failure: String? = null
        private set

    /**
     * The connection's keys, made on the first send or receive: our random
     * bytes go out, the peer's come in (as both sides do this first, neither
     * waits on the other), and the keys follow from both and the secret.
     */
    private fun keys(): Keys? = keys ?: synchronized(lock) {
        keys ?: run {
            val ours = ByteArray(RANDOM_BYTES).also(random::nextBytes)
            if (!inner.send(Base64.getEncoder().encodeToString(ours))) return null
            val line = inner.receive() ?: return null
            val theirs = runCatching { Base64.getDecoder().decode(line) }.getOrNull()?.takeIf { it.size == RANDOM_BYTES }
                ?: run { fail("no opening of this protocol: not a connection from someone with the invite"); return null }
            val salt = if (role == Role.HOST) ours + theirs else theirs + ours
            val hostToGuest = Hkdf.key(secret, salt, "host to guest")
            val guestToHost = Hkdf.key(secret, salt, "guest to host")
            Keys(send = if (role == Role.HOST) hostToGuest else guestToHost, receive = if (role == Role.HOST) guestToHost else hostToGuest)
                .also { keys = it }
        }
    }

    override fun send(line: String): Boolean {
        val k = keys() ?: return false
        return synchronized(this) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, k.send, sent++).doFinal(line.toByteArray(Charsets.UTF_8))
            inner.send(Base64.getEncoder().encodeToString(sealed))
        }
    }

    override fun receive(): String? {
        val k = keys() ?: return null
        val raw = inner.receive() ?: return null
        return try {
            val opened = cipher(Cipher.DECRYPT_MODE, k.receive, received).doFinal(Base64.getDecoder().decode(raw))
            received++
            String(opened, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            fail("a line that wouldn't open: not sealed with this invite's secret, or changed on the way")
        } catch (e: IllegalArgumentException) {
            fail("a line that isn't sealed at all")
        }
    }

    private fun fail(why: String): String? {
        failure = why
        inner.close()
        return null
    }

    /** As the sealed line goes: base64 of the text and a 16-byte tag. */
    override fun bound(maxLine: Int, readTimeoutMillis: Int) = inner.bound((maxLine + TAG_BYTES) / 3 * 4 + 4, readTimeoutMillis)

    override fun close() = inner.close()

    private fun cipher(mode: Int, key: SecretKeySpec, counter: Long): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            // Four zero bytes and the line's number: the keys are this connection's and this direction's, so no nonce repeats under a key.
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, ByteBuffer.allocate(12).putInt(0).putLong(counter).array()))
        }

    private companion object {
        const val TAG_BYTES = 16
        const val RANDOM_BYTES = 16
        val random = SecureRandom()
    }
}

/** HKDF-SHA256 (RFC 5869): a 32-byte key for one purpose, from the invite's secret and the connection's [salt]. */
internal object Hkdf {
    private val LABEL = "mtg-oracle network play v1 ".toByteArray()

    fun key(secret: ByteArray, salt: ByteArray, purpose: String): SecretKeySpec {
        val prk = hmac(salt, secret)
        val okm = hmac(prk, LABEL + purpose.toByteArray() + byteArrayOf(1))
        return SecretKeySpec(okm, "AES")
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
