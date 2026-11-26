//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ProcedureResult
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A call-back request this user received (TS 24.379 §11.1.5.2.2): "PCCB-R2: private-call-pending" until fulfilled. */
data class CallBackRequest(val from: String, val urgency: String, val time: String?, val at: Long = System.currentTimeMillis())

/** A private call service request this client carried out, or one it asked of another user, and how it went. */
data class ServiceRecord(val type: String, val peer: String, val target: String?, val outcome: String? = null,
                         val notify: Boolean = true, val at: Long = System.currentTimeMillis())

data class PrivateServicesState(
    /** Call-back requests sent, by the called MCPTT ID: "PCCB-I2: confirm-pending", "PCCB-I3: confirmed", "PCCB-I4: cancel-pending". */
    val callBacksSent: Map<String, String> = emptyMap(),
    /** Call-back requests received, by the requesting MCPTT ID. */
    val callBacksReceived: Map<String, CallBackRequest> = emptyMap(),
    /** The requests received and carried out (remote, transfer, forwarding), newest last. */
    val handled: List<ServiceRecord> = emptyList(),
    /** The outcomes reported for this user's own requests, newest last. */
    val outcomes: List<ServiceRecord> = emptyList(),
    /** The last request sent: its result at the server (2xx, or the refusal with its warning). */
    val lastRequest: ProcedureResult? = null,
)

/** The rules of the private call services, without I/O (TS 24.379 §10.1.5, §11.1.5 to §11.1.9; annex F.1). */
object PrivateServiceRules {
    const val CALL_BACK = "private-call-call-back-request"
    const val CALL_BACK_CANCEL = "private-call-call-back-cancel-request"
    const val REMOTE_PRIVATE = "remotely-initiated-private-call-request"
    const val REMOTE_GROUP = "remotely-initiated-group-call-request"
    const val TRANSFER = "transfer-private-call-request"
    const val FORWARD = "forward-private-call-request"
    val REQUESTS = setOf(CALL_BACK, CALL_BACK_CANCEL, REMOTE_PRIVATE, REMOTE_GROUP, TRANSFER, FORWARD)
    val RESPONSES = setOf("private-call-call-back-response", "private-call-call-back-cancel-response",
        "remotely-initiated-private-call-response", "remotely-initiated-group-call-response", "transfer-private-call-response",
        "forward-private-call-response")
    /** Annex D.3: the release capability of an ambient listening call, in Feature-Caps. */
    const val AMBIENT_RELEASE = "g.3gpp.mcptt.ambient-listening-call-release"

    /** The request or response a MESSAGE's mcptt-info carries, null for any other MESSAGE. */
    fun typeOf(info: McpttInfo): String? = info.requestType?.takeIf { it in REQUESTS } ?: info.service["response-type"]?.takeIf { it in RESPONSES }

