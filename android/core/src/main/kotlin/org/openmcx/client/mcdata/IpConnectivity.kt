//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.ServerTransaction
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// MCData IP connectivity (TS 24.282 clause 20, TS 24.582 clause 13): a one-to-one session whose media is a GRE-in-UDP
// tunnel (RFC 8086) relayed by the controlling MCData function (TS 24.582 clause 13.3).
//
// The phone has no TUN device here (no VpnService): the IP application at each end of the tunnel is this client's own,
// a minimal IPv4 endpoint that answers ICMP echo requests and sends its own (RFC 792), as the Linux client's. The
// tunnel is UDP from a port this client binds: the MCData server sends to the address and port of the SDP, which the
// phone reaches as it reaches the RTP of a call (the media network, without NAT).

/** The codecs of the tunnel: its SDP (TS 24.582 clause 13.5.2.1, 13.6.2.3), GRE (RFC 2784) and IPv4/ICMP. */
object IpConn {
    const val ICSI = "urn:urn-7:3gpp-service.ims.icsi.mcdata.ipconn"
    const val FEATURE = "+g.3gpp.mcdata.ipconn"
    const val IPV4 = 0x0800
    const val IPV6 = 0x86DD
    private const val MAX_SDP = 16 * 1024

    class Error(message: String) : Exception("IP connectivity: $message")

    /** One end of the tunnel: the UDP address and port it receives on, and the source port it sends from (fmtp). */
    data class TunnelMedia(val address: String, val port: Int, val sourcePort: Int? = null)

    /**
     * TS 24.582 clause 13.5.2.1 (table 13.5.2.1-1) and clause 13.6.2.3, used by TS 24.282 clauses 20.2.0a and 20.2.0b:
     * "m=application <port> udp MCDATA" with the optional "mcdata-ipconn-s-port" fmtp parameter.
     */
    fun tunnelSdp(media: TunnelMedia, sessionId: Long): String {
        address(media.address)
        if (media.port !in 1..65535) throw Error("the tunnel needs a UDP port")
        return buildString {
            append("v=0\r\no=- $sessionId 1 IN IP4 ${media.address}\r\ns=MCData\r\nc=IN IP4 ${media.address}\r\nt=0 0\r\n")
            append("m=application ${media.port} udp MCDATA\r\n")
            media.sourcePort?.let { append("a=fmtp:MCDATA mcdata-ipconn-s-port=$it\r\n") }
        }
    }

    /** TS 24.582 clauses 13.5.2.1 and 13.6.2.3: the first "m=application ... udp MCDATA" section of an SDP. */
    fun parseTunnelSdp(sdp: String): TunnelMedia {
        if (sdp.length > MAX_SDP) throw Error("SDP too large")
        var session: String? = null; var media: String? = null; var port: Int? = null; var source: Int? = null
        var version = false; var inTunnel = false; var anyMedia = false
        for (raw in sdp.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            if (line.length < 2 || line[1] != '=') throw Error("malformed SDP line")
            val value = line.substring(2)
            when (line[0]) {
                'v' -> { if (value != "0") throw Error("unsupported SDP version"); version = true }
                'c' -> {
                    val p = value.split(' ').filter { it.isNotEmpty() }
                    if (p.size != 3 || p[0] != "IN" || p[1] != "IP4") throw Error("unsupported SDP connection")
                    address(p[2])
                    if (inTunnel) media = p[2] else if (!anyMedia) session = p[2]
                }
                'm' -> {
                    val p = value.split(' ').filter { it.isNotEmpty() }
                    anyMedia = true
                    inTunnel = port == null && p.size == 4 && p[0] == "application" && p[2] == "udp" && p[3] == "MCDATA"
                    if (inTunnel) port = portNumber(p[1])
                }
                'a' -> if (inTunnel && value.startsWith("fmtp:MCDATA ")) {
                    // The example of TS 24.582 clause 13.5.2.1 also shows a bare "mcdata-ipconn", which names no port.
                    for (parameter in value.substring(12).split(';')) {
                        val t = parameter.trim()
                        if (t.startsWith("mcdata-ipconn-s-port=")) source = portNumber(t.substring(21))
                    }
                }
            }
        }
        if (!version) throw Error("SDP without version")
        val p = port ?: throw Error("no IP connectivity media section")
        return TunnelMedia(media ?: session ?: throw Error("no connection address for the tunnel"), p, source)
    }

