//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.mcvideo.McvideoInfo

/**
 * End-to-end protected ad hoc group calls (TS 24.379 §17.1, §17.2.2.1.1 step 10 c) iii) and 2xx step 3, §17.2.2.1.2
 * step 7; TS 24.281 §22.2.2.1.1, §22.2.2.1.2 step 8; TS 33.180 §5.7, §7.3): the request, the keying by the
 * <preconfigured-group-id>, the senders and the GMK subscriptions; the elements in the mcptt-info and mcvideo-info
 * bodies as the OpenMCX server and the Linux client write them.
 */
class AdhocSecurityTest {
    private val security = "sip:adhoc-security@mcx.test"

    @Test fun `a client that protects media requests end-to-end security, else leaves the element out`() {
        assertEquals(true, AdhocSecurity.requested(true))
        assertNull(AdhocSecurity.requested(false))
    }

    @Test fun `protected media are keyed with the named preconfigured group, never without one`() {
        assertEquals(AdhocSecurity.Keying.Group(security), AdhocSecurity.keying(true, " $security "))
        assertTrue(AdhocSecurity.keying(true, null) is AdhocSecurity.Keying.Refused)
        assertTrue(AdhocSecurity.keying(true, "") is AdhocSecurity.Keying.Refused)
        assertTrue(AdhocSecurity.keying(true, "tel:+1") is AdhocSecurity.Keying.Refused)
        // Clear media stay clear, whatever the element says.
        assertEquals(AdhocSecurity.Keying.Clear, AdhocSecurity.keying(false, security))
        assertEquals(AdhocSecurity.Keying.Clear, AdhocSecurity.keying(false, null))
    }

    @Test fun `the senders are the talker first, the initiator, the participants and the group's members, never this user`() {
        val alice = "sip:alice@mcx.test"; val bob = "sip:bob@mcx.test"; val carol = "sip:carol@mcx.test"
        assertEquals(listOf(carol, alice, bob), AdhocSecurity.senders(carol, alice, listOf(bob, carol), listOf(alice, bob, "sip:me@mcx.test"), "sip:me@mcx.test"))
        assertEquals(listOf(bob), AdhocSecurity.senders(null, null, listOf("sip:me@mcx.test", bob, ""), emptyList(), "sip:me@mcx.test"))
    }

    @Test fun `the configured preconfigured groups are subscribed after the client's own groups, once each`() {
        assertEquals(listOf("sip:operations@mcx.test", security),
            AdhocSecurity.keyGroups(listOf("sip:operations@mcx.test"), listOf(security, "sip:operations@mcx.test")))
    }

    @Test fun `mcptt-info carries end-to-end-security and reads the server's preconfigured-group-id`() {
        val request = McpttInfo(sessionType = "adhoc", clientId = "urn:uuid:1", endToEndSecurity = true).encode()
        assertTrue(request, "<anyExt><end-to-end-security>true</end-to-end-security></anyExt>" in request)
        assertEquals(true, McpttInfo.parse(request).endToEndSecurity)
        assertNull(McpttInfo.parse(McpttInfo(sessionType = "adhoc").encode()).endToEndSecurity)
        // The OpenMCX server writes the anyURI as text (src/protocol/service_info.cpp).
        val answer = McpttInfo.parse("<?xml version=\"1.0\"?><mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params>" +
            "<mcptt-calling-group-id type=\"Normal\"><mcpttURI>sip:adhoc-x@mcx.test</mcpttURI></mcptt-calling-group-id>" +
            "<anyExt><preconfigured-group-id>$security</preconfigured-group-id></anyExt></mcptt-Params></mcpttinfo>")
        assertEquals(security, answer.preconfiguredGroupId)
    }

    @Test fun `mcvideo-info carries end-to-end-security and reads preconfigured-group-id`() {
        val request = McvideoInfo(sessionType = "adhoc", clientId = "urn:uuid:1", endToEndSecurity = true).encode()
        assertTrue(request, "<end-to-end-security>true</end-to-end-security>" in request)
        assertEquals(true, McvideoInfo.parse(request).endToEndSecurity)
        val answer = McvideoInfo.parse("<?xml version=\"1.0\"?><mcvideoinfo xmlns=\"urn:3gpp:ns:mcvideoInfo:1.0\"><mcvideo-Params>" +
            "<session-type>adhoc</session-type><anyExt><preconfigured-group-id>$security</preconfigured-group-id></anyExt>" +
            "</mcvideo-Params></mcvideoinfo>")
        assertEquals(security, answer.preconfiguredGroupId)
        assertEquals(security, McvideoInfo.parse(McvideoInfo(sessionType = "adhoc", preconfiguredGroupId = security).encode()).preconfiguredGroupId)
        assertNull(McvideoInfo.parse(McvideoInfo(sessionType = "adhoc").encode()).preconfiguredGroupId)
    }
}
