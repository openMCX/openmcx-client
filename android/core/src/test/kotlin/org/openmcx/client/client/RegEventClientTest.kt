//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.RegInfo
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.sip.Connection
import org.openmcx.client.sip.Framed
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import org.openmcx.client.sip.StreamFramer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** The client's reg event subscription and what its NOTIFYs make it do (TS 24.229 §5.1.1.3, §5.1.1.5A, §5.1.1.7). */
private typealias Core = FakeSipCore

class RegEventClientTest {
    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8901/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val noHttp = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
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

    private fun start(cores: MutableList<Core>): McxClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = McxClient(scope, config(), noHttp, connector = { _, _ -> Core().also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false))
        client.start()
        waitFor("ready") { client.status.value.phase == "ready" && client.status.value.regSubscribed }
        return client
    }

    /** §5.1.1.3 items a) to e) and §5.1.1.4.1: the subscription after the registration, and the reregistration time. */
    @Test
    fun subscribesAfterTheRegistration() = runBlocking {
        val cores = CopyOnWriteArrayList<Core>()
        val client = start(cores)
        val subscribe = cores[0].regSubscribe()
        assertEquals("sip:alice@mcx.test", subscribe.uri)
        assertEquals("sip:alice@mcx.test", subscribe.from.uri)
        assertEquals("sip:alice@mcx.test", subscribe.to.uri)
        assertEquals("600000", subscribe.headers["Expires"])
        assertEquals(RegInfo.CONTENT_TYPE, subscribe.headers["Accept"])
        assertTrue(cores[0].requests.indexOfFirst { it.method == "REGISTER" } < cores[0].requests.indexOf(subscribe))
        // A first NOTIFY (registered) is answered 200 and asks nothing.
        cores[0].regNotify(0, "active", "registered", 3600)
        assertEquals(200, cores[0].answer().status)
        // A NOTIFY of no subscription of the client: 481; a document that is not reginfo: 400 (RFC 6665 §4.1.3).
        cores[0].notify(subscribe, "reg", "active", RegInfo.CONTENT_TYPE, "<reginfo/>", callId = "other@core")
        assertEquals(481, cores[0].answer().status)
        cores[0].notify(subscribe, "reg", "active", RegInfo.CONTENT_TYPE, "<reginfo/>")
        assertEquals(400, cores[0].answer().status)
        // §5.1.1.4.1: a 3600 s registration is renewed 600 s before its expiry (no longer at two thirds).
        val registeredAt = System.currentTimeMillis() / 1000
        assertTrue(client.status.value.registered)
        assertEquals(1, cores[0].requests.count { it.method == "REGISTER" })
        assertEquals((registeredAt + 3000).toDouble(), nextRegisterAt(client), 5.0)
        client.stop()
    }

    private fun nextRegisterAt(client: McxClient): Double =
        McxClient::class.java.getDeclaredField("nextRegisterAt").apply { isAccessible = true }.getLong(client).toDouble()

    /** §5.1.1.5A: a 3600 s registration shortened to 120 s is renewed at once, in the same registration (Call-ID). */
    @Test
    fun shortenedRegistrationIsRenewed() = runBlocking {
        val cores = CopyOnWriteArrayList<Core>()
        val client = start(cores)
        cores[0].regNotify(0, "active", "registered", 3600)
        assertEquals(200, cores[0].answer().status)
        cores[0].regNotify(1, "active", "shortened", 120)
        assertEquals(200, cores[0].answer().status)
        waitFor("reregistration", 8) { cores[0].requests.count { it.method == "REGISTER" } == 2 }
        val (first, second) = cores[0].requests.filter { it.method == "REGISTER" }
        assertEquals(first.callId, second.callId)
        // RFC 3680 §5.2: an older document is discarded (no second reregistration).
        cores[0].regNotify(0, "active", "shortened", 60)
        assertEquals(200, cores[0].answer().status)
        Thread.sleep(6_000)
        assertEquals(2, cores[0].requests.count { it.method == "REGISTER" })
        client.stop()
    }

    /** §5.1.1.7: "deactivated": the TLS session goes, then an initial registration (new Call-ID) and a new subscription. */
    @Test
    fun deactivatedStartsAnInitialRegistration() = runBlocking {
        val cores = CopyOnWriteArrayList<Core>()
        val client = start(cores)
        val first = cores[0].requests.first { it.method == "REGISTER" }
        cores[0].regNotify(0, "terminated", "deactivated", registrationState = "terminated", state = "terminated;reason=deactivated")
        assertEquals(200, cores[0].answer().status)
        waitFor("new flow") { cores.size == 2 }
        waitFor("ready again") { client.status.value.phase == "ready" && client.status.value.regSubscribed }
        val again = cores[1].requests.first { it.method == "REGISTER" }
        assertNotEquals(first.callId, again.callId)
        assertFalse(client.status.value.loggedOff)
        assertTrue(cores[1].requests.any { it.method == "PUBLISH" && it.headers["Event"] == "poc-settings" && it.headers["SIP-If-Match"] == null })
        client.stop()
    }

    /** §5.1.1.7: "rejected": dialogs released, the client stays off (shown) until the user logs on. */
    @Test
    fun rejectedStaysOffUntilLogOn() = runBlocking {
        val cores = CopyOnWriteArrayList<Core>()
        val client = start(cores)
        cores[0].regNotify(0, "terminated", "rejected", registrationState = "terminated", state = "terminated;reason=rejected")
        assertEquals(200, cores[0].answer().status)
        waitFor("off") { client.status.value.loggedOff && client.status.value.phase == "logged-off" }
        assertEquals("rejected", client.status.value.networkDeregistered)
        assertFalse(client.status.value.mcptt.authorised)
        Thread.sleep(2_000)
        assertEquals(1, cores.size)
        client.logOn()
        waitFor("ready after log-on") { client.status.value.phase == "ready" && client.status.value.regSubscribed }
        assertEquals(null, client.status.value.networkDeregistered)
        assertNotEquals(cores[0].requests.first { it.method == "REGISTER" }.callId, cores[1].requests.first { it.method == "REGISTER" }.callId)
        client.stop()
    }

    /** RFC 6665 §4.1.2.3: the final NOTIFY of a subscription ended at log-off is answered 200 (was 481). */
    @Test
    fun finalNotifyAfterTheUnsubscribeIsAnswered() = runBlocking {
        val cores = CopyOnWriteArrayList<Core>()
        val client = start(cores)
        client.logOff()
        val core = cores[0]
        assertTrue(core.requests.any { it.method == "SUBSCRIBE" && it.headers["Event"] == "presence" && it.headers["Expires"] == "0" })
        assertEquals(200, core.answer().status)
        client.stop()
    }

    /**
     * §5.1.1.3: a start-up that fails after the registration (the MC service refused) starts over with an initial
     * registration on a new flow, and that registration has its own reg event subscription.
     */
    @Test
    fun aFailedStartUpSubscribesAgainAfterTheNextRegistration() = runBlocking {
        val cores = CopyOnWriteArrayList<Core>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = McxClient(scope, config(), noHttp, connector = { _, _ -> Core(refusePublish = cores.isEmpty()).also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false))
        client.start()
        waitFor("first flow subscribed") { cores.isNotEmpty() && cores[0].requests.any { it.method == "SUBSCRIBE" && it.headers["Event"] == "reg" } }
        waitFor("a new flow after the failed start-up", 30) { cores.size >= 2 }
        waitFor("ready on the new flow", 30) { client.status.value.phase == "ready" }
        waitFor("reg subscription on the new flow") { cores[1].requests.any { it.method == "SUBSCRIBE" && it.headers["Event"] == "reg" } }
        assertTrue(client.status.value.regSubscribed)
        client.stop()
    }
}
