//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.client.ClientConfig
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import java.util.UUID

/** Enhanced status (TS 24.282 §14.2.1) with the operational values of a group document (TS 24.481 §7.2.4.2). */
class EnhancedStatusTest {
    private val group = "sip:operations@mcx.test"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun document(allowed: Boolean) = """<?xml version="1.0"?>
        <resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list-service xmlns="urn:oma:xml:poc:list-service" uri="$group">
        <mcpttgi:mcdata-allow-enhanced-status xmlns:mcpttgi="urn:3gpp:ns:mcpttGroupInfo:1.0">$allowed</mcpttgi:mcdata-allow-enhanced-status>
        <mcpttgi:mcdata-enhanced-status-operational-values xmlns:mcpttgi="urn:3gpp:ns:mcpttGroupInfo:1.0">
          <mcpttgi:status><mcpttgi:id>1</mcpttgi:id><mcpttgi:shortText><mcpttgi:langType>en</mcpttgi:langType><mcpttgi:langText>On scene</mcpttgi:langText></mcpttgi:shortText></mcpttgi:status>
          <mcpttgi:status><mcpttgi:id>2</mcpttgi:id><mcpttgi:shortText><mcpttgi:langText>En route</mcpttgi:langText></mcpttgi:shortText></mcpttgi:status>
        </mcpttgi:mcdata-enhanced-status-operational-values></list-service></resource-lists>"""

    private fun sds(): McdataService {
        val config = ClientConfig.parse("""{
            "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
            "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                    "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
            "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
            "cms": "https://cms", "gms": "https://gms",
            "mcdata": {"user_id": "sip:alice.mcdata@mcx.test", "psi": "sip:mcdata@mcx.test", "groups": ["$group"]},
            "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")
        val http = object : HttpClient {
            override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
                HttpResponse(404, emptyMap(), ByteArray(0))
        }
        return McdataService(McxClient(scope, config, http, connector = { _, _ -> throw IllegalStateException("offline") }))
    }

    private fun signalling() = SdsSignalling(1_700_000_000, UUID.randomUUID(), UUID.randomUUID())
    private fun status(id: String) = DataPayload(listOf(Payload(PayloadType.ENHANCED_STATUS, id.toByteArray())))
    private val from = McdataInfo(callingUserId = "sip:bob.mcdata@mcx.test", callingGroupId = group)

    @Test fun operationalValues() {
        val values = EnhancedStatusValues.parse(document(true))
        assertTrue(values.allowed)
        assertEquals(listOf("1" to "On scene", "2" to "En route"), values.values)
        assertFalse(EnhancedStatusValues.parse(document(false)).allowed)
    }

    @Test fun receivedValueRenderedOrDiscarded() = runBlocking {
        val sds = sds()
        val es = EnhancedStatusService(sds) { document(true) }
        // §14.2.1.2 step 3: a value of the group is rendered and the message delivered.
        sds.received(from, signalling(), status("2"))
        assertEquals("En route", es.received.value.single().value)
        assertEquals(1, sds.inbox.value.size)
        // One the group does not know is discarded.
        sds.received(from, signalling(), status("9"))
        assertEquals(1, es.discarded)
        assertEquals(1, sds.inbox.value.size)
        // Text messages are not concerned.
        sds.received(from, signalling(), DataPayload(listOf(Payload.text("hello"))))
        assertEquals(2, sds.inbox.value.size)
    }

    @Test fun sendNeedsTheGroupsPermissionAndAKnownValue() = runBlocking {
        val sds = sds()
        // §14.2.1.1: only with <mcdata-allow-enhanced-status> "true", and only an id of the group's values.
        assertTrue(runCatching { EnhancedStatusService(sds) { document(false) }.send(group, "1") }.exceptionOrNull() is SdsError)
        val es = EnhancedStatusService(sds) { document(true) }
        val unknown = runCatching { es.send(group, "7") }.exceptionOrNull()
        assertTrue(unknown is SdsError && unknown.message!!.contains("no operational value"))
    }
}
