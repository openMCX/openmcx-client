//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.alert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.location.LocationService
import org.openmcx.client.mcdata.McdataInfo
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcvideo.EmergencyLocation
import org.openmcx.client.mcvideo.McvideoInfo
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

class AlertError(message: String, val status: Int? = null) : Exception(message)

/** Another user's emergency alert: on a group, or an ad hoc group alert to the users its [criteria] select. */
data class EmergencyAlert(val service: LocationService, val user: String, val group: String?, val adhoc: Boolean,
                          val location: EmergencyLocation?, val criteria: String? = null, val at: Long = System.currentTimeMillis())

/** This user's ad hoc group emergency alert: the group identity the server gave it, and its criteria. */
data class OwnAdhocAlert(val group: String, val criteria: String)

/** The server's notice that this user entered or left an emergency alert area or a group's geographic area. */
data class AreaNotice(val service: LocationService, val alertArea: Boolean, val entered: Boolean, val group: String?,
                      val alerted: String? = null, val at: Long = System.currentTimeMillis())

interface AlertListener {
    /** Another user's alert raised ([active]) or cancelled. */
    fun alert(alert: EmergencyAlert, active: Boolean) {}
    /** This user's own alert on [group] was cancelled by [by], an authorised user. */
    fun ownCancelled(service: LocationService, group: String, by: String?) {}
    fun area(notice: AreaNotice) {}
}

/**
 * Emergency alerts of one MC service, as the client sends and receives them:
 * - the group emergency alert of MCPTT (TS 24.379 §12.1) and MCData (TS 24.282 §16.2), with its receipt and the
 *   cancellation of another user's alert by an authorised user (`originated-by`); MCVideo's is in the McvideoService;
 * - the ad hoc group emergency alert (TS 24.379 §12.1A, and TS 24.281 for MCVideo) to the users that participant
 *   criteria select (`affiliated:<group>` or `member:<group>`, comma separated);
 * - the server's area notices (TS 24.379 §6.3.2.4, TS 24.282 §17.2.4): entering an emergency alert area raises an
 *   alert on [defaultGroup] (§12.1.1.6), unless this user's alert is already active.
 * Each alert carries where the phone is, as an Emergency location report.
 */
