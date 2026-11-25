//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress

// The MCPTT client's pre-established session (TS 24.379 §8): establishment (§8.2.1), modification by the user
// (§8.3.1.1) or by the participating function (§8.3.1.2), release by either side (§8.4.1.1, §8.4.1.2); the calls over
// it: originated by REFER (§10.1.1.2.2.1 prearranged, §10.1.2.2.2.1 chat, §11.1.1.2.2.1 private and first-to-answer,
// §11.1.6.2.2.1 ambient listening; a broadcast group call is a prearranged one) and left by REFER "BYE" (§6.2.4.2),
// received by Connect (automatic commencement, TS 24.380 §9.2.2.3.2), by re-INVITE (manual, TS 24.379 §10.1.1.2.2.2)
// or by an on-demand INVITE with Replaces naming the session (§6.3.2.2.5.3 step 1: an emergency or Priv-Answer-Mode
// call; the session is then ended and made again after the call); the media plane control of TS 24.380 §9.2.2
// (PreEstablishedControl.kt).
//
// The media: the session's speech, RTCP and floor sockets are bound for its lifetime; a call over it uses them (its
// MediaSession keeps them open at the end). The control channel has one reader, this class's: "MCPC" messages go to the
// state machine, floor control to the call's floor participant. The offer carries host candidates only (the PARITY_PLAN
// decision: UDP to the server network, as every Android call): the phone runs no connectivity checks and answers no
// STUN, so a participating function running ICE finds it failed and sends to the default addresses (the OpenMCX server
// does: it records the failure only).
//
// Lifecycle: as the MCData session (mcdata/PreEstablished.kt): the refresh on the wall clock (Doze), the dialog kept
// across a lost flow and refreshed with the new Contact, or made anew, on the next MCPTT authorisation.
//
// Not implemented: ICE connectivity checks; protected calls over the session (the PCK I_MESSAGE of a Connect, the key
// re-INVITE of a first-to-answer call: such calls are refused 'Not Accepted' and go on demand); ad hoc group calls
// over the session (TS 24.379 §17 has none); the location of the REFER (§11.1.1.2.2.1 step 13).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), RFC 3515, RFC 4488, RFC 4538, RFC 2392,
// RFC 5366, RFC 5368, RFC 3891, RFC 8445, RFC 8839, RFC 4028.

/** The MCPTT pre-established session as the status and the UI show it. */
data class McpttPreSessionStatus(
    /** establishing, established, releasing, suspended (flow lost), replaced (by a call's INVITE), released, failed. */
    val state: String,
    val uri: String? = null,
    val callId: String? = null,
    /** TS 24.380 §9.2.2: start-stop, not-in-use, in-use. */
    val control: String = "start-stop",
    val implicit: Boolean = false,
    val callIdOver: Int? = null,
    val calls: Int = 0,
    val lastControl: String? = null,
    val floorPriority: Int? = null,
    val refreshes: Int = 0,
    val modified: Int = 0,
    val resumed: Int = 0,
    val reestablished: Int = 0,
    val error: String? = null,
    val releaseReason: String? = null,
    val audioPort: Int? = null,
    val floorPort: Int? = null,
)

class McpttPreSession internal constructor(private val svc: McpttService) {
    private val _status = MutableStateFlow<McpttPreSessionStatus?>(null)
    val status: StateFlow<McpttPreSessionStatus?> = _status
    private val mutex = Mutex()
    @Volatile var wanted = false
        private set
    @Volatile private var implicit = false
    @Volatile internal var dialog: Dialog? = null
    @Volatile private var uri: String? = null
    @Volatile private var ports: MediaPorts? = null
    @Volatile internal var local: LocalMedia? = null
    @Volatile internal var remote: RemoteMedia? = null
    @Volatile private var phase = "idle"
    private val control = PreSessionControl(0)
    /** The call over the session; [pending] its Connect while this client's REFER or answer waits for it. */
    @Volatile internal var call: Call? = null
    @Volatile private var pending: CompletableDeferred<McpcMessage?>? = null
    @Volatile private var calls = 0
    @Volatile private var lastControl: String? = null
    @Volatile private var refreshes = 0
    @Volatile private var modified = 0
    @Volatile private var resumed = 0
    @Volatile private var reestablished = 0
    @Volatile private var error: String? = null
    @Volatile private var releaseReason: String? = null
    @Volatile private var sessionExpires = 0
    private var reader: Job? = null
    private var refresher: Job? = null
    private val origin = McpttSdp.Origin()
    private val ufrag = newId().take(8)
    private val password = newId() + newId().take(8)

    private val preUri get() = "${svc.config.psi};mcptt-pre-established"
    private fun event(text: String) = svc.event("pre-established session: $text")

