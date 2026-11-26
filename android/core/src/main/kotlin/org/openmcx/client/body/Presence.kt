//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.body

import java.util.UUID

/** The MC service a presence body belongs to: its affiliation namespace (TS 24.379 §9.3, TS 24.282 §8.4). */
enum class McService(val presNamespace: String, val prefix: String) {
    MCPTT("urn:3gpp:ns:mcpttPresInfo:1.0", "mcpttPI10"),
    MCDATA("urn:3gpp:ns:mcdataPresInfo:1.0", "mcdataPI10"),
    MCVIDEO("urn:3gpp:ns:mcvideoPresInfo:1.0", "mcvideoPI10"),
}

/** poc-settings (RFC 4354, TS 24.379 §7.4.1.2.2), sent at service authorisation. */
object PocSettings {
    const val CONTENT_TYPE = "application/poc-settings+xml"
    private const val NAMESPACE = "urn:oma:params:xml:ns:poc:poc-settings"
    private const val MCS = "urn:3gpp:mcsSettings:1.0"

    /**
     * [answerMode] is `automatic` or `manual` for MCPTT and MCVideo, null for MCData; [profileIndex] the
     * "user-profile-index" of the selected user profile (TS 24.379 §7.2.3 4) b), TS 24.484 §8.3.2).
     */
    fun encode(clientId: String, answerMode: String?, profileIndex: Int = 0): String = Xml.document("poc-settings", NAMESPACE) {
        attr("xmlns:mcs10Set", MCS)
        element("entity") {
            attr("id", clientId)
            if (answerMode != null) element("am-settings") { leaf("answer-mode", answerMode) }
            element("mcs10Set:selected-user-profile-index") { leaf("mcs10Set:user-profile-index", profileIndex.toString()) }
            if (answerMode != null) leaf("mcs10Set:multiplex-support", "false")
        }
    }

    /** The service settings of one client in a poc-settings document (RFC 4354 <entity>, TS 24.379 §7.4.1.2.2). */
    data class Entity(val id: String, val answerMode: String?, val profileIndex: Int?, val multiplexSupport: Boolean?)

    /**
     * TS 24.379 §7.2.4: the entities of a NOTIFY's poc-settings document: per client ("id"), its <am-settings> answer
     * mode and its <selected-user-profile-index>. The 3GPP elements are read in either namespace, as the server does.
     */
    fun parse(body: String): List<Entity> {
        val root = Xml.parse(body)
        if (root.localName != "poc-settings" || root.namespaceURI != NAMESPACE) throw IllegalArgumentException("not a poc-settings document")
        return root.children("entity").map { entity ->
            val id = entity.getAttribute("id").ifEmpty { throw IllegalArgumentException("poc-settings entity without id") }
            Entity(id, entity.child("am-settings")?.childText("answer-mode"),
                entity.child("selected-user-profile-index")?.childText("user-profile-index")?.toIntOrNull(),
                entity.childText("multiplex-support")?.let { it == "true" || it == "1" })
        }
    }
}

/** Affiliation in PIDF (RFC 3863 with the MC presence extensions, TS 24.379 §9.3.1, TS 24.282 §8.4). */
object Affiliation {
    const val CONTENT_TYPE = "application/pidf+xml"
    const val PIDF = "urn:ietf:params:xml:ns:pidf"

    /** A PUBLISH body: the full set of groups this client wants to be affiliated to. */
    fun publish(service: McService, entity: String, clientId: String, groups: Collection<String>, pId: String = UUID.randomUUID().toString()): String =
        Xml.document("presence", PIDF) {
            attr("xmlns:${service.prefix}", service.presNamespace)
            attr("entity", entity)
            element("tuple") {
                attr("id", clientId)
                element("status") { groups.forEach { g -> element("${service.prefix}:affiliation") { attr("group", g) } } }
            }
            leaf("${service.prefix}:p-id", pId)
        }

    /**
     * A NOTIFY body: group to status, from the tuple of [clientId] (§9.3.1.2: one tuple per client of the user; none of
     * ours is no group). The "status" attribute is optional: a group listed without it is "affiliated".
     */
    fun parse(body: String, clientId: String): Map<String, String> {
        val root = Xml.parse(body)
        val ours = root.children("tuple").filter { it.getAttribute("id") == clientId }
        return ours.flatMap { it.descendants("affiliation") }
            .associate { it.getAttribute("group") to it.getAttribute("status").ifEmpty { "affiliated" } }
    }

    /**
     * §9.2.1.3: the per-user affiliation information of a NOTIFY, for every client of the user (one <tuple> per client,
     * §9.3.1.2): client ID to group to status.
     */
    fun parseAll(body: String): Map<String, Map<String, String>> = Xml.parse(body).children("tuple").associate { tuple ->
        tuple.getAttribute("id") to tuple.descendants("affiliation")
            .associate { it.getAttribute("group") to it.getAttribute("status").ifEmpty { "affiliated" } }
    }

    /** §9.2.1.2 step 5 c): the groups a NOTIFY's client is interested in: those it is affiliated to or affiliating. */
    fun interested(groups: Map<String, String>): List<String> = groups.filterValues { counts(it) }.keys.toList()

    /** §9.2.2.2.11 step 3 (as the participating function determines it): a listed group counts unless given up. */
    fun counts(status: String?): Boolean = status != null && status != "deaffiliating" && status != "deaffiliated"

    /** The p-id of a NOTIFY, if any. */
    fun pId(body: String): String? = Xml.parse(body).descendants("p-id").firstOrNull()?.text()
}

