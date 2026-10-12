//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** How calls name their parties with functional aliases (TS 24.379 §9A): the role first, the user behind it second. */
class CallDisplayTest {
    private val alice = "sip:alice.mcptt@mcx.test"
    private val duty = "sip:duty-officer.alias@mcx.test"
    private val patrol = "sip:patrol-car-12.alias@mcx.test"

    @Test
    fun aRoleReadsAsWords() {
        assertEquals("Duty officer", CallDisplay.role(duty))
        assertEquals("Patrol car 12", CallDisplay.role(patrol))
        assertEquals("Desk", CallDisplay.role("sip:desk.fa@mcx.test"))
        assertEquals("Dispatcher", CallDisplay.role("sip:dispatcher.alias@mcx.test"))
    }

    @Test
    fun anIncomingCallToARoleThisUserHolds() {
        assertEquals(CallDisplay.Shown("Call to Duty officer", "from Alice"), CallDisplay.privateCall(false, alice, null, duty))
        assertEquals(CallDisplay.Shown("Call to Duty officer", "from Patrol car 12 (Alice)"), CallDisplay.privateCall(false, alice, patrol, duty))
    }

    @Test
    fun anIncomingCallFromACallerPresentingARole() {
        assertEquals(CallDisplay.Shown("Patrol car 12 (Alice)", null), CallDisplay.privateCall(false, alice, patrol, null))
        assertEquals(CallDisplay.Shown("Alice", null), CallDisplay.privateCall(false, alice, null, null))
    }

    @Test
    fun anOutgoingCall() {
        assertEquals(CallDisplay.Shown("Alice", "as Duty officer"), CallDisplay.privateCall(true, alice, duty, null))
        // A call to a role: the role, and the user the server selected once it is known.
        assertEquals(CallDisplay.Shown("Duty officer", null), CallDisplay.privateCall(true, duty, null, duty))
        assertEquals(CallDisplay.Shown("Duty officer", "reached Alice · as Patrol car 12"), CallDisplay.privateCall(true, alice, patrol, duty))
    }

    @Test
    fun aGroupCallAndTheNotification() {
        assertEquals(CallDisplay.Shown("operations", "from Patrol car 12"), CallDisplay.groupCall("operations", false, patrol))
        assertEquals(CallDisplay.Shown("operations", null), CallDisplay.groupCall("operations", true, null))
        assertEquals("Call to Duty officer from Alice", CallDisplay.caller(alice, null, duty, "MCPTT"))
        assertEquals("Patrol car 12 (Alice)", CallDisplay.caller(alice, patrol, null, "MCPTT"))
        assertEquals("MCPTT", CallDisplay.caller(null, null, null, "MCPTT"))
    }
}
