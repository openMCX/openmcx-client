//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SdsError(message: String, val status: Int? = null) : Exception(message)

/** A message this client sent, and the notifications that came back for it. */
data class SentSds(
    val messageId: UUID,
    val conversationId: UUID,
    val target: String,
    val group: Boolean,
    val text: String,
    val disposition: DispositionRequest?,
    val status: Int,
    val at: Long = System.currentTimeMillis(),
    /** TS 24.282 §6.6.3.2: the DPPK-ID the payload was protected with; null when sent in the clear. */
    val dppkId: Long? = null,
    /** Notifier MCData ID to the latest notification type from them. */
    val notifications: MutableMap<String, DispositionNotification> = ConcurrentHashMap(),
)

/** A message this client received. */
data class ReceivedSds(
    val messageId: UUID,
    val conversationId: UUID,
    val sender: String,
    val group: String?,
    val payloads: List<Payload>,
    val disposition: DispositionRequest?,
    val inReplyTo: UUID?,
    val at: Long = System.currentTimeMillis(),
    /** Functional aliases (§22): the alias the sender presented, and the alias the message was sent to. */
    val senderAlias: String? = null,
    val calledAlias: String? = null,
    /** TS 24.282 §6.6.3.2: the DPPK-ID of an end-to-end protected payload; null when it came in the clear. */
    val dppkId: Long? = null,
    /** TS 33.180 §8.5.5: the payload was signed by its sender and verified. */
    val authenticated: Boolean = false,
) {
    @Volatile var displayed = false
    @Volatile var notified: DispositionNotification? = null
    internal var tdu1: Job? = null
    val text: String get() = payloads.filter { it.type == PayloadType.TEXT }.joinToString("\n") { it.text }
}

interface SdsListener {
    fun received(message: ReceivedSds) {}
    fun notification(message: SentSds, from: String, type: DispositionNotification) {}
}

/**
 * The MCData client's short data service over the signalling plane (TS 24.282 §9.2.2):
 * standalone SDS one-to-one and to groups, with delivery and read notifications
 * (§9.2.1.3, §12.2). See docs/android/wire/phase3-mcdata.md.
 */
class McdataService(private val client: McxClient) : ServiceHandler {
    private val config = client.config.mcdata!!
    private val ua = client.ua
    private val scope = client.scope
    /** TS 24.282 §6.6.3: end-to-end payload protection and authentication. */
    private val protection: McdataProtection? = client.mcdataProtection

    private val _inbox = MutableStateFlow<List<ReceivedSds>>(emptyList())
    val inbox: StateFlow<List<ReceivedSds>> = _inbox
    private val _outbox = MutableStateFlow<List<SentSds>>(emptyList())
    val outbox: StateFlow<List<SentSds>> = _outbox

    /** The user cleared the messages: forget the received and sent short data. */
    fun clear() { _inbox.value = emptyList(); _outbox.value = emptyList() }
    var listener: SdsListener? = null

    private fun event(text: String) = client.event("mcdata: $text")
    private fun now() = System.currentTimeMillis() / 1000

    private fun message(parts: List<Part>): SipRequest {
        val request = ua.newRequest("MESSAGE", config.psi, NameAddr(config.psi))
        request.headers.add("Accept-Contact", "*;+g.3gpp.mcdata.sds;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCDATA_SDS)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCDATA_SDS)
        val (type, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", type)
        return SipRequest(request.method, request.uri, request.headers, body)
    }

    /**
     * Send a standalone SDS (§9.2.2.2.1) to an MCData user, or to a group when [group] is
     * set. Returns once the MCData server has accepted it (202).
     */
    suspend fun send(target: String, text: String, group: Boolean, disposition: DispositionRequest? = null,
                     conversationId: UUID = UUID.randomUUID(), inReplyTo: UUID? = null, toAlias: Boolean = false,
                     calledAlias: String? = null): SentSds {
        val data = DataPayload(listOf(Payload.text(text)))
        // Above the signalling-plane limit the media plane (MSRP) is required, which this client does not have.
        if (data.size > config.maxPayloadSizeSdsCplaneBytes) throw SdsError("payload of ${data.size} octets is above the signalling-plane limit")
        val signalling = SdsSignalling(now(), conversationId, UUID.randomUUID(), inReplyTo, disposition = disposition)
        // §9.2.2.2.1 steps 2b ii and 3b v: an alias presented only while it is active for this user, and a one-to-one
        // SDS to an alias (<call-to-functional-alias-ind>), or to the MCData ID the server selected for it.
        val presented = client.aliasesOf(McService.MCDATA).value.let { a -> a.inUse?.takeIf { it in a.active } }
        val info = if (group) McdataInfo(requestType = "group-sds", requestUri = target, clientId = client.config.clientId, functionalAlias = presented)
        else McdataInfo(requestType = "one-to-one-sds", functionalAlias = presented, callToFunctionalAlias = if (toAlias) true else null,
            calledFunctionalAlias = calledAlias)
        val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (!group) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray())
        parts += Part(SIGNALLING, signalling.encode())
        var payload = data.encode()
        val dppk = protect(target, group, toAlias, parts, payload)?.also { payload = it.second }?.first
        // TS 33.180 §8.5.5: signed over the protected payload when both apply.
        payload = protection?.sign(payload) ?: payload
        parts += Part(PAYLOAD, payload)
        val response = ua.send(message(parts))
        if (toAlias && response.status == 300) {
            // The server names the MCData ID that holds the alias: the message goes to it, naming the alias called.
            val redirect = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }
                ?.let { McdataInfo.parse(it.text) }
            val selected = redirect?.requestUri ?: throw SdsError("functional alias redirect without an MCData ID", 300)
            event("SDS to functional alias $target: sent to $selected")
            return send(selected, text, false, disposition, conversationId, inReplyTo, calledAlias = redirect.calledFunctionalAlias ?: target)
        }
        if (!response.isSuccess) {
            val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
            throw SdsError("${response.status} ${response.reason}$warning", response.status)
        }
        val sent = SentSds(signalling.messageId, conversationId, target, group, text, disposition, response.status, dppkId = dppk)
        _outbox.value = (_outbox.value + sent).takeLast(100)
        event("sent ${if (group) "group" else "one-to-one"} SDS ${sent.messageId} to $target: ${response.status}")
        return sent
    }

