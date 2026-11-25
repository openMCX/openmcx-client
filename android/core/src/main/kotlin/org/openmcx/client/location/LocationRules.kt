//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.text
import org.w3c.dom.Element

/**
 * The location rules of a user profile and of the group documents, and when the client's position meets them:
 * - location based functional alias status change: TS 24.379 §9A.2.1.4, TS 24.282 §22.2.1.4, TS 24.281 §20.2.1.4;
 * - rules based affiliation status change by the user profile's rules: TS 24.379 §9.2.1.7.2, TS 24.282 §8.2.6.2;
 * - the group documents' geographic areas: TS 24.379 §9.2.1.7.3;
 * - the suppression of a manual de-affiliation (TS 24.379 §9.2.1.2, TS 24.282 §8.2.2) and of a manual functional alias
 *   deactivation (TS 24.282 §22.2.1.2, TS 24.281 §20.2.1.2) that a rule holds.
 *
 * Ported from the Linux client (mcptt/location_rules.cpp, mcdata/location_rules.cpp) and the server library's
 * profile readers (src/protocol/profiles.cpp: parse_mcptt_profile_rules, parse_mcdata_profile_rules,
 * parse_group_geographic_areas). Pure: [LocationRuleEngine] says what to do, the caller sends it.
 */

class RulesError(message: String) : Exception("location rules: $message")

/**
 * TS 24.484 §8.3.2.1 item 10 (and §9.3.2.1, §10.3.2.1): a functional alias of `<FunctionalAliasList>` with the location
 * criteria of its `<anyExt>`: the areas whose entry or exit activates (`<LocationCriteriaForActivation>`) or deactivates
 * (`<LocationCriteriaForDeactivation>`) it, and `<manual-deactivation-not-allowed-if-location-criteria-met>`.
 */
data class AliasAreaRule(val alias: String, val activateOnEntry: List<Area> = emptyList(), val activateOnExit: List<Area> = emptyList(),
                         val deactivateOnEntry: List<Area> = emptyList(), val deactivateOnExit: List<Area> = emptyList(),
                         val manualDeactivationLocked: Boolean = false) {
    val hasLocationCriteria get() = activateOnEntry.isNotEmpty() || activateOnExit.isNotEmpty() ||
        deactivateOnEntry.isNotEmpty() || deactivateOnExit.isNotEmpty()
}

/**
 * TS 24.484 §8.3.2.1 item 10 e), f) and §8.3.2.7: a `<RulesForAffiliation>` ([affiliate]) or `<RulesForDeaffiliation>`
 * rule of a group's entry: the areas of its `<ListOfLocationCriteria>` (an area with a `<Speed>` or `<Heading>` in its
 * `<anyExt>` only counted in [constrained]: no speed or heading is known, so it is never met) and the aliases of its
 * `<ListOfActiveFunctionalAliasCriteria>`.
 */
data class AffiliationRule(val group: String, val affiliate: Boolean, val enter: List<Area> = emptyList(), val exit: List<Area> = emptyList(),
                           val constrained: Int = 0, val aliases: List<String> = emptyList()) {
    val noLocation get() = enter.isEmpty() && exit.isEmpty() && constrained == 0
}