/** simple-filter for the affiliation status subscription (RFC 4661, TS 24.379 §9.2.1.3). */
object SimpleFilter {
    const val CONTENT_TYPE = "application/simple-filter+xml"

    fun forClient(service: McService, clientId: String): String = Xml.document("filter-set", "urn:ietf:params:xml:ns:simple-filter") {
        element("ns-bindings") {
            element("ns-binding") { attr("prefix", "pidf"); attr("urn", Affiliation.PIDF) }
            element("ns-binding") { attr("prefix", service.prefix); attr("urn", service.presNamespace) }
        }
        element("filter") {
            attr("id", "f1")
            element("what") { leaf("include", "//pidf:presence/pidf:tuple[@id=\"$clientId\"]") }
        }
    }
}

/**
 * The affiliation command of a negotiated-mode affiliation status change (TS 24.379 §9.2.1.4, §9.2.1.5; annex F.4): a
 * <command-list> with the groups to affiliate to and those to de-affiliate from.
 */
object AffiliationCommand {
    const val NAMESPACE = "urn:3gpp:ns:affiliationCommand:1.0"

    /** Annex F.4.4 (TS 24.281 annex F.4 for MCVideo): the MIME type of the service's command. */
    fun contentType(service: McService) = when (service) {
        McService.MCVIDEO -> "application/vnd.3gpp.mcvideo-affiliation-command+xml"
        else -> "application/vnd.3gpp.mcptt-affiliation-command+xml"
    }

    fun encode(affiliate: List<String>, deaffiliate: List<String>): String = Xml.document("command-list", NAMESPACE) {
        if (affiliate.isNotEmpty()) element("affiliate") { affiliate.forEach { leaf("group", it) } }
        if (deaffiliate.isNotEmpty()) element("de-affiliate") { deaffiliate.forEach { leaf("group", it) } }
    }

    /** The groups for affiliation and for de-affiliation. */
    fun parse(body: String): Pair<List<String>, List<String>> {
        val root = Xml.parse(body)
        if (root.localName != "command-list" || root.namespaceURI != NAMESPACE) throw IllegalArgumentException("not an affiliation command")
        fun groups(name: String) = root.child(name)?.children("group")?.map { it.text() }?.filter { it.isNotEmpty() } ?: emptyList()
        return groups("affiliate") to groups("de-affiliate")
    }
}

/**
 * Per-group dynamic data (TS 24.379 §9.2.1.6): the simple-filter of §9.3.2.2 naming the group and the items asked for,
 * and the PIDF of §9.3.1.2 the NOTIFYs carry, in the service's presence namespace.
 */
object GroupDynamicData {
    /** §9.3.2.2 item 7 B, the values a) to e). */
    val ITEMS = linkedMapOf(
        "status" to "//pidf:presence/pidf:groupStatus/@pidf:ModifiedBy",
        "broadcast" to "//pidf:presence/pidf:additionalData/@pidf:groupBroadcastAlias",
        "regroup" to "//pidf:presence/pidf:additionalData/@pidf:groupRegroupAlias",
        "call" to "//pidf:presence/pidf:additionalData/@pidf:groupCallOngoing",
        "affiliated" to "//pidf:presence/pidf:tuple/pidf:status/pidf:affiliation/@pidf:client [@pidf:status=\"affiliated\"]",
    )

    /**
     * §9.3.2.2: items 1 to 4 the <filter-set> with the "pidf" and the service's bindings, item 5 one <filter> with an
     * "id" and no "uri" or "domain", items 6 and 7 a <what> with the group and one <include> per item asked for (none:
     * every item).
     */
    fun filter(service: McService, group: String, items: Collection<String>, id: String): String =
        Xml.document("filter-set", "urn:ietf:params:xml:ns:simple-filter") {
            element("ns-bindings") {
                element("ns-binding") { attr("prefix", "pidf"); attr("urn", Affiliation.PIDF) }
                element("ns-binding") { attr("prefix", service.prefix); attr("urn", service.presNamespace) }
            }
            element("filter") {
                attr("id", id)
                element("what") {
                    leaf("include", "//pidf:presence[@entity=\"$group\"]")
                    for (item in items) leaf("include", ITEMS[item] ?: throw IllegalArgumentException("unknown item $item"))
                }
            }
        }

    data class Member(val id: String, val clients: List<String>, val functionalAliases: List<String>)
    data class Data(val group: String, val groupStatus: String?, val modifiedBy: String?, val broadcastAlias: String?,
                    val regroupAlias: String?, val callOngoing: Boolean?, val members: List<Member>)

    /** §9.3.1.2: the group (entity), its members' tuples with their clients and aliases, groupStatus, additionalData. */
    fun parse(body: String): Data {
        val root = Xml.parse(body)
        if (root.localName != "presence" || root.namespaceURI != Affiliation.PIDF) throw IllegalArgumentException("not a PIDF document")
        val members = root.children("tuple").map { tuple ->
            val status = tuple.child("status")
            Member(tuple.getAttribute("id"),
                status?.children("affiliation")?.map { it.getAttribute("client") } ?: emptyList(),
                status?.children("functionalAlias")?.map { it.getAttribute("functionalAliasID") } ?: emptyList())
        }
        val status = root.child("groupStatus")
        val extra = root.child("additionalData")
        fun attr(name: String) = extra?.getAttribute(name)?.ifEmpty { null }
        return Data(root.getAttribute("entity"), status?.text()?.ifEmpty { null }, status?.getAttribute("ModifiedBy")?.ifEmpty { null },
            attr("groupBroadcastAlias"), attr("groupRegroupAlias"),
            attr("groupCallOngoing")?.let { it == "true" || it == "1" }, members)
    }
}
