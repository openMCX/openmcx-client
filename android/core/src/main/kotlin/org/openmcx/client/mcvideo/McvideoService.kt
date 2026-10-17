//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KmsDocuments
import org.openmcx.client.security.Mikey
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.mcptt.CallError
import org.openmcx.client.mcptt.Commencement
import org.openmcx.client.mcptt.SdpNegotiationError
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.VideoDevices
import org.openmcx.client.media.VideoFormat
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One MCVideo call (private or group) and its media. */
class VideoCall internal constructor(
    val id: Int,
    val outgoing: Boolean,
    /** The other party's MCVideo ID (private), or who called the group. */
    @Volatile var peer: String?,
    val transmissionControl: Boolean,
    /** The group of a group call; null for a private call. */
    val groupId: String? = null,
    /** `private`, `prearranged` or `chat` (the session-type). */
    val sessionType: String = "private",
) {
    val isGroup get() = groupId != null
    /** A call with the MCVideo server itself: a push to it or a pull of a recording from it. */
    val withServer get() = sessionType == McvideoService.PUSH_TO_SERVER || sessionType == McvideoService.PULL_FROM_SERVER
    /** An ambient viewing call (§15): `remote-init` or `local-init`; null for any other call. */
    @Volatile var ambientType: String? = null
    /** Our part in an ambient viewing call: `viewing` or `viewed-to`. */
    @Volatile var ambientRole: String? = null
    /** Whether we may release the call: an ambient viewing call only with the release capability (§15.2.1.3, annex D.3). */
    @Volatile var mayRelease: Boolean = true
    /** The viewed-to user of a remotely initiated ambient viewing call gets no indication of it (§15.2.1.2 step 8, NOTE 4). */
    val hidden get() = ambientType == "remote-init" && ambientRole == "viewed-to"
    /** The PCK-ID of a protected one-to-one call (TS 33.180 7.4), once selected. */
    @Volatile var pckId: Long? = null
    /** Where the server saved the video of a push to it (§13.2.3.2.3). */
    @Volatile var pushUrl: String? = null
    /** `normal`, `emergency` or `imminent` (peril): as requested, or as the server last announced (§6.2.8). */
    @Volatile var priority: String = "normal"
    /** The session's focus URI (the dialog's remote Contact), where the conference subscription goes. */
    @Volatile var sessionUri: String? = null
    /** Functional aliases (TS 24.281 §20): the alias the caller presented, and the alias that was called. */
    @Volatile var callingAlias: String? = null
    @Volatile var calledAlias: String? = null
    /** A private call to a functional alias, before the server names who holds it (§10.2.2.2.1, 300). */
    internal var toAlias: Boolean = false
    /** Group calls: who is in the call, MC ID to endpoint status, from the conference event package (§9.2.3). */
    @Volatile var participants: Map<String, String> = emptyMap()
    /** The conference subscription: its Call-ID (NOTIFYs may beat its 200) and, once answered, its dialog. */
    @Volatile internal var conferenceCallId: String? = null
    @Volatile internal var conference: Dialog? = null
    @Volatile var state: String = "setup" // setup, ringing, established, released
    @Volatile var dialog: Dialog? = null
    @Volatile var media: VideoMediaSession? = null
    @Volatile var releaseReason: String? = null
    @Volatile var invite: SipRequest? = null
    internal var incoming: ServerTransaction? = null
    internal val answered = CompletableDeferred<Boolean>()
    internal var refresher: Job? = null
    @Volatile var sessionExpires = 0
    val startedAt = System.currentTimeMillis()
    /** Transmission control events, newest last. */
    val events: MutableList<String> = CopyOnWriteArrayList()
}

interface VideoCallListener {
    fun incoming(call: VideoCall) {}
    fun changed(call: VideoCall) {}
    /** Another member's emergency alert, raised ([alert].active) or cancelled (§11.2). */
    fun alert(alert: VideoAlert) {}
}

/** An emergency alert (§11.2) raised by [user] on [group], with where they were. */
data class VideoAlert(val user: String, val group: String?, val active: Boolean, val location: EmergencyLocation?,
                      val at: Long = System.currentTimeMillis())

/**
 * The MCVideo client's calls: private calls (TS 24.281 §10.2) with or without transmission
 * control, and prearranged group calls (§9.2.1), always with transmission control (TS 24.581),
 * originating and terminating, with session refresh by UPDATE. The signalling is the MCPTT
 * calls' with the MCVideo ICSI, feature tag, PSI and mcvideo-info.
 */