/** The location rules of one user profile: aliases with location criteria, affiliation rules, de-affiliation locks. */
data class ProfileRules(val aliases: List<AliasAreaRule> = emptyList(), val rules: List<AffiliationRule> = emptyList(),
                        val manualDeaffiliationLocked: Set<String> = emptySet()) {
    val empty get() = aliases.none { it.hasLocationCriteria } && rules.isEmpty() && manualDeaffiliationLocked.isEmpty()

    companion object {
        const val MCPTT_NS = "urn:3gpp:mcptt:user-profile:1.0"
        const val MCDATA_NS = "urn:3gpp:ns:mcdata:user-profile:1.0"
        const val MCVIDEO_NS = "urn:3gpp:ns:mcvideo:user-profile:1.0"

        /**
         * The rules of an MCPTT (`<MCPTTGroupInfo>` `<entry>`: group in `<uri-entry>`, rules in its `<anyExt>`), MCData
         * (`<MCDataGroupInfo>`: group in `<MCData-Group-ID>/<uri-entry>`, rules in its `<anyExt>`, TS 24.484 §10.3.2.1
         * item 9 b vii) or MCVideo user profile (aliases only); the aliases from `<OnNetwork>/<anyExt>/<FunctionalAliasList>`.
         */
        fun parse(text: String): ProfileRules {
            val root = Xml.parse(text)
            val ns = root.namespaceURI
            if (ns !in setOf(MCPTT_NS, MCDATA_NS, MCVIDEO_NS) || !root.localName.endsWith("-user-profile")) throw RulesError("not a user profile")
            val aliases = mutableListOf<AliasAreaRule>()
            val rules = mutableListOf<AffiliationRule>()
            val locked = mutableSetOf<String>()
            for (on in root.kids("OnNetwork", ns)) {
                for (ext in on.kids("anyExt", ns)) for (list in ext.kids("FunctionalAliasList", ns)) for (entry in list.kids("entry", ns)) {
                    if (aliases.size >= 256) throw RulesError("too many functional aliases")
                    aliases += alias(entry, ns)
                }
                when (ns) {
                    MCPTT_NS -> for (info in on.kids("MCPTTGroupInfo", ns)) for (entry in info.kids("entry", ns)) {
                        val ext = entry.kids("anyExt", ns).firstOrNull() ?: continue
                        val group = entry.kids("uri-entry", ns).firstOrNull()?.text().orEmpty()
                        if (group.isEmpty()) throw RulesError("an MCPTT group entry without its URI")
                        groupRules(ext, ns, group, rules, locked)
                    }
                    MCDATA_NS -> for (info in on.kids("MCDataGroupInfo", ns)) {
                        val ext = info.kids("anyExt", ns).firstOrNull() ?: continue
                        val group = info.kids("MCData-Group-ID", ns).firstOrNull()?.kids("uri-entry", ns)?.firstOrNull()?.text().orEmpty()
                        if (group.isEmpty()) throw RulesError("an MCData group entry without its URI")
                        groupRules(ext, ns, group, rules, locked)
                    }
                }
            }
            return ProfileRules(aliases, rules, locked)
        }

        private fun alias(entry: Element, ns: String): AliasAreaRule {
            val uri = entry.kids("uri-entry", ns).firstOrNull()?.text().orEmpty()
            if (uri.isEmpty()) throw RulesError("a functional alias entry without its URI")
            val out = mutableMapOf<String, MutableList<Area>>()
            var locked = false
            for (ext in entry.kids("anyExt", ns)) for (node in ext.children()) {
                if (node.namespaceURI != ns) continue
                when (node.localName) {
                    "LocationCriteriaForActivation", "LocationCriteriaForDeactivation" -> for (area in node.children()) {
                        val kind = area.localName.takeIf { it == "EnterSpecificArea" || it == "ExitSpecificArea" } ?: continue
                        val list = out.getOrPut(node.localName + kind) { mutableListOf() }
                        if (list.size >= 16) throw RulesError("too many functional alias location criteria")
                        list += profileArea(area, ns)
                    }
                    "manual-deactivation-not-allowed-if-location-criteria-met" -> locked = node.text() in setOf("true", "1")
                }
            }
            fun get(k: String) = out["LocationCriteriaFor$k"].orEmpty()
            return AliasAreaRule(uri, get("ActivationEnterSpecificArea"), get("ActivationExitSpecificArea"),
                get("DeactivationEnterSpecificArea"), get("DeactivationExitSpecificArea"), locked)
        }

        private fun groupRules(ext: Element, ns: String, group: String, rules: MutableList<AffiliationRule>, locked: MutableSet<String>) {
            for (node in ext.children()) {
                if (node.namespaceURI != ns) continue
                when (node.localName) {
                    "RulesForAffiliation", "RulesForDeaffiliation", "RulesForDeAffiliation" -> {
                        if (rules.size >= 256) throw RulesError("too many affiliation rules")
                        val enter = mutableListOf<Area>(); val exit = mutableListOf<Area>(); var constrained = 0
                        val aliases = mutableListOf<String>()
                        for (part in node.children()) when (part.localName) {
                            "ListOfLocationCriteria" -> for (area in part.children()) {
                                val entering = area.localName == "EnterSpecificArea"
                                if (!entering && area.localName != "ExitSpecificArea") continue
                                if (enter.size + exit.size + constrained >= 32) throw RulesError("too many location criteria")
                                val ax = area.kids("anyExt", ns).firstOrNull()
                                if (ax != null && (ax.kids("Speed", ns).isNotEmpty() || ax.kids("Heading", ns).isNotEmpty())) { constrained++; continue }
                                (if (entering) enter else exit) += profileArea(area, ns)
                            }
                            "ListOfActiveFunctionalAliasCriteria" -> for (entry in part.kids("entry", ns)) {
                                val uri = entry.kids("uri-entry", ns).firstOrNull()?.text().orEmpty()
                                if (uri.isEmpty()) throw RulesError("a functional alias criterion without its URI")
                                aliases += uri
                            }
                        }
                        rules += AffiliationRule(group, node.localName == "RulesForAffiliation", enter, exit, constrained, aliases)
                    }
                    "manual-deaffiliation-not-allowed-if-affiliation-rules-are-met" -> if (node.text() in setOf("true", "1")) locked += group
                }
            }
        }
    }
}

