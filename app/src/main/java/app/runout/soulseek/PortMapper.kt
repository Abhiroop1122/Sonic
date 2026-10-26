package app.runout.soulseek

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory

/** Whether other people can reach this phone: what the router said when asked to forward Sonic's port. */
sealed interface PortState {
    data object Off : PortState
    data object Mapping : PortState
    /** The router forwards [port]; [via] "nat-pmp" or "upnp"; [external] the router's public address, when it said. */
    data class Open(val port: Int, val via: String, val external: String?) : PortState
    /** On mobile data: no router of ours to ask, and carriers don't let anyone in. */
    data object MobileData : PortState
    /** Not forwarded. [port] and [local] (this phone's address on the home network) are what to forward by hand. */
    data class Closed(val reason: String, val port: Int = 0, val local: String? = null) : PortState
    /**
     * The router forwards [port], but its own outside address [router] isn't the one the internet sees
     * ([public]): the provider has another router in front (CGNAT), and that one lets no one in.
     */
    data class BehindCgnat(val port: Int, val router: String, val public: String?) : PortState
}

/**
 * Asks the home router to forward Sonic's listening port, as Nicotine+ does: NAT-PMP first (one UDP
 * packet to the gateway), then UPnP (find the router by SSDP, then ask its WANIPConnection service).
 * Without it a phone behind a router can't be reached: two such people can't exchange files at
 * all, and search replies have to come the long way round. Only on Wi-Fi or Ethernet; renewed
 * before the lease runs out, and removed on sign-out.
 */