class McvideoService(
    private val client: McxClient,
    private val video: VideoDevices,
    private val codecs: SpeechCodecFactory,
    private val audio: AudioDevices,
) : ServiceHandler {
    private val config = client.config.mcvideo!!
    private val ua = client.ua
    private val scope = client.scope
    private val lock = Mutex()
    private val ids = AtomicInteger()
    private val _calls = MutableStateFlow<List<VideoCall>>(emptyList())
    val calls: StateFlow<List<VideoCall>> = _calls
    val history: MutableList<VideoCall> = CopyOnWriteArrayList()
    var listener: VideoCallListener? = null
    @Volatile var answerMode: String = config.answerMode
    private val format get() = VideoFormat(config.width, config.height, config.frameRate, config.bitrate)

    val active: VideoCall? get() = _calls.value.firstOrNull { it.state != "released" }
    private val _alerts = MutableStateFlow<Map<String, VideoAlert>>(emptyMap())
    /** Active emergency alerts of other members, by their MCVideo ID. */
    val alerts: StateFlow<Map<String, VideoAlert>> = _alerts
    private val _recordings = MutableStateFlow<List<String>>(emptyList())
    /** URLs of this user's pushes to the server, newest last: videos to pull again. */
    val recordings: StateFlow<List<String>> = _recordings
    private val _alerting = MutableStateFlow<Set<String>>(emptySet())
    /** The groups on which this user's own emergency alert is active. */
    val alerting: StateFlow<Set<String>> = _alerting

    /** Each group's current GMK (TS 33.180 5.7), its CSB-ID the GMK-ID; from the GKTP subscriptions. */
    private val groupKeys = java.util.concurrent.ConcurrentHashMap<String, KeyMessage>()
    val groupKeyIds: Map<String, Long> get() = groupKeys.mapValues { it.value.csbId }
    /** The GKTP subscriptions: Call-ID to group. */
    private val keySubscriptions = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // TS 24.281 4.7.1: once authorised, subscribe to each group's GMK at the MCVideo server, and again before it expires.
        if (client.config.security?.protectMedia == true) scope.launch {
            client.status.map { it.mcvideo.authorised }.distinctUntilChanged().collect { authorised ->
                if (!authorised) return@collect
                keyRefresh?.cancel()
                keyRefresh = scope.launch { while (true) { subscribeGroupKeys(); delay(KEY_SUBSCRIPTION_SECONDS * 900L) } }
            }
        }
    }
    private var keyRefresh: Job? = null

    private suspend fun subscribeGroupKeys() {
        val token = client.tokens?.accessToken ?: return
        for (group in (config.groups + config.chatGroups).distinct()) {
            val request = ua.newRequest("SUBSCRIBE", config.psi, NameAddr(config.psi))
            commonHeaders(request)
            request.headers.add("Event", "xcap-diff").add("Accept", "application/xcap-diff+xml").add("Expires", KEY_SUBSCRIPTION_SECONDS.toString())
            val (contentType, body) = Multipart.encode(listOf(
                Part(McvideoInfo.CONTENT_TYPE, McvideoInfo(accessToken = token, requestUri = group, clientId = client.config.clientId).encode().toByteArray()),
                Part(ResourceLists.CONTENT_TYPE, KmsDocuments.gktpResourceList(group).toByteArray())))
            request.headers.add("Content-Type", contentType)
            val subscribe = SipRequest(request.method, request.uri, request.headers, body)
            keySubscriptions[subscribe.callId] = group
            val response = runCatching { ua.send(subscribe) }.getOrNull()
            event("group key subscription for $group: ${response?.status}")
            if (response == null || !response.isSuccess) keySubscriptions.remove(subscribe.callId)
        }
    }

    /** A GKTP NOTIFY (xcap-diff): open the group's GMK, authenticated as from the GMS. */
    private suspend fun keyNotify(group: String, tx: ServerTransaction) {
        val request = tx.request
        if (request.headers["Subscription-State"]?.startsWith("terminated") == true) {
            tx.respond(200); keySubscriptions.remove(request.callId); return
        }
        val security = client.config.security
        val keys = client.videoKeys
        val gms = security?.gmsUri; val root = security?.xcapRoot
        if (keys == null || gms == null || root == null || request.contentType?.startsWith("application/xcap-diff+xml") != true) { tx.respond(400); return }
        tx.respond(200)
        try {
            val now = Mikey.nowNtp()
            val key = keys.openGroupMessage(gms, group, KmsDocuments.parseGktpXcapDiff(String(request.body), group, root, now))
            groupKeys[group] = key
            event("group key for $group: GMK %08x".format(key.csbId))
        } catch (e: Exception) {
            event("group key for $group refused: ${e.message}")
        }
    }

    /**
     * The group's GMK, waiting a little for it: the GMS sends it after start-up (TS 33.180 5.7), and a call placed
     * just then, a chat group joined at once for instance, would otherwise fail.
     */
    private suspend fun awaitGroupKey(group: String): KeyMessage =
        groupKeys[group] ?: kotlinx.coroutines.withTimeoutOrNull(GROUP_KEY_WAIT_MS) {
            event("waiting for the group key for $group")
            while (groupKeys[group] == null) kotlinx.coroutines.delay(100)
            groupKeys[group]
        } ?: throw CallError("no group key for $group yet")

    private fun event(text: String) = client.event("mcvideo: $text")
    private fun changed(call: VideoCall) { _calls.value = _calls.value.toList(); listener?.changed(call) }

    private fun commonHeaders(request: SipRequest) {
        request.headers.add("Contact", client.contactFor(McService.MCVIDEO))
            .add("Allow", ALLOW)
            .add("Supported", "timer")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCVIDEO)}\";require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.mcvideo;require;explicit")
            .add("P-Preferred-Service", Icsi.MCVIDEO)
    }

    private fun bindMedia(transmissionControl: Boolean, implicitRequest: Boolean = false, direction: String = "sendrecv"): Pair<VideoPorts, LocalVideoMedia> {
        val flow = ua.flow ?: throw CallError("not connected")
        val address = flow.connection.localHost
        val ports = VideoPorts.bind(InetAddress.getByName(address), client.config.media.portRange)
        return ports to LocalVideoMedia(address, ports.audio.localPort, ports.video.localPort,
            if (transmissionControl) ports.control.localPort else null,
            McvideoSdp.newSsrc(), McvideoSdp.newSsrc(), McvideoSdp.newSsrc(), implicitRequest && transmissionControl, direction)
    }

    /** Debug builds: corrupt the next PCK offered, to see the called side refuse it (488, warning 136). */
    @Volatile var tamperNextPck = false

    private fun tamper(encoded: String): String {
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        bytes[bytes.size - 70] = (bytes[bytes.size - 70].toInt() xor 1).toByte()
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private val protectedMedia get() = client.config.security?.protectMedia == true
    private val protectedFloor get() = client.config.security?.protectFloor == true

    /** The call's packet keys: transmission control under the CSK, a one-to-one call's media under its [pck]. */
    private fun protection(pck: KeyMessage?, localInitiator: Boolean, gmk: KeyMessage? = null, call: VideoCall? = null): CallProtection? {
        val csk = client.videoCsk.takeIf { protectedFloor }
        if (protectedFloor && csk == null) throw CallError("no MCVideo CSK for protected transmission control")
        return when {
            pck != null -> CallProtection.privateCall(csk, pck, localInitiator)
            // The sender of received group media: the transmitter, or any participant.
            gmk != null -> CallProtection.Group(csk, gmk, config.userId) {
                listOfNotNull(call?.media?.control?.transmitter) + (call?.participants?.keys ?: emptySet())
            }
            csk != null -> CallProtection(csk, null, null)
            else -> null
        }
    }

    private fun newMedia(call: VideoCall, ports: VideoPorts, local: LocalVideoMedia) = VideoMediaSession(scope, ports, local, format,
        video, codecs, audio, client.config.media.amrWbMode) { event("call ${call.id}: $it") }.also { media ->
        media.events = object : TransmissionListener {
            override fun granted(audioSsrc: Long?, videoSsrc: Long?, durationSeconds: Int?) = note(call, "granted${durationSeconds?.let { " ${it}s" } ?: ""}")
            override suspend fun stopTransmitting() = note(call, "stop transmitting")
            override fun taken(user: String?) = note(call, "taken by $user")
            override fun idle() = note(call, "idle")
            override fun rejected(cause: Int?, text: String?) = note(call, "rejected cause $cause")
            override fun revoked(cause: Int?) = note(call, "revoked cause $cause")
            override fun requestFailed() = note(call, "request failed")
            override fun reception(receiving: Boolean, rejectCause: Int?) = note(call, if (receiving) "receiving" else "reception refused $rejectCause")
            override fun remoteResult(start: Boolean, done: Boolean?) = note(call, "remote ${if (start) "start" else "stop"} ${if (done == true) "done" else "not done"}")
        }
    }

    private fun note(call: VideoCall, text: String) { call.events += text; event("call ${call.id} $text"); changed(call) }

    // Originating calls (§10.2.2.2.1).

    /** A private call; with [emergency], an emergency private call (§6.2.8.3). */
    suspend fun privateCall(target: String, commencement: Commencement = Commencement.AUTOMATIC, transmissionControl: Boolean = true,
                            emergency: Boolean = false): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, target, transmissionControl).also { if (emergency) it.priority = "emergency" }, commencement)

    /**
     * A private call to a functional alias (TS 24.281 §10.2.2.2.1 with §20): the request names the alias with
     * <call-to-functional-alias-ind>; the server answers 300 with the MCVideo ID it selected, which is then called with
     * the alias as <called-functional-alias-URI>.
     */
    suspend fun privateCallToAlias(alias: String, commencement: Commencement = Commencement.AUTOMATIC, transmissionControl: Boolean = true): VideoCall {
        val redirect = try {
            return originate(VideoCall(ids.incrementAndGet(), true, alias, transmissionControl).also { it.toAlias = true }, commencement)
        } catch (e: AliasRedirect) { e }
        return originate(VideoCall(ids.incrementAndGet(), true, redirect.selected, transmissionControl).also {
            it.calledAlias = redirect.alias ?: alias }, commencement)
    }

    /** The server's 300 to a call to a functional alias: the MCVideo ID it selected. */
    private class AliasRedirect(val selected: String, val alias: String?) : Exception("redirected to $selected")

    /**
     * A prearranged group call (§9.2.1.2.1): the server invites the group's affiliated members. With
     * [priority] `emergency` or `imminent`, an emergency or imminent peril group call (§6.2.8.1).
     */
    suspend fun groupCall(group: String = config.groups.firstOrNull() ?: throw CallError("no MCVideo group"), priority: String = "normal"): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, null, true, group, "prearranged").also { it.priority = priority }, null)

    /**
     * Join a chat group's call (§9.2.2.2.1): nobody is invited; the server creates the group's
     * session or adds us to it, affiliating us implicitly. Others join the same way.
     */
    suspend fun joinChat(group: String = config.chatGroups.firstOrNull() ?: throw CallError("no MCVideo chat group")): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, null, true, group, "chat"), null)

    // Video push and pull (§12.2, §13.2): private calls in which one side sends without asking.

    /** Push this phone's camera to [target] (§13.2.2.1): it transmits at once (implicit request); they only watch. */
    suspend fun push(target: String): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, target, true, sessionType = PUSH), Commencement.AUTOMATIC)

    /** Pull [target]'s camera (§12.2.2.1): they transmit at once (their answer's implicit request); this phone watches. */
    suspend fun pull(target: String): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, target, true, sessionType = PULL), Commencement.AUTOMATIC)

    /** Push to the MCVideo server (§13.2.2.4), which records it and sends its URL ([VideoCall.pushUrl]). */
    suspend fun pushToServer(): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, config.psi, true, sessionType = PUSH_TO_SERVER), Commencement.AUTOMATIC)

    /** Pull a recording from the MCVideo server (§12.2.2.4), played as it was recorded. */
    suspend fun pullFromServer(url: String): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, config.psi, true, sessionType = PULL_FROM_SERVER).also { it.pushUrl = url },
            Commencement.AUTOMATIC)

    /** Ask the server to start ([start]) or end another participant's transmission in the call (TS 24.581 §9.2.22–9.2.25). */
    /**
     * An ambient viewing call (§15.2.1.1): [local] false views [target]'s camera without them doing anything (remote-init),
     * true asks [target] to view ours (local-init). Always automatic commencement (Priv-Answer-Mode: Auto); the viewed-to
     * side transmits by implicit request.
     */
    suspend fun ambient(target: String, local: Boolean = false): VideoCall =
        originate(VideoCall(ids.incrementAndGet(), true, target, true, sessionType = AMBIENT).also {
            it.ambientType = if (local) "local-init" else "remote-init"
            it.ambientRole = if (local) "viewed-to" else "viewing"
            it.mayRelease = false // until the 200 (OK) carries the release capability
        }, Commencement.FORCED)

    suspend fun remoteTransmission(user: String, start: Boolean, call: VideoCall? = active) {
        val media = call?.media?.takeIf { it.isStarted } ?: throw CallError("no established video call")
        if (!call.transmissionControl) throw CallError("the call has no transmission control")
        media.control.remote(user, start)
    }

    private suspend fun originate(call: VideoCall, commencement: Commencement?): VideoCall {
        // The user knows nothing of a hidden ambient viewing call: a call of their own ends it (the server
        // releases it too, with call-request-initiated-by-viewed-to-client, §15.4.3).
        active?.takeIf { it.hidden }?.let { release(it, "call-request-initiated-by-viewed-to-client") }
        if (active != null) throw CallError("already in a video call")
        val transmissionControl = call.transmissionControl
        val target = call.peer
        // §10.2.2.2.1 step 11, §15.2.1.1 with TS 33.180 7.4: a protected one-to-one call's media under a PCK we
        // generate for the called MCVideo ID, offered in a=key-mgmt. Calls with the server itself stay clear.
        // A call to an alias is keyed once the server names the user who holds it (the 300, then the call to them).
        val pck = if (protectedMedia && !call.isGroup && !call.withServer && !call.toAlias) {
            val keys = client.videoKeys ?: throw CallError("no MCVideo keys for a protected call")
            keys.makePck(target ?: throw CallError("no one to call"))
        } else null
        // Calls with the MCVideo server itself (recording, playback) stay in the clear, transmission control too.
        // A protected group or chat call's media under the group's GMK (TS 33.180 7.3).
        val gmk = if (protectedMedia && call.isGroup) awaitGroupKey(call.groupId ?: throw CallError("no group")) else null
        val protection = if (call.withServer) null else protection(pck?.first, localInitiator = true, gmk = gmk, call = call)
        _calls.value = _calls.value + call
        val (ports, bound) = bindMedia(transmissionControl,
            implicitRequest = call.sessionType == PUSH || call.sessionType == PUSH_TO_SERVER || call.ambientType == "local-init",
            direction = when (call.ambientRole) { "viewing" -> "recvonly"; "viewed-to" -> "sendonly"; else -> "sendrecv" })
        // A test of the called side's check (debug builds): one flipped bit in the I_MESSAGE's signature.
        val offered = pck?.second?.let { if (tamperNextPck) { tamperNextPck = false; tamper(it) } else it }
        val local = if (pck != null) bound.copy(protected = true, keyMgmt = offered) else if (gmk != null) bound.copy(protected = true) else bound
        val media = newMedia(call, ports, local)
        media.protection = protection
        pck?.let { call.pckId = it.first.csbId; event("call ${call.id}: PCK %08x for $target".format(it.first.csbId)) }
        call.media = media
        try {
            val request = ua.newRequest("INVITE", config.psi, NameAddr(config.psi))
            commonHeaders(request)
            request.headers.add("Session-Expires", "1800").add("Min-SE", "90")
            when (commencement) {
                Commencement.AUTOMATIC -> request.headers.add("Answer-Mode", "Auto")
                Commencement.MANUAL -> request.headers.add("Answer-Mode", "Manual")
                Commencement.FORCED -> request.headers.add("Priv-Answer-Mode", "Auto")
                null -> Unit
            }
            // §20: the alias this user presents (the reference client's mcvideo-fa use), the call to an alias, and the
            // alias called once the server selected its MCVideo ID.
            val presented = client.aliasesOf(McService.MCVIDEO).value.inUse.takeIf { !call.withServer }
            call.callingAlias = presented
            if (call.toAlias) call.calledAlias = target
            val aliasCalled = call.calledAlias.takeIf { !call.toAlias }
            val info = priorityInfo(if (call.isGroup) McvideoInfo(sessionType = call.sessionType, requestUri = call.groupId, clientId = client.config.clientId,
                    functionalAlias = presented)
                else McvideoInfo(sessionType = call.sessionType, callingUserId = config.userId, clientId = client.config.clientId,
                    videoPullUrl = if (call.sessionType == PULL_FROM_SERVER) call.pushUrl else null, ambientViewingType = call.ambientType,
                    functionalAlias = presented, callToFunctionalAlias = if (call.toAlias) true else null, calledFunctionalAlias = aliasCalled), call.priority)
            resourcePriority(call.priority)?.let { request.headers.add("Resource-Priority", it) }
            val parts = mutableListOf(Part("application/sdp", McvideoSdp.offer(local).toByteArray()), Part(McvideoInfo.CONTENT_TYPE, info.encode().toByteArray()))
            // A call with the server names nobody (§13.2.2.4, §12.2.2.4).
            if (!call.isGroup && !call.withServer) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOfNotNull(target)).toByteArray())
            val (contentType, body) = Multipart.encode(parts)
            request.headers.add("Content-Type", contentType)
            val invite = SipRequest(request.method, request.uri, request.headers, body)
            call.invite = invite
            event("call ${call.id}: " + if (call.isGroup) "${call.sessionType} group video call on ${call.groupId}"
                else if (call.ambientType != null) "${call.ambientType} ambient viewing call to $target"
                else "private video call to $target${if (transmissionControl) "" else " without transmission control"}")
            val response = ua.send(invite, provisional = { r ->
                if (r.status == 180 || r.status == 183) { call.state = "ringing"; changed(call) }
            })
            val sent = ua.lastSent[invite.callId] ?: invite
            call.invite = sent
            if (call.toAlias && response.status == 300) {
                val redirect = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }
                    ?.let { McvideoInfo.parse(it.text) }
                val selected = redirect?.requestUri ?: throw CallError("functional alias redirect without an MCVideo ID")
                event("call ${call.id}: functional alias ${redirect.calledFunctionalAlias ?: target} is $selected")
                throw AliasRedirect(selected, redirect.calledFunctionalAlias)
            }
            if (!response.isSuccess) {
                val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
                // 480 after ringing, without a decline (warning 110), is the callee's no-answer timeout: report it as one.
                val unanswered = response.status == 480 && call.state == "ringing" && "110" !in warning
                throw CallError("${response.status} ${response.reason}$warning${if (unanswered) " (not answered)" else ""}",
                    if (unanswered) 408 else response.status)
            }
            val dialog = Dialog.fromResponse(sent, response)
            call.dialog = dialog
            ua.ack(dialog, sent)
            val sdp = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == "application/sdp" }
                ?: throw CallError("200 without SDP")
            val remote = McvideoSdp.remote(sdp.text)
            if (remote.transmissionControl != transmissionControl) throw CallError("transmission control not negotiated as offered")
            if (remote.protected != local.protected) throw CallError("media protection not negotiated as offered")
            media.start(remote, McvideoSdp.VIDEO_PT)
            call.sessionUri = response.headers["Contact"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
            if (call.ambientType != null) call.mayRelease = releaseCapability(response.headers.values("Feature-Caps"))
            call.state = "established"
            call.sessionExpires = response.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: 0
            val refresher = response.headers["Session-Expires"]?.substringAfter("refresher=", "")?.substringBefore(';')?.trim()
            if (call.sessionExpires > 0 && refresher != "uas") startRefresh(call)
            event("call ${call.id}: established")
            changed(call)
            if (call.isGroup) scope.launch { subscribeConference(call) }
            return call
        } catch (e: Exception) {
            release(call, e.message ?: "failed")
            throw e
        }
    }

    // Terminating calls (§10.2.2.2.2).

    private fun isMcvideo(request: SipRequest) =
        request.headers["P-Asserted-Service"]?.trim() == Icsi.MCVIDEO ||
            request.headers.values("Accept-Contact").any { it.contains(Icsi.encoded(Icsi.MCVIDEO)) } ||
            request.contentType?.let { ct -> Multipart.parse(ct, request.body).any { it.mediaType == McvideoInfo.CONTENT_TYPE } } == true

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        if (request.method == "MESSAGE" && isMcvideo(request) && receiveAlert(tx)) return true
        if (request.method == "NOTIFY" && request.headers["Event"]?.substringBefore(';')?.trim() == "xcap-diff") {
            val group = keySubscriptions[request.callId] ?: return false
            keyNotify(group, tx)
            return true
        }
        if (request.method == "NOTIFY" && request.headers["Event"]?.substringBefore(';')?.trim() == "conference") {
            val call = _calls.value.firstOrNull { it.conferenceCallId == request.callId } ?: return false
            tx.respond(200)
            conferenceNotify(call, request)
            return true
        }
        val call = _calls.value.firstOrNull { it.dialog?.callId == request.callId || it.invite?.callId == request.callId }
        if (call == null && (request.method != "INVITE" || !isMcvideo(request))) return false
        when (request.method) {
            "INVITE" -> if (call == null) incoming(tx) else reinvite(call, tx)
            "UPDATE" -> tx.respond(200, extra = sessionTimerHeaders(request))
            "BYE" -> {
                tx.respond(200)
                event("call ${call!!.id}: released by the server")
                // §15.4.3: the viewing user of an ambient viewing call is told why.
                val reason = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }
                    ?.let { runCatching { McvideoInfo.parse(it.text).releaseReason }.getOrNull() }
                release(call, reason ?: request.headers["Reason"] ?: "released by the server")
            }
            "INFO" -> {
                tx.respond(200)
                // The server announces a changed call state (emergency, imminent peril) in the call (§6.2.8.1), or,
                // in the g.3gpp.mcvideo-info package, where it saved a push to it (§13.2.3.2.3).
                val info = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }
                    ?.let { runCatching { McvideoInfo.parse(it.text) }.getOrNull() }
                if (info?.videoPushUrl != null && call!!.sessionType == PUSH_TO_SERVER) {
                    call.pushUrl = info.videoPushUrl
                    _recordings.value = (_recordings.value + info.videoPushUrl).distinct().takeLast(20)
                    note(call, "recorded at ${info.videoPushUrl}")
                } else info?.let { notePriority(call!!, it) }
            }
            "MESSAGE", "NOTIFY" -> tx.respond(200)
            else -> return false
        }
        return true
    }

    private suspend fun incoming(tx: ServerTransaction) {
        val request = tx.request
        val parts = Multipart.parse(request.contentType, request.body)
        val info = parts.firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }?.let { McvideoInfo.parse(it.text) }
        val sdp = parts.firstOrNull { it.mediaType == "application/sdp" }?.text
        if (info == null || sdp == null) { tx.respond(400, "Bad Request"); return }
        val sessionType = info.sessionType ?: "private"
        if (sessionType !in setOf("private", "prearranged", "chat", PUSH, PULL, AMBIENT)) { tx.respond(488); event("$sessionType video calls are not supported"); return }
        if (active != null) { tx.respond(486); return }
        val remote = try { McvideoSdp.remote(sdp) } catch (e: SdpNegotiationError) { event("offer refused: ${e.message}"); tx.respond(488); return }
        // One-to-one calls (private, push, pull, ambient viewing) name no group, even with a request URI in the info.
        val group = if (sessionType in ONE_TO_ONE) null else info.callingGroupId ?: info.requestUri
        val call = VideoCall(ids.incrementAndGet(), false, info.callingUserId, remote.transmissionControl, group, sessionType)
        call.callingAlias = info.functionalAlias
        call.calledAlias = info.calledFunctionalAlias
        if (sessionType == AMBIENT) {
            // §15.2.1.2 steps 7 and 10: the type and our role; we may release only with the release capability.
            call.ambientType = info.ambientViewingType ?: "remote-init"
            call.ambientRole = if (call.ambientType == "remote-init") "viewed-to" else "viewing"
            call.mayRelease = releaseCapability(request.headers.values("Feature-Caps"))
        }
        // §10.2.2.2.2 with TS 33.180 7.4: a protected one-to-one offer carries the caller's PCK, which must
        // authenticate as from the calling MCVideo ID; otherwise 488 with warning 136.
        var pck: org.openmcx.client.security.KeyMessage? = null
        if (remote.protected) {
            if (group != null) {
                if (groupKeys[group] == null) { tx.respond(488); event("protected call on $group without its group key"); return }
            } else try {
                val keys = client.videoKeys ?: throw CallError("no MCVideo keys")
                pck = keys.acceptPck(info.callingUserId ?: throw CallError("no caller"), remote.keyMgmt ?: throw CallError("protected call without a PCK"))
            } catch (e: Exception) {
                event("call from ${info.callingUserId}: PCK refused: ${e.message}")
                tx.respond(488, extra = listOf("Warning" to "399 ${ua.sentBy} \"136 authentication of the MIKEY-SAKE I_MESSAGE failed\""))
                return
            }
        }
        notePriority(call, info)
        call.incoming = tx
        call.invite = request
        _calls.value = _calls.value + call
        event("call ${call.id}: incoming ${if (group != null) "$sessionType group video call on $group from"
            else if (call.ambientType != null) "${call.ambientType} ambient viewing call from" else "private video call from"} ${info.callingUserId}")
        val manual = when {
            call.ambientType != null -> false // §15.2.1.2 step 6: always answered automatically
            request.headers["Priv-Answer-Mode"].equals("Auto", true) -> false
            request.headers["Answer-Mode"].equals("Manual", true) -> true
            request.headers["Answer-Mode"].equals("Auto", true) -> answerMode == "manual"
            else -> answerMode == "manual"
        }
        if (!call.hidden) listener?.incoming(call)
        if (manual) {
            tx.respond(180, extra = listOf("Require" to "timer", "Contact" to client.contactFor(McService.MCVIDEO)))
            call.state = "ringing"
            changed(call)
            val decision = withTimeoutOrNull(MANUAL_ANSWER_MS) {
                select<Boolean?> { call.answered.onAwait { it }; tx.cancelled.onAwait { null } }
            }
            when (decision) {
                true -> Unit
                false -> {
                    tx.respond(480, extra = listOf("Warning" to "399 ${ua.sentBy} \"110 user declined the call invitation\""))
                    release(call, "declined"); return
                }
                null -> { if (!tx.finalSent) tx.respond(480); release(call, if (tx.cancelled.isCompleted) "cancelled" else "not answered"); return }
            }
        }
        // A pulled user transmits at once: its answer requests the transmission implicitly (§12.2.2.2 step 1); so does
        // the viewed-to user of a remotely initiated ambient viewing call (§15.2.1.1 step 13).
        val (ports, local) = try {
            bindMedia(remote.transmissionControl, implicitRequest = sessionType == PULL || call.hidden,
                direction = McvideoSdp.answerDirection(remote.direction)).let { (p, l) -> p to l.copy(protected = remote.protected) }
        } catch (e: Exception) {
            tx.respond(480); release(call, "no media ports"); return
        }
        val media = newMedia(call, ports, local)
        media.protection = try {
            protection(pck, localInitiator = false, gmk = if (remote.protected && group != null) groupKeys[group] else null, call = call)
        } catch (e: CallError) {
            tx.respond(480); release(call, e.message ?: "no keys"); return
        }
        pck?.let { call.pckId = it.csbId; event("call ${call.id}: PCK %08x from ${info.callingUserId}".format(it.csbId)) }
        call.media = media
        val offered = request.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: 90
        call.sessionExpires = offered
        val response = tx.response(200, body = McvideoSdp.answer(remote, local).toByteArray(), contentType = "application/sdp", extra = listOf(
            "Contact" to client.contactFor(McService.MCVIDEO), "Require" to "timer", "Supported" to "timer",
            "Session-Expires" to "$offered;refresher=uas", "Allow" to ALLOW))
        call.dialog = Dialog.fromRequest(request, tx.localTag)
        call.sessionUri = request.headers["Contact"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
        media.start(remote, remote.videoPayloadType)
        tx.send(response)
        call.state = "established"
        startRefresh(call)
        event("call ${call.id}: answered")
        changed(call)
        if (call.isGroup) scope.launch { subscribeConference(call) }
    }

    /** SUBSCRIBE to the session's conference event package (§9.2.3.1): who is in the group call. */
    private suspend fun subscribeConference(call: VideoCall) {
        val uri = call.sessionUri ?: run { event("call ${call.id}: no session URI to subscribe to"); return }
        val request = ua.newRequest("SUBSCRIBE", uri, NameAddr(uri))
        commonHeaders(request)
        request.headers.add("Event", "conference").add("Accept", ConferenceInfo.CONTENT_TYPE).add("Expires", "4294967295")
            .add("Content-Type", McvideoInfo.CONTENT_TYPE)
        val info = McvideoInfo(requestUri = call.groupId, clientId = client.config.clientId)
        val subscribe = SipRequest(request.method, request.uri, request.headers, info.encode().toByteArray())
        call.conferenceCallId = subscribe.callId
        val response = runCatching { ua.send(subscribe) }.getOrNull()
        event("call ${call.id}: conference subscription ${response?.status}")
        if (response == null || !response.isSuccess) { call.conferenceCallId = null; return }
        call.conference = Dialog.fromResponse(ua.lastSent[subscribe.callId] ?: subscribe, response)
    }

    /** A conference NOTIFY (RFC 4575): update the call's participants. */
    private fun conferenceNotify(call: VideoCall, request: SipRequest) {
        for (part in Multipart.parse(request.contentType, request.body)) {
            if (part.mediaType != ConferenceInfo.CONTENT_TYPE) continue
            val info = runCatching { ConferenceInfo.parse(part.text) }.getOrElse { event("call ${call.id}: bad conference-info: ${it.message}"); continue }
            call.participants = info.applyTo(call.participants)
        }
        if (request.headers["Subscription-State"]?.startsWith("terminated") == true) { call.conference = null; call.conferenceCallId = null }
        event("call ${call.id}: participants ${call.participants.keys}")
        changed(call)
    }

    private suspend fun reinvite(call: VideoCall, tx: ServerTransaction) {
        val media = call.media ?: run { tx.respond(491); return }
        val parts = Multipart.parse(tx.request.contentType, tx.request.body)
        parts.firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }?.let { runCatching { McvideoInfo.parse(it.text) }.getOrNull() }
            ?.let { notePriority(call, it) }
        val sdp = parts.firstOrNull { it.mediaType == "application/sdp" }?.text
        val remote = sdp?.let { runCatching { McvideoSdp.remote(it) }.getOrNull() }
        val answer = remote?.let { McvideoSdp.answer(it, media.local.copy(keyMgmt = null)) }
        tx.respond(200, body = answer?.toByteArray() ?: ByteArray(0), contentType = answer?.let { "application/sdp" },
            extra = sessionTimerHeaders(tx.request))
    }

    // Emergency alerts (§11.2).

    /**
     * Raise ([on], with where this UE is) or cancel this user's emergency alert on [group]: a MESSAGE
     * to the MCVideo PSI (§11.2.1.1); the server tells the group's other affiliated members.
     */
    suspend fun sendAlert(group: String = config.groups.firstOrNull() ?: throw CallError("no MCVideo group"), on: Boolean,
                          location: EmergencyLocation? = null, originatedBy: String? = null) {
        require(originatedBy == null || !on) { "only a cancellation names another user's alert" }
        if (on && location == null) throw CallError("an emergency alert needs this phone's location")
        val request = ua.newRequest("MESSAGE", config.psi, NameAddr(config.psi))
        request.headers.add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCVIDEO)}\";require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.mcvideo;require;explicit")
            .add("P-Preferred-Service", Icsi.MCVIDEO)
        // An authorised user cancels another member's alert by naming its originator (§11.2.1.2).
        val info = McvideoInfo(requestUri = group, clientId = client.config.clientId, alert = on, originatedBy = originatedBy)
        val parts = mutableListOf(Part(McvideoInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (on) parts += Part(EmergencyLocation.CONTENT_TYPE, location!!.encode().toByteArray())
        val (contentType, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", contentType)
        val response = ua.send(SipRequest(request.method, request.uri, request.headers, body))
        if (!response.isSuccess) {
            val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
            throw CallError("${response.status} ${response.reason}$warning", response.status)
        }
        if (originatedBy == null) _alerting.value = if (on) _alerting.value + group else _alerting.value - group
        else _alerts.value = _alerts.value - originatedBy
        event("emergency alert on $group ${if (on) "raised at ${location?.latitude}, ${location?.longitude}" else "cancelled"}: ${response.status}")
    }

    /** Another member's alert or its cancellation, from the server (§11.2.2.1); false when [tx] is not one. */
    private suspend fun receiveAlert(tx: ServerTransaction): Boolean {
        val request = tx.request
        val parts = Multipart.parse(request.contentType, request.body)
        val info = parts.firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }?.let { runCatching { McvideoInfo.parse(it.text) }.getOrNull() }
        val on = info?.alert ?: return false
        // The alert's originator: named in originated-by when an authorised user cancelled someone else's.
        val user = info.originatedBy ?: info.callingUserId ?: run { tx.respond(400, "Bad Request"); return true }
        val location = parts.firstOrNull { it.mediaType == EmergencyLocation.CONTENT_TYPE }?.let { runCatching { EmergencyLocation.parse(it.text) }.getOrNull() }
        tx.respond(200)
        if (user == config.userId) {
            // About our own alert: it is active, or someone authorised has cancelled it.
            val group = info.callingGroupId ?: info.requestUri
            if (group != null) _alerting.value = if (on) _alerting.value + group else _alerting.value - group
            event("own emergency alert on $group ${if (on) "active" else "cancelled by ${info.callingUserId}"}")
            return true
        }
        val alert = VideoAlert(user, info.callingGroupId, on, location)
        _alerts.value = if (on) _alerts.value + (user to alert) else _alerts.value - user
        event("emergency alert from $user on ${info.callingGroupId}: ${if (on) "raised at ${location?.latitude}, ${location?.longitude}" else "cancelled"}")
        listener?.alert(alert)
        return true
    }

    // Emergency and imminent peril (§6.2.8).

    private fun priorityInfo(info: McvideoInfo, priority: String) = when (priority) {
        // §6.2.8.1.1 step 3: an emergency call states that it is not an alert.
        "emergency" -> info.copy(emergency = true, alert = false)
        "imminent" -> info.copy(imminentPeril = true)
        else -> info
    }

    // TS 24.281 §6.2.8.1.15: from the MCVideo service configuration document.
    private fun resourcePriority(priority: String): String? = client.serviceResourcePriorities["mcvideo"]?.get(priority)?.takeIf { it.isNotBlank() }
        ?: if (priority != "normal") throw CallError("no Resource-Priority provisioned for $priority") else null

    /** Follow the call state in a request from the server: its emergency and imminent peril indications. */
    private fun notePriority(call: VideoCall, info: McvideoInfo) {
        val next = when {
            info.emergency == true -> "emergency"
            info.imminentPeril == true -> "imminent"
            info.emergency == false || info.imminentPeril == false -> "normal"
            else -> return
        }
        if (next != call.priority) {
            call.priority = next
            call.events += "priority $next"
            event("call ${call.id}: now $next")
            changed(call)
        }
    }

    /**
     * Raise or cancel the emergency (or imminent peril) state of the call in progress by re-INVITE
     * in its dialog (§6.2.8.1.4, §6.2.8.1.9, §6.2.8.3): the server tells the other participants.
     */
    suspend fun setPriority(emergency: Boolean, enabled: Boolean, call: VideoCall? = active) {
        call ?: throw CallError("no video call")
        val dialog = call.dialog ?: throw CallError("the call is not established")
        val media = call.media ?: throw CallError("no media")
        val wanted = if (!enabled) "normal" else if (emergency) "emergency" else "imminent"
        var info = if (call.isGroup) McvideoInfo(sessionType = call.sessionType, requestUri = call.groupId, clientId = client.config.clientId)
        else McvideoInfo(sessionType = "private", callingUserId = config.userId, clientId = client.config.clientId)
        info = when {
            emergency && enabled -> info.copy(emergency = true, alert = false)
            emergency -> info.copy(emergency = false)
            enabled -> info.copy(imminentPeril = true)
            else -> info.copy(imminentPeril = false)
        }
        val parts = mutableListOf(Part("application/sdp", McvideoSdp.offer(media.local.copy(keyMgmt = null)).toByteArray()), Part(McvideoInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (!call.isGroup) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOfNotNull(call.peer)).toByteArray())
        val request = ua.inDialog(dialog, "INVITE")
        commonHeaders(request)
        resourcePriority(if (enabled) wanted else "normal")?.let { request.headers.add("Resource-Priority", it) }
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
        call.events += "priority $wanted"
        event("call ${call.id}: $wanted")
        changed(call)
    }

    private fun sessionTimerHeaders(request: SipRequest): List<Pair<String, String>> {
        val se = request.headers["Session-Expires"] ?: return listOf("Contact" to client.contactFor(McService.MCVIDEO))
        return listOf("Contact" to client.contactFor(McService.MCVIDEO), "Require" to "timer", "Supported" to "timer", "Session-Expires" to se)
    }

    private fun startRefresh(call: VideoCall) {
        if (call.sessionExpires <= 0) return
        call.refresher?.cancel()
        call.refresher = scope.launch {
            while (call.state == "established") {
                delay(call.sessionExpires * 500L)
                val dialog = call.dialog ?: return@launch
                if (call.state != "established") return@launch
                val update = ua.inDialog(dialog, "UPDATE")
                update.headers.add("Contact", client.contactFor(McService.MCVIDEO)).add("Supported", "timer")
                    .add("Session-Expires", "${call.sessionExpires};refresher=uac")
                val response = runCatching { ua.send(update, dialog) }.getOrNull()
                event("call ${call.id}: session refresh ${response?.status}")
                if (response == null || response.status == 481 || response.status == 408) { release(call, "session refresh failed"); return@launch }
            }
        }
    }

    // User actions.

    fun answer(): Boolean = _calls.value.firstOrNull { it.state == "ringing" && !it.outgoing }?.answered?.complete(true) ?: false
    fun reject(): Boolean = _calls.value.firstOrNull { it.state == "ringing" && !it.outgoing }?.answered?.complete(false) ?: false

    /** Ask to transmit (TS 24.581 §6.2.4.3). */
    suspend fun transmit(call: VideoCall? = active) {
        val media = call?.media ?: throw CallError("no video call")
        if (!media.isStarted) throw CallError("the call is not established")
        media.control.request()
    }

    suspend fun stopTransmit(call: VideoCall? = active) { call?.media?.takeIf { it.isStarted }?.control?.release() }

    suspend fun hangup(call: VideoCall? = active) {
        call ?: throw CallError("no video call")
        // §15.2.1.3 step 1: an ambient viewing call is released only with the release capability.
        if (!call.mayRelease && call.state == "established") throw CallError("this ambient viewing call may not be released here")
        val dialog = call.dialog
        when {
            dialog != null -> {
                call.media?.takeIf { it.isStarted }?.let { m -> if (m.control.state.value == TxState.HAS_PERMISSION) m.control.release() }
                val bye = ua.inDialog(dialog, "BYE")
                runCatching { withTimeoutOrNull(5_000) { ua.send(bye, dialog) } }
                release(call, "released")
            }
            call.outgoing && call.invite != null -> { ua.cancel(call.invite!!); release(call, "cancelled") }
            call.incoming != null -> call.answered.complete(false)
            else -> release(call, "released")
        }
    }

    private suspend fun release(call: VideoCall, reason: String) = lock.withLock {
        if (call.state == "released") return@withLock
        call.state = "released"
        call.releaseReason = reason
        // In the history as soon as it is no longer active, so the last call is always there to look at.
        history += call
        while (history.size > 20) history.removeAt(0)
        call.refresher?.cancel()
        runCatching { call.media?.stop() }
        call.conferenceCallId = null
        call.conference?.let { d ->
            // End the conference subscription (Expires 0); the server may have ended it with the session already.
            call.conference = null
            scope.launch {
                val unsubscribe = ua.inDialog(d, "SUBSCRIBE")
                commonHeaders(unsubscribe)
                unsubscribe.headers.add("Event", "conference").add("Expires", "0")
                runCatching { withTimeoutOrNull(3_000) { ua.send(unsubscribe, d) } }
            }
        }
        _calls.value = _calls.value - call
        event("call ${call.id}: $reason")
        listener?.changed(call)
    }

    override suspend fun flowLost() { _calls.value.forEach { release(it, "connection lost") } }

    /** The user logs off: every call is left (BYE, or CANCEL while calling); an ambient viewing call too. */
    override suspend fun logOff() {
        _calls.value.forEach { call -> runCatching { hangup(call) }.onFailure { release(call, "logged off") } }
    }

    companion object {
        /** How long a protected group call waits for the group's GMK before giving up. */
        const val GROUP_KEY_WAIT_MS = 10_000L
        const val ALLOW = "INVITE, ACK, CANCEL, BYE, UPDATE, INFO, MESSAGE, NOTIFY, OPTIONS"
        const val MANUAL_ANSWER_MS = 20_000L
        const val PUSH = "one-to-one video push"
        const val PULL = "one-to-one video pull"
        const val PUSH_TO_SERVER = "one-to-server video push"
        const val PULL_FROM_SERVER = "one-from-server video pull"
        const val AMBIENT = "ambient-viewing"
        val ONE_TO_ONE = setOf("private", PUSH, PULL, AMBIENT)
        /** How long a GKTP subscription lasts; renewed at nine tenths of it. */
        const val KEY_SUBSCRIPTION_SECONDS = 3600L
        /** Annex D.3: the release capability of an ambient viewing call, in Feature-Caps. */
        const val AMBIENT_RELEASE = "g.3gpp.mcvideo.ambient-viewing-call-release"

        fun releaseCapability(featureCaps: List<String>) = featureCaps.any { AMBIENT_RELEASE in it }
    }
}