    /**
     * §11.1.5.2.1 a) iii): <time-of-request> in the form of annex F.1.3, "YYYY-MM-DDThh:mm:ss", without a time zone
     * designator; UTC, as the Linux client (annex F.1.3 names no time scale).
     */
    fun timeOfRequest(now: Instant = Instant.now()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(now)

    /** §11.1.5.2.1: <urgency-ind> is low, normal or high. */
    fun urgency(value: String?): String = (value ?: "normal").also { require(it in setOf("low", "normal", "high")) { "urgency is low, normal or high" } }

    /** §11.1.7.2.1: the <ric-commencement-mode> of a commencement mode. */
    fun ricMode(commencement: Commencement?): String? = when (commencement) {
        Commencement.FORCED -> "force-auto-mode"; Commencement.MANUAL -> "manual-mode"; Commencement.AUTOMATIC -> "auto-mode"; null -> null
    }

    /** §11.1.7.2.2 3) c): the commencement mode of the call a <ric-commencement-mode> asks for (automatic without one). */
    fun commencement(ricMode: String?): Commencement = when (ricMode) {
        "force-auto-mode" -> Commencement.FORCED; "manual-mode" -> Commencement.MANUAL; else -> Commencement.AUTOMATIC
    }

    /** RFC 5373 §2: the answer-mode-value of an Answer-Mode or Priv-Answer-Mode header, as "Auto" or "Manual". */
    fun answerMode(header: String?): String? = when (header?.substringBefore(';')?.trim()?.lowercase()) {
        "auto" -> "Auto"; "manual" -> "Manual"; else -> null
    }

    /** §11.1.8.2.2, §11.1.9.2.2: a 480 with warning 175 (call is forwarded) or 191 (forwarding due to migration) is a success. */
    fun forwarded(status: Int, warning: String?): Boolean =
        status == 480 && warning != null && Regex("""\b(175|191)\b""").containsMatchIn(warning)

    /** Annex D.3: whether the Feature-Caps values give the ambient listening call's release capability. */
    fun ambientReleaseAllowed(featureCaps: List<String>): Boolean = featureCaps.any { AMBIENT_RELEASE in it }

    /**
     * §11.1.6.2.1.2: the refusal of an incoming ambient listening call: 488 when it names no calling user, a group, a
     * type other than remote-init or local-init, emergency, imminent peril, alert or broadcast, or is addressed to
     * another user; 400 without Priv-Answer-Mode Auto. Null: accept it.
     */
    fun ambientRefusal(info: McpttInfo, userId: String, privAnswerMode: String?): Int? = when {
        info.callingUserId.isNullOrEmpty() || info.callingUserId == userId || !info.callingGroupId.isNullOrEmpty() -> 488
        info.requestUri != null && info.requestUri != userId -> 488
        info.ambientListeningType != "remote-init" && info.ambientListeningType != "local-init" -> 488
        info.emergency == true || info.imminentPeril == true || info.alert == true || info.broadcast == true -> 488
        answerMode(privAnswerMode) != "Auto" -> 400
        else -> null
    }

    /**
     * The mcptt-info of the report a client sends once it has placed the call a request asked for (§10.1.5.2.2 step
     * 1 e), §11.1.7.2.2, §11.1.8.2.2, §11.1.9.2.2): the response type and the outcome, <mcptt-called-party-id> the user
     * called (<mcptt-request-uri> the group, for a group call).
     */
    fun outcomeReport(request: String, target: String, success: Boolean): McpttInfo {
        val outcome = if (success) "success" else "fail"
        return when (request) {
            REMOTE_GROUP -> McpttInfo(requestUri = target, service = mapOf("response-type" to "remotely-initiated-group-call-response",
                "remotely-initiated-call-outcome" to outcome))
            REMOTE_PRIVATE -> McpttInfo(calledPartyId = target, service = mapOf("response-type" to "remotely-initiated-private-call-response",
                "remotely-initiated-call-outcome" to outcome))
            TRANSFER -> McpttInfo(calledPartyId = target, service = mapOf("response-type" to "transfer-private-call-response",
                "transfer-call-outcome" to outcome))
            FORWARD -> McpttInfo(calledPartyId = target, service = mapOf("response-type" to "forward-private-call-response",
                "forwarding-call-outcome" to outcome))
            else -> throw IllegalArgumentException("no outcome report for $request")
        }
    }

    /** The outcome a response reports, if any. */
    fun outcomeOf(info: McpttInfo): String? = info.service["remotely-initiated-call-outcome"] ?: info.service["transfer-call-outcome"]
        ?: info.service["forwarding-call-outcome"]

    /**
     * §11.1.9.2.1 2) d) i): the forwarding request of a ringing private call: <request-type>, B) the target as a
     * functional alias ([toAlias]), C) <forwarding-reason> "manual-input", D) the functional alias the call was made to,
     * E) <forwarded-by-mcptt-id> the MCPTT ID it was addressed to, F) <forwarding-target-orig-id> the target.
     */
    fun forwardRequest(target: String, forwardedBy: String, calledAlias: String?, toAlias: Boolean): McpttInfo = McpttInfo(
        requestType = FORWARD, callToFunctionalAlias = if (toAlias) true else null,
        service = buildMap {
            put("forwarding-reason", "manual-input"); put("forwarded-by-mcptt-id", forwardedBy)
            put("forwarding-target-orig-id", target); calledAlias?.let { put("forwarded-by-functional-alias", it) }
        })

    /**
     * §11.1.1.2.1.1 14A) to 14C): what the call a request asks for carries: a transfer <call-transfer-ind> "true"
     * (14A)) with the Priv-Answer-Mode of the call transferred; a forwarding <call-forwarding-ind> "true" with the
     * forwarded call's <forwarding-sequence-number> incremented (14C) a1)) and its Priv-Answer-Mode; a remotely
     * initiated call <remotely-initiated-call-request-ind> (14A) D)) and the Resource-Priority of
     * <ric-app-level-priority> (§11.1.7.2.2 3) b)).
     */
    fun followUp(request: String, info: McpttInfo, answerMode: String?, sequence: Int, alongside: Boolean): ServiceCall = when (request) {
        TRANSFER -> ServiceCall("transfer", mapOf("call-transfer-ind" to "true"), privAnswerMode = answerMode, alongside = alongside)
        FORWARD -> ServiceCall("forwarding", mapOf("call-forwarding-ind" to "true"), privAnswerMode = answerMode, forwardingSequence = sequence + 1)
        REMOTE_PRIVATE -> ServiceCall("remote", resourcePriority = info.service["ric-app-level-priority"], remoteRequest = true)
        REMOTE_GROUP -> ServiceCall("remote", resourcePriority = info.service["ric-app-level-priority"])
        else -> throw IllegalArgumentException("no call for $request")
    }
}

/**
 * The MCPTT client's private call services (TS 24.379, as the Linux client's mcptt/private_services.cpp): each request
 * or response is a SIP MESSAGE to the participating function's PSI with the client ID in mcptt-info and the other user
 * in a resource list; the server passes it on to that user.
 *  - call-back (§11.1.5): request, cancel, and the target's fulfilment by a call with manual commencement (§11.1.5.2.3);
 *  - remotely initiated private (§11.1.7) and group (§10.1.5) calls: the remote user's client places the call and
 *    reports the outcome;
 *  - transfer (§11.1.8, unannounced): the transferred user calls the target and reports; on success the requester
 *    ends its call with the transferred user;
 *  - forwarding (§11.1.9): of a ringing call by the user (manual input), and by the server (immediate, no answer, not
 *    reachable, §6.3.2.6): the caller places the call to the forwarding target and reports.
 */
class PrivateServices(private val client: McxClient, private val calls: McpttService) {
    private val config = client.config.mcptt!!
    private val _state = MutableStateFlow(PrivateServicesState())
    val state: StateFlow<PrivateServicesState> = _state
    /** Told of a request received with <notify-remote-user> "true", and of the outcomes of this user's requests. */
    var listener: ((ServiceRecord) -> Unit)? = null