class PortMapper(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow<PortState>(PortState.Off)
    val state: StateFlow<PortState> = _state.asStateFlow()
    private val lock = Mutex()
    private var renewal: Job? = null
    private var mapped: Mapping? = null

    private data class Mapping(val port: Int, val via: String, val control: String? = null, val service: String? = null, val gateway: InetAddress? = null)

    /** Forward [port] (TCP, the same port outside and in), or say why not. */
    fun open(port: Int): Job = scope.launch(Dispatchers.IO) {
        lock.withLock {
            renewal?.cancel()
            if (port !in 1..65535) { _state.value = PortState.Closed("no listening port"); return@launch }
            val net = network()
            if (net == null) { _state.value = PortState.Off; return@launch }
            if (!net.wifi) { closeLocked(); _state.value = PortState.MobileData; return@launch }
            _state.value = PortState.Mapping
            val result = runCatching { natPmp(net.gateway, port) }.getOrNull()
                ?: runCatching { upnp(net.local, port) }.getOrNull()
            if (result == null) {
                mapped = null
                _state.value = PortState.Closed("your router didn't open a port. turn on upnp or nat-pmp in its settings.", port, net.local.hostAddress)
                return@launch
            }
            mapped = result.first
            _state.value = PortState.Open(port, result.first.via, result.second)
            renewal = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(RENEW_MS)
                    val again = runCatching {
                        if (result.first.via == "nat-pmp") natPmp(net.gateway, port) else upnp(net.local, port)
                    }.getOrNull()
                    if (again == null) _state.value = PortState.Closed("your router stopped forwarding sonic's port.", port, net.local.hostAddress)
                }
            }
        }
    }

    /** Remove the forward: on sign-out, or leaving Wi-Fi. */
    fun close(): Job = scope.launch(Dispatchers.IO) { lock.withLock { closeLocked(); _state.value = PortState.Off } }

    private fun closeLocked() {
        renewal?.cancel(); renewal = null
        val m = mapped ?: return
        mapped = null
        runCatching {
            when (m.via) {
                "nat-pmp" -> m.gateway?.let { natPmpRequest(it, m.port, lifetime = 0) }
                "upnp" -> if (m.control != null && m.service != null) soap(m.control, m.service, "DeletePortMapping", deleteArgs(m.port))
            }
        }
    }

    private data class Net(val wifi: Boolean, val gateway: InetAddress, val local: InetAddress)

    private fun network(): Net? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active)
        val wifi = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        val props = cm.getLinkProperties(active) ?: return null
        val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway ?: return null
        val local = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        return Net(wifi, gateway, local)
    }

    // ---- NAT-PMP (RFC 6886) -------------------------------------------------------------------

    private fun natPmp(gateway: InetAddress, port: Int): Pair<Mapping, String?>? {
        val mappedPort = natPmpRequest(gateway, port, LEASE_S) ?: return null
        if (mappedPort != port) { natPmpRequest(gateway, port, 0); return null }
        val external = runCatching { natPmpExternal(gateway) }.getOrNull()
        return Mapping(port, "nat-pmp", gateway = gateway) to external
    }

    private fun natPmpRequest(gateway: InetAddress, port: Int, lifetime: Int): Int? =
        exchange(gateway, natPmpMapRequest(port, lifetime))?.let { parseNatPmpMap(it) }

    private fun natPmpExternal(gateway: InetAddress): String? =
        exchange(gateway, byteArrayOf(0, 0))?.let { parseNatPmpExternal(it) }

    /** NAT-PMP's retry: 250 ms, doubling, four tries. */
    private fun exchange(gateway: InetAddress, request: ByteArray): ByteArray? = DatagramSocket().use { socket ->
        var timeout = 250
        repeat(4) {
            socket.soTimeout = timeout
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(gateway, NAT_PMP_PORT)))
            val buffer = ByteArray(16)
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet); return buffer.copyOf(packet.length) } catch (_: SocketTimeoutException) { timeout *= 2 }
        }
        null
    }

    // ---- UPnP IGD -----------------------------------------------------------------------------

    private fun upnp(local: InetAddress, port: Int): Pair<Mapping, String?>? {
        val wifi = app.getSystemService(WifiManager::class.java)
        val multicast = wifi?.createMulticastLock("sonic-upnp")?.apply { setReferenceCounted(false); acquire() }
        try {
            for (location in discover(local)) {
                val description = runCatching { get(location) }.getOrNull() ?: continue
                val (service, control) = parseIgd(description, location) ?: continue
                val added = runCatching { soap(control, service, "AddPortMapping", addArgs(port, local.hostAddress.orEmpty(), LEASE_S)) }
                    .recoverCatching { soap(control, service, "AddPortMapping", addArgs(port, local.hostAddress.orEmpty(), 0)) } // some only take permanent leases
                if (added.isFailure) continue
                val external = runCatching { soap(control, service, "GetExternalIPAddress", "") }.getOrNull()?.let { tagValue(it, "NewExternalIPAddress") }
                return Mapping(port, "upnp", control, service) to external
            }
            return null
        } finally {
            runCatching { multicast?.release() }
        }
    }

    /** SSDP: the routers that answer an M-SEARCH for an Internet gateway within two seconds. */
    private fun discover(local: InetAddress): List<String> = DatagramSocket(InetSocketAddress(local, 0)).use { socket ->
        val found = linkedSetOf<String>()
        for (target in listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1", "urn:schemas-upnp-org:device:InternetGatewayDevice:2")) {
            val request = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n").toByteArray()
            socket.send(DatagramPacket(request, request.size, InetSocketAddress("239.255.255.250", 1900)))
        }
        socket.soTimeout = 500
        val until = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < until) {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
            ssdpLocation(String(buffer, 0, packet.length, Charsets.UTF_8))?.let { found += it }
        }
        found.toList()
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 3_000; readTimeout = 3_000
        try { inputStream.bufferedReader().use { it.readText() } } finally { disconnect() }
    }

    private fun soap(control: String, service: String, action: String, args: String): String = (URL(control).openConnection() as HttpURLConnection).run {
        connectTimeout = 3_000; readTimeout = 5_000
        requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        setRequestProperty("SOAPAction", "\"$service#$action\"")
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$service\">$args</u:$action></s:Body></s:Envelope>"
        try {
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (responseCode !in 200..299) throw java.io.IOException("$action: HTTP $responseCode")
            inputStream.bufferedReader().use { it.readText() }
        } finally { disconnect() }
    }

    companion object {
        private const val NAT_PMP_PORT = 5351
        /** Asked for an hour, renewed every half: a phone that leaves without a goodbye leaves no hole open for long. */
        const val LEASE_S = 3_600
        private const val RENEW_MS = LEASE_S * 1000L / 2

        /** A NAT-PMP request to map TCP [port] to the same port outside, for [lifetime] seconds (0 removes it). */
        fun natPmpMapRequest(port: Int, lifetime: Int): ByteArray = ByteBuffer.allocate(12)
            .put(0).put(2).putShort(0).putShort(port.toShort()).putShort(if (lifetime == 0) 0 else port.toShort()).putInt(lifetime).array()

        /** The external port a NAT-PMP mapping reply grants, or null for an error or a malformed reply. */
        fun parseNatPmpMap(reply: ByteArray): Int? {
            if (reply.size < 16 || reply[0].toInt() != 0 || (reply[1].toInt() and 0xFF) != 130) return null
            val b = ByteBuffer.wrap(reply)
            if (b.getShort(2).toInt() != 0) return null
            return b.getShort(10).toInt() and 0xFFFF
        }

        fun parseNatPmpExternal(reply: ByteArray): String? {
            if (reply.size < 12 || (reply[1].toInt() and 0xFF) != 128 || ByteBuffer.wrap(reply).getShort(2).toInt() != 0) return null
            return (8..11).joinToString(".") { (reply[it].toInt() and 0xFF).toString() }
        }

        /** The LOCATION header of an SSDP answer. */
        fun ssdpLocation(response: String): String? = response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }

        /** The router's WAN connection service and its control URL, from its device description. */
        fun parseIgd(xml: String, location: String): Pair<String, String>? {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
            val services = doc.getElementsByTagName("service")
            val wanted = listOf("WANIPConnection:2", "WANIPConnection:1", "WANPPPConnection:1")
            val found = (0 until services.length).mapNotNull { i ->
                val s = services.item(i) as org.w3c.dom.Element
                val type = s.getElementsByTagName("serviceType").item(0)?.textContent?.trim() ?: return@mapNotNull null
                val control = s.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: return@mapNotNull null
                type to control
            }
            for (w in wanted) {
                val hit = found.firstOrNull { it.first.endsWith(w) } ?: continue
                return hit.first to URL(URL(base), hit.second).toString()
            }
            return null
        }

        fun addArgs(port: Int, client: String, lease: Int) =
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>" +
                "<NewInternalPort>$port</NewInternalPort><NewInternalClient>$client</NewInternalClient><NewEnabled>1</NewEnabled>" +
                "<NewPortMappingDescription>Sonic</NewPortMappingDescription><NewLeaseDuration>$lease</NewLeaseDuration>"

        fun deleteArgs(port: Int) = "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>"

        /**
         * What [state] means once the Soulseek server has said what address it sees ([public]): an
         * open port behind a router whose outside address is private, shared (100.64/10), or simply
         * not ours on the internet, opens nothing.
         */
        fun verdict(state: PortState, public: String?): PortState {
            if (state !is PortState.Open || state.external == null) return state
            val outside = state.external
            return if (isPrivate(outside) || (public != null && public != outside)) PortState.BehindCgnat(state.port, outside, public) else state
        }

        /** RFC 1918 private ranges and RFC 6598 carrier-grade NAT space. */
        fun isPrivate(ip: String): Boolean {
            val o = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (o.size != 4) return false
            return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 100 && o[1] in 64..127)
        }

        fun tagValue(xml: String, tag: String): String? = Regex("<(?:\\w+:)?$tag>([^<]*)</").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
