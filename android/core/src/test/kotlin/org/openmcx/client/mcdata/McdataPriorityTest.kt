//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MCData priority states of TS 24.282 §6.2.8 (annex G.4), as the Linux client keeps them. */
class McdataPriorityTest {
    private val group = "sip:operations@mcx.test"
    private val alice = "sip:alice.mcdata@mcx.test"
    private val bob = "sip:bob.mcdata@mcx.test"

    @Test fun anEmergencyGroupRequestGrantedSetsTheGroupInProgress() {
        val p = McdataPriority()
        // §6.2.8.1.1: MDEGC 2, MDEG 4, with an alert MDEA 2.
        p.requested(true, group, emergency = true, imminent = false, alert = true)
        assertEquals(McdataPriority.States(mdeg = 4, mdegc = 2, mdea = 2, group = group), p.states)
        // §6.2.8.1.4 1): MDEG 2, MDEGC 3; the alert initiated.
        p.answered(200, null)
        assertEquals(2, p.states.mdeg); assertEquals(3, p.states.mdegc); assertEquals(3, p.states.mdea)
        // §9.2.4.2.3 8)e): the next plain communication on the group has the emergency priority, another group none.
        assertEquals("emergency", p.level(true, false, group, false, false))
        assertNull(p.level(true, false, "sip:other@mcx.test", false, false))
        assertNull(p.level(false, false, bob, false, false))
        // §6.2.8.1.17 with the Linux client: the release keeps the in-progress state, the communication state capable.
        p.released()
        assertEquals(2, p.states.mdeg); assertEquals(1, p.states.mdegc)
    }

    @Test fun aRefusedEmergencyRequestReturnsTheStates() {
        val p = McdataPriority()
        p.requested(true, group, emergency = true, imminent = false, alert = true)
        // §6.2.8.1.5: MDEGC 1, MDEG 4 -> 1, MDEA 2 -> 1.
        p.answered(403, null)
        assertEquals(1, p.states.mdeg); assertEquals(1, p.states.mdegc); assertEquals(1, p.states.mdea)
        assertNull(p.level(true, false, group, false, false))
    }

    @Test fun anImminentPerilRequestWarned149WaitsForTheInfo() {
        val p = McdataPriority()
        p.requested(true, group, emergency = false, imminent = true, alert = false)
        assertEquals(4, p.states.mdig)
        p.answered(200, "399 mcdata-server \"149 SIP INFO request pending\"")
        // §6.2.8.1.4 2): not granted while the SIP INFO is pending.
        assertEquals(2, p.states.mdigc)
        // §6.2.8.1.13 3): the INFO carries it at the emergency level.
        p.info(McdataInfo(emergency = true, imminentPeril = false))
        assertEquals(1, p.states.mdig); assertEquals(1, p.states.mdigc); assertEquals(2, p.states.mdeg)
        assertEquals("emergency", p.level(true, false, group, false, false))
    }

    @Test fun anImminentPerilGrantedGivesTheImminentPriority() {
        val p = McdataPriority()
        p.requested(true, group, emergency = false, imminent = true, alert = false)
        p.answered(200, null)
        assertEquals(2, p.states.mdig); assertEquals(3, p.states.mdigc)
        assertEquals("imminent", p.level(true, false, group, false, false))
    }

    @Test fun aRequestReleasedWhileBeingEstablished() {
        // §6.2.8.1.17 1): MDEGC 2 -> 1, MDEG 4 -> 1 (the clause's "MDEG 3: confirm-pending"), MDEA 2 -> 1; 2): MDIG 4 -> 1.
        val p = McdataPriority()
        p.requested(true, group, emergency = true, imminent = false, alert = true)
        p.released()
        assertEquals(McdataPriority.States(group = group), p.states)
        p.requested(true, group, emergency = false, imminent = true, alert = false)
        p.released()
        assertEquals(1, p.states.mdig); assertEquals(1, p.states.mdigc)
        // §6.2.8.1.18 1): MDEPC 2 -> 1, MDEPP 3 -> 1.
        p.requested(false, bob, emergency = true, imminent = false, alert = false)
        assertEquals(2, p.states.mdepc); assertEquals(3, p.states.mdepp)
        p.released()
        assertEquals(1, p.states.mdepc); assertEquals(1, p.states.mdepp)
    }

