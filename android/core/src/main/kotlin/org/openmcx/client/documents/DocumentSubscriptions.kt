//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.documents

import org.openmcx.client.body.ResourceLists
import org.openmcx.client.body.Xml
import org.openmcx.client.body.descendants
import org.openmcx.client.security.KeyClient

/**
 * The subscriptions to the configuration management documents (TS 24.484 §6.3.13.2.2, through the CMS subscription
 * proxy function) and to the group documents (TS 24.481 §6.3.13.2.1, through the GMS subscription proxy function):
 * the resource lists they subscribe to, the xcap-diff notifications (RFC 5875, RFC 5874) they receive.
 */
object DocumentSubscriptions {
    /** RFC 5875 §4: the event package of both subscriptions. */
    const val EVENT = "xcap-diff"
    const val ACCEPT = "application/xcap-diff+xml"

    /**
     * RFC 5875 §4.5: "The default expiration time for subscriptions within this package is 3600 seconds"; both
     * subscriptions ask for it (as the GKTP subscriptions do). TS 24.484 and TS 24.481 give no other value.
     */
    const val EXPIRES_SECONDS = 3600L

    /**
     * TS 24.484 §6.3.13.2.2 b) 1) (RFC 5875 §4.4: relative paths under the XCAP root): the user's MCPTT UE
     * configuration (§8.2.1, by the MCPTT ID and the UE ID), the user profile (§8.3.1A, mcptt-user-profile-<index>.xml)
     * and the MCPTT service configuration (§8.4.2). XCAP path segments keep ':' and '@' (RFC 3986 §3.3).
     */
    fun configurationEntries(mcpttId: String, ueId: String, profileIndex: Int): List<String> = listOf(
        "org.3gpp.mcptt.ue-config/users/${KeyClient.xcapSegment(mcpttId)}/${KeyClient.xcapSegment(ueId)}",
        "org.3gpp.mcptt.user-profile/users/${KeyClient.xcapSegment(mcpttId)}/mcptt-user-profile-$profileIndex.xml",
        "org.3gpp.mcptt.service-config/global/service-config.xml",
    )

    /** TS 24.481 §6.3.13.2.1 a) 1): one entry per group document, addressed by its group ID (§7.2.10.2). */
    fun groupEntries(groups: List<String>): List<String> =
        groups.map { "$GROUP_DOCUMENTS${KeyClient.xcapSegment(it)}" }

    private const val GROUP_DOCUMENTS = "org.openmobilealliance.groups/global/byGroupID/"

    fun resourceList(entries: List<String>): String = ResourceLists.encode(entries)

    /** A document of an xcap-diff notification (RFC 5874 §4): its selector and new entity tag (none: removed). */
    data class Changed(val selector: String, val newEtag: String?)

    /** RFC 5874 §4: the <document> elements of an xcap-diff document. */
    fun parse(xml: String): List<Changed> {
        val root = Xml.parse(xml)
        require(root.localName == "xcap-diff" && root.namespaceURI == XCAP_DIFF_NS) { "not an xcap-diff document" }
        return root.descendants("document").filter { it.namespaceURI == XCAP_DIFF_NS }.map { document ->
            Changed(document.getAttribute("sel"), document.getAttribute("new-etag").ifEmpty { null })
        }
    }

    private const val XCAP_DIFF_NS = "urn:ietf:params:xml:ns:xcap-diff"

    /**
     * The group of a group document's selector (relative, or absolute under an XCAP root), percent-decoded
     * (RFC 3986 §2.1); null for any other document.
     */
    fun groupOf(selector: String): String? {
        val at = selector.indexOf(GROUP_DOCUMENTS)
        if (at < 0) return null
        val segment = selector.substring(at + GROUP_DOCUMENTS.length).substringBefore('/').substringBefore('?')
        return percentDecode(segment).takeIf { it.isNotEmpty() }
    }

    private fun percentDecode(text: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                out.write(text.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { out.write(c.toString().toByteArray()); i += 1 }
        }
        return out.toString("UTF-8")
    }
}
