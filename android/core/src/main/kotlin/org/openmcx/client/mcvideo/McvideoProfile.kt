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
 *   PrivateCallKMSURIEntryType), the KMS of a private call's target (TS 24.281 §6.2.8.3.9 step 1 a);
 * - [actions]: the granted rights, the boolean elements "true" of the <cp:ruleset> actions (§9.3.2.1 item 11, directly
 *   or in their <anyExt>), and [targets], the EntryType <entry> of the priority requests' target elements (§9.3.2.3):
 *   what TS 24.281 §6.2.8.1.6, §6.2.8.1.7, §6.2.8.1.8, §6.2.8.1.10, §6.2.8.1.22 and §6.2.8.3.1 authorise a request by
 *   ([McvideoAuthorisation]).
 */
data class McvideoProfile(
    val userId: String?,
    val groups: List<String>,
    val privateCallTargets: List<String>,
    val privateCallKms: Map<String, String>,
    val priority: Int,
    val actions: Set<String> = emptySet(),
    val targets: Map<String, TargetEntry> = emptyMap(),
) {
    /** TS 24.484 §9.3.2.3 EntryType: the "entry-info" of an <entry> and its <uri-entry> (absent for a choice naming no target). */
    data class TargetEntry(val entryInfo: String, val uri: String?)

    fun allows(action: String) = action in actions

    companion object {
        const val NS = "urn:3gpp:ns:mcvideo:user-profile:1.0"
        const val EXTENSION_NS = "urn:openmcx:ns:userProfile:1.0"
        const val COMMON_POLICY_NS = "urn:ietf:params:xml:ns:common-policy"

        /** The target entries, by the names [McvideoAuthorisation] uses. */
        const val EMERGENCY_GROUP_INITIATION = "EmergencyCall/MCVideoGroupInitiation"
        const val IMMINENT_GROUP_INITIATION = "ImminentPerilCall/MCVideoGroupInitiation"
        const val GROUP_EMERGENCY_ALERT = "EmergencyAlert"
        const val EMERGENCY_PRIVATE_RECIPIENT = "PrivateCall/EmergencyCall/MCVideoPrivateRecipient"
        const val PRIVATE_EMERGENCY_ALERT = "PrivateEmergencyAlert"

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
            val callTargets = mutableListOf<String>()
            val kms = mutableMapOf<String, String>()
            for (entry in entries) {
                val target = entry.kid("PrivateCallURI")?.uriEntry() ?: continue
                callTargets += target
                entry.kid("PrivateCallKMSURI")?.kid("PrivateCallKMSURI")?.uriEntry()?.let { kms[target] = it }
            }
            val priority = common.flatMap { it.kids("MCVideo-group-call") }.firstNotNullOfOrNull { it.kid("Priority") }?.text()?.let {
                it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: throw IllegalArgumentException("invalid MCVideo group call priority $it")
            } ?: 0
            // §9.3.2.3: the <entry> of a target element, with its "entry-info" and <uri-entry>.
            fun target(holder: Element?, name: String): TargetEntry? = holder?.kid(name)?.kid("entry")?.let {
                TargetEntry(it.getAttribute("entry-info").trim(), it.uriEntry())
            }
            val groupCall = common.flatMap { it.kids("MCVideo-group-call") }.firstOrNull()
            val privateCall = common.flatMap { it.kids("PrivateCall") }.firstOrNull()
            val targets = listOfNotNull(
                // §9.3.2.1 item 8 e): <EmergencyCall> and <ImminentPerilCall> (each an <MCVideoGroupInitiation>), <EmergencyAlert>.
                target(groupCall?.kid("EmergencyCall"), "MCVideoGroupInitiation")?.let { EMERGENCY_GROUP_INITIATION to it },
                target(groupCall?.kid("ImminentPerilCall"), "MCVideoGroupInitiation")?.let { IMMINENT_GROUP_INITIATION to it },
                target(groupCall, "EmergencyAlert")?.let { GROUP_EMERGENCY_ALERT to it },
                // Item 8 d) ii): <PrivateCall>/<EmergencyCall>/<MCVideoPrivateRecipient>.
                target(privateCall?.kid("EmergencyCall"), "MCVideoPrivateRecipient")?.let { EMERGENCY_PRIVATE_RECIPIENT to it },
                // Item 9 f): <OnNetwork>/<PrivateEmergencyAlert>, the first <OnNetwork> carrying one.
                root.kids("OnNetwork").firstNotNullOfOrNull { target(it, "PrivateEmergencyAlert") }?.let { PRIVATE_EMERGENCY_ALERT to it },
            ).toMap()
            return McvideoProfile(
                userId = common.firstNotNullOfOrNull { it.kid("MCVideoUserID")?.uriEntry() } ?: root.getAttribute("XUI-URI").takeIf { it.isNotEmpty() },
                groups = root.kids("OnNetwork").flatMap { it.kids("MCVideoGroupInfo") }.mapNotNull { it.kid("MCVideo-Group-ID")?.uriEntry() },
                privateCallTargets = callTargets, privateCallKms = kms, priority = priority,
                actions = rights(root), targets = targets)
        }

        /**
         * §9.3.2.1 item 11: each right is a boolean element of the <cp:ruleset> actions, directly or in their <anyExt>,
         * granted when "true" (or "1"); the rights TS 24.484 has no element for are in the OpenMCX extension namespace.
         */
        private fun rights(root: Element): Set<String> {
            val out = mutableSetOf<String>()
            fun collect(list: Element) {
                for (e in list.children()) {
                    if (e.localName == "anyExt" && e.namespaceURI == NS) { collect(e); continue }
                    if (e.namespaceURI != NS && e.namespaceURI != EXTENSION_NS) continue
                    if (e.text() in setOf("true", "1")) out += e.localName
                }
            }
            root.children("ruleset").filter { it.namespaceURI == COMMON_POLICY_NS }
                .flatMap { it.children("rule").filter { r -> r.namespaceURI == COMMON_POLICY_NS } }
                .flatMap { it.children("actions").filter { a -> a.namespaceURI == COMMON_POLICY_NS } }.forEach(::collect)
            return out
        }
    }
}
