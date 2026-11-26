//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text
import org.w3c.dom.Element

/**
 * The client's own authorisation of the user's priority requests by the MCVideo user profile (TS 24.484 §9.3.2), as
 * the Linux client does it: a request is authorised only when the <ruleset> right is "true" and its target meets the
 * conditions of its target entry; "In all other cases" it is unauthorised and not sent:
 * - TS 24.281 §6.2.8.1.8: an emergency (imminent peril) group call needs <allow-emergency-group-call>
 *   (<allow-imminent-peril-call>) and the <entry> of <MCVideoGroupInitiation> of <EmergencyCall> (<ImminentPerilCall>)
 *   a) "DedicatedGroup" with the targeted group, or b) "UseCurrentlySelectedGroup";
 * - §6.2.8.1.6: an emergency alert needs <allow-activate-emergency-alert> and the <entry> of <EmergencyAlert> a)
 *   "DedicatedGroup" with the targeted group, or b) "UseCurrentlySelectedGroup" and the group document's
 *   <mcvideo-allow-emergency-alert> "true" for this user (TS 24.481 §7.2.2); its cancellation <allow-cancel-emergency-alert>;
 * - §6.2.8.1.7 and §6.2.8.1.10: cancelling a group's emergency or imminent peril needs <allow-cancel-group-emergency>
 *   or <allow-cancel-imminent-peril>;
 * - §6.2.8.1.22: an emergency (imminent peril) ad hoc group call needs <allow-emergency-adhoc-group-call>
 *   (<allow-imminent-peril-adhoc-group-call>); §6.2.8.1.27 an ad hoc group emergency alert
 *   <allow-activate-adhoc-group-emergency-alert>, its cancellation <allow-cancel-adhoc-group-emergency-alert>;
 * - §6.2.8.3.1.1: an emergency private call needs <allow-emergency-private-call> and the <entry> of
 *   <MCVideoPrivateRecipient> a) "UsePreConfigured" with the targeted user or b) "LocallyDetermined"; a profile without
 *   that element (minOccurs 0, the schema's single entry needs a ProSe user) leaves it to the right alone, as the Linux
 *   client; its cancellation <allow-cancel-private-emergency-call>.
 */
object McvideoAuthorisation {
    /** The request is not authorised: [action] is the right or target entry that refuses it. */
    class NotAuthorised(val action: String, what: String, detail: String? = null) :
        org.openmcx.client.mcptt.CallError("not authorised by the MCVideo user profile: $what ($action${detail?.let { ", $it" } ?: ""})", 403)

    const val POC_NS = "urn:oma:xml:poc:list-service"
    const val GROUP_INFO_NS = "urn:3gpp:ns:mcpttGroupInfo:1.0"
    const val COMMON_POLICY_NS = "urn:ietf:params:xml:ns:common-policy"

    /** The ruleset right [action] (TS 24.484 §9.3.2.1 item 11). */
    fun requireRight(profile: McvideoProfile, action: String, what: String) {
        if (!profile.allows(action)) throw NotAuthorised(action, what)
    }

    /**
     * The target condition of the entry [entry] of [profile] for [target]. [groupAllowsAlert] answers, for a group
     * alert with "UseCurrentlySelectedGroup", whether the targeted group's document allows this user's alert.
     */
    suspend fun requireTarget(profile: McvideoProfile, entry: String, target: String?, what: String,
                              groupAllowsAlert: suspend (String) -> Boolean = { false }) {
        val found = profile.targets[entry]
        val allowed = when {
            entry == McvideoProfile.EMERGENCY_PRIVATE_RECIPIENT -> found == null || found.entryInfo == "LocallyDetermined" ||
                (found.entryInfo == "UsePreConfigured" && found.uri != null && found.uri == target)
            found == null -> false
            found.entryInfo == "DedicatedGroup" -> target != null && found.uri == target
            found.entryInfo == "UseCurrentlySelectedGroup" ->
                entry != McvideoProfile.GROUP_EMERGENCY_ALERT || (target != null && runCatching { groupAllowsAlert(target) }.getOrDefault(false))
            else -> false
        }
        if (!allowed) throw NotAuthorised(entry, what, "target $target, entry ${found?.let { "${it.entryInfo} ${it.uri ?: ""}".trim() } ?: "absent"}")
    }

    /**
     * TS 24.481 §7.2.2 with RFC 4745 §10: whether a <rule> of the group document's <ruleset> that applies to this user
     * (all its conditions met: <identity> naming one of [identities], <is-list-member> with the user an <entry> of the
     * list; any other condition unknown, so the rule does not apply, §7.2.8) grants [action] (an element of the
     * mcpttGroupInfo namespace, "true"); boolean permissions combine by OR (RFC 4745 §10.2).
     */
    fun groupAllows(groupDocument: String, action: String, identities: Collection<String>): Boolean {
        val root = Xml.parse(groupDocument)
        val service = root.descendants("list-service").firstOrNull { it.namespaceURI == POC_NS } ?: return false
        fun mine(id: String?) = !id.isNullOrEmpty() && id in identities
        val member = service.children("list").filter { it.namespaceURI == POC_NS }.flatMap { it.children("entry") }.any { e ->
            mine(e.getAttribute("uri")) || e.children().any { id -> id.namespaceURI == GROUP_INFO_NS &&
                (id.localName == "mcvideo-mcvideo-id" || id.localName == "mcdata-mcdata-id") && mine(id.getAttribute("uri")) }
        }
        val ruleset = service.children("ruleset").firstOrNull { it.namespaceURI == COMMON_POLICY_NS } ?: return false
        for (rule in ruleset.children("rule").filter { it.namespaceURI == COMMON_POLICY_NS }) {
            val conditions = rule.children("conditions").firstOrNull { it.namespaceURI == COMMON_POLICY_NS }
            val applies = conditions?.children()?.all { c: Element ->
                when {
                    c.localName == "identity" && c.namespaceURI == COMMON_POLICY_NS ->
                        c.children("one").any { it.namespaceURI == COMMON_POLICY_NS && mine(it.getAttribute("id")) }
                    c.localName == "is-list-member" && c.namespaceURI == POC_NS -> member
                    else -> false
                }
            } ?: true
            if (!applies) continue
            val granted = rule.children("actions").filter { it.namespaceURI == COMMON_POLICY_NS }.flatMap { it.children() }
                .any { it.namespaceURI == GROUP_INFO_NS && it.localName == action && it.text() in setOf("true", "1") }
            if (granted) return true
        }
        return false
    }
}
