package mtgoracle.net

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Lines both ways between a host and a guest: TCP on this machine now, the relay later. */
interface Link : AutoCloseable {
    /** Sends one line; false once the link is closed. */
    fun send(line: String): Boolean

    /** The next line, waiting for it; null once the link is closed, from either end, or a read waited past its bound. */
    fun receive(): String?

    /** From now on, a line may be at most [maxLine] characters and a read wait at most [readTimeoutMillis] (0: for ever). */
    fun bound(maxLine: Int, readTimeoutMillis: Int) {}

    override fun close()
}

/**
 * A [Link] over a TCP socket: on this machine's loopback address for local
 * play ([listenLocal]), or at every address while a room is open to the
 * internet ([listen], for [Room.public]).
 */
class TcpLink(private val socket: Socket) : Link {
    @Volatile private var maxLine = MAX_LINE
    private val closed = AtomicBoolean(false)
    private val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer: Writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    @Synchronized
    override fun send(line: String): Boolean = try {
        writer.write(line)
        writer.write("\n")
        writer.flush()
        true
    } catch (e: IOException) {
        close()
        false
    }

    override fun receive(): String? = if (closed.get()) null else try {
        readLine()
    } catch (e: IOException) {
        null
    }

    /** One line, refused past [maxLine]: a peer can't make this side hold more than that. */
    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return null
            if (c == '\n'.code) return line.toString()
            if (line.length >= maxLine) {
                close()
                throw IOException("a line over ${maxLine / 1024} KB: the link is closed")
            }
            line.append(c.toChar())
        }
    }

    override fun bound(maxLine: Int, readTimeoutMillis: Int) {
        this.maxLine = maxLine
        runCatching { socket.soTimeout = readTimeoutMillis }
    }

    /**
     * Closes in order: what was sent goes first, then the end of the stream,
     * and what the peer still sends is read away until it closes too (two
     * seconds at most). A socket closed with lines unread is reset on
     * Windows, and the peer loses the last ones sent: a goodbye, an End.
     */
    override fun close() {
        if (closed.getAndSet(true)) return
        runCatching { socket.shutdownOutput() }
        thread(name = "tcp-link-close", isDaemon = true) {
            runCatching {
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val away = ByteArray(8192)
                while (input.read(away) >= 0) Unit
            }
            runCatching { socket.close() }
        }
    }

    companion object {
        /** The largest message a side takes: a board is at most tens of KB, a whole log a few hundred. */
        const val MAX_LINE = 4 * 1024 * 1024

        /** A listener on this machine's loopback address, on a free port; a few can queue while a stranger is turned away. */
        fun listenLocal(): Listener = Listener(ServerSocket(0, 4, InetAddress.getLoopbackAddress()))

        /** A listener on [port] at [bind], or at every address this machine has when null: a room opened to the internet. */
        fun listen(bind: InetAddress?, port: Int): Listener = Listener(ServerSocket(port, 4, bind))

        fun connect(address: InetAddress, port: Int, timeoutMillis: Int = 10_000): TcpLink =
            TcpLink(Socket().apply { connect(InetSocketAddress(address, port), timeoutMillis) })

        fun connectLocal(port: Int, timeoutMillis: Int = 5_000): TcpLink = connect(InetAddress.getLoopbackAddress(), port, timeoutMillis)
    }

    class Listener internal constructor(private val server: ServerSocket) : AutoCloseable {
        val port: Int get() = server.localPort
        val isLoopbackOnly: Boolean get() = server.inetAddress.isLoopbackAddress

        /** The next guest, waiting up to [timeoutMillis] (0: until the listener closes). */
        fun accept(timeoutMillis: Int = 60_000): TcpLink {
            server.soTimeout = timeoutMillis
            return TcpLink(server.accept())
        }

        override fun close() = server.close()
    }
}
