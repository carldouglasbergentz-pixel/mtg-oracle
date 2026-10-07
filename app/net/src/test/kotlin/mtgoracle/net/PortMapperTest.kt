package mtgoracle.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opening a room to the internet, against a fake router on this machine:
 * SSDP on a loopback port, its description and SOAP control over HTTP on
 * another. Nothing here reaches the network the machine sits on.
 */
class PortMapperTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val closing = mutableListOf<AutoCloseable>()
    @AfterTest fun close() { closing.asReversed().forEach { runCatching { it.close() } } }

    /** A UPnP router as far as a room needs one. [faults] answer the next actions, in order, with those UPnP errors. */
    private inner class FakeRouter(
        var outside: String = "81.230.4.17",
        val faults: MutableList<Int> = mutableListOf(),
        val description: (port: Int) -> String = ::igd,
        val location: ((port: Int) -> String)? = null,
        /** The description's address answers with a redirect to where it really is. */
        val redirect: Boolean = false,
    ) : AutoCloseable {
        val actions = ConcurrentLinkedQueue<String>()
        private val ssdp = DatagramSocket(0, loopback)
        private val http: HttpServer = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        val discovery: InetSocketAddress get() = InetSocketAddress(loopback, ssdp.localPort)
        val searches = ConcurrentLinkedQueue<String>()

        init {
            http.createContext("/igd.xml") { ex ->
                if (redirect) { ex.responseHeaders.add("Location", "http://127.0.0.1:${http.address.port}/moved.xml"); respond(ex, 302, "") }
                else respond(ex, 200, description(http.address.port))
            }
            http.createContext("/moved.xml") { ex -> respond(ex, 200, description(http.address.port)) }
            http.createContext("/ctl/wan") { ex ->
                val action = ex.requestHeaders.getFirst("SOAPAction").orEmpty().substringAfter('#').trim('"')
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                fun arg(name: String) = Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)
                actions += when (action) {
                    "AddPortMapping" -> "add ${arg("NewExternalPort")} to ${arg("NewInternalClient")}:${arg("NewInternalPort")} lease ${arg("NewLeaseDuration")} '${arg("NewPortMappingDescription")}'"
                    "DeletePortMapping" -> "delete ${arg("NewExternalPort")}"
                    else -> action
                }
                val fault = if (action == "GetExternalIPAddress") null else faults.removeFirstOrNull()
                if (fault != null) respond(ex, 500, """<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>$fault</errorCode><errorDescription>fault $fault</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""")
                else respond(ex, 200, """<s:Envelope><s:Body><u:${action}Response>${if (action == "GetExternalIPAddress") "<NewExternalIPAddress>$outside</NewExternalIPAddress>" else ""}</u:${action}Response></s:Body></s:Envelope>""")
            }
            http.start()
            thread(isDaemon = true, name = "fake-ssdp") {
                val buffer = ByteArray(2048)
                while (!ssdp.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { ssdp.receive(packet) }.onFailure { return@thread }
                    val search = String(packet.data, 0, packet.length)
                    searches += search
                    val st = search.lineSequence().first { it.startsWith("ST:") }.substringAfter(':').trim()
                    val where = location?.invoke(http.address.port) ?: "http://127.0.0.1:${http.address.port}/igd.xml"
                    val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: $st\r\nUSN: uuid:fake::$st\r\nLOCATION: $where\r\n\r\n".toByteArray()
                    runCatching { ssdp.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
                }
            }
        }

        private fun respond(ex: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        override fun close() { ssdp.close(); http.stop(0) }
    }

    /** A router's description as they come: the WAN service three devices deep, its control URL relative. */
    private fun igd(port: Int) = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
        <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/ctl/l3f</controlURL></service></serviceList>
        <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType><deviceList><device>
        <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType><serviceList><service>
        <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/wan</controlURL></service></serviceList>
        </device></deviceList></device></deviceList></device></root>"""

    private fun router(router: FakeRouter) = router.also { closing += it }
    private fun mapper(router: FakeRouter) = PortMapper(router.discovery, timeoutMillis = 1_000)
    private fun ports(vararg ports: Int): () -> Int { val left = ports.toMutableList(); return { left.removeAt(0) } }
    private fun free() = java.net.ServerSocket(0, 1, loopback).use { it.localPort }

    private fun open(router: FakeRouter, port: () -> Int = { free() }, lease: Int = Room.LEASE_SECONDS) =
        Room.public(mapper(router), bind = loopback, leaseSeconds = lease, port = port).also { (it as? Opening.Opened)?.room?.let(closing::add) }

    @Test
    fun `the router opens a port to this machine, the invite has its address outside, and closing the room closes the port`() {
        val fake = router(FakeRouter())
        val port = free()
        val room = assertIs<Opening.Opened>(open(fake, ports(port))).room
        assertEquals(InetAddress.getByName("81.230.4.17"), room.invite.address)
        assertEquals(port, room.invite.port)
        assertEquals(listOf("GetExternalIPAddress", "add $port to 127.0.0.1:$port lease 7200 'MTG Oracle'"), fake.actions.toList())
        assertTrue(fake.searches.any { "WANIPConnection" in it })

        // The friend's app reaches the router's address; here, the port it opened leads to this machine.
        val deck = AiCopy.asBuilt(Deck(1, "Green", null, null, listOf(DeckCard("Forest", 60, false, false))))
        var guest: Room.Guest? = null
        val waiting = thread { guest = room.awaitGuest(HostMessage.Hello(PROTOCOL_VERSION, "test", "Alice"), judge = { null }) }
        RemoteSeat(Invite(loopback, port, room.invite.secret).join(), "Bob", deck, "test").also { closing += it }.start()
        waiting.join(10_000)
        assertNotNull(guest, "in through the opened port")
        room.close()
        assertEquals("delete $port", fake.actions.last())
    }

    @Test
    fun `the lease is renewed while the room is open, and a router that keeps no leases gets a port until it is closed`() {
        val fake = router(FakeRouter())
        val port = free()
        val room = assertIs<Opening.Opened>(open(fake, ports(port), lease = 1)).room
        Thread.sleep(1_800)
        assertTrue(fake.actions.count { it.startsWith("add $port") } >= 3, "renewed at half time: ${fake.actions}")
        room.close()
        val after = fake.actions.size
        Thread.sleep(1_200)
        assertEquals(after, fake.actions.size, "and no more once it is closed")

        val permanent = router(FakeRouter(faults = mutableListOf(PortMapper.ONLY_PERMANENT)))
        val port2 = free()
        assertIs<Opening.Opened>(open(permanent, ports(port2))).room.close()
        assertEquals(listOf("GetExternalIPAddress", "add $port2 to 127.0.0.1:$port2 lease 7200 'MTG Oracle'",
            "add $port2 to 127.0.0.1:$port2 lease 0 'MTG Oracle'", "delete $port2"), permanent.actions.toList())
    }

    @Test
    fun `a port the router has given away is passed over for another`() {
        val fake = router(FakeRouter(faults = mutableListOf(PortMapper.PORT_TAKEN)))
        val (first, second) = free() to free()
        val room = assertIs<Opening.Opened>(open(fake, ports(first, second))).room
        assertEquals(second, room.invite.port)
    }

    @Test
    fun `behind another router or the provider's shared address, the host is told why and no port is asked for`() {
        for ((outside, said) in listOf("100.70.1.2" to "CGNAT", "192.168.1.1" to "double NAT", "10.0.0.138" to "double NAT")) {
            val fake = router(FakeRouter(outside = outside))
            val reason = assertIs<Opening.NotReachable>(open(fake)).reason
            assertTrue(outside in reason && said in reason, reason)
            assertEquals(listOf("GetExternalIPAddress"), fake.actions.toList(), "nothing opened")
        }
        // A name instead of an address is never looked up.
        val named = router(FakeRouter(outside = "router.example.com"))
        assertTrue("didn't say its address" in assertIs<Opening.NotReachable>(open(named)).reason)
    }

    @Test
    fun `no router, a refusal, and descriptions that aren't to be trusted`() {
        // Nothing answers the search.
        val silent = DatagramSocket(0, loopback).also { closing += it }
        val started = System.currentTimeMillis()
        val none = Room.public(PortMapper(InetSocketAddress(loopback, silent.localPort), timeoutMillis = 500), bind = loopback)
        assertEquals(Opening.NotReachable(Mapping.NO_ROUTER), none)
        assertTrue(System.currentTimeMillis() - started < 3_000, "said soon")

        val refusing = router(FakeRouter(faults = mutableListOf(501)))
        val reason = assertIs<Opening.NotReachable>(open(refusing)).reason
        assertTrue("wouldn't open a port" in reason && "501" in reason, reason)

        // A description with a document type (an XML entity attack) is no router at all.
        val hostile = router(FakeRouter(description = { port -> """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///c:/windows/win.ini">]>""" + igd(port).substringAfter("?>") }))
        assertEquals(Opening.NotReachable(Mapping.NO_ROUTER), open(hostile))
        assertTrue(hostile.actions.isEmpty())
        // A description somewhere on the internet is never fetched.
        val elsewhere = router(FakeRouter(location = { "http://8.8.8.8:80/igd.xml" }))
        assertEquals(Opening.NotReachable(Mapping.NO_ROUTER), open(elsewhere))
    }

    @Test
    fun `whoever answers the search can't point the app at another host, or slip anything into its requests`() {
        fun refused(name: String, fake: FakeRouter) {
            assertEquals(Opening.NotReachable(Mapping.NO_ROUTER), open(router(fake)), name)
            assertTrue(fake.actions.isEmpty(), "$name: nothing was asked of anyone: ${fake.actions}")
        }
        // The answer names a description on another address than its own.
        refused("another host's description", FakeRouter(location = { port -> "http://127.0.0.2:$port/igd.xml" }))
        // The description's control URL is on another host.
        refused("a control URL elsewhere", FakeRouter(description = { port -> igd(port).replace("<controlURL>/ctl/wan</controlURL>", "<controlURL>http://127.0.0.2:$port/ctl/wan</controlURL>") }))
        // A service type with markup in it, for the SOAP body and header.
        refused("a service type with markup", FakeRouter(description = { port ->
            igd(port).replace("urn:schemas-upnp-org:service:WANIPConnection:1", "urn:schemas-upnp-org:service:WANIPConnection:1&quot;&gt;&lt;x/&gt;")
        }))
        // A redirect is not followed.
        refused("a redirect", FakeRouter(redirect = true))

        // A URLBase pointing elsewhere is ignored: the control URL resolves against the description's own address.
        val based = router(FakeRouter(description = { port -> igd(port).replace("<device>", "<URLBase>http://127.0.0.2:9/</URLBase><device>") }))
        assertIs<Opening.Opened>(open(based))
        assertTrue(based.actions.first() == "GetExternalIPAddress", "${based.actions}")
    }
}