/** TS 24.481 §7.2.4.2 and §7.2.8: the `<permitted-geographic-area>` and `<mandatory-geographic-area>` of a group document. */
data class GroupAreas(val permitted: List<Area> = emptyList(), val mandatory: List<Area> = emptyList()) {
    companion object {
        const val GROUP_INFO_NS = "urn:3gpp:ns:mcpttGroupInfo:1.0"

        /** The areas of a group document's `<list-service>` (each a union of polygons and ellipsoid arcs). */
        fun parse(text: String): GroupAreas {
            val root = Xml.parse(text)
            val service = root.children("list-service").firstOrNull() ?: throw RulesError("no list-service")
            fun read(name: String): List<Area> {
                val element = service.kids(name, GROUP_INFO_NS).firstOrNull() ?: return emptyList()
                val areas = element.children().filter { it.namespaceURI == GROUP_INFO_NS && it.localName in setOf("PolygonArea", "EllipsoidArcArea") }
                    .map { shape(it, GROUP_INFO_NS) }
                if (areas.isEmpty()) throw RulesError("$name without an area")
                if (areas.size > 32) throw RulesError("too many areas in a group geographic area")
                return areas
            }
            return GroupAreas(read("permitted-geographic-area"), read("mandatory-geographic-area"))
        }
    }
}

private fun Element.kids(name: String, ns: String) = children(name).filter { it.namespaceURI == ns }

private fun coded(parent: Element, name: String, ns: String, max: Long): Long {
    val value = parent.kids(name, ns).firstOrNull()?.text() ?: throw RulesError("no <$name>")
    return value.toLongOrNull()?.takeIf { value.length <= 10 && it in 0..max } ?: throw RulesError("invalid <$name> '$value'")
}

private fun point(parent: Element, ns: String) =
    Point(coded(parent, "Latitude", ns, 0xffffff).toInt(), coded(parent, "Longitude", ns, 0xffffff).toInt())

