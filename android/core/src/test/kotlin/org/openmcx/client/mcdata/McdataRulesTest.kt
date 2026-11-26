//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS 24.282 §11.1 with the MCData service configuration (TS 24.484 §10.4.2.1) and the group document (TS 24.481 §7.2). */
class McdataRulesTest {
    private val alice = "sip:alice.mcdata@mcx.test"

    private fun serviceConfig(control: String) = """<?xml version="1.0"?>
        <service-configuration-info xmlns="urn:3gpp:mcdata:mcdataServiceConfig:1.0"><service-configuration-params>
        <common/><on-network><tx-and-rx-control>$control</tx-and-rx-control></on-network>
        </service-configuration-params></service-configuration-info>"""

    @Test fun theTxLimitsOfTheServiceConfiguration() {
        val limits = McdataTxLimits.parse(serviceConfig("<max-data-size-sds-bytes>20</max-data-size-sds-bytes>" +
            "<max-payload-size-sds-cplane-bytes>0</max-payload-size-sds-cplane-bytes><max-data-size-fd-bytes>100</max-data-size-fd-bytes>"))
        assertEquals(McdataTxLimits(20, 0, 100, null), limits)
        assertEquals(McdataTxLimits(), McdataTxLimits.parse(serviceConfig("").replace("<tx-and-rx-control></tx-and-rx-control>", "")))
        assertTrue(runCatching { McdataTxLimits.parse(serviceConfig("<max-data-size-fd-bytes>-1</max-data-size-fd-bytes>")) }.isFailure)
    }

    private fun group(elements: String, actions: String = "<mcdata-allow-transmit-data-in-this-group>true</mcdata-allow-transmit-data-in-this-group>",
                      conditions: String = "<cp:conditions><is-list-member/></cp:conditions>") = """<?xml version="1.0"?>
        <resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list-service xmlns="urn:oma:xml:poc:list-service"
          xmlns:cp="urn:ietf:params:xml:ns:common-policy" xmlns:gi="urn:3gpp:ns:mcpttGroupInfo:1.0" uri="sip:operations@mcx.test">
          <list><entry uri="sip:alice@mcx.test"><gi:mcdata-mcdata-id uri="$alice"/></entry></list>
          $elements
          <cp:ruleset><cp:rule id="r">$conditions<cp:actions>$actions</cp:actions></cp:rule></cp:ruleset>
        </list-service></resource-lists>"""

    private val allowed = "<gi:mcdata-allow-short-data-service>true</gi:mcdata-allow-short-data-service>"

    @Test fun theGroupSdsRules() {
        val ids = listOf(alice)
        assertNull(McdataGroupRules.parse(group(allowed)).sdsRefusal(40, false, false, ids))
        // §9.2.2.2.1 3)a): AllowedSDS absent or "false".
        assertEquals("mcdata-allow-short-data-service", McdataGroupRules.parse(group("")).sdsRefusal(1, false, false, ids))
        // §11.1 2): no transmit right for the user (no rule grants it, or the user is not a member).
        assertEquals(McdataGroupRules.TRANSMIT, McdataGroupRules.parse(group(allowed, actions = "")).sdsRefusal(1, false, false, ids))
        assertEquals(McdataGroupRules.TRANSMIT, McdataGroupRules.parse(group(allowed)).sdsRefusal(1, false, false, listOf("sip:carol.mcdata@mcx.test")))
        // An <identity> condition names the user.
        assertNull(McdataGroupRules.parse(group(allowed, conditions = "<cp:conditions><cp:identity><cp:one id=\"$alice\"/></cp:identity></cp:conditions>"))
            .sdsRefusal(1, false, false, ids))
        // §11.1 5): above the group's limit.
        val limited = McdataGroupRules.parse(group(allowed + "<gi:mcdata-on-network-max-data-size-for-SDS>20</gi:mcdata-on-network-max-data-size-for-SDS>"))
        assertEquals("mcdata-on-network-max-data-size-for-SDS", limited.sdsRefusal(40, false, false, ids))
        assertNull(limited.sdsRefusal(8, false, false, ids))
        // §9.2.3.2.3 / §9.2.4.2.3 8)a1): a preconfigured-use-only group: no media plane SDS, no session; signalling allowed.
        val preconf = McdataGroupRules.parse(group(allowed + "<gi:preconfigured-group-use-only>true</gi:preconfigured-group-use-only>"))
        assertNull(preconf.sdsRefusal(8, false, false, ids))
        assertEquals("preconfigured-group-use-only", preconf.sdsRefusal(8, true, false, ids))
        assertEquals("preconfigured-group-use-only", preconf.sdsRefusal(0, true, true, ids))
    }

    @Test fun theGroupFdRules() {
        val ids = listOf(alice)
        // <mcdata-allow-file-distribution> absent: allowed.
        assertNull(McdataGroupRules.parse(group("")).fdRefusal(58, ids))
        assertEquals("mcdata-allow-file-distribution",
            McdataGroupRules.parse(group("<gi:mcdata-allow-file-distribution>false</gi:mcdata-allow-file-distribution>")).fdRefusal(58, ids))
        assertEquals("mcdata-on-network-max-data-size-for-FD",
            McdataGroupRules.parse(group("<gi:mcdata-on-network-max-data-size-for-FD>100</gi:mcdata-on-network-max-data-size-for-FD>")).fdRefusal(18000, ids))
        assertEquals(McdataGroupRules.TRANSMIT, McdataGroupRules.parse(group("", actions = "")).fdRefusal(58, ids))
        assertEquals("preconfigured-group-use-only",
            McdataGroupRules.parse(group("<gi:preconfigured-group-use-only>true</gi:preconfigured-group-use-only>")).fdRefusal(58, ids))
    }

    @Test fun theAssociatedGroupOfATemporaryGroup() {
        val a = "sip:operations@mcx.test"; val b = "sip:operations-noemergency@mcx.test"
        assertEquals(b, McdataGroupRules.associatedGroup(listOf(a, b), b, listOf(a, b)))
        assertEquals(a, McdataGroupRules.associatedGroup(listOf("sip:x@mcx.test", a), null, listOf(a)))
        assertEquals("sip:x@mcx.test", McdataGroupRules.associatedGroup(listOf("sip:x@mcx.test"), null, emptyList()))
        assertNull(McdataGroupRules.associatedGroup(emptyList(), a, listOf(a)))
    }
}