    private fun event(text: String) = client.event("mcptt services: $text")

    private fun requireRight(vararg rights: String) {
        val profile = client.profile ?: return
        if (rights.none { profile.allows(it) }) throw CallError("the user profile does not allow it (${rights.first()})")
    }

    /** The MESSAGE to [target] through the PSI (§11.1.5.2.1 and the others): ICSI, Accept-Contact, the info and the resource list. */
    private suspend fun send(target: String, info: McpttInfo, what: String): ProcedureResult {
        if (!client.status.value.mcptt.authorised) throw CallError("MCPTT not authorised")
        val ua = client.ua
        val request = ua.newRequest("MESSAGE", config.psi, NameAddr(config.psi))
        request.headers.add("P-Preferred-Service", Icsi.MCPTT).add("Accept-Contact", "*;+g.3gpp.mcptt;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)}\";require;explicit")
        val (contentType, body) = Multipart.encode(listOf(
            Part(McpttInfo.CONTENT_TYPE, info.copy(clientId = client.config.clientId).encode().toByteArray()),
            Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray())))
        request.headers.add("Content-Type", contentType)
        val response = runCatching { ua.send(SipRequest(request.method, request.uri, request.headers, body)) }.getOrNull()
        val result = ProcedureResult("$what $target", response?.status ?: 0, response?.headers?.get("Warning"))
        _state.update { it.copy(lastRequest = result) }
        event("${result.action}: ${result.status}${result.warning?.let { " ($it)" } ?: ""}")
        return result
    }

