//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.msrp.MsrpMessage
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr

// The MCData client's pre-established session (TS 24.282 §18.3): establishment by an INVITE to the participating MCData
// function (§18.3.2.1), release by either side (§18.3.3.1.1, §18.3.3.1.2), modification by the participating function
// (§18.3.4.1.2); and the SDS communications over it (§9.2.5): the REFER that starts a one-to-one or group standalone
// SDS over the media plane or SDS session (§9.2.5.2.1.1, §9.2.5.3.1.1), emergency and imminent peril ones included
// (§9.2.5.2.1.1 1a), §9.2.5.3.1.1 3A)-3D)); the participating function's re-INVITE that reports its outcome or offers
// a communication (§9.2.5.2.1.2, §9.2.5.3.1.2); the release by a REFER "BYE" or the participating function's re-INVITE
// (§9.2.5.4.1.1, §9.2.5.4.1.2). The media plane is TS 24.582 §12.1 with §6.1.
//
// Outbound only. The session's offer is "a=setup:actpass" with the phone's one host candidate, TCP active on port 9
// (RFC 6544 §4.5: an active candidate's port is 9; RFC 4145 §4): the phone never listens. The OpenMCX participating
// function answers "passive" and keeps listening on the session's MSRP URI for the session's lifetime
// (openmcx-server src/mcdata/pre_established.cpp, pre_session_invite: an offer that would listen gets 488), so this
// client connects anew to it for each communication and closes the connection when the communication ends (TS 24.582
// §12.1), exactly as on demand. A participating function answering "active" would need a listener: the session is then
// kept but not used, the communications going on demand (as the Linux client does).
//
// Lifecycle: the session's refresh (RFC 4028, this client the refresher unless the 2xx names the UAS) is timed on the
// wall clock, so a refresh that Doze delayed is sent at the next wake-up; on a lost flow the dialog is kept, and once
// MCData is authorised again on the new registration [resume] refreshes it with the new Contact (a target refresh,
// RFC 3261 §12.2), or, refused, releases it and establishes a new one.
//
// Not implemented: ICE connectivity checks (the answer of the OpenMCX server carries no ICE attributes, which turns ICE
// off, RFC 8839 §4.2.3), FD over the session (TS 24.282 has none). The priority states of §6.2.8 (MDEG, MDEGC, MDIG,
// MDIGC, MDEPC, MDEPP) are the media service's ([McdataPriority]), as on demand.
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.582 V19.0.0 (Rel-19), RFC 3515, RFC 4488, RFC 4538, RFC 2392,
// RFC 5366, RFC 5368, RFC 4975, RFC 4145, RFC 6544, RFC 8839, RFC 4028, RFC 3261.

/** The pre-established session as the status and the UI show it. */
data class PreSessionStatus(
    /** establishing, established, releasing, suspended (flow lost), released, failed. */
    val state: String,
    val uri: String? = null,
    val callId: String? = null,
    /** The communication over it: requested, established, releasing; null when free. */
    val communication: String? = null,
    val communicationUri: String? = null,
    val communicationId: Int? = null,
    /** The <mcdata-communication-state> of the participating function's last re-INVITE. */
    val lastState: String? = null,
    val established: Int = 0,
    val terminated: Int = 0,
    /** The participating function's a=setup ("passive": usable). */
    val setup: String? = null,
    val sessionExpires: Int = 0,
    val refreshes: Int = 0,
    /** Times the session was kept (target refresh) or made anew after a new registration. */
    val resumed: Int = 0,
    val reestablished: Int = 0,
    val error: String? = null,
    val releaseReason: String? = null,
)

class McdataPreSession internal constructor(private val s: McdataMediaService.Internals) {
    private val _status = MutableStateFlow<PreSessionStatus?>(null)
    /** Null: no session was asked for. */
    val status: StateFlow<PreSessionStatus?> = _status
    private val mutex = Mutex()
    private val peer get() = s.peer

