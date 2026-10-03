package org.openmcx.client.sip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Who this UA is, and how it presents itself (RFC 3261, RFC 5626). */
data class UaIdentity(
    /** The IMS public user identity, `sip:alice@mcx.test`. */
    val publicUserId: String,
    val privateUserId: String,
    val password: String,
    val domain: String,
    /** `urn:uuid:…`, the +sip.instance of this UE (RFC 5626 §4.1). */
    val instanceId: String,
    val userAgent: String = "OpenMCX-Android/0.1",
)

/** An incoming request and the means to answer it once (RFC 3261 §17.2, reliable transport). */
class ServerTransaction internal constructor(private val ua: UserAgent, val request: SipRequest) {
    @Volatile var finalSent = false
        private set
    /** Completes when a CANCEL arrives for this INVITE. */
    val cancelled = CompletableDeferred<Unit>()
    /** The To tag this UA uses in its responses to this request. */
    val localTag: String = request.to.tag ?: ua.newTag()

    fun response(status: Int, reason: String = reasonPhrase(status), body: ByteArray = ByteArray(0),
                  contentType: String? = null, extra: List<Pair<String, String>> = emptyList()): SipResponse {
        val headers = Headers()
        request.headers.all("Via").forEach { headers.add("Via", it) }
        // A response that can establish a dialog carries the Record-Route set (RFC 3261 §12.1.1).
        if (status in 101..299) request.headers.all("Record-Route").forEach { headers.add("Record-Route", it) }
        headers.add("From", request.headers["From"]!!)
        val to = request.to
        headers.add("To", if (status == 100 || to.tag != null) request.headers["To"]!! else to.withTag(localTag).toString())
        headers.add("Call-ID", request.callId)
        headers.add("CSeq", request.headers["CSeq"]!!)
        extra.forEach { (k, v) -> headers.add(k, v) }
        if (contentType != null) headers.add("Content-Type", contentType)
        return SipResponse(status, reason, headers, body)
    }

    suspend fun respond(status: Int, reason: String = reasonPhrase(status), body: ByteArray = ByteArray(0),
                        contentType: String? = null, extra: List<Pair<String, String>> = emptyList()): SipResponse {
        val response = response(status, reason, body, contentType, extra)
        send(response)
        return response
    }

    suspend fun send(response: SipResponse) {
        if (finalSent) return
        if (response.isFinal) finalSent = true
        ua.sendResponse(this, response)
    }
}

/** A dialog (RFC 3261 §12), from either side. */
class Dialog(
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val localUri: NameAddr,
    val remoteUri: NameAddr,
    @Volatile var remoteTarget: String,
    val routeSet: List<String>,
    localSeq: Long,
) {
    private val seq = AtomicLong(localSeq)
    @Volatile var remoteSeq: Long = -1
    val id: String get() = "$callId;$localTag;$remoteTag"
    fun nextSeq(): Long = seq.incrementAndGet()

    companion object {
        /** The UAC side, from a 2xx (or 1xx with a tag) to our INVITE (§12.1.2). */
        fun fromResponse(request: SipRequest, response: SipResponse): Dialog = Dialog(
            callId = request.callId,
            localTag = request.from.tag!!,
            remoteTag = response.to.tag ?: throw SipParseError("response without To tag"),
            localUri = request.from,
            remoteUri = response.to,
            remoteTarget = response.headers["Contact"]?.let { NameAddr.parse(it).uri } ?: request.uri,
            routeSet = response.headers.values("Record-Route").reversed(),
            localSeq = request.cseq.number,
        )

        /** The UAS side, from a request we answered with [localTag] (§12.1.1). */
        fun fromRequest(request: SipRequest, localTag: String): Dialog = Dialog(
            callId = request.callId,
            localTag = localTag,
            remoteTag = request.from.tag ?: throw SipParseError("request without From tag"),
            localUri = request.to.withTag(localTag),
            remoteUri = request.from,
            remoteTarget = request.headers["Contact"]?.let { NameAddr.parse(it).uri } ?: throw SipParseError("no Contact"),
            routeSet = request.headers.values("Record-Route"),
            localSeq = SecureRandom().nextInt(1 shl 16).toLong(),
        ).also { it.remoteSeq = request.cseq.number }
    }
}

class SipTimeout(message: String) : Exception(message)

/**
 * The SIP user agent over one outbound flow: client and server transactions (reliable
 * transport, so no retransmission timers), request construction, digest authentication
 * and dialogs.
 */
