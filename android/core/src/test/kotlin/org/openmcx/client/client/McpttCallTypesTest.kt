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
import org.openmcx.client.mcptt.CallType
import org.openmcx.client.mcptt.LocalMedia
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcptt.McpttSdp
import org.openmcx.client.mcptt.McpttService
import org.openmcx.client.mcptt.Regroup
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCPTT call types of phase 2 on the wire of a fake SIP core: first-to-answer calls (TS 24.379 §11.1.1.2.1.1,
 * §11.1.1.2.1.2; the TS 37.579-2 6.2.21 INVITE), chat and broadcast group calls (§10.1.2.2.1.1, §6.2.8.2), ad hoc calls by
 * criteria and their modification (§17.2.2.1.1, §17.2.6.1.1), the floor priority offered (TS 24.380 §14.3.3) and the
 * regroup requests and notices (§16.2.1).
 */
class McpttCallTypesTest {
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

    private fun start(floorPriority: String = "", answer: (SipRequest) -> Pair<String, ByteArray>? = { null }): Lab {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(floorPriority), noHttp,
            connector = { _, _ -> FakeSipCore(bodyFor = answer, headersFor = { r ->
                // The MCPTT session identity in the 200 (OK)'s Contact (§17.2.5.1.1).
                if (r.method == "INVITE" && r.to.tag == null) listOf("Contact" to "<sip:mcptt@127.0.0.1:5061;transport=tls;isfocus>") else emptyList() }).also { cores += it } })
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