    /** The user wants a session: it is made again after a new registration (cleared by the user's or server's release). */
    @Volatile var wanted = false
        private set
    @Volatile internal var dialog: Dialog? = null
    @Volatile private var uri: String? = null
    @Volatile internal var local: MsrpMedia? = null
    @Volatile internal var remote: MsrpMedia? = null
    @Volatile private var phase = "idle"
    @Volatile internal var current: MediaCommunication? = null
    @Volatile private var communication: String? = null
    @Volatile private var communicationUri: String? = null
    @Volatile private var lastState: String? = null
    @Volatile private var established = 0
    @Volatile private var terminated = 0
    @Volatile private var sessionExpires = 0
    @Volatile private var refreshes = 0
    @Volatile private var resumed = 0
    @Volatile private var reestablished = 0
    @Volatile private var error: String? = null
    @Volatile private var releaseReason: String? = null
    @Volatile private var outcome: CompletableDeferred<McdataInfo?>? = null
    private var refresher: Job? = null

    private fun publish() {
        _status.value = PreSessionStatus(phase, uri, dialog?.callId, communication, communicationUri, current?.id, lastState,
            established, terminated, remote?.setup, sessionExpires, refreshes, resumed, reestablished, error, releaseReason)
    }

    /** Whether a communication can start over the session: established, free, and answered "passive" (this client connects). */
    val usable: Boolean get() = phase == "established" && current == null && dialog != null && uri != null && remote?.setup == "passive"

    private fun sdp(media: MsrpMedia) = Part("application/sdp", media.encode(s.clock()).toByteArray())

    /** TS 24.282 §18.3.1.1 (an offer, "actpass") or §18.3.1.2 (an answer, "active": this client connects). */
    internal fun media(setup: String, direction: String = "sendrecv"): MsrpMedia =
        (local ?: throw SdsError("no pre-established session")).copy(setup = setup, direction = direction)

    // Establishment (TS 24.282 §18.3.2.1).

    /**
     * TS 24.282 §18.3.2.1: step 1, the one host candidate (TCP active, port 9: the phone does not listen); step 2, an
     * initial INVITE to the participating function's PSI (1)), the SDS feature tag in Contact (3)), the two
     * Accept-Contact (4), 6)), the SDS ICSI in P-Preferred-Service (5)), Supported "timer" (7)), Session-Expires
     * without a refresher (8)), the MCData-Info with <pre-established-session-ind> "true" (9)), the SDP offer of
     * §18.3.1.1 (10)): "m=message", "a=sendrecv", the MSRP URI, the accepted types, "a=setup:actpass".
     * The 2xx (§18.3.2.2 step 9): the URI identifying the session in its Contact, the MSRP media of the answer.
     */
    suspend fun establish(): PreSessionStatus = mutex.withLock {
        if (phase in setOf("establishing", "established", "releasing")) throw SdsError("an MCData pre-established session already exists")
        wanted = true
        establishLocked()
        _status.value!!
    }