class UserAgent(
    private val scope: CoroutineScope,
    val identity: UaIdentity,
    private val transactionTimeoutMs: Long = 32_000,
) {
    private val random = SecureRandom()
    private val clients = ConcurrentHashMap<String, ClientTx>()
    private val servers = ConcurrentHashMap<String, ServerTransaction>()
    private val digest = DigestAuth(identity.privateUserId, identity.password)
    private var challenges = mutableMapOf<String, DigestChallenge>()

    @Volatile var flow: SipFlow? = null
    /** Handles new incoming requests. The handler must answer the transaction. */
    var onRequest: suspend (ServerTransaction) -> Unit = { it.respond(501, "Not Implemented") }
    /** Receives ACKs for our 2xx answers, keyed by dialog. */
    var onAck: suspend (SipRequest) -> Unit = {}

    private class ClientTx(val request: SipRequest, val provisional: suspend (SipResponse) -> Unit) {
        val final = CompletableDeferred<SipResponse>()
    }

    fun newTag(): String = hex(6)
    fun newCallId(): String = "${hex(12)}@$sentBy"
    fun newBranch(): String = "z9hG4bK" + hex(10)
    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * The host this UA names in Via and Contact: a random `.invalid` name (RFC 5626 §5).
     * Requests reach us over the flow, never by this name; the SIP core aliases the
     * Contact to the flow only when the Contact differs from the flow's source address.
     */
    val sentBy: String = "${hex(5)}.invalid"

    /** Our Contact URI, with `ob` (RFC 5626 §5.4). */
    fun contactUri(): String {
        val c = flow?.connection ?: throw IllegalStateException("no flow")
        val user = SipUri.parse(identity.publicUserId).user
        return "sip:$user@$sentBy;transport=${c.transport.lowercase()};ob"
    }

    /** A new out-of-dialog request, routed through the outbound proxy. */
    fun newRequest(method: String, uri: String, to: NameAddr, from: NameAddr = NameAddr(identity.publicUserId),
                   callId: String = newCallId(), cseq: Long = 1): SipRequest {
        val headers = Headers()
            .add("Max-Forwards", "70")
            .add("From", (if (from.tag == null) from.withTag(newTag()) else from).toString())
            .add("To", to.toString())
            .add("Call-ID", callId)
            .add("CSeq", "$cseq $method")
            .add("User-Agent", identity.userAgent)
        return SipRequest(method, uri, headers)
    }

    /** A request inside [dialog] (§12.2.1.1). */
    fun inDialog(dialog: Dialog, method: String, cseq: Long = if (method == "ACK" || method == "CANCEL") 0 else dialog.nextSeq()): SipRequest {
        val headers = Headers()
        dialog.routeSet.forEach { headers.add("Route", it) }
        headers.add("Max-Forwards", "70")
            .add("From", dialog.localUri.withTag(dialog.localTag).toString())
            .add("To", dialog.remoteUri.withTag(dialog.remoteTag).toString())
            .add("Call-ID", dialog.callId)
            .add("CSeq", "$cseq $method")
            .add("User-Agent", identity.userAgent)
        return SipRequest(method, dialog.remoteTarget, headers)
    }

    /**
     * Send [request] in a client transaction and return its final response. A 401 or 407
     * is answered once with credentials (RFC 3261 §22.2), in a new transaction.
     */
    suspend fun send(request: SipRequest, dialog: Dialog? = null, provisional: suspend (SipResponse) -> Unit = {}): SipResponse {
        applyCredentials(request)
        var response = transact(request, provisional)
        if (response.status == 401 || response.status == 407) {
            val header = if (response.status == 401) "WWW-Authenticate" else "Proxy-Authenticate"
            val challenge = response.headers.all(header).map(DigestChallenge::parse).firstOrNull() ?: return response
            challenges[header] = challenge
            val retry = SipRequest(request.method, request.uri, request.headers.copy(), request.body)
            val cseq = request.cseq
            retry.headers.set("CSeq", "${dialog?.nextSeq() ?: (cseq.number + 1)} ${cseq.method}")
            applyCredentials(retry)
            response = transact(retry, provisional)
            lastSent[request.callId] = retry
        } else {
            lastSent[request.callId] = request
        }
        return response
    }

    /** The request last sent for a Call-ID after authentication, so its CSeq can be followed. */
    val lastSent = ConcurrentHashMap<String, SipRequest>()

    private fun applyCredentials(request: SipRequest) {
        if (request.method == "ACK" || request.method == "CANCEL") return
        for ((header, challenge) in challenges) {
            val target = if (header == "WWW-Authenticate") "Authorization" else "Proxy-Authorization"
            request.headers.set(target, digest.authorization(challenge, request.method, request.uri))
        }
    }

    private suspend fun transact(request: SipRequest, provisional: suspend (SipResponse) -> Unit): SipResponse {
        val flow = flow ?: throw IllegalStateException("no flow")
        val branch = newBranch()
        val c = flow.connection
        request.headers.remove("Via")
        request.headers.prepend("Via", "SIP/2.0/${c.transport} $sentBy;branch=$branch;rport")
        if (request.method == "ACK") { flow.send(request); return SipResponse(200, "OK") }
        val tx = ClientTx(request, provisional)
        val key = "$branch|${request.cseq.method}"
        clients[key] = tx
        try {
            flow.send(request)
            return withTimeout(transactionTimeoutMs) { tx.final.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw SipTimeout("${request.method} ${request.uri}: no final response")
        } finally {
            clients.remove(key)
        }
    }

    /** CANCEL a pending INVITE (§9.1): same branch, Route and Request-URI. */
    suspend fun cancel(invite: SipRequest) {
        val flow = flow ?: return
        val headers = Headers()
        headers.add("Via", invite.headers.values("Via").first())
        invite.headers.all("Route").forEach { headers.add("Route", it) }
        headers.add("Max-Forwards", "70").add("From", invite.headers["From"]!!).add("To", invite.headers["To"]!!)
            .add("Call-ID", invite.callId).add("CSeq", "${invite.cseq.number} CANCEL")
        val cancel = SipRequest("CANCEL", invite.uri, headers)
        val key = "${invite.topVia.branch}|CANCEL"
        val tx = ClientTx(cancel) {}
        clients[key] = tx
        try {
            flow.send(cancel)
            withTimeout(transactionTimeoutMs) { tx.final.await() }
        } catch (_: Exception) {
        } finally {
            clients.remove(key)
        }
    }

    /** ACK a 2xx to INVITE (§13.2.2.4): end to end, in the dialog, with the INVITE's CSeq number. */
    suspend fun ack(dialog: Dialog, invite: SipRequest, body: ByteArray = ByteArray(0), contentType: String? = null) {
        val ack = inDialog(dialog, "ACK", cseq = (lastSent[invite.callId] ?: invite).cseq.number)
        if (contentType != null) ack.headers.add("Content-Type", contentType)
        transact(SipRequest("ACK", ack.uri, ack.headers, body)) {}
    }

    internal suspend fun sendResponse(tx: ServerTransaction, response: SipResponse) {
        (flow ?: throw IllegalStateException("no flow")).send(response)
        if (response.isFinal) servers.remove("${tx.request.topVia.branch}|${tx.request.method}")
    }

    /** Dispatch a message received on the flow. */
    suspend fun receive(message: SipMessage) {
        when (message) {
            is SipResponse -> {
                val key = "${message.topVia.branch}|${message.cseq.method}"
                val tx = clients[key] ?: return
                if (message.isFinal) {
                    // Over a reliable transport the transaction layer ACKs a non-2xx final to INVITE (§17.1.1.3).
                    if (message.status >= 300 && tx.request.method == "INVITE") hopAck(tx.request, message)
                    tx.final.complete(message)
                } else scope.launch { tx.provisional(message) }
            }
            is SipRequest -> receiveRequest(message)
        }
    }

    private suspend fun hopAck(invite: SipRequest, response: SipResponse) {
        val headers = Headers()
        headers.add("Via", invite.headers.values("Via").first())
        invite.headers.all("Route").forEach { headers.add("Route", it) }
        headers.add("Max-Forwards", "70").add("From", invite.headers["From"]!!).add("To", response.headers["To"]!!)
            .add("Call-ID", invite.callId).add("CSeq", "${invite.cseq.number} ACK")
        flow?.send(SipRequest("ACK", invite.uri, headers))
    }

    private suspend fun receiveRequest(request: SipRequest) {
        val branch = request.topVia.branch ?: ""
        when (request.method) {
            "ACK" -> { scope.launch { onAck(request) }; return }
            "CANCEL" -> {
                val invite = servers["$branch|INVITE"]
                val tx = ServerTransaction(this, request)
                if (invite == null) { tx.respond(481); return }
                tx.respond(200)
                if (!invite.finalSent) {
                    invite.cancelled.complete(Unit)
                    invite.respond(487)
                }
                return
            }
        }
        val key = "$branch|${request.method}"
        if (servers.containsKey(key)) return // a retransmission; the transport is reliable, so drop it
        val tx = ServerTransaction(this, request)
        servers[key] = tx
        scope.launch {
            try {
                onRequest(tx)
                if (!tx.finalSent && request.method != "INVITE") tx.respond(500)
            } catch (e: Exception) {
                if (!tx.finalSent) runCatching { tx.respond(500, "Server Internal Error") }
            }
        }
    }
}