    private fun failed(result: ProcedureResult) =
        CallError("${result.action}: ${result.status}${result.warning?.let { " ($it)" } ?: ""}", result.status)

    // Requests of this user.

    /** §11.1.5.2.1: ask [target] to call back, with an [urgency] (low, normal, high) and the time of the request. */
    suspend fun requestCallBack(target: String, urgency: String = "normal"): ProcedureResult {
        requireRight("allow-request-private-call-call-back")
        val info = McpttInfo(requestType = PrivateServiceRules.CALL_BACK, service = mapOf(
            "urgency-ind" to PrivateServiceRules.urgency(urgency), "time-of-request" to PrivateServiceRules.timeOfRequest()))
        _state.update { it.copy(callBacksSent = it.callBacksSent + (target to "PCCB-I2: confirm-pending")) }
        val result = send(target, info, "call-back request to")
        if (result.status !in 200..299) { _state.update { it.copy(callBacksSent = it.callBacksSent - target) }; throw failed(result) }
        return result
    }

    /** §11.1.5.2.1 (cancel): withdraw the call-back request sent to [target]. */
    suspend fun cancelCallBack(target: String): ProcedureResult {
        requireRight("allow-cancel-private-call-call-back")
        if (target !in _state.value.callBacksSent) throw CallError("no call-back request to $target")
        _state.update { it.copy(callBacksSent = it.callBacksSent + (target to "PCCB-I4: cancel-pending")) }
        val result = send(target, McpttInfo(requestType = PrivateServiceRules.CALL_BACK_CANCEL), "call-back cancel to")
        if (result.status !in 200..299) { _state.update { it.copy(callBacksSent = it.callBacksSent - target) }; throw failed(result) }
        return result
    }

    /** §11.1.5.2.3: call back the user of a received request, with manual commencement; the request is then fulfilled. */
    suspend fun fulfilCallBack(from: String): Call {
        if (from !in _state.value.callBacksReceived) throw CallError("no call-back request from $from")
        val call = calls.serviceCall(from, Commencement.MANUAL, ServiceCall("call-back"))
        _state.update { it.copy(callBacksReceived = it.callBacksReceived - from) }
        event("call-back request of $from fulfilled")
        return call
    }

    /** §11.1.5.2.3: a call from a user this user asked to call back fulfils the request. */
    fun calledBy(user: String) {
        if (_state.value.callBacksSent[user] == "PCCB-I3: confirmed") {
            _state.update { it.copy(callBacksSent = it.callBacksSent - user) }
            event("call-back request to $user fulfilled")
        }
    }

    /**
     * §11.1.7.2.1: ask [remote]'s client to call [target] privately, with a commencement mode (none: its default),
     * [notify] (<notify-remote-user>) and an application priority ("namespace.value", <ric-app-level-priority>).
     */
    suspend fun remotePrivateCall(remote: String, target: String, commencement: Commencement? = null, notify: Boolean = true,
                                  priority: String? = null): ProcedureResult {
        requireRight("allow-request-remote-init-private-call")
        val info = McpttInfo(requestType = PrivateServiceRules.REMOTE_PRIVATE, calledPartyId = target, service = buildMap {
            put("notify-remote-user", notify.toString())
            priority?.let { put("ric-app-level-priority", it) }
            PrivateServiceRules.ricMode(commencement)?.let { put("ric-commencement-mode", it) }
        })
        return send(remote, info, "remotely initiated private call request to").also { if (it.status !in 200..299) throw failed(it) }
    }