    private suspend fun establishLocked() {
        phase = "establishing"; error = null; releaseReason = null; communication = null; current = null
        val address = peer.localAddress()
        val media = MsrpMedia.local(address, MsrpMessage.newIdent(), "actpass", "sendrecv").copy(ice = iceAttributes(address))
        local = media; remote = null; uri = null; dialog = null
        publish()
        val info = McdataInfo(preEstablishedSession = true)
        val headers = s.headers("sds") + ("Session-Expires" to "$SESSION_EXPIRES")
        val (response, d) = try { peer.invite(listOf(sdp(media), Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())), headers) }
            catch (e: Exception) { phase = "failed"; error = e.message; publish(); throw SdsError("pre-established session: ${e.message}") }
        if (d == null) {
            phase = "failed"; error = "${response.status} ${response.reason}" + (response.headers["Warning"]?.let { " ($it)" } ?: "")
            publish()
            s.event("pre-established session refused: $error")
            throw SdsError("pre-established session: $error", response.status)
        }
        dialog = d
        uri = response.headers["Contact"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() } ?: d.remoteTarget
        remote = runCatching {
            MsrpMedia.parse(Multipart.parse(response.contentType, response.body).first { it.mediaType == "application/sdp" }.text)
        }.getOrNull()
        timer(response.headers["Session-Expires"], ourRequest = true)
        phase = "established"
        publish()
        s.event("pre-established session $uri established (answer a=setup:${remote?.setup})")
    }

    /**
     * RFC 4028 §7.4/§9: the session timer of the 2xx (or of a request this client answers): this client refreshes
     * when the refresher is itself ("uac" of its own request), half way, on the wall clock so a refresh that Doze held
     * back goes at the next wake-up.
     */
    private fun timer(value: String?, ourRequest: Boolean) {
        val interval = value?.substringBefore(';')?.trim()?.toIntOrNull() ?: 0
        if (interval <= 0) { if (ourRequest) { sessionExpires = 0; refresher?.cancel() }; return }
        sessionExpires = interval
        val named = value!!.substringAfter("refresher=", "").substringBefore(';').trim().lowercase()
        val ours = if (ourRequest) named != "uas" else named == "uas"
        refresher?.cancel()
        if (!ours) return
        val due = System.currentTimeMillis() + interval * 500L
        refresher = s.scope.launch {
            while (System.currentTimeMillis() < due) delay(minOf(30_000L, maxOf(1L, due - System.currentTimeMillis())))
            refresh()
        }
    }

    /** RFC 4028 §10 with TS 24.282 §18.3.4: a re-INVITE in the session's dialog with its media as negotiated. */
    private suspend fun refresh(): Boolean {
        val d = dialog ?: return false
        val response = runCatching {
            peer.reinvite(d, listOf(sdp(media("actpass"))), listOf("Contact" to peer.contact("sds"), "Supported" to "timer",
                "Session-Expires" to "${if (sessionExpires > 0) sessionExpires else SESSION_EXPIRES};refresher=uac"))
        }.getOrElse { s.event("pre-established session refresh: ${it.message}"); return false }
        if (!response.isSuccess) {
            s.event("pre-established session refresh: ${response.status} ${response.reason}")
            if (response.status == 481 || response.status == 408) ended("refresh ${response.status}")
            return false
        }
        refreshes++
        timer(response.headers["Session-Expires"], ourRequest = true)
        publish()
        return true
    }

    // Release (TS 24.282 §18.3.3.1.1).

    /** TS 24.282 §18.3.3.1.1: the BYE of §13.2.2.2.2.1 in the session's dialog; a communication over it goes with it. */
    suspend fun release(): PreSessionStatus = mutex.withLock {
        wanted = false
        val d = dialog ?: throw SdsError("no MCData pre-established session to release")
        phase = "releasing"; publish()
        current?.let { s.released(it, "released with the pre-established session") }
        runCatching { peer.bye(d) }
        ended("released by the user")
        _status.value!!
    }

    private fun ended(why: String) {
        refresher?.cancel(); refresher = null
        current?.let { s.released(it, "pre-established session ended: $why") }
        outcome?.complete(null)
        current = null; communication = null; communicationUri = null; dialog = null
        phase = "released"; releaseReason = why
        publish()
        s.event("pre-established session released: $why")
    }

    // Communications over the session (TS 24.282 §9.2.5).

    /**
     * TS 24.282 §9.2.5.2.1.1 (one-to-one) and §9.2.5.3.1.1 (group): the REFER outside a dialog to the session's URI
     * (1)); Refer-To the Content-ID URL of the resource list (2), RFC 2392); its single <entry> the called user or
     * group (4) / 3)), whose hname "body" holds the SDP offer of §9.2.3.2.1 or §9.2.4.2.1 (the session's media, with
     * the PCK of a one-to-one communication) and the MCData-Info (3)), protected as the request's own bodies
     * (§6.5.2.3.1); P-Preferred-Service the SDS ICSI (5) / 4)); Supported "norefersub", Refer-Sub "false" (7) / 6),
     * RFC 4488); Target-Dialog the session (8) / 7), RFC 4538); the SDS feature tag in Contact (9) / 8)); Require
     * "multiple-refer" (RFC 5368 §4). A priority communication also carries an MCData-Info body of the REFER itself
     * (§9.2.5.2.1.1 1a)a), §9.2.5.3.2.1 6A)), its Resource-Priority ([extraHeaders], §9.2.5.3.1.1 3B), 3D)) and the
     * location of an emergency alert in the REFER's body ([extraParts], §6.2.8.1.1 3)b): the entry's URI has no room
     * for it, as the Linux client's d44fc8b).
     *
     * The 2xx is followed by the participating function's re-INVITE "establish-success" (returned: its MCData-Info,
     * the grant) or "establish-fail" (an [SdsError]); then the media plane ([McdataMediaService.connect]).
     */
    internal suspend fun originate(c: MediaCommunication, target: String, info: McdataInfo, offer: MsrpMedia,
                                   extraParts: List<Part> = emptyList(), extraHeaders: List<Pair<String, String>> = emptyList(),
                                   priority: Boolean = false): McdataInfo {
        val d = dialog ?: throw SdsError("no pre-established session")
        val session = uri ?: throw SdsError("no pre-established session")
        val entryParts = peer.sealEntry(listOf(
            Part("application/sdp", offer.encode(s.clock()).toByteArray(), listOf("Content-ID" to "<${newId()}@mcx>")),
            Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray(), listOf("Content-ID" to "<${newId()}@mcx>"))))
        val contentId = "${newId()}@mcx"
        val list = Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(entryUri(target, entryParts))).toByteArray(),
            listOf("Content-ID" to "<$contentId>"))
        val body = buildList {
            add(list)
            if (priority) add(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
            addAll(extraParts)
        }
        val headers = listOf("Refer-To" to "<cid:$contentId>", "Refer-Sub" to "false", "Supported" to "norefersub",
            "Require" to "multiple-refer", "Target-Dialog" to targetDialog(d), "P-Preferred-Service" to Icsi.MCDATA_SDS,
            "Contact" to peer.contact("sds")) + extraHeaders
        val waiting = CompletableDeferred<McdataInfo?>()
        mutex.withLock {
            if (!usable) throw SdsError("the pre-established session is in use")
            current = c; communication = "requested"; lastState = null; outcome = waiting
            c.preEstablished = true; c.dialog = d
            publish()
        }
        s.event("communication ${c.id}: REFER over the pre-established session to $target (${info.requestType})")
        val response = try { peer.refer(session, body, headers) } catch (e: Exception) { free(c); throw SdsError("REFER: ${e.message}") }
        c.status = response.status; c.warning = response.headers["Warning"]
        if (!response.isSuccess) {
            free(c)
            throw SdsError("REFER: ${response.status} ${response.reason}" + (response.headers["Warning"]?.let { " ($it)" } ?: ""), response.status)
        }
        s.event("communication ${c.id}: REFER accepted (${response.status})")
        // The used priority (§9.2.5.2.1.1, final 2xx 1)) is in an MCData-Info of the 2xx; the outcome in the re-INVITE.
        val granted = withTimeoutOrNull(OUTCOME_TIMEOUT_MS) { waiting.await() }
        if (granted == null) {
            if (current === c) free(c)
            throw SdsError(if (lastState == "establish-fail") "establish-fail" else "no establish-success within the pre-established session")
        }
        return granted
    }

    /** The communication is over on this side; the session stays (TS 24.582 §12.1: the MSRP connection is closed). */
    private fun free(c: MediaCommunication) {
        if (current !== c) return
        current = null; communication = null; communicationUri = null; outcome = null
        c.msrp?.close()
        publish()
    }

    /**
     * TS 24.282 §9.2.5.4.1.1: the user leaves the communication over the session (or a standalone transfer ends, TS
     * 24.582 §6.1.1.2.1): 1) the media plane released; 2)-9) a REFER outside a dialog to the session's URI (3)),
     * Refer-Sub "false" (4)), Supported "norefersub" (5)), Refer-To the MCData session identity (6)) with method "BYE"
     * (7)), Target-Dialog the session (8)). The participating function's re-INVITE "terminated" completes it.
     */
    internal suspend fun leave(c: MediaCommunication) {
        if (current !== c) return
        val d = dialog ?: return free(c)
        val session = uri ?: return free(c)
        c.msrp?.close()
        communication = "releasing"; publish()
        val identity = communicationUri ?: session
        val response = runCatching {
            peer.refer(session, emptyList(), listOf("Refer-To" to "<$identity;method=BYE>", "Refer-Sub" to "false",
                "Supported" to "norefersub", "Target-Dialog" to targetDialog(d), "P-Preferred-Service" to Icsi.MCDATA_SDS,
                "Contact" to peer.contact("sds")))
        }.getOrNull()
        s.event("communication ${c.id}: REFER BYE over the pre-established session: ${response?.status}")
        // Refused, or the participating function released it first: the communication is over on this side all the same.
        if (response?.isSuccess != true && current === c) free(c)
    }

    // The participating function's requests in the session's dialog.

    /**
     * The participating function's requests in the session's dialog: BYE (§18.3.3.1.2: 1) a communication over it
     * released, TS 24.582 §8; the 200 (OK)), UPDATE (§18.3.4.1.2), re-INVITE by its <mcdata-communication-state>:
     *   establish-success / establish-fail  the outcome of this client's REFER (§9.2.5.2.1.1, §9.2.5.3.1.1)
     *   terminated                          the end of the communication this client left (§9.2.5.4.1.1)
     *   establish-request                   a communication offered (§9.2.5.2.1.2, §9.2.5.3.1.2): §9.2.3.2.4 or §9.2.4.2.4
     *   terminate-request                   the communication released by the server (§9.2.5.4.1.2)
     *   none                                a modification (§18.3.4.1.2), or the priority of the communication (§6.2.8.4.2)
     * The answer to an offer is "active" (this client connects), the session's own MSRP URI.
     */
    internal suspend fun handle(tx: MediaInvite): Boolean {
        val request = tx.request
        val d = dialog ?: return false
        if (request.callId != d.callId) return false
        when (request.method) {
            "BYE" -> {
                tx.respond(200)
                wanted = false
                ended("released by the participating function" + (request.headers["Reason"]?.let { ": $it" } ?: ""))
                return true
            }
            "UPDATE" -> {
                val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrDefault(emptyList())
                val offer = parts.any { it.mediaType == "application/sdp" }
                timer(request.headers["Session-Expires"], ourRequest = false)
                if (offer) tx.respond(200, sdp(media("active")).content, "application/sdp", answerHeaders(request.headers["Session-Expires"]))
                else tx.respond(200, extra = answerHeaders(request.headers["Session-Expires"]))
                return true
            }
            "INVITE" -> {}
            else -> return false
        }
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrDefault(emptyList())
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
        val offer = parts.firstOrNull { it.mediaType == "application/sdp" }?.text
        val state = info?.communicationState
        if (state != null) lastState = state
        timer(request.headers["Session-Expires"], ourRequest = false)
        val answer = sdp(media(if (offer != null) "active" else "actpass")).content
        val extra = answerHeaders(request.headers["Session-Expires"])
        val contact = request.headers["Contact"]
        when (state) {
            "establish-request" -> {
                // §9.2.5.2.1.2 1): the session carries a communication already.
                if (current != null || offer == null || info == null) { tx.respond(if (offer == null || info == null) 488 else 486); publish(); return true }
                val c = s.offeredOverSession(tx, parts, info, offer, this)
                if (c != null) {
                    current = c; communication = "established"; established++
                    communicationUri = contact?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
                    s.event("communication ${c.id}: offered over the pre-established session ($communicationUri)")
                }
            }
            "terminate-request" -> {
                // §9.2.5.4.1.2: i) the 200 (OK); ii) the media plane resources of the communication released.
                tx.respond(200, answer, "application/sdp", extra)
                current?.let { c -> s.released(c, "released by the server (terminate-request)"); terminated++; free(c) }
            }
            "establish-success" -> {
                tx.respond(200, answer, "application/sdp", extra)
                if (communication == "requested") {
                    communication = "established"; established++
                    // The communication's MCData session identity: a Contact naming a focus (§9.2.5.2.2.2 3)iii)); else the session's.
                    communicationUri = if (contact?.contains("isfocus") == true) runCatching { NameAddr.parse(contact).uri }.getOrNull() else uri
                    // §9.2.5.3.1.1 0)b): the Resource-Priority noted.
                    current?.let { c -> request.headers["Resource-Priority"]?.let { c.resourcePriority = it } }
                    outcome?.complete(info ?: McdataInfo())
                }
            }
            "establish-fail" -> {
                tx.respond(200, answer, "application/sdp", extra)
                // §9.2.5.2.1.1 2)i): the user notified of the failure, the states restored (the communication's flags).
                current?.let { c -> c.emergency = false; c.imminentPeril = false }
                val waiting = outcome
                current?.let { free(it) }
                waiting?.complete(null)
            }
            "terminated" -> {
                tx.respond(200, answer, "application/sdp", extra)
                // §9.2.5.4.1.1 1)i): the user notified of the successful termination.
                current?.let { c -> if (!c.ended) s.released(c, "left the communication over the pre-established session"); terminated++; free(c) }
            }
            else -> {
                val c = current
                if (c != null && info != null && communication == "established") {
                    // §6.2.8.4.2, §6.2.7.4 (the participating function's priority re-INVITE): the session's media in the 200
                    // (OK), for a group the g.3gpp.mcdata feature tag and the MCData ICSI in its Contact (§6.2.7.4 7)-8)).
                    val reply = s.priorityFromServer(c, info)
                    tx.respond(200, answer, "application/sdp", listOf("Contact" to reply) + extra)
                } else {
                    // §18.3.4.1.2: 1) an acceptable "m=message" offer (else 488); 2)-3) the 200 (OK) with the answer.
                    if (offer != null && runCatching { MsrpMedia.parse(offer) }.isFailure) { tx.respond(488); return true }
                    tx.respond(200, answer, "application/sdp", listOf("Contact" to peer.contact("sds")) + extra)
                    s.event("pre-established session modified by the participating function")
                }
            }
        }
        publish()
        return true
    }

    /** RFC 4028 §9: the 2xx to a refresh carries its Session-Expires and Require "timer". */
    private fun answerHeaders(sessionExpires: String?) =
        if (sessionExpires == null) emptyList() else listOf("Session-Expires" to sessionExpires, "Require" to "timer")

    // Lifecycle.

    /** The flow is gone: a communication over the session fails; the dialog is kept for [resume]. */
    internal fun flowLost() {
        if (phase != "established" && phase != "establishing") return
        refresher?.cancel(); refresher = null
        current?.let { c -> c.msrp?.close(); free(c) }
        outcome?.complete(null)
        phase = "suspended"; publish()
    }

    /**
     * MCData is authorised again on a new registration: the session the user wants is refreshed in its dialog with the
     * new Contact (a target refresh, RFC 3261 §12.2.1.1, TS 24.282 §18.3.4); refused, it is released and a new one
     * established (§18.3.2.1).
     */
    suspend fun resume() = mutex.withLock {
        if (!wanted || phase in setOf("establishing", "established", "releasing")) return@withLock
        if (phase == "suspended" && dialog != null) {
            phase = "established"
            if (refresh()) { resumed++; publish(); s.event("pre-established session kept on the new registration"); return@withLock }
            dialog?.let { runCatching { withTimeoutOrNull(5_000) { peer.bye(it) } } }
        }
        reestablished++
        runCatching { establishLocked() }.onFailure { s.event("pre-established session not re-established: ${it.message}") }
    }

    /** The user logs off: the session is released (TS 24.282 §18.3.3.1.1), not re-established. */
    internal suspend fun logOff() { if (dialog != null) runCatching { release() } }

    companion object {
        /** RFC 4028 §4: the Session-Expires offered (TS 24.282 §18.3.2.1 step 8), in seconds. */
        const val SESSION_EXPIRES = 1800
        const val OUTCOME_TIMEOUT_MS = 15_000L
        /** The boundary of the entry's hname "body" (TS 37.579-1 table 5.5.2.12-2; the Linux client's). */
        const val ENTRY_BOUNDARY = "mcdata-entry"

        private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        /** RFC 4538 §7: "callid;local-tag=...;remote-tag=..." of the session's dialog. */
        fun targetDialog(d: Dialog) = "${d.callId};local-tag=${d.localTag};remote-tag=${d.remoteTag}"

        /**
         * TS 24.282 §18.3.2.1 step 1 and §18.3.1.1 with RFC 8839 §5.1, §5.4 and RFC 6544 §4.5: the ICE attributes: the
         * client's credentials and its one host candidate, TCP active on port 9 (the phone does not listen).
         * RFC 8445 §5.1.2.1: priority = 2^24 * 126 (host) + 2^8 * local preference + (256 - component 1); RFC 6544
         * §4.2: the local preference of an active TCP host candidate, 2^13 * 6 (direction preference) + 8191.
         */
        fun iceAttributes(address: String, ufrag: String = newId().take(8), password: String = newId() + newId().take(8)): String {
            val priority = (126L shl 24) or ((((6L shl 13) or 8191L)) shl 8) or 255L
            return "a=ice-ufrag:$ufrag\r\na=ice-pwd:$password\r\na=candidate:1 1 TCP $priority $address 9 typ host tcptype active\r\n"
        }

        /** RFC 3261 §19.1.1, §25.1: an hname or hvalue escaped, all but unreserved and hnv-unreserved characters as %HH. */
        fun escapeUriHeader(value: ByteArray): String = buildString {
            for (b in value) {
                val ch = (b.toInt() and 0xff).toChar()
                if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.!~*'()[]/?:+$") append(ch)
                else append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xf]).append("0123456789ABCDEF"[b.toInt() and 0xf])
            }
        }

        /** RFC 3261 §19.1.2: undoes [escapeUriHeader]. */
        fun unescapeUriHeader(value: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                if (value[i] == '%' && i + 2 < value.length) {
                    out.write(value.substring(i + 1, i + 3).toInt(16)); i += 3
                } else { out.write(value[i].code); i++ }
            }
            return out.toByteArray()
        }

        /**
         * TS 24.282 §9.2.5.2.1.1 4), §9.2.5.3.1.1 3) (the form of TS 24.379 §10.1.2.2.2.1 step 3, TS 37.579-1 table
         * 5.5.2.12-2): the entry's "uri": the target, "?", the Content-Type "multipart/mixed" with the boundary and the
         * hname "body" with the parts.
         */
        fun entryUri(target: String, parts: List<Part>): String {
            require('?' !in target) { "a REFER target with a headers portion" }
            val (_, body) = Multipart.encode(parts, ENTRY_BOUNDARY)
            return "$target?Content-Type=${escapeUriHeader("multipart/mixed;boundary=$ENTRY_BOUNDARY".toByteArray())}" +
                "&body=${escapeUriHeader(body)}"
        }

        /** Reads an entry "uri": the target and the parts of its hname "body" (for the tests' participating function). */
        fun parseEntryUri(uri: String): Pair<String, List<Part>> {
            val target = uri.substringBefore('?')
            val fields = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
                .associate { it.substringBefore('=') to unescapeUriHeader(it.substringAfter('=')) }
            val type = fields["Content-Type"]?.let { String(it, Charsets.UTF_8) }
            val body = fields["body"] ?: return target to emptyList()
            return target to Multipart.parse(type, body)
        }
    }
}