/** A `<PolygonArea>` (3 to 15 `<Corner>`s) or an `<EllipsoidArcArea>` as TS 24.484 codes them (TS 23.032 codes). */
private fun shape(shape: Element, ns: String): Area = when (shape.localName) {
    "PolygonArea" -> Area(polygon = shape.kids("Corner", ns).map { point(it, ns) }).also {
        if (it.polygon.size !in 3..15) throw RulesError("a polygon has 3 to 15 corners")
    }
    "EllipsoidArcArea" -> Area(center = point(shape.kids("Center", ns).firstOrNull() ?: throw RulesError("no <Center>"), ns),
        radius = coded(shape, "Radius", ns, 0xffffffffL), offsetAngle = coded(shape, "OffsetAngle", ns, 255).toInt(),
        includedAngle = coded(shape, "IncludedAngle", ns, 255).toInt())
    else -> throw RulesError("an area is a polygon or an ellipsoid arc")
}

/** A GeographicalAreaType element (`<EnterSpecificArea>`, ...): its `<PolygonArea>` or `<EllipsoidArcArea>`. */
private fun profileArea(node: Element, ns: String): Area {
    val found = node.children().firstOrNull { it.namespaceURI == ns && it.localName in setOf("PolygonArea", "EllipsoidArcArea") }
        ?: throw RulesError("an area is a polygon or an ellipsoid arc")
    return shape(found, ns)
}

/** What a position or an alias change asks the client to do, and why (for the event log and the UI). */
data class RuleActions(val activate: List<String> = emptyList(), val deactivate: List<String> = emptyList(),
                       val affiliate: List<String> = emptyList(), val deaffiliate: List<String> = emptyList(),
                       val notes: List<String> = emptyList()) {
    val none get() = activate.isEmpty() && deactivate.isEmpty() && affiliate.isEmpty() && deaffiliate.isEmpty()
    operator fun plus(o: RuleActions) = RuleActions(activate + o.activate, deactivate + o.deactivate, affiliate + o.affiliate,
        deaffiliate + o.deaffiliate, notes + o.notes)
}

/**
 * The location rules of one MC service at the client. Areas are evaluated from the second position on: the first
 * only says where the client is (an area is entered or left by a crossing, as the Linux client has it).
 */
class LocationRuleEngine(val groupAreasApply: Boolean = true) {
    @Volatile var rules: ProfileRules = ProfileRules()
        private set
    private val groupAreas = mutableMapOf<String, GroupAreas>()
    private val inside = mutableMapOf<String, Boolean>()
    @Volatile var lastPosition: Pair<Double, Double>? = null
        private set
    /** Groups left for being outside their mandatory (or permitted) area, affiliated again when back (§9.2.1.7.3). */
    val areaLeftGroups: MutableSet<String> = linkedSetOf()
    /** Groups a fulfilled rule affiliated (the manual de-affiliation lock of §9.2.1.2 applies to them). */
    val ruleAffiliatedGroups: MutableSet<String> = linkedSetOf()
    /** Aliases a location criterion activated (the manual deactivation lock of TS 24.282 §22.2.1.2 applies to them). */
    val aliasesByLocation: MutableSet<String> = linkedSetOf()

    @Synchronized fun setRules(value: ProfileRules) {
        rules = value.copy(aliases = value.aliases.filter { it.hasLocationCriteria })
        inside.clear()
        aliasLocks = value.aliases.filter { it.manualDeactivationLocked }.map { it.alias }.toSet()
    }
    @Volatile private var aliasLocks: Set<String> = emptySet()

    @Synchronized fun setGroupAreas(group: String, areas: GroupAreas?) {
        if (areas == null || (areas.permitted.isEmpty() && areas.mandatory.isEmpty())) groupAreas.remove(group) else groupAreas[group] = areas
    }
    @Synchronized fun groupAreas(): Map<String, GroupAreas> = groupAreas.toMap()

    /** The client logged off or lost its service: nothing crossed or held any more. */
    @Synchronized fun reset() {
        inside.clear(); areaLeftGroups.clear(); ruleAffiliatedGroups.clear(); aliasesByLocation.clear()
    }

