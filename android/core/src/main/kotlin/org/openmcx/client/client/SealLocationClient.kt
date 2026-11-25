//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openmcx.client.http.HttpClient
import org.openmcx.client.location.Point
import org.openmcx.client.location.SealLocation
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The SEAL location management client's state (TS 24.545 §6.2.2, §6.2.20). */
data class SealState(val started: Boolean = false, val offline: Boolean = false, val periodic: Int = 0, val minimumInterval: Int = 0,
                     val reportsSent: Int = 0, val stored: Int = 0, val storedTotal: Int = 0, val historySent: Int = 0,
                     val lastStatus: Int = 0, val reportUri: String = "", val lastError: String? = null)

/**
 * The SEAL location management client (TS 24.545), as the Linux client has it (core/seal_location.cpp): besides the MC
 * services' location procedures, the client reports its position to the SEAL location management server over HTTP:
 * - §6.2.2.2.1: it reads its location reporting configuration from the server (GET) and starts the periodic trigger;
 * - §6.2.2.2.2: on the trigger it reports the position (POST to the report URI the configuration names);
 * - §6.2.20.2: while it cannot report (offline, a test aid standing in for a loss of connectivity) it stores the
 *   positions, and once back sends them in a history location result report.
 * [lms] is the server's base URI (`lms` of the provisioning; the lab's HTTP proxy routes `/lms/seal-lm/v1/...`).
 */
class SealLocationClient(private val client: McxClient, private val http: HttpClient, private val lms: String?,
                         private val position: () -> Point?) {
    private val _state = MutableStateFlow(SealState())
    val state: StateFlow<SealState> = _state
    @Volatile private var config = SealLocation.Configuration()
    private val stored = mutableListOf<SealLocation.Fix>()
    private var job: Job? = null

    private fun event(text: String) = client.event("seal location: $text")
    private fun user() = client.config.mcptt?.userId ?: client.config.mcdata?.userId ?: throw IllegalStateException("no MC service user")
    private fun bearer() = "Bearer " + (client.tokens?.accessToken ?: throw IllegalStateException("no access token"))

    /** §6.2.2.2.1: read the configuration and start the periodic trigger. */
    suspend fun start() {
        val base = lms ?: throw IllegalStateException("no location management server is configured")
        val url = "$base/seal-lm/v1/users/${HttpClient.enc(user())}/configuration"
        val response = http.request("GET", url, mapOf("Authorization" to bearer(), "Accept" to SealLocation.MIME))
        _state.update { it.copy(lastStatus = response.status) }
        if (response.status != 200) throw IllegalStateException("SEAL location configuration: ${response.status}")
        val read = SealLocation.parseConfiguration(response.text)
        if (read.reportUri.isEmpty() || read.periodic == 0) throw IllegalStateException("SEAL configuration without a periodic report")
        config = read
        _state.update { it.copy(started = true, periodic = read.periodic, minimumInterval = read.minimumInterval, reportUri = read.reportUri, lastError = null) }
        event("configuration received: periodic ${read.periodic} s, minimum interval ${read.minimumInterval} s")
        job?.cancel()
        job = client.scope.launch {
            while (isActive && _state.value.started) {
                tick()
                delay(1000L * maxOf(config.periodic, config.minimumInterval, 1))
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _state.update { it.copy(started = false) }
    }

    fun offline() = _state.update { it.copy(offline = true) }

    /** §6.2.20.2: back online, the positions stored meanwhile go in a history location result report. */
    suspend fun online() {
        _state.update { it.copy(offline = false) }
        val fixes = synchronized(stored) { stored.toList() }
        if (fixes.isEmpty() || config.historyUri.isEmpty()) return
        val response = http.request("POST", config.historyUri, mapOf("Authorization" to bearer(), "Content-Type" to SealLocation.MIME),
            SealLocation.historyXml("history", fixes).toByteArray())
        _state.update { it.copy(lastStatus = response.status) }
        if (response.status != 200) throw IllegalStateException("SEAL history location result report: ${response.status}")
        synchronized(stored) { repeat(fixes.size) { stored.removeAt(0) } }
        _state.update { it.copy(historySent = it.historySent + fixes.size, stored = stored.size) }
        event("history location result report: ${fixes.size} positions")
    }

    private fun fix(): SealLocation.Fix? = position()?.let {
        SealLocation.Fix(it.latitude, it.longitude, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
    }

    /** §6.2.2.2.2: the periodic trigger: report the position (or, offline, store it). */
    private suspend fun tick() {
        val fix = fix() ?: return
        if (_state.value.offline) {
            synchronized(stored) { stored += fix; while (stored.size > 1000) stored.removeAt(0) }
            _state.update { it.copy(stored = stored.size, storedTotal = it.storedTotal + 1) }
            return
        }
        runCatching {
            http.request("POST", config.reportUri, mapOf("Authorization" to bearer(), "Content-Type" to SealLocation.MIME),
                SealLocation.reportXml(user(), "periodic", fix).toByteArray())
        }.onSuccess { r ->
            _state.update { it.copy(lastStatus = r.status, reportsSent = it.reportsSent + if (r.status == 200) 1 else 0,
                lastError = if (r.status == 200) null else "report refused: ${r.status}") }
        }.onFailure { e -> _state.update { it.copy(lastStatus = 0, lastError = e.message) } }
    }
}