    @Test fun thePreEstablishedGrant() {
        // §6.2.8.1.16 1)a): granted; b) <alert-ind> false: MDEA 1.
        val p = McdataPriority()
        p.requested(true, group, emergency = true, imminent = false, alert = true)
        p.preGranted(true, emergency = true, imminent = null, alert = false)
        assertEquals(2, p.states.mdeg); assertEquals(3, p.states.mdegc); assertEquals(1, p.states.mdea)
        // 2)b): an imminent peril request on a group in an emergency: raised to the emergency level, the request denied.
        val q = McdataPriority()
        q.requested(true, group, emergency = false, imminent = true, alert = false)
        q.preGranted(true, emergency = true, imminent = false, alert = null)
        assertEquals(2, q.states.mdeg); assertEquals(1, q.states.mdigc); assertEquals(1, q.states.mdig)
        assertEquals("emergency", q.level(true, false, group, false, false))
        // One-to-one (§9.2.5.2.1.1 3)a)): MDEPP 2, MDEPC 3.
        val r = McdataPriority()
        r.requested(false, bob, emergency = true, imminent = false, alert = false)
        r.preGranted(false, null, null, null)
        assertEquals(3, r.states.mdepc); assertEquals(2, r.states.mdepp)
    }

    @Test fun theStatesAReceivedRequestCarries() {
        val p = McdataPriority()
        // §9.2.4.2.4 4A): another user's emergency with an alert on the group.
        p.groupReceived(McdataInfo(callingGroupId = group, callingUserId = bob, emergency = true, alert = true), alice)
        assertEquals(2, p.states.mdeg); assertEquals(bob, p.states.groupAlertOriginator); assertEquals(group, p.states.group)
        // §6.2.7.4: the emergency ended.
        p.groupReceived(McdataInfo(callingGroupId = group, callingUserId = bob, emergency = false), alice)
        assertEquals(1, p.states.mdeg); assertNull(p.states.groupAlertOriginator)
        // §6.2.8.4.2: the peer upgraded a one-to-one communication, then cancelled it.
        p.privateReceived(true, null, bob); assertEquals(2, p.states.mdepp)
        p.privateReceived(false, null, bob); assertEquals(1, p.states.mdepp); assertEquals(1, p.states.mdepc)
    }

    @Test fun theUsersGroupPriorityChanges() {
        val p = McdataPriority()
        p.requested(true, group, emergency = true, imminent = false, alert = false); p.answered(200, null)
        // §6.2.8.1.3: the cancel: MDEG 3; granted MDEG 1.
        assertEquals(false to null, p.groupChange(emergency = true, enable = false, cancelAlert = false))
        assertEquals(3, p.states.mdeg)
        p.groupChangeAnswered(true, false, 200, null, null)
        assertEquals(1, p.states.mdeg)
        // Not in an emergency state any more: a second cancel is refused locally.
        assertTrue(runCatching { p.groupChange(emergency = true, enable = false, cancelAlert = false) }.exceptionOrNull() is SdsError)
        // §6.2.8.1.1 upgrade then §6.2.7.2 a refused cancel: MDEG 2 again.
        p.groupChange(emergency = true, enable = true, cancelAlert = false); p.groupChangeAnswered(true, true, 200, null, null)
        p.groupChange(emergency = true, enable = false, cancelAlert = false); p.groupChangeAnswered(true, false, 403, null, McdataInfo(emergency = true))
        assertEquals(2, p.states.mdeg)
        // §6.2.7.2 3) -> §6.2.8.1.14: another user's emergency and alert cancelled by this user names that user.
        val q = McdataPriority()
        q.groupReceived(McdataInfo(callingGroupId = group, callingUserId = bob, emergency = true, alert = true), alice)
        assertEquals(false to bob, q.groupChange(emergency = true, enable = false, cancelAlert = true))
    }

    @Test fun theUsersPrivatePriorityChanges() {
        val p = McdataPriority()
        p.requested(false, bob, emergency = true, imminent = false, alert = false); p.answered(200, null)
        assertEquals(3, p.states.mdepc)
        p.privateChange(false); assertEquals(3, p.states.mdepp)
        p.privateChangeAnswered(false, true, true)
        assertEquals(1, p.states.mdepc); assertEquals(1, p.states.mdepp)
        p.privateChange(true); p.privateChangeAnswered(true, false, false)
        assertEquals(1, p.states.mdepc) // §6.2.8.3.5
    }
}
