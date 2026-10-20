//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.body

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceTest {
    @Test
    fun affiliationPublishMatchesTheContract() {
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" " +
            "xmlns:mcpttPI10=\"urn:3gpp:ns:mcpttPresInfo:1.0\" entity=\"sip:alice.mcptt@mcx.test\"><tuple id=\"urn:uuid:1\"><status>" +
            "<mcpttPI10:affiliation group=\"sip:operations@mcx.test\"/></status></tuple><mcpttPI10:p-id>p1</mcpttPI10:p-id></presence>",
            Affiliation.publish(McService.MCPTT, "sip:alice.mcptt@mcx.test", "urn:uuid:1", listOf("sip:operations@mcx.test"), "p1"))
    }

    @Test
    fun parsesNotify() {
        val body = "<?xml version=\"1.0\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" xmlns:mc=\"urn:3gpp:ns:mcpttPresInfo:1.0\" " +
            "entity=\"sip:alice.mcptt@mcx.test\"><tuple id=\"urn:uuid:1\"><status><mc:affiliation group=\"sip:operations@mcx.test\" " +
            "status=\"affiliated\"/></status></tuple><mc:p-id>9drX</mc:p-id></presence>"
        assertEquals(mapOf("sip:operations@mcx.test" to "affiliated"), Affiliation.parse(body, "urn:uuid:1"))
        assertEquals("9drX", Affiliation.pId(body))
        assertEquals(emptyMap<String, String>(), Affiliation.parse("<presence xmlns=\"urn:ietf:params:xml:ns:pidf\"><tuple id=\"x\"><status/></tuple></presence>", "urn:uuid:1"))
    }

    @Test
    fun readsOnlyItsOwnTupleAndAnOptionalStatus() {
        // TS 24.379 §9.3.1.2: one tuple per client of the user, the "status" attribute optional.
        val body = "<presence xmlns=\"urn:ietf:params:xml:ns:pidf\" xmlns:mc=\"urn:3gpp:ns:mcpttPresInfo:1.0\" entity=\"sip:a@x\">" +
            "<tuple id=\"urn:uuid:tablet\"><status><mc:affiliation group=\"sip:g1@x\" status=\"affiliated\"/></status></tuple>" +
            "<tuple id=\"urn:uuid:1\"><status><mc:affiliation group=\"sip:g2@x\"/><mc:affiliation group=\"sip:g3@x\" " +
            "status=\"affiliating\"/><mc:affiliation group=\"sip:g4@x\" status=\"deaffiliating\"/></status></tuple></presence>"
        val ours = Affiliation.parse(body, "urn:uuid:1")
        assertEquals(mapOf("sip:g2@x" to "affiliated", "sip:g3@x" to "affiliating", "sip:g4@x" to "deaffiliating"), ours)
        assertEquals(emptyMap<String, String>(), Affiliation.parse(body, "urn:uuid:laptop"))
        assertEquals(listOf(true, true, false, false), listOf("sip:g2@x", "sip:g3@x", "sip:g4@x", "sip:g1@x").map { Affiliation.counts(ours[it]) })
    }

    @Test
    fun pocSettings() {
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><poc-settings xmlns=\"urn:oma:params:xml:ns:poc:poc-settings\" " +
            "xmlns:mcs10Set=\"urn:3gpp:mcsSettings:1.0\"><entity id=\"urn:uuid:1\"><am-settings><answer-mode>automatic</answer-mode>" +
            "</am-settings><mcs10Set:selected-user-profile-index><mcs10Set:user-profile-index>0</mcs10Set:user-profile-index>" +
            "</mcs10Set:selected-user-profile-index><mcs10Set:multiplex-support>false</mcs10Set:multiplex-support></entity></poc-settings>",
            PocSettings.encode("urn:uuid:1", "automatic"))
    }
}
