package mtgoracle.net

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.concurrent.thread

/** What came of opening a room to the internet. */
sealed interface Opening {
    data class Opened(val room: Room) : Opening
    /** Why a friend couldn't reach this machine, said so the host knows what to do (often: let the friend host). */
    data class NotReachable(val reason: String) : Opening
}

/** Whether an address is on the internet itself, rather than behind another router or the provider's shared address. */
internal object Addresses {
    fun onInternet(address: InetAddress): Boolean = !(
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress || cgnat(address) || uniqueLocal(address)
        )

    /** 100.64.0.0/10: an address a provider shares among customers (carrier-grade NAT). */
    private fun cgnat(address: InetAddress) =
        address is Inet4Address && address.address[0] == 100.toByte() && (address.address[1].toInt() and 0xC0) == 0x40

    /** fc00::/7: IPv6's private addresses. */
    private fun uniqueLocal(address: InetAddress) = address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
}

/**
 * A port the router keeps open to this machine for a room: its lease renewed
 * at half time while the room is open, and the port closed with the room. A
 * router that only keeps ports until told otherwise gets lease 0, and then
 * the close is what ends it.
 */
internal class Mapping(private val mapper: PortMapper, private val gateway: PortMapper.Gateway, val port: Int, private val leaseSeconds: Int) : AutoCloseable {
    @Volatile private var open = true
    private val renewer = if (leaseSeconds <= 0) null else thread(name = "upnp-lease-$port", isDaemon = true) {
        while (open) {
            try { Thread.sleep(leaseSeconds * 500L) } catch (e: InterruptedException) { break }
            if (open) runCatching { mapper.open(gateway, port, leaseSeconds, Room.MAPPING_NAME) }
        }
    }

    override fun close() {
        if (!open) return
        open = false
        renewer?.interrupt()
        runCatching { mapper.close(gateway, port) }
    }

    companion object {
        /**
         * Opens a port to this machine for a room at [bind] (null: every address this machine has): the room, or why not.
         * [port] picks the port to try, a few times over when the router says one is taken.
         */
        fun openRoom(mapper: PortMapper, bind: InetAddress?, leaseSeconds: Int, port: () -> Int, make: (TcpLink.Listener, Invite, Mapping) -> Room): Opening {
            val gateway = try { mapper.discover() } catch (e: IOException) { null } ?: return Opening.NotReachable(NO_ROUTER)
            val outside = try { mapper.externalAddress(gateway) } catch (e: Exception) { null }
                ?: return Opening.NotReachable("The router answered but didn't say its address on the internet, so there is no invite to give. Let your friend host.")
            if (!Addresses.onInternet(outside)) return Opening.NotReachable(behind(outside))
            repeat(PORT_TRIES) {
                val p = port()
                val listener = try { TcpLink.listen(bind, p) } catch (e: IOException) { return@repeat } // taken on this machine
                try {
                    val lease = try {
                        mapper.open(gateway, p, leaseSeconds, Room.MAPPING_NAME); leaseSeconds
                    } catch (f: UpnpFault) {
                        if (f.code != PortMapper.ONLY_PERMANENT) throw f
                        mapper.open(gateway, p, 0, Room.MAPPING_NAME); 0
                    }
                    return Opening.Opened(make(listener, Invite.create(outside, p), Mapping(mapper, gateway, p, lease)))
                } catch (f: UpnpFault) {
                    listener.close()
                    if (f.code != PortMapper.PORT_TAKEN) return Opening.NotReachable("The router wouldn't open a port to this machine: ${f.description} (UPnP error ${f.code}).")
                } catch (e: IOException) {
                    listener.close()
                    return Opening.NotReachable("The router stopped answering while it opened a port (${e.message}). Try again, or let your friend host.")
                }
            }
            return Opening.NotReachable("Every port tried was taken on the router or on this machine. Try again.")
        }

        private const val PORT_TRIES = 4

        const val NO_ROUTER = "No router on this network answered UPnP: it is turned off in the router's settings, or the router doesn't have it. " +
            "Turn UPnP on there, or let your friend host."

        private fun behind(outside: InetAddress) =
            "Your router's address outside is ${outside.hostAddress}, which isn't on the internet: another router stands in front of it (double NAT), " +
                "or your provider shares one address among its customers (CGNAT). A friend can't reach you here; let them host."
    }
}
