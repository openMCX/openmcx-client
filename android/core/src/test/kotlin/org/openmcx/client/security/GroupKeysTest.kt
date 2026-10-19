//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS 33.180 5.7 and E.6.4: the GMK a group uses, by Activation Time. */
class GroupKeysTest {
    private var now = 1_800_000_000L
    private val keys = GroupKeys { now }
    private val group = "sip:operations@mcx.test"

    private fun gmk(id: Long, activation: Long, expiry: Long = 0, sentAt: Long = now) =
        KeyMessage(ByteArray(16), ByteArray(16), id, sentAt + Mikey.NTP_EPOCH, KeyProperties(0, activation = activation, expiry = expiry, groups = listOf(group)))

    @Test fun aKeyAheadOfTheClockIsKeptAndUsedFromItsActivationTime() {
        // The GMS stamped it a little ahead of this clock (a phone 2 s behind the host).
        keys.add(group, gmk(1, activation = now + 2))
        assertNull(keys.active(group))
        assertTrue(keys.held(group))
        now += 2
        assertEquals(1L, keys.active(group)?.csbId)
    }

    @Test fun theMostRecentActiveKeyIsUsedAndTheNextWaits() {
        keys.add(group, gmk(1, activation = now - 100))
        keys.add(group, gmk(2, activation = now + 3600)) // sent ahead of a key change
        assertEquals(1L, keys.active(group)?.csbId)
        assertEquals(mapOf(group to 1L), keys.ids)
        now += 3600
        assertEquals(2L, keys.active(group)?.csbId)
    }

    @Test fun activationZeroMeansTheMessageTimeAndExpiredKeysGo() {
        keys.add(group, gmk(1, activation = 0, expiry = now + 10, sentAt = now - 5))
        assertEquals(1L, keys.active(group)?.csbId)
        now += 10
        assertNull(keys.active(group))
        assertTrue(!keys.held(group))
    }
}
