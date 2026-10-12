package org.openmcx.client.app.alert

import org.openmcx.client.alert.AlertError
import org.openmcx.client.alert.EmergencyAlerts
import org.openmcx.client.location.LocationService
import org.openmcx.client.mcvideo.EmergencyLocation
import org.openmcx.client.mcvideo.McvideoService

/** One person's emergency alerts, merged from the services they came through. */
data class PersonAlert(
    val person: String,
    val services: List<LocationService>,
    val group: String?,
    val adhoc: Boolean,
    val criteria: String?,
    val latitude: Double?,
    val longitude: Double?,
    val at: Long,
    /** Per service, the MC ID and the group of each group alert: what cancelling it names. */
    val cancellable: List<Triple<LocationService, String, String>>,
)

/** The outcome of an alert request on each service it went to, in order. */
data class AlertOutcome(val service: LocationService, val error: Throwable?)

/**
 * The app's single emergency alert (the Call tab's Send emergency alert): raised on the selected group through every
 * provisioned MC service in turn (MCPTT, then MCData, then MCVideo), or on one of them; cancelled everywhere it is up.
 * The MCPTT and MCData alerts are [EmergencyAlerts]; the MCVideo group alert is the McvideoService's, its ad hoc alert
 * an [EmergencyAlerts] too. Received alerts are shown once per person.
 */
