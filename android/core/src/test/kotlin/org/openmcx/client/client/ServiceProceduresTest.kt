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
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.AffiliationCommand
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.PocSettings
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCPTT service settings (TS 24.379 §7.2.3, §7.2.4), the affiliation of other users (§9.2.1.2 to §9.2.1.5), the
 * group dynamic data subscription (§9.2.1.6) and monitored groups, on the wire of a fake SIP core.
 */
class ServiceProceduresTest {
    private val alice = "sip:alice.mcptt@mcx.test"
    private val bob = "sip:bob.mcptt@mcx.test"
    private val group = "sip:operations@mcx.test"

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "$alice", "psi": "sip:mcptt@mcx.test", "groups": [], "profile_index": 2,
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20,
                  "subscribe_configuration": false, "subscribe_group_documents": false},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val noHttp = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
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

    private fun start(cores: MutableList<FakeSipCore>, statusFor: (SipRequest) -> Int? = { null }): McxClient {
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(), noHttp,
            connector = { _, _ -> FakeSipCore(statusFor = statusFor).also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false))
        client.start()
        waitFor("ready") { client.status.value.phase == "ready" }
        return client
    }

    private fun parts(request: SipRequest) = Multipart.parse(request.contentType, request.body)
    private fun info(request: SipRequest) = McpttInfo.parse(parts(request).first { it.mediaType == McpttInfo.CONTENT_TYPE }.text)
    private fun part(request: SipRequest, type: String) = parts(request).firstOrNull { it.mediaType == type }?.text
    private fun FakeSipCore.last(method: String, event: String? = null) =
        requests.last { it.method == method && (event == null || it.headers["Event"] == event) }

    /** §7.2.3: the settings-only PUBLISH modifies the authorisation's publication; Expires 0 removes it (log-off, NOTE 3). */
    @Test fun settingsPublishAndRemove() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val authorisation = core.requests.first { it.method == "PUBLISH" && it.headers["Event"] == "poc-settings" }
        client.setAnswerMode(McService.MCPTT, "manual")
        val result = client.procedures!!.settingsPublish()
        assertEquals(200, result.status)
        val publish = core.last("PUBLISH", "poc-settings")
        assertEquals("sip:mcptt@mcx.test", publish.uri)
        assertEquals(Icsi.MCPTT, publish.headers["P-Preferred-Service"])
        assertEquals("4294967295", publish.headers["Expires"])
        // RFC 3903: the entity-tag the authorisation's 200 gave (the fake core's "e<n>").
        assertEquals("e${core.requests.indexOf(authorisation) + 1}", publish.headers["SIP-If-Match"])
        val i = info(publish)
        assertEquals(alice, i.requestUri)
        assertEquals("urn:uuid:1", i.clientId)
        assertNull("no access token: settings only", i.accessToken)
        assertEquals(PocSettings.Entity("urn:uuid:1", "manual", 2, false), PocSettings.parse(part(publish, PocSettings.CONTENT_TYPE)!!).single())
        assertEquals(200, client.procedures!!.state.value.settingsPublication?.status)
        // The next one modifies this publication.
        client.procedures!!.settingsPublish()
        assertEquals("e${core.requests.indexOf(publish) + 1}", core.last("PUBLISH", "poc-settings").headers["SIP-If-Match"])
        client.procedures!!.settingsPublish(remove = true)
        assertEquals("0", core.last("PUBLISH", "poc-settings").headers["Expires"])
        assertFalse(client.status.value.mcptt.authorised)
        client.stop()
    }

    /** §7.2.3: a refused settings PUBLISH is reported with its Warning, and the settings are not taken as set. */
    @Test fun settingsPublishRefused() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        var refuse = false
        val client = start(cores) { if (refuse && it.method == "PUBLISH") 403 else null }
        refuse = true
        val result = client.procedures!!.settingsPublish()
        assertEquals(403, result.status)
        assertEquals(403, client.procedures!!.state.value.settingsPublication?.status)
        assertTrue(client.status.value.mcptt.authorised)
        client.stop()
    }

    /** §7.2.4: subscribe (or fetch), the NOTIFY cached per client, re-subscribe and de-subscribe in the dialog. */
    @Test fun settingsSubscription() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val procedures = client.procedures!!
        assertEquals(200, procedures.settingsSubscribe().status)
        val subscribe = core.last("SUBSCRIBE", "poc-settings")
        assertEquals("sip:mcptt@mcx.test", subscribe.uri)
        assertEquals(Icsi.MCPTT, subscribe.headers["P-Preferred-Service"])
        assertEquals(PocSettings.CONTENT_TYPE, subscribe.headers["Accept"])
        assertEquals("4294967295", subscribe.headers["Expires"])
        assertEquals(alice, info(subscribe).requestUri)
        core.notify(subscribe, "poc-settings", "active;expires=3600", PocSettings.CONTENT_TYPE, PocSettings.encode("urn:uuid:9", "manual", 1))
        assertEquals(200, core.answer().status)
        waitFor("settings cached") { procedures.state.value.serviceSettings["urn:uuid:9"] != null }
        assertEquals(ClientSettings("manual", 1, false, own = false), procedures.state.value.serviceSettings["urn:uuid:9"])
        assertTrue(procedures.state.value.settingsSubscribed)
        procedures.settingsResubscribe()
        val again = core.last("SUBSCRIBE", "poc-settings")
        assertEquals(subscribe.callId, again.callId)
        assertEquals("4294967295", again.headers["Expires"])
        assertEquals(PocSettings.CONTENT_TYPE, again.headers["Accept"])
        procedures.settingsResubscribe(end = true)
        assertEquals("0", core.last("SUBSCRIBE", "poc-settings").headers["Expires"])
        assertFalse(procedures.state.value.settingsSubscribed)
        // §7.2.4 step 7: a fetch has Expires 0, and its NOTIFY is read.
        procedures.settingsSubscribe(fetch = true)
        val fetch = core.last("SUBSCRIBE", "poc-settings")
        assertEquals("0", fetch.headers["Expires"])
        assertTrue(fetch.callId != subscribe.callId)
        core.notify(fetch, "poc-settings", "terminated;reason=timeout", PocSettings.CONTENT_TYPE, PocSettings.encode("urn:uuid:1", "automatic", 2))
        assertEquals(200, core.answer().status)
        waitFor("own settings cached") { procedures.state.value.serviceSettings["urn:uuid:1"]?.own == true }
        client.stop()
    }

    private fun bobAffiliation(groups: Map<String, String>) = """<?xml version="1.0"?><presence xmlns="${Affiliation.PIDF}"
        xmlns:mcpttPI10="urn:3gpp:ns:mcpttPresInfo:1.0" entity="$bob"><tuple id="urn:uuid:b"><status>""" +
        groups.entries.joinToString("") { "<mcpttPI10:affiliation group=\"${it.key}\" status=\"${it.value}\"/>" } + "</status></tuple></presence>"

    /**
     * §9.2.1.3: another user's affiliation subscribed to (no per-client filter); §9.2.1.2 mandatory mode: the PUBLISH
     * for the targeted client with every group of its interest; §9.2.1.4 negotiated mode: the MESSAGE with the command.
     */
    @Test fun remoteAffiliation() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val procedures = client.procedures!!
        // Mandatory mode needs the target's client first.
        assertTrue(runCatching { procedures.affiliateRemote(bob, group, affiliate = true, negotiated = false) }.isFailure)
        procedures.affiliationSubscribe(bob)
        val subscribe = core.last("SUBSCRIBE", "presence")
        assertEquals(bob, info(subscribe).requestUri)
        assertEquals(Affiliation.CONTENT_TYPE, subscribe.headers["Accept"])
        assertEquals("4294967295", subscribe.headers["Expires"])
        assertNull("no per-client filter for another user", part(subscribe, "application/simple-filter+xml"))
        core.notify(subscribe, "presence", "active;expires=3600", Affiliation.CONTENT_TYPE,
            bobAffiliation(mapOf("sip:other@mcx.test" to "affiliated", "sip:old@mcx.test" to "deaffiliating")))
        assertEquals(200, core.answer().status)
        waitFor("bob's affiliation") { procedures.state.value.targetAffiliations[bob]?.clients?.isNotEmpty() == true }
        procedures.affiliateRemote(bob, group, affiliate = true, negotiated = false)
        val publish = core.last("PUBLISH", "presence")
        assertEquals("4294967295", publish.headers["Expires"])
        assertEquals(bob, info(publish).requestUri)
        val pidf = part(publish, Affiliation.CONTENT_TYPE)!!
        assertEquals(mapOf("urn:uuid:b" to mapOf("sip:other@mcx.test" to "affiliated", group to "affiliated")), Affiliation.parseAll(pidf))
        assertFalse("no status attribute (§9.2.1.2 5 e)", pidf.contains("status=\""))
        assertNotNull(Affiliation.pId(pidf))
        // The last group removed: Expires 0 and no PIDF (§9.2.1.2 step 4).
        core.notify(subscribe, "presence", "active;expires=3600", Affiliation.CONTENT_TYPE, bobAffiliation(mapOf(group to "affiliated")))
        assertEquals(200, core.answer().status)
        waitFor("bob's new affiliation") { procedures.state.value.targetAffiliations[bob]?.notifications == 2 }
        procedures.affiliateRemote(bob, group, affiliate = false, negotiated = false)
        val leave = core.last("PUBLISH", "presence")
        assertEquals("0", leave.headers["Expires"])
        assertNull(part(leave, Affiliation.CONTENT_TYPE))
        // Negotiated mode.
        val result = procedures.affiliateRemote(bob, group, affiliate = false, negotiated = true)
        assertEquals(200, result.status)
        val message = core.last("MESSAGE")
        assertEquals("sip:mcptt@mcx.test", message.uri)
        assertEquals(Icsi.MCPTT, message.headers["P-Preferred-Service"])
        assertEquals(bob, info(message).requestUri)
        assertEquals(emptyList<String>() to listOf(group), AffiliationCommand.parse(part(message, AffiliationCommand.contentType(McService.MCPTT))!!))
        procedures.affiliationUnsubscribe(bob)
        assertEquals("0", core.requests.last { it.method == "SUBSCRIBE" && it.callId == subscribe.callId }.headers["Expires"])
        client.stop()
    }

    /** §9.2.1.5: 200 at once, the user's answer awaited; accepted, the groups changed as in §9.2.1.2; declined, nothing. */
    @Test fun negotiatedRequestReceived() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val procedures = client.procedures!!
        val own = core.requests.first { it.method == "SUBSCRIBE" && it.headers["Event"] == "presence" }
        val body = AffiliationCommand.encode(listOf(group), emptyList())
        val (type, multipart) = Multipart.encode(listOf(org.openmcx.client.body.Part(AffiliationCommand.contentType(McService.MCPTT), body.toByteArray())))
        core.message(type, String(multipart), extra = listOf("P-Asserted-Service" to Icsi.MCPTT))
        assertEquals(200, core.answer().status)
        waitFor("request pending") { procedures.state.value.affiliationRequest != null }
        assertEquals(listOf(group), procedures.state.value.affiliationRequest!!.affiliate)
        val publishes = core.requests.count { it.method == "PUBLISH" && it.headers["Event"] == "presence" }
        val answer = async(Dispatchers.Default) { procedures.answerAffiliationRequest(accept = true) }
        waitFor("the affiliation PUBLISH") { core.requests.count { it.method == "PUBLISH" && it.headers["Event"] == "presence" } == publishes + 1 }
        val publish = core.last("PUBLISH", "presence")
        assertEquals(mapOf("urn:uuid:1" to mapOf(group to "affiliated")), Affiliation.parseAll(part(publish, Affiliation.CONTENT_TYPE)!!))
        core.notify(own, "presence", "active;expires=3600", Affiliation.CONTENT_TYPE, """<?xml version="1.0"?><presence xmlns="${Affiliation.PIDF}"
            xmlns:mcpttPI10="urn:3gpp:ns:mcpttPresInfo:1.0" entity="$alice"><tuple id="urn:uuid:1"><status>
            <mcpttPI10:affiliation group="$group" status="affiliated"/></status></tuple></presence>""")
        assertEquals(200, core.answer().status)
        answer.await()
        assertEquals("accepted", procedures.state.value.lastAffiliationAnswer)
        assertNull(procedures.state.value.affiliationRequest)
        assertEquals(listOf(group), client.wantedGroups(McService.MCPTT))
        // A second request, declined: no PUBLISH.
        val (type2, body2) = Multipart.encode(listOf(org.openmcx.client.body.Part(AffiliationCommand.contentType(McService.MCPTT),
            AffiliationCommand.encode(emptyList(), listOf(group)).toByteArray())))
        core.message(type2, String(body2))
        assertEquals(200, core.answer().status)
        waitFor("second request") { procedures.state.value.affiliationRequest?.deaffiliate == listOf(group) }
        val before = core.requests.size
        procedures.answerAffiliationRequest(accept = false)
        Thread.sleep(300)
        assertEquals(before, core.requests.size)
        assertEquals("declined", procedures.state.value.lastAffiliationAnswer)
        assertTrue(runCatching { procedures.answerAffiliationRequest(true) }.isFailure)
        client.stop()
    }

    /** §9.2.1.6: the SUBSCRIBE naming the group with the per-group filter; the NOTIFY's dynamic data; de-subscribe. */
    @Test fun groupDynamicDataSubscription() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val procedures = client.procedures!!
        procedures.gddSubscribe(group, items = listOf("call", "affiliated"))
        val subscribe = core.last("SUBSCRIBE", "presence")
        assertEquals(group, info(subscribe).requestUri)
        assertEquals(Affiliation.CONTENT_TYPE, subscribe.headers["Accept"])
        assertEquals("4294967295", subscribe.headers["Expires"])
        val filter = part(subscribe, "application/simple-filter+xml")!!
        assertTrue(filter.contains("//pidf:presence[@entity=&quot;$group&quot;]"))
        assertTrue(filter.contains("groupCallOngoing"))
        core.notify(subscribe, "presence", "active;expires=3600", Affiliation.CONTENT_TYPE, """<?xml version="1.0"?>
            <presence xmlns="${Affiliation.PIDF}" xmlns:mcpttPI10="urn:3gpp:ns:mcpttPresInfo:1.0" entity="$group">
            <tuple id="$bob"><status><mcpttPI10:affiliation client="urn:uuid:b"/></status></tuple>
            <mcpttPI10:additionalData groupCallOngoing="false"/></presence>""")
        assertEquals(200, core.answer().status)
        waitFor("dynamic data") { procedures.state.value.groupDynamicData[group]?.data != null }
        val data = procedures.state.value.groupDynamicData.getValue(group).data!!
        assertEquals(false, data.callOngoing)
        assertEquals(listOf(bob), data.members.map { it.id })
        assertTrue(runCatching { procedures.gddSubscribe(group) }.isFailure)
        procedures.gddResubscribe(group, end = true)
        assertEquals("0", core.requests.last { it.callId == subscribe.callId && it.method == "SUBSCRIBE" }.headers["Expires"])
        assertFalse(procedures.state.value.groupDynamicData.getValue(group).subscribed)
        client.stop()
    }

    /** A NOTIFY of no subscription is still 481; a MESSAGE without an affiliation command goes on to the services. */
    @Test fun othersAreNotTaken() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val own = core.requests.first { it.method == "SUBSCRIBE" && it.headers["Event"] == "presence" }
        core.notify(own, "presence", "active", null, null, callId = "unknown@core")
        assertEquals(481, core.answer().status)
        core.message("text/plain", "hello")
        assertEquals(488, core.answer().status)
        client.stop()
    }

    /** §9.2.1.2: a monitored group must be one of the user profile; none of it without a profile. */
    @Test fun monitorNeedsAProfileGroup() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        assertTrue(runCatching { client.monitor(listOf("sip:elsewhere@mcx.test")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<String>(), client.monitoredGroups)
        client.stop()
    }
}
