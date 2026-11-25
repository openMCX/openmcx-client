//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.openmcx.client.mcvideo.CallProtection
import org.openmcx.client.mcvideo.GroupKeySubscriptions
import org.openmcx.client.security.GroupKeys
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KmsDocuments
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
import org.openmcx.client.client.ClientRules
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

/**
 * The call types of TS 24.379: private (§11.1; a first-to-answer call is a private call once an invitee answered,
 * §11.1.1, its session type "first-to-answer" kept in [Call.firstToAnswer]), prearranged (§10.1.1), chat (§10.1.2)
 * and ad hoc (§17) group calls. A broadcast group call (§6.2.8.2) is a prearranged or ad hoc call with
 * [Call.broadcast].
 */
enum class CallType(val sessionType: String) {
    PRIVATE("private"), PREARRANGED("prearranged"), ADHOC("adhoc"), CHAT("chat");

    companion object {
        const val FIRST_TO_ANSWER = "first-to-answer"
        /** An ambient listening call (§11.1.6) is a private call; its type and our role are in [Call.ambientType]. */
        const val AMBIENT = "ambient-listening"
        fun of(sessionType: String?) = if (sessionType == FIRST_TO_ANSWER || sessionType == AMBIENT) PRIVATE
            else entries.firstOrNull { it.sessionType == sessionType }
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
    /**
     * The implicit floor request of the call's initial INVITE (TS 24.380 §14.2.5): null without one, then "offered",
     * and at the 200 (OK) "accepted", "granted" (by the answer's "mc_granted", §14.2.4) or "not accepted".
     */
    @Volatile var implicitFloorRequest: String? = null
    /** The o= of this client's SDP in the call: one session ID, the version incremented (RFC 3264 §8). */
    internal val origin = McpttSdp.Origin()
    /** Our initial INVITE's offer had "mc_granted" (TS 24.380 §14.2.4). */
    @Volatile var grantedOffered: Boolean = false
    /** A first-to-answer call (§11.1.1.2.1.1 step 15, §11.1.1.2.1.2 step 8): [invitees] the users it was made to. */
    @Volatile var firstToAnswer: Boolean = false
    @Volatile var invitees: List<String> = emptyList()
    /** A broadcast group call (TS 24.379 §6.2.8.2, `<broadcast-ind>`): only its initiator talks (TS 24.380 bit B). */
    @Volatile var broadcast: Boolean = false
    /** An ad hoc group call's participant criteria (§17.2.2.1.1 step 12), as last accepted (§17.2.6.1.1 step 2a). */
    @Volatile var criteria: String? = null
    /** The constituent group a call on a regroup or temporary group is placed from (§10.1.1.2.1.1 step 14 d)). */
    @Volatile var associatedGroup: String? = null
    /** The MCPTT session identity (the 200 (OK)'s Contact): rejoining an ad hoc call targets it (§17.2.5.1.1). */
    @Volatile var sessionUri: String? = null
    /** The floor priority negotiated ("mc_priority", TS 24.380 §14.3.3) that our Floor Requests carry. */
    @Volatile var floorPriority: Int? = null
    /** The outcome of the last modification of an ad hoc call's participants or criteria (§17.2.6.1.1). */
    @Volatile var lastModification: String? = null
    /** A protected incoming call's keys, opened before it is answered: its PCK (private) or GMK (group). */
    internal var keys: Pair<org.openmcx.client.security.KeyMessage?, org.openmcx.client.security.KeyMessage?> = null to null
    /** A first-to-answer invitee's PCK I_MESSAGE for its answer's a=key-mgmt (§11.1.1.2.1.2 step 5). */
    internal var answerKeyMgmt: String? = null
    /** An ambient listening call (§11.1.6): its type, `remote-init` or `local-init`, and our role, `listening` or `listened-to`. */
    @Volatile var ambientType: String? = null
    @Volatile var ambientRole: String? = null
    /** §11.1.6.2.1.3 step 1: an ambient listening call is released here only with the release capability (annex D.3). */
    @Volatile var mayRelease: Boolean = true
    /**
     * The listened-to user of a remotely initiated ambient listening call is given no indication of it (§11.1.6.2.1.2,
     * TS 23.379 §10.14.3.2): the call is not shown and is not [McpttService.active].
     */
    val hidden: Boolean get() = ambientType == "remote-init" && ambientRole == "listened-to"
    /** The answer mode of this private call ("Auto" or "Manual"), which its transfer or forwarding repeats (§11.1.1.2.1.1 14A) a), 14C) a)). */
    @Volatile var answerMode: String? = null
    /** Its <forwarding-sequence-number> (§11.1.1.2.1.1 14B), 14C) a1)). */
    @Volatile var forwardingSequence: Int = 0
    /** An incoming call's <mcptt-request-uri>: the MCPTT ID called, the forwarding user of a manual forwarding (§11.1.9.2.1 2) d) i) E)). */
    @Volatile var requestUri: String? = null
    /** The private call service the call is placed for or received as: `transfer`, `forwarding`, `remote` or `call-back`. */
    @Volatile var service: String? = null
    /** A ringing call declined with this status and warning (manual forwarding, §11.1.9.2.1: 480 with warning 175). */
    internal var declineWith: Pair<Int, String>? = null
}

/**
 * What a call placed for a private call service carries (TS 24.379 §11.1.1.2.1.1 14A) to 14C)): the service elements
 * of <anyExt> (<call-transfer-ind>, <call-forwarding-ind>, ...), a Priv-Answer-Mode in place of the commencement
 * mode, the Resource-Priority asked for (<ric-app-level-priority>), <remotely-initiated-call-request-ind>, the
 * <forwarding-sequence-number>; [alongside]: placed while the user's call with the requester goes on (the transferred
 * user, §11.1.8.2.2). [outcome] completes with the success of the origination: true on 180, 183 or 2xx, and on 480 with
 * warning 175 or 191 (the call was forwarded), false otherwise.
 */
class ServiceCall(
    val kind: String,
    val service: Map<String, String> = emptyMap(),
    val privAnswerMode: String? = null,
    val resourcePriority: String? = null,
    val remoteRequest: Boolean = false,
    val forwardingSequence: Int? = null,
    val alongside: Boolean = false,
) {
    val outcome = CompletableDeferred<Boolean>()
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
    /**
     * The local answer mode for calls that do not dictate one; the client's, so that the service settings carry it
     * (TS 24.379 §7.2.3 4) a)).
     */
    var answerMode: String
        get() = client.answerModeOf(McService.MCPTT) ?: config.answerMode
        set(value) = client.setAnswerMode(McService.MCPTT, value)

    /**
     * The user's call: the newest one not released (a transferred user's call to the target is newer than its call
     * with the requester, §11.1.8.2.2); a hidden ambient listening call (§11.1.6.2.1.2) is never the user's call.
     */
    val active: Call? get() = _calls.value.lastOrNull { it.state != "released" && !it.hidden }

    /** The call-back, remotely initiated call, transfer and forwarding procedures (TS 24.379 §10.1.5, §11.1.5, §11.1.7-§11.1.9). */
    val services = PrivateServices(client, this)

    /** Regroups and temporary groups (TS 24.379 §16, TS 24.481 §6.3.14): their notices and the requests for them. */
    val regroups = Regroups(client)

    init {
        ua.onAck = { ack -> onAck(ack) }
        // TS 33.180 5.7, TS 24.481 §6.3.13.2.1: with protected media, once authorised, subscribe to each group's GMK
        // (the <GKTPs> of its MCS GKTP document) at the GMS-URI; each subscription is refreshed in its dialog before it
        // expires (RFC 6665 §4.1.2.2).
        if (client.config.security?.protectMedia == true) scope.launch {
            client.status.map { it.mcptt.authorised }.distinctUntilChanged().collect { authorised ->
                keyRefresh?.cancel()
                if (!authorised) { keyRefresh = null; return@collect }
                // A round of SUBSCRIBEs is not cut short, so each accepted subscription has its dialog to end it with.
                keyRefresh = scope.launch { withContext(NonCancellable) { subscribeGroupKeys() }; refreshGroupKeys() }
            }
        }
    }

    /** Each group's GMKs (TS 33.180 5.7), their CSB-IDs the GMK-IDs; from the GKTP subscriptions. */
    private val groupKeys = GroupKeys()
    val groupKeyIds: Map<String, Long> get() = groupKeys.ids

    // TS 33.180 clause 8.4: the GMK of a group also protects MCData payloads for its MC group IDs (as the Linux client).
    init { client.mcpttGroupKeys = groupKeys }
    private val keySubscriptions = GroupKeySubscriptions()
    private var keyRefresh: Job? = null

    private suspend fun subscribeGroupKeys() {
        val token = client.tokens?.accessToken ?: return
        val gms = config.gmsSubscriptionPsi ?: run { event("group key subscription: no GMS-URI configured"); return }
        for (group in config.groups.distinct()) subscribeGroupKey(gms, group, token)
    }

    /**
     * TS 24.481 §6.3.13.2.1: the initial SIP SUBSCRIBE for [group]'s <GKTPs>: b) the Request-URI the GMS-URI, the
     * MCPTT ICSI in P-Preferred-Service (c)) and in the Contact (f)), an mcptt-info body with the access token, and the
     * resource list; Event xcap-diff (RFC 5875), as the Linux client.
     */
    private suspend fun subscribeGroupKey(gms: String, group: String, token: String) {
        val request = ua.newRequest("SUBSCRIBE", gms, NameAddr(gms))
        request.headers.add("Contact", client.contactFor(McService.MCPTT)).add("P-Preferred-Service", Icsi.MCPTT)
            .add("Event", GroupKeySubscriptions.EVENT).add("Accept", GroupKeySubscriptions.ACCEPT)
            .add("Expires", GroupKeySubscriptions.EXPIRES_SECONDS.toString())
        val (contentType, body) = Multipart.encode(listOf(
            Part(McpttInfo.CONTENT_TYPE, McpttInfo(accessToken = token, requestUri = group, clientId = client.config.clientId).encode().toByteArray()),
            Part(ResourceLists.CONTENT_TYPE, KmsDocuments.gktpResourceList(group).toByteArray())))
        request.headers.add("Content-Type", contentType)
        val subscribe = SipRequest(request.method, request.uri, request.headers, body)
        keySubscriptions.sent(subscribe.callId, group)
        val response = runCatching { ua.send(subscribe) }.getOrNull()
        val expires = response?.headers?.get("Expires")?.let { ", $it s" } ?: ""
        event("group key subscription for $group: ${response?.status}$expires")
        if (response == null || !response.isSuccess) { keySubscriptions.ended(subscribe.callId); return }
        runCatching { Dialog.fromResponse(ua.lastSent[subscribe.callId] ?: subscribe, response) }.getOrNull()
            ?.let { keySubscriptions.accepted(it, GroupKeySubscriptions.granted(response)) }
    }

    private suspend fun refreshGroupKeys() {
        while (true) {
            val next = keySubscriptions.nextRefresh() ?: return
            delay((next - System.currentTimeMillis()).coerceAtLeast(0))
            for ((dialog, group) in keySubscriptions.due())
                withContext(NonCancellable) { refreshGroupKey(dialog, group) }
        }
    }

    /**
     * TS 24.481 §6.3.13.2.1: the re-SUBSCRIBE in [dialog], the MCPTT ICSI in the Contact. A refused or failed refresh
     * ends that subscription (RFC 6665 §4.1.2.2); a new initial SUBSCRIBE takes its place.
     */
    private suspend fun refreshGroupKey(dialog: Dialog, group: String) {
        val request = GroupKeySubscriptions.refresh(ua, dialog, group)
        request.headers.add("Contact", client.contactFor(McService.MCPTT)).add("P-Preferred-Service", Icsi.MCPTT)
        val response = runCatching { ua.send(request, dialog) }.getOrNull()
        event("group key subscription for $group refreshed: ${response?.status}")
        if (response != null && response.isSuccess) {
            keySubscriptions.accepted(dialog, GroupKeySubscriptions.granted(response))
            return
        }
        keySubscriptions.ended(dialog.callId)
        val gms = config.gmsSubscriptionPsi ?: return
        client.tokens?.accessToken?.let { subscribeGroupKey(gms, group, it) }
    }

    /**
     * End every GKTP subscription in its dialog (RFC 6665 §4.1.2.3, Expires 0): before the SIP de-REGISTER the UE
     * releases all dialogs that use the contact address (TS 24.229 §5.1.1.6.1). The renewal stops first; the final
     * NOTIFYs are still answered 200 (the dialogs stay known until then).
     */
    private suspend fun endGroupKeySubscriptions() {
        keyRefresh?.cancelAndJoin()
        keyRefresh = null
        for (dialog in keySubscriptions.takeDialogs()) {
            val group = keySubscriptions.group(dialog.callId)
            val request = GroupKeySubscriptions.unsubscribe(ua, dialog)
            request.headers.add("P-Preferred-Service", Icsi.MCPTT)
            val response = runCatching { ua.send(request, dialog) }.getOrNull()
            event("group key subscription for $group ended: ${response?.status}")
        }
    }

    /** A GKTP NOTIFY (xcap-diff): open the group's GMK, authenticated as from the GMS (TS 33.180 5.7). */
    private suspend fun keyNotify(group: String, tx: ServerTransaction) {
        val request = tx.request
        if (request.headers["Subscription-State"]?.startsWith("terminated") == true) {
            tx.respond(200); keySubscriptions.ended(request.callId); return
        }
        val security = client.config.security
        val keys = client.keysOf(McService.MCPTT)
        val gms = security?.gmsUri; val root = security?.xcapRoot
        if (keys == null || gms == null || root == null || request.contentType?.startsWith(GroupKeySubscriptions.ACCEPT) != true) { tx.respond(400); return }
        tx.respond(200)
        try {
            val key = keys.openGroupMessage(gms, group, KmsDocuments.parseGktpXcapDiff(String(request.body), group, root))
            groupKeys.add(group, key)
            event("group key for $group: GMK %08x".format(key.csbId) + (if (groupKeys.active(group)?.csbId != key.csbId) ", not active yet" else ""))
        } catch (e: Exception) {
            event("group key for $group refused: ${e.message}")
        }
    }

    /** The group's active GMK, waiting a little for it (sent by the GMS after start-up, TS 33.180 5.7). */
    private suspend fun awaitGroupKey(group: String): KeyMessage =
        groupKeys.active(group) ?: withTimeoutOrNull(GROUP_KEY_WAIT_MS) {
            event("waiting for the group key for $group")
            while (groupKeys.active(group) == null) delay(100)
            groupKeys.active(group)
        } ?: throw CallError("no group key for $group yet")

    private fun event(text: String) = client.event("mcptt: $text")
    private fun changed(call: Call) { _calls.value = _calls.value.toList(); listener?.changed(call) }

    private fun commonHeaders(request: SipRequest, initialInvite: Boolean = false) {
        request.headers.add("Contact", if (initialInvite) client.sessionContact(McService.MCPTT) else client.contactFor(McService.MCPTT))
            .add("Allow", ALLOW)
            .add("Supported", "timer")
            .add("Accept-Contact", "*;+g.3gpp.mcptt;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCPTT)
    }

    private val protectedMedia get() = client.config.security?.protectMedia == true
    private val protectedFloor get() = client.config.security?.protectFloor == true

    /**
     * The call's packet keys (MCPTT CS-IDs, TS 33.180 annex E): floor control under the CSK (9.4), a private call's
     * speech under its [pck] (7.4), a group call's under its [gmk] (7.3).
     */
    private fun protection(pck: KeyMessage?, localInitiator: Boolean, gmk: KeyMessage?, call: Call): CallProtection? {
        val csk = client.cskOf(McService.MCPTT).takeIf { protectedFloor }
        if (protectedFloor && csk == null) throw CallError("no MCPTT CSK for protected floor control")
        return when {
            pck != null -> CallProtection.privateCall(csk, pck, localInitiator, mcvideo = false)
            // The sender of received group speech: the floor's talker, else any member of the group (its group
            // document): speech can arrive before the Floor Taken that names the talker.
            gmk != null -> CallProtection.Group(csk, gmk, config.userId, mcvideo = false) {
                listOfNotNull(runCatching { call.media?.floor?.talker }.getOrNull()) +
                    (call.groupId?.let { client.groupDocuments[it]?.members } ?: emptyList())
            }
            csk != null -> CallProtection(csk, null, null, mcvideo = false)
            else -> null
        }
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

    /**
     * TS 24.380 §6.2.4.3.5 1 b): the Floor Indicator of the call's Floor Request and Floor Release: emergency (a group
     * or private emergency call) or imminent peril, else normal; bit F when the call negotiated queueing.
     * Bit B for a broadcast group call (§6.2.8.2).
     */
    private fun indicatorOf(call: Call, local: LocalMedia, remote: RemoteMedia): Int = ClientRules.floorIndicator(
        broadcast = call.broadcast, emergency = call.priority == "emergency", imminent = call.priority == "imminent",
        queueing = local.queueing && remote.queueing)

    private fun floor(call: Call, text: String) { call.floorEvents += text; event("call ${call.id} floor $text"); changed(call) }

    // Originating calls.

    /**
     * Private call (TS 24.379 §11.1.1.2.1.1); without [floorControl], a private call without floor control
     * (§11.1.2.2): offered without `m=application`, both parties then speak at once, like a telephone call.
     */
    suspend fun privateCall(target: String, commencement: Commencement = Commencement.AUTOMATIC, floorControl: Boolean = true,
                            emergency: Boolean = false, implicit: Implicit? = null): Call =
        originate(CallType.PRIVATE, target, null, listOf(target), commencement, floorControl, if (emergency) "emergency" else "normal",
            implicit = implicit)

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
    suspend fun groupCall(group: String, priority: String = "normal", implicit: Implicit? = null, broadcast: Boolean = false): Call {
        val doc = regroups.configurationOf(group)
        // A chat group's calls are chat group calls (§10.1.2): joining is the same request.
        if (doc?.chat == true && !broadcast) return chatCall(group, priority, implicit)
        return originate(CallType.PREARRANGED, null, group, emptyList(), null, priority = priority, implicit = implicit, broadcast = broadcast)
    }

    /**
     * Initiate or join a chat group call (TS 24.379 §10.1.2.2.1.1): the INVITE with session type "chat" naming the group;
     * the same request joins the call once it is ongoing, and the server invites nobody. Leaving is [hangup] (BYE,
     * §10.1.2.2.3.1), and rejoining is joining again.
     */
    suspend fun chatCall(group: String, priority: String = "normal", implicit: Implicit? = null): Call =
        originate(CallType.CHAT, null, group, emptyList(), null, priority = priority, implicit = implicit)

    /**
     * A broadcast group call (TS 24.379 §10.1.1.2.1.1 with §6.2.8.2): a prearranged group call with <broadcast-ind>
     * "true"; only the initiator talks, and the server ends the call when its transmission ends.
     */
    suspend fun broadcastCall(group: String, priority: String = "normal"): Call = groupCall(group, priority, broadcast = true)

    /**
     * A first-to-answer call (TS 24.379 §11.1.1.2.1.1 steps 2, 10 a) and 15): to two or more users; the server rings
     * them all and the call continues as a private call with the first to answer, named in the 200 (OK)'s
     * <mcptt-called-party-id> (200 step 4).
     */
    suspend fun firstToAnswerCall(targets: List<String>, emergency: Boolean = false, floorControl: Boolean = true,
                                  implicit: Implicit? = null): Call {
        val list = GroupCallRules.firstToAnswerTargets(targets, config.userId)
        val profile = client.profile
        if (!GroupCallRules.mayRequestFirstToAnswer(profile != null, profile?.allows("allow-request-first-to-answer-call") == true))
            throw CallError("the user profile does not allow first-to-answer calls")
        return originate(CallType.PRIVATE, null, null, list, null, floorControl, if (emergency) "emergency" else "normal",
            implicit = implicit, firstToAnswer = true)
    }

    /** The highest floor priority to offer in group calls (TS 24.380 §14.3.3); the user's choice over the configured one. */
    @Volatile var floorPriority: Int? = null

    private fun floorPriorityOffer(group: String?): Int? = GroupCallRules.floorPriorityOffer(floorPriority ?: config.floorPriority,
        group?.let { client.groupDocuments[it]?.userPriorities?.get(config.userId) }, client.profile?.groupCallPriority)

    /**
     * The implicit floor request of one call (TS 24.380 §14.2.5) and whether its offer has "mc_granted" (§14.2.4),
     * instead of the configured `implicit_floor_request` and `offer_granted`.
     */
    data class Implicit(val request: Boolean, val granted: Boolean)

    /**
     * Ad hoc group call (TS 24.379 §17.2.2.1.1): the participants are chosen here (step 11), or the criteria the
     * server determines them by (step 12, <call-participants-criterias>); [broadcast] makes it a broadcast ad hoc group
     * call (step 9).
     */
    suspend fun adhocCall(participants: List<String>, criteria: String? = null, broadcast: Boolean = false): Call {
        if (participants.isEmpty() == (criteria == null)) throw CallError("an ad hoc call names its participants or the criteria for them")
        return originate(CallType.ADHOC, null, null, participants, null, criteria = criteria?.let(GroupCallRules::adhocCriteria),
            broadcast = broadcast)
    }

    /** The last ad hoc call left (§17.2.4), which [rejoinAdhoc] rejoins by its session identity. */
    @Volatile var lastAdhoc: Call? = null
        private set

    /** Rejoin the ad hoc call left (§17.2.5.1.1): §17.2.2.1.1 with the Request-URI the MCPTT session identity. */
    suspend fun rejoinAdhoc(): Call {
        val left = lastAdhoc ?: throw CallError("no ad hoc call to rejoin")
        val uri = left.sessionUri ?: throw CallError("the ad hoc call's session identity is not known")
        return originate(CallType.ADHOC, null, left.groupId, emptyList(), null, requestUriOverride = uri)
    }

    /**
     * Modify an established ad hoc call (TS 24.379 §17.2.6.1.1): a re-INVITE with the mcptt-info as established (step 1)
     * and either a resource list of the users to [invite] and to [remove] ("method" INVITE or BYE, step 2) or the new
     * [criteria] (step 2a), with the established media (step 3). Needs <allow-to-modify-adhoc-group-call-participants-info>.
     */
    suspend fun modifyAdhoc(invite: List<String> = emptyList(), remove: List<String> = emptyList(), criteria: String? = null,
                            call: Call? = active): String {
        call ?: throw CallError("no call")
        if (call.type != CallType.ADHOC || call.state != "established") throw CallError("no established ad hoc call")
        val dialog = call.dialog ?: throw CallError("the call is not established")
        val media = call.media ?: throw CallError("no media")
        client.profile?.let { if (!it.allows("allow-to-modify-adhoc-group-call-participants-info")) throw CallError("the user profile does not allow modifying ad hoc calls") }
        val entries = try { GroupCallRules.adhocModificationEntries(invite, remove, criteria?.let(GroupCallRules::adhocCriteria)) }
            catch (e: IllegalArgumentException) { throw CallError(e.message ?: "invalid modification") }
        val newCriteria = criteria?.let(GroupCallRules::adhocCriteria)
        val info = McpttInfo(sessionType = "adhoc", callingGroupId = call.groupId, clientId = client.config.clientId,
            participantCriteria = newCriteria ?: call.criteria, functionalAlias = call.callingAlias)
        val parts = mutableListOf(Part("application/sdp", McpttSdp.offer(media.local, call.origin).toByteArray()),
            Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (entries.isNotEmpty()) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(entries).toByteArray())
        val request = ua.inDialog(dialog, "INVITE")
        commonHeaders(request)
        val (contentType, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", contentType)
        val reinvite = SipRequest(request.method, request.uri, request.headers, body)
        val response = ua.send(reinvite, dialog)
        if (response.isSuccess) ua.ack(dialog, ua.lastSent[reinvite.callId] ?: reinvite)
        val outcome = "${response.status}" + (response.headers["Warning"]?.let { " $it" } ?: "")
        call.lastModification = outcome
        if (response.isSuccess && newCriteria != null) call.criteria = newCriteria
        event("call ${call.id}: ad hoc modification ${entries.ifEmpty { listOf("criteria $newCriteria") }}: $outcome")
        changed(call)
        if (!response.isSuccess) throw CallError("${response.status} ${response.reason}" + (response.headers["Warning"]?.let { " ($it)" } ?: ""), response.status)
        return outcome
    }

    private suspend fun originate(type: CallType, peer: String?, group: String?, targets: List<String>, commencement: Commencement?,
                                  floorControl: Boolean = true, priority: String = "normal", toAlias: Boolean = false,
                                  calledAlias: String? = null, implicit: Implicit? = null, broadcast: Boolean = false,
                                  firstToAnswer: Boolean = false, criteria: String? = null, requestUriOverride: String? = null,
                                  extra: ServiceCall? = null, ambientType: String? = null): Call {
        if (active != null && extra?.alongside != true) throw CallError("already in a call")
        // TS 23.379 §10.14.3.3: a call request of the listened-to user ends a hidden ambient listening call; the server
        // releases it before setting up the new call.
        _calls.value.filter { it.hidden }.forEach { release(it, "call-request-initiated-by-listened-to-client") }
        val groupCall = type == CallType.PREARRANGED || type == CallType.CHAT
        // §10.1.1.2.1.1, §10.1.2.2.1.1: no call on a group whose document says <preconfigured-group-use-only>.
        if (groupCall && !GroupCallRules.callsAllowed(group?.let { client.groupDocuments[it]?.preconfiguredUseOnly }))
            throw CallError("calls are not allowed on the preconfigured group $group")
        // §10.1.1.2.1.1 step 14 d), §10.1.2.2.1.1 step 13 d): the constituent group of a regroup or temporary group.
        val associated = if (groupCall && group != null) regroups.callingConstituent(group) else null
        if (firstToAnswer && protectedMedia) throw CallError("protected first-to-answer calls are not supported")
        val resource = resourcePriority(priority)
        // TS 33.180 7.4 (TS 24.379 §11.1.1.2.1.1): a protected private call's media under a new PCK for the called
        // MCPTT ID, offered in a=key-mgmt; 7.3: a protected group call's media under the group's GMK. A call to a
        // functional alias has no MCPTT ID to key the PCK for yet; an ad hoc group call has no GMK (as MCVideo).
        val pck = if (protectedMedia && type == CallType.PRIVATE && !toAlias && !firstToAnswer) {
            val keys = client.keysOf(McService.MCPTT) ?: throw CallError("no MCPTT keys for a protected call")
            keys.makePck(peer ?: throw CallError("no one to call"))
        } else null
        val gmk = if (protectedMedia && groupCall) awaitGroupKey(group ?: throw CallError("no group")) else null
        val call = Call(ids.incrementAndGet(), type, true, peer, group).also {
            it.floorControl = floorControl; it.priority = priority; it.broadcast = broadcast; it.firstToAnswer = firstToAnswer
            it.invitees = if (firstToAnswer) targets else emptyList(); it.criteria = criteria; it.associatedGroup = associated
            it.ambientType = ambientType
            it.ambientRole = when (ambientType) { "remote-init" -> "listening"; "local-init" -> "listened-to"; else -> null }
            it.service = extra?.kind
        }
        val protection = protection(pck?.first, localInitiator = true, gmk = gmk, call = call)
        _calls.value = _calls.value + call
        val (ports, bound) = bindMedia()
        // §11.1.2.2 steps 12-13: no media-floor control section; the speech SSRC is announced instead.
        val floored = if (floorControl) bound else bound.copy(floorControl = false, audioSsrc = McpttSdp.newFloorSsrc())
        // TS 24.380 §14.3.3: a group call offers the floor priority asked for ("mc_priority").
        val prioritised = if (groupCall && floorControl) floored.copy(floorPriority = floorPriorityOffer(group)) else floored
        // TS 24.379 §6.2.1 item 2c: an ambient listening call's speech is recvonly for the listening user, sendonly
        // for the listened-to user.
        val directed = when (call.ambientRole) { "listening" -> prioritised.copy(direction = "recvonly")
            "listened-to" -> prioritised.copy(direction = "sendonly"); else -> prioritised }
        val local = when {
            pck != null -> directed.copy(protected = true, keyMgmt = pck.second)
            gmk != null -> directed.copy(protected = true)
            else -> directed
        }
        // TS 24.379 §6.4, TS 24.380 §14.2.5: the initial INVITE may request the floor implicitly; §14.2.4: with
        // "mc_granted" when the floor granted in the 200 (OK) is accepted (offer_granted). Only this offer carries them
        // (§14.5): the call's own media ([local]) has neither, so no re-INVITE repeats them.
        // §11.1.6.2.1.1 steps 12, 13 (§6.4): an ambient listening call's offer is always an implicit request: the
        // listened-to originator's own floor request (local-init), else a request to grant the floor to the invited user.
        val implicitRequest = floorControl && call.ambientRole != "listening" &&
            (call.ambientRole == "listened-to" || (implicit?.request ?: config.implicitFloorRequest))
        val grantedOffer = implicitRequest && call.ambientType == null && (implicit?.granted ?: config.offerGranted)
        val offered = when {
            implicitRequest -> local.copy(implicitRequest = true, granted = grantedOffer, audioSsrc = McpttSdp.newFloorSsrc())
            call.ambientRole == "listening" -> local.copy(implicitRequest = true)
            else -> local
        }
        call.implicitFloorRequest = if (implicitRequest) "offered" else null
        call.grantedOffered = grantedOffer
        val media = newMedia(call, ports, local)
        media.protection = protection
        pck?.let { event("call ${call.id}: PCK %08x for $peer".format(it.first.csbId)) }
        call.media = media
        try {
            // §17.2.5.1.1: a rejoin goes to the MCPTT session identity; every other call to the participating function's PSI.
            val target = requestUriOverride ?: config.psi
            val request = ua.newRequest("INVITE", target, NameAddr(target))
            commonHeaders(request, initialInvite = true)
            request.headers.add("Session-Expires", config.sessionExpires.coerceAtLeast(90).toString()).add("Min-SE", "90")
            // §11.1.1.2.1.1 14A) a), 14C) a): a transferred or forwarded call repeats the answer mode of the call it
            // follows in Priv-Answer-Mode; §11.1.6.2.1.1 step 14: an ambient listening call is always Priv-Answer-Mode Auto.
            val privAnswerMode = extra?.privAnswerMode ?: "Auto".takeIf { ambientType != null }
            when {
                privAnswerMode != null -> request.headers.add("Priv-Answer-Mode", privAnswerMode)
                commencement == Commencement.AUTOMATIC -> request.headers.add("Answer-Mode", "Auto")
                commencement == Commencement.MANUAL -> request.headers.add("Answer-Mode", "Manual")
                commencement == Commencement.FORCED -> request.headers.add("Priv-Answer-Mode", "Auto")
            }
            call.answerMode = privAnswerMode ?: when (commencement) { Commencement.MANUAL -> "Manual"; null -> null; else -> "Auto" }
            // §11.1.7.2.2 3) b), §10.1.5.2.2: the Resource-Priority of a remotely initiated call's <ric-app-level-priority>.
            (resource ?: extra?.resourcePriority)?.let { request.headers.add("Resource-Priority", it) }
            // §9A, §11.1.1.2.1.1 step 14c: the alias this user presents (private and ad hoc calls, as the reference client),
            // the call to an alias, and the alias called once the server selected its MCPTT ID.
            val presented = client.aliases.value.inUse.takeIf { type != CallType.PREARRANGED && type != CallType.CHAT && ambientType == null }
            call.callingAlias = presented
            call.calledAlias = calledAlias ?: peer.takeIf { toAlias }
            // §11.1.1.2.1.1 14B) a): a private call the user initiates has <forwarding-sequence-number> 0; 14C) a1): a
            // forwarded one the forwarded call's, incremented; a transferred or remotely initiated one none.
            // §11.1.1.2.1.1 step 15 a): a first-to-answer call's session type; 14B a) is for private calls only.
            // §11.1.6.2.1.1 steps 7, 8: an ambient listening call's session type and <ambient-listening-type>.
            val sequence = extra?.forwardingSequence ?: if (type == CallType.PRIVATE && !firstToAnswer && ambientType == null && extra == null) 0 else null
            call.forwardingSequence = sequence ?: 0
            val info = priorityInfo(McpttInfo(sessionType = when { firstToAnswer -> CallType.FIRST_TO_ANSWER; ambientType != null -> CallType.AMBIENT
                    else -> type.sessionType },
                ambientListeningType = ambientType, service = extra?.service ?: emptyMap(),
                remotelyInitiatedCallRequest = if (extra?.remoteRequest == true) true else null,
                requestUri = group, clientId = client.config.clientId, broadcast = if (broadcast) true else null,
                associatedGroupId = associated, participantCriteria = criteria,
                functionalAlias = presented, callToFunctionalAlias = if (toAlias) true else null, calledFunctionalAlias = calledAlias,
                forwardingSequenceNumber = sequence), priority)
            val parts = mutableListOf(Part("application/sdp", McpttSdp.offer(offered, call.origin).toByteArray()),
                Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray()))
            if (targets.isNotEmpty()) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(targets).toByteArray())
            val (contentType, body) = Multipart.encode(parts)
            request.headers.add("Content-Type", contentType)
            val invite = SipRequest(request.method, request.uri, request.headers, body)
            call.invite = invite
            event("call ${call.id}: ${info.sessionType}${if (broadcast) " broadcast" else ""} call to ${peer ?: group ?: targets}" +
                (criteria?.let { " by criteria $it" } ?: "") + (associated?.let { " from $it" } ?: "") +
                (if (floorControl) "" else " without floor control") + (local.floorPriority?.let { ", floor priority $it" } ?: "") +
                if (priority != "normal") " ($priority)" else "")
            val response = ua.send(invite, provisional = { r ->
                if (r.status == 180 || r.status == 183) {
                    extra?.outcome?.complete(true) // §11.1.7.2.2, §11.1.8.2.2, §11.1.9.2.2: progress is success
                    // §11.1.6.2.1.1: the listened-to originator of a local-init call sees nothing ring.
                    if (call.ambientRole != "listened-to") { call.state = "ringing"; changed(call) }
                }
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
                // 480 with warning 175 or 191: the call is forwarded, the origination a success (§11.1.9.2.2).
                extra?.outcome?.complete(PrivateServiceRules.forwarded(response.status, response.headers["Warning"]))
                throw CallError("${response.status} ${response.reason}$warning", response.status)
            }
            extra?.outcome?.complete(true)
            // §11.1.6.2.1.1 (200 (OK)): the release capability of an ambient listening call (annex D.3).
            if (ambientType != null) call.mayRelease = PrivateServiceRules.ambientReleaseAllowed(response.headers.values("Feature-Caps"))
            val dialog = Dialog.fromResponse(sent, response)
            call.dialog = dialog
            ua.ack(dialog, sent)
            val parts2 = Multipart.parse(response.contentType, response.body)
            val sdp = parts2.firstOrNull { it.mediaType == "application/sdp" } ?: throw CallError("200 without SDP")
            parts2.firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }?.let {
                val answerInfo = McpttInfo.parse(it.text)
                if (type == CallType.ADHOC) call.groupId = answerInfo.callingGroupId ?: call.groupId // D8: the ad hoc group identity
                // §11.1.1.2.1.1, 200 step 4: the invitee that answered a first-to-answer call.
                if (firstToAnswer) call.peer = answerInfo.calledPartyId ?: answerInfo.requestUri ?: call.peer
                else if (type == CallType.PRIVATE && answerInfo.requestUri != null) call.peer = answerInfo.requestUri
                if (answerInfo.broadcast == true) call.broadcast = true
                notePriority(call, answerInfo, quiet = true)
            }
            // The MCPTT session identity, for a rejoin (§17.2.5.1.1).
            call.sessionUri = response.headers["Contact"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
            val remote = McpttSdp.remote(sdp.text)
            if (remote.floorControl != floorControl) throw CallError("floor control not negotiated as offered")
            // TS 24.380 §14.3.3: the Floor Request priority is the one negotiated.
            call.floorPriority = GroupCallRules.negotiatedFloorPriority(local.floorPriority, remote.floorPriority)
            if (remote.protected != local.protected) throw CallError("media protection not negotiated as offered")
            // TS 24.380 §14.4: the answer's "mc_implicit_request" and "mc_granted" count only when our offer had them.
            val accepted = ClientRules.answerParameterKept(remote.implicitRequest, implicitRequest)
            val grantedByAnswer = accepted && ClientRules.answerParameterKept(remote.granted, grantedOffer)
            if (implicitRequest && call.ambientRole == null) {
                call.implicitFloorRequest = when { grantedByAnswer -> "granted"; accepted -> "accepted"; else -> "not accepted" }
                event("call ${call.id}: implicit floor request ${call.implicitFloorRequest}")
            }
            // §14.3.6: the RTP SSRC of a grant in the answer is its "mc_ssrc", else the one our offer announced.
            media.start(remote, priority = call.floorPriority, implicit = accepted,
                grantedSsrc = if (grantedByAnswer) remote.grantedSsrc ?: offered.audioSsrc else null,
                indicator = { indicatorOf(call, local, remote) })
            // The microphone opens while the grant is awaited, as on a PTT press (pttPress).
            if (accepted && !grantedByAnswer) { media.requestedAt = System.currentTimeMillis(); media.prepareTalk() }
            if (grantedByAnswer) floor(call, "granted by mc_granted in the 200 (OK)")
            call.state = "established"
            call.sessionExpires = response.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: 0
            val refresher = response.headers["Session-Expires"]?.substringAfter("refresher=", "")?.substringBefore(';')?.trim()
            if (call.sessionExpires > 0 && refresher != "uas") startRefresh(call)
            event("call ${call.id}: established${call.groupId?.let { " on $it" } ?: ""}")
            changed(call)
            return call
        } catch (e: Exception) {
            extra?.outcome?.complete(false)
            release(call, e.message ?: "failed")
            throw e
        }
    }

    /**
     * An ambient listening call (TS 24.379 §11.1.6.2.1.1): listen to [target] without them doing anything (`remote-init`,
     * needs <allow-request-remote-initiated-ambient-listening>), or let [target] listen to this phone ([local],
     * `local-init`, needs <allow-request-locally-initiated-ambient-listening>). Automatic commencement (Priv-Answer-Mode
     * Auto, step 14), one target in the resource list (step 9), the speech recvonly or sendonly; the listened-to user
     * holds the floor for the whole call.
     */
    suspend fun ambientCall(target: String, local: Boolean = false): Call {
        if (target == config.userId) throw CallError("an ambient listening call is to another user")
        val right = if (local) "allow-request-locally-initiated-ambient-listening" else "allow-request-remote-initiated-ambient-listening"
        client.profile?.let { if (!it.allows(right)) throw CallError("the user profile does not allow this ambient listening call ($right)") }
        return originate(CallType.PRIVATE, target, null, listOf(target), null, ambientType = if (local) "local-init" else "remote-init")
    }

    /**
     * A private call placed for a private call service (call-back fulfilment, remotely initiated, transfer, forwarding),
     * with what [extra] says it carries.
     */
    internal suspend fun serviceCall(target: String, commencement: Commencement?, extra: ServiceCall): Call =
        originate(CallType.PRIVATE, target, null, listOf(target), commencement, extra = extra)

    /** A prearranged group call placed for a remotely initiated group call request (§10.1.5.2.2). */
    internal suspend fun serviceGroupCall(group: String, extra: ServiceCall): Call {
        val doc = regroups.configurationOf(group)
        val type = if (doc?.chat == true) CallType.CHAT else CallType.PREARRANGED
        return originate(type, null, group, emptyList(), null, extra = extra)
    }

    /** The calls not released, hidden ones included. */
    internal val current: List<Call> get() = _calls.value.filter { it.state != "released" }

    // Terminating calls.

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        if (request.method == "NOTIFY" && request.headers["Event"]?.substringBefore(';')?.trim() == GroupKeySubscriptions.EVENT) {
            val group = keySubscriptions.group(request.callId) ?: return false
            keyNotify(group, tx)
            return true
        }
        val call = _calls.value.firstOrNull { it.dialog?.callId == request.callId || it.invite?.callId == request.callId }
        when (request.method) {
            "INVITE" -> if (call == null) incoming(tx) else reinvite(call, tx)
            "UPDATE" -> if (call == null) tx.respond(481) else update(call, tx)
            "BYE" -> {
                if (call == null) { tx.respond(481); return true }
                tx.respond(200)
                // §11.1.1.2.1.2 (SIP BYE) 1): a first-to-answer invitee not selected; b) the user may be told why.
                val notSelected = GroupCallRules.firstToAnswerRelease(call.firstToAnswer, infoOf(request)?.releaseReason)
                event("call ${call.id}: released by the server" + (notSelected?.let { " ($it)" } ?: ""))
                release(call, notSelected ?: request.headers["Reason"] ?: "released by the server")
            }
            "INFO", "MESSAGE" -> {
                // TS 24.379 §16.2.1.3, §16.2.1.4: a regroup notice.
                if (call == null && request.method == "MESSAGE" && regroups.handle(tx)) return true
                // §10.1.5.2.2, §11.1.5.2.2, §11.1.7.2.2, §11.1.8.2, §11.1.9.2: a private call service request or response.
                if (call == null && request.method == "MESSAGE" && services.handle(tx)) return true
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
        val ambient = info.sessionType == CallType.AMBIENT
        // The server releases a hidden ambient listening call before it invites the listened-to user to another call
        // (§11.1.6.4.3): what is left of it here goes.
        if (!ambient) _calls.value.filter { it.hidden }.forEach { release(it, "released for another call") }
        // §11.1.1.2.1.2 step 8 c), §11.1.6.2.1.2 step 1 a): busy on another call, 486 (Busy Here); the same for any
        // other call type here. A transferred user's call to the target may arrive while it is in the call with the
        // requester (two at most).
        if (current.isNotEmpty() && !(current.size == 1 && request.headers["Replaces"] != null)) { tx.respond(486); return }
        val firstToAnswer = info.sessionType == CallType.FIRST_TO_ANSWER
        val remote = try { McpttSdp.remote(sdp) } catch (e: SdpNegotiationError) { tx.respond(488); return }
        // §11.1.6.2.1.2: an ambient listening call names one calling user, no group, no emergency, imminent peril,
        // alert or broadcast (488 otherwise), and is answered automatically (Priv-Answer-Mode Auto, else 400).
        if (ambient) PrivateServiceRules.ambientRefusal(info, config.userId, request.headers["Priv-Answer-Mode"])?.let { tx.respond(it); return }
        // §11.1.2: only a private call (a first-to-answer call included, §11.1.2.1) comes without floor control.
        if (!remote.floorControl && type != CallType.PRIVATE) { tx.respond(488); return }
        val call = Call(ids.incrementAndGet(), type, false, info.callingUserId,
            if (type == CallType.PRIVATE) null else info.callingGroupId).also {
            it.floorControl = remote.floorControl
            it.callingAlias = info.functionalAlias
            it.calledAlias = info.calledFunctionalAlias
            it.firstToAnswer = firstToAnswer
            it.broadcast = info.broadcast == true
            // §6.3.4.1.2 step 6 b): a call on a regroup names the member's constituent group.
            it.associatedGroup = info.associatedGroupId
            // The MCPTT session identity, the controlling function's Contact (isfocus), to rejoin an ad hoc call left.
            it.sessionUri = request.headers["Contact"]?.let { c -> runCatching { NameAddr.parse(c).uri }.getOrNull() }
            if (ambient) {
                it.ambientType = info.ambientListeningType
                it.ambientRole = if (info.ambientListeningType == "remote-init") "listened-to" else "listening"
                it.mayRelease = PrivateServiceRules.ambientReleaseAllowed(request.headers.values("Feature-Caps"))
            }
            // §11.1.1.2.1.1 14A) a), 14C) a): the answer mode and forwarding count of the call, for its transfer or
            // forwarding; §11.1.9.2.1 2) d) i) E): the MCPTT ID it was addressed to.
            it.answerMode = PrivateServiceRules.answerMode(request.headers["Priv-Answer-Mode"]) ?: PrivateServiceRules.answerMode(request.headers["Answer-Mode"])
                ?: if (answerMode == "manual") "Manual" else "Auto"
            it.forwardingSequence = info.forwardingSequenceNumber ?: 0
            it.requestUri = info.requestUri
            it.service = when { info.service["call-transfer-ind"] == "true" -> "transfer"; info.service["call-forwarding-ind"] == "true" -> "forwarding"
                else -> null }
        }
        // TS 33.180 7.4 with TS 24.379 §11.1.1.2.1.2: a protected private call's offer carries the caller's PCK, which
        // must authenticate as from the calling MCPTT ID, else 488 with warning 136; a protected group call needs the
        // group's GMK (7.3).
        var pck: KeyMessage? = null
        var gmk: KeyMessage? = null
        if (remote.protected) {
            val group = call.groupId
            if (type == CallType.PREARRANGED && group != null) {
                gmk = runCatching { awaitGroupKey(group) }.getOrNull()
                if (gmk == null) { tx.respond(488); event("protected call on $group without its group key"); return }
            } else if (type == CallType.PRIVATE && firstToAnswer) try {
                // §11.1.1.2.1.2 step 5: for a first-to-answer call the invitee makes the PCK, for the caller, and offers
                // it in its answer (NOTE 4: the invitee is the initiator of the I_MESSAGE).
                val keys = client.keysOf(McService.MCPTT) ?: throw CallError("no MCPTT keys")
                val made = keys.makePck(info.callingUserId ?: throw CallError("no caller"))
                pck = made.first
                call.answerKeyMgmt = made.second
            } catch (e: Exception) {
                event("first-to-answer call from ${info.callingUserId}: no PCK: ${e.message}")
                tx.respond(488); return
            } else if (type == CallType.PRIVATE) try {
                val keys = client.keysOf(McService.MCPTT) ?: throw CallError("no MCPTT keys")
                pck = keys.acceptPck(info.callingUserId ?: throw CallError("no caller"), remote.keyMgmt ?: throw CallError("protected call without a PCK"))
            } catch (e: Exception) {
                event("call from ${info.callingUserId}: PCK refused: ${e.message}")
                tx.respond(488, extra = listOf("Warning" to "399 ${ua.sentBy} \"136 authentication of the MIKEY-SAKE I_MESSAGE failed\""))
                return
            } else { tx.respond(488); event("protected ${type.sessionType} call: no key for it"); return }
        }
        call.keys = pck to gmk
        notePriority(call, info, quiet = true)
        call.incoming = tx
        call.invite = request
        _calls.value = _calls.value + call
        event("call ${call.id}: incoming ${info.sessionType}${if (call.broadcast) " broadcast" else ""} call from ${info.callingUserId}" +
            (call.groupId?.let { " on $it" } ?: "") + (call.associatedGroup?.let { " through $it" } ?: ""))
        // Commencement mode (§11.1.1.2.1.2 steps 9-10), the header values by token (RFC 5373 §2: "Auto;require" is Auto).
        // Step 8 a): a first-to-answer call is always notified to the user, who answers it (the server's Priv-Answer-Mode
        // is Manual, NOTE 5); step 8 d) the client's time out is the manual answer timeout, then 480.
        val manual = !ambient && (firstToAnswer || ClientRules.manualCommencement(request.headers["Answer-Mode"], request.headers["Priv-Answer-Mode"],
            answerMode, type == CallType.PRIVATE))
        // §11.1.6.2.1.2: the listened-to user of a remotely initiated ambient listening call is not told.
        if (!call.hidden) listener?.incoming(call)
        // §11.1.5.2.3: a call from the user this user asked to call back fulfils the request.
        if (type == CallType.PRIVATE && !ambient) info.callingUserId?.let { services.calledBy(it) }
        if (manual) {
            tx.respond(180, extra = listOf("Require" to "timer", "Contact" to client.sessionContact(McService.MCPTT)))
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
                    // §11.1.9.2.1 NOTE 1: a call forwarded by the user is declined with 480 and warning 175.
                    val (status, warning) = call.declineWith ?: (480 to "110 user declined the call invitation")
                    tx.respond(status, extra = listOf("Warning" to "399 ${ua.sentBy} \"$warning\""))
                    release(call, if (call.declineWith != null) "forwarded" else "declined"); return
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
        val floored = if (remote.floorControl) bound else bound.copy(floorControl = false, audioSsrc = McpttSdp.newFloorSsrc())
        // §6.2.1 item 2c: the listened-to user's speech sendonly, the listening user's recvonly.
        val local = floored.copy(protected = remote.protected, direction = when (call.ambientRole) {
            "listened-to" -> "sendonly"; "listening" -> "recvonly"; else -> "sendrecv" })
        // A first-to-answer invitee is the initiator of its own PCK (§11.1.1.2.1.2 step 5 NOTE 4).
        val protection = try { protection(call.keys.first, localInitiator = call.firstToAnswer, gmk = call.keys.second, call = call) }
            catch (e: CallError) { ports.close(); tx.respond(480); release(call, e.message ?: "no keys"); return }
        val media = newMedia(call, ports, local)
        media.protection = protection
        call.media = media
        val offered = tx.request.headers["Session-Expires"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: 90
        call.sessionExpires = offered
        val answer = McpttSdp.answer(remote, local, call.origin, keyMgmt = call.answerKeyMgmt)
        val response = tx.response(200, body = answer.toByteArray(), contentType = "application/sdp", extra = listOf(
            "Contact" to client.sessionContact(McService.MCPTT), "Require" to "timer", "Supported" to "timer",
            "Session-Expires" to "$offered;refresher=uas", "Allow" to ALLOW))
        call.dialog = Dialog.fromRequest(tx.request, tx.localTag)
        media.start(remote, indicator = { indicatorOf(call, local, remote) })
        // §6.4: the INVITE of a remotely initiated ambient listening call asks to grant the floor to this client; the
        // speech then goes out without the user (the microphone, kept by the foreground service, TS 23.379 §10.14.3.2).
        if (call.ambientRole == "listened-to") media.floor.grantExpected = true
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
        val answer = if (remote != null) McpttSdp.answer(remote, media.local, call.origin) else null
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
        val parts = mutableListOf(Part("application/sdp", McpttSdp.offer(media.local, call.origin).toByteArray()), Part(McpttInfo.CONTENT_TYPE, info.encode().toByteArray()))
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

    /**
     * Leave or release the call (§6.2.4.1, §10.1.2.2.3.1 for a chat call, §17.2.3.1.1). The originator of an ad hoc
     * call releases it for all ([releaseAll], the BYE's Reason "User requested release"), or only leaves it (§17.2.4.1.1,
     * a BYE without it); a left ad hoc call can be rejoined ([rejoinAdhoc]).
     */
    suspend fun hangup(call: Call? = active, releaseAll: Boolean = true) {
        call ?: throw CallError("no call")
        // §11.1.6.2.1.3 step 1: an ambient listening call is released only with the release capability (annex D.3).
        if (call.ambientType != null && !call.mayRelease && call.dialog != null) throw CallError("this ambient listening call may not be released here")
        val dialog = call.dialog
        if (call.type == CallType.ADHOC && dialog != null) lastAdhoc = call
        when {
            dialog != null -> {
                call.media?.let { m -> if (m.floor.state.value == FloorState.HAS_PERMISSION) m.floor.release() }
                val bye = ua.inDialog(dialog, "BYE")
                if (call.type == CallType.ADHOC && call.outgoing && releaseAll) bye.headers.add("Reason", "SIP;cause=200;text=\"User requested release\"")
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

    /** The user logs off: every call is left (BYE, or CANCEL while calling), as by hang-up. */
    override suspend fun logOff() {
        _calls.value.forEach { call -> runCatching { hangup(call) }.onFailure { release(call, "logged off") } }
        endGroupKeySubscriptions()
    }

    companion object {
        /** How long a protected group call waits for the group's GMK before giving up (as MCVideo). */
        const val GROUP_KEY_WAIT_MS = 10_000L
        const val ALLOW = "INVITE, ACK, CANCEL, BYE, UPDATE, INFO, MESSAGE, NOTIFY, OPTIONS"
    }
}
