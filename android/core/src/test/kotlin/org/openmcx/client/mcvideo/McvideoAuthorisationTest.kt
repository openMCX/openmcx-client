//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS 24.281 §6.2.8.1.6, §6.2.8.1.8, §6.2.8.3.1.1 with TS 24.484 §9.3.2: the user profile's rights and target entries. */
class McvideoAuthorisationTest {
    private val alice = "sip:alice.mcvideo@mcx.test"
    private val a = "sip:video-a@mcx.test"
    private val b = "sip:video-b@mcx.test"

    companion object {
        /** An MCVideo user profile of [user] with the rights [rights] and the target entries given (null: element absent). */
        fun profile(user: String, rights: Map<String, Boolean>, emergency: String? = "UseCurrentlySelectedGroup", emergencyUri: String? = null,
                    imminent: String? = "UseCurrentlySelectedGroup", imminentUri: String? = null,
                    alert: String? = "UseCurrentlySelectedGroup", alertUri: String? = null,
                    privateRecipient: String? = null, privateRecipientUri: String? = null): String {
            fun entry(info: String?, uri: String?) = if (info == null) "" else
                "<entry entry-info=\"$info\">${uri?.let { "<uri-entry>$it</uri-entry>" } ?: ""}</entry>"
            return """<?xml version="1.0" encoding="UTF-8"?><mcvideo-user-profile xmlns="urn:3gpp:ns:mcvideo:user-profile:1.0"
                xmlns:cp="urn:ietf:params:xml:ns:common-policy" XUI-URI="$user" user-profile-index="1"><Status>true</Status>
                <Common index="c"><MCVideoUserID><uri-entry>$user</uri-entry></MCVideoUserID>
                ${if (privateRecipient != null) "<PrivateCall><PrivateCallList/><EmergencyCall><MCVideoPrivateRecipient>${entry(privateRecipient, privateRecipientUri)}</MCVideoPrivateRecipient></EmergencyCall></PrivateCall>" else ""}
                <MCVideo-group-call><MaxSimultaneousCallsN6>1</MaxSimultaneousCallsN6>
                ${if (emergency != null) "<EmergencyCall><MCVideoGroupInitiation>${entry(emergency, emergencyUri)}</MCVideoGroupInitiation></EmergencyCall>" else ""}
                ${if (imminent != null) "<ImminentPerilCall><MCVideoGroupInitiation>${entry(imminent, imminentUri)}</MCVideoGroupInitiation></ImminentPerilCall>" else ""}
                ${if (alert != null) "<EmergencyAlert>${entry(alert, alertUri)}</EmergencyAlert>" else ""}
                <Priority>3</Priority></MCVideo-group-call></Common>
                <cp:ruleset><cp:rule id="rights"><cp:actions>${rights.entries.joinToString("") { (k, v) -> "<$k>$v</$k>" }}
                <anyExt><allow-activate-adhoc-group-emergency-alert>true</allow-activate-adhoc-group-emergency-alert></anyExt>
                </cp:actions></cp:rule></cp:ruleset></mcvideo-user-profile>"""
        }

        /** A group document whose <ruleset> grants <mcvideo-allow-emergency-alert> to [granted] by <identity>. */
        fun groupDocument(group: String, members: List<String>, granted: List<String>, toMembers: Boolean = false) =
            """<?xml version="1.0"?><resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list-service
            xmlns="urn:oma:xml:poc:list-service" xmlns:cp="urn:ietf:params:xml:ns:common-policy"
            xmlns:gi="urn:3gpp:ns:mcpttGroupInfo:1.0" uri="$group"><list>${members.joinToString("") { "<entry uri=\"$it\"/>" }}</list>
            <cp:ruleset><cp:rule id="alert"><cp:conditions>${if (toMembers) "<is-list-member/>"
                else "<cp:identity>${granted.joinToString("") { "<cp:one id=\"$it\"/>" }}</cp:identity>"}</cp:conditions>
            <cp:actions><gi:mcvideo-allow-emergency-alert>true</gi:mcvideo-allow-emergency-alert></cp:actions></cp:rule>
            <cp:rule id="other"><cp:conditions><cp:identity><cp:one id="sip:x@mcx.test"/></cp:identity><cp:validity/></cp:conditions>
            <cp:actions><gi:mcvideo-allow-emergency-call>true</gi:mcvideo-allow-emergency-call></cp:actions></cp:rule></cp:ruleset>
            </list-service></resource-lists>"""
    }

    @Test fun rightsAndTargetEntriesAreRead() {
        val p = McvideoProfile.parse(profile(alice, mapOf("allow-emergency-group-call" to true, "allow-imminent-peril-call" to false),
            emergency = "DedicatedGroup", emergencyUri = b, privateRecipient = "UsePreConfigured", privateRecipientUri = "sip:bob.mcvideo@mcx.test"))
        assertTrue(p.allows("allow-emergency-group-call"))
        assertFalse(p.allows("allow-imminent-peril-call"))
        assertTrue("a right in <anyExt>", p.allows("allow-activate-adhoc-group-emergency-alert"))
        assertEquals(McvideoProfile.TargetEntry("DedicatedGroup", b), p.targets[McvideoProfile.EMERGENCY_GROUP_INITIATION])
        assertEquals(McvideoProfile.TargetEntry("UseCurrentlySelectedGroup", null), p.targets[McvideoProfile.IMMINENT_GROUP_INITIATION])
        assertEquals("UsePreConfigured", p.targets[McvideoProfile.EMERGENCY_PRIVATE_RECIPIENT]?.entryInfo)
        assertNull(p.targets[McvideoProfile.PRIVATE_EMERGENCY_ALERT])
        assertEquals(3, p.priority)
    }

