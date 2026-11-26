//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.children
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text
import org.w3c.dom.Element

// The transmission control of TS 24.282 V19.7.0 §11.1 and the group pre-checks of the SDS and FD procedures, from the
// MCData service configuration document (TS 24.484 V19.6.0 §10.4.2.1) and the group document (TS 24.481 §7.2.2, §7.2.4),
// as the Linux client applies them (linux/src/client/mcdata/protection.cpp mcdata_group_sds_allowed,
// mcdata_group_fd_allowed; file_distribution.cpp mcdata_one_to_one_fd_allowed; dispatch.cpp, the SDS plane).

/**
 * TS 24.484 §10.4.2.1 item 2: the `<tx-and-rx-control>` of `<on-network>` in the MCData service configuration document:
 * a) `<max-data-size-sds-bytes>`, b) `<max-payload-size-sds-cplane-bytes>` (the signalling-plane limit of TS 24.282
 * §9.2.1.1), c) `<max-data-size-fd-bytes>`, d) `<max-data-size-auto-recv-bytes>`; each null when absent.
 */
data class McdataTxLimits(val maxDataSizeSds: Long? = null, val maxPayloadSizeSdsCplane: Long? = null,
                          val maxDataSizeFd: Long? = null, val maxDataSizeAutoRecv: Long? = null) {
    companion object {
        fun parse(text: String): McdataTxLimits {
            val root = Xml.parse(text)
            if (root.localName != "service-configuration-info") throw McdataCodecError("not a service configuration document")
            val control = root.descendants("on-network").firstOrNull()?.descendants("tx-and-rx-control")?.firstOrNull()
                ?: return McdataTxLimits()
            fun value(name: String): Long? = control.descendants(name).firstOrNull()?.text()?.let {
                it.toLongOrNull()?.takeIf { n -> n in 0..0xFFFFFFFFL } ?: throw McdataCodecError("<$name> is not an xs:unsignedInt: $it")
            }
            return McdataTxLimits(value("max-data-size-sds-bytes"), value("max-payload-size-sds-cplane-bytes"),
                value("max-data-size-fd-bytes"), value("max-data-size-auto-recv-bytes"))
        }
    }
}

/**
 * The MCData elements of a group document (TS 24.481 §7.2.2 table 7.2.2-1, the `<list-service>`): <mcdata-allow-short-
 * data-service> (the AllowedSDS of TS 24.483, absent "false"), <mcdata-on-network-max-data-size-for-SDS>,
 * <preconfigured-group-use-only>, <mcdata-allow-file-distribution> (absent: allowed, as TS 24.282 §10.2.1.3.4 tests it
 * "set to "false""), <mcdata-on-network-max-data-size-for-FD>, and the rules of the `<cp:ruleset>` for
 * <mcdata-allow-transmit-data-in-this-group>.
 */
