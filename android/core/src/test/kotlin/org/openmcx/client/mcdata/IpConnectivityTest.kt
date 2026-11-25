//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** MCData IP connectivity (TS 24.282 §20, TS 24.582 §13) against a fake MCData server and its UDP relay on loopback. */
class IpConnectivityTest {
    private val alice = "sip:alice.mcdata@mcx.test"
    private val bob = "sip:bob.mcdata@mcx.test"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relay = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { it.soTimeout = 3000 }

    @After fun close() = relay.close()

    private inner class Server(var status: Int = 200, var redirectTo: String? = null) : McdataMediaPeer {
        val invites = CopyOnWriteArrayList<Pair<List<Part>, List<Pair<String, String>>>>()
        val byes = CopyOnWriteArrayList<Dialog>()
        override val userId = alice
        override val clientId = "urn:uuid:1"
        override fun localAddress() = "127.0.0.1"
        override fun contact(feature: String) = "<sip:alice@127.0.0.1>"
        override fun resourcePriority(level: String): String? = null
        override suspend fun invite(parts: List<Part>, headers: List<Pair<String, String>>): Pair<SipResponse, Dialog?> {
            invites += parts to headers
            redirectTo?.let { selected ->
                redirectTo = null
                val info = McdataInfo(requestUri = selected).encode()
                return SipResponse(300, "Multiple Choices", Headers().add("Content-Type", McdataInfo.CONTENT_TYPE), info.toByteArray()) to null
            }
            if (status >= 300) return SipResponse(status, "Forbidden", Headers().add("Warning", "399 mcdata \"230 not authorised\"")) to null
            val sdp = IpConn.tunnelSdp(IpConn.TunnelMedia("127.0.0.1", relay.localPort, relay.localPort), 7)
            val (type, body) = Multipart.encode(listOf(Part("application/sdp", sdp.toByteArray())))
            return SipResponse(200, "OK", Headers().add("Content-Type", type), body) to
                Dialog("ip@x", "lt", "rt", NameAddr(alice), NameAddr("sip:mcdata@mcx.test"), "sip:mcdata@mcx.test", emptyList(), 1)
        }
        override suspend fun reinvite(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>) = SipResponse(200, "OK")
        override suspend fun bye(dialog: Dialog, headers: List<Pair<String, String>>) { byes += dialog }
        override suspend fun info(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>) = SipResponse(200, "OK")
        override fun event(text: String) = println(text)
    }

