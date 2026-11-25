//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location rules (TS 24.379 §9A.2.1.4, §9.2.1.7.2, §9.2.1.7.3, §9.2.1.2; TS 24.282 §22.2.1.4, §8.2.6.2, §8.2.2,
 * §22.2.1.2), with the profile and group documents of the OpenMCX server library's tests (tests/test_protocol.cpp).
 */
class LocationRulesTest {
    private fun corner(lat: Double, lon: Double, p: String = "") =
        "<${p}Corner><${p}Longitude>${encodeLongitude(lon)}</${p}Longitude><${p}Latitude>${encodeLatitude(lat)}</${p}Latitude></${p}Corner>"
    private fun polygon(p: String = "") = "<${p}PolygonArea>" + corner(45.0005, 9.999, p) + corner(45.0005, 10.001, p) +
        corner(45.0013, 10.001, p) + corner(45.0013, 9.999, p) + "</${p}PolygonArea>"
    private val inArea = 45.0009 to 10.0
    private val outArea = 45.0018 to 10.0

    private val mcpttProfile =
        "<mcptt-user-profile xmlns=\"urn:3gpp:mcptt:user-profile:1.0\" XUI-URI=\"sip:a@x\" user-profile-index=\"1\">" +
        "<OnNetwork index=\"1\"><MCPTTGroupInfo><entry index=\"1\"><uri-entry>sip:ga@x</uri-entry><anyExt>" +
        "<RulesForAffiliation><ListOfLocationCriteria><ExitSpecificArea>" + polygon() +
        "</ExitSpecificArea></ListOfLocationCriteria><ListOfActiveFunctionalAliasCriteria><entry index=\"1\">" +
        "<uri-entry>sip:fa1@x</uri-entry></entry></ListOfActiveFunctionalAliasCriteria></RulesForAffiliation>" +
        "<RulesForDeaffiliation><ListOfLocationCriteria><EnterSpecificArea>" + polygon() + "</EnterSpecificArea><EnterSpecificArea>" +
        polygon() + "<anyExt><Speed><MinimumSpeed>5</MinimumSpeed><MaximumSpeed>9</MaximumSpeed></Speed></anyExt>" +
        "</EnterSpecificArea></ListOfLocationCriteria></RulesForDeaffiliation>" +
        "<manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>true" +
        "</manual-deaffiliation-not-allowed-if-affiliation-rules-are-met></anyExt></entry>" +
        "<entry index=\"2\"><uri-entry>sip:gb@x</uri-entry></entry></MCPTTGroupInfo>" +
        "<anyExt><FunctionalAliasList><entry index=\"1\"><uri-entry>sip:fa1@x</uri-entry><anyExt>" +
        "<LocationCriteriaForActivation><EnterSpecificArea>" + polygon() + "</EnterSpecificArea></LocationCriteriaForActivation>" +
        "<LocationCriteriaForDeactivation><ExitSpecificArea>" + polygon() + "</ExitSpecificArea><ExitSpecificArea><EllipsoidArcArea>" +
        "<Center><Longitude>1</Longitude><Latitude>2</Latitude></Center><Radius>500</Radius><OffsetAngle>0</OffsetAngle>" +
        "<IncludedAngle>179</IncludedAngle></EllipsoidArcArea></ExitSpecificArea></LocationCriteriaForDeactivation>" +
        "<manual-deactivation-not-allowed-if-location-criteria-met>true</manual-deactivation-not-allowed-if-location-criteria-met>" +
        "</anyExt></entry><entry index=\"2\"><uri-entry>sip:fa2@x</uri-entry></entry></FunctionalAliasList></anyExt>" +
        "</OnNetwork></mcptt-user-profile>"