    private fun crossed(key: String, areas: List<Area>, entry: Boolean, lat: Double, lon: Double): Boolean {
        var out = false
        areas.forEachIndexed { index, area ->
            val slot = "$key|$index"
            val now = inside(area, lat, lon)
            val before = inside[slot]
            if (before != null && before != now && now == entry) out = true
            inside[slot] = now
        }
        return out
    }

    /**
     * The position changed. [standing]: the aliases this user has or asked for (an alias already in the state asked for
     * is left as it is); [active]: the aliases active (a rule's functional alias criterion); [groups]: the groups of
     * interest for the group areas (those the client wants affiliated), with [authorised] the service is up.
     */
    @Synchronized fun position(lat: Double, lon: Double, standing: Set<String>, active: Set<String>, groups: Collection<String>,
                               authorised: Boolean = true): RuleActions {
        lastPosition = lat to lon
        val activate = mutableListOf<String>(); val deactivate = mutableListOf<String>(); val notes = mutableListOf<String>()
        // §9A.2.1.4: "If a location criterion for functional alias activation or de-activation is met, the MCPTT client
        // shall initiate the functional alias status change procedure as specified in clause 9A.2.1.2".
        for (rule in rules.aliases) {
            val key = "fa|${rule.alias}"
            val on = crossed("$key|ae", rule.activateOnEntry, true, lat, lon) or crossed("$key|ax", rule.activateOnExit, false, lat, lon)
            val off = crossed("$key|de", rule.deactivateOnEntry, true, lat, lon) or crossed("$key|dx", rule.deactivateOnExit, false, lat, lon)
            val has = rule.alias in standing
            if (!authorised) continue
            if (on && !off && !has) { activate += rule.alias; aliasesByLocation += rule.alias; notes += "functional alias ${rule.alias}: location criterion for activation met" }
            else if (off && !on && has) { deactivate += rule.alias; aliasesByLocation -= rule.alias; notes += "functional alias ${rule.alias}: location criterion for deactivation met" }
        }
        // §9.2.1.7.2 (TS 24.282 §8.2.6.2): "A rule is fulfilled if any of the location criteria and any of the functional
        // alias based criteria are met": an area of the rule entered or left, and one of its aliases active (none listed: met).
        val fulfilled = mutableMapOf<String, Pair<Boolean, Boolean>>()
        rules.rules.forEachIndexed { index, rule ->
            val key = "rule|$index"
            val hit = crossed("$key|e", rule.enter, true, lat, lon) or crossed("$key|x", rule.exit, false, lat, lon)
            if (hit && aliasesMet(rule, active)) fulfilled.mark(rule)
        }
        var out = RuleActions(activate, deactivate, notes = notes)
        if (authorised) out += apply(fulfilled, "location")
        if (authorised && groupAreasApply) out += groupAreasAt(lat, lon, groups)
        return out
    }

    private fun MutableMap<String, Pair<Boolean, Boolean>>.mark(rule: AffiliationRule) {
        val (join, leave) = this[rule.group] ?: (false to false)
        this[rule.group] = if (rule.affiliate) true to leave else join to true
    }

    private fun aliasesMet(rule: AffiliationRule, active: Set<String>) = rule.aliases.isEmpty() || rule.aliases.any { it in active }

    /**
     * §9.2.1.7.2 (TS 24.282 §8.2.6.2: "evaluated ... whenever a functional alias is activated or deactivated"): the
     * active aliases changed from [before] to [after]; a rule one of whose aliases became active is fulfilled when its
     * location criterion is met at the last position (inside an area it enters, outside one it leaves; none: met).
     */
    @Synchronized fun aliasesChanged(before: Set<String>, after: Set<String>): RuleActions {
        aliasesByLocation.retainAll(after)
        val fulfilled = mutableMapOf<String, Pair<Boolean, Boolean>>()
        for (rule in rules.rules) {
            if (rule.aliases.isEmpty() || rule.aliases.none { it in after && it !in before }) continue
            var met = rule.noLocation
            val here = lastPosition
            if (!met && here != null) met = rule.enter.any { inside(it, here.first, here.second) } || rule.exit.any { !inside(it, here.first, here.second) }
            if (met) fulfilled.mark(rule)
        }
        return apply(fulfilled, "functional alias")
    }