    /**
     * TS 24.282 §6.6.3.2 and §9.2.2.2.1 step 2c: with `protect_payload`, the DATA PAYLOAD of a one-to-one SDS protected
     * with a PCK (its I_MESSAGE sent with it as an application/mikey part, step 2c viii, unless an unexpired context
     * with the peer exists) and of a group SDS with the group's GMK; the DPPK-ID and the protected payload, null when
     * unprotected (an SDS to a functional alias, whose recipient is not known yet, goes in the clear, as the Linux
     * client). A required key that cannot be had stops the message: it is not sent in the clear.
     */
    private suspend fun protect(target: String, group: Boolean, toAlias: Boolean, parts: MutableList<Part>,
                                payload: ByteArray): Pair<Long, ByteArray>? {
        val protection = protection?.takeIf { it.protectsPayload } ?: return null
        return try {
            if (group) {
                val key = protection.gmkFor(target)
                key.csbId to protection.seal(payload, key)
            } else if (!toAlias) {
                val (key, message) = protection.pckFor(target)
                if (message != null) parts += Part(McdataProtection.MIKEY, message.toByteArray())
                key.csbId to protection.seal(payload, key)
            } else null
        } catch (e: org.openmcx.client.security.KeyError) {
            throw SdsError("payload protection: ${e.message}")
        }
    }

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        if (request.method != "MESSAGE") return false
        val sds = request.headers.values("Accept-Contact").any { it.contains(Icsi.encoded(Icsi.MCDATA_SDS)) } ||
            request.headers["P-Asserted-Service"]?.trim() == Icsi.MCDATA_SDS
        if (!sds) return false
        val parts = Multipart.parse(request.contentType, request.body)
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
        val signalling = parts.filter { it.mediaType == SIGNALLING }
        if (info == null || signalling.isEmpty()) { tx.respond(400, "Bad Request"); return true }
        try {
            when (MessageType.of(signalling.first().content)) {
                MessageType.SDS_SIGNALLING_PAYLOAD -> {
                    val payload = parts.firstOrNull { it.mediaType == PAYLOAD } ?: run { tx.respond(400, "Bad Request"); return true }
                    val opened = openPayload(parts, info, payload.content)
                    if (opened.failure != null) { tx.respond(606, extra = opened.failure); return true }
                    received(info, SdsSignalling.decode(signalling.first().content), DataPayload.decode(opened.data!!),
                        opened.dppkId, opened.authenticated)
                }
                MessageType.SDS_NOTIFICATION -> signalling.forEach { notification(info, SdsNotification.decode(it.content)) }
                else -> { tx.respond(400, "Bad Request"); return true }
            }
        } catch (e: McdataCodecError) {
            event("bad SDS: ${e.message}")
            tx.respond(400, "Bad Request")
            return true
        }
        tx.respond(200)
        return true
    }

    private class Opened(val data: ByteArray?, val dppkId: Long? = null, val authenticated: Boolean = false,
                         val failure: List<Pair<String, String>>? = null)

    /**
     * TS 24.282 §9.2.2.2.2 step 3 and §6.6.3.2: accept the PCK sent with the message, verify a signed DATA PAYLOAD
     * (bit 8, TS 33.180 §8.5.5) and decrypt a protected one (bit 7) end to end; a failure is answered 606, with warning
     * 136 for a refused I_MESSAGE (as the Linux client).
     */
    private suspend fun openPayload(parts: List<Part>, info: McdataInfo, payload: ByteArray): Opened {
        val sender = info.callingUserId ?: ""
        val protection = protection
        for (part in parts.filter { it.mediaType == McdataProtection.MIKEY }) {
            try {
                protection ?: throw org.openmcx.client.security.KeyError("MCData key management client unavailable")
                protection.acceptPck(part.text.trim(), sender)
            } catch (e: Exception) {
                protection?.failures?.incrementAndGet()
                event("PCK from $sender refused: ${e.message}")
                return Opened(null, failure = listOf("Warning" to McdataProtection.WARNING_136))
            }
        }
        var data = payload
        var authenticated = false
        if (data.isNotEmpty() && (data[0].toInt() and 0x80) != 0) {
            try {
                data = (protection ?: throw org.openmcx.client.security.KeyError("no key material to verify a signed payload")).verify(data, sender)
                authenticated = true
            } catch (e: Exception) {
                protection?.failures?.incrementAndGet()
                event("payload signature from $sender refused: ${e.message}")
                return Opened(null, failure = emptyList())
            }
        }
        var dppk: Long? = null
        if (data.isNotEmpty() && (data[0].toInt() and 0x40) != 0) {
            try {
                protection ?: throw org.openmcx.client.security.KeyError("no key material for a protected payload")
                dppk = protection.keyId(data)
                data = protection.open(data, info.callingUserId, info.callingGroupId)
            } catch (e: Exception) {
                protection?.failures?.incrementAndGet()
                event("protected payload from $sender${info.callingGroupId?.let { " on $it" } ?: ""} undecryptable: ${e.message}")
                return Opened(null, failure = emptyList())
            }
        }
        return Opened(data, dppk, authenticated)
    }

    private fun received(info: McdataInfo, signalling: SdsSignalling, data: DataPayload, dppkId: Long? = null, authenticated: Boolean = false) {
        // A re-delivery after an UNDELIVERED report carries the same message ID: answer, but once.
        if (_inbox.value.any { it.messageId == signalling.messageId }) { event("duplicate SDS ${signalling.messageId}"); return }
        val sender = info.callingUserId ?: signalling.sender ?: "unknown"
        val message = ReceivedSds(signalling.messageId, signalling.conversationId, sender, info.callingGroupId,
            data.payloads, signalling.disposition, signalling.inReplyTo, senderAlias = info.functionalAlias, calledAlias = info.calledFunctionalAlias,
            dppkId = dppkId, authenticated = authenticated)
        _inbox.value = (_inbox.value + message).takeLast(100)
        event("received SDS ${message.messageId} from $sender${message.group?.let { " on $it" } ?: ""}: ${message.text}")
        // Messages for an application are not shown to the user (§9.2.1.2); this client has no applications.
        listener?.received(message)
        when (message.disposition) {
            DispositionRequest.DELIVERY -> notify(message, DispositionNotification.DELIVERED)
            DispositionRequest.DELIVERY_AND_READ -> message.tdu1 = scope.launch {
                delay(config.tdu1Seconds * 1000L)
                if (!message.displayed) notify(message, DispositionNotification.DELIVERED)
            }
            else -> Unit
        }
    }

    /** The user has seen [messageId] (display indication): send the read notification it asked for. */
    fun display(messageId: UUID): Boolean {
        val message = _inbox.value.firstOrNull { it.messageId == messageId } ?: return false
        if (message.displayed) return true
        message.displayed = true
        when (message.disposition) {
            DispositionRequest.READ -> notify(message, DispositionNotification.READ)
            DispositionRequest.DELIVERY_AND_READ ->
                if (message.notified == null) { message.tdu1?.cancel(); notify(message, DispositionNotification.DELIVERED_AND_READ) }
                else notify(message, DispositionNotification.READ)
            else -> Unit
        }
        return true
    }

    /** Send an SDS NOTIFICATION to the original sender (§12.2.1.1). */
    private fun notify(message: ReceivedSds, type: DispositionNotification) {
        message.notified = type
        scope.launch {
            val notification = SdsNotification(type, now(), message.conversationId, message.messageId, sender = config.userId)
            val info = McdataInfo(callingGroupId = message.group)
            val parts = listOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()),
                Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(message.sender)).toByteArray()),
                Part(SIGNALLING, notification.encode()))
            val response = runCatching { ua.send(message(parts)) }.getOrNull()
            event("sent ${type.name} for ${message.messageId} to ${message.sender}: ${response?.status}")
        }
    }

    private fun notification(info: McdataInfo, notification: SdsNotification) {
        val from = notification.sender ?: info.callingUserId ?: "unknown"
        val sent = _outbox.value.firstOrNull { it.messageId == notification.messageId }
        event("${notification.type.name} from $from for ${notification.messageId}${if (sent == null) " (unknown message)" else ""}")
        if (sent != null) {
            val previous = sent.notifications[from]
            // DELIVERED then READ adds up to delivered-and-read.
            sent.notifications[from] = if (previous == DispositionNotification.DELIVERED && notification.type == DispositionNotification.READ)
                DispositionNotification.DELIVERED_AND_READ else notification.type
            _outbox.value = _outbox.value.toList()
            listener?.notification(sent, from, notification.type)
        }
    }

    companion object {
        const val SIGNALLING = "application/vnd.3gpp.mcdata-signalling"
        const val PAYLOAD = "application/vnd.3gpp.mcdata-payload"
    }
}
