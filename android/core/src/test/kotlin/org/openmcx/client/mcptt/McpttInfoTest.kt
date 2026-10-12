//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpttInfoTest {
    @Test
    fun privateRequestMatchesTheContract() {
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params>" +
            "<session-type>private</session-type><mcptt-client-id type=\"Normal\"><mcpttString>urn:uuid:1</mcpttString>" +
            "</mcptt-client-id></mcptt-Params></mcpttinfo>",
            McpttInfo(sessionType = "private", clientId = "urn:uuid:1").encode())
    }

    @Test
    fun parsesTheServersForm() {
        val info = McpttInfo.parse("<?xml version=\"1.0\"?><mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params>" +
            "<session-type>adhoc</session-type><mcptt-request-uri type=\"Normal\"><mcpttURI>sip:alice.mcptt@mcx.test</mcpttURI>" +
            "</mcptt-request-uri><mcptt-calling-user-id type=\"Normal\"><mcpttURI>sip:alice.mcptt@mcx.test</mcpttURI>" +
            "</mcptt-calling-user-id><mcptt-calling-group-id type=\"Normal\"><mcpttURI>sip:adhoc-x@mcx.test</mcpttURI>" +
            "</mcptt-calling-group-id><floor-state>floor-idle</floor-state></mcptt-Params></mcpttinfo>")
        assertEquals("adhoc", info.sessionType)
        assertEquals("sip:adhoc-x@mcx.test", info.callingGroupId)
        assertEquals("floor-idle", info.extra["floor-state"])
    }

    @Test
    fun emergencyAndImminentPerilIndications() {
        val emergency = McpttInfo(sessionType = "prearranged", requestUri = "sip:operations@mcx.test", emergency = true, alert = false).encode()
        // §6.2.8.1.1: an emergency call states that it is not an alert, in schema order.
        assertTrue(emergency, "<emergency-ind type=\"Normal\"><mcpttBoolean>true</mcpttBoolean></emergency-ind>" +
            "<alert-ind type=\"Normal\"><mcpttBoolean>false</mcpttBoolean></alert-ind>" in emergency)
        val peril = McpttInfo(sessionType = "prearranged", imminentPeril = true).encode()
        assertTrue(peril, "<imminentperil-ind type=\"Normal\"><mcpttBoolean>true</mcpttBoolean></imminentperil-ind>" in peril)
        assertEquals(true, McpttInfo.parse(peril).imminentPeril)
        assertEquals(false, McpttInfo.parse(emergency).alert)
    }
}
