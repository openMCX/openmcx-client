//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.childText
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text

/**
 * The mcdata-info document (TS 24.282 Annex D.1), namespace `urn:3gpp:ns:mcdataInfo:1.0`.
 * Elements are written in the schema's sequence order.
 */
data class McdataInfo(
    val accessToken: String? = null,
    val requestType: String? = null,
    val requestUri: String? = null,
    val callingUserId: String? = null,
    val calledPartyId: String? = null,
    val callingGroupId: String? = null,
    val clientId: String? = null,
    /** The emergency alert (§16.2) and whose alert an authorised user cancels. */
    val alert: Boolean? = null,
    val originatedBy: String? = null,
    /** In `<anyExt>`: the server's receipt of this client's alert (§16.2.1.1). */
    val alertReceived: Boolean? = null,
    /** In `<anyExt>`: entry into (true) or exit from an emergency alert area or a group's geographic area (§17.2.4). */
    val associatedGroupId: String? = null,
    val emergencyAlertArea: Boolean? = null,
    val groupGeoArea: Boolean? = null,
    val usedPriority: Int? = null,
    val userRequestedPriority: Int? = null,
    val controllerPsi: String? = null,
    /**
     * In `<anyExt>` (TS 24.282 Annex D.1), functional aliases (§22): the call to an alias, the alias presented by the
     * sender and the alias called.
     */
    val callToFunctionalAlias: Boolean? = null,
    val functionalAlias: String? = null,
    val calledFunctionalAlias: String? = null,
) {
    fun encode(): String = Xml.document("mcdatainfo", NAMESPACE) {
        element("mcdata-Params") {
            if (accessToken != null) element("mcdata-access-token") { attr("type", "Normal"); leaf("mcdataString", accessToken) }
            leaf("request-type", requestType)
            uri("mcdata-request-uri", requestUri)
            uri("mcdata-calling-user-id", callingUserId)
            uri("mcdata-called-party-id", calledPartyId)
            uri("mcdata-calling-group-id", callingGroupId)
            if (alert != null) element("alert-ind") { attr("type", "Normal"); leaf("mcdataBoolean", alert.toString()) }
            uri("originated-by", originatedBy)
            if (clientId != null) element("mcdata-client-id") { attr("type", "Normal"); leaf("mcdataString", clientId) }
            uri("mcdata-controller-psi", controllerPsi)
            if (alertReceived != null || associatedGroupId != null || userRequestedPriority != null || usedPriority != null ||
                emergencyAlertArea != null || groupGeoArea != null || callToFunctionalAlias != null || functionalAlias != null ||
                calledFunctionalAlias != null) element("anyExt") {
                leaf("alert-ind-rcvd", alertReceived?.toString())
                leaf("associated-group-id", associatedGroupId)
                leaf("user-requested-priority", userRequestedPriority?.toString())
                leaf("used-priority", usedPriority?.toString())
                leaf("emergency-alert-area-ind", emergencyAlertArea?.toString())
                leaf("group-geo-area-ind", groupGeoArea?.toString())
                leaf("call-to-functional-alias-ind", callToFunctionalAlias?.toString())
                uri("functional-alias-URI", functionalAlias)
                uri("called-functional-alias-URI", calledFunctionalAlias)
            }
        }
    }

    private fun org.openmcx.client.body.XmlElement.uri(name: String, value: String?) {
        if (value != null) element(name) { attr("type", "Normal"); leaf("mcdataURI", value) }
    }

    companion object {
        const val NAMESPACE = "urn:3gpp:ns:mcdataInfo:1.0"
        const val CONTENT_TYPE = "application/vnd.3gpp.mcdata-info+xml"

        fun parse(text: String): McdataInfo {
            val root = Xml.parse(text)
            val params = root.child("mcdata-Params") ?: root
            fun value(name: String) = params.child(name)?.let { it.child("mcdataURI") ?: it.child("mcdataString") ?: it.child("mcdataBoolean") ?: it }?.text()
            fun ext(name: String) = params.child("anyExt")?.descendants(name)?.firstOrNull()?.text()?.trim()
            fun flag(name: String) = ext(name)?.let { it == "true" || it == "1" }
            return McdataInfo(
                accessToken = value("mcdata-access-token"),
                requestType = params.childText("request-type"),
                requestUri = value("mcdata-request-uri"),
                callingUserId = value("mcdata-calling-user-id"),
                calledPartyId = value("mcdata-called-party-id"),
                callingGroupId = value("mcdata-calling-group-id"),
                clientId = value("mcdata-client-id"),
                alert = value("alert-ind")?.trim()?.let { it == "true" || it == "1" },
                originatedBy = value("originated-by"),
                alertReceived = flag("alert-ind-rcvd"),
                associatedGroupId = ext("associated-group-id"),
                emergencyAlertArea = flag("emergency-alert-area-ind"),
                groupGeoArea = flag("group-geo-area-ind"),
                usedPriority = params.descendants("used-priority").firstOrNull()?.text()?.toIntOrNull(),
                userRequestedPriority = params.descendants("user-requested-priority").firstOrNull()?.text()?.toIntOrNull(),
                controllerPsi = value("mcdata-controller-psi"),
                callToFunctionalAlias = flag("call-to-functional-alias-ind"),
                functionalAlias = ext("functional-alias-URI"),
                calledFunctionalAlias = ext("called-functional-alias-URI"),
            )
        }
    }
}