    private fun publish() {
        _status.value = McpttPreSessionStatus(phase, uri, dialog?.callId, control.state.name.lowercase().replace('_', '-'), implicit,
            call?.id, calls, lastControl, local?.floorPriority, refreshes, modified, resumed, reestablished, error, releaseReason,
            ports?.audioPort, ports?.floorPort)
    }

    /**
     * An established session, not in use (TS 24.380 §9.2.2.3), its media negotiated: calls go over it. Not for a
     * protected call (the PCK of TS 24.380 §8.3.3.10 is not handled) or an ad hoc one.
     */
    val usable: Boolean get() = phase == "established" && call == null && remote != null && ports != null &&
        control.state == PreSessionControlState.NOT_IN_USE

    /** The session's media for a call over it: its ports and its local media (TS 24.379 §6.3.2.1.1.2). */
    internal fun media(): Pair<MediaPorts, LocalMedia> = (ports ?: throw CallError("no pre-established session")) to
        (local ?: throw CallError("no pre-established session")).copy(implicitRequest = false, granted = false, floorPriority = null)

    // Establishment (TS 24.379 §8.2.1).

    /**
     * TS 24.379 §8.2.1: step 1 the media (speech, RTCP and floor sockets) and host candidates; steps 2-10 an initial
     * INVITE to the PSI identifying pre-established session set-up (§6.3.1.1) with an SDP offer only (TS 37.579-1 table
     * 5.3.3.4-1: Accept "application/sdp"): the MCPTT feature tag and ICSI, Supported "timer" and Session-Expires, an
     * implicit floor request when asked (§6.4). The 2xx: the session's URI in its Contact (§8.2.2 step 9 a).
     */
    suspend fun establish(implicitFloor: Boolean = false): McpttPreSessionStatus = mutex.withLock {
        if (phase in setOf("establishing", "established", "releasing")) throw CallError("a pre-established session already exists")
        wanted = true
        implicit = implicitFloor
        establishLocked()
        _status.value!!
    }

    private suspend fun establishLocked() {
        phase = "establishing"; error = null; releaseReason = null; call = null
        val flow = svc.ua.flow ?: throw CallError("not connected")
        val address = flow.connection.localHost
        ports?.close()
        val bound = MediaPorts.bind(InetAddress.getByName(address), svc.client.config.media.portRange)
        // A call's audio reader ends within this when its call does, leaving the socket to the next call.
        bound.audio.soTimeout = SOCKET_TIMEOUT_MS
        bound.floor.soTimeout = SOCKET_TIMEOUT_MS
        ports = bound
        local = LocalMedia(address, bound.audioPort, bound.floorPort, McpttSdp.newFloorSsrc(), queueing = svc.client.mcpttFloorQueue,
            implicitRequest = implicit)
        publish()
        val request = svc.ua.newRequest("INVITE", preUri, NameAddr(preUri))
        svc.commonHeaders(request, initialInvite = true)
        request.headers.add("Session-Expires", svc.config.sessionExpires.coerceAtLeast(90).toString()).add("Min-SE", "90")
            .add("Accept", "application/sdp").add("Content-Type", "application/sdp")
        val invite = SipRequest(request.method, request.uri, request.headers, offer().toByteArray())
        val response = try { svc.ua.send(invite) } catch (e: Exception) { fail(e.message ?: "INVITE failed"); throw CallError("pre-established session: ${e.message}") }
        if (!response.isSuccess) {
            fail("${response.status} ${response.reason}" + (response.headers["Warning"]?.let { " ($it)" } ?: ""))
            throw CallError("pre-established session: $error", response.status)
        }
        val sent = svc.ua.lastSent[invite.callId] ?: invite
        val d = Dialog.fromResponse(sent, response)
        svc.ua.ack(d, sent)
        dialog = d
        uri = response.headers["Contact"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() } ?: d.remoteTarget
        remote = sdpOf(response)?.let { runCatching { McpttSdp.remote(it) }.getOrNull() }
        if (remote == null) { runCatching { svc.ua.send(svc.ua.inDialog(d, "BYE"), d) }; fail("2xx without a usable SDP answer"); throw CallError("pre-established session: $error") }
        // TS 24.380 §4.3: the Acknowledge carries the participating function's "mc_floor_ssrc".
        control.ssrc = remote!!.floorSsrc
        control.started()
        phase = "established"
        startReader()
        timer(response.headers["Session-Expires"], ourRequest = true)
        publish()
        event("$uri established (audio ${bound.audioPort}, floor ${bound.floorPort})")
    }

    private fun fail(why: String) {
        error = why; phase = "failed"
        ports?.close(); ports = null
        publish()
        event("failed: $why")
    }

    private fun sdpOf(message: org.openmcx.client.sip.SipMessage) =
        Multipart.parse(message.contentType, message.body).firstOrNull { it.mediaType == "application/sdp" }?.text

