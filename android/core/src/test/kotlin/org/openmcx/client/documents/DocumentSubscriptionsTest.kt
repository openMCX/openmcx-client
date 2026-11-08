//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openmcx.client.body.ResourceLists

class DocumentSubscriptionsTest {
    /** TS 24.484 §6.3.13.2.2 b) 1): the three documents, relative paths, ':' and '@' unencoded (RFC 3986 §3.3). */
    @Test fun configurationEntries() {
        assertEquals(listOf(
            "org.3gpp.mcptt.ue-config/users/sip:alice.mcptt@mcx.test/urn:uuid:1",
            "org.3gpp.mcptt.user-profile/users/sip:alice.mcptt@mcx.test/mcptt-user-profile-2.xml",
            "org.3gpp.mcptt.service-config/global/service-config.xml"),
            DocumentSubscriptions.configurationEntries("sip:alice.mcptt@mcx.test", "urn:uuid:1", 2))
    }

    /** TS 24.481 §6.3.13.2.1 a) 1): one entry per group document; a space is percent-encoded. */
    @Test fun groupEntriesAndTheirResourceList() {
        val entries = DocumentSubscriptions.groupEntries(listOf("sip:operations@mcx.test", "sip:a b@mcx.test"))
        assertEquals(listOf("org.openmobilealliance.groups/global/byGroupID/sip:operations@mcx.test",
            "org.openmobilealliance.groups/global/byGroupID/sip:a%20b@mcx.test"), entries)
        assertEquals(entries, ResourceLists.parse(DocumentSubscriptions.resourceList(entries)))
    }

    /** RFC 5874 §4: the changed documents, a removed one without new-etag; the group back from its selector. */
    @Test fun xcapDiff() {
        val changed = DocumentSubscriptions.parse("""<?xml version="1.0"?><xcap-diff xmlns="urn:ietf:params:xml:ns:xcap-diff"
            xcap-root="https://gms/"><document new-etag="e2" sel="org.openmobilealliance.groups/global/byGroupID/sip:operations%40mcx.test"/>
            <document previous-etag="e1" sel="org.openmobilealliance.groups/global/byGroupID/sip:old@mcx.test"/></xcap-diff>""")
        assertEquals(2, changed.size)
        assertEquals("e2", changed[0].newEtag)
        assertNull(changed[1].newEtag)
        assertEquals("sip:operations@mcx.test", DocumentSubscriptions.groupOf(changed[0].selector))
        assertEquals("sip:old@mcx.test", DocumentSubscriptions.groupOf("https://gms/" + changed[1].selector))
        assertNull(DocumentSubscriptions.groupOf("org.3gpp.mcptt.user-profile/users/x/mcptt-user-profile-1.xml"))
    }
}
