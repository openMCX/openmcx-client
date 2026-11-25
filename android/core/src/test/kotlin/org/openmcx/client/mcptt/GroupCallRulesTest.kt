//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.client.ClientRules
import org.openmcx.client.documents.GroupDocument
import org.openmcx.client.documents.UserProfile

/** The rules of [GroupCallRules] and the phase 2 bodies: regroup documents, the GMOP request, group documents. */
class GroupCallRulesTest {
    private val bob = "sip:bob.mcptt@mcx.test"
    private val carol = "sip:client-c.mcptt@mcx.test"

    @Test
    fun firstToAnswerTargets() {
        assertEquals(listOf(bob, carol), GroupCallRules.firstToAnswerTargets(listOf(bob, carol, bob), "sip:alice.mcptt@mcx.test"))
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.firstToAnswerTargets(listOf(bob), null) }
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.firstToAnswerTargets(listOf(bob, carol), bob) }
        // §11.1.1.2.1.1 step 2: the right, unless the profile was not read.
        assertTrue(GroupCallRules.mayRequestFirstToAnswer(profileRead = false, allowed = false))
        assertFalse(GroupCallRules.mayRequestFirstToAnswer(profileRead = true, allowed = false))
    }

    @Test
    fun firstToAnswerRelease() {
        assertEquals("not selected for call", GroupCallRules.firstToAnswerRelease(true, "not selected for call"))
        assertNull(GroupCallRules.firstToAnswerRelease(false, "not selected for call"))
        assertNull(GroupCallRules.firstToAnswerRelease(true, "administrator-action"))
    }

    /** TS 24.379 §11.1.1.2.1.2 step 10 c): Priv-Answer-Mode Manual is a manual answer even with the automatic setting. */
    @Test
    fun privAnswerModeManual() {
        assertTrue(ClientRules.manualCommencement(null, "Manual", "automatic", privateCall = true))
        assertFalse(ClientRules.manualCommencement("Manual", "Auto", "manual", privateCall = true))
    }

    @Test
    fun floorPriority() {
        assertEquals(90, GroupCallRules.floorPriorityOffer(90, 3, 1))
        assertEquals(3, GroupCallRules.floorPriorityOffer(null, 3, 1))
        assertEquals(1, GroupCallRules.floorPriorityOffer(null, null, 1))
        assertNull(GroupCallRules.floorPriorityOffer(null, null, null))
        assertEquals(255, GroupCallRules.floorPriorityOffer(300, null, null))
        // §14.3.3: the answer may lower it, not raise it; nothing offered, nothing negotiated.
        assertEquals(10, GroupCallRules.negotiatedFloorPriority(90, 10))
        assertEquals(90, GroupCallRules.negotiatedFloorPriority(90, 99))
        assertNull(GroupCallRules.negotiatedFloorPriority(null, 10))
        assertNull(GroupCallRules.negotiatedFloorPriority(90, null))
    }

    @Test
    fun adhocCriteriaAndModification() {
        assertEquals("affiliated:sip:a@x,location:north", GroupCallRules.adhocCriteria(" affiliated:sip:a@x , location:north"))
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.adhocCriteria("a,,b") }
        assertEquals(listOf("$bob;method=INVITE", "$carol;method=BYE"),
            GroupCallRules.adhocModificationEntries(listOf(bob), listOf(carol), null))
        assertEquals(emptyList<String>(), GroupCallRules.adhocModificationEntries(emptyList(), emptyList(), "x"))
        // NOTE of §17.2.6.1.1: the list and the criteria at once, or nothing, is refused.
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.adhocModificationEntries(listOf(bob), emptyList(), "x") }
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.adhocModificationEntries(emptyList(), emptyList(), null) }
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.adhocModificationEntries(listOf(bob), listOf(bob), null) }
    }

    @Test
    fun callingConstituentAndPreconfiguredUseOnly() {
        val north = "sip:north@x"; val south = "sip:south@x"
        assertEquals(south, GroupCallRules.callingConstituent(listOf(north, south), south, emptySet()))
        assertEquals(north, GroupCallRules.callingConstituent(listOf(north, south), "sip:other@x", setOf(north)))
        assertEquals(north, GroupCallRules.callingConstituent(listOf(north, south), null, emptySet()))
        assertNull(GroupCallRules.callingConstituent(emptyList(), north, setOf(north)))
        assertFalse(GroupCallRules.callsAllowed(true))
        assertTrue(GroupCallRules.callsAllowed(null))
    }

    /** TS 24.379 annex F.7.2: the regroup document, both ways, and its constraints (F.7.3). */
    @Test
    fun regroupDocument() {
        val r = Regroup("create", "sip:rg@x", "sip:pre@x", groups = listOf("sip:n@x", "sip:s@x"))
        val text = r.encode()
        assertTrue(text, text.contains("<mcpttregroup xmlns=\"urn:3gpp:ns:preconfiguredRegroup:1.0\"><mcpttregroup-Params>" +
            "<preconfig-group-id><preconfigured-group>sip:pre@x</preconfigured-group></preconfig-group-id>" +
            "<mcptt-regroup-uri><mcptt-regroup-uri>sip:rg@x</mcptt-regroup-uri></mcptt-regroup-uri>" +
            "<groups-for-regroup><group>sip:n@x</group><group>sip:s@x</group></groups-for-regroup>" +
            "<regroup-action>create</regroup-action>"))
        assertEquals(r, Regroup.parse(text))
        val users = Regroup("create", "sip:rg2@x", "sip:pre@x", users = listOf("sip:a@x"))
        assertEquals(users, Regroup.parse(users.encode()))
        val remove = Regroup("remove", "sip:rg@x", null)
        assertEquals(remove, Regroup.parse(remove.encode()))
        assertTrue(Regroup("create", "sip:t@x", "sip:t@x", groups = listOf("sip:n@x")).temporary)
        assertThrows(IllegalArgumentException::class.java) { Regroup("create", "sip:rg@x", "sip:pre@x") }
        assertThrows(IllegalArgumentException::class.java) { Regroup("create", "sip:rg@x", "sip:pre@x", listOf("a"), listOf("b")) }
        assertThrows(IllegalArgumentException::class.java) { Regroup("merge", "sip:rg@x", null) }
    }

    /** TS 24.481 §7.3.4.3: the GMOP group regroup creation request with <on-network-temporary>. */
    @Test
    fun temporaryGroupRequest() {
        val text = TemporaryGroups.creationRequest("sip:tg@x", listOf("sip:n@x", "sip:s@x"), chat = true, protect = false)
        assertTrue(text, text.contains("<document xmlns=\"urn:3gpp:ns:mcpttGMOP:1.0\"><request><group-regroup-creation>" +
            "<group xmlns=\"urn:oma:xml:poc:list-service\" xmlns:mcpttgi=\"urn:3gpp:ns:mcpttGroupInfo:1.0\"><list-service uri=\"sip:tg@x\">"))
        assertTrue(text, text.contains("<mcpttgi:on-network-temporary><mcpttgi:constituent-MCPTT-group-IDs>" +
            "<mcpttgi:constituent-MCPTT-group-ID>sip:n@x</mcpttgi:constituent-MCPTT-group-ID>" +
            "<mcpttgi:constituent-MCPTT-group-ID>sip:s@x</mcpttgi:constituent-MCPTT-group-ID>"))
        assertTrue(text, text.contains("{&quot;type&quot;:&quot;chat&quot;}"))
        assertThrows(IllegalArgumentException::class.java) { TemporaryGroups.creationRequest("sip:tg@x", listOf("sip:n@x"), false, false) }
    }

    /** TS 24.481 §7.2.2, §7.2.4.2: the group document's type, use, member priorities and temporary group marks. */
    @Test
    fun groupDocumentPhase2Parts() {
        val doc = GroupDocument.parse("""<?xml version="1.0"?><group xmlns="urn:oma:xml:poc:list-service"
            xmlns:gi="urn:3gpp:ns:mcpttGroupInfo:1.0"><list-service uri="sip:chat@x"><list>
            <entry uri="$bob"><gi:user-priority>7</gi:user-priority></entry><entry uri="$carol"/></list>
            <gi:on-network-invite-members>false</gi:on-network-invite-members>
            <gi:preconfigured-group-use-only>true</gi:preconfigured-group-use-only>
            <gi:on-network-regrouped temporary-MCPTT-group-ID="sip:tg@x" temporary-MCPTT-group-requestor="$bob">
            <gi:constituent-MCPTT-group-IDs><gi:constituent-MCPTT-group-ID>sip:chat@x</gi:constituent-MCPTT-group-ID></gi:constituent-MCPTT-group-IDs>
            </gi:on-network-regrouped></list-service></group>""")
        assertTrue(doc.chat)
        assertTrue(doc.preconfiguredUseOnly)
        assertEquals(mapOf(bob to 7), doc.userPriorities)
        assertEquals(listOf("sip:tg@x"), doc.regroupedInto)
        val temp = GroupDocument.parse("""<?xml version="1.0"?><group xmlns="urn:oma:xml:poc:list-service"
            xmlns:gi="urn:3gpp:ns:mcpttGroupInfo:1.0"><list-service uri="sip:tg@x"><gi:on-network-invite-members>true</gi:on-network-invite-members>
            <gi:on-network-temporary><gi:constituent-MCPTT-group-IDs><gi:constituent-MCPTT-group-ID>sip:n@x</gi:constituent-MCPTT-group-ID>
            <gi:constituent-MCPTT-group-ID>sip:s@x</gi:constituent-MCPTT-group-ID></gi:constituent-MCPTT-group-IDs></gi:on-network-temporary>
            <configuration xmlns="urn:openmcx:ns:groupConfiguration:1.0">{"type":"group-broadcast"}</configuration></list-service></group>""")
        assertEquals("group-broadcast", temp.type)
        assertFalse(temp.chat)
        assertEquals(listOf("sip:n@x", "sip:s@x"), temp.temporaryConstituents)
    }

    /** TS 24.484 §8.3.2.1 item 8 e) v): the <Priority> of <MCPTT-group-call>. */
    @Test
    fun profileGroupCallPriority() {
        val profile = UserProfile.parse("""<?xml version="1.0"?><mcptt-user-profile xmlns="urn:3gpp:mcptt:user-profile:1.0"
            XUI-URI="sip:alice.mcptt@mcx.test" user-profile-index="1"><Common index="1"><MCPTT-group-call>
            <MaxSimultaneousCallsN6>1</MaxSimultaneousCallsN6><Priority>4</Priority></MCPTT-group-call></Common></mcptt-user-profile>""")
        assertEquals(4, profile.groupCallPriority)
    }

    /** Annex F.1: <broadcast-ind> a plain boolean after <imminentperil-ind>; <release-reason> in <anyExt>. */
    @Test
    fun mcpttInfoBroadcastAndReleaseReason() {
        val text = McpttInfo(sessionType = "prearranged", requestUri = "sip:g@x", imminentPeril = true, broadcast = true,
            associatedGroupId = "sip:n@x").encode()
        assertTrue(text, text.contains("</imminentperil-ind><broadcast-ind>true</broadcast-ind><associated-group-id"))
        val parsed = McpttInfo.parse(text)
        assertEquals(true, parsed.broadcast)
        assertTrue("broadcast-ind" !in parsed.extra)
        val bye = McpttInfo.parse(McpttInfo(releaseReason = "not selected for call").encode())
        assertEquals("not selected for call", bye.releaseReason)
        assertNull(McpttInfo.parse(McpttInfo(sessionType = "private").encode()).broadcast)
        // CallType: a first-to-answer call is a private call (§11.1.1).
        assertEquals(CallType.PRIVATE, CallType.of("first-to-answer"))
        assertEquals(CallType.CHAT, CallType.of("chat"))
    }
}
