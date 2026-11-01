//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** RFC 3680 §5: the registration information of the reg event package (TS 24.229 §5.1.1.5A and §5.1.1.7). */
class RegInfoTest {
    private val ns = "urn:ietf:params:xml:ns:reginfo"

    @Test
    fun registrationAndContacts() {
        val info = RegInfo.parse("""<?xml version="1.0"?><reginfo xmlns="$ns" xmlns:x="urn:example" version="2" state="full">
            <registration aor="sip:alice@mcx.test" id="a1" state="active">
            <contact id="c1" state="active" event="shortened" expires="60" x:other="1">
            <uri> sip:alice@192.0.2.1:5064;transport=tcp </uri><x:extension/></contact>
            <contact id="c2" state="terminated" event="deactivated"><uri>sip:alice@192.0.2.2</uri></contact>
            </registration><x:other/></reginfo>""")
        assertEquals(BigInteger.TWO, info.version)
        assertTrue(info.full)
        val registration = info.registrations.single()
        assertEquals("sip:alice@mcx.test", registration.aor)
        assertEquals("active", registration.state)
        assertEquals(RegInfo.Contact("sip:alice@192.0.2.1:5064;transport=tcp", "active", "shortened", 60), registration.contacts[0])
        assertNull(registration.contacts[1].expires)
        assertEquals("deactivated", registration.contacts[1].event)
    }

    @Test
    fun partialStateAndNumbers() {
        assertFalse(RegInfo.parse("""<reginfo xmlns="$ns" version="0" state="partial"/>""").full)
        // §5.4: version is an xs:nonNegativeInteger, expires an xs:unsignedLong: beyond 32 bits both stay valid.
        val big = RegInfo.parse("""<reginfo xmlns="$ns" version="18446744073709551616" state="full">
            <registration aor="sip:a@x" id="r" state="active"><contact id="c" state="active" event="registered"
            expires="18446744073709551615"><uri>sip:a@h</uri></contact></registration></reginfo>""")
        assertEquals(BigInteger("18446744073709551616"), big.version)
        assertEquals(Long.MAX_VALUE, big.registrations[0].contacts[0].expires)
    }

    @Test
    fun refusesWhatIsNotAReginfoDocument() {
        assertThrows(IllegalArgumentException::class.java) { RegInfo.parse("""<reginfo xmlns="$ns" state="full"/>""") }
        assertThrows(IllegalArgumentException::class.java) { RegInfo.parse("""<reginfo xmlns="urn:other" version="0" state="full"/>""") }
        assertThrows(IllegalArgumentException::class.java) { RegInfo.parse("""<reginfo xmlns="$ns" version="-1" state="full"/>""") }
        assertEquals(BigInteger("7"), RegInfo.parse("""<reginfo xmlns="$ns" version=" +007 " state="full"/>""").version)
        assertEquals(BigInteger.ZERO, RegInfo.parse("""<reginfo xmlns="$ns" version="-0" state="full"/>""").version)
        // A contact without its <uri>, and an expires past xs:unsignedLong.
        assertThrows(IllegalArgumentException::class.java) {
            RegInfo.parse("""<reginfo xmlns="$ns" version="0" state="full"><registration aor="sip:a@x" id="r" state="active">
                <contact id="c" state="active" event="registered"/></registration></reginfo>""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegInfo.parse("""<reginfo xmlns="$ns" version="0" state="full"><registration aor="sip:a@x" id="r" state="active">
                <contact id="c" state="active" event="registered" expires="18446744073709551616"><uri>sip:a@h</uri></contact>
                </registration></reginfo>""")
        }
    }
}