    private fun portNumber(text: String): Int = text.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw Error("invalid port")

    /** A dotted IPv4 address as its four octets. */
    fun address(text: String): ByteArray {
        val p = text.split('.')
        if (p.size != 4) throw Error("an IPv4 address is required")
        return ByteArray(4) { i -> (p[i].toIntOrNull()?.takeIf { it in 0..255 } ?: throw Error("an IPv4 address is required")).toByte() }
    }

    private fun addressText(b: ByteArray, at: Int) = (0 until 4).joinToString(".") { (b[at + it].toInt() and 0xff).toString() }
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) shl 8 or (b[at + 1].toInt() and 0xff)
    private fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte() }

    /** RFC 1071: the Internet checksum of [length] octets of [bytes] from [offset]. */
    fun checksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var sum = 0L
        var i = offset
        while (i + 1 < offset + length) { sum += u16(bytes, i); i += 2 }
        if (length % 2 == 1) sum += (bytes[offset + length - 1].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }

    /**
     * TS 24.582 clause 13.4: a GRE header (RFC 2784 §2.1) without checksum, key (item 3) or sequence number in front of
     * an IP packet; the UDP checksum (item 1) is the socket's.
     */
    fun encapsulate(ip: ByteArray): ByteArray {
        if (ip.isEmpty()) throw Error("empty IP packet")
        val version = (ip[0].toInt() and 0xff) shr 4
        if (version != 4 && version != 6) throw Error("not an IP packet")
        val out = ByteArray(4 + ip.size)
        put16(out, 2, if (version == 4) IPV4 else IPV6)
        ip.copyInto(out, 4)
        return out
    }

    /**
     * TS 24.582 clause 13.4 and RFC 2784: the GRE header removed; a GRE version other than 0, a key, a sequence number,
     * reserved bits, another protocol or a checksum that does not verify are refused. Returns (protocol, IP packet).
     */
    fun decapsulate(datagram: ByteArray): Pair<Int, ByteArray> {
        if (datagram.size < 4) throw Error("short GRE header")
        val flags = u16(datagram, 0)
        if (flags and 0x0007 != 0) throw Error("unsupported GRE version")
        if (flags and 0x2000 != 0) throw Error("a GRE key is not used (TS 24.582 13.4)")
        if (flags and 0x7ff8 != 0) throw Error("reserved GRE bits set")
        val protocol = u16(datagram, 2)
        if (protocol != IPV4 && protocol != IPV6) throw Error("unsupported GRE protocol type")
        var header = 4
        if (flags and 0x8000 != 0) {
            if (datagram.size < 8) throw Error("short GRE checksum field")
            if (checksum(datagram) != 0) throw Error("GRE checksum mismatch")
            header = 8
        }
        val packet = datagram.copyOfRange(header, datagram.size)
        if (packet.isEmpty() || (packet[0].toInt() and 0xff) shr 4 != (if (protocol == IPV4) 4 else 6))
            throw Error("GRE protocol type does not match the packet")
        return protocol to packet
    }

    data class Ipv4(val source: String, val destination: String, val protocol: Int, val ttl: Int, val identification: Int,
                    val payload: ByteArray)

    /** RFC 791 §3.1: an unfragmented IPv4 packet with a 20-octet header and its checksum. */
    fun encodeIpv4(p: Ipv4): ByteArray {
        val length = 20 + p.payload.size
        if (length > 65535) throw Error("IPv4 packet too large")
        val out = ByteArray(length)
        out[0] = 0x45
        put16(out, 2, length); put16(out, 4, p.identification); put16(out, 6, 0x4000)
        out[8] = p.ttl.toByte(); out[9] = p.protocol.toByte()
        address(p.source).copyInto(out, 12); address(p.destination).copyInto(out, 16)
        put16(out, 10, checksum(out, 0, 20))
        p.payload.copyInto(out, 20)
        return out
    }

    /** RFC 791 §3.1: version, header length, total length and header checksum verified; fragments refused. */
    fun decodeIpv4(b: ByteArray): Ipv4 {
        if (b.size < 20) throw Error("short IPv4 header")
        if ((b[0].toInt() and 0xff) shr 4 != 4) throw Error("not IPv4")
        val header = (b[0].toInt() and 0x0f) * 4
        if (header < 20 || b.size < header) throw Error("invalid IPv4 header length")
        val total = u16(b, 2)
        if (total < header || total > b.size) throw Error("invalid IPv4 total length")
        if (checksum(b, 0, header) != 0) throw Error("IPv4 header checksum mismatch")
        if (u16(b, 6) and 0x3fff != 0) throw Error("IPv4 fragments are not reassembled")
        return Ipv4(addressText(b, 12), addressText(b, 16), b[9].toInt() and 0xff, b[8].toInt() and 0xff, u16(b, 4),
            b.copyOfRange(header, total))
    }

    data class IcmpEcho(val reply: Boolean, val identifier: Int, val sequence: Int, val data: ByteArray)

    /** RFC 792: an Echo (type 8) or Echo Reply (type 0) with its checksum. */
    fun encodeIcmp(e: IcmpEcho): ByteArray {
        val out = ByteArray(8 + e.data.size)
        out[0] = (if (e.reply) 0 else 8).toByte()
        put16(out, 4, e.identifier); put16(out, 6, e.sequence)
        e.data.copyInto(out, 8)
        put16(out, 2, checksum(out))
        return out
    }

    fun decodeIcmp(b: ByteArray): IcmpEcho {
        if (b.size < 8) throw Error("short ICMP message")
        if ((b[0].toInt() != 0 && b[0].toInt() != 8) || b[1].toInt() != 0) throw Error("not an ICMP echo")
        if (checksum(b) != 0) throw Error("ICMP checksum mismatch")
        return IcmpEcho(b[0].toInt() == 0, u16(b, 4), u16(b, 6), b.copyOfRange(8, b.size))
    }
}

