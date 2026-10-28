//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// RFC 7714 §8.4: an SSRC this client has issued is never given out again (the CSK and GMKs are shared master keys).
class McvideoSsrcTest {
    @Test fun issuedSsrcsAreNeverRepeated() {
        val issued = List(20000) { McvideoSdp.newSsrc() }
        assertEquals(issued.size, issued.toSet().size)
        issued.forEach { assertNotEquals(0L, it) }
    }
}
