package org.openmcx.client.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsTest {
    /** A TS 24.484 user profile as the OpenMCX CMS serves it (Go's encoding/xml namespace style). */
    private val profile = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<mcptt-user-profile xmlns=\"urn:3gpp:mcptt:user-profile:1.0\" XUI-URI=\"sip:alice.mcptt@mcx.test\" user-profile-index=\"1\">" +
        "<Status>true</Status><ProfileName>mcptt-user-profile-1.xml</ProfileName>" +
        "<Common index=\"0\"><MCPTTUserID><uri-entry>sip:alice.mcptt@mcx.test</uri-entry></MCPTTUserID>" +
        "<PrivateCall><PrivateCallList><PrivateCallURI><uri-entry>sip:bob.mcptt@mcx.test</uri-entry></PrivateCallURI>" +
        "<PrivateCallURI><uri-entry>sip:carol.mcptt@mcx.test</uri-entry></PrivateCallURI></PrivateCallList></PrivateCall></Common>" +
        "<OnNetwork index=\"0\"><MCPTTGroupInfo><entry><uri-entry>sip:operations@mcx.test</uri-entry></entry>" +
        "<entry><uri-entry>sip:logistics@mcx.test</uri-entry></entry></MCPTTGroupInfo><MaxAffiliationsN2>4</MaxAffiliationsN2>" +
        "<anyExt><IncomingPrivateCallList><PrivateCallURI><uri-entry>sip:dave.mcptt@mcx.test</uri-entry></PrivateCallURI>" +
        "</IncomingPrivateCallList></anyExt></OnNetwork>" +
        "<ruleset xmlns=\"urn:ietf:params:xml:ns:common-policy\"><rule id=\"rights\"><actions>" +
        "<allow-private-call xmlns=\"urn:3gpp:mcptt:user-profile:1.0\">true</allow-private-call>" +
        "<allow-force-auto-answer xmlns=\"urn:3gpp:mcptt:user-profile:1.0\">false</allow-force-auto-answer>" +
        "<allow-transmit-data xmlns=\"urn:3gpp:ns:mcdata:user-profile:1.0\"> 1 </allow-transmit-data>" +
        "<allow-cancel-group-emergency xmlns=\"urn:openmcx:ns:userProfile:1.0\">true</allow-cancel-group-emergency>" +
        "<other xmlns=\"urn:x\">true</other>" +
        "<anyExt xmlns=\"urn:3gpp:mcptt:user-profile:1.0\"><allow-call-transfer>true</allow-call-transfer></anyExt>" +
        "</actions></rule></ruleset>" +
        "<profile-extension xmlns=\"urn:openmcx:ns:userProfile:1.0\"><mc-id>alice</mc-id>" +
        "<sip-uri>sip:alice@mcx.test</sip-uri><client-id>urn:uuid:1</client-id></profile-extension></mcptt-user-profile>"

    @Test
    fun userProfileOfTs24484() {
        val p = UserProfile.parse(profile)
        assertEquals("sip:alice.mcptt@mcx.test", p.mcpttId)
        assertEquals("urn:uuid:1", p.clientId)
        assertEquals(listOf("sip:operations@mcx.test", "sip:logistics@mcx.test"), p.groups)
        // Incoming-only entries are not targets.
        assertEquals(listOf("sip:bob.mcptt@mcx.test", "sip:carol.mcptt@mcx.test"), p.privateCallTargets)
        assertEquals(setOf("allow-private-call", "allow-transmit-data", "allow-cancel-group-emergency", "allow-call-transfer"), p.actions)
        assertTrue(p.allows("allow-private-call"))
        assertFalse(p.allows("allow-force-auto-answer"))
        assertFalse(p.allows("other"))
    }

    @Test
    fun prefixedRulesetAndMinimalProfile() {
        val p = UserProfile.parse("<mcptt-user-profile xmlns=\"urn:3gpp:mcptt:user-profile:1.0\" XUI-URI=\"sip:a@x\" user-profile-index=\"1\">" +
            "<Status>true</Status><Common index=\"0\"><PrivateCall><PrivateCallList><anyExt/></PrivateCallList></PrivateCall></Common>" +
            "<cp:ruleset xmlns:cp=\"urn:ietf:params:xml:ns:common-policy\"><cp:rule id=\"r\"><cp:actions>" +
            "<allow-emergency-private-call>true</allow-emergency-private-call><allow-regroup>yes</allow-regroup>" +
            "</cp:actions></cp:rule></cp:ruleset></mcptt-user-profile>")
        assertEquals("sip:a@x", p.mcpttId) // from XUI-URI without <MCPTTUserID>
        assertNull(p.clientId)
        assertEquals(emptyList<String>(), p.groups)
        assertEquals(emptyList<String>(), p.privateCallTargets)
        assertEquals(setOf("allow-emergency-private-call"), p.actions)
    }

    @Test
    fun oldOrForeignProfileIsRejected() {
        assertThrows(DocumentError::class.java) {
            UserProfile.parse("<mcptt-user-profile xmlns=\"urn:3gpp:ns:mcpttUserProfile:1.0\"><mcptt-id>sip:a@x</mcptt-id></mcptt-user-profile>")
        }
    }

    @Test
    fun groupDocument() {
        val group = GroupDocument.parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?><group xmlns=\"urn:oma:xml:poc:list-service\">" +
            "<list-service uri=\"sip:operations@mcx.test\"><list><entry uri=\"sip:alice.mcptt@mcx.test\"></entry></list>" +
            "<supported-services xmlns=\"urn:oma:xml:xdm:extensions\"><service enabler=\"urn:urn-7:3gpp-service.ims.icsi.mcptt\"></service>" +
            "<service enabler=\"urn:urn-7:3gpp-service.ims.icsi.mcdata\"></service></supported-services>" +
            "<protect-media xmlns=\"urn:3gpp:ns:mcpttGroupInfo:1.0\">false</protect-media></list-service></group>")
        assertEquals("sip:operations@mcx.test", group.uri)
        assertEquals(listOf("sip:alice.mcptt@mcx.test"), group.members)
        assertTrue(group.mcptt && group.mcdata)
    }
}