    /** TS 24.379 §8.2.1 steps 9-10 with RFC 8839 §5.1, §5.4: the session's offer with its host candidates. */
    private fun offer(media: LocalMedia = local!!): String = withIce(McpttSdp.offer(media, origin), ufrag, password,
        media.address, media.audioPort, media.audioPort + 1, if (media.floorControl) media.floorPort else null)

    /** The session's answer to the participating function's offer (§8.3.1.2 step 2 a), its "mc_priority" returned (TS 24.380 §14.3.3). */
    private fun answer(offer: RemoteMedia, media: LocalMedia = local!!): String =
        withIce(McpttSdp.answer(offer, media, origin), ufrag, password, media.address, media.audioPort, media.audioPort + 1,
            if (offer.floorControl && media.floorControl) media.floorPort else null)

    // The session timer (RFC 4028), as the MCData session's.

    private fun timer(value: String?, ourRequest: Boolean) {
        val interval = value?.substringBefore(';')?.trim()?.toIntOrNull() ?: 0
        if (interval <= 0) { if (ourRequest) { sessionExpires = 0; refresher?.cancel() }; return }
        sessionExpires = interval
        val named = value!!.substringAfter("refresher=", "").substringBefore(';').trim().lowercase()
        val ours = if (ourRequest) named != "uas" else named == "uas"
        refresher?.cancel()
        if (!ours) return
        val due = System.currentTimeMillis() + interval * 500L
        refresher = svc.scope.launch {
            while (System.currentTimeMillis() < due) delay(minOf(30_000L, maxOf(1L, due - System.currentTimeMillis())))
            refresh()
        }
    }

    /** RFC 4028 §10 with TS 24.379 §8.3.1.1: a re-INVITE in the session's dialog with its media (a new Contact after a new flow). */
    private suspend fun refresh(media: LocalMedia? = local): Boolean {
        val d = dialog ?: return false
        val m = media ?: return false
        val request = svc.ua.inDialog(d, "INVITE")
        request.headers.add("Contact", svc.client.sessionContact(McService.MCPTT)).add("Supported", "timer")
            .add("Session-Expires", "${if (sessionExpires > 0) sessionExpires else svc.config.sessionExpires.coerceAtLeast(90)};refresher=uac")
            .add("Content-Type", "application/sdp")
        val reinvite = SipRequest("INVITE", request.uri, request.headers, offer(m).toByteArray())
        val response = runCatching { svc.ua.send(reinvite, d) }.getOrElse { event("refresh: ${it.message}"); return false }
        if (!response.isSuccess) {
            event("refresh: ${response.status} ${response.reason}")
            if (response.status == 481 || response.status == 408) ended("refresh ${response.status}")
            return false
        }
        svc.ua.ack(d, svc.ua.lastSent[reinvite.callId] ?: reinvite)
        sdpOf(response)?.let { runCatching { McpttSdp.remote(it) }.getOrNull() }?.let { remote = it; control.ssrc = it.floorSsrc }
        refreshes++
        timer(response.headers["Session-Expires"], ourRequest = true)
        publish()
        return true
    }

    /**
     * TS 24.379 §8.3.1.1: the user modifies the session outside a call: the session's offer asking (or no longer asking)
     * for implicit floor control (§6.4), by re-INVITE or by UPDATE (RFC 3311); the previous media kept until the 2xx.
     */
    suspend fun modify(implicitFloor: Boolean? = null, update: Boolean = false): McpttPreSessionStatus = mutex.withLock {
        val d = dialog?.takeIf { phase == "established" } ?: throw CallError("no established pre-established session to modify")
        if (call != null) throw CallError("the pre-established session carries a call")
        val media = local!!.copy(implicitRequest = implicitFloor ?: implicit)
        val request = svc.ua.inDialog(d, if (update) "UPDATE" else "INVITE")
        request.headers.add("Contact", svc.client.sessionContact(McService.MCPTT)).add("Content-Type", "application/sdp")
        val sent = SipRequest(request.method, request.uri, request.headers, offer(media).toByteArray())
        val response = svc.ua.send(sent, d)
        if (!update && response.isSuccess) svc.ua.ack(d, svc.ua.lastSent[sent.callId] ?: sent)
        if (!response.isSuccess) throw CallError("modification: ${response.status} ${response.reason}", response.status)
        implicit = media.implicitRequest
        local = media
        sdpOf(response)?.let { runCatching { McpttSdp.remote(it) }.getOrNull() }?.let { remote = it }
        modified++
        publish()
        event("modified by ${request.method}: implicit floor request $implicit")
        _status.value!!
    }

    // Release (TS 24.379 §8.4.1.1).

