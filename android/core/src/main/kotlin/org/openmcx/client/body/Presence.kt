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

    /** [answerMode] is `automatic` or `manual` for MCPTT and MCVideo, null for MCData. */
    fun encode(clientId: String, answerMode: String?): String = Xml.document("poc-settings", NAMESPACE) {
        attr("xmlns:mcs10Set", MCS)
        element("entity") {
            attr("id", clientId)
            if (answerMode != null) element("am-settings") { leaf("answer-mode", answerMode) }
            element("mcs10Set:selected-user-profile-index") { leaf("mcs10Set:user-profile-index", "0") }
            if (answerMode != null) leaf("mcs10Set:multiplex-support", "false")
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