class EmergencyAlerts(
    private val client: McxClient,
    val service: LocationService,
    private val psi: String,
    private val userId: String,
    /** Whether this handler does the group alert; MCVideo's is the McvideoService's. */
    private val groupAlerts: Boolean = service != LocationService.MCVIDEO,
) : ServiceHandler {
    private val _alerts = MutableStateFlow<Map<String, EmergencyAlert>>(emptyMap())
    /** Other users' active alerts, by user and group. */
    val alerts: StateFlow<Map<String, EmergencyAlert>> = _alerts
    private val _alerting = MutableStateFlow<Set<String>>(emptySet())
    /** The groups on which this user's own alert is active. */
    val alerting: StateFlow<Set<String>> = _alerting
    private val _adhoc = MutableStateFlow<OwnAdhocAlert?>(null)
    val adhoc: StateFlow<OwnAdhocAlert?> = _adhoc
    val notices: MutableList<AreaNotice> = CopyOnWriteArrayList()
    val events: MutableList<String> = CopyOnWriteArrayList()
    var listener: AlertListener? = null
    /** Where the phone is, for an alert the area notice raises; the group it goes to. */
    var position: () -> EmergencyLocation? = { null }
    var defaultGroup: () -> String? = { null }

    private fun event(text: String) {
        events += text
        while (events.size > 50) events.removeAt(0)
        client.event("alert ${service.lower}: $text")
    }

    private fun key(user: String, group: String?) = "$user ${group ?: ""}"

    // The fields of the three info documents.

    private class Fields(val requestUri: String? = null, val callingUser: String? = null, val callingGroup: String? = null,
                         val clientId: String? = null, val alert: Boolean? = null, val originatedBy: String? = null,
                         val alertReceived: Boolean? = null, val adhocAlert: Boolean? = null, val adhocAlertReceived: Boolean? = null,
                         val criteria: String? = null, val alertArea: Boolean? = null, val groupArea: Boolean? = null,
                         val associatedGroup: String? = null)

    private val infoType = when (service) {
        LocationService.MCPTT -> McpttInfo.CONTENT_TYPE; LocationService.MCDATA -> McdataInfo.CONTENT_TYPE; LocationService.MCVIDEO -> McvideoInfo.CONTENT_TYPE
    }

    private fun encode(f: Fields): String = when (service) {
        LocationService.MCPTT -> McpttInfo(requestUri = f.requestUri, clientId = f.clientId, alert = f.alert, originatedBy = f.originatedBy,
            adhocAlert = f.adhocAlert, participantCriteria = f.criteria).encode()
        LocationService.MCDATA -> McdataInfo(requestUri = f.requestUri, clientId = f.clientId, alert = f.alert, originatedBy = f.originatedBy).encode()
        LocationService.MCVIDEO -> McvideoInfo(requestUri = f.requestUri, clientId = f.clientId, adhocAlert = f.adhocAlert,
            participantCriteria = f.criteria).encode()
    }

    private fun decode(text: String): Fields = when (service) {
        LocationService.MCPTT -> McpttInfo.parse(text).let {
            Fields(it.requestUri, it.callingUserId, it.callingGroupId, it.clientId, it.alert, it.originatedBy, it.alertReceived,
                it.adhocAlert, it.adhocAlertReceived, it.participantCriteria, it.emergencyAlertArea, it.groupGeoArea, it.associatedGroupId)
        }
        LocationService.MCDATA -> McdataInfo.parse(text).let {
            Fields(it.requestUri, it.callingUserId, it.callingGroupId, it.clientId, it.alert, it.originatedBy, it.alertReceived,
                alertArea = it.emergencyAlertArea, groupArea = it.groupGeoArea, associatedGroup = it.associatedGroupId)
        }
        LocationService.MCVIDEO -> McvideoInfo.parse(text).let {
            Fields(it.requestUri, it.callingUserId, it.callingGroupId, it.clientId, adhocAlert = it.adhocAlert,
                adhocAlertReceived = it.adhocAlertReceived, criteria = it.participantCriteria)
        }
    }

    // Sending.

    private suspend fun message(fields: Fields, location: EmergencyLocation?): org.openmcx.client.sip.SipResponse {
        val icsi = when (service) { LocationService.MCPTT -> Icsi.MCPTT; LocationService.MCDATA -> Icsi.MCDATA; LocationService.MCVIDEO -> Icsi.MCVIDEO }
        val request = client.ua.newRequest("MESSAGE", psi, NameAddr(psi))
        request.headers.add("P-Preferred-Service", icsi)
        if (service != LocationService.MCDATA) request.headers.add("Accept-Contact", "*;+g.3gpp.${service.lower};require;explicit")
        request.headers.add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(icsi)}\";require;explicit")
        val parts = mutableListOf(Part(infoType, encode(fields).toByteArray()))
        if (location != null) parts += Part(service.mime, location.encode(service).toByteArray())
        val (type, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", type)
        return client.ua.send(SipRequest(request.method, request.uri, request.headers, body))
    }

    private fun failure(what: String, response: org.openmcx.client.sip.SipResponse): AlertError {
        val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
        event("$what: ${response.status}$warning")
        return AlertError("${response.status} ${response.reason}$warning", response.status)
    }

    /**
     * Raise ([on], with where the phone is) or cancel this user's alert on [group]; with [originatedBy], an authorised
     * user cancels that user's alert (TS 24.379 §12.1.1.2, TS 24.282 §16.2.1.2).
     */
    suspend fun send(group: String, on: Boolean, location: EmergencyLocation?, originatedBy: String? = null) {
        check(groupAlerts) { "the ${service.lower} group alert is the service's own" }
        require(originatedBy == null || !on) { "only a cancellation names another user's alert" }
        if (on && location == null) throw AlertError("an emergency alert needs this phone's location")
        val response = message(Fields(requestUri = group, clientId = client.config.clientId, alert = on,
            originatedBy = originatedBy?.takeIf { it != userId }), if (on) location else null)
        if (!response.isSuccess) throw failure("alert on $group ${if (on) "raise" else "cancel"}", response)
        if (originatedBy == null || originatedBy == userId) _alerting.value = if (on) _alerting.value + group else _alerting.value - group
        else _alerts.value = _alerts.value - key(originatedBy, group)
        event("alert on $group ${if (on) "raised at ${location?.latitude}, ${location?.longitude}" else "cancelled"}" +
            (originatedBy?.let { " (of $it)" } ?: "") + ": ${response.status}")
    }

    /** §12.1A.1.1: an ad hoc group emergency alert to the users [criteria] select; the server names its group. */
    suspend fun sendAdhoc(criteria: String, location: EmergencyLocation?) {
        if (_adhoc.value != null) throw AlertError("an ad hoc alert is already active")
        val response = message(Fields(clientId = client.config.clientId, adhocAlert = true, criteria = criteria), location)
        if (!response.isSuccess) throw failure("ad hoc alert to $criteria", response)
        val group = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == infoType }
            ?.let { runCatching { decode(it.text) }.getOrNull() }?.callingGroup ?: throw AlertError("no ad hoc group in the answer")
        _adhoc.value = OwnAdhocAlert(group, criteria)
        event("ad hoc alert $group to $criteria: ${response.status}")
    }

    /** §12.1A.1.2: cancel this user's ad hoc alert, addressed to the group the server gave it. */
    suspend fun cancelAdhoc() {
        val own = _adhoc.value ?: throw AlertError("no ad hoc alert to cancel")
        val response = message(Fields(requestUri = own.group, clientId = client.config.clientId, adhocAlert = false), null)
        if (!response.isSuccess) throw failure("ad hoc alert ${own.group} cancel", response)
        _adhoc.value = null
        event("ad hoc alert ${own.group} cancelled: ${response.status}")
    }

    // Receiving.

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        if (request.method != "MESSAGE") return false
        val parts = Multipart.parse(request.contentType, request.body)
        val info = parts.firstOrNull { it.mediaType == infoType }?.let { runCatching { decode(it.text) }.getOrNull() } ?: return false
        val group = info.alert != null && groupAlerts
        if (!group && info.adhocAlert == null && info.alertArea == null && info.groupArea == null) return false
        val location = parts.firstOrNull { it.mediaType == service.mime }?.let { runCatching { EmergencyLocation.parse(it.text) }.getOrNull() }
        tx.respond(200)
        when {
            info.alertArea != null || info.groupArea != null -> area(info)
            info.adhocAlert != null -> adhoc(info, location)
            else -> groupAlert(info, location)
        }
        return true
    }

    private fun groupAlert(info: Fields, location: EmergencyLocation?) {
        val on = info.alert!!
        val group = info.callingGroup ?: info.requestUri
        val originator = info.originatedBy ?: info.callingUser
        if (info.alertReceived == true || originator == null || originator == userId) {
            // The receipt of our own alert or its cancellation, or an authorised user cancelled it.
            if (group != null) _alerting.value = if (on) _alerting.value + group else _alerting.value - group
            val by = info.callingUser?.takeIf { it != userId && info.alertReceived != true }
            event("own alert on $group ${if (on) "active" else "cancelled"}${by?.let { " by $it" } ?: ""}")
            if (!on && by != null && group != null) listener?.ownCancelled(service, group, by)
            return
        }
        val alert = EmergencyAlert(service, originator, group, false, location)
        _alerts.value = if (on) _alerts.value + (key(originator, group) to alert) else _alerts.value - key(originator, group)
        event("alert from $originator on $group: ${if (on) "raised at ${location?.latitude}, ${location?.longitude}" else "cancelled"}")
        listener?.alert(alert, on)
    }

    private fun adhoc(info: Fields, location: EmergencyLocation?) {
        val on = info.adhocAlert!!
        val group = info.callingGroup ?: info.requestUri
        if (info.adhocAlertReceived == true) {
            if (group != null) _adhoc.value = if (on) OwnAdhocAlert(group, info.criteria ?: _adhoc.value?.criteria ?: "") else null
            event("own ad hoc alert $group ${if (on) "active" else "ended"}")
            return
        }
        val user = info.callingUser ?: return
        if (user == userId) return
        val alert = EmergencyAlert(service, user, group, true, location, info.criteria)
        _alerts.value = if (on) _alerts.value + (key(user, group) to alert) else _alerts.value - key(user, group)
        event("ad hoc alert from $user ($group, ${info.criteria}): ${if (on) "raised" else "withdrawn"}")
        listener?.alert(alert, on)
    }

    private fun area(info: Fields) {
        val alertArea = info.alertArea != null
        val entered = (info.alertArea ?: info.groupArea)!!
        var notice = AreaNotice(service, alertArea, entered, info.associatedGroup)
        if (alertArea && entered && groupAlerts && _alerting.value.isEmpty()) {
            // §12.1.1.6 (TS 24.282 §16.2.1.4): entering an emergency alert area raises an alert, unless one is active.
            val target = defaultGroup()
            val where = position()
            notice = notice.copy(alerted = target)
            if (target != null && where != null) client.scope.launch {
                runCatching { send(target, true, where) }.onFailure { event("area alert on $target: ${it.message}") }
            } else notice = notice.copy(alerted = null).also { event("area alert: no ${if (target == null) "group" else "position"}") }
        }
        notices += notice
        while (notices.size > 20) notices.removeAt(0)
        event("${if (alertArea) "emergency alert area" else "geographic area of ${info.associatedGroup}"} ${if (entered) "entered" else "left"}")
        listener?.area(notice)
    }

    /** Alerts are soft state of the server; a new registration keeps ours, the server repeats the others' (§16.2.3.3). */
    override suspend fun flowLost() {}
}