    private val mcdataProfile =
        "<mcdata-user-profile xmlns=\"urn:3gpp:ns:mcdata:user-profile:1.0\" XUI-URI=\"sip:a@x\" user-profile-index=\"1\">" +
        "<OnNetwork index=\"0\"><MCDataGroupInfo><MCData-Group-ID><uri-entry>sip:ga@x</uri-entry></MCData-Group-ID><anyExt>" +
        "<allow-store-group-comm-in-msgstore>true</allow-store-group-comm-in-msgstore><RulesForAffiliation><ListOfLocationCriteria>" +
        "<ExitSpecificArea>" + polygon() + "</ExitSpecificArea></ListOfLocationCriteria></RulesForAffiliation><RulesForDeaffiliation>" +
        "<ListOfLocationCriteria><EnterSpecificArea>" + polygon() + "</EnterSpecificArea></ListOfLocationCriteria></RulesForDeaffiliation>" +
        "<manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>true</manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>" +
        "</anyExt></MCDataGroupInfo><MCDataGroupInfo><MCData-Group-ID><uri-entry>sip:gb@x</uri-entry></MCData-Group-ID></MCDataGroupInfo>" +
        "<anyExt><FunctionalAliasList><entry index=\"1\"><uri-entry>sip:fa1@x</uri-entry><anyExt><LocationCriteriaForActivation>" +
        "<EnterSpecificArea>" + polygon() + "</EnterSpecificArea></LocationCriteriaForActivation><LocationCriteriaForDeactivation>" +
        "<ExitSpecificArea>" + polygon() + "</ExitSpecificArea></LocationCriteriaForDeactivation>" +
        "<manual-deactivation-not-allowed-if-location-criteria-met>true</manual-deactivation-not-allowed-if-location-criteria-met>" +
        "</anyExt></entry></FunctionalAliasList></anyExt></OnNetwork></mcdata-user-profile>"

    private fun groupDoc(extra: String) = "<group xmlns=\"urn:oma:xml:poc:list-service\" xmlns:gi=\"urn:3gpp:ns:mcpttGroupInfo:1.0\">" +
        "<list-service uri=\"sip:ga@x\">$extra</list-service></group>"

    private fun LocationRuleEngine.at(p: Pair<Double, Double>, standing: Set<String> = emptySet(), active: Set<String> = standing,
                                      groups: List<String> = emptyList()) = position(p.first, p.second, standing, active, groups)

    @Test fun mcpttProfileRules() {
        val rules = ProfileRules.parse(mcpttProfile)
        assertEquals(listOf("sip:fa1@x", "sip:fa2@x"), rules.aliases.map { it.alias })
        val fa = rules.aliases.first()
        assertTrue(fa.activateOnEntry.size == 1 && fa.activateOnExit.isEmpty() && fa.deactivateOnEntry.isEmpty() && fa.deactivateOnExit.size == 2)
        assertTrue(fa.manualDeactivationLocked && fa.hasLocationCriteria && !rules.aliases.last().hasLocationCriteria)
        assertTrue(inside(fa.activateOnEntry.first(), inArea.first, inArea.second) && !inside(fa.activateOnEntry.first(), outArea.first, outArea.second))
        val arc = fa.deactivateOnExit.last()
        assertTrue(arc.center?.longitude == 1 && arc.center?.latitude == 2 && arc.radius == 500L && arc.includedAngle == 179)
        assertEquals(2, rules.rules.size)
        val (join, leave) = rules.rules
        assertTrue(join.group == "sip:ga@x" && join.affiliate && join.exit.size == 1 && join.enter.isEmpty() && join.aliases == listOf("sip:fa1@x"))
        assertTrue(!leave.affiliate && leave.enter.size == 1 && leave.constrained == 1 && leave.aliases.isEmpty())
        assertEquals(setOf("sip:ga@x"), rules.manualDeaffiliationLocked)
        assertTrue(ProfileRules.parse("<mcptt-user-profile xmlns=\"urn:3gpp:mcptt:user-profile:1.0\"><OnNetwork/></mcptt-user-profile>").empty)
        // two corners: refused
        val bad = mcpttProfile.replaceFirst(corner(45.0013, 10.001), "").replaceFirst(corner(45.0013, 9.999), "")
        assertTrue(runCatching { ProfileRules.parse(bad) }.isFailure)
        assertTrue(runCatching { ProfileRules.parse("<other xmlns=\"urn:x\"/>") }.isFailure)
    }

    @Test fun mcdataProfileRules() {
        val rules = ProfileRules.parse(mcdataProfile)
        assertEquals(listOf("sip:fa1@x"), rules.aliases.map { it.alias })
        assertTrue(rules.rules.size == 2 && rules.rules[0].group == "sip:ga@x" && rules.rules[0].affiliate && rules.rules[0].exit.size == 1 &&
            !rules.rules[1].affiliate && rules.rules[1].enter.size == 1)
        assertEquals(setOf("sip:ga@x"), rules.manualDeaffiliationLocked)
    }

