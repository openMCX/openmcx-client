//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.text
import org.w3c.dom.Element

/**
 * What the MCVideo client takes from its MCVideo user profile (TS 24.484 §9.3, namespace of §9.3.2.4), read from the CMS
 * once the MCVideo service is authorised (TS 24.484 §9.3.1A, the same profile index as the MCPTT user profile):
 * - [priority]: <Common>/<MCVideo-group-call>/<Priority> (§9.3.2.1 item 8 e) v), PriorityType 0 to 255), the configured
 *   priority TS 24.581 §14.2.3 and §14.2.6 base the transmission and reception priority on. TS 24.484 has no separate
 *   reception priority element: the one priority is used for both, as the Linux client does;
 * - [privateCallKms]: the <PrivateCallKMSURI> of each <PrivateCallOnNetwork> entry (§9.3.2.1 item 8 d), schema
 *   PrivateCallKMSURIEntryType), the KMS of a private call's target (TS 24.281 §6.2.8.3.9 step 1 a).
 */
data class McvideoProfile(
    val userId: String?,
    val groups: List<String>,
    val privateCallTargets: List<String>,
    val privateCallKms: Map<String, String>,
    val priority: Int,
) {
    companion object {
        const val NS = "urn:3gpp:ns:mcvideo:user-profile:1.0"

        private fun Element.kids(name: String) = children(name).filter { it.namespaceURI == NS }
        private fun Element.kid(name: String) = kids(name).firstOrNull()
        private fun Element.uriEntry() = kid("uri-entry")?.text()?.takeIf { it.isNotEmpty() }

        /** The XCAP path of the profile (TS 24.484 §9.3.1A): `org.3gpp.mcvideo.user-profile/users/<id>/mcvideo-user-profile-<index>.xml`. */
        fun path(userIdSegment: String, index: Int) = "org.3gpp.mcvideo.user-profile/users/$userIdSegment/mcvideo-user-profile-$index.xml"

        fun parse(text: String): McvideoProfile {
            val root = Xml.parse(text)
            if (root.localName != "mcvideo-user-profile" || root.namespaceURI != NS) throw IllegalArgumentException("not an MCVideo user profile")
            val common = root.kids("Common")
            val entries = common.flatMap { it.kids("PrivateCall") }.flatMap { it.kids("PrivateCallList") }.flatMap { it.kids("PrivateCallOnNetwork") }
            val targets = mutableListOf<String>()
            val kms = mutableMapOf<String, String>()
            for (entry in entries) {
                val target = entry.kid("PrivateCallURI")?.uriEntry() ?: continue
                targets += target
                entry.kid("PrivateCallKMSURI")?.kid("PrivateCallKMSURI")?.uriEntry()?.let { kms[target] = it }
            }
            val priority = common.flatMap { it.kids("MCVideo-group-call") }.firstNotNullOfOrNull { it.kid("Priority") }?.text()?.let {
                it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: throw IllegalArgumentException("invalid MCVideo group call priority $it")
            } ?: 0
            return McvideoProfile(
                userId = common.firstNotNullOfOrNull { it.kid("MCVideoUserID")?.uriEntry() } ?: root.getAttribute("XUI-URI").takeIf { it.isNotEmpty() },
                groups = root.kids("OnNetwork").flatMap { it.kids("MCVideoGroupInfo") }.mapNotNull { it.kid("MCVideo-Group-ID")?.uriEntry() },
                privateCallTargets = targets, privateCallKms = kms, priority = priority)
        }
    }
}