    /** TS 24.379 §8.4.1.1: BYE in the session's dialog (steps 1-3); the media plane resources go (step 4). */
    suspend fun release(): McpttPreSessionStatus = mutex.withLock {
        wanted = false
        val d = dialog ?: throw CallError("no pre-established session to release")
        phase = "releasing"; publish()
        call?.let { svc.release(it, "released with the pre-established session") }
        runCatching { withTimeoutOrNull(5_000) { svc.ua.send(svc.ua.inDialog(d, "BYE"), d) } }
        ended("released by the user")
        _status.value!!
    }

    /** TS 24.379 §8.4.1.2 / TS 24.380 §9.2.2.3.3: the session ended; its media plane resources released, a call over it with them. */
    private fun ended(why: String, keepPorts: Boolean = false) {
        refresher?.cancel(); refresher = null
        control.stopped()
        pending?.complete(null); pending = null
        call?.let { c -> svc.scope.launch { svc.release(c, "pre-established session ended: $why") } }
        call = null
        dialog = null
        reader?.cancel(); reader = null
        if (!keepPorts) { ports?.close(); ports = null }
        phase = "released"; releaseReason = why
        publish()
        event("released: $why")
    }

    // Calls over the session (TS 24.379 §10.1.1.2.2.1, §10.1.2.2.2.1, §11.1.1.2.2.1, §11.1.6.2.2.1).