    @Test fun mcvideoProfileAliasesOnly() {
        val video = mcpttProfile.replace("urn:3gpp:mcptt:user-profile:1.0", "urn:3gpp:ns:mcvideo:user-profile:1.0")
            .replace("mcptt-user-profile", "mcvideo-user-profile")
        val rules = ProfileRules.parse(video)
        assertTrue(rules.aliases.size == 2 && rules.rules.isEmpty())
    }

    @Test fun groupGeographicAreas() {
        val gi = polygon("gi:")
        val areas = GroupAreas.parse(groupDoc("<gi:mandatory-geographic-area>$gi$gi</gi:mandatory-geographic-area>"))
        assertTrue(areas.mandatory.size == 2 && areas.permitted.isEmpty() && inside(areas.mandatory.first(), inArea.first, inArea.second))
        assertTrue(GroupAreas.parse(groupDoc("")).mandatory.isEmpty())
        assertTrue(runCatching { GroupAreas.parse(groupDoc("<gi:permitted-geographic-area/>")) }.isFailure)
    }

    /** §9A.2.1.4: entering the activation area activates, leaving the deactivation area deactivates; the first position only places. */
    @Test fun locationBasedFunctionalAlias() {
        val e = LocationRuleEngine().apply { setRules(ProfileRules.parse(mcpttProfile)) }
        assertTrue(e.at(inArea).none) // the first position: no crossing
        assertTrue(e.at(outArea).activate.isEmpty())
        val enter = e.at(inArea)
        assertEquals(listOf("sip:fa1@x"), enter.activate)
        assertTrue("sip:fa1@x" in e.aliasesByLocation)
        assertEquals("deactivation of sip:fa1@x is not allowed while its location criteria are met", e.manualDeactivationRefusal("sip:fa1@x"))
        // Already active: nothing asked again.
        assertTrue(e.at(outArea, setOf("sip:fa1@x")).deactivate == listOf("sip:fa1@x"))
        assertNull(e.manualDeactivationRefusal("sip:fa1@x"))
        assertTrue(e.at(inArea, setOf("sip:fa1@x")).activate.isEmpty())
        // Not authorised: nothing sent, the crossing still noted.
        assertTrue(e.position(outArea.first, outArea.second, emptySet(), emptySet(), emptyList(), authorised = false).none)
    }

    /** §9.2.1.7.2: the de-affiliation rule on entry; the affiliation rule on exit needs its alias criterion. */
    @Test fun rulesBasedAffiliation() {
        val e = LocationRuleEngine().apply { setRules(ProfileRules.parse(mcpttProfile)) }
        e.at(outArea)
        val enter = e.at(inArea)
        assertEquals(listOf("sip:ga@x"), enter.deaffiliate)
        assertTrue(enter.affiliate.isEmpty())
        // Leaving without sip:fa1@x active: the affiliation rule is not fulfilled.
        assertTrue(e.at(outArea).affiliate.isEmpty())
        e.at(inArea)
        val back = e.at(outArea, active = setOf("sip:fa1@x"))
        assertEquals(listOf("sip:ga@x"), back.affiliate)
        // §9.2.1.2: held by the rule and locked by the profile.
        assertEquals("de-affiliation from sip:ga@x is not allowed while its affiliation rules are met", e.manualDeaffiliationRefusal("sip:ga@x"))
        assertNull(e.manualDeaffiliationRefusal("sip:gb@x"))
        // Entering again: the de-affiliation rule releases the lock.
        assertEquals(listOf("sip:ga@x"), e.at(inArea).deaffiliate)
        assertNull(e.manualDeaffiliationRefusal("sip:ga@x"))
    }

    /** §9.2.1.7.2: an alias of a rule becoming active fulfils it when its location criterion is met at the last position. */
    @Test fun aliasCriterionOnActivation() {
        val e = LocationRuleEngine().apply { setRules(ProfileRules.parse(mcpttProfile)) }
        assertTrue(e.aliasesChanged(emptySet(), setOf("sip:fa1@x")).none) // no position: the exit criterion is not met
        e.at(outArea)
        assertEquals(listOf("sip:ga@x"), e.aliasesChanged(emptySet(), setOf("sip:fa1@x")).affiliate)
        assertTrue(e.aliasesChanged(setOf("sip:fa1@x"), setOf("sip:fa1@x")).none)
        e.at(inArea)
        assertTrue(e.aliasesChanged(emptySet(), setOf("sip:fa1@x")).affiliate.isEmpty()) // inside: the exit rule not met
    }