    /**
     * §10.1.5.2.1: ask [remote]'s client to call [group]; step 1A: not on a group whose document says
     * <preconfigured-group-use-only>.
     */
    suspend fun remoteGroupCall(remote: String, group: String, notify: Boolean = true, priority: String? = null): ProcedureResult {
        requireRight("allow-request-remote-init-group-call")
        if (client.groupDocuments[group]?.preconfiguredUseOnly == true) throw CallError("calls are not allowed on the preconfigured group $group")
        val info = McpttInfo(requestType = PrivateServiceRules.REMOTE_GROUP, requestUri = group, service = buildMap {
            put("notify-remote-user", notify.toString()); priority?.let { put("ric-app-level-priority", it) }
        })
        return send(remote, info, "remotely initiated group call request to").also { if (it.status !in 200..299) throw failed(it) }
    }

    /**
     * §11.1.8.2.1: transfer the established private call to [target] (an MCPTT ID, or a functional alias with
     * [toAlias]): the request goes to the other party of the call, which calls the target; on its success report this
     * user's call ends (§11.1.8.2.1, on the response).
     */
    suspend fun transfer(target: String, toAlias: Boolean = false): ProcedureResult {
        requireRight("allow-call-transfer")
        val call = calls.active?.takeIf { it.type == CallType.PRIVATE && it.state == "established" && it.ambientType == null }
            ?: throw CallError("a transfer needs an established private call")
        val peer = call.peer ?: throw CallError("the call has no other party")
        if (target == peer || target == config.userId) throw CallError("the transfer target is a third user")
        val info = McpttInfo(requestType = PrivateServiceRules.TRANSFER, calledPartyId = target, callToFunctionalAlias = toAlias)
        return send(peer, info, "transfer request to").also { if (it.status !in 200..299) throw failed(it) }
    }

    /**
     * §11.1.9.2.1: forward the ringing incoming private call to [target] (manual input; NOTE 1: manual commencement
     * only). Step 1: <allow-call-fwd-manual-any> (the OpenMCX servers name it <allow-call-forward-manual-input>);
     * the request goes to the caller; once the server accepts it the call is declined with 480 and warning 175.
     */
    suspend fun forward(target: String, toAlias: Boolean = false): ProcedureResult {
        requireRight("allow-call-fwd-manual-any", "allow-call-forward-manual-input")
        val call = calls.calls.value.firstOrNull { it.state == "ringing" && !it.outgoing && it.type == CallType.PRIVATE && !it.firstToAnswer }
            ?: throw CallError("forwarding needs a ringing incoming private call")
        val caller = call.peer ?: throw CallError("the call has no caller")
        val info = PrivateServiceRules.forwardRequest(target, call.requestUri ?: config.userId, call.calledAlias, toAlias)
        val result = send(caller, info, "forwarding request to")
        if (result.status !in 200..299) throw failed(result)
        call.declineWith = 480 to "175 call is forwarded"
        call.answered.complete(false)
        return result
    }

    // Requests and responses from other users, passed on by the server.

