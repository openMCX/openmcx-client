//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.mcdata.McdataPreSession
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.AudioSink
import org.openmcx.client.media.AudioSource
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.SpeechDecoder
import org.openmcx.client.media.SpeechEncoder
import org.openmcx.client.mcptt.CallType
import org.openmcx.client.mcptt.LocalMedia
import org.openmcx.client.mcptt.McpcMessage
import org.openmcx.client.mcptt.McpcReasonCode
import org.openmcx.client.mcptt.McpcSessionType
import org.openmcx.client.mcptt.McpcType
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcptt.McpttPreSession
import org.openmcx.client.mcptt.McpttSdp
import org.openmcx.client.mcptt.McpttService
import org.openmcx.client.mcptt.PreSessionControl
import org.openmcx.client.mcptt.PreSessionControlState
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCPTT pre-established session (TS 24.379 §8, §10.1.1.2.2, §11.1.1.2.2; TS 24.380 §8.3, §9.2.2) on the wire of a
 * fake SIP core, the participating function's media plane control channel a loopback socket of the test.
 */
class McpttPreSessionTest {
    private val alice = "sip:alice.mcptt@mcx.test"
    private val bob = "sip:bob.mcptt@mcx.test"
    private val group = "sip:operations@mcx.test"
    private val psi = "sip:mcptt@mcx.test"
    private val sessionUri = "sip:mcptt-session@mcx.test;mcptt-pre-session=x1"
    private val callIdentity = "sip:mcptt@mcx.test;call=42"
    private val serverFloor = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 5000 }
    private val serverAudio = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    private val labs = CopyOnWriteArrayList<McxClient>()

    @After fun close() { labs.forEach { it.stop() }; serverFloor.close(); serverAudio.close() }

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "$alice", "psi": "$psi", "groups": [], "answer_mode": "automatic",
                  "session_expires": 1800, "manual_answer_timeout": 20, "subscribe_configuration": false,
                  "subscribe_group_documents": false},
        "media": {"port_range": [22000, 22400], "amr_wb_mode": 2}}""")

    private val noHttp = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
    }
    private val silence = object : AudioDevices {
        override fun source(duplex: Boolean) = object : AudioSource { override fun read(frame: ShortArray): Boolean { Thread.sleep(20); frame.fill(0); return true }; override fun close() {} }
        override fun sink() = object : AudioSink { override fun write(frame: ShortArray) {}; override fun close() {} }
    }
    private val codecs = object : SpeechCodecFactory {
        override fun encoder(mode: Int) = object : SpeechEncoder {
            override fun encode(pcm: ShortArray) = AmrWb.Frame(2, true, ByteArray((AmrWb.frameBits(2) + 7) / 8))
            override fun close() {}
        }
        override fun decoder() = object : SpeechDecoder { override fun decode(frame: AmrWb.Frame?) = ShortArray(AmrWb.FRAME_SAMPLES); override fun close() {} }
    }

    private fun tokens() = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
        mcpttId = alice, mcdataId = null, mcvideoId = null, sipUri = null, clientId = null, refreshToken = "RT", nonce = "n")

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(30) }
    }

    private val serverMedia get() = LocalMedia("127.0.0.1", serverAudio.localPort, serverFloor.localPort, 777)

    private class Lab(val client: McxClient, val service: McpttService, val core: FakeSipCore)

    /** The core answers the session's INVITE with the session's SDP answer and URI, every REFER 200 (or [referStatus]). */
    private fun start(referStatus: Int? = null): Lab {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(), noHttp,
            connector = { _, _ -> FakeSipCore(
                statusFor = { r -> if (r.method == "REFER" && r.headers["Refer-To"]?.contains("cid:") == true) referStatus else null },
                headersFor = { r -> if (r.method == "INVITE" && r.uri.endsWith(";mcptt-pre-established")) listOf("Contact" to "<$sessionUri>") else emptyList() },
                bodyFor = { r -> if (r.method == "INVITE" && r.body.isNotEmpty())
                    "application/sdp" to McpttSdp.answer(McpttSdp.remote(String(r.body)), serverMedia).toByteArray() else null }).also { cores += it } })
        labs += client
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false))
        val service = McpttService(client, codecs, silence)
        client.handlers += service
        client.start()
        waitFor("ready") { client.status.value.phase == "ready" }
        return Lab(client, service, cores.last())
    }

    private fun established(): Lab = start().also { runBlocking { it.service.preSession.establish() } }

    private fun preInvite(lab: Lab) = lab.core.requests.first { it.method == "INVITE" && it.uri.endsWith(";mcptt-pre-established") }
    private fun clientFloorPort(lab: Lab) = McpttSdp.remote(String(preInvite(lab).body)).floorPort

    /** The participating function's media plane control message to the client's control port. */
    private fun sendControl(lab: Lab, message: McpcMessage) {
        val data = message.encode()
        serverFloor.send(DatagramPacket(data, data.size, InetSocketAddress("127.0.0.1", clientFloorPort(lab))))
    }

    /** The next MCPC message from the client on the session's control channel (floor control skipped). */
    private fun receiveControl(): McpcMessage {
        val buffer = ByteArray(1500)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            serverFloor.receive(packet)
            if (McpcMessage.isMcpc(buffer, packet.length)) return McpcMessage.decode(buffer.copyOf(packet.length))!!
        }
    }

    private fun connect(type: Int, identity: String = callIdentity, group: String? = null, from: String? = null, controlChannel: Int = 2) =
        McpcMessage(McpcType.CONNECT, 777, ackRequired = true, sessionType = type, sessionIdentity = identity, groupIdentity = group,
            mediaStream = 1, controlChannel = controlChannel, invitingUser = from, answerState = 0)

    private var cseq = 100

    /** A request of the participating function in the session's dialog. */
    private fun inSession(lab: Lab, method: String, parts: List<Part>, extra: List<Pair<String, String>> = emptyList()) {
        val invite = preInvite(lab)
        val headers = Headers().add("Via", "SIP/2.0/TLS 127.0.0.1:5061;branch=z9hG4bKpre${cseq}").add("Max-Forwards", "70")
            .add("From", "<$psi;mcptt-pre-established>;tag=core").add("To", invite.headers["From"]!!)
            .add("Call-ID", invite.callId).add("CSeq", "${cseq++} $method").add("Contact", "<$sessionUri>")
        extra.forEach { (n, v) -> headers.add(n, v) }
        val body = if (parts.isEmpty()) ByteArray(0) else Multipart.encode(parts).let { (t, b) -> headers.add("Content-Type", t); b }
        lab.core.send(SipRequest(method, lab.core.contact(), headers, body))
    }

    @Test fun theSessionInviteOffersTheMediaWithHostCandidates() = runBlocking {
        val lab = established()
        val invite = preInvite(lab)
        // TS 24.379 §8.2.1: the PSI identifying pre-established session set-up; an SDP offer only (TS 37.579-1 table 5.3.3.4-1).
        assertEquals("$psi;mcptt-pre-established", invite.uri)
        assertEquals("application/sdp", invite.headers["Accept"])
        assertEquals("application/sdp", invite.contentType)
        assertTrue(invite.headers.all("Accept-Contact").any { it.contains("+g.3gpp.mcptt") })
        assertEquals("1800", invite.headers["Session-Expires"])
        val sdp = String(invite.body)
        val offer = McpttSdp.remote(sdp)
        assertTrue(offer.floorControl)
        // RFC 8839: the credentials and the host candidates of speech RTP, RTCP and the floor control port.
        assertTrue(sdp.contains("a=ice-ufrag:") && sdp.contains("a=ice-pwd:"))
        assertTrue(sdp.contains(Regex("a=candidate:1 1 UDP \\d+ 127.0.0.1 ${offer.audioPort} typ host")))
        assertTrue(sdp.contains(Regex("a=candidate:1 2 UDP \\d+ 127.0.0.1 ${offer.audioPort + 1} typ host")))
        assertTrue(sdp.contains(Regex("a=candidate:2 1 UDP \\d+ 127.0.0.1 ${offer.floorPort} typ host")))
        val status = lab.service.preSession.status.value!!
        assertEquals("established", status.state)
        assertEquals(sessionUri, status.uri)
        assertEquals("not-in-use", status.control)
        assertTrue(lab.service.preSession.usable)
    }

    @Test fun aGroupCallGoesByReferAndIsConfirmedByConnect() = runBlocking {
        val lab = established()
        val call = async(Dispatchers.Default) { lab.service.groupCall(group) }
        waitFor("REFER") { lab.core.requests.any { it.method == "REFER" } }
        val refer = lab.core.requests.first { it.method == "REFER" }
        // TS 24.379 §10.1.1.2.2.1: to the session's URI; Refer-To a cid URL; RFC 4488, RFC 4538, RFC 5368.
        assertEquals(sessionUri, refer.uri)
        assertEquals("<$psi;mcptt-pre-established>", refer.headers["To"])
        assertEquals("false", refer.headers["Refer-Sub"])
        assertTrue(refer.headers.all("Supported").contains("norefersub"))
        assertEquals("multiple-refer", refer.headers["Require"])
        assertTrue(refer.headers["Target-Dialog"]!!.startsWith(preInvite(lab).callId + ";local-tag="))
        val cid = refer.headers["Refer-To"]!!.removePrefix("<cid:").removeSuffix(">")
        val list = Multipart.parse(refer.contentType, refer.body).single()
        assertTrue(list.headers.contains("Content-ID" to "<$cid>"))
        val entry = ResourceLists.parse(list.text).single()
        assertTrue("the entry's two Accept-Contact", entry.contains("Accept-Contact=") && entry.substringAfter("Accept-Contact=").contains("Accept-Contact="))
        val (target, body) = McdataPreSession.parseEntryUri(entry)
        assertEquals(group, target)
        assertEquals("prearranged", McpttInfo.parse(body.first { it.mediaType == McpttInfo.CONTENT_TYPE }.text).sessionType)
        val offer = McpttSdp.remote(body.first { it.mediaType == "application/sdp" }.text)
        assertEquals(clientFloorPort(lab), offer.floorPort) // the session's media
        // TS 24.380 §9.2.2.4.2: the Connect of this client's call, acknowledged; the call is established.
        sendControl(lab, connect(McpcSessionType.PREARRANGED, group = group))
        val ack = receiveControl()
        assertEquals(McpcType.ACKNOWLEDGE, ack.type)
        assertEquals(McpcReasonCode.ACCEPTED, ack.reasonCode)
        assertEquals(777L, ack.ssrc)
        val c = call.await()
        assertEquals("established", c.state)
        assertTrue(c.preEstablished)
        assertEquals(callIdentity, c.sessionUri)
        assertEquals("in-use", lab.service.preSession.status.value!!.control)
        // TS 24.379 §6.2.4.2: the user leaves by REFER "BYE"; the session stays.
        lab.service.hangup(c)
        val leave = lab.core.requests.last { it.method == "REFER" }
        assertEquals("<$callIdentity;method=BYE>", leave.headers["Refer-To"])
        assertTrue(lab.core.requests.none { it.method == "BYE" })
        assertEquals("released", c.state)
        assertTrue(lab.service.preSession.usable)
        // The next call uses the same session: the session's ports, not new ones.
        val second = async(Dispatchers.Default) { lab.service.privateCall(bob) }
        waitFor("second REFER") { lab.core.requests.count { it.method == "REFER" } == 3 }
        val privateEntry = ResourceLists.parse(Multipart.parse(lab.core.requests.last { it.method == "REFER" }.contentType,
            lab.core.requests.last { it.method == "REFER" }.body).single().text).single()
        assertTrue("a private call's Answer-Mode", privateEntry.contains("Answer-Mode=Auto"))
        sendControl(lab, connect(McpcSessionType.PRIVATE))
        receiveControl()
        assertEquals(CallType.PRIVATE, second.await().type)
    }

    @Test fun aRefusedReferFailsTheCallAndKeepsTheSession() = runBlocking {
        val lab = start(referStatus = 403).also { runBlocking { it.service.preSession.establish() } }
        assertTrue(runCatching { lab.service.groupCall(group) }.isFailure)
        assertTrue(lab.service.preSession.usable)
        assertTrue(lab.service.calls.value.isEmpty())
    }

    @Test fun aDisconnectInPlaceOfTheConnectFailsTheCall() = runBlocking {
        val lab = established()
        val call = async(Dispatchers.Default) { runCatching { lab.service.groupCall(group) } }
        waitFor("REFER") { lab.core.requests.any { it.method == "REFER" } }
        sendControl(lab, McpcMessage(McpcType.DISCONNECT, 777, ackRequired = true, sessionIdentity = sessionUri, reasonCause = 0))
        assertEquals(McpcType.ACKNOWLEDGE, receiveControl().type)
        assertTrue(call.await().isFailure)
        waitFor("free") { lab.service.preSession.usable }
    }

    @Test fun aCallOfferedByConnectAndEndedByDisconnect() = runBlocking {
        val lab = established()
        sendControl(lab, connect(McpcSessionType.PREARRANGED, group = group, from = bob))
        assertEquals(McpcReasonCode.ACCEPTED, receiveControl().reasonCode)
        waitFor("call") { lab.service.calls.value.firstOrNull()?.state == "established" }
        val c = lab.service.calls.value.single()
        assertFalse(c.outgoing)
        assertEquals(group, c.groupId)
        assertEquals(bob, c.peer)
        assertTrue(c.preEstablished)
        // A second Connect while the session is in use is the call's own: acknowledged, no second call.
        sendControl(lab, McpcMessage(McpcType.DISCONNECT, 777, ackRequired = true, sessionIdentity = callIdentity))
        assertEquals(McpcType.ACKNOWLEDGE, receiveControl().type)
        waitFor("released") { c.state == "released" }
        assertTrue(lab.service.preSession.usable)
    }

    @Test fun aPrivateCallOfferedByReInviteIsAnsweredWithinTheSession() = runBlocking {
        val lab = established()
        val info = McpttInfo(sessionType = "private", callingUserId = bob, requestUri = alice)
        // TS 24.380 §14.2.6, §14.3.7: an offer with "mc_no_floor_ctrl".
        val offer = McpttSdp.offer(serverMedia).replace("mc_floor_ssrc=777", "mc_floor_ssrc=777;mc_no_floor_ctrl")
        inSession(lab, "INVITE", listOf(Part("application/sdp", offer.toByteArray()), Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray())))
        assertEquals(180, lab.core.answers.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!.status)
        waitFor("ringing") { lab.service.calls.value.firstOrNull()?.state == "ringing" }
        assertTrue(lab.service.answer())
        val ok = lab.core.answers.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals(200, ok.status)
        val answer = String(ok.body)
        assertTrue(answer.contains("mc_no_floor_ctrl"))
        assertFalse(answer.contains("mc_floor_ssrc"))
        assertTrue(answer.contains("m=application ${clientFloorPort(lab)} "))
        // The Connect that follows gives the call its session identity (TS 24.380 §9.2.2.4.2).
        sendControl(lab, connect(McpcSessionType.PRIVATE, from = bob, controlChannel = 0))
        receiveControl()
        val c = lab.service.calls.value.single()
        waitFor("established") { c.state == "established" }
        assertEquals(callIdentity, c.sessionUri)
        assertFalse(c.floorControl)
    }

    @Test fun aModificationByTheFunctionTakesTheOfferedFloorPriority() = runBlocking {
        val lab = established()
        inSession(lab, "UPDATE", listOf(Part("application/sdp", McpttSdp.offer(serverMedia.copy(floorPriority = 7)).toByteArray())))
        val ok = lab.core.answers.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals(200, ok.status)
        assertEquals(7, McpttSdp.remote(String(ok.body)).floorPriority) // TS 24.380 §14.3.3
        assertEquals(1, lab.service.preSession.status.value!!.modified)
    }

    @Test fun theUsersModificationAndRelease() = runBlocking {
        val lab = established()
        lab.service.preSession.modify(implicitFloor = true)
        val reinvite = lab.core.requests.last { it.method == "INVITE" }
        assertNotNull(reinvite.to.tag)
        assertTrue(McpttSdp.remote(String(reinvite.body)).implicitRequest)
        assertTrue(lab.service.preSession.status.value!!.implicit)
        lab.service.preSession.release()
        assertEquals(1, lab.core.requests.count { it.method == "BYE" })
        assertEquals("released", lab.service.preSession.status.value!!.state)
        assertFalse(lab.service.preSession.usable)
    }

    @Test fun theFunctionsByeEndsTheSession() = runBlocking {
        val lab = established()
        inSession(lab, "BYE", emptyList())
        assertEquals(200, lab.core.answers.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!.status)
        waitFor("released") { lab.service.preSession.status.value!!.state == "released" }
        assertFalse(lab.service.preSession.wanted)
    }

    @Test fun mcpcMessagesRoundTrip() {
        val c = McpcMessage(McpcType.CONNECT, 0x01020304, ackRequired = true, sessionType = McpcSessionType.CHAT, sessionIdentity = callIdentity,
            groupIdentity = group, mediaStream = 1, controlChannel = 2, warningText = "w", answerState = 1, invitingUser = bob,
            invitedUser = alice, pckIMessage = byteArrayOf(1, 2, 3))
        val back = McpcMessage.decode(c.encode())!!
        assertEquals(c, back)
        assertTrue(McpcMessage.isMcpc(c.encode()))
        val ack = McpcMessage.acknowledge(5, McpcReasonCode.BUSY)
        assertEquals(McpcReasonCode.BUSY, McpcMessage.decode(ack.encode())!!.reasonCode)
        assertTrue(runCatching { McpcMessage(McpcType.ACKNOWLEDGE, 5, ackRequired = true, reasonCode = 0).encode() }.isFailure)
        // §8.3.3.9 step 1: an unknown subtype is ignored.
        val unknown = org.openmcx.client.media.RtcpApp(5, 1, "MCPC", listOf(1 to byteArrayOf(0, 1))).encode()
        assertNull(McpcMessage.decode(unknown))
    }

    @Test fun theClientsStateMachine() {
        val m = PreSessionControl(9)
        val connect = connect(McpcSessionType.PREARRANGED)
        assertNull(m.connect(connect).acknowledge) // start-stop: discarded
        m.started()
        val busy = m.connect(connect, McpcReasonCode.BUSY)
        assertEquals(McpcReasonCode.BUSY, busy.acknowledge!!.reasonCode)
        assertFalse(busy.callStarted)
        assertEquals(PreSessionControlState.NOT_IN_USE, m.state)
        assertTrue(m.connect(connect).callStarted)
        assertEquals(PreSessionControlState.IN_USE, m.state)
        assertTrue(m.disconnect(McpcMessage(McpcType.DISCONNECT, 777, true, sessionIdentity = callIdentity)).callEnded)
        assertEquals(PreSessionControlState.NOT_IN_USE, m.state)
    }

    @Test fun theIceAttributesGoInEachMediaSection() {
        val sdp = McpttSdp.offer(LocalMedia("10.0.0.1", 30000, 30002, 5))
        val out = McpttPreSession.withIce(sdp, "uf", "pw0123456789012345678901", "10.0.0.1", 30000, 30001, 30002)
        val audio = out.substringAfter("m=audio").substringBefore("m=application")
        val application = out.substringAfter("m=application")
        assertTrue(audio.contains("30000 typ host") && audio.contains("30001 typ host"))
        assertTrue(application.contains("30002 typ host"))
        assertTrue(out.indexOf("a=ice-ufrag:uf") < out.indexOf("m=audio"))
    }
}
