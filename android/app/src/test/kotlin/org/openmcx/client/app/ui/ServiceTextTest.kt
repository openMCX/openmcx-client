//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmcx.client.mcptt.PrivateServiceRules
import org.openmcx.client.mcptt.ServiceRecord

/** How the Call tab tells of the private call services (TS 24.379 §11.1.5 to §11.1.9, §10.1.5). */
class ServiceTextTest {
    private val bob = "sip:bob.mcptt@mcx.test"
    private val carol = "sip:client-c.mcptt@mcx.test"

    @Test
    fun requestsReceived() {
        assertEquals("Bob asks you to call back", serviceText(ServiceRecord(PrivateServiceRules.CALL_BACK, bob, null)))
        assertEquals("Bob asked this phone to call Client-c: done",
            serviceText(ServiceRecord(PrivateServiceRules.REMOTE_PRIVATE, bob, carol, "success")))
        assertEquals("Bob transferred you to Client-c: failed", serviceText(ServiceRecord(PrivateServiceRules.TRANSFER, bob, carol, "fail")))
        assertEquals("Call forwarded by Client-c to Bob", serviceText(ServiceRecord(PrivateServiceRules.FORWARD, carol, bob)))
    }

    @Test
    fun outcomesOfTheUsersOwnRequests() {
        assertEquals("Transfer of Bob to Client-c: done", serviceText(ServiceRecord("transfer-private-call-response", bob, carol, "success")))
        assertEquals("Bob's call to Client-c: failed",
            serviceText(ServiceRecord("remotely-initiated-private-call-response", bob, carol, "fail")))
    }
}
