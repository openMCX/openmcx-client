//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.AudioSink
import org.openmcx.client.media.AudioSource
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.SpeechDecoder
import org.openmcx.client.media.SpeechEncoder
import org.openmcx.client.mcptt.CallError
import org.openmcx.client.mcptt.CallType
import org.openmcx.client.mcptt.LocalMedia
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcptt.McpttSdp
import org.openmcx.client.mcptt.McpttService
import org.openmcx.client.mcptt.Commencement
import org.openmcx.client.mcptt.PrivateServiceRules
import org.openmcx.client.alert.EmergencyAlerts
import org.openmcx.client.location.LocationService
import java.time.Instant
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCPTT call services of phase 3 on the wire of a fake SIP core (TS 24.379 V19.7.0): ambient listening (§11.1.6),
 * call-back (§11.1.5), remotely initiated private and group calls (§11.1.7, §10.1.5), transfer (§11.1.8), forwarding
 * (§11.1.9), and the emergency and imminent peril notification without an alert (§12.1.1.3).
 */
class PrivateServicesTest {
    private val alice = "sip:alice.mcptt@mcx.test"
    private val bob = "sip:bob.mcptt@mcx.test"
    private val carol = "sip:client-c.mcptt@mcx.test"
    private val group = "sip:operations@mcx.test"
    private val psi = "sip:mcptt@mcx.test"