class McdataGroupRules(
    val allowSds: Boolean = false, val maxSdsBytes: Long? = null, val preconfiguredUseOnly: Boolean = false,
    val allowFd: Boolean = true, val maxFdBytes: Long? = null,
    private val rules: List<Rule> = emptyList(), private val members: List<Set<String>> = emptyList(),
) {
    /** A `<cp:rule>`: its `<identity>` ids (null: no such condition), whether it asks `<is-list-member>`, its true actions. */
    class Rule(val identities: Set<String>?, val listMember: Boolean, val other: Boolean, val actions: Set<String>)

    /**
     * TS 24.481 §7.2.2 with RFC 4745 §10: whether [action] is granted to the user known by [identities] (its "uri" in the
     * group's `<entry>` or its `<mcdata-mcdata-id>`): a rule applies when all its conditions match (an unknown one never
     * does, §7.2.8); a boolean permission is granted when an applying rule sets it.
     */
    fun allows(action: String, identities: Collection<String>): Boolean {
        val mine = identities.filter { it.isNotEmpty() }.toSet()
        val member = members.any { it.any(mine::contains) }
        return rules.any { r ->
            !r.other && (r.identities == null || r.identities.any(mine::contains)) && (!r.listMember || member) && action in r.actions
        }
    }

    /**
     * TS 24.282 §11.1 2), 5), §9.2.2.2.1 3)a), §9.2.3.2.3 / §9.2.4.2.3 8)a), a1): why a group SDS of [size] octets is
     * refused ([mediaPlane]: over the media plane, [session]: an SDS session), null when allowed.
     */
    fun sdsRefusal(size: Long, mediaPlane: Boolean, session: Boolean, identities: Collection<String>): String? = when {
        !allows(TRANSMIT, identities) -> TRANSMIT
        !allowSds -> "mcdata-allow-short-data-service"
        maxSdsBytes != null && size > maxSdsBytes -> "mcdata-on-network-max-data-size-for-SDS"
        preconfiguredUseOnly && (mediaPlane || session) -> "preconfigured-group-use-only"
        else -> null
    }

    /** TS 24.282 §10.2.4.2.1 / §10.2.5.2.3 9)a), a1) and §11.1 2), 6): why a group FD of [size] octets is refused, or null. */
    fun fdRefusal(size: Long, identities: Collection<String>): String? = when {
        preconfiguredUseOnly -> "preconfigured-group-use-only"
        !allows(TRANSMIT, identities) -> TRANSMIT
        !allowFd -> "mcdata-allow-file-distribution"
        maxFdBytes != null && size > maxFdBytes -> "mcdata-on-network-max-data-size-for-FD"
        else -> null
    }

    companion object {
        const val TRANSMIT = "mcdata-allow-transmit-data-in-this-group"

        fun parse(text: String): McdataGroupRules {
            val root = Xml.parse(text)
            val service = root.descendants("list-service").firstOrNull() ?: throw McdataCodecError("no <list-service>")
            fun element(name: String) = service.descendants(name).firstOrNull()
            fun flag(name: String, absent: Boolean): Boolean = element(name)?.text()?.let {
                when (it) { "true", "1" -> true; "false", "0" -> false; else -> throw McdataCodecError("<$name> is not a boolean: $it") }
            } ?: absent
            fun number(name: String): Long? = element(name)?.text()?.let {
                it.toLongOrNull()?.takeIf { n -> n in 0..0xFFFFFFFFL } ?: throw McdataCodecError("<$name> is not an xs:unsignedInt: $it")
            }
            val members = service.descendants("list").flatMap { it.children("entry") }.map { e ->
                (listOf(e.getAttribute("uri")) + e.children().filter { it.localName == "mcdata-mcdata-id" || it.localName == "mcvideo-mcvideo-id" }
                    .map { it.getAttribute("uri") }).filter { it.isNotEmpty() }.toSet()
            }
            val rules = service.descendants("ruleset").flatMap { it.children("rule") }.map { rule -> parseRule(rule) }
            return McdataGroupRules(flag("mcdata-allow-short-data-service", false), number("mcdata-on-network-max-data-size-for-SDS"),
                flag("preconfigured-group-use-only", false), flag("mcdata-allow-file-distribution", true),
                number("mcdata-on-network-max-data-size-for-FD"), rules, members)
        }

        private fun parseRule(rule: Element): Rule {
            var identities: Set<String>? = null
            var listMember = false
            var other = false
            rule.child("conditions")?.children()?.forEach { c ->
                when (c.localName) {
                    "identity" -> identities = (identities ?: emptySet()) + c.children("one").map { it.getAttribute("id") }
                    "is-list-member" -> listMember = true
                    else -> other = true
                }
            }
            val actions = rule.child("actions")?.children()?.filter { it.text() in setOf("true", "1") }?.map { it.localName }?.toSet() ?: emptySet()
            return Rule(identities, listMember, other, actions)
        }

        /**
         * TS 24.282 §9.2.2.2.1 3)b)iii): the <associated-group-id> of an SDS to a temporary group: a constituent group
         * (TS 24.481 §7.2.4.2 <on-network-temporary>) the user is a member of: [preferred] first, then one of [groups],
         * else the first listed (the GMS forms the temporary group of the user's groups); null for no constituents.
         */
        fun associatedGroup(constituents: List<String>, preferred: String?, groups: Collection<String>): String? =
            constituents.firstOrNull { it == preferred } ?: constituents.firstOrNull { it in groups } ?: constituents.firstOrNull()
    }
}

/**
 * The transmission control of TS 24.282 §11.1 on the running client: the MCData service configuration's limits and the
 * group document as the GMS has it now (read for each request, as the Linux client: the document may have changed since
 * the login). Each check throws an [SdsError] naming the element that refuses; without a readable document the server
 * decides.
 */
internal object McdataTransmission {
    /** The identities a group document may name this user by (its MCData ID, its MCPTT ID, its IMPU; TS 24.481 §7.2.2). */
    fun identities(client: org.openmcx.client.client.McxClient): List<String> =
        listOfNotNull(client.config.mcdata?.userId, client.config.mcptt?.userId, client.config.sip.publicUserId)

    /** TS 24.282 §11.1 2), 5), §9.2.2.2.1 3)a), §9.2.3.2.3, §9.2.4.2.3 8)a), a1): a group SDS; returns the document read. */
    suspend fun groupSds(client: org.openmcx.client.client.McxClient, group: String, size: Long, mediaPlane: Boolean, session: Boolean): String? {
        val text = client.groupDocumentText(group) ?: return null
        val reason = try { McdataGroupRules.parse(text).sdsRefusal(size, mediaPlane, session, identities(client)) }
            catch (e: Exception) { "unreadable group document: ${e.message}" } ?: return text
        client.event("mcdata: group SDS not allowed on $group: $reason")
        throw SdsError("group SDS not allowed on $group ($reason, TS 24.282 §11.1)")
    }

    /**
     * TS 24.282 §10.2.4.2.1 / §10.2.5.2.3 9)a), a1) and §11.1 2), 6) for a group FD; §11.1 4) for a one-to-one FD larger
     * than the service configuration's <max-data-size-fd-bytes> (TS 24.484 §10.4.2.1 2c)).
     */
    suspend fun fd(client: org.openmcx.client.client.McxClient, target: String, group: Boolean, size: Long) {
        if (!group) {
            val max = client.mcdataTxLimits?.maxDataSizeFd ?: return
            if (size <= max) return
            client.event("mcdata: FD of $size octets not allowed: <max-data-size-fd-bytes> $max (TS 24.282 §11.1 4))")
            throw SdsError("FD larger than <max-data-size-fd-bytes> $max (TS 24.282 §11.1 4))")
        }
        val text = client.groupDocumentText(target) ?: return
        val reason = try { McdataGroupRules.parse(text).fdRefusal(size, identities(client)) }
            catch (e: Exception) { "unreadable group document: ${e.message}" } ?: return
        client.event("mcdata: group FD not allowed on $target: $reason")
        throw SdsError("group FD not allowed on $target ($reason, TS 24.282 §10.2.4.2.1, §11.1)")
    }
}
