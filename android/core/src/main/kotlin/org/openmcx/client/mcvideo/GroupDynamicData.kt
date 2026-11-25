//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.body.Xml
import org.openmcx.client.body.children

/**
 * MCVideo per-group dynamic data (TS 24.281 §8.2.1.6): the subscription's application/simple-filter+xml (§8.3.2.2) and
 * the application/pidf+xml of its NOTIFYs (§8.3.1.2), in the MCVideo presence namespace.
 */
object GroupDynamicData {
    const val EVENT = "presence"
    const val FILTER_TYPE = "application/simple-filter+xml"
    const val PIDF_TYPE = "application/pidf+xml"
    const val FILTER_NS = "urn:ietf:params:xml:ns:simple-filter"
    const val PIDF_NS = "urn:ietf:params:xml:ns:pidf"
    /** TS 24.281 §8.3.1.1: the MCVideo presence namespace, bound to "mcvideoPI10". */
    const val MCVIDEO_NS = "urn:3gpp:ns:mcvideoPresInfo:1.0"

    /** §8.3.2.2 item 7 B: the data items to restrict the notifications to, in the order a) to e). */
    enum class Item(val value: String) {
        STATUS("//pidf:presence/pidf:groupStatus/@pidf:ModifiedBy"),
        BROADCAST("//pidf:presence/pidf:additionalData/@pidf:groupBroadcastAlias"),
        REGROUP("//pidf:presence/pidf:additionalData/@pidf:groupRegroupAlias"),
        CALL("//pidf:presence/pidf:additionalData/@pidf:groupCallOngoing"),
        AFFILIATED("//pidf:presence/pidf:tuple/pidf:status/pidf:affiliation/@pidf:client [@pidf:status=\"affiliated\"]"),
    }

    /**
     * §8.3.2.2 items 1 to 7: a <filter-set> with the "pidf" and "mcvideoPI10" bindings, one <filter> with an "id" and no
     * "uri" or "domain", and a <what> with an <include> naming the group's presence, then one per item asked for (none:
     * all the dynamic data).
     */
    fun filter(group: String, id: String, items: Collection<Item> = emptyList()): String = Xml.document("filter-set", FILTER_NS) {
        element("ns-bindings") {
            element("ns-binding") { attr("prefix", "pidf"); attr("urn", PIDF_NS) }
            element("ns-binding") { attr("prefix", "mcvideoPI10"); attr("urn", MCVIDEO_NS) }
        }
        element("filter") {
            attr("id", id)
            element("what") {
                leaf("include", "//pidf:presence[@entity=\"$group\"]")
                for (item in Item.entries) if (item in items) leaf("include", item.value)
            }
        }
    }

    /** One member of the group (§8.3.1.2 items 9 to 12): its MCVideo ID, its affiliated clients and its functional aliases. */
    data class Member(val id: String, val clients: List<String>, val functionalAliases: List<String>)

    /** The group's dynamic data: its status (and who changed it), aliases, whether a call is ongoing, and its members. */
    data class State(val group: String?, val status: String?, val modifiedBy: String?, val broadcastAlias: String?,
                     val regroupAlias: String?, val callOngoing: Boolean?, val members: List<Member>)

    /** §8.3.1.2: the <presence> of the group, its elements in the MCVideo presence namespace. */
    fun parse(text: String): State {
        val root = Xml.parse(text)
        if (root.localName != "presence" || root.namespaceURI != PIDF_NS) throw IllegalArgumentException("not a PIDF presence document")
        fun org.w3c.dom.Element.attr(name: String) = getAttribute(name).takeIf { it.isNotEmpty() }
        var status: String? = null; var modifiedBy: String? = null
        var broadcast: String? = null; var regroup: String? = null; var call: Boolean? = null
        val members = mutableListOf<Member>()
        for (child in root.children()) when {
            child.localName == "tuple" && child.namespaceURI == PIDF_NS -> {
                val id = child.attr("id") ?: throw IllegalArgumentException("tuple without id")
                val st = child.children("status").firstOrNull { it.namespaceURI == PIDF_NS }
                val nodes = st?.children()?.filter { it.namespaceURI == MCVIDEO_NS } ?: emptyList()
                members += Member(id, nodes.filter { it.localName == "affiliation" }.mapNotNull { it.attr("client") },
                    nodes.filter { it.localName == "functionalAlias" }.mapNotNull { it.attr("functionalAliasID") })
            }
            child.localName == "groupStatus" && child.namespaceURI == MCVIDEO_NS -> { status = child.textContent.trim(); modifiedBy = child.attr("ModifiedBy") }
            child.localName == "additionalData" && child.namespaceURI == MCVIDEO_NS -> {
                broadcast = child.attr("groupBroadcastAlias"); regroup = child.attr("groupRegroupAlias")
                call = child.attr("groupCallOngoing")?.trim()?.let { it == "true" || it == "1" }
            }
        }
        return State(root.attr("entity"), status, modifiedBy, broadcast, regroup, call, members)
    }
}