    private fun service(server: Server, address: String? = "10.77.0.1") =
        IpconService(scope, server, { "sip:alice@127.0.0.1:5061;transport=tls" }, address)

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(20) }
    }

    private fun receive(): DatagramPacket = DatagramPacket(ByteArray(2048), 2048).also { relay.receive(it) }

    @Test fun codecs() {
        // RFC 792 / RFC 791 / RFC 2784 round trips, with the checksums verified on decoding.
        val echo = IpConn.IcmpEcho(false, 0x1234, 7, "abcdef".toByteArray())
        val ip = IpConn.encodeIpv4(IpConn.Ipv4("10.77.0.1", "10.77.0.2", 1, 64, 9, IpConn.encodeIcmp(echo)))
        assertEquals(0, IpConn.checksum(ip, 0, 20))
        val (protocol, packet) = IpConn.decapsulate(IpConn.encapsulate(ip))
        assertEquals(IpConn.IPV4, protocol)
        val decoded = IpConn.decodeIpv4(packet)
        assertEquals("10.77.0.2", decoded.destination)
        val back = IpConn.decodeIcmp(decoded.payload)
        assertEquals(7, back.sequence); assertArrayEquals(echo.data, back.data)
        // TS 24.582 §13.4: no GRE key; a broken ICMP checksum is refused.
        assertTrue(runCatching { IpConn.decapsulate(byteArrayOf(0x20, 0, 0x08, 0, 0x45)) }.isFailure)
        val broken = IpConn.encodeIcmp(echo).also { it[9] = (it[9] + 1).toByte() }
        assertTrue(runCatching { IpConn.decodeIcmp(broken) }.isFailure)
    }

    @Test fun tunnelSdp() {
        // TS 24.582 §13.5.2.1 and §13.6.2.3: the media-level address wins, and the source port of the fmtp.
        val sdp = "v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\ns=-\r\nc=IN IP4 10.0.0.1\r\nt=0 0\r\nm=audio 4000 RTP/AVP 0\r\n" +
            "m=application 5000 udp MCDATA\r\nc=IN IP4 10.0.0.9\r\na=fmtp:MCDATA mcdata-ipconn-s-port=5002\r\n"
        assertEquals(IpConn.TunnelMedia("10.0.0.9", 5000, 5002), IpConn.parseTunnelSdp(sdp))
        val ours = IpConn.tunnelSdp(IpConn.TunnelMedia("10.0.0.2", 6000, 6000), 1)
        assertTrue(ours.contains("m=application 6000 udp MCDATA\r\n"))
        assertTrue(ours.contains("a=fmtp:MCDATA mcdata-ipconn-s-port=6000"))
        assertTrue(runCatching { IpConn.parseTunnelSdp("v=0\r\nc=IN IP4 1.2.3.4\r\nm=message 1 TCP/MSRP *\r\n") }.isFailure)
    }

    @Test fun originatingSessionAndTunnel() = runBlocking {
        val server = Server()
        val ipcon = service(server)
        val s = ipcon.open(bob)
        // TS 24.282 §20.2.1 steps 1-4, 7, 8a.
        val (parts, headers) = server.invites.single()
        assertEquals("one-to-one-ipconn", McdataInfo.parse(parts.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text).requestType)
        assertEquals(listOf(bob), ResourceLists.parse(parts.first { it.mediaType == ResourceLists.CONTENT_TYPE }.text))
        assertTrue(headers.contains("P-Preferred-Service" to IpConn.ICSI))
        assertTrue(headers.any { it.first == "Accept-Contact" && it.second == "*;+g.3gpp.mcdata.ipconn;require;explicit" })
        assertTrue(headers.any { it.first == "Contact" && it.second.contains("+g.3gpp.mcdata.ipconn") && it.second.contains("ipconn\"") })
        val offer = IpConn.parseTunnelSdp(parts.first { it.mediaType == "application/sdp" }.text)
        assertEquals(offer.port, offer.sourcePort)
        assertEquals("open", s.state)
        // TS 24.582 §13.1.2: the other end's echo request is answered through the tunnel.
        val request = IpConn.encodeIpv4(IpConn.Ipv4("10.77.0.2", "10.77.0.1", 1, 64, 1,
            IpConn.encodeIcmp(IpConn.IcmpEcho(false, 99, 1, "ping".toByteArray()))))
        relay.send(DatagramPacket(IpConn.encapsulate(request).let { it }, IpConn.encapsulate(request).size,
            InetSocketAddress(InetAddress.getLoopbackAddress(), offer.port)))
        val reply = receive()
        val echo = IpConn.decodeIcmp(IpConn.decodeIpv4(IpConn.decapsulate(reply.data.copyOf(reply.length)).second).payload)
        assertTrue(echo.reply); assertEquals(99, echo.identifier)
        // This client's ping: the reply from the other end is measured.
        val id = ipcon.ping("10.77.0.2", count = 1, size = 8)
        val sent = receive()
        val out = IpConn.decodeIpv4(IpConn.decapsulate(sent.data.copyOf(sent.length)).second)
        assertEquals("10.77.0.1", out.source)
        val outEcho = IpConn.decodeIcmp(out.payload)
        assertEquals(id, outEcho.identifier)
        val answer = IpConn.encapsulate(IpConn.encodeIpv4(IpConn.Ipv4("10.77.0.2", "10.77.0.1", 1, 63, 2,
            IpConn.encodeIcmp(outEcho.copy(reply = true)))))
        relay.send(DatagramPacket(answer, answer.size, InetSocketAddress(InetAddress.getLoopbackAddress(), offer.port)))
        waitFor("the echo reply") { s.pings.isNotEmpty() }
        assertEquals(63, s.pings.single()["ttl"])
        ipcon.close()
        assertEquals("closed", s.state)
        assertEquals(1, server.byes.size)
    }

    @Test fun refusedAndRedirected() = runBlocking {
        val refused = Server(status = 403)
        val ipcon = service(refused)
        assertEquals(403, (runCatching { ipcon.open(bob) }.exceptionOrNull() as SdsError).status)
        assertEquals("rejected", ipcon.session.value!!.state)
        // §20.2.1: a 300 to an alias target invites the selected user, naming the alias called.
        val alias = "sip:dispatcher.fa@mcx.test"
        val redirect = Server(redirectTo = bob)
        val s = service(redirect).open(alias, toAlias = true)
        val first = McdataInfo.parse(redirect.invites[0].first.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text)
        assertEquals(true, first.callToFunctionalAlias)
        val second = redirect.invites[1].first
        assertEquals(listOf(bob), ResourceLists.parse(second.first { it.mediaType == ResourceLists.CONTENT_TYPE }.text))
        assertEquals(alias, McdataInfo.parse(second.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text).calledFunctionalAlias)
        assertEquals("open", s.state)
        assertTrue(runCatching { service(Server(), address = null).open(bob) }.isFailure)
    }

    private class Answer(override val request: SipRequest) : MediaInvite {
        override val localTag = "ours"
        val responses = CopyOnWriteArrayList<Triple<Int, String?, List<Pair<String, String>>>>()
        override suspend fun respond(status: Int, body: ByteArray, contentType: String?, extra: List<Pair<String, String>>) {
            responses += Triple(status, if (body.isEmpty()) null else String(body), extra)
        }
    }

    private fun invite(sdp: String): SipRequest {
        val info = McdataInfo(requestType = "one-to-one-ipconn", requestUri = alice, callingUserId = bob,
            calledFunctionalAlias = "sip:dispatcher.fa@mcx.test")
        val (type, body) = Multipart.encode(listOf(Part("application/sdp", sdp.toByteArray()), Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())))
        val headers = Headers().add("Via", "SIP/2.0/TLS 10.0.0.1:5061;branch=z9hG4bK${UUID.randomUUID()}")
            .add("From", "<sip:mcdata@mcx.test>;tag=srv").add("To", "<$alice>").add("Call-ID", "ip-${UUID.randomUUID()}")
            .add("CSeq", "1 INVITE").add("Contact", "<sip:mcdata@10.0.0.1>;isfocus").add("P-Asserted-Service", IpConn.ICSI)
            .add("Accept-Contact", "*;+g.3gpp.mcdata.ipconn;require;explicit").add("Content-Type", type)
        return SipRequest("INVITE", "sip:alice@127.0.0.1", headers, body)
    }

    @Test fun terminatingSession() = runBlocking {
        val ipcon = service(Server())
        val tx = Answer(invite(IpConn.tunnelSdp(IpConn.TunnelMedia("127.0.0.1", relay.localPort, relay.localPort), 3)))
        assertTrue(ipcon.handle(tx))
        val (status, body, extra) = tx.responses.single()
        // TS 24.282 §20.2.2 steps 5, 7, 8.
        assertEquals(200, status)
        assertTrue(extra.contains("Require" to "timer"))
        assertTrue(extra.any { it.first == "Contact" && it.second.contains("+g.3gpp.mcdata.ipconn") })
        val local = IpConn.parseTunnelSdp(body!!)
        val s = ipcon.session.value!!
        assertEquals(bob, s.from); assertEquals("sip:dispatcher.fa@mcx.test", s.calledAlias); assertEquals("open", s.state)
        // Busy: a second session is refused 486.
        val busy = Answer(invite(IpConn.tunnelSdp(IpConn.TunnelMedia("127.0.0.1", 9, 9), 4)))
        ipcon.handle(busy)
        assertEquals(486, busy.responses.single().first)
        // A datagram from another source is dropped (TS 24.582 §13.1.3).
        DatagramSocket().use { other ->
            val p = IpConn.encapsulate(IpConn.encodeIpv4(IpConn.Ipv4("10.77.0.2", "10.77.0.1", 1, 64, 1,
                IpConn.encodeIcmp(IpConn.IcmpEcho(false, 1, 1, ByteArray(0))))))
            other.send(DatagramPacket(p, p.size, InetSocketAddress(InetAddress.getLoopbackAddress(), local.port)))
        }
        waitFor("the stray datagram dropped") { s.dropped.get() == 1L }
        // The server's BYE ends it.
        val bye = SipRequest("BYE", "sip:alice@127.0.0.1", Headers().add("Via", "SIP/2.0/TLS 10.0.0.1;branch=z9hG4bKb")
            .add("From", "<sip:mcdata@mcx.test>;tag=srv").add("To", "<$alice>;tag=ours").add("Call-ID", tx.request.callId).add("CSeq", "2 BYE"))
        val byeTx = Answer(bye)
        assertTrue(ipcon.handle(byeTx))
        assertEquals("closed", s.state)
        assertNull(ipcon.session.value?.takeIf { it.state == "open" })
        // An INVITE of another service is not this handler's.
        val other = invite("v=0\r\n").let { SipRequest(it.method, it.uri, Headers().add("Call-ID", "x").add("To", "<$alice>")
            .add("From", "<sip:mcdata@mcx.test>;tag=s").add("Via", "SIP/2.0/TLS 1.1.1.1;branch=z9hG4bKq").add("CSeq", "1 INVITE"), ByteArray(0)) }
        assertTrue(!ipcon.handle(Answer(other)))
    }
}