/** The UDP socket of this end of the tunnel (a test's, or [UdpTunnelSocket]). */
interface TunnelSocket {
    val port: Int
    fun send(bytes: ByteArray, address: String, port: Int)
    /** The next datagram and its source (address, port), or null after [timeoutMs]. */
    fun receive(timeoutMs: Int): Triple<ByteArray, String, Int>?
    fun close()
}

class UdpTunnelSocket(address: String) : TunnelSocket {
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(address), 0))
    override val port: Int get() = socket.localPort
    override fun send(bytes: ByteArray, address: String, port: Int) =
        socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(InetAddress.getByName(address), port)))
    override fun receive(timeoutMs: Int): Triple<ByteArray, String, Int>? {
        val buffer = ByteArray(65535)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.soTimeout = timeoutMs
        return try { socket.receive(packet); Triple(buffer.copyOf(packet.length), packet.address.hostAddress ?: "", packet.port) }
        catch (_: SocketTimeoutException) { null }
    }
    override fun close() = socket.close()
}

/** The IP connectivity session (one at a time, as the Linux client). */
class IpconSession(val originating: Boolean, val target: String?, val from: String?) {
    /** inviting, open, closed, rejected, failed. */
    @Volatile var state = "inviting"
    @Volatile var status: Int? = null
    @Volatile var warning: String? = null
    @Volatile var error: String? = null
    @Volatile var peer: IpConn.TunnelMedia? = null
    @Volatile var local: IpConn.TunnelMedia? = null
    /** TS 24.282 §20.2.2 steps 3 to 3B: the alias the caller presented and the alias called; after a 300 the user selected. */
    @Volatile var functionalAlias: String? = null
    @Volatile var calledAlias: String? = null
    @Volatile var selected: String? = null
    val sent = AtomicLong(); val received = AtomicLong(); val dropped = AtomicLong(); val echoesAnswered = AtomicLong()
    /** The echo replies to this client's pings: (from, sequence, octets, ttl, round trip ms). */
    val pings = CopyOnWriteArrayList<Map<String, Any>>()
    internal var dialog: Dialog? = null
    internal var socket: TunnelSocket? = null
    internal var job: Job? = null
    internal val outstanding = ConcurrentHashMap<Int, Long>()
}

