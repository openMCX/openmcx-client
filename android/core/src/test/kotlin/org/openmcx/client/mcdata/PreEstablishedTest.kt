//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.openmcx.client.client.ClientConfig
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.mcvideo.EmergencyLocation
import org.openmcx.client.msrp.MsrpMessage
import org.openmcx.client.msrp.PassiveEndpoint
import org.openmcx.client.msrp.SocketMsrpConnector
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MCData pre-established session (TS 24.282 §18.3, §9.2.5; TS 24.582 §12) against a fake participating MCData
 * function that behaves as openmcx-server's src/mcdata/pre_established.cpp: it answers "passive" and keeps listening on
 * the session's MSRP URI, the client connecting anew for each communication.
 */
class PreEstablishedTest {
    private val alice = "sip:alice.mcdata@mcx.test"
    private val bob = "sip:bob.mcdata@mcx.test"
    private val group = "sip:operations@mcx.test"
    private val sessionUri = "sip:mcdata-session@mcx.test;mcdata-pre-session=ab12"
    private val focus = "sip:mcdata-session@mcx.test;session=77"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpoint = PassiveEndpoint()

    @After fun close() = endpoint.close()

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcdata": {"user_id": "$alice", "psi": "sip:mcdata@mcx.test", "groups": ["$group"], "max_payload_size_sds_cplane_bytes": 100},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val noHttp = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
    }

    /** What the client answered to one of the function's requests. */
    class Answer(val status: Int, val body: ByteArray, val contentType: String?, val headers: List<Pair<String, String>>) {
        val sdp get() = MsrpMedia.parse(String(body))
    }

    private inner class Tx(override val request: SipRequest) : MediaInvite {
        val answered = CompletableDeferred<Answer>()
        override val localTag = "lt"
        override suspend fun respond(status: Int, body: ByteArray, contentType: String?, extra: List<Pair<String, String>>) {
            answered.complete(Answer(status, body, contentType, extra))
        }
    }

    /** The participating function: the session's INVITE, its REFERs, its re-INVITEs and BYE. */
    private inner class Function : McdataMediaPeer {
        lateinit var media: McdataMediaService
        val invites = CopyOnWriteArrayList<Pair<List<Part>, List<Pair<String, String>>>>()
        val refers = CopyOnWriteArrayList<Triple<String, List<Part>, List<Pair<String, String>>>>()
        val reinvites = CopyOnWriteArrayList<Pair<List<Part>, List<Pair<String, String>>>>()
        val byes = CopyOnWriteArrayList<Dialog>()
        val answers = CopyOnWriteArrayList<Answer>()
        var inviteStatus = 200
        var referStatus = 200
        var reinviteStatus = 200
        var sessionExpires: String? = null
        /** What the function does after a REFER that starts a communication: the re-INVITE state it sends, or none. */
        var outcome: String? = "establish-success"
        var grant = McdataInfo()
        private val cseq = AtomicInteger(10)
        override val userId = alice
        override val clientId = "urn:uuid:1"
        override fun localAddress() = "127.0.0.1"
        override fun contact(feature: String) = "<sip:alice@127.0.0.1;transport=tls>;+g.3gpp.mcdata.$feature"
        override fun resourcePriority(level: String) = "mcdatap.$level"
        override suspend fun invite(parts: List<Part>, headers: List<Pair<String, String>>): Pair<SipResponse, Dialog?> {
            invites += parts to headers
            if (inviteStatus >= 300) return SipResponse(inviteStatus, "Forbidden", Headers().add("Warning", "399 mcdata \"226 function not allowed\"")) to null
            val answer = MsrpMedia("127.0.0.1", endpoint.uri.port, endpoint.uri.text(), "passive", "sendrecv").encode()
            val headersOut = Headers().add("Contact", "<$sessionUri>;+g.3gpp.mcdata.sds").add("Content-Type", "application/sdp")
            sessionExpires?.let { headersOut.add("Session-Expires", it) }
            return SipResponse(200, "OK", headersOut, answer.toByteArray()) to dialog()
        }
        override suspend fun reinvite(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
            reinvites += parts to headers
            val answer = MsrpMedia("127.0.0.1", endpoint.uri.port, endpoint.uri.text(), "passive", "sendrecv").encode()
            return SipResponse(reinviteStatus, "OK", Headers().add("Content-Type", "application/sdp"), answer.toByteArray())
        }
        override suspend fun bye(dialog: Dialog, headers: List<Pair<String, String>>) { byes += dialog }
        override suspend fun info(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>) = SipResponse(200, "OK")
        override suspend fun refer(uri: String, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
            refers += Triple(uri, parts, headers)
            if (referStatus >= 300) return SipResponse(referStatus, "Forbidden", Headers().add("Warning", "399 mcdata \"233 not authorised\""))
            val leave = headers.first { it.first == "Refer-To" }.second.contains("method=BYE")
            val state = if (leave) "terminated" else outcome
            if (state != null) scope.launch { delay(30); send(state, if (state == "establish-success") grant else McdataInfo(), contact = if (state == "establish-success") "<$focus>;isfocus" else "<$sessionUri>") }
            return SipResponse(200, "OK", Headers().add("Refer-Sub", "false"))
        }
        override fun event(text: String) { println(text) }

        /** A request in the session's dialog. */
        fun request(method: String, parts: List<Part>, headers: List<Pair<String, String>> = emptyList()): SipRequest {
            val h = Headers().add("From", "<sip:mcdata@mcx.test>;tag=rt").add("To", "<$alice>;tag=lt").add("Call-ID", "pre@x")
                .add("CSeq", "${cseq.incrementAndGet()} $method").add("P-Asserted-Service", Icsi.MCDATA_SDS)
            headers.forEach { (k, v) -> h.add(k, v) }
            if (parts.isEmpty()) return SipRequest(method, "sip:alice@127.0.0.1", h)
            val (type, body) = Multipart.encode(parts)
            h.add("Content-Type", type)
            return SipRequest(method, "sip:alice@127.0.0.1", h, body)
        }

        fun sessionSdp(direction: String = "sendrecv", keyMgmt: String? = null) =
            Part("application/sdp", MsrpMedia("127.0.0.1", endpoint.uri.port, endpoint.uri.text(), "passive", direction, keyMgmt = keyMgmt).encode().toByteArray())

        /** TS 24.282 §9.2.5.1.2/§9.2.5.1.3: a re-INVITE with <mcdata-communication-state> [state]. */
        suspend fun send(state: String?, info: McdataInfo = McdataInfo(), contact: String = "<$sessionUri>",
                         headers: List<Pair<String, String>> = emptyList(), sdp: Part = sessionSdp()): Answer {
            val tx = Tx(request("INVITE", listOf(sdp, Part(McdataInfo.CONTENT_TYPE, info.copy(communicationState = state).encode().toByteArray())),
                listOf("Contact" to contact) + headers))
            media.handle(tx)
            return withTimeout(5000) { tx.answered.await() }.also { answers += it }
        }

        suspend fun plain(method: String, parts: List<Part> = emptyList(), headers: List<Pair<String, String>> = emptyList()): Answer {
            val tx = Tx(request(method, parts, headers))
            assertTrue(media.handle(tx))
            return withTimeout(5000) { tx.answered.await() }
        }
    }

    private fun dialog() = Dialog("pre@x", "lt", "rt", NameAddr(alice), NameAddr("sip:mcdata@mcx.test"), sessionUri, emptyList(), 1)

    private fun services(function: Function): Pair<McdataService, McdataMediaService> {
        val client = McxClient(scope, config(), noHttp, connector = { _, _ -> throw IllegalStateException("offline") })
        val sds = McdataService(client)
        val media = McdataMediaService(scope, function, sds, SocketMsrpConnector())
        sds.media = media
        function.media = media
        return sds to media
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(20) }
    }

    private fun header(headers: List<Pair<String, String>>, name: String) = headers.firstOrNull { it.first == name }?.second
    private fun info(parts: List<Part>) = McdataInfo.parse(parts.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text)

    /** The REFER's resource list entry: its target and the parts of its hname "body". */
    private fun entry(parts: List<Part>, headers: List<Pair<String, String>>): Pair<String, List<Part>> {
        val cid = header(headers, "Refer-To")!!.removePrefix("<cid:").removeSuffix(">")
        val list = parts.first { p -> p.headers.any { it.first == "Content-ID" && it.second == "<$cid>" } }
        assertEquals(ResourceLists.CONTENT_TYPE, list.mediaType)
        return McdataPreSession.parseEntryUri(ResourceLists.parse(list.text).single())
    }

    private suspend fun established(): Pair<Function, Pair<McdataService, McdataMediaService>> {
        val function = Function()
        val services = services(function)
        services.second.preSession.establish()
        return function to services
    }

    @Test fun theSessionInviteAndItsAnswer() = runBlocking {
        val (function, services) = established()
        val (parts, headers) = function.invites.single()
        // TS 24.282 §18.3.2.1 steps 3-10.
        assertEquals(true, info(parts).preEstablishedSession)
        assertTrue(headers.contains("P-Preferred-Service" to Icsi.MCDATA_SDS))
        assertTrue(headers.contains("Supported" to "timer"))
        assertEquals("${McdataPreSession.SESSION_EXPIRES}", header(headers, "Session-Expires"))
        assertTrue(header(headers, "Contact")!!.contains("+g.3gpp.mcdata.sds"))
        assertEquals(2, headers.count { it.first == "Accept-Contact" })
        val sdp = parts.first { it.mediaType == "application/sdp" }.text
        val offer = MsrpMedia.parse(sdp)
        assertEquals("actpass", offer.setup)
        assertEquals("sendrecv", offer.direction)
        assertEquals(listOf(McdataService.SIGNALLING, McdataService.PAYLOAD), offer.acceptTypes)
        // The one host candidate: TCP active, port 9 (RFC 6544 §4.5): the phone does not listen.
        assertTrue(sdp.contains(Regex("a=candidate:1 1 TCP \\d+ 127.0.0.1 9 typ host tcptype active")))
        assertTrue(sdp.contains("a=ice-ufrag:") && sdp.contains("a=ice-pwd:"))
        val status = services.second.preSession.status.value!!
        assertEquals("established", status.state)
        assertEquals(sessionUri, status.uri)
        assertEquals("passive", status.setup)
        assertTrue(services.second.preSession.usable)
    }

    @Test fun aRefusedSessionIsReported() = runBlocking {
        val function = Function().apply { inviteStatus = 403 }
        val (_, media) = services(function)
        val error = runCatching { media.preSession.establish() }.exceptionOrNull() as SdsError
        assertEquals(403, error.status)
        assertEquals("failed", media.preSession.status.value!!.state)
        assertFalse(media.preSession.usable)
    }

    @Test fun standaloneSdsGoesByReferAndLeavesByReferBye() = runBlocking {
        val (function, services) = established()
        val (sds, media) = services
        val sent = sds.send(bob, "x".repeat(500), group = false)
        // TS 24.282 §9.2.5.2.1.1: no INVITE of its own, a REFER to the session's URI.
        assertEquals(1, function.invites.size)
        val (uri, parts, headers) = function.refers.first()
        assertEquals(sessionUri, uri)
        assertEquals("false", header(headers, "Refer-Sub"))
        assertEquals("norefersub", header(headers, "Supported"))
        assertEquals("multiple-refer", header(headers, "Require"))
        assertEquals("pre@x;local-tag=lt;remote-tag=rt", header(headers, "Target-Dialog"))
        assertEquals(Icsi.MCDATA_SDS, header(headers, "P-Preferred-Service"))
        assertTrue(header(headers, "Contact")!!.contains("+g.3gpp.mcdata.sds"))
        val (target, body) = entry(parts, headers)
        assertEquals(bob, target)
        val offer = MsrpMedia.parse(body.first { it.mediaType == "application/sdp" }.text)
        assertEquals("sendonly", offer.direction)
        assertEquals("actpass", offer.setup)
        assertEquals("one-to-one-sds", info(body).requestType)
        // No MCData-Info of the REFER's own for a normal priority communication.
        assertNull(parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE })
        // The re-INVITE "establish-success" answered "active" with the session's MSRP URI; the client connects.
        val answer = function.answers.first()
        assertEquals(200, answer.status)
        assertEquals("active", answer.sdp.setup)
        assertEquals(offer.path, answer.sdp.path)
        assertEquals(0, endpoint.next().body.size) // the binding SEND (RFC 6135 §4)
        val bodies = endpoint.next().let { Multipart.parse(it.contentType, it.body) }
        assertEquals(sent.messageId, SdsSignalling.decode(bodies[0].content).messageId)
        // TS 24.282 §9.2.5.4.1.1: the REFER "BYE" to the communication's MCData session identity.
        waitFor("REFER BYE") { function.refers.size == 2 }
        val leave = function.refers[1].third
        assertEquals("<$focus;method=BYE>", header(leave, "Refer-To"))
        assertEquals("pre@x;local-tag=lt;remote-tag=rt", header(leave, "Target-Dialog"))
        assertTrue(function.byes.isEmpty())
        waitFor("terminated") { media.preSession.status.value!!.terminated == 1 }
        assertTrue(media.preSession.usable)
        assertEquals("pre-established", sds.outbox.value.single().plane)
        // The next communication connects anew to the same listener (TS 24.582 §12.1).
        sds.send(group, "y".repeat(500), group = true)
        waitFor("second connection") { endpoint.accepted == 2 }
        val (target2, body2) = entry(function.refers[2].second, function.refers[2].third)
        assertEquals(group, target2)
        assertEquals("group-sds", info(body2).requestType)
        assertEquals(group, info(body2).requestUri)
    }

    @Test fun establishFailEndsTheCommunicationNotTheSession() = runBlocking {
        val (function, services) = established()
        function.outcome = "establish-fail"
        val media = services.second
        val error = runCatching { media.openSession(bob, group = false) }.exceptionOrNull()
        assertTrue(error is SdsError)
        assertEquals("failed", media.communications.value.single().state)
        assertTrue(media.preSession.usable)
        assertEquals("establish-fail", media.preSession.status.value!!.lastState)
    }

    @Test fun aRefusedReferIsTheCommunicationsRefusal() = runBlocking {
        val (function, services) = established()
        function.referStatus = 403
        val media = services.second
        val error = runCatching { media.openSession(bob, group = false, priority = "emergency") }.exceptionOrNull() as SdsError
        assertEquals(403, error.status)
        assertEquals("refused", media.communications.value.single().state)
        assertTrue(media.preSession.usable)
    }

    @Test fun groupEmergencySessionWithAnAlertOverTheSession() = runBlocking {
        val (function, services) = established()
        val media = services.second
        media.alertLocation = { EmergencyLocation(48.85, 2.35) }
        function.grant = McdataInfo(emergency = true)
        val c = media.openSession(group, group = true, priority = "emergency", alert = true)
        val (_, parts, headers) = function.refers.single()
        // TS 24.282 §9.2.5.3.1.1 3A)-3D): the indications in the entry's MCData-Info and in the REFER's own; the
        // Resource-Priority; the alert's location in the REFER's body (§6.2.8.1.1 3)b)).
        val own = info(parts)
        assertEquals(true, own.emergency)
        assertEquals(true, own.alert)
        assertEquals(true, info(entry(parts, headers).second).emergency)
        assertEquals("mcdatap.emergency", header(headers, "Resource-Priority"))
        assertNotNull(parts.firstOrNull { it.mediaType == "application/vnd.3gpp.mcdata-location-info+xml" })
        assertTrue(c.open && c.emergency && c.preEstablished)
        // §6.2.8.1.3 within the session (§9.2.5.2.1.3): the cancel by re-INVITE in the session's dialog, its media as negotiated.
        endpoint.next()
        media.setPriority(emergency = true, enable = false, c)
        val (reParts, _) = function.reinvites.single()
        assertEquals("actpass", MsrpMedia.parse(reParts.first { it.mediaType == "application/sdp" }.text).setup)
        assertEquals(false, info(reParts).emergency)
        assertFalse(c.emergency)
        media.close(c)
        waitFor("terminated") { media.preSession.usable }
    }

    @Test fun anImminentPerilRequestRaisedToAnEmergencyByTheGrant() = runBlocking {
        val (function, services) = established()
        function.grant = McdataInfo(emergency = true, imminentPeril = false)
        val c = services.second.openSession(group, group = true, priority = "imminent")
        // TS 24.282 §6.2.8.1.16 2)b).
        assertTrue(c.emergency)
        assertFalse(c.imminentPeril)
        val (_, parts, headers) = function.refers.single()
        assertEquals(true, info(parts).imminentPeril)
        assertEquals("mcdatap.imminent_peril", header(headers, "Resource-Priority"))
    }

    @Test fun aOneToOneEmergencyCarriesNoResourcePriority() = runBlocking {
        val (function, services) = established()
        val c = services.second.openSession(bob, group = false, priority = "emergency")
        val (_, parts, headers) = function.refers.single()
        assertEquals(true, info(parts).emergency) // §9.2.5.2.1.1 1a)a)
        assertNull(header(headers, "Resource-Priority"))
        assertTrue(c.emergency)
    }

    @Test fun aCommunicationOfferedOverTheSessionAndReleasedByTheServer() = runBlocking {
        val (function, services) = established()
        val (sds, media) = services
        // TS 24.282 §9.2.5.2.1.2: the re-INVITE "establish-request" of an SDS session from bob.
        val answer = function.send("establish-request", McdataInfo(requestType = "one-to-one-sds-session", callingUserId = bob),
            contact = "<$focus>;isfocus")
        assertEquals(200, answer.status)
        assertEquals("active", answer.sdp.setup)
        assertEquals("sendrecv", answer.sdp.direction)
        assertEquals(media.preSession.local!!.path, answer.sdp.path)
        val c = media.communications.value.single()
        assertTrue(c.preEstablished)
        waitFor("open") { c.open }
        assertEquals(0, endpoint.next().body.size)
        assertEquals(focus, media.preSession.status.value!!.communicationUri)
        // A second offer while the session is in use: 486 (§9.2.5.2.1.2 1)).
        assertEquals(486, function.send("establish-request", McdataInfo(requestType = "one-to-one-sds", callingUserId = bob)).status)
        // A message in the session.
        val signalling = SdsSignalling(1, c.conversationId, UUID.randomUUID(), sender = bob)
        val (type, body) = Multipart.encode(listOf(Part(McdataService.SIGNALLING, signalling.encode()),
            Part(McdataService.PAYLOAD, DataPayload(listOf(Payload.text("hello"))).encode())))
        endpoint.send(MsrpMessage("t1", method = "SEND", toPath = listOf(endpoint.clientUri!!), fromPath = listOf(endpoint.uri),
            messageId = "m1", byteRange = org.openmcx.client.msrp.ByteRange(1, body.size.toLong(), body.size.toLong()), contentType = type, body = body))
        waitFor("received") { sds.inbox.value.isNotEmpty() }
        assertEquals("hello", sds.inbox.value.single().text)
        // TS 24.282 §9.2.5.4.1.2: "terminate-request": the 200 (OK), the communication released, the session kept.
        assertEquals(200, function.send("terminate-request").status)
        assertEquals("released", c.state)
        assertTrue(media.preSession.usable)
        // A modification (§18.3.4.1.2): 200 with the session's media.
        assertEquals("active", function.send(null).sdp.setup)
        assertEquals(200, function.plain("UPDATE").status)
    }

    @Test fun theServersByeEndsTheSessionAndCommunicationsGoOnDemand() = runBlocking {
        val (function, services) = established()
        val (sds, media) = services
        function.outcome = null // the REFER accepted, no outcome yet
        scope.launch { runCatching { media.openSession(group, group = true) } }
        waitFor("REFER") { function.refers.isNotEmpty() }
        assertEquals(200, function.plain("BYE", headers = listOf("Reason" to "SIP;cause=200")).status)
        val status = media.preSession.status.value!!
        assertEquals("released", status.state)
        assertFalse(media.preSession.wanted)
        waitFor("communication ended") { media.communications.value.single().ended }
        // The next SDS: on demand, its own INVITE.
        runCatching { sds.send(bob, "z".repeat(500), group = false) }
        assertEquals(2, function.invites.size)
        assertEquals("one-to-one-sds", info(function.invites[1].first).requestType)
    }

    @Test fun theUsersReleaseIsABye() = runBlocking {
        val (function, services) = established()
        services.second.preSession.release()
        assertEquals(1, function.byes.size)
        assertEquals("released", services.second.preSession.status.value!!.state)
        assertTrue(runCatching { services.second.preSession.release() }.isFailure)
    }

    @Test fun afterAFlowLossTheSessionIsRefreshedOrMadeAnew() = runBlocking {
        val (function, services) = established()
        val pre = services.second.preSession
        services.second.flowLost()
        assertEquals("suspended", pre.status.value!!.state)
        assertFalse(pre.usable)
        // The new registration: a target refresh in the dialog (re-INVITE with the new Contact).
        pre.resume()
        assertEquals("established", pre.status.value!!.state)
        assertEquals(1, pre.status.value!!.resumed)
        assertTrue(header(function.reinvites.single().second, "Contact")!!.contains("+g.3gpp.mcdata.sds"))
        // The function lost the dialog meanwhile: 481, then a BYE and a new session.
        services.second.flowLost()
        function.reinviteStatus = 481
        pre.resume()
        assertEquals("established", pre.status.value!!.state)
        assertEquals(1, pre.status.value!!.reestablished)
        assertEquals(2, function.invites.size)
    }

    @Test fun theSessionIsRefreshedWhenThisClientIsTheRefresher() = runBlocking {
        val function = Function().apply { sessionExpires = "2;refresher=uac" }
        val (_, media) = services(function)
        media.preSession.establish()
        waitFor("refresh") { function.reinvites.isNotEmpty() }
        assertTrue(header(function.reinvites.first().second, "Session-Expires")!!.startsWith("2"))
        waitFor("refreshes") { media.preSession.status.value!!.refreshes >= 1 }
    }

    @Test fun theEntryUriRoundTrips() {
        val parts = listOf(Part("application/sdp", "v=0\r\nm=message 9 TCP/MSRP *\r\na=path:msrp://1.2.3.4:9/x;tcp\r\n".toByteArray()),
            Part(McdataInfo.CONTENT_TYPE, McdataInfo(requestType = "group-sds", requestUri = group).encode().toByteArray()))
        val uri = McdataPreSession.entryUri(group, parts)
        assertTrue(uri.startsWith("$group?Content-Type=multipart/mixed%3Bboundary%3Dmcdata-entry&body="))
        assertFalse(uri.substringAfter('?').contains(Regex("[ <>\"\r\n]")))
        val (target, back) = McdataPreSession.parseEntryUri(uri)
        assertEquals(group, target)
        assertEquals("group-sds", info(back).requestType)
        assertTrue(back.first().text.contains("a=path:msrp://1.2.3.4:9/x;tcp"))
    }
}