    /**
     * TS 37.579-2 6.2.21 (TS 24.379 §11.1.1.2.1.2): the first-to-answer INVITE of the suite, without floor control and with
     * Priv-Answer-Mode Manual, rang with 180 (it had been answered 400: the session type was not known); the user's
     * answer gives 200 with the SDP answer; the BYE with <release-reason> "not selected for call" releases it with that
     * reason (SIP BYE step 1).
     */
    @Test
    fun firstToAnswerInviteRingsAndIsAnswered() {
        val lab = start()
        val info = McpttInfo(sessionType = "first-to-answer", requestUri = alice, callingUserId = bob)
        val body = Multipart.encode(listOf(Part("application/sdp", serverSdp(floor = false).toByteArray()),
            Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray())))
        fromCore(lab.core, "INVITE", "fta1", body, listOf("Priv-Answer-Mode" to "Manual", "Session-Expires" to "90",
            "Supported" to "timer", "Require" to "timer"))
        val ringing = lab.core.answer()
        assertEquals(180, ringing.status)
        waitFor("ringing") { lab.service.calls.value.firstOrNull()?.state == "ringing" }
        val call = lab.service.calls.value.single()
        assertEquals(CallType.PRIVATE, call.type)
        assertTrue(call.firstToAnswer)
        assertEquals(bob, call.peer)
        assertFalse("no floor control was offered", call.floorControl)
        assertTrue(lab.service.answer())
        val ok = lab.core.answer()
        assertEquals(200, ok.status)
        val answer = McpttSdp.remote(String(ok.body))
        assertFalse(answer.floorControl)
        waitFor("established") { call.state == "established" }
        val bye = McpttInfo(releaseReason = "not selected for call")
        fromCore(lab.core, "BYE", "fta1", McpttInfo.CONTENT_TYPE to bye.encode().toByteArray(), toTag = ok.to.tag)
        assertEquals(200, lab.core.answer().status)
        waitFor("released") { call.state == "released" }
        assertEquals("not selected for call", call.releaseReason)
        lab.client.stop()
    }

    /** §11.1.1.2.1.2 step 8 c): a first-to-answer invitee busy on another call answers 486; CANCEL gives 487 (SIP CANCEL 4). */
    @Test
    fun firstToAnswerCancelledBeforeTheAnswer() {
        val lab = start()
        val info = McpttInfo(sessionType = "first-to-answer", requestUri = alice, callingUserId = bob)
        val body = Multipart.encode(listOf(Part("application/sdp", serverSdp().toByteArray()),
            Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray())))
        fromCore(lab.core, "INVITE", "fta2", body, listOf("Priv-Answer-Mode" to "Manual"), branch = "z9hG4bKfta2")
        assertEquals(180, lab.core.answer().status)
        waitFor("ringing") { lab.service.calls.value.firstOrNull()?.state == "ringing" }
        val call = lab.service.calls.value.single()
        assertTrue("with floor control", call.floorControl)
        // A second first-to-answer call while ringing: busy.
        fromCore(lab.core, "INVITE", "fta3", body, listOf("Priv-Answer-Mode" to "Manual"), branch = "z9hG4bKfta3")
        assertEquals(486, lab.core.answer().status)
        cseq--
        fromCore(lab.core, "CANCEL", "fta2", null, branch = "z9hG4bKfta2")
        val answers = listOf(lab.core.answer().status, lab.core.answer().status).sorted()
        assertEquals(listOf(200, 487), answers)
        waitFor("released") { call.state == "released" }
        lab.client.stop()
    }

    /**
     * §11.1.1.2.1.1 steps 10 a) and 15 a): the first-to-answer INVITE names two or more targets and the session type
     * "first-to-answer", with no Answer-Mode and no <forwarding-sequence-number> (14B is for private calls); the
     * 200 (OK)'s <mcptt-called-party-id> becomes the peer (200 step 4).
     */
    @Test
    fun firstToAnswerOriginated() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "first-to-answer", calledPartyId = carol)) })
        val call = lab.service.firstToAnswerCall(listOf(bob, carol))
        val invite = lab.core.requests.last { it.method == "INVITE" }
        val sent = info(invite)
        assertEquals("first-to-answer", sent.sessionType)
        assertNull(sent.forwardingSequenceNumber)
        assertNull(invite.headers["Answer-Mode"])
        assertNull(invite.headers["Priv-Answer-Mode"])
        assertEquals(listOf(bob, carol), ResourceLists.parse(part(invite, ResourceLists.CONTENT_TYPE)!!))
        assertEquals(carol, call.peer)
        assertTrue(call.firstToAnswer)
        lab.service.hangup(call)
        lab.client.stop()
    }

    @Test(expected = IllegalArgumentException::class)
    fun firstToAnswerNeedsTwoTargets() = runBlocking { start().service.firstToAnswerCall(listOf(bob)); Unit }

    /** §10.1.2.2.1.1 step 13: a chat group call's INVITE: session type "chat", the group in <mcptt-request-uri>. */
    @Test
    fun chatCallJoin() = runBlocking {
        val chat = "sip:operations-chat@mcx.test"
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "chat", callingGroupId = chat)) })
        val call = lab.service.chatCall(chat)
        val sent = info(lab.core.requests.last { it.method == "INVITE" })
        assertEquals("chat", sent.sessionType)
        assertEquals(chat, sent.requestUri)
        assertEquals("urn:uuid:1", sent.clientId)
        assertEquals(CallType.CHAT, call.type)
        assertEquals("established", call.state)
        // Leave (BYE, §10.1.2.2.3.1), then join again: a new INVITE.
        lab.service.hangup(call)
        assertTrue(lab.core.requests.any { it.method == "BYE" })
        val again = lab.service.chatCall(chat)
        assertEquals(2, lab.core.requests.count { it.method == "INVITE" && info(it).sessionType == "chat" })
        lab.service.hangup(again)
        lab.client.stop()
    }

    /** §6.2.8.2 step 1: a broadcast group call's INVITE has <broadcast-ind> true; its floor messages carry bit B. */
    @Test
    fun broadcastCall() = runBlocking {
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "prearranged", callingGroupId = group, broadcast = true)) })
        val call = lab.service.broadcastCall(group)
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertTrue(String(invite.body), "<broadcast-ind>true</broadcast-ind>" in String(invite.body))
        assertEquals(true, info(invite).broadcast)
        assertTrue(call.broadcast)
        lab.service.hangup(call)
        lab.client.stop()
    }

    /**
     * TS 24.380 §14.3.3: a group call offers the configured floor priority in "mc_priority"; the Floor Request's priority
     * is the one the server answered (here lowered to 5).
     */
    @Test
    fun floorPriorityOfferedAndNegotiated() = runBlocking {
        val lab = start(floorPriority = ", \"floor_priority\": 90",
            answer = { r -> ok(r, McpttInfo(sessionType = "prearranged", callingGroupId = group), priority = 5) })
        val call = lab.service.groupCall(group)
        val offer = McpttSdp.remote(part(lab.core.requests.last { it.method == "INVITE" }, "application/sdp")!!)
        assertEquals(90, offer.floorPriority)
        assertEquals(5, call.floorPriority)
        lab.service.hangup(call)
        // A private call offers none.
        lab.client.stop()
    }

    /**
     * §17.2.2.1.1 step 12: an ad hoc call by criteria carries <call-participants-criterias> and no resource list;
     * §17.2.6.1.1: a modification of its list is a re-INVITE with the mcptt-info as established and the entries with
     * "method" INVITE or BYE; of its criteria, the new criteria and no list.
     */
    @Test
    fun adhocByCriteriaAndModified() = runBlocking {
        val adhoc = "sip:adhoc-1@mcx.test"
        // A <preconfigured-group-id> in the 2xx keys nothing when the media are clear (§17.2.2.1.1 2xx step 3).
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "adhoc", callingGroupId = adhoc, preconfiguredGroupId = "sip:adhoc-security@mcx.test")) })
        val call = lab.service.adhocCall(emptyList(), criteria = "affiliated:$group")
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertEquals("affiliated:$group", info(invite).participantCriteria)
        assertNull(part(invite, ResourceLists.CONTENT_TYPE))
        assertEquals(adhoc, call.groupId)
        // A client without protected media asks for no end-to-end security (§17.2.2.1.1 step 10 c) iii)) and offers RTP/AVP.
        assertNull(info(invite).endToEndSecurity)
        assertTrue(part(invite, "application/sdp")!!.contains("RTP/AVP"))
        assertNull(call.securityGroup)
        lab.service.modifyAdhoc(invite = listOf(carol), remove = listOf(bob))
        val reinvite = lab.core.requests.last { it.method == "INVITE" }
        assertTrue(reinvite.to.tag != null)
        assertEquals(adhoc, info(reinvite).callingGroupId)
        assertEquals(listOf("$carol;method=INVITE", "$bob;method=BYE"), ResourceLists.parse(part(reinvite, ResourceLists.CONTENT_TYPE)!!))
        lab.service.modifyAdhoc(criteria = "affiliated:sip:other@mcx.test")
        val third = lab.core.requests.last { it.method == "INVITE" }
        assertEquals("affiliated:sip:other@mcx.test", info(third).participantCriteria)
        assertNull(part(third, ResourceLists.CONTENT_TYPE))
        assertEquals("affiliated:sip:other@mcx.test", call.criteria)
        // Leave without releasing, then rejoin by the session identity (§17.2.4.1.1, §17.2.5.1.1).
        lab.service.hangup(call, releaseAll = false)
        assertNull(lab.core.requests.last { it.method == "BYE" }.headers["Reason"])
        val again = lab.service.rejoinAdhoc()
        val rejoin = lab.core.requests.last { it.method == "INVITE" }
        assertTrue(rejoin.uri, rejoin.uri.startsWith("sip:mcptt@127.0.0.1"))
        assertEquals("adhoc", info(rejoin).sessionType)
        lab.service.hangup(again)
        lab.client.stop()
    }

    /**
     * TS 24.379 §16.2.1.1: the regroup request is a MESSAGE to the PSI with the client ID and the regroup document;
     * §16.2.1.3: the server's notice is stored, a call on the regroup names the user's constituent group in
     * <associated-group-id> (§10.1.1.2.1.1 step 14 d)); §16.2.1.4 the removal forgets it.
     */
    @Test
    fun regroupRequestNoticeAndCall() = runBlocking {
        val regroup = "sip:operations-regroup-1@mcx.test"
        val lab = start(answer = { r -> ok(r, McpttInfo(sessionType = "prearranged", callingGroupId = regroup)) })
        val result = lab.service.regroups.request("create", regroup, "sip:operations-preconf@mcx.test", groups = listOf(group, "sip:south@mcx.test"))
        assertEquals(200, result.status)
        val message = lab.core.requests.last { it.method == "MESSAGE" }
        assertEquals(psi, message.uri)
        assertEquals("urn:uuid:1", info(message).clientId)
        val sent = Regroup.parse(part(message, Regroup.CONTENT_TYPE)!!)
        assertEquals(listOf(group, "sip:south@mcx.test"), sent.groups)
        val notice = Regroup("create", regroup, "sip:operations-preconf@mcx.test", groups = listOf(group, "sip:south@mcx.test"))
        lab.core.message(Regroup.CONTENT_TYPE, notice.encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("notified") { regroup in lab.service.regroups.state.value.regroups }
        val call = lab.service.groupCall(regroup)
        val invite = lab.core.requests.last { it.method == "INVITE" }
        assertEquals(regroup, info(invite).requestUri)
        assertEquals(group, info(invite).associatedGroupId)
        assertEquals(group, call.associatedGroup)
        lab.service.hangup(call)
        lab.core.message(Regroup.CONTENT_TYPE, Regroup("remove", regroup, null).encode(), from = psi)
        assertEquals(200, lab.core.answer().status)
        waitFor("forgotten") { regroup !in lab.service.regroups.state.value.regroups }
        lab.client.stop()
    }

    /** A regroup notice that does not come from this user's participating function is refused. */
    @Test
    fun regroupNoticeFromAnotherSourceIsRefused() {
        val lab = start()
        lab.core.message(Regroup.CONTENT_TYPE, Regroup("create", "sip:x@mcx.test", "sip:p@mcx.test", groups = listOf(group)).encode(),
            from = "sip:intruder@mcx.test")
        assertEquals(403, lab.core.answer().status)
        assertTrue(lab.service.regroups.state.value.regroups.isEmpty())
        lab.client.stop()
    }

    @Test
    fun privateCallOffersNoFloorPriority() = runBlocking {
        val lab = start(floorPriority = ", \"floor_priority\": 90",
            answer = { r -> ok(r, McpttInfo(sessionType = "private", requestUri = bob)) })
        val call = lab.service.privateCall(bob)
        assertNull(McpttSdp.remote(part(lab.core.requests.last { it.method == "INVITE" }, "application/sdp")!!).floorPriority)
        assertNotNull(call.dialog)
        lab.service.hangup(call)
        lab.client.stop()
    }
}
