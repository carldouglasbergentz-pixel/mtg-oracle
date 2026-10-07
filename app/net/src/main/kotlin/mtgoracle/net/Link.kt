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

/** Lines both ways between a host and a guest: TCP on this machine now, the relay later. */
interface Link : AutoCloseable {
    /** Sends one line; false once the link is closed. */
    fun send(line: String): Boolean

    /** The next line, waiting for it; null once the link is closed, from either end. */
    fun receive(): String?

    override fun close()
}

/**
 * A [Link] over a TCP socket. Only ever bound to this machine's loopback
 * address ([listenLocal]): nothing on the network reaches it.
 */
class TcpLink(private val socket: Socket) : Link {
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

    override fun receive(): String? = try {
        readLine()
    } catch (e: IOException) {
        null
    }

    /** One line, refused past [MAX_LINE]: a peer can't make this side hold more than that. */
    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return null
            if (c == '\n'.code) return line.toString()
            if (line.length >= MAX_LINE) {
                close()
                throw IOException("a line over ${MAX_LINE / 1024} KB: the link is closed")
            }
            line.append(c.toChar())
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** The largest message a side takes: a board is at most tens of KB, a whole log a few hundred. */
        const val MAX_LINE = 4 * 1024 * 1024

        /** A listener on this machine's loopback address, on a free port. */
        fun listenLocal(): Listener = Listener(ServerSocket(0, 1, InetAddress.getLoopbackAddress()))

        fun connectLocal(port: Int, timeoutMillis: Int = 5_000): TcpLink =
            TcpLink(Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMillis) })
    }

    class Listener internal constructor(private val server: ServerSocket) : AutoCloseable {
        val port: Int get() = server.localPort
        val isLoopbackOnly: Boolean get() = server.inetAddress.isLoopbackAddress

        /** The next guest, waiting up to [timeoutMillis]. */
        fun accept(timeoutMillis: Int = 60_000): TcpLink {
            server.soTimeout = timeoutMillis
            return TcpLink(server.accept())
        }

        override fun close() = server.close()
    }
}