    /**
     * The REFER outside a dialog to the session's identity: Refer-To a cid URL naming the resource list whose entries
     * are the targets (one: the group or the called user; several: a first-to-answer call, §11.1.1.2.2.1 step 9), each
     * with [entryHeaders] (the group call's two Accept-Contact; the private call's Answer-Mode or Priv-Answer-Mode) and
     * the hname "body" with the MCPTT-Info (protected under the CSK, §6.6.2.1) and the SDP offer (the session's media,
     * with the call's implicit floor request, floor priority and direction, §6.4); Refer-Sub "false", Supported
     * "norefersub", Require "multiple-refer", Target-Dialog, P-Preferred-Service, To the PSI of the session's INVITE
     * (TS 37.579-1 table 5.5.2.12-1), the Resource-Priority of a priority call (§10.1.2.2.2.1 steps 5, 7).
     *
     * Its 2xx: 'U: Pre-established session in use' (TS 24.380 §9.2.2.3.5) and the Connect follows with the call's
     * session identity (§4.1.2.2); a Disconnect instead is a refusal (§9.2.2.3.4).
     */
    internal suspend fun originate(c: Call, targets: List<String>, info: McpttInfo, offered: LocalMedia, entryHeaders: List<Pair<String, String>>,
                                   resourcePriority: String?): McpcMessage {
        val d = dialog ?: throw CallError("no pre-established session")
        val session = uri ?: throw CallError("no pre-established session")
        val entryBody = svc.client.protection.mcpttBodies(listOf(
            Part("application/sdp", offer(offered).toByteArray(), listOf("Content-ID" to "<${newId()}@mcx>")),
            Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray(), listOf("Content-ID" to "<${newId()}@mcx>"))))
        val contentId = "${newId()}@mcx"
        val list = ResourceLists.encode(targets.map { entryUri(it, entryHeaders, entryBody) })
        val request = svc.ua.newRequest("REFER", session, NameAddr(preUri))
        request.headers.add("Refer-To", "<cid:$contentId>").add("Refer-Sub", "false").add("Supported", "norefersub")
            .add("Require", "multiple-refer").add("Target-Dialog", targetDialog(d)).add("P-Preferred-Service", Icsi.MCPTT)
            .add("Contact", svc.client.sessionContact(McService.MCPTT))
        resourcePriority?.let { request.headers.add("Resource-Priority", it) }
        val (type, body) = Multipart.encode(listOf(Part(ResourceLists.CONTENT_TYPE, list.toByteArray(), listOf("Content-ID" to "<$contentId>"))))
        request.headers.add("Content-Type", type)
        val waiting = CompletableDeferred<McpcMessage?>()
        mutex.withLock {
            if (!usable) throw CallError("the pre-established session is in use")
            call = c; pending = waiting
            c.preEstablished = true; c.dialog = d
            publish()
        }
        event("call ${c.id}: REFER to ${targets.joinToString()} (${info.sessionType})")
        val response = try { svc.ua.send(SipRequest("REFER", request.uri, request.headers, body)) }
            catch (e: Exception) { free(c); throw CallError("REFER: ${e.message}") }
        if (!response.isSuccess) {
            free(c)
            throw CallError("REFER: ${response.status} ${response.reason}" + (response.headers["Warning"]?.let { " (${it})" } ?: ""), response.status)
        }
        control.referAccepted()
        // TS 24.379 §11.1.6.2.2.3 step 1 b: an ambient listening call's release capability.
        if (c.ambientType != null) c.mayRelease = PrivateServiceRules.ambientReleaseAllowed(response.headers.values("Feature-Caps"))
        publish()
        val connect = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiting.await() }
        if (connect == null) {
            if (call === c) free(c)
            throw CallError(if (lastControl?.startsWith("Disconnect") == true) "refused (Disconnect)" else "no Connect for the call over the pre-established session")
        }
        return connect
    }

    /**
     * TS 24.379 §6.2.4.2: the user leaves the call keeping the session: REFER outside a dialog to the session's identity,
     * Refer-To the MCPTT session identity with method "BYE", Refer-Sub "false", Supported "norefersub", Target-Dialog
     * (steps 2-10); its 2xx: 'U: Pre-established session not in use' (TS 24.380 §9.2.2.4.6) and the call ends.
     */
    internal suspend fun leave(c: Call) {
        val d = dialog
        val session = uri
        if (d != null && session != null) {
            val identity = c.sessionUri ?: session
            val request = svc.ua.newRequest("REFER", session, NameAddr(preUri))
            request.headers.add("Refer-To", "<$identity;method=BYE>").add("Refer-Sub", "false").add("Supported", "norefersub")
                .add("Target-Dialog", targetDialog(d)).add("P-Preferred-Service", Icsi.MCPTT)
                .add("Contact", svc.client.sessionContact(McService.MCPTT))
            val response = runCatching { withTimeoutOrNull(5_000) { svc.ua.send(request) } }.getOrNull()
            event("call ${c.id}: REFER BYE: ${response?.status}")
            if (response?.isSuccess == true) control.leaveAccepted()
        }
        free(c)
        svc.release(c, "released")
    }

    /** The call left the session (released, refused): the session is free again, its media kept. */
    internal fun free(c: Call) {
        if (call !== c) return
        call = null; pending = null
        if (control.state == PreSessionControlState.IN_USE) control.leaveAccepted()
        publish()
        // A session replaced by a call's INVITE is made again once that call is over.
        if (phase == "replaced" && wanted && svc.active == null) svc.scope.launch { runCatching { reestablish() } }
    }


    // The media plane control channel (TS 24.380 §4.1.2.1, §9.2.2).

    private fun startReader() {
        reader?.cancel()
        val socket = ports?.floor ?: return
        reader = svc.scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1500)
            while (isActive && dialog != null && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (e: java.net.SocketTimeoutException) { continue } catch (e: Exception) { break }
                val raw = buffer.copyOf(packet.length)
                val media = call?.media
                val clear = media?.protection?.let { it.unprotectControl(raw) } ?: raw
                runCatching {
                    if (McpcMessage.isMcpc(clear)) McpcMessage.decode(clear)?.let { control(it) }
                    else media?.floorPacket(clear)
                }.onFailure { event("control channel: ${it.message}") }
            }
        }
    }

    private suspend fun send(message: McpcMessage) {
        val r = remote ?: return
        val data = message.encode()
        val media = call?.media
        if (media != null) media.sendControl(data, r.address, r.floorPort)
        else withContext(Dispatchers.IO) { ports?.floor?.send(DatagramPacket(data, data.size, InetSocketAddress(r.address, r.floorPort))) }
        event("control → $message")
    }

    /**
     * TS 24.380 §9.2.2.3.2, §9.2.2.4.2, §9.2.2.3.4, §9.2.2.4.5: a Connect or Disconnect, acknowledged as the state
     * machine says. A Connect to this client's own REFER (or re-INVITE answer) confirms its call (§4.1.2.2); one without
     * a call of this client offers one (automatic commencement, TS 24.379 §6.3.2.2.5.3): accepted, unless the user is in
     * another call ('Busy') or it would need end-to-end keys ('Not Accepted'); a Disconnect ends the call over the session.
     */
    private suspend fun control(message: McpcMessage) {
        lastControl = message.toString()
        event("control ← $message")
        when (message.type) {
            McpcType.CONNECT -> {
                val own = call != null && pending != null
                val answer = when {
                    own -> McpcReasonCode.ACCEPTED
                    call != null || svc.active != null -> McpcReasonCode.BUSY
                    message.pckIMessage != null || svc.protectedMedia -> McpcReasonCode.NOT_ACCEPTED
                    else -> McpcReasonCode.ACCEPTED
                }
                val outcome = control.connect(message, answer)
                outcome.acknowledge?.let { send(it) }
                if (own) { val w = pending; pending = null; w?.complete(message) }
                else if (outcome.callStarted && answer == McpcReasonCode.ACCEPTED) offeredByConnect(message)
            }
            McpcType.DISCONNECT -> {
                val outcome = control.disconnect(message)
                outcome.acknowledge?.let { send(it) }
                val c = call
                val w = pending
                if (w != null) { pending = null; w.complete(null) }
                else if (c != null) { call = null; svc.release(c, "released by the server (Disconnect)") }
            }
        }
        publish()
    }

    /** TS 24.380 §9.2.2.3.2 1 b-d with TS 24.379 §10.1.1.2.2.2 by Connect (automatic commencement): the call takes the session's media. */
    private suspend fun offeredByConnect(connect: McpcMessage) {
        val type = when (connect.sessionType) { McpcSessionType.PRIVATE -> CallType.PRIVATE; McpcSessionType.CHAT -> CallType.CHAT; else -> CallType.PREARRANGED }
        val noControl = connect.controlChannel == 0
        val c = Call(svc.ids.incrementAndGet(), type, false, connect.invitingUser, connect.groupIdentity?.takeIf { type != CallType.PRIVATE })
        c.floorControl = !noControl
        c.preEstablished = true
        c.dialog = dialog
        c.sessionUri = connect.sessionIdentity
        call = c
        calls++
        svc._calls.value = svc._calls.value + c
        svc.event("call ${c.id}: ${type.sessionType} call from ${c.peer}${c.groupId?.let { " on $it" } ?: ""} over the pre-established session (Connect)")
        svc.listener?.incoming(c)
        startCall(c, connect, implicitOffered = false)
    }

    /** The call over the session is established: its media the session's (TS 24.380 §9.2.2.3.2 1 b-d, §4.1.2.2). */
    internal suspend fun startCall(c: Call, connect: McpcMessage, implicitOffered: Boolean, media: MediaSession? = null) {
        val (sessionPorts, sessionLocal) = media()
        val noControl = connect.controlChannel == 0 || !c.floorControl
        val callLocal = if (noControl) sessionLocal.copy(floorControl = false, audioSsrc = McpttSdp.newFloorSsrc()) else sessionLocal
        val m = media ?: svc.newMedia(c, sessionPorts, callLocal)
        m.keepPorts = true
        m.externalFloor = true
        c.media = m
        c.sessionUri = connect.sessionIdentity ?: c.sessionUri
        // TS 24.380 §9.3.2.4.10 step 1 g: a first-to-answer call's Connect names the user who answered.
        if (c.firstToAnswer && connect.invitedUser != null) c.peer = connect.invitedUser
        val r = remote!!.copy(floorControl = !noControl)
        c.floorPriority = r.floorPriority
        m.start(r, priority = c.floorPriority, implicit = implicitOffered && !noControl, indicator = { svc.indicatorOf(c, callLocal, r) })
        if (c.ambientRole == "listened-to") m.floor.grantExpected = true
        c.state = "established"
        svc.event("call ${c.id}: established over the pre-established session (${c.sessionUri})")
        svc.changed(c)
        publish()
    }

    // The participating function's requests in the session's dialog.

    /**
     * TS 24.379 §8.3.1.2 (an UPDATE or re-INVITE modifying the session: 488 without an acceptable offer, else 200 with
     * the answer, the offered floor priority taken, TS 24.380 §14.3.3), §8.4.1.2 (its BYE), §10.1.1.2.2.2 and
     * §11.1.1.2.2.2 (a re-INVITE with MCPTT-Info offering a call: manual commencement, the user alerted, the answer the
     * session's media), §6.2.8.1.16 (a re-INVITE during this client's priority call over the session: its priority, the
     * answer the session's media, TS 24.380 §14.3.3).
     */
    internal suspend fun handle(tx: ServerTransaction): Boolean {
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
            "UPDATE", "INVITE" -> {}
            else -> return false
        }
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrDefault(emptyList())
        val info = parts.firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }?.let { runCatching { McpttInfo.parse(it.text) }.getOrNull() }
        val sdp = parts.firstOrNull { it.mediaType == "application/sdp" }?.text
        val offer = sdp?.let { runCatching { McpttSdp.remote(it) }.getOrNull() }
        timer(request.headers["Session-Expires"], ourRequest = false)
        val timerHeaders = request.headers["Session-Expires"]?.let { listOf("Session-Expires" to it, "Require" to "timer") } ?: emptyList()
        val c = call
        if (request.method == "INVITE" && c != null && offer != null) {
            // §6.2.8.1.16 (and the first-to-answer key re-INVITE, not handled: protected calls do not go over the session).
            info?.let { svc.notePriority(c, it) }
            tx.respond(200, body = answer(offer, (c.media?.local ?: local!!)).toByteArray(), contentType = "application/sdp",
                extra = listOf("Contact" to svc.client.sessionContact(McService.MCPTT)) + timerHeaders)
            return true
        }
        if (request.method == "INVITE" && info != null && offer != null) { offeredByReinvite(tx, info, offer, timerHeaders); return true }
        if (offer == null) {
            if (sdp != null) { tx.respond(488); return true }
            tx.respond(200, extra = timerHeaders) // a refresh without an offer
            return true
        }
        local = local!!.copy(floorPriority = offer.floorPriority)
        remote = offer
        control.ssrc = offer.floorSsrc
        tx.respond(200, body = answer(offer).toByteArray(), contentType = "application/sdp",
            extra = listOf("Contact" to svc.client.sessionContact(McService.MCPTT)) + timerHeaders)
        modified++
        publish()
        event("modified by the participating function (${request.method}), floor priority ${offer.floorPriority}")
        return true
    }

    /**
     * TS 24.379 §10.1.1.2.2.2 with §10.1.1.2.1.2 ("re-INVITE" for "INVITE"), §11.1.1.2.2.2: a call offered in the
     * session's dialog, manual commencement (NOTE 1 of §6.3.2.2.6.3): busy, 486; else the user is alerted (180 for a
     * private call) and answers; the answer is the session's media, "mc_no_floor_ctrl" for an offer with it (TS 24.380
     * §14.3.7, as the Linux client's 9eaa3ac); the Connect that follows the 200 (OK) gives the call its session identity.
     */
    private suspend fun offeredByReinvite(tx: ServerTransaction, info: McpttInfo, offer: RemoteMedia, timerHeaders: List<Pair<String, String>>) {
        if (call != null || svc.active != null) { tx.respond(486); return }
        if (offer.keyMgmt != null || svc.protectedMedia) { tx.respond(488); return }
        val type = CallType.of(info.sessionType) ?: CallType.PREARRANGED
        val c = Call(svc.ids.incrementAndGet(), type, false, info.callingUserId, if (type == CallType.PRIVATE) null else info.callingGroupId)
        c.floorControl = offer.floorControl && !offer.noFloorControl
        c.preEstablished = true
        c.dialog = dialog
        c.broadcast = info.broadcast == true
        c.incoming = tx
        call = c
        calls++
        svc._calls.value = svc._calls.value + c
        c.state = "ringing"
        if (type == CallType.PRIVATE) tx.respond(180)
        svc.listener?.incoming(c)
        svc.changed(c)
        svc.event("call ${c.id}: ${info.sessionType} call from ${info.callingUserId} offered over the pre-established session (re-INVITE)")
        val decision = withTimeoutOrNull(svc.config.manualAnswerTimeout * 1000L) { c.answered.await() }
        if (decision != true) {
            tx.respond(480, extra = listOf("Warning" to "399 ${svc.ua.sentBy} \"110 user declined the call invitation\""))
            call = null
            svc.release(c, if (decision == false) "declined" else "not answered")
            publish()
            return
        }
        val (sessionPorts, sessionLocal) = media()
        val callLocal = if (c.floorControl) sessionLocal else sessionLocal.copy(audioSsrc = McpttSdp.newFloorSsrc())
        val m = svc.newMedia(c, sessionPorts, callLocal)
        m.keepPorts = true
        m.externalFloor = true
        c.media = m
        val waiting = CompletableDeferred<McpcMessage?>()
        pending = waiting
        tx.respond(200, body = answer(offer, callLocal).toByteArray(), contentType = "application/sdp",
            extra = listOf("Contact" to svc.client.sessionContact(McService.MCPTT)) + timerHeaders)
        control.reinviteAnswered()
        publish()
        val connect = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiting.await() }
        if (connect == null) { free(c); svc.release(c, "no Connect after the answer"); return }
        startCall(c, if (offer.noFloorControl) connect.copy(controlChannel = 0) else connect, implicitOffered = false, media = m)
    }

    /**
     * RFC 3891 with TS 24.379 §6.3.2.2.5.3 step 1 and §6.3.2.2.6.3 step 1: an on-demand INVITE whose Replaces names
     * this session (the to-tag this client's, the from-tag the participating function's): once the call is accepted
     * the session is ended (BYE); the session the user wants is made again after the call.
     */
    internal suspend fun replacedBy(replaces: String): Boolean {
        val d = dialog ?: return false
        val callId = replaces.substringBefore(';').trim()
        val params = replaces.substringAfter(';', "").split(';').associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
        if (callId != d.callId || params["to-tag"] != d.localTag || params["from-tag"] != d.remoteTag) return false
        event("replaced by an on-demand call")
        runCatching { withTimeoutOrNull(5_000) { svc.ua.send(svc.ua.inDialog(d, "BYE"), d) } }
        val keep = wanted
        ended("replaced by a call (RFC 3891)")
        if (keep) { wanted = true; phase = "replaced"; publish() }
        return true
    }


    /** After a replaced session's call: the session made again (TS 24.379 §8.2.1). */
    internal suspend fun reestablish() = mutex.withLock {
        if (!wanted || phase in setOf("establishing", "established")) return@withLock
        reestablished++
        runCatching { establishLocked() }.onFailure { event("not re-established: ${it.message}") }
    }


    // Lifecycle.

    internal fun flowLost() {
        if (phase != "established" && phase != "establishing") return
        refresher?.cancel(); refresher = null
        reader?.cancel(); reader = null
        pending?.complete(null); pending = null
        call = null
        control.stopped()
        phase = "suspended"; publish()
    }

    /**
     * MCPTT is authorised again on a new registration: the session the user wants is refreshed in its dialog with the
     * new Contact (RFC 3261 §12.2.1.1, TS 24.379 §8.3.1.1), its media and ports kept; refused, it is released and a new
     * one established (§8.2.1).
     */
    suspend fun resume() = mutex.withLock {
        if (!wanted || phase in setOf("establishing", "established", "releasing")) return@withLock
        if (phase == "suspended" && dialog != null && ports != null) {
            phase = "established"
            if (refresh()) {
                control.started(); startReader(); resumed++; publish()
                event("kept on the new registration")
                return@withLock
            }
            dialog?.let { d -> runCatching { withTimeoutOrNull(5_000) { svc.ua.send(svc.ua.inDialog(d, "BYE"), d) } } }
            dialog = null
        }
        reestablished++
        runCatching { establishLocked() }.onFailure { event("not re-established: ${it.message}") }
    }

    internal suspend fun logOff() { if (dialog != null) runCatching { release() } }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 500
        const val ENTRY_BOUNDARY = "mcx-entry"

        private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        fun targetDialog(d: Dialog) = "${d.callId};local-tag=${d.localTag};remote-tag=${d.remoteTag}"

        /**
         * TS 24.379 §8.2.1 step 1, §6.2.1 with RFC 8839 §5.1 and §5.4: the ICE attributes of the session's offer or
         * answer: credentials at the session level, and in each media section its host candidates (RFC 8445 §5.1.2.1:
         * 2^24 * 126 + 2^8 * 65535 + (256 - component)): speech RTP and RTCP, the floor control port.
         */
        fun withIce(sdp: String, ufrag: String, password: String, address: String, audio: Int, rtcp: Int, floor: Int?): String {
            fun candidate(foundation: Int, component: Int, port: Int) =
                "a=candidate:$foundation $component UDP ${(126L shl 24) or (65535L shl 8) or (256L - component)} $address $port typ host\r\n"
            val out = StringBuilder()
            var section: String? = null
            fun close() {
                when (section) {
                    "audio" -> out.append(candidate(1, 1, audio)).append(candidate(1, 2, rtcp))
                    "application" -> floor?.let { out.append(candidate(2, 1, it)) }
                }
            }
            for (line in sdp.split("\r\n").filter { it.isNotEmpty() }) {
                if (line.startsWith("m=")) {
                    if (section == null) out.append("a=ice-ufrag:$ufrag\r\na=ice-pwd:$password\r\n") else close()
                    val words = line.removePrefix("m=").split(' ')
                    section = if (words.getOrNull(1) == "0") "" else words[0]
                }
                out.append(line).append("\r\n")
            }
            close()
            return out.toString()
        }

        /** RFC 3261 §19.1.1: the entry "uri": the target, its header fields and the hname "body" (TS 37.579-1 table 5.5.2.12-2). */
        fun entryUri(target: String, headers: List<Pair<String, String>>, parts: List<Part>): String {
            val (_, body) = Multipart.encode(parts, ENTRY_BOUNDARY)
            val esc = org.openmcx.client.mcdata.McdataPreSession.Companion::escapeUriHeader
            val fields = headers.map { (k, v) -> "${esc(k.toByteArray())}=${esc(v.toByteArray())}" } +
                "Content-Type=${esc("multipart/mixed;boundary=$ENTRY_BOUNDARY".toByteArray())}" + "body=${esc(body)}"
            return "$target?" + fields.joinToString("&")
        }
    }
}

/** A Call's [Call.preEstablished] mark lives in McpttService.kt; the REFER's entry headers per call type are here. */
internal object PreSessionEntries {
    /**
     * TS 24.379 §10.1.2.2.2.1 step 3 a-b (a group call: the two Accept-Contact), §11.1.1.2.2.1 step 8 a-b (a private
     * call: Answer-Mode, or Priv-Answer-Mode for a forced or a service call), §11.1.6.2.2.1 (ambient listening:
     * Priv-Answer-Mode "Auto").
     */
    fun headers(type: CallType, privAnswerMode: String?, commencement: Commencement?): List<Pair<String, String>> =
        if (type == CallType.PRIVATE) listOfNotNull(when {
            privAnswerMode != null -> "Priv-Answer-Mode" to privAnswerMode
            commencement == Commencement.MANUAL -> "Answer-Mode" to "Manual"
            commencement == Commencement.FORCED -> "Priv-Answer-Mode" to "Auto"
            commencement == Commencement.AUTOMATIC -> "Answer-Mode" to "Auto"
            else -> null
        }) else listOf("Accept-Contact" to "*;+g.3gpp.mcptt;require;explicit",
            "Accept-Contact" to "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)}\";require;explicit")
}
