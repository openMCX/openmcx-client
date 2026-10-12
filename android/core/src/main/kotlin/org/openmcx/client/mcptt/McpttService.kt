//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.body.McService
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CallError(message: String, val status: Int? = null) : Exception(message)

enum class CallType(val sessionType: String) {
    PRIVATE("private"), PREARRANGED("prearranged"), ADHOC("adhoc");

    companion object {
        fun of(sessionType: String?) = entries.firstOrNull { it.sessionType == sessionType }
    }
}

/** Commencement modes of a private call (TS 24.379 §11.1.1.2.1.1 step 14, RFC 5373). */
enum class Commencement { AUTOMATIC, MANUAL, FORCED }

/** One MCPTT call and its media. */
class Call internal constructor(
    val id: Int,
    val type: CallType,
    val outgoing: Boolean,
    /** The called or calling MCPTT ID (private), or the group ID (group and ad hoc). */
    @Volatile var peer: String?,
    @Volatile var groupId: String?,
) {
    @Volatile var state: String = "setup" // setup, ringing, established, released
    /** False for a private call without floor control (TS 24.379 §11.1.2): both parties speak at once. */
    @Volatile var floorControl: Boolean = true
    @Volatile var dialog: Dialog? = null
    @Volatile var media: MediaSession? = null
    @Volatile var releaseReason: String? = null
    @Volatile var invite: SipRequest? = null
    internal var incoming: ServerTransaction? = null
    internal val answered = CompletableDeferred<Boolean>()
    internal var refresher: Job? = null
    @Volatile var sessionExpires = 0
    /** `normal`, `emergency` or `imminent` (peril): as requested, or as the server last announced (§6.2.8). */
    @Volatile var priority: String = "normal"
    val startedAt = System.currentTimeMillis()
    /** Functional aliases (TS 24.379 §9A): the alias the caller presented, and the alias that was called. */
    @Volatile var callingAlias: String? = null
    @Volatile var calledAlias: String? = null
    val floorEvents: MutableList<String> = CopyOnWriteArrayList()
}

/** Tells the UI and the service about call events. */
interface CallListener {
    fun incoming(call: Call) {}
    fun changed(call: Call) {}
}

/**
 * The MCPTT client's call control (TS 24.379 §10, §11, §17; on-demand sessions only):
 * private, prearranged group and ad hoc group calls, originating and terminating, with
 * session refresh by UPDATE (docs/android/wire/phase2-mcptt.md §7).
 */
