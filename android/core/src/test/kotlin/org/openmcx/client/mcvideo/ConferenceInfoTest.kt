//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** conference-info documents shaped as the OpenMCX server writes them (protocol.cpp conference_info_xml). */
class ConferenceInfoTest {
    private fun doc(state: String, vararg users: Pair<String, String>) =
        "<?xml version=\"1.0\"?><conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\" " +
            "xmlns:mcpttConfInfo=\"urn:3gpp:ns:mcpttConfInfo:1.0\" entity=\"sip:operations@mcx.test\" version=\"3\" state=\"$state\">" +
            "<conference-description><conf-uris><entry><uri>sips:mcvideo@mcx.test;mcptt-session=abc</uri><purpose>participation</purpose>" +
            "</entry></conf-uris></conference-description><users>" +
            users.joinToString("") { (id, status) -> "<user entity=\"$id\"><endpoint entity=\"$id\"><status>$status</status></endpoint></user>" } +
            "</users></conference-info>"

    @Test fun fullThenPartialUpdates() {
        val full = ConferenceInfo.parse(doc("full", "sip:alice.mcvideo@mcx.test" to "connected", "sip:bob.mcvideo@mcx.test" to "connected"))
        assertEquals("sip:operations@mcx.test", full.group); assertEquals(3L, full.version); assertTrue(full.full)
        var participants = full.applyTo(mapOf("sip:stale@mcx.test" to "connected"))
        assertEquals(listOf("sip:alice.mcvideo@mcx.test", "sip:bob.mcvideo@mcx.test"), participants.keys.toList())
        participants = ConferenceInfo.parse(doc("partial", "sip:client-c.mcvideo@mcx.test" to "connected")).applyTo(participants)
        assertEquals(3, participants.size)
        participants = ConferenceInfo.parse(doc("partial", "sip:bob.mcvideo@mcx.test" to "disconnected")).applyTo(participants)
        assertEquals(listOf("sip:alice.mcvideo@mcx.test", "sip:client-c.mcvideo@mcx.test"), participants.keys.toList())
        val deleted = "<conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\" entity=\"sip:operations@mcx.test\" version=\"4\" state=\"deleted\"/>"
        assertTrue(ConferenceInfo.parse(deleted).applyTo(participants).isEmpty())
    }

    @Test fun aDeletedUserAndTheVersionRule() {
        // RFC 4575 §5.6: state="deleted" removes the user (no endpoint); §4.6: a partial document applies only as the
        // next version, a full one when newer.
        val partial = ConferenceInfo.parse("<conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\" " +
            "entity=\"sip:operations@mcx.test\" version=\"4\" state=\"partial\"><users>" +
            "<user entity=\"sip:bob.mcvideo@mcx.test\" state=\"deleted\"/></users></conference-info>")
        assertEquals(listOf("sip:alice.mcvideo@mcx.test"),
            partial.applyTo(mapOf("sip:alice.mcvideo@mcx.test" to "connected", "sip:bob.mcvideo@mcx.test" to "connected")).keys.toList())
        assertTrue(partial.follows(3L))
        assertTrue(!partial.follows(2L) && !partial.follows(null) && !partial.follows(4L))
        val full = ConferenceInfo.parse(doc("full"))
        assertTrue(full.follows(null) && full.follows(1L) && !full.follows(3L))
    }

    @Test fun otherDocumentsAreRefused() {
        assertTrue(runCatching { ConferenceInfo.parse("<presence xmlns=\"urn:ietf:params:xml:ns:pidf\"/>") }.isFailure)
        assertTrue(runCatching { ConferenceInfo.parse(doc("sideways")) }.isFailure)
    }
}