class AlertCenter(
    private val handlers: List<EmergencyAlerts>,
    private val video: () -> McvideoService?,
    /** The groups of each service, to find the selected group's counterpart. */
    private val groups: (LocationService) -> List<String>,
) {
    private fun handler(service: LocationService) = handlers.firstOrNull { it.service == service }

    /** The services a group alert can go through, in the order they are raised. */
    fun services(): List<LocationService> = listOfNotNull(
        LocationService.MCPTT.takeIf { handler(it) != null },
        LocationService.MCDATA.takeIf { handler(it) != null },
        LocationService.MCVIDEO.takeIf { video() != null })

    /** The services an ad hoc group emergency alert can go through. */
    fun adhocServices(): List<LocationService> = listOf(LocationService.MCPTT, LocationService.MCVIDEO).filter { handler(it) != null }

    /** [selected] if [service] has it, else the service's first group. */
    fun groupFor(service: LocationService, selected: String?): String? {
        val all = groups(service)
        return if (selected != null && selected in all) selected else all.firstOrNull()
    }

    private fun alerting(service: LocationService): Set<String> =
        if (service == LocationService.MCVIDEO) video()?.alerting?.value ?: emptySet() else handler(service)?.alerting?.value ?: emptySet()

    /** The services on which this user's own group alert is up, and on which groups. */
    fun own(): Map<LocationService, Set<String>> = services().associateWith { alerting(it) }.filterValues { it.isNotEmpty() }

    /** This user's ad hoc alert: its criteria, and the services it is up on. */
    fun ownAdhoc(): Pair<String, List<LocationService>>? {
        val up = handlers.mapNotNull { h -> h.adhoc.value?.let { h.service to it.criteria } }
        return up.firstOrNull()?.let { (_, criteria) -> criteria to up.map { it.first } }
    }

    private suspend fun send(service: LocationService, group: String, on: Boolean, location: EmergencyLocation?, originatedBy: String? = null) {
        if (service == LocationService.MCVIDEO) {
            val v = video() ?: throw AlertError("MCVideo is not provisioned")
            v.sendAlert(group, on, location, originatedBy)
        } else (handler(service) ?: throw AlertError("${service.name} is not provisioned")).send(group, on, location, originatedBy)
    }

    /** Raise the alert on [selected] through [only] or every service, MCPTT first and video last. */
    suspend fun raise(selected: String?, only: LocationService?, location: EmergencyLocation): List<AlertOutcome> =
        services().filter { only == null || it == only }.map { service ->
            val group = groupFor(service, selected)
            AlertOutcome(service, when {
                group == null -> AlertError("no ${service.name} group")
                group in alerting(service) -> null
                else -> runCatching { send(service, group, true, location) }.exceptionOrNull()
            })
        }

    /** Cancel this user's alerts, group and ad hoc, on [only] or every service they are up on. */
    suspend fun cancel(only: LocationService?): List<AlertOutcome> {
        val out = mutableListOf<AlertOutcome>()
        for ((service, groups) in own()) if (only == null || service == only)
            for (group in groups) out += AlertOutcome(service, runCatching { send(service, group, false, null) }.exceptionOrNull())
        for (h in handlers) if (h.adhoc.value != null && (only == null || h.service == only))
            out += AlertOutcome(h.service, runCatching { h.cancelAdhoc() }.exceptionOrNull())
        return out
    }

    /** An ad hoc group emergency alert to the users [criteria] select, through [only] or MCPTT and MCVideo. */
    suspend fun adhoc(criteria: String, only: LocationService?, location: EmergencyLocation?): List<AlertOutcome> =
        adhocServices().filter { only == null || it == only }.map { service ->
            AlertOutcome(service, runCatching { handler(service)!!.sendAdhoc(criteria, location) }.exceptionOrNull())
        }

    /** Other users' active alerts, one entry per person, newest first. */
    fun received(): List<PersonAlert> {
        data class Source(val service: LocationService, val user: String, val group: String?, val adhoc: Boolean,
                          val criteria: String?, val location: EmergencyLocation?, val at: Long)
        val sources = handlers.flatMap { h -> h.alerts.value.values.map { Source(h.service, it.user, it.group, it.adhoc, it.criteria, it.location, it.at) } } +
            (video()?.alerts?.value?.values?.map { Source(LocationService.MCVIDEO, it.user, it.group, false, null, it.location, it.at) } ?: emptyList())
        return sources.groupBy { person(it.user) }.map { (person, all) ->
            val newest = all.maxBy { it.at }
            val located = all.filter { it.location != null }.maxByOrNull { it.at }?.location
            PersonAlert(person, LocationService.entries.filter { s -> all.any { it.service == s } },
                all.firstOrNull { !it.adhoc }?.group ?: newest.group, all.all { it.adhoc }, all.firstNotNullOfOrNull { it.criteria },
                located?.latitude, located?.longitude, newest.at,
                all.filter { !it.adhoc && it.group != null }.map { Triple(it.service, it.user, it.group!!) })
        }.sortedByDescending { it.at }
    }

    /** Cancel [person]'s group alerts on every service they came through (TS 24.379 §12.1.1.2, if authorised). */
    suspend fun cancelOf(person: String): List<AlertOutcome> {
        val alert = received().firstOrNull { it.person == person } ?: return emptyList()
        return alert.cancellable.map { (service, user, group) ->
            AlertOutcome(service, runCatching { send(service, group, false, null, originatedBy = user) }.exceptionOrNull())
        }
    }

    companion object {
        /** A person's name from their MC IDs, which the OpenMCX provisioning gives a per-service suffix. */
        fun person(id: String) = id.removePrefix("sip:").substringBefore('@')
            .removeSuffix(".mcptt").removeSuffix(".mcdata").removeSuffix(".mcvideo")

        fun label(service: LocationService) = when (service) {
            LocationService.MCPTT -> "MCPTT"; LocationService.MCDATA -> "MCData"; LocationService.MCVIDEO -> "Video"
        }

        /** What went wrong, in a line: which services refused or failed. */
        fun summary(outcomes: List<AlertOutcome>, verb: String): String {
            if (outcomes.isEmpty()) return "Nothing to $verb"
            val failed = outcomes.filter { it.error != null }
            if (failed.isEmpty()) return "Emergency alert ${if (verb == "send") "sent" else "cancelled"} (${outcomes.joinToString(", ") { label(it.service) }})"
            val ok = outcomes.filter { it.error == null }.map { label(it.service) }
            return (if (ok.isEmpty()) "Emergency alert not ${if (verb == "send") "sent" else "cancelled"}" else "Done on ${ok.joinToString(", ")}") +
                "; " + failed.joinToString("; ") { "${label(it.service)}: ${reason(it.error!!)}" }
        }

        fun reason(e: Throwable): String {
            val status = (e as? AlertError)?.status ?: (e as? org.openmcx.client.mcptt.CallError)?.status
            return if (status == 403) "not allowed" else e.message ?: e.javaClass.simpleName
        }
    }
}