    /** Both an affiliation and a de-affiliation rule fulfilled at once for a group: nothing. */
    @Test fun conflictingRules() {
        val e = LocationRuleEngine().apply {
            setRules(ProfileRules(rules = listOf(
                AffiliationRule("sip:g@x", true, enter = listOf(GroupAreas.parse(groupDoc("<gi:mandatory-geographic-area>${polygon("gi:")}</gi:mandatory-geographic-area>")).mandatory.first())),
                AffiliationRule("sip:g@x", false, enter = ProfileRules.parse(mcpttProfile).rules[1].enter))))
        }
        e.at(outArea)
        assertTrue(e.at(inArea).none)
    }

    /** §9.2.1.7.3: outside the mandatory area de-affiliate, back inside affiliate again; outside the permitted area no affiliation. */
    @Test fun groupConfigurationRules() {
        val gi = polygon("gi:")
        val e = LocationRuleEngine()
        e.setGroupAreas("sip:ga@x", GroupAreas.parse(groupDoc("<gi:mandatory-geographic-area>$gi</gi:mandatory-geographic-area>")))
        assertTrue(e.allowedHere("sip:ga@x")) // no position yet
        assertTrue(e.at(inArea, groups = listOf("sip:ga@x", "sip:gb@x")).none)
        val left = e.at(outArea, groups = listOf("sip:ga@x", "sip:gb@x"))
        assertEquals(listOf("sip:ga@x"), left.deaffiliate)
        assertEquals("outside the geographic area of sip:ga@x", e.manualAffiliationRefusal("sip:ga@x"))
        // still outside, no longer wanted by the PUBLISH: nothing again
        assertTrue(e.at(outArea.first + 0.0001 to outArea.second, groups = listOf("sip:gb@x")).none)
        assertEquals(listOf("sip:ga@x"), e.at(inArea, groups = listOf("sip:gb@x")).affiliate)
        assertNull(e.manualAffiliationRefusal("sip:ga@x"))
        // permitted area: a rule's affiliation outside it is refused
        val p = LocationRuleEngine()
        p.setRules(ProfileRules(rules = listOf(AffiliationRule("sip:ga@x", true, exit = ProfileRules.parse(mcpttProfile).rules[0].exit))))
        p.setGroupAreas("sip:ga@x", GroupAreas.parse(groupDoc("<gi:permitted-geographic-area>$gi</gi:permitted-geographic-area>")))
        p.at(inArea)
        val refused = p.at(outArea)
        assertTrue(refused.affiliate.isEmpty() && refused.notes.any { "refused" in it })
        // without group areas applied (MCData): nothing
        val d = LocationRuleEngine(groupAreasApply = false)
        d.setGroupAreas("sip:ga@x", GroupAreas.parse(groupDoc("<gi:mandatory-geographic-area>$gi</gi:mandatory-geographic-area>")))
        d.at(inArea, groups = listOf("sip:ga@x"))
        assertTrue(d.at(outArea, groups = listOf("sip:ga@x")).none)
    }

    /** TS 24.282 §22.2.1.4, §8.2.6.2 on the MCData profile. */
    @Test fun mcdataRules() {
        val e = LocationRuleEngine(groupAreasApply = false).apply { setRules(ProfileRules.parse(mcdataProfile)) }
        e.at(outArea)
        val enter = e.at(inArea)
        assertEquals(listOf("sip:fa1@x"), enter.activate)
        assertEquals(listOf("sip:ga@x"), enter.deaffiliate)
        val leave = e.at(outArea, setOf("sip:fa1@x"))
        assertEquals(listOf("sip:fa1@x"), leave.deactivate)
        assertEquals(listOf("sip:ga@x"), leave.affiliate)
        assertFalse(e.manualDeaffiliationRefusal("sip:ga@x") == null)
        e.reset()
        assertNull(e.manualDeaffiliationRefusal("sip:ga@x"))
        assertTrue(e.at(inArea).none) // after a reset: placed again first
    }
}