    /** A MESSAGE with a private call service request or response; false for any other MESSAGE. */
    suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        val part = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE } ?: return false
        val info = runCatching { McpttInfo.parse(part.text) }.getOrNull() ?: return false
        val type = PrivateServiceRules.typeOf(info) ?: return false
        // The server passes the requests on (§11.1.5.3.2 and the others): from the PSI, for this user, naming the sender.
        val from = request.headers["From"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
        if ((from != null && from != config.psi) || (info.requestUri != null && info.requestUri != config.userId)) {
            tx.respond(403); event("$type from $from refused"); return true
        }
        val sender = info.callingUserId
        if (sender.isNullOrEmpty()) { tx.respond(403); event("$type without a calling user refused"); return true }
        tx.respond(200)
        client.scope.launch { runCatching { received(type, sender, info) }.onFailure { event("$type from $sender: ${it.message}") } }
        return true
    }

    private suspend fun received(type: String, from: String, info: McpttInfo) {
        when (type) {
            // §11.1.5.2.2: store the request ("PCCB-R2: private-call-pending") and confirm it; a cancel removes it.
            PrivateServiceRules.CALL_BACK -> {
                val request = CallBackRequest(from, info.service["urgency-ind"] ?: "normal", info.service["time-of-request"])
                _state.update { it.copy(callBacksReceived = it.callBacksReceived + (from to request)) }
                event("call-back request from $from (${request.urgency})")
                listener?.invoke(ServiceRecord(type, from, null))
                send(from, McpttInfo(service = mapOf("response-type" to "private-call-call-back-response")), "call-back response to")
            }
            PrivateServiceRules.CALL_BACK_CANCEL -> {
                _state.update { it.copy(callBacksReceived = it.callBacksReceived - from) }
                event("call-back request from $from cancelled")
                listener?.invoke(ServiceRecord(type, from, null))
                send(from, McpttInfo(service = mapOf("response-type" to "private-call-call-back-cancel-response")), "call-back cancel response to")
            }
            // §11.1.5.2.1: the target's confirmations.
            "private-call-call-back-response" -> _state.update {
                if (it.callBacksSent[from] == "PCCB-I2: confirm-pending") it.copy(callBacksSent = it.callBacksSent + (from to "PCCB-I3: confirmed")) else it
            }
            "private-call-call-back-cancel-response" -> _state.update {
                if (it.callBacksSent[from] == "PCCB-I4: cancel-pending") it.copy(callBacksSent = it.callBacksSent - from) else it
            }
            PrivateServiceRules.REMOTE_GROUP -> remoteGroup(from, info)
            PrivateServiceRules.REMOTE_PRIVATE, PrivateServiceRules.TRANSFER, PrivateServiceRules.FORWARD -> placeFollowUp(type, from, info)
            else -> outcome(type, from, info)
        }
    }

    /** §10.1.5.2.2: call the group of the request (the group is in <mcptt-calling-group-id>) and report the outcome. */
    private suspend fun remoteGroup(from: String, info: McpttInfo) {
        val group = info.callingGroupId ?: info.requestUri ?: return
        val notify = info.service["notify-remote-user"] != "false"
        event("remotely initiated group call request from $from for $group")
        record(ServiceRecord(PrivateServiceRules.REMOTE_GROUP, from, group, notify = notify), notify)
        val extra = PrivateServiceRules.followUp(PrivateServiceRules.REMOTE_GROUP, info, null, 0, false)
        val affiliated = client.status.value.mcptt.affiliations[group] == "affiliated"
        val success = if (calls.active != null || !affiliated) false else {
            client.scope.launch { runCatching { calls.serviceGroupCall(group, extra) }.onFailure { extra.outcome.complete(false) } }
            withTimeoutOrNull(OUTCOME_WAIT_MS) { extra.outcome.await() } ?: false
        }
        report(PrivateServiceRules.REMOTE_GROUP, from, group, success)
    }

    /**
     * §11.1.7.2.2 (remotely initiated), §11.1.8.2.2 (transfer) and §11.1.9.2.2 (forwarding): place the call the request
     * asks for and report its outcome; the requester of a remotely initiated call that is itself called gets no report
     * (§11.1.7.2.2 NOTE). A transferred user keeps its call with the requester meanwhile (§11.1.8.2.2).
     */
    private suspend fun placeFollowUp(type: String, from: String, info: McpttInfo) {
        val forward = type == PrivateServiceRules.FORWARD
        val target = (if (forward) info.service["forwarding-target-id"] else info.calledPartyId) ?: return
        val requester = if (forward) info.service["forwarded-by-mcptt-id"] ?: from else from
        val notify = info.service["notify-remote-user"] != "false"
        event("$type from $from: call $target")
        record(ServiceRecord(type, from, target, notify = notify), notify)
        // The call the request follows: the transferred call (ours with the requester), or our call to the forwarding user.
        val followed = if (forward) {
            // The 480 of the call forwarded may come after the request (ms apart): that call is then still current, and
            // in the history only once it has ended. Let it end first, then take it (its answer mode and sequence number).
            val forwarded = calls.active?.takeIf { it.outgoing && it.type == CallType.PRIVATE && it.peer == requester }
            withTimeoutOrNull(3_000) { while (calls.active != null) delay(100) }
            forwarded ?: calls.history.lastOrNull { it.outgoing && it.type == CallType.PRIVATE && it.peer == requester }
        } else calls.active?.takeIf { it.peer == from && it.type == CallType.PRIVATE }
        val extra = PrivateServiceRules.followUp(type, info, followed?.answerMode, followed?.forwardingSequence ?: 0,
            alongside = type == PrivateServiceRules.TRANSFER && followed != null)
        val commencement = if (type == PrivateServiceRules.REMOTE_PRIVATE) PrivateServiceRules.commencement(info.service["ric-commencement-mode"])
            else Commencement.AUTOMATIC
        val success = if (calls.active != null && !extra.alongside) false else {
            client.scope.launch { runCatching { calls.serviceCall(target, commencement, extra) }.onFailure { extra.outcome.complete(false) } }
            withTimeoutOrNull(OUTCOME_WAIT_MS) { extra.outcome.await() } ?: false
        }
        if (type == PrivateServiceRules.REMOTE_PRIVATE && success && target == from) return
        report(type, requester, target, success)
    }

    private suspend fun report(type: String, to: String, target: String, success: Boolean) {
        event("$type for $target: ${if (success) "success" else "fail"}, reported to $to")
        val outcome = if (success) "success" else "fail"
        _state.update { s ->
            val i = s.handled.indexOfLast { it.type == type && it.target == target && it.outcome == null }
            s.copy(handled = if (i < 0) s.handled else s.handled.toMutableList().also { it[i] = it[i].copy(outcome = outcome) })
        }
        send(to, PrivateServiceRules.outcomeReport(type, target, success), "$type outcome to")
    }

    private fun record(r: ServiceRecord, notify: Boolean) {
        _state.update { it.copy(handled = (it.handled + r).takeLast(20)) }
        // §11.1.7.2.2 1), 3) a): told only with <notify-remote-user> "true".
        if (notify) listener?.invoke(r)
    }

    /** The outcome of this user's own request (§10.1.5.2.1, §11.1.7.2.1, §11.1.8.2.1, §11.1.9.2.1 on the response). */
    private suspend fun outcome(type: String, from: String, info: McpttInfo) {
        val outcome = PrivateServiceRules.outcomeOf(info)
        val r = ServiceRecord(type, from, info.calledPartyId ?: info.callingGroupId, outcome)
        _state.update { it.copy(outcomes = (it.outcomes + r).takeLast(20)) }
        event("$type from $from: $outcome")
        listener?.invoke(r)
        // §11.1.8.2.1: a successful transfer ends the requester's call with the transferred user.
        if (type == "transfer-private-call-response" && outcome == "success") {
            calls.current.firstOrNull { it.type == CallType.PRIVATE && it.peer == from && it.ambientType == null }
                ?.let { runCatching { calls.hangup(it) } }
        }
    }

    companion object {
        /** How long the outcome of a requested call is awaited before it is reported a failure. */
        const val OUTCOME_WAIT_MS = 30_000L
    }
}
