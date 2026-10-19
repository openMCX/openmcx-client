//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** What the client does when the system cannot be reached, and when IdMS signs the user out. */
class RecoveryTest {
    private val issuer = "https://idms.example.test"

    private fun config(password: String?, redirectPort: Int = 8900) = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "$issuer", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:$redirectPort/callback"
                 ${if (password != null) ""","username": "alice", "password": "$password"""" else ""}},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private fun tokens(expiresIn: Long) = Tokens(accessToken = "AT", idToken = "ID",
        expiresAt = System.currentTimeMillis() / 1000 + expiresIn, mcpttId = "sip:alice.mcptt@mcx.test", mcdataId = null,
        mcvideoId = null, sipUri = null, clientId = null, refreshToken = "RT", nonce = "n")

    /** IdMS: discovery, and a token endpoint that refuses every refresh token (RFC 6749 §5.2 invalid_grant). */
    private class RefusingIdms(private val issuer: String) : HttpClient {
        val tokenRequests = AtomicInteger()
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
            when {
                url.endsWith("/.well-known/openid-configuration") -> HttpResponse(200, emptyMap(), """{"issuer":"$issuer",
                    "authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}""".toByteArray())
                url.endsWith("/token") -> { tokenRequests.incrementAndGet(); HttpResponse(400, emptyMap(), """{"error":"invalid_grant"}""".toByteArray()) }
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
    }

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun noServiceIsShownAndRetriedOnRequest() = runBlocking {
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = McxClient(scope, config("secret"), RefusingIdms(issuer),
            connector = { _, _ -> attempts.incrementAndGet(); throw IOException("connection refused") })
        client.tokens = tokens(3600)
        client.start()
        waitFor("first attempt") { client.status.value.offlineSince != null }
        val status = client.status.value
        assertNotNull(status.nextRetryAt)
        assertTrue(status.nextRetryAt!! >= status.offlineSince!!)
        assertEquals("connection refused", status.lastError)
        // After the second failure the back-off is 4 s: Retry now tries at once.
        waitFor("second attempt") { attempts.get() >= 2 }
        val before = attempts.get()
        client.retryNow()
        waitFor("attempt on Retry now", 2) { attempts.get() > before }
        assertEquals(status.offlineSince, client.status.value.offlineSince) // still the start of the outage
        client.stop()
    }

    @Test
    fun refusedRefreshTokenSignsOutWithoutRetrying() = runBlocking {
        val idms = RefusingIdms(issuer)
        val opened = AtomicInteger()
        val attempts = AtomicInteger()
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = McxClient(scope, config(null, port), idms,
            connector = { _, _ -> attempts.incrementAndGet(); throw IOException("unused") }, browser = { opened.incrementAndGet() })
        client.tokens = tokens(100) // renewed before connecting: less than 10 minutes left
        client.start()
        waitFor("signed out") { client.status.value.signedOut != null }
        assertEquals("signed-out", client.status.value.phase)
        assertNull(client.tokens)
        Thread.sleep(3_000)
        // Nothing tried again, and IdMS's page did not open by itself.
        assertEquals(1, idms.tokenRequests.get())
        assertEquals(0, opened.get())
        assertEquals(0, attempts.get())
        // Sign in: IdMS's page opens, once.
        client.signIn()
        waitFor("IdMS page opened") { opened.get() == 1 }
        assertNull(client.status.value.signedOut)
        client.stop()
    }
}
