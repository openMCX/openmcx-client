//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.documents

import org.junit.Assert.*
import org.junit.Test

class GroupFdPolicyTest {
    @Test fun groupExtensionProvidesFdPolicy() {
        val xml = """<group xmlns="urn:oma:xml:poc:list-service"><list-service uri="sip:group@x">
          <configuration xmlns="urn:openmcx:ns:groupConfiguration:1.0">{"mcdata":{"allow_fd":true,"fd_supported":true,"max_fd_bytes":20000000}}</configuration>
        </list-service></group>"""
        val fd = requireNotNull(GroupDocument.parse(xml).fd)
        assertTrue(fd.allowed)
        assertTrue(fd.supported)
        assertEquals(20_000_000L, fd.maxBytes)
    }

    @Test fun foreignConfigurationDoesNotGrantPermission() {
        val xml = """<group><list-service uri="sip:group@x"><configuration xmlns="urn:foreign">{"mcdata":{"allow_fd":true}}</configuration></list-service></group>"""
        assertNull(GroupDocument.parse(xml).fd)
    }
}
