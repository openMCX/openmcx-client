//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.openmcx.client.body.Xml
import org.openmcx.client.body.XmlElement
import org.openmcx.client.body.child
import org.openmcx.client.body.childText
import org.openmcx.client.body.children
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text

/**
 * The mcptt-info document (TS 24.379 Annex F.1), namespace `urn:3gpp:ns:mcpttInfo:1.0`.
 * Elements are written in the `mcptt-ParamsType` sequence order. [extra] carries
 * elements this client does not model, as (name, value) text pairs, when parsing.
 */
data class McpttInfo(
    val accessToken: String? = null,
    val sessionType: String? = null,
    val requestUri: String? = null,
    val callingUserId: String? = null,
    val calledPartyId: String? = null,
    val callingGroupId: String? = null,
    val emergency: Boolean? = null,
    /** The emergency alert indication (§12.1), and whose alert an authorised user cancels. */
    val alert: Boolean? = null,
    val imminentPeril: Boolean? = null,
    val associatedGroupId: String? = null,
    val originatedBy: String? = null,
    val clientId: String? = null,
    /** The server's receipt of this client's alert (§12.1.1.1 step 7). */
    val alertReceived: Boolean? = null,
    /** In `<anyExt>`: the ad hoc group emergency alert (§12.1A) and its receipt, with the participant criteria. */
    val participantCriteria: String? = null,
    /** In `<anyExt>`: entry into (true) or exit from an emergency alert area or a group's geographic area (§6.3.2.4). */
    val emergencyAlertArea: Boolean? = null,
    val groupGeoArea: Boolean? = null,
    val adhocAlert: Boolean? = null,
    val adhocAlertReceived: Boolean? = null,
    /**
     * In `<anyExt>` (TS 24.379 Annex F.1.3), functional aliases (§9A): the alias presented by the caller, the alias
     * called, the call to an alias (§11.1.1.2.1.1 step 14c), and the request type of the alias status subscription
     * (`functional-alias-status-determination`, §9A.2.1.3).
     */
    val functionalAlias: String? = null,
    val calledFunctionalAlias: String? = null,
    val callToFunctionalAlias: Boolean? = null,
    val requestType: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    fun encode(): String = Xml.document("mcpttinfo", NAMESPACE) {
        element("mcptt-Params") {
            wrapped("mcptt-access-token", accessToken, "mcpttString")
            leaf("session-type", sessionType)
            wrapped("mcptt-request-uri", requestUri, "mcpttURI")
            wrapped("mcptt-calling-user-id", callingUserId, "mcpttURI")
            wrapped("mcptt-called-party-id", calledPartyId, "mcpttURI")
            wrapped("mcptt-calling-group-id", callingGroupId, "mcpttURI")
            emergency?.let { wrapped("emergency-ind", it.toString(), "mcpttBoolean") }
            alert?.let { wrapped("alert-ind", it.toString(), "mcpttBoolean") }
            imminentPeril?.let { wrapped("imminentperil-ind", it.toString(), "mcpttBoolean") }
            wrapped("associated-group-id", associatedGroupId, "mcpttURI")
            wrapped("originated-by", originatedBy, "mcpttURI")
            wrapped("mcptt-client-id", clientId, "mcpttString")
            alertReceived?.let { wrapped("alert-ind-rcvd", it.toString(), "mcpttBoolean") }
            if (participantCriteria != null || emergencyAlertArea != null || groupGeoArea != null || adhocAlert != null ||
                adhocAlertReceived != null || functionalAlias != null || calledFunctionalAlias != null ||
                callToFunctionalAlias != null || requestType != null) element("anyExt") {
                leaf("call-participants-criterias", participantCriteria)
                leaf("emergency-alert-area-ind", emergencyAlertArea?.toString())
                leaf("group-geo-area-ind", groupGeoArea?.toString())
                leaf("adhoc-alert-ind", adhocAlert?.toString())
                leaf("adhoc-alert-ind-rcvd", adhocAlertReceived?.toString())
                wrapped("functional-alias-URI", functionalAlias, "mcpttURI")
                wrapped("called-functional-alias-URI", calledFunctionalAlias, "mcpttURI")
                leaf("call-to-functional-alias-ind", callToFunctionalAlias?.toString())
                leaf("request-type", requestType)
            }
        }
    }

    private fun XmlElement.wrapped(name: String, value: String?, wrapper: String) {
        if (value != null) element(name) { attr("type", "Normal"); leaf(wrapper, value) }
    }

    companion object {
        const val NAMESPACE = "urn:3gpp:ns:mcpttInfo:1.0"
        const val CONTENT_TYPE = "application/vnd.3gpp.mcptt-info+xml"

        fun parse(text: String): McpttInfo {
            val root = Xml.parse(text)
            val params = root.child("mcptt-Params") ?: root
            fun value(name: String) = params.child(name)?.let {
                it.child("mcpttURI") ?: it.child("mcpttString") ?: it.child("mcpttBoolean") ?: it
            }?.text()
            val known = setOf("mcptt-access-token", "session-type", "mcptt-request-uri", "mcptt-calling-user-id",
                "mcptt-called-party-id", "mcptt-calling-group-id", "emergency-ind", "alert-ind", "imminentperil-ind", "associated-group-id",
                "originated-by", "mcptt-client-id", "alert-ind-rcvd", "anyExt")
            fun ext(name: String) = params.child("anyExt")?.descendants(name)?.firstOrNull()?.text()
            fun flag(name: String) = ext(name)?.trim()?.let { it == "true" || it == "1" }
            return McpttInfo(
                accessToken = value("mcptt-access-token"),
                sessionType = params.childText("session-type"),
                requestUri = value("mcptt-request-uri"),
                callingUserId = value("mcptt-calling-user-id"),
                calledPartyId = value("mcptt-called-party-id"),
                callingGroupId = value("mcptt-calling-group-id"),
                emergency = value("emergency-ind")?.toBooleanStrictOrNull(),
                alert = value("alert-ind")?.toBooleanStrictOrNull(),
                imminentPeril = value("imminentperil-ind")?.toBooleanStrictOrNull(),
                associatedGroupId = value("associated-group-id"),
                originatedBy = value("originated-by"),
                clientId = value("mcptt-client-id"),
                alertReceived = value("alert-ind-rcvd")?.toBooleanStrictOrNull(),
                participantCriteria = ext("call-participants-criterias"),
                emergencyAlertArea = flag("emergency-alert-area-ind"),
                groupGeoArea = flag("group-geo-area-ind"),
                adhocAlert = flag("adhoc-alert-ind"),
                adhocAlertReceived = flag("adhoc-alert-ind-rcvd"),
                functionalAlias = ext("functional-alias-URI"),
                calledFunctionalAlias = ext("called-functional-alias-URI"),
                callToFunctionalAlias = flag("call-to-functional-alias-ind"),
                requestType = ext("request-type"),
                extra = params.children().filter { it.localName !in known }.associate { it.localName to it.text() },
            )
        }
    }
}
