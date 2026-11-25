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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.AffiliationCommand
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.mcvideo.McvideoInfo
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCVideo instance of the affiliation procedures (TS 24.281 §8.2.1.2 to §8.2.1.5): a negotiated-mode request
 * accepted per group, de-affiliation from one group, and the requests sent for another user, with the mcvideo-info,
 * the MCVideo PSI and the MCVideo affiliation command, on the wire of a fake SIP core.
 */
class McvideoProceduresTest {
    private val alice = "sip:alice.mcvideo@mcx.test"
    private val bob = "sip:bob.mcvideo@mcx.test"
    private val one = "sip:video-one@mcx.test"
    private val two = "sip:video-two@mcx.test"
    private val three = "sip:video-three@mcx.test"

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8904/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20,
                  "subscribe_configuration": false, "subscribe_group_documents": false},
        "mcvideo": {"user_id": "$alice", "psi": "sip:mcvideo@mcx.test", "groups": []},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val noHttp = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
    }

    private fun tokens() = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
        mcpttId = "sip:alice.mcptt@mcx.test", mcdataId = null, mcvideoId = alice, sipUri = null, clientId = null,
        refreshToken = "RT", nonce = "n")

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    private fun start(cores: MutableList<FakeSipCore>): McxClient {
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(), noHttp,
            connector = { _, _ -> FakeSipCore().also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false), "mcvideo" to (false to false))
        client.start()
        waitFor("ready") { client.status.value.phase == "ready" && client.status.value.mcvideo.authorised }
        return client
    }

    private fun parts(request: SipRequest) = Multipart.parse(request.contentType, request.body)
    private fun part(request: SipRequest, type: String) = parts(request).firstOrNull { it.mediaType == type }?.text
    private fun info(request: SipRequest) = McvideoInfo.parse(part(request, McvideoInfo.CONTENT_TYPE)!!)
    private fun videoPublishes(core: FakeSipCore) =
        core.requests.filter { it.method == "PUBLISH" && it.headers["Event"] == "presence" && it.uri == "sip:mcvideo@mcx.test" }

    /** The NOTIFY of this client's own MCVideo affiliation subscription (§8.2.1.3), with [groups] affiliated. */
    private fun notifyOwn(core: FakeSipCore, groups: List<String>) {
        val own = core.requests.first { it.method == "SUBSCRIBE" && it.headers["Event"] == "presence" && it.uri == "sip:mcvideo@mcx.test" }
        val ns = McService.MCVIDEO.presNamespace
        core.notify(own, "presence", "active;expires=3600", Affiliation.CONTENT_TYPE, """<?xml version="1.0"?><presence
            xmlns="${Affiliation.PIDF}" xmlns:v="$ns" entity="$alice"><tuple id="urn:uuid:1"><status>${
            groups.joinToString("") { """<v:affiliation group="$it" status="affiliated"/>""" }}</status></tuple></presence>""")
        assertEquals(200, core.answer().status)
    }

    private fun command(core: FakeSipCore, affiliate: List<String>, leave: List<String>) {
        val (type, body) = Multipart.encode(listOf(Part(AffiliationCommand.contentType(McService.MCVIDEO),
            AffiliationCommand.encode(affiliate, leave).toByteArray())))
        core.message(type, String(body), from = "sip:mcvideo@mcx.test", extra = listOf("P-Asserted-Service" to Icsi.MCVIDEO))
        assertEquals(200, core.answer().status) // §8.2.1.5 step 1: at once
    }

    /** One affiliation PUBLISH of MCVideo after [action], answered by the NOTIFY that confirms [confirmed]. */
    private fun changed(client: McxClient, core: FakeSipCore, confirmed: List<String>, action: suspend () -> Unit): SipRequest = runBlocking {
        val before = videoPublishes(core).size
        val job = async(Dispatchers.Default) { action() }
        waitFor("the MCVideo affiliation PUBLISH") { videoPublishes(core).size == before + 1 }
        notifyOwn(core, confirmed)
        job.await()
        videoPublishes(core).last()
    }

    /**
     * §8.2.1.5: the MCVideo command goes to the MCVideo procedures (not MCPTT's); 200 at once; accepted for one group
     * of two, only that one is affiliated (step 3 a); then a de-affiliation request accepted (step 3 b).
     */
    @Test fun negotiatedRequestAcceptedPerGroup() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val video = client.videoProcedures!!
        notifyOwn(core, emptyList())
        command(core, listOf(one, two), emptyList())
        waitFor("request pending") { video.state.value.affiliationRequest != null }
        assertNull("not MCPTT's", client.procedures!!.state.value.affiliationRequest)
        assertEquals(listOf(one, two), video.state.value.affiliationRequest!!.affiliate)
        val publish = changed(client, core, listOf(one)) { video.answerAffiliationRequest(accept = true, only = setOf(one)) }
        assertEquals(alice, info(publish).requestUri)
        assertEquals(Icsi.MCVIDEO, publish.headers["P-Preferred-Service"])
        assertEquals(mapOf("urn:uuid:1" to mapOf(one to "affiliated")), Affiliation.parseAll(part(publish, Affiliation.CONTENT_TYPE)!!))
        assertEquals("accepted", video.state.value.lastAffiliationAnswer)
        assertEquals(listOf(one), client.wantedGroups(McService.MCVIDEO))
        assertEquals("ready", client.status.value.phase)
        // De-affiliation of the one group: Expires 0 (§8.2.1.2 step 4), no group in the PIDF (NOTE 2: none is needed).
        command(core, emptyList(), listOf(one))
        waitFor("second request") { video.state.value.affiliationRequest?.deaffiliate == listOf(one) }
        val leave = changed(client, core, emptyList()) { video.answerAffiliationRequest(accept = true) }
        assertEquals("0", leave.headers["Expires"])
        assertEquals(emptyMap<String, String>(), Affiliation.parseAll(part(leave, Affiliation.CONTENT_TYPE)!!)["urn:uuid:1"].orEmpty())
        assertEquals(emptyList<String>(), client.wantedGroups(McService.MCVIDEO))
        client.stop()
    }

    /** §8.2.1.2: de-affiliation from one group keeps the others in the PUBLISH; declining a request changes nothing. */
    @Test fun deaffiliationFromOneGroup() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val video = client.videoProcedures!!
        notifyOwn(core, emptyList())
        changed(client, core, listOf(one, two, three)) { client.changeAffiliation(McService.MCVIDEO, listOf(one, two, three), emptyList()) }
        waitFor("affiliated") { client.status.value.mcvideo.affiliations.size == 3 }
        val publish = changed(client, core, listOf(one, three)) { client.changeAffiliation(McService.MCVIDEO, emptyList(), listOf(two)) }
        assertEquals("4294967295", publish.headers["Expires"])
        assertEquals(mapOf("urn:uuid:1" to mapOf(one to "affiliated", three to "affiliated")),
            Affiliation.parseAll(part(publish, Affiliation.CONTENT_TYPE)!!))
        assertEquals(listOf(one, three), client.wantedGroups(McService.MCVIDEO))
        // Declined: no PUBLISH.
        command(core, emptyList(), listOf(one))
        waitFor("request") { video.state.value.affiliationRequest != null }
        val before = core.requests.size
        video.answerAffiliationRequest(accept = false)
        Thread.sleep(300)
        assertEquals(before, core.requests.size)
        assertEquals("declined", video.state.value.lastAffiliationAnswer)
        client.stop()
    }

    /** §8.2.1.4 (negotiated mode, sent) and §8.2.1.3: the MCVideo PSI, ICSI, mcvideo-info and command type. */
    @Test fun requestsForAnotherUser() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val video = client.videoProcedures!!
        assertEquals(200, video.affiliateRemote(bob, one, affiliate = true, negotiated = true).status)
        val message = core.requests.last { it.method == "MESSAGE" }
        assertEquals("sip:mcvideo@mcx.test", message.uri)
        assertEquals(Icsi.MCVIDEO, message.headers["P-Preferred-Service"])
        assertEquals(bob, info(message).requestUri)
        assertEquals(listOf(one) to emptyList<String>(), AffiliationCommand.parse(part(message, AffiliationCommand.contentType(McService.MCVIDEO))!!))
        video.affiliationSubscribe(bob)
        val subscribe = core.requests.last { it.method == "SUBSCRIBE" && it.headers["Event"] == "presence" }
        assertEquals("sip:mcvideo@mcx.test", subscribe.uri)
        assertEquals(bob, info(subscribe).requestUri)
        assertTrue(video.state.value.targetAffiliations[bob]?.subscribed == true)
        client.stop()
    }
}
