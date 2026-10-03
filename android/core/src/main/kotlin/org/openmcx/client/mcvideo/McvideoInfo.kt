package org.openmcx.client.mcvideo

import org.openmcx.client.body.Xml
import org.openmcx.client.body.XmlElement
import org.openmcx.client.body.child
import org.openmcx.client.body.childText
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text

/**
 * The mcvideo-info document (TS 24.281 Annex F.1), namespace `urn:3gpp:ns:mcvideoInfo:1.0`:
 * the mcptt-info shape with the `mcvideo` prefix. Elements are written in schema order.
 */
data class McvideoInfo(
    val accessToken: String? = null,
    val sessionType: String? = null,
    val requestUri: String? = null,
    val callingUserId: String? = null,
    val calledPartyId: String? = null,
    val callingGroupId: String? = null,
    val clientId: String? = null,
    /** §6.2.8: the emergency, alert and imminent peril indications (absent: not stated). */
    val emergency: Boolean? = null,
    val alert: Boolean? = null,
    val imminentPeril: Boolean? = null,
    /** Whose alert an authorised user cancels (§11.2.1.2). */
    val originatedBy: String? = null,
    /** The recording to pull from the server (§12.2.2.4). */
    val videoPullUrl: String? = null,
    /** Where the server saved a push to it (§13.2.3.2.3), in `<anyExt>`. */
    val videoPushUrl: String? = null,
    /** An ambient viewing call's type, `remote-init` or `local-init` (§15.2.1.1 step 8), in `<anyExt>`. */
    val ambientViewingType: String? = null,
    /** Why the server released the call (§15.4.3), in `<anyExt>` of a BYE. */
    val releaseReason: String? = null,
    /** In `<anyExt>`: the ad hoc group emergency alert (TS 24.379 §12.1A as TS 24.281 uses it), its receipt and criteria. */
    val participantCriteria: String? = null,
    val adhocAlert: Boolean? = null,
    val adhocAlertReceived: Boolean? = null,
) {
    fun encode(): String = Xml.document("mcvideoinfo", NAMESPACE) {
        element("mcvideo-Params") {
            wrapped("mcvideo-access-token", accessToken, "mcvideoString")
            leaf("session-type", sessionType)
            wrapped("mcvideo-request-uri", requestUri, "mcvideoURI")
            wrapped("mcvideo-calling-user-id", callingUserId, "mcvideoURI")
            wrapped("mcvideo-called-party-id", calledPartyId, "mcvideoURI")
            wrapped("mcvideo-calling-group-id", callingGroupId, "mcvideoURI")
            wrapped("emergency-ind", emergency?.toString(), "mcvideoBoolean")
            wrapped("alert-ind", alert?.toString(), "mcvideoBoolean")
            wrapped("imminentperil-ind", imminentPeril?.toString(), "mcvideoBoolean")
            wrapped("originated-by", originatedBy, "mcvideoURI")
            wrapped("mcvideo-client-id", clientId, "mcvideoString")
            wrapped("video-pull-url", videoPullUrl, "mcvideoURI")
            if (ambientViewingType != null || participantCriteria != null || adhocAlert != null || adhocAlertReceived != null) element("anyExt") {
                leaf("ambient-viewing-type", ambientViewingType)
                leaf("call-participants-criteria", participantCriteria)
                leaf("adhoc-alert-ind", adhocAlert?.toString())
                leaf("adhoc-alert-ind-rcvd", adhocAlertReceived?.toString())
            }
        }
    }

    private fun XmlElement.wrapped(name: String, value: String?, wrapper: String) {
        if (value != null) element(name) { attr("type", "Normal"); leaf(wrapper, value) }
    }

    companion object {
        const val NAMESPACE = "urn:3gpp:ns:mcvideoInfo:1.0"
        const val CONTENT_TYPE = "application/vnd.3gpp.mcvideo-info+xml"

        fun parse(text: String): McvideoInfo {
            val root = Xml.parse(text)
            val params = root.child("mcvideo-Params") ?: root
            fun value(name: String) = params.child(name)?.let { it.child("mcvideoURI") ?: it.child("mcvideoString") ?: it.child("mcvideoBoolean") ?: it }?.text()
            return McvideoInfo(
                accessToken = value("mcvideo-access-token"),
                sessionType = params.childText("session-type"),
                requestUri = value("mcvideo-request-uri"),
                callingUserId = value("mcvideo-calling-user-id"),
                calledPartyId = value("mcvideo-called-party-id"),
                callingGroupId = value("mcvideo-calling-group-id"),
                clientId = value("mcvideo-client-id"),
                emergency = value("emergency-ind")?.toBooleanStrictOrNull(),
                alert = value("alert-ind")?.toBooleanStrictOrNull(),
                imminentPeril = value("imminentperil-ind")?.toBooleanStrictOrNull(),
                originatedBy = value("originated-by"),
                videoPullUrl = value("video-pull-url"),
                videoPushUrl = params.descendants("video-push-url").firstOrNull()?.text(),
                ambientViewingType = params.descendants("ambient-viewing-type").firstOrNull()?.text(),
                releaseReason = params.descendants("release-reason").firstOrNull()?.text(),
                participantCriteria = params.descendants("call-participants-criteria").firstOrNull()?.text(),
                adhocAlert = params.descendants("adhoc-alert-ind").firstOrNull()?.text()?.trim()?.let { it == "true" || it == "1" },
                adhocAlertReceived = params.descendants("adhoc-alert-ind-rcvd").firstOrNull()?.text()?.trim()?.let { it == "true" || it == "1" },
            )
        }
    }
}