class McpttService(
    private val client: McxClient,
    private val codecs: SpeechCodecFactory,
    val audio: AudioDevices,
) : ServiceHandler {
    private val config = client.config.mcptt!!
    private val ua = client.ua
    private val scope = client.scope
    private val lock = Mutex()
    private val ids = AtomicInteger()
    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls
    /** Finished calls, newest last, for the status view. */
    val history: MutableList<Call> = CopyOnWriteArrayList()
    var listener: CallListener? = null
    /** The local answer mode for calls that do not dictate one. */
    @Volatile var answerMode: String = config.answerMode

    val active: Call? get() = _calls.value.firstOrNull { it.state != "released" }

    init {
        ua.onAck = { ack -> onAck(ack) }
    }

    private fun event(text: String) = client.event("mcptt: $text")
    private fun changed(call: Call) { _calls.value = _calls.value.toList(); listener?.changed(call) }

    private fun commonHeaders(request: SipRequest) {
        request.headers.add("Contact", client.contactFor(McService.MCPTT))
            .add("Allow", ALLOW)
            .add("Supported", "timer")
            .add("Accept-Contact", "*;+g.3gpp.mcptt;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCPTT)
    }

    private fun bindMedia(): Pair<MediaPorts, LocalMedia> {
        val flow = ua.flow ?: throw CallError("not connected")
        val address = flow.connection.localHost
        val ports = MediaPorts.bind(InetAddress.getByName(address), client.config.media.portRange)
        return ports to LocalMedia(address, ports.audioPort, ports.floorPort, McpttSdp.newFloorSsrc(), queueing = client.mcpttFloorQueue)
    }

    private fun newMedia(call: Call, ports: MediaPorts, local: LocalMedia) = MediaSession(scope, ports, local, codecs, audio,
        client.config.media.amrWbMode) { event("call ${call.id}: $it") }.also { media ->
        media.floorEvents = object : FloorListener {
            override fun granted(mediaSsrc: Long, durationSeconds: Int?) = floor(call, "granted ssrc=%08x %ss".format(mediaSsrc, durationSeconds))
            override suspend fun stopTalking() = floor(call, "stop talking")
            override fun taken(party: String?, mediaSsrc: Long?) = floor(call, "taken by $party")
            override fun idle() = floor(call, "idle")
            override fun denied(cause: Int?, text: String?) = floor(call, "denied cause $cause")
            override fun revoked(cause: Int?) = floor(call, "revoked cause $cause")
            override fun requestFailed() = floor(call, "request failed")
            override fun queued(position: Int?, priority: Int?) = floor(call, "queued position $position priority $priority")
            override fun queuedGrantOffered(seconds: Int) = floor(call, "queued grant offered ${seconds}s")
            override fun queuedGrantLapsed() = floor(call, "queued grant lapsed")
            override fun queueTimeout() = floor(call, "queue position request timed out")
        }
    }

    private fun floor(call: Call, text: String) { call.floorEvents += text; event("call ${call.id} floor $text"); changed(call) }

    // Originating calls.

    /**
     * Private call (TS 24.379 §11.1.1.2.1.1); without [floorControl], a private call without floor control
     * (§11.1.2.2): offered without `m=application`, both parties then speak at once, like a telephone call.
     */
    suspend fun privateCall(target: String, commencement: Commencement = Commencement.AUTOMATIC, floorControl: Boolean = true,
                            emergency: Boolean = false): Call =
        originate(CallType.PRIVATE, target, null, listOf(target), commencement, floorControl, if (emergency) "emergency" else "normal")

    /**
     * A private call to a functional alias (§11.1.1.2.1.1 step 14c): the request names the alias with
     * <call-to-functional-alias-ind>; the server answers 300 with the MCPTT ID it selected, which is then called
     * with the alias as <called-functional-alias-URI>.
     */
    suspend fun privateCallToAlias(alias: String, commencement: Commencement = Commencement.AUTOMATIC, floorControl: Boolean = true): Call {
        val redirect = try {
            return originate(CallType.PRIVATE, alias, null, listOf(alias), commencement, floorControl, toAlias = true)
        } catch (e: AliasRedirect) { e }
        return originate(CallType.PRIVATE, redirect.selected, null, listOf(redirect.selected), commencement, floorControl,
            calledAlias = redirect.alias ?: alias)
    }

    /** The server's 300 to a call to a functional alias: the MCPTT ID it selected (§11.1.1.2.1.1). */
    private class AliasRedirect(val selected: String, val alias: String?) : Exception("redirected to $selected")

    /**
     * Prearranged group call (TS 24.379 §10.1.1.2.1.1); with [priority] `emergency` or `imminent`, an emergency or
     * imminent peril group call (§6.2.8.1).
     */
    suspend fun groupCall(group: String, priority: String = "normal"): Call =
        originate(CallType.PREARRANGED, null, group, emptyList(), null, priority = priority)

    /** Ad hoc group call (TS 24.379 §17.2.2.1.1): the participants are chosen here. */
    suspend fun adhocCall(participants: List<String>): Call = originate(CallType.ADHOC, null, null, participants, null)

    private suspend fun originate(type: CallType, peer: String?, group: String?, targets: List<String>, commencement: Commencement?,
                                  floorControl: Boolean = true, priority: String = "normal", toAlias: Boolean = false,
                                  calledAlias: String? = null): Call {
        if (active != null) throw CallError("already in a call")
        val resource = resourcePriority(priority)
        val call = Call(ids.incrementAndGet(), type, true, peer, group).also { it.floorControl = floorControl; it.priority = priority }
        _calls.value = _calls.value + call
        val (ports, bound) = bindMedia()
        // §11.1.2.2 steps 12-13: no media-floor control section; the speech SSRC is announced instead.
        val local = if (floorControl) bound else bound.copy(floorControl = false, audioSsrc = McpttSdp.newFloorSsrc())
        val media = newMedia(call, ports, local)
        call.media = media
        try {
            val request = ua.newRequest("INVITE", config.psi, NameAddr(config.psi))
            commonHeaders(request)
            request.headers.add("Session-Expires", config.sessionExpires.coerceAtLeast(90).toString()).add("Min-SE", "90")
            when (commencement) {
                Commencement.AUTOMATIC -> request.headers.add("Answer-Mode", "Auto")
                Commencement.MANUAL -> request.headers.add("Answer-Mode", "Manual")
                Commencement.FORCED -> request.headers.add("Priv-Answer-Mode", "Auto")
                null -> Unit
            }
            resource?.let { request.headers.add("Resource-Priority", it) }
            // §9A, §11.1.1.2.1.1 step 14c: the alias this user presents (private and ad hoc calls, as the reference client),
            // the call to an alias, and the alias called once the server selected its MCPTT ID.
            val presented = client.aliases.value.inUse.takeIf { type != CallType.PREARRANGED }
            call.callingAlias = presented
            call.calledAlias = calledAlias ?: peer.takeIf { toAlias }
            val info = priorityInfo(McpttInfo(sessionType = type.sessionType, requestUri = group, clientId = client.config.clientId,
                functionalAlias = presented, callToFunctionalAlias = if (toAlias) true else null, calledFunctionalAlias = calledAlias), priority)
            val parts = mutableListOf(Part("application/sdp", McpttSdp.offer(local).toByteArray()),
                Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray()))
            if (targets.isNotEmpty()) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(targets).toByteArray())
            val (contentType, body) = Multipart.encode(parts)
            request.headers.add("Content-Type", contentType)
            val invite = SipRequest(request.method, request.uri, request.headers, body)
            call.invite = invite
            event("call ${call.id}: ${type.sessionType} call to ${peer ?: group ?: targets}${if (floorControl) "" else " without floor control"}" +
                if (priority != "normal") " ($priority)" else "")
            val response = ua.send(invite, provisional = { r ->
                if (r.status == 180 || r.status == 183) { call.state = "ringing"; changed(call) }
            })
            val sent = ua.lastSent[invite.callId] ?: invite
            call.invite = sent
            if (toAlias && response.status == 300) {
                val redirect = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }
                    ?.let { McpttInfo.parse(it.text) }
                val selected = redirect?.requestUri ?: throw CallError("functional alias redirect without an MCPTT ID", 300)
                event("call ${call.id}: functional alias ${redirect.calledFunctionalAlias ?: peer} is $selected")
                throw AliasRedirect(selected, redirect.calledFunctionalAlias)
            }
            if (!response.isSuccess) {
                val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
                throw CallError("${response.status} ${response.reason}$warning", response.status)
            }
            val dialog = Dialog.fromResponse(sent, response)
            call.dialog = dialog
            ua.ack(dialog, sent)
            val parts2 = Multipart.parse(response.contentType, response.body)
            val sdp = parts2.firstOrNull { it.mediaType == "application/sdp" } ?: throw CallError("200 without SDP")
            parts2.firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }?.let {
                val answerInfo = McpttInfo.parse(it.text)
                if (type == CallType.ADHOC) call.groupId = answerInfo.callingGroupId // D8: the ad hoc group identity
                if (type == CallType.PRIVATE && answerInfo.requestUri != null) call.peer = answerInfo.requestUri
                notePriority(call, answerInfo, quiet = true)
            }
            val remote = McpttSdp.remote(sdp.text)
            if (remote.floorControl != floorControl) throw CallError("floor control not negotiated as offered")
            media.start(remote, priority = null)
            call.state = "established"
            call.sessionExpires = response.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: 0
            val refresher = response.headers["Session-Expires"]?.substringAfter("refresher=", "")?.substringBefore(';')?.trim()
            if (call.sessionExpires > 0 && refresher != "uas") startRefresh(call)
            event("call ${call.id}: established${call.groupId?.let { " on $it" } ?: ""}")
            changed(call)
            return call
        } catch (e: Exception) {
            release(call, e.message ?: "failed")
            throw e
        }
    }

    // Terminating calls.

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        val call = _calls.value.firstOrNull { it.dialog?.callId == request.callId || it.invite?.callId == request.callId }
        when (request.method) {
            "INVITE" -> if (call == null) incoming(tx) else reinvite(call, tx)
            "UPDATE" -> if (call == null) tx.respond(481) else update(call, tx)
            "BYE" -> {
                if (call == null) { tx.respond(481); return true }
                tx.respond(200)
                event("call ${call.id}: released by the server")
                release(call, request.headers["Reason"] ?: "released by the server")
            }
            "INFO", "MESSAGE" -> {
                if (call == null) return false
                tx.respond(200)
                infoOf(request)?.let { notePriority(call, it) }
            }
            else -> return false
        }
        return true
    }

    private suspend fun incoming(tx: ServerTransaction) {
        val request = tx.request
        val parts = Multipart.parse(request.contentType, request.body)
        val info = parts.firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }?.let { McpttInfo.parse(it.text) }
        val sdp = parts.firstOrNull { it.mediaType == "application/sdp" }?.text
        val type = CallType.of(info?.sessionType)
        if (info == null || sdp == null || type == null) { tx.respond(400, "Bad Request"); return }
        if (active != null) { tx.respond(486); return }
        val remote = try { McpttSdp.remote(sdp) } catch (e: SdpNegotiationError) { tx.respond(488); return }
        // §11.1.2: only a private call comes without floor control.
        if (!remote.floorControl && type != CallType.PRIVATE) { tx.respond(488); return }
        val call = Call(ids.incrementAndGet(), type, false, info.callingUserId,
            if (type == CallType.PRIVATE) null else info.callingGroupId).also {
            it.floorControl = remote.floorControl
            it.callingAlias = info.functionalAlias
            it.calledAlias = info.calledFunctionalAlias
        }
        notePriority(call, info, quiet = true)
        call.incoming = tx
        call.invite = request
        _calls.value = _calls.value + call
        event("call ${call.id}: incoming ${type.sessionType} call from ${info.callingUserId}${call.groupId?.let { " on $it" } ?: ""}")
        // Commencement mode (§11.1.1.2.1.2 steps 9-10).
        val manual = when {
            request.headers["Priv-Answer-Mode"].equals("Auto", true) -> false
            request.headers["Answer-Mode"].equals("Manual", true) -> true
            request.headers["Answer-Mode"].equals("Auto", true) -> answerMode == "manual" && type == CallType.PRIVATE
            else -> answerMode == "manual"
        }
        listener?.incoming(call)
        if (manual) {
            tx.respond(180, extra = listOf("Require" to "timer", "Contact" to client.contactFor(McService.MCPTT)))
            call.state = "ringing"
            changed(call)
            val decision = withTimeoutOrNull(config.manualAnswerTimeout * 1000L) {
                select<Boolean?> {
                    call.answered.onAwait { it }
                    tx.cancelled.onAwait { null }
                }
            }
            when (decision) {
                true -> Unit
                false -> {
                    tx.respond(480, extra = listOf("Warning" to "399 ${ua.sentBy} \"110 user declined the call invitation\""))
                    release(call, "declined"); return
                }
                null -> {
                    if (!tx.finalSent) tx.respond(480)
                    release(call, if (tx.cancelled.isCompleted) "cancelled" else "not answered"); return
                }
            }
        }
        accept(call, tx, remote)
    }

    private suspend fun accept(call: Call, tx: ServerTransaction, remote: RemoteMedia) {
        val (ports, bound) = try { bindMedia() } catch (e: Exception) { tx.respond(480); release(call, "no media ports"); return }
        // §11.1.2.3: an offer without floor control is answered without it, announcing our speech SSRC.
        val local = if (remote.floorControl) bound else bound.copy(floorControl = false, audioSsrc = McpttSdp.newFloorSsrc())
        val media = newMedia(call, ports, local)
        call.media = media
        val offered = tx.request.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: 90
        call.sessionExpires = offered
        val answer = McpttSdp.answer(remote, local)
        val response = tx.response(200, body = answer.toByteArray(), contentType = "application/sdp", extra = listOf(
            "Contact" to client.contactFor(McService.MCPTT), "Require" to "timer", "Supported" to "timer",
            "Session-Expires" to "$offered;refresher=uas", "Allow" to ALLOW))
        call.dialog = Dialog.fromRequest(tx.request, tx.localTag)
        media.start(remote)
        tx.send(response)
        call.state = "established"
        startRefresh(call) // we are the refresher (§6.2.3.1.1 step 5)
        event("call ${call.id}: answered")
        changed(call)
    }

    private fun onAck(ack: SipRequest) { /* the server marks the leg active on ACK; nothing to do here */ }

    private suspend fun reinvite(call: Call, tx: ServerTransaction) {
        val media = call.media
        if (media == null) { tx.respond(491); return }
        val sdp = Multipart.parse(tx.request.contentType, tx.request.body).firstOrNull { it.mediaType == "application/sdp" }?.text
        val remote = sdp?.let { runCatching { McpttSdp.remote(it) }.getOrNull() }
        val answer = if (remote != null) McpttSdp.answer(remote, media.local) else null
        tx.respond(200, body = answer?.toByteArray() ?: ByteArray(0), contentType = answer?.let { "application/sdp" },
            extra = sessionTimerHeaders(tx.request))
        // The server announces a changed call state (emergency, imminent peril) by re-INVITE (§6.2.8.1).
        infoOf(tx.request)?.let { notePriority(call, it) }
    }

    private fun infoOf(request: SipRequest) = Multipart.parse(request.contentType, request.body)
        .firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }?.let { runCatching { McpttInfo.parse(it.text) }.getOrNull() }

    // Emergency and imminent peril (§6.2.8).

    private fun priorityInfo(info: McpttInfo, priority: String) = when (priority) {
        // §6.2.8.1.1 step 3: an emergency call says whether it is also an alert; this one is not (the Call tab's
        // Emergency alert is separate).
        "emergency" -> info.copy(emergency = true, alert = false)
        "imminent" -> info.copy(imminentPeril = true)
        else -> info
    }

    // TS 24.379 §6.2.8.1.15: from the MCPTT service configuration document.
    private fun resourcePriority(priority: String): String? = client.serviceResourcePriorities["mcptt"]?.get(priority)?.takeIf { it.isNotBlank() }
        ?: if (priority != "normal") throw CallError("no Resource-Priority for $priority calls: the MCPTT service configuration document was not read") else null

    /** Follow the call state in a request from the server: its emergency and imminent peril indications. */
    private fun notePriority(call: Call, info: McpttInfo, quiet: Boolean = false) {
        val next = when {
            info.emergency == true -> "emergency"
            info.imminentPeril == true -> "imminent"
            info.emergency == false || info.imminentPeril == false -> "normal"
            else -> return
        }
        if (next == call.priority) return
        call.priority = next
        event("call ${call.id}: now $next")
        if (!quiet) changed(call)
    }

    /**
     * Raise or cancel the emergency (or imminent peril, group calls only) state of the call in progress by re-INVITE
     * in its dialog (§6.2.8.1.4, §6.2.8.1.9, §6.2.8.3): the server tells the other participants.
     */
    suspend fun setPriority(emergency: Boolean, enabled: Boolean, call: Call? = active) {
        call ?: throw CallError("no call")
        val dialog = call.dialog ?: throw CallError("the call is not established")
        val media = call.media ?: throw CallError("no media")
        if (!emergency && call.type == CallType.PRIVATE) throw CallError("imminent peril is for group calls")
        val wanted = if (!enabled) "normal" else if (emergency) "emergency" else "imminent"
        val resource = resourcePriority(wanted)
        var info = if (call.type == CallType.PRIVATE) McpttInfo(sessionType = "private", callingUserId = config.userId, clientId = client.config.clientId)
        else McpttInfo(sessionType = call.type.sessionType, requestUri = call.groupId, clientId = client.config.clientId)
        info = when {
            emergency && enabled -> info.copy(emergency = true, alert = false)
            emergency -> info.copy(emergency = false)
            enabled -> info.copy(imminentPeril = true)
            else -> info.copy(imminentPeril = false)
        }
        val parts = mutableListOf(Part("application/sdp", McpttSdp.offer(media.local).toByteArray()), Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (call.type == CallType.PRIVATE) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOfNotNull(call.peer)).toByteArray())
        val request = ua.inDialog(dialog, "INVITE")
        commonHeaders(request)
        resource?.let { request.headers.add("Resource-Priority", it) }
        val (contentType, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", contentType)
        val reinvite = SipRequest(request.method, request.uri, request.headers, body)
        val response = ua.send(reinvite, dialog)
        if (response.isSuccess) ua.ack(dialog, ua.lastSent[reinvite.callId] ?: reinvite)
        if (!response.isSuccess) {
            val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
            throw CallError("${response.status} ${response.reason}$warning", response.status)
        }
        call.priority = wanted
        event("call ${call.id}: $wanted")
        changed(call)
    }

    private suspend fun update(call: Call, tx: ServerTransaction) = tx.respond(200, extra = sessionTimerHeaders(tx.request))

    private fun sessionTimerHeaders(request: SipRequest): List<Pair<String, String>> {
        val se = request.headers["Session-Expires"] ?: return listOf("Contact" to client.contactFor(McService.MCPTT))
        return listOf("Contact" to client.contactFor(McService.MCPTT), "Require" to "timer", "Supported" to "timer", "Session-Expires" to se)
    }

    /** Refresh the session with UPDATE at half the interval (RFC 4028 §10; re-INVITE is refused, contract D9). */
    private fun startRefresh(call: Call) {
        if (call.sessionExpires <= 0) return
        call.refresher?.cancel()
        call.refresher = scope.launch {
            while (call.state == "established") {
                delay(call.sessionExpires * 500L)
                val dialog = call.dialog ?: return@launch
                if (call.state != "established") return@launch
                val update = ua.inDialog(dialog, "UPDATE")
                update.headers.add("Contact", client.contactFor(McService.MCPTT)).add("Supported", "timer")
                    .add("Session-Expires", "${call.sessionExpires};refresher=uac")
                val response = runCatching { ua.send(update, dialog) }.getOrNull()
                event("call ${call.id}: session refresh ${response?.status}")
                if (response == null || response.status == 481 || response.status == 408) { release(call, "session refresh failed"); return@launch }
                response.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull()?.let { call.sessionExpires = it }
            }
        }
    }

    // User actions.

    fun answer(): Boolean = _calls.value.firstOrNull { it.state == "ringing" && !it.outgoing }?.answered?.complete(true) ?: false
    fun reject(): Boolean = _calls.value.firstOrNull { it.state == "ringing" && !it.outgoing }?.answered?.complete(false) ?: false

    /** Leave or release the call (§6.2.4.1, §17.2.3.1.1). */
    suspend fun hangup(call: Call? = active) {
        call ?: throw CallError("no call")
        val dialog = call.dialog
        when {
            dialog != null -> {
                call.media?.let { m -> if (m.floor.state.value == FloorState.HAS_PERMISSION) m.floor.release() }
                val bye = ua.inDialog(dialog, "BYE")
                if (call.type == CallType.ADHOC && call.outgoing) bye.headers.add("Reason", "SIP;cause=200;text=\"User requested release\"")
                runCatching { withTimeoutOrNull(5_000) { ua.send(bye, dialog) } }
                release(call, "released")
            }
            call.outgoing && call.invite != null -> { ua.cancel(call.invite!!); release(call, "cancelled") }
            call.incoming != null -> { call.answered.complete(false) }
            else -> release(call, "released")
        }
    }

    /** A call without floor control: stop or start sending the microphone. */
    fun setMuted(muted: Boolean, call: Call? = active) {
        val media = call?.media?.takeIf { it.isStarted && it.duplex } ?: return
        media.muted = muted
        event("call ${call.id}: ${if (muted) "muted" else "unmuted"}")
        changed(call)
    }

    /**
     * Push to talk: request the floor. [hold]: the user keeps PTT pressed, so a queued grant is accepted at once
     * (TS 24.380 §6.2.4.9.12); without it a queued grant waits for [confirmFloor] within T132.
     */
    suspend fun pttPress(call: Call? = active, hold: Boolean = true) {
        val media = call?.media ?: throw CallError("no call")
        if (media.isStarted && media.duplex) return // no floor: the microphone is already sent
        media.requestedAt = System.currentTimeMillis()
        media.prepareTalk()
        media.floor.autoConfirm = hold
        media.floor.request()
    }

    /** Accept a queued grant (TS 24.380 §6.2.4.9.12); false when none awaits acceptance. */
    suspend fun confirmFloor(call: Call? = active): Boolean = call?.media?.takeIf { it.isStarted }?.floor?.confirm() ?: false

    /** Ask for the queue position of our queued floor request (TS 24.380 §6.2.4.9.9); false when not queued. */
    suspend fun requestQueuePosition(call: Call? = active): Boolean =
        call?.media?.takeIf { it.isStarted }?.floor?.requestQueuePosition() ?: false

    suspend fun pttRelease(call: Call? = active) {
        if (call?.media?.let { it.isStarted && it.duplex } == true) return
        call?.media?.cancelPrepared()
        call?.media?.floor?.release()
    }

    private suspend fun release(call: Call, reason: String) = lock.withLock {
        if (call.state == "released") return@withLock
        call.state = "released"
        call.releaseReason = reason
        call.refresher?.cancel()
        runCatching { call.media?.stop() }
        _calls.value = _calls.value - call
        history += call
        while (history.size > 20) history.removeAt(0)
        event("call ${call.id}: $reason")
        listener?.changed(call)
    }

    override suspend fun flowLost() {
        _calls.value.forEach { release(it, "connection lost") }
    }

    companion object {
        const val ALLOW = "INVITE, ACK, CANCEL, BYE, UPDATE, INFO, MESSAGE, NOTIFY, OPTIONS"
    }
}
