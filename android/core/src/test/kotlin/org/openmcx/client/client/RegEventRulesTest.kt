//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.RegInfo
import java.math.BigInteger

/** The reg event subscription and registration timing rules (TS 24.229 §5.1.1.3, §5.1.1.4.1, §5.1.1.5A, §5.1.1.7). */
class RegEventRulesTest {
    @Test
    fun refreshAndReregistrationTimes() {
        // §5.1.1.3 / §5.1.1.4.1: 600 s before the expiry for more than 1200 s, else at half.
        assertEquals(3000, RegEventRules.refreshDelayS(3600, 3600))
        assertEquals(599_400, RegEventRules.refreshDelayS(600_000, 600_000))
        assertEquals(3161, RegEventRules.refreshDelayS(3761, 3761))
        assertEquals(600, RegEventRules.refreshDelayS(1200, 1200))
        assertEquals(60, RegEventRules.refreshDelayS(120, 120))
        // §5.1.1.5A: a 3600 s registration shortened to 120 s is renewed at once (600 s before an expiry 120 s away).
        assertEquals(0, RegEventRules.refreshDelayS(3600, 120))
        // A later NOTIFY's expiry, timed from the initial subscription's duration.
        assertEquals(2400, RegEventRules.refreshDelayS(600_000, 3000))
    }

    @Test
    fun versionOrder() {
        // RFC 3680 §5.2: the first document always; then only a higher version; a gap asks for a refresh.
        assertTrue(RegEventRules.versionNew(null, BigInteger.ZERO))
        assertFalse(RegEventRules.versionNew(BigInteger.TWO, BigInteger.TWO))
        assertFalse(RegEventRules.versionNew(BigInteger.TWO, BigInteger.ONE))
        assertTrue(RegEventRules.versionNew(BigInteger.TWO, BigInteger("3")))
        assertFalse(RegEventRules.versionGap(BigInteger.TWO, BigInteger("3")))
        assertTrue(RegEventRules.versionGap(BigInteger.TWO, BigInteger("4")))
        assertFalse(RegEventRules.versionGap(null, BigInteger("9")))
    }

    @Test
    fun contactAddressAndExpires() {
        assertEquals("alice@abc.invalid", RegEventRules.contactAddress("<sip:alice@abc.invalid;transport=tls;ob>"))
        assertEquals("alice@192.0.2.1:5064", RegEventRules.contactAddress(" sip:alice@192.0.2.1:5064;transport=tcp "))
        assertEquals(3600L, RegEventRules.stateExpires("active;expires=3600"))
        assertEquals(42L, RegEventRules.stateExpires("pending; expires=42;retry-after=1"))
        assertNull(RegEventRules.stateExpires("terminated;reason=noresource"))
        assertNull(RegEventRules.stateExpires(null))
    }

    private fun document(registrationState: String, vararg contacts: String) = RegInfo.parse(
        """<reginfo xmlns="${RegInfo.NAMESPACE}" version="1" state="full"><registration aor="sip:alice@mcx.test" id="r"
            state="$registrationState">${contacts.joinToString("")}</registration></reginfo>""")

    private fun contact(uri: String, state: String, event: String, expires: Long? = null) =
        """<contact id="${uri.hashCode()}" state="$state" event="$event"${expires?.let { " expires=\"$it\"" } ?: ""}><uri>$uri</uri></contact>"""

    @Test
    fun actionForTheOwnContact() {
        val own = "alice@abc.invalid"
        val mine = "sip:alice@abc.invalid;transport=tls;ob"
        val other = "sip:alice@def.invalid;transport=tls;ob"
        val aor = "sip:alice@mcx.test"
        // §5.1.1.5A: shortened, own contact, registration active.
        assertEquals(RegEventRules.Action.Shortened(120),
            RegEventRules.action(document("active", contact(mine, "active", "shortened", 120)), aor, own))
        // Another device's contact, or another identity: nothing.
        assertNull(RegEventRules.action(document("active", contact(other, "active", "shortened", 120)), aor, own))
        assertNull(RegEventRules.action(document("active", contact(mine, "active", "shortened", 120)), "sip:bob@mcx.test", own))
        // §5.1.1.7 items 1 and 2: terminated with unregistered, rejected or deactivated.
        for (event in listOf("unregistered", "rejected", "deactivated")) {
            assertEquals(RegEventRules.Action.Deregistered(event),
                RegEventRules.action(document("terminated", contact(mine, "terminated", event)), aor, own))
            assertEquals(RegEventRules.Action.Deregistered(event),
                RegEventRules.action(document("active", contact(mine, "terminated", event), contact(other, "active", "registered")), aor, own))
        }
        // expired and probation (RFC 3680 §4.7.1) are no deregistration of §5.1.1.7; refreshed is nothing to do.
        assertNull(RegEventRules.action(document("terminated", contact(mine, "terminated", "expired")), aor, own))
        assertNull(RegEventRules.action(document("terminated", contact(mine, "terminated", "probation")), aor, own))
        assertNull(RegEventRules.action(document("active", contact(mine, "active", "refreshed", 3600)), aor, own))
    }
}