    private fun refused(block: suspend () -> Unit): String? = runBlocking {
        try { block(); null } catch (e: McvideoAuthorisation.NotAuthorised) { e.action }
    }

    /** §6.2.8.1.8 1): "DedicatedGroup" authorises the named group only; "UseCurrentlySelectedGroup" any; absent none. */
    @Test fun groupInitiationTargets() {
        val dedicated = McvideoProfile.parse(profile(alice, emptyMap(), emergency = "DedicatedGroup", emergencyUri = b, imminent = null))
        val entry = McvideoProfile.EMERGENCY_GROUP_INITIATION
        assertEquals(entry, refused { McvideoAuthorisation.requireTarget(dedicated, entry, a, "emergency group call") })
        assertNull(refused { McvideoAuthorisation.requireTarget(dedicated, entry, b, "emergency group call") })
        assertEquals("absent: unauthorised", McvideoProfile.IMMINENT_GROUP_INITIATION,
            refused { McvideoAuthorisation.requireTarget(dedicated, McvideoProfile.IMMINENT_GROUP_INITIATION, b, "imminent peril group call") })
        val selected = McvideoProfile.parse(profile(alice, emptyMap()))
        assertNull(refused { McvideoAuthorisation.requireTarget(selected, entry, a, "emergency group call") })
    }

    /** §6.2.8.1.6 2) b): "UseCurrentlySelectedGroup" for an alert needs the group document's grant to this user. */
    @Test fun alertTargetNeedsTheGroupDocumentsGrant() {
        val p = McvideoProfile.parse(profile(alice, emptyMap()))
        val entry = McvideoProfile.GROUP_EMERGENCY_ALERT
        assertNull(refused { McvideoAuthorisation.requireTarget(p, entry, a, "emergency alert") { true } })
        assertEquals(entry, refused { McvideoAuthorisation.requireTarget(p, entry, a, "emergency alert") { false } })
        assertEquals(entry, refused { McvideoAuthorisation.requireTarget(p, entry, a, "emergency alert") { throw IllegalStateException("HTTP 404") } })
        assertTrue(McvideoAuthorisation.groupAllows(groupDocument(a, listOf(alice), listOf(alice)), "mcvideo-allow-emergency-alert", listOf(alice)))
        assertFalse(McvideoAuthorisation.groupAllows(groupDocument(a, listOf(alice), listOf("sip:bob.mcvideo@mcx.test")),
            "mcvideo-allow-emergency-alert", listOf(alice)))
        assertTrue("<is-list-member>", McvideoAuthorisation.groupAllows(groupDocument(a, listOf(alice), emptyList(), toMembers = true),
            "mcvideo-allow-emergency-alert", listOf(alice)))
        assertFalse("not a member", McvideoAuthorisation.groupAllows(groupDocument(a, listOf("sip:bob.mcvideo@mcx.test"), emptyList(), toMembers = true),
            "mcvideo-allow-emergency-alert", listOf(alice)))
        assertFalse("an unknown condition: the rule does not apply", McvideoAuthorisation.groupAllows(
            groupDocument(a, listOf(alice), listOf("sip:x@mcx.test")), "mcvideo-allow-emergency-call", listOf("sip:x@mcx.test")))
        val dedicated = McvideoProfile.parse(profile(alice, emptyMap(), alert = "DedicatedGroup", alertUri = b))
        assertEquals(entry, refused { McvideoAuthorisation.requireTarget(dedicated, entry, a, "emergency alert") { true } })
        assertNull(refused { McvideoAuthorisation.requireTarget(dedicated, entry, b, "emergency alert") { false } })
    }

    /** §6.2.8.3.1.1 1): the emergency private call's recipient; a profile without the element leaves it to the right. */
    @Test fun privateRecipientTargets() {
        val entry = McvideoProfile.EMERGENCY_PRIVATE_RECIPIENT
        val bob = "sip:bob.mcvideo@mcx.test"
        val none = McvideoProfile.parse(profile(alice, emptyMap()))
        assertNull(refused { McvideoAuthorisation.requireTarget(none, entry, bob, "emergency private call") })
        val preconfigured = McvideoProfile.parse(profile(alice, emptyMap(), privateRecipient = "UsePreConfigured", privateRecipientUri = bob))
        assertNull(refused { McvideoAuthorisation.requireTarget(preconfigured, entry, bob, "emergency private call") })
        assertEquals(entry, refused { McvideoAuthorisation.requireTarget(preconfigured, entry, "sip:carol.mcvideo@mcx.test", "emergency private call") })
        val local = McvideoProfile.parse(profile(alice, emptyMap(), privateRecipient = "LocallyDetermined"))
        assertNull(refused { McvideoAuthorisation.requireTarget(local, entry, "sip:carol.mcvideo@mcx.test", "emergency private call") })
    }

    @Test fun rightsAreRequired() {
        val p = McvideoProfile.parse(profile(alice, mapOf("allow-emergency-group-call" to true, "allow-cancel-group-emergency" to false)))
        assertNull(refused { McvideoAuthorisation.requireRight(p, "allow-emergency-group-call", "emergency group call") })
        assertEquals("allow-cancel-group-emergency", refused { McvideoAuthorisation.requireRight(p, "allow-cancel-group-emergency", "cancel") })
        assertEquals("allow-emergency-adhoc-group-call", refused { McvideoAuthorisation.requireRight(p, "allow-emergency-adhoc-group-call", "ad hoc") })
    }
}