    private fun apply(fulfilled: Map<String, Pair<Boolean, Boolean>>, trigger: String): RuleActions {
        val join = mutableListOf<String>(); val leave = mutableListOf<String>(); val notes = mutableListOf<String>()
        for ((group, wanted) in fulfilled) {
            val (affiliate, deaffiliate) = wanted
            if (affiliate == deaffiliate) continue // both at once: nothing
            if (affiliate) {
                if (!allowedHere(group)) { notes += "affiliation rule for $group ($trigger): refused, outside the group's geographic area"; continue }
                join += group; ruleAffiliatedGroups += group
            } else { leave += group; ruleAffiliatedGroups -= group }
            notes += "${if (affiliate) "affiliation" else "de-affiliation"} rule for $group fulfilled ($trigger)"
        }
        return RuleActions(affiliate = join, deaffiliate = leave, notes = notes)
    }

    /**
     * §9.2.1.7.3 at the last position: whether the client may be affiliated to [group]: not outside the group's
     * `<permitted-geographic-area>` ("is allowed to affiliate" only within it) nor its `<mandatory-geographic-area>` (it
     * "shall de-affiliate" there). Without a position or areas: allowed.
     */
    @Synchronized fun allowedHere(group: String): Boolean {
        val areas = groupAreas[group] ?: return true
        val (lat, lon) = lastPosition ?: return true
        fun within(list: List<Area>) = list.isEmpty() || list.any { inside(it, lat, lon) }
        return within(areas.permitted) && within(areas.mandatory)
    }

    /**
     * §9.2.1.7.3: "If the <mandatory-geographic-area> element ... is present and the MCPTT client is not within the area
     * ... the MCPTT client shall de-affiliate from the group": each group of interest is checked; a group left so is
     * affiliated again once the client is back where it may be (the user's interest in it stands).
     */
    private fun groupAreasAt(lat: Double, lon: Double, groups: Collection<String>): RuleActions {
        val join = mutableListOf<String>(); val leave = mutableListOf<String>(); val notes = mutableListOf<String>()
        for (group in (groups + areaLeftGroups).distinct()) {
            val allowed = allowedHere(group)
            if (!allowed && group !in areaLeftGroups) { areaLeftGroups += group; leave += group; notes += "outside the geographic area of $group: de-affiliating" }
            else if (allowed && group in areaLeftGroups) { areaLeftGroups -= group; join += group; notes += "back in the geographic area of $group: affiliating" }
        }
        return RuleActions(affiliate = join, deaffiliate = leave, notes = notes)
    }

    /** §9.2.1.2 (TS 24.282 §8.2.2): why a manual de-affiliation from [group] is suppressed, or null. */
    @Synchronized fun manualDeaffiliationRefusal(group: String): String? =
        if (group in ruleAffiliatedGroups && group in rules.manualDeaffiliationLocked)
            "de-affiliation from $group is not allowed while its affiliation rules are met" else null

    /** TS 24.282 §22.2.1.2, TS 24.281 §20.2.1.2: why a manual deactivation of [alias] is suppressed, or null. */
    @Synchronized fun manualDeactivationRefusal(alias: String): String? =
        if (alias in aliasesByLocation && alias in aliasLocks) "deactivation of $alias is not allowed while its location criteria are met" else null

    /** §9.2.1.7.3: why a manual affiliation to [group] is refused here, or null. */
    fun manualAffiliationRefusal(group: String): String? = if (allowedHere(group)) null else "outside the geographic area of $group"
}
