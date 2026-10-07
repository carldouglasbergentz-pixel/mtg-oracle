package mtgoracle.net

import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** The router answered an action with a UPnP error ([code] as the standard numbers them, 718 for a port taken). */
class UpnpFault(val code: Int, val description: String) : Exception("UPnP error $code: $description")

/**
 * As much of UPnP's Internet Gateway Device as a room needs, written here
 * rather than taken from a library: find the router (SSDP), ask its address
 * outside, open a port to this machine, close it again. Everything the router
 * says is read warily: its description must come from the local network, is
 * capped in size, and is parsed with no document type (no XML entities);
 * its answer must come from the address its description is on, the control
 * URL must be on that same host, no redirect is followed, and the service
 * type must be exactly one of UPnP's WAN connections. A router is never this
 * machine, except where the search itself goes to this machine (the tests).
 *
 * [discovery] is where the search goes: UPnP's multicast address, or in the
 * tests a fake router on this machine.
 */
class PortMapper(
    private val discovery: InetSocketAddress = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900),
    private val timeoutMillis: Int = 3_000,
) {
    /** A router's WAN connection service, and this machine's address towards it. */
    data class Gateway(val controlUrl: URL, val serviceType: String, val localAddress: InetAddress)

    /** The first router that answers with a WAN connection service, or null when none does in time. */
    fun discover(): Gateway? {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            for (target in SEARCH_TARGETS) {
                val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n".toByteArray()
                socket.send(DatagramPacket(search, search.size, discovery))
            }
            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (e: SocketTimeoutException) { break }
                val location = header(String(packet.data, 0, packet.length, Charsets.ISO_8859_1), "LOCATION") ?: continue
                gateway(location, from = packet.address)?.let { return it }
            }
        }
        return null
    }

    /** The router's address outside, or null when it doesn't say. */
    fun externalAddress(gateway: Gateway): InetAddress? =
        tag(soap(gateway, "GetExternalIPAddress", emptyList()), "NewExternalIPAddress")?.trim()?.let(::literalAddress)

    /** An address written out (IPv4 or IPv6), never a name: a name would be looked up, on the router's say-so. */
    private fun literalAddress(text: String): InetAddress? =
        if (text.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) || (text.contains(':') && text.matches(Regex("[0-9A-Fa-f:.]+"))))
            runCatching { InetAddress.getByName(text) }.getOrNull()
        else null

    /** Opens [port] (TCP, outside and in) to this machine for [leaseSeconds] (0: until it is closed). */
    fun open(gateway: Gateway, port: Int, leaseSeconds: Int, description: String) {
        soap(gateway, "AddPortMapping", listOf(
            "NewRemoteHost" to "", "NewExternalPort" to "$port", "NewProtocol" to "TCP", "NewInternalPort" to "$port",
            "NewInternalClient" to gateway.localAddress.hostAddress, "NewEnabled" to "1",
            "NewPortMappingDescription" to description, "NewLeaseDuration" to "$leaseSeconds",
        ))
    }

    fun close(gateway: Gateway, port: Int) {
        soap(gateway, "DeletePortMapping", listOf("NewRemoteHost" to "", "NewExternalPort" to "$port", "NewProtocol" to "TCP"))
    }

    /**
     * The router's description at [location], when [from] (who answered the search) is where it is, it is on the
     * local network, and it has a WAN connection service whose control URL is on the same host.
     */
    private fun gateway(location: String, from: InetAddress): Gateway? {
        val url = runCatching { URI(location).toURL() }.getOrNull() ?: return null
        if (url.protocol != "http" || !literal(url.host)) return null
        val host = runCatching { InetAddress.getByName(url.host) }.getOrNull() ?: return null
        // Anyone on the network can answer a search: the description must be where the answer came from,
        // and on the local network, or fetching it would be a request on someone else's behalf.
        if (host != from) return null
        if (!(host.isSiteLocalAddress || host.isLinkLocalAddress || (host.isLoopbackAddress && discovery.address.isLoopbackAddress))) return null
        val xml = runCatching { fetch(url) }.getOrNull() ?: return null
        val doc = runCatching { parse(xml) }.getOrNull() ?: return null
        // URLBase is ignored: control URLs resolve against the description's own address, and must stay on its host.
        val base = url.toURI()
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            val type = service.child("serviceType") ?: continue
            if (!type.matches(WAN_SERVICE)) continue
            val control = service.child("controlURL") ?: continue
            val controlUrl = runCatching { base.resolve(control).toURL() }.getOrNull() ?: continue
            if (controlUrl.protocol != "http" || controlUrl.host != url.host) continue
            val local = DatagramSocket().use { it.connect(InetSocketAddress(host, url.port.takeIf { p -> p > 0 } ?: 80)); it.localAddress }
            return Gateway(controlUrl, type, local)
        }
        return null
    }

    /** An address written out, so nothing the router said is looked up as a name. */
    private fun literal(host: String): Boolean = literalAddress(host.removeSurrounding("[", "]")) != null

    private fun Element.child(name: String): String? =
        getElementsByTagName(name).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun fetch(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.use { it.readNBytes(MAX_BODY) }.toString(Charsets.UTF_8)
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        // No document type at all: nothing a router (or someone posing as one) sends can pull in entities or files.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(xml.byteInputStream())

    /** One SOAP action on the router's WAN service: its response body, or a [UpnpFault]. */
    private fun soap(gateway: Gateway, action: String, args: List<Pair<String, String>>): String {
        val body = "<?xml version=\"1.0\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${gateway.serviceType}\">" + args.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" } +
            "</u:$action></s:Body></s:Envelope>"
        val connection = gateway.controlUrl.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", "\"${gateway.serviceType}#$action\"")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val ok = connection.responseCode in 200..299
        val text = (if (ok) connection.inputStream else connection.errorStream)?.use { it.readNBytes(MAX_BODY) }?.toString(Charsets.UTF_8).orEmpty()
        if (!ok) throw UpnpFault(tag(text, "errorCode")?.toIntOrNull() ?: connection.responseCode, tag(text, "errorDescription") ?: "HTTP ${connection.responseCode}")
        return text
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** The text of the first element named [name], whatever its namespace prefix. */
    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?$name>([^<]*)</(?:[A-Za-z0-9_]+:)?$name>").find(xml)?.groupValues?.get(1)

    private fun header(response: String, name: String): String? =
        response.lineSequence().firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim()

    companion object {
        private val WAN_SERVICE = Regex("urn:schemas-upnp-org:service:WAN(IP|PPP)Connection:[0-9]")
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:2", "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1", "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        )
        private const val MAX_BODY = 64 * 1024

        /** The UPnP error a router gives for a port another mapping has. */
        const val PORT_TAKEN = 718
        /** And for a lease it can't keep: it only opens ports until they are closed. */
        const val ONLY_PERMANENT = 725
    }
}