    private fun config(floorPriority: String = "") = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "$alice", "psi": "$psi", "groups": [], "answer_mode": "automatic",
                  "session_expires": 1800, "manual_answer_timeout": 20, "subscribe_configuration": false,
                  "subscribe_group_documents": false $floorPriority},
        "media": {"port_range": [21000, 21400], "amr_wb_mode": 2}}""")

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
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    /** The server's SDP: an answer to the client's offer in [request], or an offer ([floor] false: no floor control). */
    private fun serverSdp(request: SipRequest? = null, floor: Boolean = true, priority: Int? = null): String {
        val server = LocalMedia("127.0.0.1", 4002, 5002, 777, floorControl = floor)
        if (request == null) return McpttSdp.offer(server)
        val offer = McpttSdp.remote(part(request, "application/sdp")!!)
        return McpttSdp.answer(if (priority != null) offer.copy(floorPriority = priority) else offer, server)
    }

    private class Lab(val client: McxClient, val service: McpttService, val core: FakeSipCore)

    private fun start(floorPriority: String = "", status: (SipRequest) -> Int? = { null },
                      headers: (SipRequest) -> List<Pair<String, String>> = { emptyList() },
                      answer: (SipRequest) -> Pair<String, ByteArray>? = { null }, hold: (SipRequest) -> Boolean = { false },
                      http: HttpClient = noHttp): Lab {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(floorPriority), http,
            connector = { _, _ -> FakeSipCore(statusFor = status, holdFor = hold, bodyFor = answer, headersFor = { r -> headers(r) +
                // The MCPTT session identity in the 200 (OK)'s Contact (§17.2.5.1.1).
                (if (r.method == "INVITE" && r.to.tag == null) listOf("Contact" to "<sip:mcptt@127.0.0.1:5061;transport=tls;isfocus>") else emptyList()) }).also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false))
        val service = McpttService(client, codecs, silence)
        client.handlers += service
        client.start()
        waitFor("ready") { client.status.value.phase == "ready" }
        return Lab(client, service, cores.last())
    }

    /** A 200 (OK) to an INVITE: the SDP answer and the mcptt-info [info]. */
    private fun ok(request: SipRequest, info: McpttInfo, priority: Int? = null): Pair<String, ByteArray>? {
        if (request.method != "INVITE") return null
        return Multipart.encode(listOf(Part("application/sdp", serverSdp(request, priority = priority).toByteArray()),
            Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray())))
    }

    private fun parts(request: SipRequest) = Multipart.parse(request.contentType, request.body)
    private fun info(request: SipRequest) = McpttInfo.parse(parts(request).first { it.mediaType == McpttInfo.CONTENT_TYPE }.text)
    private fun part(request: SipRequest, type: String) = parts(request).firstOrNull { it.mediaType == type }?.text

    private var cseq = 1

    /** A request from the core to the client: an initial INVITE (no [toTag]) or one in the dialog of [callId]. */
    private fun fromCore(core: FakeSipCore, method: String, callId: String, body: Pair<String, ByteArray>?,
                         extra: List<Pair<String, String>> = emptyList(), toTag: String? = null, branch: String = "z9hG4bKc${cseq}") {
        val headers = Headers().add("Via", "SIP/2.0/TLS 127.0.0.1:5061;branch=$branch").add("Max-Forwards", "70")
            .add("From", "<$psi>;tag=srv").add("To", "<sip:alice@mcx.test>" + (toTag?.let { ";tag=$it" } ?: ""))
            .add("Call-ID", callId).add("CSeq", "${cseq++} $method").add("Contact", "<sip:mcptt@127.0.0.1:5061;transport=tls;isfocus>")
            .add("P-Asserted-Identity", "<$psi>")
        extra.forEach { (n, v) -> headers.add(n, v) }
        if (body != null) headers.add("Content-Type", body.first)
        core.send(SipRequest(method, core.contact(), headers, body?.second ?: ByteArray(0)))
    }


    private fun messages(lab: Lab) = lab.core.requests.filter { it.method == "MESSAGE" }

    /** A service MESSAGE from the server (the PSI), as it passes requests and responses on: the info only. */
    private fun fromServer(lab: Lab, info: McpttInfo) {
        lab.core.message(McpttInfo.CONTENT_TYPE, info.copy(requestUri = alice).encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
    }

    /** An incoming private (or ambient listening) INVITE from the server, with the server's offer. */
    private fun invite(lab: Lab, callId: String, info: McpttInfo, headers: List<Pair<String, String>>, direction: String = "sendrecv") {
        val offer = McpttSdp.offer(LocalMedia("127.0.0.1", 4002, 5002, 777, direction = direction))
        val body = Multipart.encode(listOf(Part("application/sdp", offer.toByteArray()), Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray())))
        fromCore(lab.core, "INVITE", callId, body, headers + listOf("Session-Expires" to "90", "Supported" to "timer"))
    }

    /**
     * §11.1.6.2.1.1: a remotely initiated ambient listening call: session type "ambient-listening" (step 7),
     * <ambient-listening-type> remote-init (step 8), the one user listed (step 9), Priv-Answer-Mode Auto (step 14), the
     * speech recvonly (§6.2.1 item 2c) and the implicit request to grant the floor to the invited user (§6.4); the
     * 200 (OK)'s Feature-Caps gives the release capability (annex D.3), and the call is released by BYE.
     */
    @Test
    fun ambientListeningRemoteInit() = runBlocking {
        val lab = start(headers = { r -> if (r.method == "INVITE") listOf("Feature-Caps" to "*;+${PrivateServiceRules.AMBIENT_RELEASE}") else emptyList() },
            answer = { r -> ok(r, McpttInfo(sessionType = "ambient-listening", requestUri = bob, ambientListeningType = "remote-init")) })
        val call = lab.service.ambientCall(bob)
        val invite = lab.core.requests.last { it.method == "INVITE" }
        val sent = info(invite)
        assertEquals("ambient-listening", sent.sessionType)
        assertEquals("remote-init", sent.ambientListeningType)
        assertTrue(String(invite.body), "<ambient-listening-type>remote-init</ambient-listening-type>" in String(invite.body))
        assertNull("no forwarding sequence number", sent.forwardingSequenceNumber)
        assertEquals("Auto", invite.headers["Priv-Answer-Mode"])
        assertNull(invite.headers["Answer-Mode"])
        assertEquals(listOf(bob), ResourceLists.parse(part(invite, ResourceLists.CONTENT_TYPE)!!))
        val offer = McpttSdp.remote(part(invite, "application/sdp")!!)
        assertEquals("recvonly", offer.direction)
        assertTrue("an implicit request to grant the floor to bob", offer.implicitRequest)
        assertEquals("listening", call.ambientRole)
        assertFalse(call.hidden)
        assertTrue(call.mayRelease)
        assertEquals(call, lab.service.active)
        lab.service.hangup(call)
        assertTrue(lab.core.requests.any { it.method == "BYE" })
        lab.client.stop()
    }

    /** §11.1.6.2.1.1 with local-init: the listened-to user's offer is sendonly and its own implicit floor request. */
    @Test
    fun ambientListeningLocalInitWithoutReleaseCapability() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "ambient-listening", requestUri = bob, ambientListeningType = "local-init")) })
        val call = lab.service.ambientCall(bob, local = true)
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertEquals("local-init", info(invite).ambientListeningType)
        val offer = McpttSdp.remote(part(invite, "application/sdp")!!)
        assertEquals("sendonly", offer.direction)
        assertTrue(offer.implicitRequest)
        assertEquals("listened-to", call.ambientRole)
        // §11.1.6.2.1.1 (200 (OK)) 3): no indication of the establishment, even of a call the user asked for; it stays
        // the user's call (§11.1.6.2.1.3).
        assertTrue("not shown", call.hidden)
        assertFalse(call.concealed)
        assertEquals(call, lab.service.active)
        // §11.1.6.2.1.3 step 1: no Feature-Caps in the 200 (OK): this client may not release the call.
        assertFalse(call.mayRelease)
        assertEquals("not released", true, runCatching { lab.service.hangup(call) }.isFailure)
        lab.client.stop()
    }

    /**
     * §11.1.6.2.1.1 with local-init and the release capability (annex D.3): the call is hidden from the user that
     * initiated it (200 (OK) step 3)) and from the listeners of the app (no ringing, no incoming call); the user may
     * still release it (§11.1.6.2.1.3 step 1), and the release is not shown either (step 2)).
     */
    @Test
    fun ambientListeningLocalInitHiddenAndReleasable() = runBlocking {
        val lab = start(headers = { r -> if (r.method == "INVITE") listOf("Feature-Caps" to "*;+${PrivateServiceRules.AMBIENT_RELEASE}") else emptyList() },
            answer = { r -> ok(r, McpttInfo(sessionType = "ambient-listening", requestUri = bob, ambientListeningType = "local-init")) })
        val call = lab.service.ambientCall(bob, local = true)
        assertEquals("established", call.state)
        assertTrue(call.hidden)
        assertFalse(call.concealed)
        assertTrue(call.mayRelease)
        assertEquals("the user's call", call, lab.service.active)
        lab.service.hangup()
        assertTrue(lab.core.requests.any { it.method == "BYE" })
        assertEquals("released", call.state)
        assertTrue("still hidden once released", call.hidden)
        lab.client.stop()
    }

    /**
     * §11.1.6.2.1.2: the listened-to user of a remotely initiated ambient listening call: answered at once with 200, the
     * speech sendonly; the user is not told (no incoming call, no active call), and the floor granted without a request
     * is taken (§6.4). A call of the user's own ends the hidden call (TS 23.379 §10.14.3.3).
     */
    @Test
    fun ambientListenedToIsHidden() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = carol)) })
        val told = CopyOnWriteArrayList<String>()
        lab.service.listener = object : org.openmcx.client.mcptt.CallListener { override fun incoming(call: org.openmcx.client.mcptt.Call) { told += "incoming" } }
        invite(lab, "al1", McpttInfo(sessionType = "ambient-listening", requestUri = alice, callingUserId = bob, ambientListeningType = "remote-init"),
            listOf("Priv-Answer-Mode" to "Auto", "Alert-Info" to "<file:///dev/null>"), direction = "recvonly")
        val ok = lab.core.answer()
        assertEquals(200, ok.status)
        assertEquals("sendonly", McpttSdp.remote(String(ok.body)).direction)
        waitFor("established") { lab.service.calls.value.firstOrNull()?.state == "established" }
        val call = lab.service.calls.value.single()
        assertTrue(call.hidden)
        assertTrue(call.concealed)
        assertNull("not the user's call", lab.service.active)
        assertTrue("no indication", told.isEmpty())
        assertTrue(call.media!!.floor.grantExpected)
        // The user calls client-c: the hidden call goes.
        val own = lab.service.privateCall(carol)
        assertEquals("released", call.state)
        assertEquals("call-request-initiated-by-listened-to-client", call.releaseReason)
        lab.service.hangup(own)
        lab.client.stop()
    }

    /** §11.1.6.2.1.2: an ambient listening INVITE without Priv-Answer-Mode Auto is refused with 400, one with a group with 488. */
    @Test
    fun ambientListeningRefusals() {
        assertEquals(400, PrivateServiceRules.ambientRefusal(McpttInfo(callingUserId = bob, ambientListeningType = "remote-init"), alice, "Manual"))
        assertEquals(488, PrivateServiceRules.ambientRefusal(McpttInfo(callingUserId = bob, callingGroupId = group, ambientListeningType = "remote-init"), alice, "Auto"))
        assertEquals(488, PrivateServiceRules.ambientRefusal(McpttInfo(callingUserId = bob, ambientListeningType = "other"), alice, "Auto"))
        assertEquals(488, PrivateServiceRules.ambientRefusal(McpttInfo(callingUserId = bob, ambientListeningType = "local-init", emergency = true), alice, "Auto"))
        assertEquals(488, PrivateServiceRules.ambientRefusal(McpttInfo(ambientListeningType = "local-init"), alice, "Auto"))
        assertNull(PrivateServiceRules.ambientRefusal(McpttInfo(callingUserId = bob, requestUri = alice, ambientListeningType = "local-init"), alice, "Auto;require"))
        val lab = start()
        invite(lab, "al2", McpttInfo(sessionType = "ambient-listening", requestUri = alice, callingUserId = bob, ambientListeningType = "remote-init"),
            listOf("Answer-Mode" to "Auto"))
        assertEquals(400, lab.core.answer().status)
        lab.client.stop()
    }

    /**
     * §11.1.5.2.1: the call-back request: <request-type>, <urgency-ind>, <time-of-request> (annex F.1.3, no zone), the
     * client ID, the target in a resource list, to the PSI; the target's response confirms it ("PCCB-I3"); its call
     * fulfils it (§11.1.5.2.3). The cancel request, and its response.
     */
    @Test
    fun callBackRequested() = runBlocking {
        val lab = start()
        lab.service.services.requestCallBack(bob, "high")
        val message = messages(lab).last()
        assertEquals(psi, message.uri)
        val sent = info(message)
        assertEquals(PrivateServiceRules.CALL_BACK, sent.requestType)
        assertEquals("high", sent.service["urgency-ind"])
        assertTrue(sent.service["time-of-request"]!!, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d""").matches(sent.service["time-of-request"]!!))
        assertEquals("urn:uuid:1", sent.clientId)
        assertEquals(listOf(bob), ResourceLists.parse(part(message, ResourceLists.CONTENT_TYPE)!!))
        assertEquals("PCCB-I2: confirm-pending", lab.service.services.state.value.callBacksSent[bob])
        fromServer(lab, McpttInfo(callingUserId = bob, service = mapOf("response-type" to "private-call-call-back-response")))
        waitFor("confirmed") { lab.service.services.state.value.callBacksSent[bob] == "PCCB-I3: confirmed" }
        // bob calls back.
        invite(lab, "cb1", McpttInfo(sessionType = "private", requestUri = alice, callingUserId = bob), listOf("Answer-Mode" to "Auto"))
        assertEquals(200, lab.core.answer().status)
        waitFor("fulfilled") { bob !in lab.service.services.state.value.callBacksSent }
        lab.service.hangup()
        // A second request, cancelled.
        lab.service.services.requestCallBack(carol)
        lab.service.services.cancelCallBack(carol)
        assertEquals(PrivateServiceRules.CALL_BACK_CANCEL, info(messages(lab).last()).requestType)
        fromServer(lab, McpttInfo(callingUserId = carol, service = mapOf("response-type" to "private-call-call-back-cancel-response")))
        waitFor("cancelled") { carol !in lab.service.services.state.value.callBacksSent }
        lab.client.stop()
    }

    /**
     * §11.1.5.2.2: a call-back request received is stored and confirmed by a response to the requester; its fulfilment
     * is a private call with manual commencement (§11.1.5.2.3), after which it is gone; a cancel removes a request.
     */
    @Test
    fun callBackReceivedAndFulfilled() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = bob)) })
        fromServer(lab, McpttInfo(callingUserId = bob, requestType = PrivateServiceRules.CALL_BACK,
            service = mapOf("urgency-ind" to "low", "time-of-request" to "2026-11-25T10:00:00")))
        waitFor("stored") { bob in lab.service.services.state.value.callBacksReceived }
        assertEquals("low", lab.service.services.state.value.callBacksReceived[bob]!!.urgency)
        waitFor("confirmed") { messages(lab).any { info(it).service["response-type"] == "private-call-call-back-response" } }
        val response = messages(lab).last()
        assertEquals(listOf(bob), ResourceLists.parse(part(response, ResourceLists.CONTENT_TYPE)!!))
        val call = lab.service.services.fulfilCallBack(bob)
        assertEquals("Manual", lab.core.requests.last { it.method == "INVITE" }.headers["Answer-Mode"])
        assertTrue(lab.service.services.state.value.callBacksReceived.isEmpty())
        lab.service.hangup(call)
        fromServer(lab, McpttInfo(callingUserId = carol, requestType = PrivateServiceRules.CALL_BACK))
        waitFor("stored") { carol in lab.service.services.state.value.callBacksReceived }
        fromServer(lab, McpttInfo(callingUserId = carol, requestType = PrivateServiceRules.CALL_BACK_CANCEL))
        waitFor("removed") { carol !in lab.service.services.state.value.callBacksReceived }
        waitFor("cancel confirmed") { messages(lab).any { info(it).service["response-type"] == "private-call-call-back-cancel-response" } }
        lab.client.stop()
    }

    /** §11.1.7.2.1: the remotely initiated private call request names the target, the mode, the notification and the priority. */
    @Test
    fun remoteCallRequested() = runBlocking {
        val lab = start()
        lab.service.services.remotePrivateCall(bob, carol, Commencement.FORCED, notify = false, priority = "mcpttp.5")
        val sent = info(messages(lab).last())
        assertEquals(PrivateServiceRules.REMOTE_PRIVATE, sent.requestType)
        assertEquals(carol, sent.calledPartyId)
        assertEquals("force-auto-mode", sent.service["ric-commencement-mode"])
        assertEquals("false", sent.service["notify-remote-user"])
        assertEquals("mcpttp.5", sent.service["ric-app-level-priority"])
        lab.service.services.remoteGroupCall(bob, group)
        val groupRequest = info(messages(lab).last())
        assertEquals(PrivateServiceRules.REMOTE_GROUP, groupRequest.requestType)
        assertEquals(group, groupRequest.requestUri)
        assertEquals(listOf(bob), ResourceLists.parse(part(messages(lab).last(), ResourceLists.CONTENT_TYPE)!!))
        // The outcome the remote client reports.
        fromServer(lab, McpttInfo(callingUserId = bob, calledPartyId = carol,
            service = mapOf("response-type" to "remotely-initiated-private-call-response", "remotely-initiated-call-outcome" to "success")))
        waitFor("outcome") { lab.service.services.state.value.outcomes.any { it.outcome == "success" && it.target == carol } }
        lab.client.stop()
    }

    /**
     * §11.1.7.2.2: the remote client places the call: Answer-Mode by <ric-commencement-mode> (3) c)), Resource-Priority
     * from <ric-app-level-priority> (3) b)), <remotely-initiated-call-request-ind> (§11.1.1.2.1.1 14A) D)); then reports
     * the outcome to the requester (step 2), <mcptt-called-party-id> the target.
     */
    @Test
    fun remoteCallPlaced() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = carol)) })
        fromServer(lab, McpttInfo(callingUserId = bob, calledPartyId = carol, requestType = PrivateServiceRules.REMOTE_PRIVATE,
            service = mapOf("notify-remote-user" to "false", "ric-commencement-mode" to "manual-mode", "ric-app-level-priority" to "mcpttp.5")))
        waitFor("reported") { messages(lab).any { info(it).service["response-type"] == "remotely-initiated-private-call-response" } }
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertEquals("Manual", invite.headers["Answer-Mode"])
        assertEquals("mcpttp.5", invite.headers["Resource-Priority"])
        assertEquals(true, info(invite).remotelyInitiatedCallRequest)
        assertEquals(listOf(carol), ResourceLists.parse(part(invite, ResourceLists.CONTENT_TYPE)!!))
        val report = messages(lab).last()
        assertEquals("success", info(report).service["remotely-initiated-call-outcome"])
        assertEquals(carol, info(report).calledPartyId)
        assertEquals(listOf(bob), ResourceLists.parse(part(report, ResourceLists.CONTENT_TYPE)!!))
        assertEquals(false, lab.service.services.state.value.handled.last().notify)
        lab.service.hangup()
        lab.client.stop()
    }

    /** §10.1.5.2.2: a group call request for a group this client is not affiliated to fails, and the report names the group. */
    @Test
    fun remoteGroupCallNotAffiliated() = runBlocking {
        val lab = start()
        fromServer(lab, McpttInfo(callingUserId = bob, callingGroupId = group, requestType = PrivateServiceRules.REMOTE_GROUP))
        waitFor("reported") { messages(lab).any { info(it).service["response-type"] == "remotely-initiated-group-call-response" } }
        val report = info(messages(lab).last())
        assertEquals("fail", report.service["remotely-initiated-call-outcome"])
        assertEquals(group, report.requestUri)
        assertTrue(lab.core.requests.none { it.method == "INVITE" })
        lab.client.stop()
    }

    /**
     * §11.1.8.2.1: the transfer request goes to the other party of the established private call, the target in
     * <mcptt-called-party-id>; a success report ends the call with the transferred user.
     */
    @Test
    fun transferRequested() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = bob)) })
        val call = lab.service.privateCall(bob)
        lab.service.services.transfer(carol)
        val message = messages(lab).last()
        assertEquals(PrivateServiceRules.TRANSFER, info(message).requestType)
        assertEquals(carol, info(message).calledPartyId)
        assertEquals(listOf(bob), ResourceLists.parse(part(message, ResourceLists.CONTENT_TYPE)!!))
        fromServer(lab, McpttInfo(callingUserId = bob, calledPartyId = carol,
            service = mapOf("response-type" to "transfer-private-call-response", "transfer-call-outcome" to "success")))
        waitFor("call ended") { call.state == "released" }
        assertTrue(lab.core.requests.any { it.method == "BYE" })
        lab.client.stop()
    }

    /**
     * §11.1.8.2.2: the transferred user keeps its call with the requester and calls the target with
     * <call-transfer-ind> "true" and the Priv-Answer-Mode of the call transferred (§11.1.1.2.1.1 14A) a)), then reports.
     */
    @Test
    fun transferPlaced() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = carol)) })
        invite(lab, "tr1", McpttInfo(sessionType = "private", requestUri = alice, callingUserId = bob), listOf("Answer-Mode" to "Auto"))
        assertEquals(200, lab.core.answer().status)
        waitFor("established") { lab.service.active?.state == "established" }
        val held = lab.service.active!!
        fromServer(lab, McpttInfo(callingUserId = bob, calledPartyId = carol, requestType = PrivateServiceRules.TRANSFER))
        waitFor("reported") { messages(lab).any { info(it).service["response-type"] == "transfer-private-call-response" } }
        val invite = lab.core.requests.last { it.method == "INVITE" && it.to.tag == null }
        assertEquals("true", info(invite).service["call-transfer-ind"])
        assertEquals("Auto", invite.headers["Priv-Answer-Mode"])
        assertNull(info(invite).forwardingSequenceNumber)
        assertEquals("success", info(messages(lab).last()).service["transfer-call-outcome"])
        assertEquals("the call with bob goes on", "established", held.state)
        assertEquals(carol, lab.service.active!!.peer)
        assertEquals("transfer", lab.service.active!!.service)
        lab.client.stop()
    }

    /**
     * §11.1.9.2.1: forwarding a ringing private call: the request to the caller with "manual-input", the MCPTT ID the
     * call was addressed to and the target (2) d) i)); the call is then declined with 480 and warning 175 (NOTE 1).
     */
    @Test
    fun forwardRinging() = runBlocking {
        val lab = start()
        invite(lab, "fw1", McpttInfo(sessionType = "private", requestUri = alice, callingUserId = bob), listOf("Answer-Mode" to "Manual"))
        assertEquals(180, lab.core.answer().status)
        waitFor("ringing") { lab.service.calls.value.firstOrNull()?.state == "ringing" }
        lab.service.services.forward(carol)
        val sent = info(messages(lab).last())
        assertEquals(PrivateServiceRules.FORWARD, sent.requestType)
        assertEquals("manual-input", sent.service["forwarding-reason"])
        assertEquals(alice, sent.service["forwarded-by-mcptt-id"])
        assertEquals(carol, sent.service["forwarding-target-orig-id"])
        // 2) d) i) B): a target that is an MCPTT ID: <call-to-functional-alias-ind> "false", not left out.
        assertEquals(false, sent.callToFunctionalAlias)
        assertTrue(String(messages(lab).last().body), "<call-to-functional-alias-ind>false</call-to-functional-alias-ind>" in String(messages(lab).last().body))
        assertEquals(listOf(bob), ResourceLists.parse(part(messages(lab).last(), ResourceLists.CONTENT_TYPE)!!))
        val declined = lab.core.answer()
        assertEquals(480, declined.status)
        assertTrue(declined.headers["Warning"]!!, "175" in declined.headers["Warning"]!!)
        lab.client.stop()
    }

    /**
     * §11.1.9.2.2 with §6.3.2.6: the server forwards this user's call to client-c (480, warning 175) and asks for a call to
     * bob: <call-forwarding-ind> "true", the forwarded call's <forwarding-sequence-number> plus one (14C) a1)) and its
     * answer mode in Priv-Answer-Mode (14C) a)); the outcome goes to the forwarding user.
     */
    @Test
    fun forwardedByTheServer() = runBlocking {
        val lab = start(status = { r -> if (r.method == "INVITE" && carol in String(r.body)) 480 else null },
            headers = { r -> if (r.method == "INVITE" && carol in String(r.body)) listOf("Warning" to "399 mcx \"175 call is forwarded\"") else emptyList() },
            answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = bob)) })
        assertTrue(runCatching { lab.service.privateCall(carol, Commencement.MANUAL) }.isFailure)
        fromServer(lab, McpttInfo(callingUserId = carol, requestType = PrivateServiceRules.FORWARD, service = mapOf(
            "forwarding-reason" to "immediate", "forwarded-by-mcptt-id" to carol, "forwarding-target-id" to bob, "forwarding-target-orig-id" to bob)))
        waitFor("reported") { messages(lab).any { info(it).service["response-type"] == "forward-private-call-response" } }
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertEquals("true", info(invite).service["call-forwarding-ind"])
        assertEquals(1, info(invite).forwardingSequenceNumber)
        assertEquals("Manual", invite.headers["Priv-Answer-Mode"])
        val report = messages(lab).last()
        assertEquals("success", info(report).service["forwarding-call-outcome"])
        assertEquals(listOf(carol), ResourceLists.parse(part(report, ResourceLists.CONTENT_TYPE)!!))
        lab.service.hangup()
        lab.client.stop()
    }

    /**
     * §11.1.9.2.2: the server's forwarding request may arrive before the 480 of the call it forwards (ms apart). The
     * call to bob still takes the forwarded call's answer mode (Manual) and sequence number: the forwarded call is
     * looked up after it has ended, not while it is still current.
     */
    @Test
    fun forwardingRequestBeforeThe480() = runBlocking {
        val toCarol = { r: SipRequest -> r.method == "INVITE" && carol in String(r.body) }
        val lab = start(status = { r -> if (toCarol(r)) 480 else null }, hold = toCarol,
            headers = { r -> if (toCarol(r)) listOf("Warning" to "399 mcx \"175 call is forwarded\"") else emptyList() },
            answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = bob)) })
        val first = async(Dispatchers.Default) { runCatching { lab.service.privateCall(carol, Commencement.MANUAL) } }
        waitFor("the INVITE to client-c") { lab.core.requests.any(toCarol) }
        fromServer(lab, McpttInfo(callingUserId = carol, requestType = PrivateServiceRules.FORWARD, service = mapOf(
            "forwarding-reason" to "no-answer", "forwarded-by-mcptt-id" to carol, "forwarding-target-id" to bob, "forwarding-target-orig-id" to bob)))
        Thread.sleep(300)
        assertFalse("no call to bob before the 480", lab.core.requests.any { it.method == "INVITE" && bob in String(it.body) })
        lab.core.releaseHeld()
        assertTrue(first.await().isFailure)
        waitFor("reported") { messages(lab).any { info(it).service["response-type"] == "forward-private-call-response" } }
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertTrue(bob in String(invite.body))
        assertEquals("Manual", invite.headers["Priv-Answer-Mode"])
        assertEquals(1, info(invite).forwardingSequenceNumber)
        lab.service.hangup()
        lab.client.stop()
    }

    /** A service MESSAGE that does not come from the participating function is refused (403), and nothing is done. */
    @Test
    fun serviceRequestFromElsewhereRefused() {
        val lab = start()
        lab.core.message(McpttInfo.CONTENT_TYPE, McpttInfo(callingUserId = bob, requestType = PrivateServiceRules.CALL_BACK).encode(),
            from = "sip:intruder@mcx.test")
        assertEquals(403, lab.core.answer().status)
        assertTrue(lab.service.services.state.value.callBacksReceived.isEmpty())
        lab.client.stop()
    }

    /**
     * §12.1.1.3 steps 5 to 8: the notification of an imminent peril group call (no <alert-ind>) is answered 200, not 488,
     * and sets the group's imminent peril state; its cancellation clears it; steps 3, 4 the same for an emergency.
     */
    @Test
    fun imminentPerilNotificationAnswered() {
        val lab = start()
        val alerts = EmergencyAlerts(lab.client, LocationService.MCPTT, psi, alice)
        lab.client.handlers += alerts
        lab.core.message(McpttInfo.CONTENT_TYPE, McpttInfo(callingGroupId = group, callingUserId = bob, imminentPeril = true).encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("in progress") { alerts.groupStates.value[group]?.imminentPeril == true }
        assertEquals(bob, alerts.groupStates.value[group]!!.by)
        lab.core.message(McpttInfo.CONTENT_TYPE, McpttInfo(callingGroupId = group, callingUserId = bob, emergency = true).encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("emergency") { alerts.groupStates.value[group]?.emergency == true }
        lab.core.message(McpttInfo.CONTENT_TYPE, McpttInfo(callingGroupId = group, callingUserId = bob, imminentPeril = false, emergency = false).encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("cancelled") { group !in alerts.groupStates.value }
        lab.client.stop()
    }

    /**
     * TS 24.379 §12.1.1.6 1)a)ii): the emergency state that keeps an emergency alert area notice from raising the alert is
     * the one this client's alert set. The server's report of an own alert this client did not raise (kept by the server
     * across a restart of the app) is shown, but the notice still raises the alert; once raised, a second notice does not.
     */
    @Test
    fun alertAreaRaisesDespiteAnOwnAlertOnlyReported() {
        val lab = start()
        val alerts = EmergencyAlerts(lab.client, LocationService.MCPTT, psi, alice)
        alerts.defaultGroup = { group }
        alerts.position = { org.openmcx.client.mcvideo.EmergencyLocation(48.05, 2.05) }
        lab.client.handlers.add(0, alerts)
        lab.core.message(McpttInfo.CONTENT_TYPE, McpttInfo(callingGroupId = group, callingUserId = alice, alert = true).encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("reported") { alerts.alerting.value == setOf(group) }
        assertFalse(alerts.inEmergencyState())
        val notice = "<?xml version=\"1.0\"?><mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params>" +
            "<mcptt-request-uri type=\"Normal\"><mcpttURI>$alice</mcpttURI></mcptt-request-uri>" +
            "<anyExt><emergency-alert-area-ind>true</emergency-alert-area-ind></anyExt></mcptt-Params></mcpttinfo>"
        lab.core.message(McpttInfo.CONTENT_TYPE, notice, from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("the alert") { messages(lab).isNotEmpty() }
        assertEquals(true, info(messages(lab).single()).alert)
        waitFor("emergency state") { alerts.inEmergencyState() }
        lab.core.message(McpttInfo.CONTENT_TYPE, notice, from = psi)
        assertEquals(200, lab.core.answer().status)
        Thread.sleep(300)
        assertEquals(1, messages(lab).size)
        assertEquals(listOf(group, null), alerts.notices.filter { it.alertArea && it.entered }.map { it.alerted })
        lab.client.stop()
    }

    /** Annex F.1.2: the service elements written in schema order and read back; the time of request has no zone. */
    @Test
    fun serviceElementsRoundTrip() {
        val info = McpttInfo(requestType = PrivateServiceRules.FORWARD, forwardingSequenceNumber = 2, ambientListeningType = "local-init",
            remotelyInitiatedCallRequest = true, service = mapOf("forwarding-target-orig-id" to bob, "forwarding-reason" to "manual-input",
                "forwarded-by-mcptt-id" to carol))
        val xml = info.encode()
        assertTrue(xml, xml.indexOf("forwarding-reason") < xml.indexOf("forwarded-by-mcptt-id"))
        assertTrue(xml, xml.indexOf("forwarding-target-orig-id") < xml.indexOf("forwarding-sequence-number"))
        assertTrue(xml, "<remotely-initiated-call-request-ind type=\"Normal\"><mcpttBoolean>true</mcpttBoolean>" in xml)
        val back = McpttInfo.parse(xml)
        assertEquals(info.service, back.service)
        assertEquals(2, back.forwardingSequenceNumber)
        assertEquals("local-init", back.ambientListeningType)
        assertEquals(true, back.remotelyInitiatedCallRequest)
        assertEquals(PrivateServiceRules.FORWARD, PrivateServiceRules.typeOf(back))
        assertEquals("2026-11-25T09:08:07", PrivateServiceRules.timeOfRequest(Instant.parse("2026-11-25T09:08:07.5Z")))
        assertEquals(Commencement.FORCED, PrivateServiceRules.commencement("force-auto-mode"))
        assertEquals(Commencement.AUTOMATIC, PrivateServiceRules.commencement(null))
        assertTrue(PrivateServiceRules.forwarded(480, "399 mcx \"175 call is forwarded\""))
        assertFalse(PrivateServiceRules.forwarded(480, "399 mcx \"110 user declined\""))
    }

    /** A GMS answering one group document: [group] with <preconfigured-group-use-only> [useOnly]; counts the GETs. */
    private class GroupGms(val group: String, val useOnly: Boolean) : HttpClient {
        val gets = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (method != "GET" || "byGroupID" !in url) return HttpResponse(404, emptyMap(), ByteArray(0))
            gets.incrementAndGet()
            val doc = """<?xml version="1.0"?><group xmlns="urn:oma:xml:poc:list-service"><list-service uri="$group">""" +
                """<list><entry uri="sip:alice.mcptt@mcx.test"/></list><preconfigured-group-use-only>$useOnly</preconfigured-group-use-only>""" +
                """<supported-services><service enabler="urn:urn-7:3gpp-service.ims.icsi.mcptt"/></supported-services></list-service></group>"""
            return HttpResponse(200, mapOf("Content-Type" to listOf("application/vnd.oma.poc.groups+xml")), doc.toByteArray())
        }
    }

    /**
     * TS 24.379 §10.1.1.2.1.1 (first paragraph) 1), 2): a call on a group whose document says
     * <preconfigured-group-use-only> "true" is refused here and nothing is sent, also for a group the client was not
     * provisioned with (affiliated later, TS 37.579-2 6.1.1.22): its document is read when the call is asked for.
     */
    @Test
    fun preconfiguredUseOnlyGroupReadOnDemandAndNotCalled() = runBlocking {
        val preconf = "sip:operations-preconf@mcx.test"
        val gms = GroupGms(preconf, useOnly = true)
        val lab = start(http = gms)
        assertTrue(preconf !in lab.client.groupDocuments)
        val refused = runCatching { lab.service.groupCall(preconf) }.exceptionOrNull()
        assertTrue("$refused", refused is CallError && "not allowed" in refused.message!!)
        assertEquals(1, gms.gets.get())
        assertEquals(true, lab.client.groupDocuments[preconf]?.preconfiguredUseOnly)
        assertTrue("no INVITE", lab.core.requests.none { it.method == "INVITE" })
        // Asked again: the document is known, not read again, and still nothing is sent.
        assertTrue(runCatching { lab.service.groupCall(preconf) }.isFailure)
        assertEquals(1, gms.gets.get())
        assertTrue(lab.core.requests.none { it.method == "INVITE" })
        lab.client.stop()
    }

    /** The same document read on demand without <preconfigured-group-use-only> "true": the call goes out. */
    @Test
    fun groupDocumentReadOnDemandAllowsTheCall() = runBlocking {
        val gms = GroupGms(group, useOnly = false)
        val lab = start(http = gms, answer = { r -> ok(r, McpttInfo(sessionType = "prearranged", callingGroupId = group)) })
        val call = lab.service.groupCall(group)
        assertEquals(1, gms.gets.get())
        assertTrue(lab.core.requests.any { it.method == "INVITE" })
        lab.service.hangup(call)
        lab.client.stop()
    }

    private val adhocProfile = { activate: Boolean, cancel: Boolean ->
        org.openmcx.client.documents.UserProfile.parse("<mcptt-user-profile xmlns=\"urn:3gpp:mcptt:user-profile:1.0\" " +
            "xmlns:cp=\"urn:ietf:params:xml:ns:common-policy\" XUI-URI=\"$alice\" user-profile-index=\"1\"><Common index=\"1\"/>" +
            "<cp:ruleset><cp:rule id=\"r\"><cp:actions><allow-activate-adhoc-group-emergency-alert>$activate" +
            "</allow-activate-adhoc-group-emergency-alert><allow-cancel-adhoc-group-emergency-alert>$cancel" +
            "</allow-cancel-adhoc-group-emergency-alert></cp:actions></cp:rule></cp:ruleset></mcptt-user-profile>")
    }

    /**
     * TS 24.379 §12.1A.1.1 with §6.2.8.1.19: an unauthorised ad hoc group emergency alert is not sent (2) "shall skip");
     * an authorised one carries <adhoc-alert-ind> "true", the criteria and the location as a <Report> (5) -> §6.2.9.1
     * 2) a)); without a position it is not sent. §12.1A.1.2: the cancellation is one mcptt-info body, sent as itself
     * (§6.5), and an unauthorised cancellation is not sent.
     */
    @Test
    fun adhocAlertAuthorisationLocationAndCancellation() = runBlocking {
        val adhocGroup = "sip:adhoc-alert-1@mcx.test"
        val lab = start(answer = { r -> if (r.method == "MESSAGE" && "adhoc-alert-ind>true" in String(r.body))
            McpttInfo.CONTENT_TYPE to McpttInfo(callingGroupId = adhocGroup).encode().toByteArray() else null })
        val alerts = EmergencyAlerts(lab.client, LocationService.MCPTT, psi, alice)
        lab.client.handlers += alerts
        val here = org.openmcx.client.mcvideo.EmergencyLocation(48.0, 2.0)
        lab.client.profile = adhocProfile(false, false)
        assertTrue(runCatching { alerts.sendAdhoc("organization=mcx.test", here) }.exceptionOrNull()!!.message!!.contains("not allowed"))
        assertTrue("nothing sent", messages(lab).isEmpty())
        lab.client.profile = adhocProfile(true, true)
        assertTrue(runCatching { alerts.sendAdhoc("organization=mcx.test", null) }.exceptionOrNull()!!.message!!.contains("location"))
        assertTrue("nothing sent", messages(lab).isEmpty())
        alerts.sendAdhoc("organization=mcx.test", here)
        val alert = messages(lab).single()
        assertTrue(alert.contentType!!.startsWith("multipart/mixed"))
        assertEquals(true, info(alert).adhocAlert)
        assertEquals("organization=mcx.test", info(alert).participantCriteria)
        assertTrue(part(alert, "application/vnd.3gpp.mcptt-location-info+xml")!!.contains("Report"))
        assertEquals(adhocGroup, alerts.adhoc.value?.group)
        // An unauthorised cancellation stays here; then the authorised one goes, one body and not multipart.
        lab.client.profile = adhocProfile(true, false)
        assertTrue(runCatching { alerts.cancelAdhoc() }.isFailure)
        assertEquals(1, messages(lab).size)
        lab.client.profile = adhocProfile(true, true)
        alerts.cancelAdhoc()
        val cancel = messages(lab).last()
        assertEquals(McpttInfo.CONTENT_TYPE, cancel.contentType)
        val cancelled = McpttInfo.parse(String(cancel.body))
        assertEquals(false, cancelled.adhocAlert)
        assertEquals(adhocGroup, cancelled.requestUri)
        assertNull(alerts.adhoc.value)
        lab.client.stop()
    }
}