/**
 * The MCData client's side of IP connectivity: [open] (TS 24.282 clause 20.2.1), the terminating INVITE in [handle]
 * (clause 20.2.2), [close], and the tunnel of TS 24.582 clause 13.1 with this client's ICMP endpoint ([ping]).
 * [address] is the configured IP address of this client in the tunnel (`mcdata.ipconn.address`, clause 20.2.0a step 1).
 */
class IpconService(
    private val scope: CoroutineScope,
    private val peer: McdataMediaPeer,
    /** The Contact URI of this client's registration. */
    private val contactUri: () -> String,
    val address: String?,
    private val socketFactory: (String) -> TunnelSocket = ::UdpTunnelSocket,
) : ServiceHandler {
    private val _session = MutableStateFlow<IpconSession?>(null)
    val session: StateFlow<IpconSession?> = _session
    private fun changed(s: IpconSession) { _session.value = null; _session.value = s }
    private fun event(text: String) = peer.event("ipconn: $text")

    /** TS 24.282 clause 20.2.1 steps 1 to 3 and clause 20.2.2 steps 7, 8: the IP connectivity tag and ICSI. */
    private fun contact() = "<${contactUri()}>;${IpConn.FEATURE};+g.3gpp.icsi-ref=\"${IpConn.ICSI.replace(":", "%3A")}\""

    private fun bind(s: IpconSession): String {
        val local = peer.localAddress()
        val socket = socketFactory(local)
        s.socket = socket
        // TS 24.282 clauses 20.2.0a and 20.2.0b: sent from the port it receives on, which the fmtp names.
        s.local = IpConn.TunnelMedia(local, socket.port, socket.port)
        return IpConn.tunnelSdp(s.local!!, System.currentTimeMillis() / 1000)
    }

    /**
     * TS 24.282 clause 20.2.1: an IP connectivity session with [target], an MCData ID or, with [toAlias], a functional
     * alias (the server's 300 (Multiple Choices) names the user to invite, the alias then called, [calledAlias]).
     */
    suspend fun open(target: String, toAlias: Boolean = false, calledAlias: String? = null): IpconSession {
        if (address.isNullOrEmpty()) throw SdsError("no IP connectivity address is configured (mcdata.ipconn.address)")
        if (_session.value?.state in setOf("inviting", "open")) throw SdsError("an IP connectivity session is already open")
        val s = IpconSession(true, target, null)
        s.calledAlias = calledAlias
        if (calledAlias != null) s.selected = target
        val sdp = bind(s)
        changed(s)
        // Step 8: request type "one-to-one-ipconn"; 8b i the alias indication; 8b ii the alias in use while active.
        val info = McdataInfo(requestType = "one-to-one-ipconn", callToFunctionalAlias = if (toAlias) true else null,
            functionalAlias = peer.aliasInUse(), calledFunctionalAlias = calledAlias)
        val parts = listOf(Part("application/sdp", sdp.toByteArray()), Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()),
            // Step 7: the invited user (or alias) in a resource list.
            Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray()))
        val headers = listOf(
            "Accept-Contact" to "*;${IpConn.FEATURE};require;explicit",                                       // step 2
            "Accept-Contact" to "*;+g.3gpp.icsi-ref=\"${IpConn.ICSI.replace(":", "%3A")}\";require;explicit", // step 3
            "P-Preferred-Service" to IpConn.ICSI,                                                             // step 4
            "Contact" to contact(),                                                                           // step 1
            "Supported" to "timer")                                                                           // step 5
        event("inviting $target${if (toAlias) " (functional alias)" else ""}")
        val (response, dialog) = try { peer.invite(parts, headers) }
            catch (e: Exception) { fail(s, e.message ?: "INVITE failed", "failed"); throw SdsError(e.message ?: "INVITE failed") }
        s.status = response.status
        s.warning = response.headers["Warning"]
        if (dialog == null) {
            if (response.status == 300 && toAlias) {
                // Clause 20.2.1: the MCData ID of <mcdata-request-uri> invited, naming the alias called.
                val selected = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }
                    ?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }?.requestUri
                fail(s, "redirected to ${selected ?: "nobody"}", "rejected")
                selected ?: throw SdsError("IP connectivity: 300 without a selected user", 300)
                event("functional alias $target: inviting $selected")
                return open(selected, false, target)
            }
            fail(s, "${response.status} ${response.reason}${s.warning?.let { " ($it)" } ?: ""}", "rejected")
            throw SdsError("IP connectivity: ${response.status} ${response.reason}", response.status)
        }
        s.dialog = dialog
        try {
            val answer = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == "application/sdp" }
                ?: throw IpConn.Error("the answer has no tunnel")
            s.peer = IpConn.parseTunnelSdp(answer.text)
        } catch (e: Exception) {
            // Clause 20.2.1: the media plane could not be established.
            fail(s, e.message ?: "bad answer", "failed")
            peer.bye(dialog)
            throw SdsError(e.message ?: "bad answer")
        }
        start(s)
        event("open with $target, tunnel to ${s.peer!!.address}:${s.peer!!.port}")
        return s
    }

    private fun fail(s: IpconSession, why: String, state: String) {
        s.error = why; s.state = state
        s.job?.cancel(); s.socket?.close()
        event("$state: $why")
        changed(s)
    }

    /** TS 24.582 clause 13.1.2: the tunnel is up. */
    private fun start(s: IpconSession) {
        s.state = "open"
        changed(s)
        s.job = scope.launch(Dispatchers.IO) {
            val socket = s.socket ?: return@launch
            while (isActive && s.state == "open") {
                val datagram = try { socket.receive(300) } catch (e: Exception) { if (s.state == "open") event("socket: ${e.message}"); break }
                    ?: continue
                runCatching { receivedDatagram(s, datagram.first, datagram.second, datagram.third) }
                    .onFailure { s.dropped.incrementAndGet(); event("dropped: ${it.message}") }
            }
        }
    }

    /**
     * TS 24.582 clauses 13.1.2, 13.1.3 and 13.4: a datagram from the other end is decapsulated and handed to the IP
     * application (an echo request answered, a reply measured); one from elsewhere, or not to this address, is dropped.
     */
    internal fun receivedDatagram(s: IpconSession, bytes: ByteArray, from: String, port: Int) {
        val other = s.peer ?: return
        if (from != other.address || port != (other.sourcePort ?: other.port)) { s.dropped.incrementAndGet(); return }
        val (protocol, packet) = IpConn.decapsulate(bytes)
        if (protocol != IpConn.IPV4) { s.dropped.incrementAndGet(); return }
        val ip = IpConn.decodeIpv4(packet)
        if (ip.destination != address) { s.dropped.incrementAndGet(); return }
        s.received.incrementAndGet()
        if (ip.protocol != 1) { changed(s); return }
        val echo = IpConn.decodeIcmp(ip.payload)
        if (!echo.reply) {
            transmit(s, IpConn.encodeIpv4(IpConn.Ipv4(address!!, ip.source, 1, 64, ip.identification,
                IpConn.encodeIcmp(echo.copy(reply = true)))))
            s.echoesAnswered.incrementAndGet()
        } else {
            val sent = s.outstanding.remove(echo.identifier shl 16 or echo.sequence) ?: return
            s.pings += mapOf("from" to ip.source, "sequence" to echo.sequence, "bytes" to echo.data.size, "ttl" to ip.ttl,
                "rtt_ms" to System.currentTimeMillis() - sent)
        }
        changed(s)
    }

    /** TS 24.582 clauses 13.1.2, 13.1.3, 13.4: an IP packet in GRE-in-UDP to the address and port of the other SDP. */
    private fun transmit(s: IpconSession, packet: ByteArray) {
        val other = s.peer ?: throw SdsError("no tunnel")
        (s.socket ?: throw SdsError("no tunnel")).send(IpConn.encapsulate(packet), other.address, other.port)
        s.sent.incrementAndGet()
    }

    /** The IP application: [count] ICMP echo requests (RFC 792) of [size] octets to [destination] into the tunnel. */
    fun ping(destination: String, count: Int = 3, size: Int = 56): Int {
        val s = _session.value?.takeIf { it.state == "open" } ?: throw SdsError("no IP connectivity session is open")
        require(count in 1..100 && size in 0..1400) { "ipcon-ping sends 1 to 100 echoes of at most 1400 octets" }
        IpConn.address(destination)
        val identifier = (System.nanoTime() and 0xffff).toInt()
        for (sequence in 1..count) {
            val data = ByteArray(size) { i -> ('a' + (i + sequence) % 26).code.toByte() }
            val packet = IpConn.encodeIpv4(IpConn.Ipv4(address!!, destination, 1, 64, sequence,
                IpConn.encodeIcmp(IpConn.IcmpEcho(false, identifier, sequence, data))))
            s.outstanding[identifier shl 16 or sequence] = System.currentTimeMillis()
            transmit(s, packet)
        }
        changed(s)
        event("$count echoes to $destination (identifier $identifier)")
        return identifier
    }

    /** Release the session with a SIP BYE and close the tunnel. */
    suspend fun close() {
        val s = _session.value ?: throw SdsError("no IP connectivity session")
        val dialog = s.dialog
        if (s.state == "open") s.state = "closed"
        s.job?.cancel(); s.socket?.close()
        changed(s)
        dialog?.let { peer.bye(it) }
        event("closed")
    }

    override suspend fun handle(tx: ServerTransaction): Boolean = handle(object : MediaInvite {
        override val request get() = tx.request
        override val localTag get() = tx.localTag
        override suspend fun respond(status: Int, body: ByteArray, contentType: String?, extra: List<Pair<String, String>>) {
            tx.respond(status, body = body, contentType = contentType, extra = extra)
        }
    })

    private fun ipconn(request: org.openmcx.client.sip.SipRequest) =
        request.headers["P-Asserted-Service"]?.trim() == IpConn.ICSI ||
            request.headers.values("Accept-Contact").any { it.contains(IpConn.FEATURE) }

    suspend fun handle(tx: MediaInvite): Boolean {
        val request = tx.request
        val s = _session.value
        val ours = s?.dialog?.callId == request.callId && s?.dialog != null
        when (request.method) {
            "BYE" -> {
                if (!ours) return false
                tx.respond(200)
                if (s!!.state == "open") s.state = "closed"
                s.job?.cancel(); s.socket?.close()
                event("released by the server")
                changed(s)
                return true
            }
            "INVITE" -> {}
            else -> return false
        }
        if (ours) {
            // A session refresh keeps the tunnel.
            tx.respond(200, IpConn.tunnelSdp(s!!.local!!, System.currentTimeMillis() / 1000).toByteArray(), "application/sdp",
                listOf("Contact" to contact()))
            return true
        }
        if (request.to.tag != null || !ipconn(request)) return false
        // TS 24.282 clause 20.2.2: accepted when the client is free.
        if (s != null && s.state in setOf("inviting", "open")) { tx.respond(486); return true }
        if (address.isNullOrEmpty()) { tx.respond(480); return true }
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrElse { tx.respond(400); return true }
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
        val offer = try { IpConn.parseTunnelSdp(parts.firstOrNull { it.mediaType == "application/sdp" }?.text ?: throw IpConn.Error("no SDP")) }
            catch (e: Exception) { event("offer refused: ${e.message}"); tx.respond(488); return true }
        val session = IpconSession(false, null, info?.callingUserId)
        session.peer = offer
        // Steps 3 to 3B: who calls, and under which functional aliases.
        session.functionalAlias = info?.functionalAlias
        session.calledAlias = info?.calledFunctionalAlias
        val sdp = try { bind(session) } catch (e: Exception) { tx.respond(480); event("no tunnel socket: ${e.message}"); return true }
        session.dialog = Dialog.fromRequest(request, tx.localTag)
        val expires = request.headers["Session-Expires"]?.substringBefore(';')?.trim()
        // Steps 5, 7 and 8: "timer" in Require, the IP connectivity tag and ICSI in the Contact of the 200 (OK).
        tx.respond(200, sdp.toByteArray(), "application/sdp", listOfNotNull("Contact" to contact(), "Require" to "timer",
            expires?.let { "Session-Expires" to "$it;refresher=uas" }))
        start(session)
        event("incoming from ${session.from}, tunnel to ${offer.address}:${offer.port}")
        return true
    }

    override suspend fun flowLost() { _session.value?.let { if (it.state == "open") fail(it, "flow lost", "failed") } }
    override suspend fun logOff() { _session.value?.takeIf { it.state == "open" }?.let { runCatching { close() } } }
}
