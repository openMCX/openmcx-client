//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bodies of the service settings and affiliation procedures (TS 24.379 §7.4.1.2.2, §9.3, annex F.4; RFC 4354). */
class ProceduresBodiesTest {
    /** §7.2.3 4): the answer mode, the selected user profile's index, <multiplex-support>; read back as §7.2.4 caches it. */
    @Test fun pocSettingsRoundTrip() {
        val xml = PocSettings.encode("urn:uuid:1", "manual", 3)
        assertTrue(xml.contains("<mcs10Set:user-profile-index>3</mcs10Set:user-profile-index>"))
        val entity = PocSettings.parse(xml).single()
        assertEquals(PocSettings.Entity("urn:uuid:1", "manual", 3, false), entity)
    }

    @Test fun pocSettingsOfSeveralClients() {
        val xml = """<?xml version="1.0"?><poc-settings xmlns="urn:oma:params:xml:ns:poc:poc-settings" xmlns:m="urn:3gpp:mcsSettings:1.0">
            <entity id="urn:uuid:1"><am-settings><answer-mode>automatic</answer-mode></am-settings>
            <m:selected-user-profile-index><m:user-profile-index>1</m:user-profile-index></m:selected-user-profile-index></entity>
            <entity id="urn:uuid:2"><am-settings><answer-mode>manual</answer-mode></am-settings></entity></poc-settings>"""
        val entities = PocSettings.parse(xml)
        assertEquals(listOf("urn:uuid:1", "urn:uuid:2"), entities.map { it.id })
        assertEquals(1, entities[0].profileIndex)
        assertEquals("manual", entities[1].answerMode)
        assertNull(entities[1].profileIndex)
    }

    /** Annex F.4: <command-list> with <affiliate> and <de-affiliate>, each listing <group>. */
    @Test fun affiliationCommand() {
        val xml = AffiliationCommand.encode(listOf("sip:a@x"), listOf("sip:b@x", "sip:c@x"))
        assertTrue(xml.contains("<command-list xmlns=\"urn:3gpp:ns:affiliationCommand:1.0\"><affiliate><group>sip:a@x</group></affiliate>"))
        assertEquals(listOf("sip:a@x") to listOf("sip:b@x", "sip:c@x"), AffiliationCommand.parse(xml))
        assertFalse(AffiliationCommand.encode(listOf("sip:a@x"), emptyList()).contains("de-affiliate"))
        assertEquals("application/vnd.3gpp.mcptt-affiliation-command+xml", AffiliationCommand.contentType(McService.MCPTT))
        assertEquals("application/vnd.3gpp.mcvideo-affiliation-command+xml", AffiliationCommand.contentType(McService.MCVIDEO))
    }

    /** §9.3.1.2: one tuple per client of the user; §9.2.1.2 5 c): the groups of interest leave "deaffiliating" ones. */
    @Test fun affiliationOfEveryClient() {
        val xml = """<?xml version="1.0"?><presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:p="urn:3gpp:ns:mcpttPresInfo:1.0" entity="sip:bob@x">
            <tuple id="urn:uuid:b1"><status><p:affiliation group="sip:g1@x" status="affiliated"/><p:affiliation group="sip:g2@x" status="deaffiliating"/></status></tuple>
            <tuple id="urn:uuid:b2"><status><p:affiliation group="sip:g3@x"/></status></tuple></presence>"""
        val all = Affiliation.parseAll(xml)
        assertEquals(setOf("urn:uuid:b1", "urn:uuid:b2"), all.keys)
        assertEquals(listOf("sip:g1@x"), Affiliation.interested(all.getValue("urn:uuid:b1")))
        assertEquals("affiliated", all.getValue("urn:uuid:b2")["sip:g3@x"])
    }

    /** §9.3.2.2: the per-group filter: the group, then one <include> per item asked for, in the service's bindings. */
    @Test fun groupDynamicDataFilter() {
        val xml = GroupDynamicData.filter(McService.MCPTT, "sip:g@x", listOf("status", "call"), "gdd-1")
        assertTrue(xml.contains("<ns-binding prefix=\"mcpttPI10\" urn=\"urn:3gpp:ns:mcpttPresInfo:1.0\"/>"))
        assertTrue(xml.contains("<filter id=\"gdd-1\"><what><include>//pidf:presence[@entity=&quot;sip:g@x&quot;]</include>" +
            "<include>//pidf:presence/pidf:groupStatus/@pidf:ModifiedBy</include>" +
            "<include>//pidf:presence/pidf:additionalData/@pidf:groupCallOngoing</include></what></filter>"))
        assertFalse(xml.contains("uri="))
    }

    /** §9.3.1.2: the group, its members' clients and aliases, groupStatus with ModifiedBy, additionalData. */
    @Test fun groupDynamicDataDocument() {
        val xml = """<?xml version="1.0"?><presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:mcpttPI10="urn:3gpp:ns:mcpttPresInfo:1.0" entity="sip:g@x">
            <tuple id="sip:alice@x"><status><mcpttPI10:affiliation client="urn:uuid:1" expires="2030-01-01T00:00:00Z"/>
            <mcpttPI10:functionalAlias functionalAliasID="sip:duty@x"/></status></tuple>
            <mcpttPI10:groupStatus ModifiedBy="sip:bob@x">emergency</mcpttPI10:groupStatus>
            <mcpttPI10:additionalData groupCallOngoing="true" groupRegroupAlias="sip:r@x"/></presence>"""
        val data = GroupDynamicData.parse(xml)
        assertEquals("sip:g@x", data.group)
        assertEquals("emergency", data.groupStatus)
        assertEquals("sip:bob@x", data.modifiedBy)
        assertEquals(true, data.callOngoing)
        assertEquals("sip:r@x", data.regroupAlias)
        assertNull(data.broadcastAlias)
        assertEquals(listOf(GroupDynamicData.Member("sip:alice@x", listOf("urn:uuid:1"), listOf("sip:duty@x"))), data.members)
    }
}
