//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The RFC 3840 media feature tags of the REGISTER Contact (TS 24.229 §5.1.1.2.1 c): "for all supported streaming media
 * types"): "audio" and "application" (the floor control stream, m=application) for MCPTT, "video" with MCVideo; never
 * "control" or "text".
 */
class MediaFeatureTagsTest {
    private fun config(mcvideo: Boolean) = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8901/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20},""" +
        (if (mcvideo) """"mcvideo": {"user_id": "sip:alice.mcvideo@mcx.test", "psi": "sip:mcvideo@mcx.test", "groups": []},""" else "") +
        """"media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val http = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
    }

    /** The parameters (names, lower case) after the URI of the client's first REGISTER Contact. */
    private fun registerTags(mcvideo: Boolean): List<String> {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(mcvideo), http,
            connector = { _, _ -> FakeSipCore().also { cores += it } })
        client.tokens = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
            mcpttId = "sip:alice.mcptt@mcx.test", mcdataId = null, mcvideoId = null, sipUri = null, clientId = null,
            refreshToken = "RT", nonce = "n")
        client.start()
        val end = System.currentTimeMillis() + 10_000
        while (cores.none { core -> core.requests.any { it.method == "REGISTER" } }) {
            if (System.currentTimeMillis() > end) throw AssertionError("no REGISTER")
            Thread.sleep(50)
        }
        val contact = cores.first { core -> core.requests.any { it.method == "REGISTER" } }
            .requests.first { it.method == "REGISTER" }.headers["Contact"]!!
        return contact.substringAfter('>').split(';').filter { it.isNotBlank() }.map { it.substringBefore('=').trim().lowercase() }
    }

    @Test
    fun mcpttRegistersAudioAndApplication() {
        val tags = registerTags(mcvideo = false)
        assertTrue(tags.toString(), "audio" in tags && "application" in tags)
        assertFalse(tags.toString(), "video" in tags || "control" in tags || "text" in tags)
        assertEquals(1, tags.count { it == "application" })
    }

    @Test
    fun mcvideoAddsVideo() {
        val tags = registerTags(mcvideo = true)
        assertTrue(tags.toString(), listOf("audio", "video", "application").all { it in tags })
        assertFalse(tags.toString(), "control" in tags)
    }
}
