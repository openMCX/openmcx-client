package org.openmcx.client.mcdata

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                     conversationId: UUID = UUID.randomUUID(), inReplyTo: UUID? = null): SentSds {
        val data = DataPayload(listOf(Payload.text(text)))
        // Above the signalling-plane limit the media plane (MSRP) is required, which this client does not have.
        if (data.size > config.maxPayloadSizeSdsCplaneBytes) throw SdsError("payload of ${data.size} octets is above the signalling-plane limit")
        val signalling = SdsSignalling(now(), conversationId, UUID.randomUUID(), inReplyTo, disposition = disposition)
        val info = if (group) McdataInfo(requestType = "group-sds", requestUri = target, clientId = client.config.clientId)
        else McdataInfo(requestType = "one-to-one-sds")
        val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (!group) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray())
        parts += Part(SIGNALLING, signalling.encode())
        parts += Part(PAYLOAD, data.encode())
        val response = ua.send(message(parts))
        if (!response.isSuccess) {
            val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
            throw SdsError("${response.status} ${response.reason}$warning", response.status)
        }
        val sent = SentSds(signalling.messageId, conversationId, target, group, text, disposition, response.status)
        _outbox.value = (_outbox.value + sent).takeLast(100)
        event("sent ${if (group) "group" else "one-to-one"} SDS ${sent.messageId} to $target: ${response.status}")
        return sent
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
                    received(info, SdsSignalling.decode(signalling.first().content), DataPayload.decode(payload.content))
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

    private fun received(info: McdataInfo, signalling: SdsSignalling, data: DataPayload) {
        // A re-delivery after an UNDELIVERED report carries the same message ID: answer, but once.
        if (_inbox.value.any { it.messageId == signalling.messageId }) { event("duplicate SDS ${signalling.messageId}"); return }
        val sender = info.callingUserId ?: signalling.sender ?: "unknown"
        val message = ReceivedSds(signalling.messageId, signalling.conversationId, sender, info.callingGroupId,
            data.payloads, signalling.disposition, signalling.inReplyTo)
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
