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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failed key management or service authorisation of one MC service (TS 33.180 clause 5.1.1 steps C-2/C-3, TS 24.379
 * clause 7.2.2): that service is not authorised and tried again; the registration and the other services stay.
 */
class ServiceRefusalTest {
    private fun config(security: Boolean) = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8902/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20},
        "mcvideo": {"user_id": "sip:alice.mcvideo@mcx.test", "psi": "sip:mcvideo@mcx.test", "groups": []},""" +
        (if (security) """"security": {"kms_uri": "https://kms", "key_parameter_profile": {}},""" else "") + """
        "recovery": {"first_retry_ms": 300, "last_retry_ms": 600},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    /** Every HTTP request refused; the KMS's counted. */
    private class Http : HttpClient {
        val kms = AtomicInteger()
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (url.startsWith("https://kms")) kms.incrementAndGet()
            return HttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private fun tokens() = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
        mcpttId = "sip:alice.mcptt@mcx.test", mcdataId = null, mcvideoId = "sip:alice.mcvideo@mcx.test", sipUri = null,
        clientId = null, refreshToken = "RT", nonce = "n")

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    private fun authorisation(request: SipRequest, service: String) =
        request.method == "PUBLISH" && request.headers["Event"] == "poc-settings" && request.uri == "sip:$service@mcx.test"

    private fun start(security: Boolean, http: HttpClient, cores: MutableList<FakeSipCore>,
                      statusFor: (SipRequest) -> Int? = { null }): McxClient {
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(security), http,
            connector = { _, _ -> FakeSipCore(statusFor = statusFor).also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false), "mcvideo" to (false to false))
        client.start()
        return client
    }

    private fun registers(cores: List<FakeSipCore>) = cores.sumOf { core -> core.requests.count { it.method == "REGISTER" && it.headers["Expires"] != "0" } }

    /** The KMS exchange of MCVideo fails: MCPTT is authorised on the one registration, MCVideo refused and retried. */
    @Test
    fun keyManagementFailureRefusesOnlyThatService() = runBlocking {
        val http = Http()
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(true, http, cores)
        waitFor("MCVideo refused") { client.status.value.mcvideo.refused != null && client.status.value.mcptt.authorised }
        val status = client.status.value
        assertEquals("ready", status.phase)
        assertTrue(status.registered)
        assertFalse(status.mcvideo.authorised)
        assertTrue(status.mcvideo.refused!!, status.mcvideo.refused!!.startsWith("MCVIDEO not authorised"))
        assertEquals(status.mcvideo.refused, status.lastError)
        val first = http.kms.get()
        waitFor("MCVideo key management tried again") { http.kms.get() > first }
        assertEquals(1, cores.size)
        assertEquals(1, registers(cores))
        assertTrue(client.status.value.mcptt.authorised)
        assertEquals(0, cores[0].requests.count { authorisation(it, "mcvideo") })
        client.stop()
    }

    /** MCVideo's authorisation PUBLISH gets 486 twice (§7.3.3, another client): retried alone, then authorised. */
    @Test
    fun refusedAuthorisationIsRetriedOnTheSameRegistration() = runBlocking {
        val refusals = AtomicInteger()
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(false, Http(), cores) { request ->
            if (authorisation(request, "mcvideo") && refusals.getAndIncrement() < 2) 486 else null
        }
        waitFor("MCVideo authorised after the retries") { client.status.value.mcvideo.authorised }
        val status = client.status.value
        assertTrue(status.mcptt.authorised)
        assertNull(status.mcvideo.refused)
        assertNull(status.mcvideo.nextRetryAt)
        assertNull(status.lastError)
        assertEquals("ready", status.phase)
        assertEquals(3, cores[0].requests.count { authorisation(it, "mcvideo") })
        assertEquals(1, cores[0].requests.count { authorisation(it, "mcptt") })
        assertEquals(1, cores.size)
        assertEquals(1, registers(cores))
        client.stop()
    }

    /** Every service refused: registered, the limited service state of TS 33.180 clause 5.1.1, no new registration. */
    @Test
    fun everyServiceRefusedKeepsTheRegistration() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(false, Http(), cores) { request ->
            if (request.method == "PUBLISH" && request.headers["Event"] == "poc-settings") 403 else null
        }
        waitFor("limited service") { client.status.value.limitedService && client.status.value.mcvideo.refused != null }
        assertEquals("limited-service", client.status.value.phase)
        waitFor("both retried") { cores[0].requests.count { authorisation(it, "mcptt") } >= 3 && cores[0].requests.count { authorisation(it, "mcvideo") } >= 3 }
        assertTrue(client.status.value.registered)
        assertEquals(1, cores.size)
        assertEquals(1, registers(cores))
        client.stop()
    }

    /** A lost flow ends the services' retries: the new start-up brings every service up again. */
    @Test
    fun lostFlowEndsTheRetries() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(false, Http(), cores) { request -> if (authorisation(request, "mcvideo")) 403 else null }
        waitFor("MCVideo retried") { cores.isNotEmpty() && cores[0].requests.count { authorisation(it, "mcvideo") } >= 2 }
        client.reconnect("test")
        waitFor("second start-up", 15) { cores.size == 2 && cores[1].requests.any { authorisation(it, "mcvideo") } }
        val old = cores[0].requests.count { authorisation(it, "mcvideo") }
        Thread.sleep(1_500)
        assertEquals(old, cores[0].requests.count { authorisation(it, "mcvideo") })
        client.stop()
    }
}
