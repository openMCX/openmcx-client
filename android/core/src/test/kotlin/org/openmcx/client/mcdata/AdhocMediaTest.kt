//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.openmcx.client.msrp.PassiveEndpoint
import org.openmcx.client.msrp.SocketMsrpConnector
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Ad hoc group communications (TS 24.282 §24.2.2.1.1) and functional alias targets (§22) over the media plane. */
class AdhocMediaTest {
    private val alice = "sip:alice.mcdata@mcx.test"
    private val bob = "sip:bob.mcdata@mcx.test"
    private val carol = "sip:client-c.mcdata@mcx.test"
    private val alias = "sip:dispatcher.fa@mcx.test"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpoints = CopyOnWriteArrayList<PassiveEndpoint>()

    @After fun close() { endpoints.forEach { it.close() } }

    private inner class Server(var redirect: String? = null, val adhocGroup: String? = null) : McdataMediaPeer {
        val invites = CopyOnWriteArrayList<List<Part>>()
        override val userId = alice
        override val clientId = "urn:uuid:1"
        override fun localAddress() = "127.0.0.1"
        override fun contact(feature: String) = "<sip:alice@127.0.0.1>;+g.3gpp.mcdata.$feature"
        override fun resourcePriority(level: String): String? = null
        override suspend fun invite(parts: List<Part>, headers: List<Pair<String, String>>): Pair<SipResponse, Dialog?> {
            invites += parts
            redirect?.let { selected ->
                redirect = null
                return SipResponse(300, "Multiple Choices", Headers().add("Content-Type", McdataInfo.CONTENT_TYPE),
                    McdataInfo(requestUri = selected).encode().toByteArray()) to null
            }
            val e = PassiveEndpoint().also { endpoints += it }
            val answer = MsrpMedia("127.0.0.1", e.uri.port, e.uri.text(), "passive", "recvonly").encode()
            val bodies = listOf(Part("application/sdp", answer.toByteArray())) +
                listOfNotNull(adhocGroup?.let { Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingGroupId = it).encode().toByteArray()) })
            val (type, body) = Multipart.encode(bodies)
            return SipResponse(200, "OK", Headers().add("Content-Type", type), body) to
                Dialog("c@x", "lt", "rt", NameAddr(alice), NameAddr("sip:mcdata@mcx.test"), "sip:mcdata@mcx.test", emptyList(), 1)
        }
        override suspend fun reinvite(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>) = SipResponse(200, "OK")
        override suspend fun bye(dialog: Dialog, headers: List<Pair<String, String>>) {}
        override suspend fun info(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>) = SipResponse(200, "OK")
        override fun event(text: String) = println(text)
    }

    private fun media(server: Server): McdataMediaService {
        val config = ClientConfig.parse("""{
            "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
            "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                    "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
            "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
            "cms": "https://cms", "gms": "https://gms",
            "mcdata": {"user_id": "$alice", "psi": "sip:mcdata@mcx.test", "groups": [], "max_payload_size_sds_cplane_bytes": 100},
            "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")
        val http = object : HttpClient {
            override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) = HttpResponse(404, emptyMap(), ByteArray(0))
        }
        val sds = McdataService(McxClient(scope, config, http, connector = { _, _ -> throw IllegalStateException("offline") }))
        return McdataMediaService(scope, server, sds, SocketMsrpConnector()).also { sds.media = it }
    }

    private fun info(parts: List<Part>) = McdataInfo.parse(parts.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text)
    private fun list(parts: List<Part>) = parts.firstOrNull { it.mediaType == ResourceLists.CONTENT_TYPE }?.let { ResourceLists.parse(it.text) }
    private fun sdp(parts: List<Part>) = MsrpMedia.parse(parts.first { it.mediaType == "application/sdp" }.text)

    @Test fun targets() {
        assertEquals(listOf(bob, carol), AdhocTarget.parse("$bob,$carol").participants)
        assertEquals("medics", AdhocTarget.parse("criteria=medics").criteria)
        assertEquals("sip:adhoc-1@mcx.test", AdhocTarget.parse("group=sip:adhoc-1@mcx.test").group)
        assertTrue(runCatching { AdhocTarget.parse("") }.isFailure)
        assertNull(AdhocTarget.of(bob))
    }

    @Test fun adhocStandaloneSdsAndSession() = runBlocking {
        val server = Server(adhocGroup = "sip:adhoc-7@mcx.test")
        val m = media(server)
        // §24.2.2.1.1: the participants in a resource list, "adhoc-group-sds-session" with a=sendonly for a standalone SDS.
        val sent = m.sendStandalone("adhoc=$bob,$carol", "hello", group = false)
        val first = server.invites[0]
        assertEquals("adhoc-group-sds-session", info(first).requestType)
        assertEquals("urn:uuid:1", info(first).clientId)
        assertEquals(listOf(bob, carol), list(first))
        assertEquals("sendonly", sdp(first).direction)
        val c = m.communications.value.first()
        assertTrue(c.adhoc)
        // Step 4: the ad hoc group the 2xx names.
        assertEquals("sip:adhoc-7@mcx.test", c.group)
        assertEquals("media", sent.plane)
        // A session by criteria: no resource list, the criteria in mcdata-info, a=sendrecv.
        val s = m.openSession("adhoc=criteria=medics", group = false)
        val second = server.invites[1]
        assertEquals("medics", info(second).participantCriteria)
        assertNull(list(second))
        assertEquals("sendrecv", sdp(second).direction)
        assertEquals("open", s.state)
        m.close(s)
        // Not implemented: ad hoc emergency or imminent peril.
        assertTrue(runCatching { m.openSession("adhoc=$bob", false, "emergency") }.exceptionOrNull() is SdsError)
    }

    @Test fun aliasTargetFollowsTheRedirect() = runBlocking {
        val server = Server(redirect = bob)
        val m = media(server)
        m.sendStandalone("fa=$alias", "to the dispatcher", group = false)
        val (first, second) = server.invites
        assertEquals(true, info(first).callToFunctionalAlias)
        assertEquals(listOf(alias), list(first))
        assertEquals(listOf(bob), list(second))
        assertEquals(alias, info(second).calledFunctionalAlias)
        assertNull(info(second).callToFunctionalAlias)
        val c = m.communications.value.single()
        assertEquals(bob, c.peer); assertEquals(alias, c.calledAlias); assertEquals("delivered", c.state)
    }

    private class Answer(override val request: SipRequest) : MediaInvite {
        override val localTag = "ours"
        val responses = CopyOnWriteArrayList<Int>()
        override suspend fun respond(status: Int, body: ByteArray, contentType: String?, extra: List<Pair<String, String>>) { responses += status }
    }

    private fun incoming(direction: String, requestType: String = "adhoc-group-sds-session", called: String? = null): SipRequest {
        val e = PassiveEndpoint().also { endpoints += it }
        val offer = MsrpMedia("127.0.0.1", e.uri.port, e.uri.text(), "passive", direction).encode()
        val info = McdataInfo(requestType = requestType, requestUri = alice, callingUserId = bob, callingGroupId = "sip:adhoc-9@mcx.test",
            calledFunctionalAlias = called)
        val (type, body) = Multipart.encode(listOf(Part("application/sdp", offer.toByteArray()), Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())))
        val headers = Headers().add("Via", "SIP/2.0/TLS 10.0.0.1:5061;branch=z9hG4bK${UUID.randomUUID()}")
            .add("From", "<sip:mcdata@mcx.test>;tag=srv").add("To", "<$alice>").add("Call-ID", "in-${UUID.randomUUID()}")
            .add("CSeq", "1 INVITE").add("Contact", "<sip:mcdata@10.0.0.1>;isfocus").add("P-Asserted-Service", Icsi.MCDATA_SDS)
            .add("Content-Type", type)
        return SipRequest("INVITE", "sip:alice@127.0.0.1", headers, body)
    }

    @Test fun incomingAdhocStandaloneAndSessionToldApartByTheOffer() = runBlocking {
        val m = media(Server())
        // §24.4.2.1.1 step 10: the same request type; "a=sendrecv" only for a session.
        val standalone = Answer(incoming("recvonly", called = alias))
        m.handle(standalone)
        assertEquals(200, standalone.responses.single())
        val a = m.communications.value.last()
        assertEquals("sds", a.kind); assertTrue(a.adhoc); assertEquals("sip:adhoc-9@mcx.test", a.group); assertEquals(alias, a.calledAlias)
        val session = Answer(incoming("sendrecv"))
        m.handle(session)
        assertEquals("session", m.communications.value.last().kind)
    }
}
