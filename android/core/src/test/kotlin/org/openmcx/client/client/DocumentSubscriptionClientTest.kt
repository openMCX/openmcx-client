//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The configuration management subscription (TS 24.484 §6.3.13.2.2, test 5.1 step 18) and the group document
 * subscription (TS 24.481 §6.3.13.2.1, step 19) of the client, against a SIP core on one flow.
 */
class DocumentSubscriptionClientTest {
    private fun config(onNotify: Boolean) = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8901/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": ["sip:operations@mcx.test"],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20,
                  "documents_on_notify": $onNotify, "gms_subscription_psi": "sip:gms-proxy@mcx.test"},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    /** The HTTP requests of the client, each answered 404 (the documents' content is not under test here). */
    private val gets = CopyOnWriteArrayList<String>()
    private val http = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            gets += "$method $url"
            if (url.endsWith("org.3gpp.mcptt.service-config/global/service-config.xml"))
                return HttpResponse(200, mapOf("ETag" to listOf("\"s1\"")), serviceConfiguration.toByteArray())
            return HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    private fun tokens() = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
        mcpttId = "sip:alice.mcptt@mcx.test", mcdataId = null, mcvideoId = null, sipUri = null, clientId = null,
        refreshToken = "RT", nonce = "n")

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    /** The MCPTT service configuration, served when the client reads it: no signalling protection. */
    private val serviceConfiguration = """<?xml version="1.0"?><service-configuration-info xmlns="urn:3gpp:mcptt:mcpttServiceConfig:1.0">""" +
        """<service-configuration-params><on-network><signalling-protection><confidentiality-protection>false</confidentiality-protection>""" +
        """<integrity-protection>false</integrity-protection></signalling-protection></on-network></service-configuration-params>""" +
        """</service-configuration-info>"""

    private fun start(onNotify: Boolean, cores: MutableList<FakeSipCore>): McxClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = McxClient(scope, config(onNotify), http, connector = { _, _ -> FakeSipCore().also { cores += it } })
        client.tokens = tokens()
        // documents_on_notify: the MCPTT service configuration comes only with the configuration NOTIFY.
        client.serviceProtection = if (onNotify) emptyMap() else mapOf("mcptt" to (false to false))
        client.start()
        return client
    }

    private fun FakeSipCore.xcapSubscribe(uri: String): SipRequest? =
        requests.firstOrNull { it.method == "SUBSCRIBE" && it.headers["Event"] == "xcap-diff" && it.uri == uri }

    private fun entries(request: SipRequest) =
        ResourceLists.parse(Multipart.parse(request.contentType, request.body).first { it.mediaType == ResourceLists.CONTENT_TYPE }.text)

    private fun info(request: SipRequest) =
        Multipart.parse(request.contentType, request.body).first { it.mediaType == "application/vnd.3gpp.mcptt-info+xml" }.text

    private fun diff(sel: String) = """<?xml version="1.0"?><xcap-diff xmlns="urn:ietf:params:xml:ns:xcap-diff" xcap-root="https://gms/">""" +
        """<document new-etag="e2" sel="$sel"/></xcap-diff>"""

    /**
     * documents_on_notify (test 5.1): step 18 before the service authorisation, the documents after its NOTIFY, then
     * step 17, then step 19 to the GMS-URI with the group read after its NOTIFY; at MC log-off both end (Expires 0).
     */
    @Test fun configurationBeforeAuthorisationThenGroups() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(true, cores)
        waitFor("configuration SUBSCRIBE") { cores.isNotEmpty() && cores[0].xcapSubscribe("sip:mcptt@mcx.test") != null }
        val core = cores[0]
        val subscribe = core.xcapSubscribe("sip:mcptt@mcx.test")!!
        // TS 24.484 §6.3.13.2.2 b) to f).
        assertEquals(DocumentSubscriptionsHeaders.icsi, subscribe.headers["P-Preferred-Service"])
        assertTrue(subscribe.headers["Contact"]!!.contains("g.3gpp.icsi-ref"))
        assertEquals("3600", subscribe.headers["Expires"])
        assertEquals("application/xcap-diff+xml", subscribe.headers["Accept"])
        assertEquals(listOf("org.3gpp.mcptt.ue-config/users/sip:alice.mcptt@mcx.test/urn:uuid:1",
            "org.3gpp.mcptt.user-profile/users/sip:alice.mcptt@mcx.test/mcptt-user-profile-1.xml",
            "org.3gpp.mcptt.service-config/global/service-config.xml"), entries(subscribe))
        assertTrue(info(subscribe).contains("AT"))
        // No service authorisation and no document read before the NOTIFY.
        Thread.sleep(500)
        assertTrue(core.requests.none { it.method == "PUBLISH" })
        assertTrue(gets.none { it.contains("org.3gpp.mcptt.user-profile") })
        core.notify(subscribe, "xcap-diff", "active;expires=3600", "application/xcap-diff+xml", diff("org.3gpp.mcptt.user-profile/users/sip:alice.mcptt@mcx.test/mcptt-user-profile-1.xml"))
        assertEquals(200, core.answer().status)
        waitFor("the documents read and the service authorised") { core.requests.any { it.method == "PUBLISH" && it.headers["Event"] == "poc-settings" } }
        assertTrue(gets.any { it == "GET https://cms/org.3gpp.mcptt.ue-config/users/sip:alice.mcptt@mcx.test/urn:uuid:1" })
        assertTrue(gets.any { it == "GET https://cms/org.3gpp.mcptt.user-profile/users/sip:alice.mcptt@mcx.test/mcptt-user-profile-1.xml" })
        assertTrue(gets.any { it == "GET https://cms/org.3gpp.mcptt.service-config/global/service-config.xml" })
        val firstPublish = core.requests.indexOfFirst { it.method == "PUBLISH" }
        assertTrue(core.requests.indexOf(subscribe) < firstPublish)
        // Step 19: to the GMS-URI, after the authorisation, the group document read after its NOTIFY (not at sign-in).
        waitFor("group SUBSCRIBE") { core.xcapSubscribe("sip:gms-proxy@mcx.test") != null }
        val group = core.xcapSubscribe("sip:gms-proxy@mcx.test")!!
        assertEquals(listOf("org.openmobilealliance.groups/global/byGroupID/sip:operations@mcx.test"), entries(group))
        assertTrue(core.requests.indexOf(group) > firstPublish)
        assertTrue(gets.none { it.contains("byGroupID") })
        core.notify(group, "xcap-diff", "active;expires=3600", "application/xcap-diff+xml", diff("org.openmobilealliance.groups/global/byGroupID/sip:operations@mcx.test"))
        assertEquals(200, core.answer().status)
        waitFor("group document read") { gets.any { it == "GET https://gms/org.openmobilealliance.groups/global/byGroupID/sip:operations@mcx.test" } }
        waitFor("status") { client.status.value.configurationSubscription == 200 && client.status.value.groupDocumentSubscription == 200 &&
            client.status.value.configurationNotifications == 1 && client.status.value.groupDocumentNotifications == 1 }
        // MC log-off: both subscriptions end in their dialogs; a final NOTIFY is answered 200 (RFC 6665 §4.1.2.3).
        client.logOff()
        val ends = core.requests.filter { it.method == "SUBSCRIBE" && it.headers["Event"] == "xcap-diff" && it.headers["Expires"] == "0" }
        assertEquals(setOf(subscribe.callId, group.callId), ends.map { it.callId }.toSet())
        core.notify(subscribe, "xcap-diff", "terminated;reason=timeout", null, null)
        assertEquals(200, core.answer().status)
        client.stop()
    }

    /** Without documents_on_notify: the documents at sign-in, both subscriptions after the service authorisation. */
    @Test fun subscriptionsAfterTheAuthorisation() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(false, cores)
        waitFor("both SUBSCRIBEs") { cores.isNotEmpty() && cores[0].xcapSubscribe("sip:mcptt@mcx.test") != null &&
            cores[0].xcapSubscribe("sip:gms-proxy@mcx.test") != null }
        val core = cores[0]
        val firstPublish = core.requests.indexOfFirst { it.method == "PUBLISH" }
        assertTrue(firstPublish >= 0 && core.requests.indexOf(core.xcapSubscribe("sip:mcptt@mcx.test")) > firstPublish)
        client.stop()
    }
}

private object DocumentSubscriptionsHeaders { const val icsi = "urn:urn-7:3gpp-service.ims.icsi.mcptt" }
