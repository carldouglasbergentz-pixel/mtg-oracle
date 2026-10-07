package mtgoracle.net

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [Link] whose lines only the invite's holders can read or write: each
 * line AES-GCM sealed, under a key per direction derived from the invite's
 * secret (HKDF-SHA256), with the line's number as the nonce. A line that
 * was changed, sent again, dropped or put out of order fails to open, and
 * the link closes: nothing from it is ever read as a message. Only the JDK.
 */
class SecureLink(private val inner: Link, secret: ByteArray, role: Role) : Link {
    enum class Role { HOST, GUEST }

    private val sendKey: SecretKeySpec
    private val receiveKey: SecretKeySpec
    private var sent = 0L
    private var received = 0L

    /** Why the link closed on a line that wouldn't open, or null. */
    @Volatile var failure: String? = null
        private set

    init {
        val hostToGuest = Hkdf.key(secret, "host to guest")
        val guestToHost = Hkdf.key(secret, "guest to host")
        sendKey = if (role == Role.HOST) hostToGuest else guestToHost
        receiveKey = if (role == Role.HOST) guestToHost else hostToGuest
    }

    @Synchronized
    override fun send(line: String): Boolean {
        val sealed = cipher(Cipher.ENCRYPT_MODE, sendKey, sent++).doFinal(line.toByteArray(Charsets.UTF_8))
        return inner.send(Base64.getEncoder().encodeToString(sealed))
    }

    override fun receive(): String? {
        val raw = inner.receive() ?: return null
        return try {
            val opened = cipher(Cipher.DECRYPT_MODE, receiveKey, received).doFinal(Base64.getDecoder().decode(raw))
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
            // Four zero bytes and the line's number: a key never sees a nonce twice, and each direction has its own key.
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, ByteBuffer.allocate(12).putInt(0).putLong(counter).array()))
        }

    private companion object {
        const val TAG_BYTES = 16
    }
}

/** HKDF-SHA256 (RFC 5869): a 32-byte key for one purpose from the invite's secret. */
internal object Hkdf {
    private val SALT = "mtg-oracle network play v1".toByteArray()

    fun key(secret: ByteArray, purpose: String): SecretKeySpec {
        val prk = hmac(SALT, secret)
        val okm = hmac(prk, purpose.toByteArray() + byteArrayOf(1))
        return SecretKeySpec(okm, "AES")
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
