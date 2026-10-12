//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Another user's location, from the report answering our request (TS 24.379 §13.2.3.2). */
data class LocatedUser(val user: String, val latitude: Double?, val longitude: Double?, val servingCell: String?,
                       val timestamp: String?, val receivedAt: Long)

/**
 * One MC service's location procedures on the client (TS 24.379 clause 13 for MCPTT, TS 24.282 clause 17 for MCData,
 * TS 24.281 clause 18 for MCVideo): the participating function's configuration and requests arrive as MESSAGEs; the
 * [Reporter] decides when a report is due, and it goes as a MESSAGE to the function that configured it. What the phone
 * observes (position, radio identities, calls) is fed in by the app.
 */
class LocationClient(
    private val client: McxClient,
    val service: LocationService,
    /** The service's PSI: configurations and requests come from it; our requests go to it. */
    private val psi: String,
) : ServiceHandler {
    private val icsi = when (service) { LocationService.MCPTT -> Icsi.MCPTT; LocationService.MCDATA -> Icsi.MCDATA; LocationService.MCVIDEO -> Icsi.MCVIDEO }
    private val featureTag = "+g.3gpp.${service.lower}"
    val reporter = Reporter()
    /** Where reports go: the P-Asserted-Identity of the configuration (§13.3.4.2 step 1). */
    @Volatile private var server: String? = null
    @Volatile private var logOnPending = true
    private var ticker: Job? = null

    val sent: MutableList<Report> = CopyOnWriteArrayList()
    val events: MutableList<String> = CopyOnWriteArrayList()
    private val _located = MutableStateFlow<Map<String, LocatedUser>>(emptyMap())
    /** Other users' locations, by MC ID, from the reports answering our requests. */
    val located: StateFlow<Map<String, LocatedUser>> = _located
    @Volatile var lastRequestResult: String? = null
        private set

    private fun event(text: String) {
        events += text
        while (events.size > 50) events.removeAt(0)
        client.event("location ${service.lower}: $text")
    }

    /** Evaluate the triggers twice a second while the client runs. */
    fun start() {
        if (ticker != null) return
        ticker = client.scope.launch {
            while (isActive) {
                delay(500)
                reporter.poll(now())?.let { send(it) }
            }
        }
    }

    private fun now() = System.nanoTime() / 1_000_000

    // What the phone observes.
    fun position(point: Point) = reporter.position(point)
    fun radio(dimension: Dimension, value: String) = reporter.radio(dimension, value)
    fun signal(event: Event) = reporter.signal(event)
    fun setEmergency(on: Boolean) { reporter.emergency = on }

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        if (request.method != "MESSAGE") return false
        val parts = Multipart.parse(request.contentType, request.body)
        val part = parts.firstOrNull { it.mediaType == service.mime } ?: return false
        val content = try { LocationInfo.parse(part.text).first } catch (e: LocationError) {
            // An emergency alert's location, for instance, is the priority procedures' (§6.2.9.1).
            return false
        }
        // A report inside another body (an emergency alert) is not ours either.
        if (content is Report && parts.size > 1) return false
        val asserted = request.headers["P-Asserted-Identity"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
        if (asserted != psi) { tx.respond(403); event("refused ${content::class.simpleName} from $asserted"); return true }
        tx.respond(200)
        when (content) {
            is Configuration -> {
                reporter.configure(content, now())
                server = asserted
                event("configuration ${if (content.full) "full" else "update"}: ${content.triggers.size} triggers" +
                    if (reporter.configured) "" else " (reporting cancelled)")
                // §13.3.2.1: the configuration of a log-on; its InitialLogOn trigger fires now.
                if (logOnPending && reporter.configured) { logOnPending = false; reporter.signal(Event.InitialLogOn) }
            }
            is Request -> {
                event("request ${content.id}${if (content.refresh) " (refresh)" else ""}")
                client.scope.launch { send(reporter.answer(content.id, now())) }
            }
            is Report -> {
                val who = content.reportingUri.ifEmpty { asserted ?: "" }
                val p = content.location.coordinate
                _located.value = _located.value + (who to LocatedUser(who, p?.lat, p?.lon,
                    content.location.servingNcgi ?: content.location.servingEcgi, content.location.timestamp, System.currentTimeMillis()))
                event("report of $who: ${p?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "no coordinate"}")
            }
        }
        return true
    }

    /** §13.3.4.2: a single-bodied MESSAGE to the function that configured us. */
    private suspend fun send(report: Report) {
        val stamped = report.copy(location = report.location.copy(timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
        val target = server ?: psi
        val request = client.ua.newRequest("MESSAGE", target, NameAddr(target))
        request.headers.add("Accept-Contact", "*;$featureTag;require;explicit").add("P-Preferred-Service", icsi)
            .add("Content-Type", service.mime)
        val message = SipRequest(request.method, request.uri, request.headers, LocationInfo.reportXml(stamped, service).toByteArray())
        sent += stamped
        while (sent.size > 32) sent.removeAt(0)
        val response = runCatching { client.ua.send(message) }.getOrNull()
        event("report ${stamped.id.ifEmpty { stamped.triggers.joinToString() }}: ${response?.status ?: "no response"}")
    }

    /** §13.3.3.2: ask for [targets]' location; the answers arrive as reports, in [located]. */
    suspend fun request(targets: List<String>, refresh: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        val (type, body) = Multipart.encode(listOf(
            Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(targets).toByteArray()),
            Part(service.mime, LocationInfo.requestXml(Request(id, refresh), service).toByteArray())))
        val request = client.ua.newRequest("MESSAGE", psi, NameAddr(psi))
        request.headers.add("P-Preferred-Service", icsi)
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(icsi)}\";require;explicit")
            .add("Content-Type", type)
        val response = client.ua.send(SipRequest(request.method, request.uri, request.headers, body))
        lastRequestResult = "${response.status}${response.headers["Warning"]?.let { " $it" } ?: ""}"
        event("request $id for $targets: $lastRequestResult")
        return id
    }

    /** A new registration is a new log-on: its configuration fires InitialLogOn again. */
    override suspend fun flowLost() { logOnPending = true }
}
