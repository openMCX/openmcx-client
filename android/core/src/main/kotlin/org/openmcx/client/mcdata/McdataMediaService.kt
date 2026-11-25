//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.msrp.MsrpConnector
import org.openmcx.client.msrp.MsrpListener
import org.openmcx.client.msrp.MsrpMessage
import org.openmcx.client.msrp.MsrpSession
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// MCData communications over the media plane (MSRP), both sides: standalone SDS (TS 24.282 §9.2.3, TS 24.582 §6.1.1),
// SDS sessions (TS 24.282 §9.2.4, TS 24.582 §6.1.2), FD (TS 24.282 §10.2.5, TS 24.582 §7), their emergency and
// imminent peril priority (TS 24.282 §6.2.8) and their release (TS 24.282 §13.2).
//
// The client never listens (README: one outbound flow). Its offer is "a=setup:actpass" (TS 24.282 §9.2.3.2.1 1g); the
// MCData server answers "passive" and this client connects (TS 24.582 §6.1.1.2.1 step 2a). Offered a stream, it
// answers "active" (TS 24.282 §9.2.3.2.2, RFC 4145) and connects. A peer that answers "active", or offers it, would
// need this client to listen, which it does not: the communication fails (offered: 488).

/** What the media plane needs from the SIP side of the client. */
interface McdataMediaPeer {
    val userId: String
    val clientId: String
    /** The local IPv4 address, for the SDP (the flow's). */
    fun localAddress(): String
    /** The Contact of an initial INVITE or its 2xx for [feature] "sds" or "fd" (TS 24.282 §9.2.3.2.3 steps 1-4). */
    fun contact(feature: String): String
    /** TS 24.282 §6.2.8.1.2, §6.2.8.1.12, §6.2.8.3.3: the Resource-Priority of [level] ("emergency", "imminent_peril"). */
    fun resourcePriority(level: String): String?
    /** The functional alias in use while it is active (TS 24.282 §9.2.3.2.3 7)b)ii)B)). */
    fun aliasInUse(): String? = null
    /** An initial INVITE to the MCData PSI; ACKed and with its dialog when 2xx. */
    suspend fun invite(parts: List<Part>, headers: List<Pair<String, String>>): Pair<SipResponse, Dialog?>
    suspend fun reinvite(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse
    suspend fun bye(dialog: Dialog, headers: List<Pair<String, String>> = emptyList())
    suspend fun info(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse
    fun event(text: String)
    val protection: McdataProtection? get() = null
    /** A MESSAGE for file distribution (FD NOTIFICATION, TS 24.282 §12.2.1.1); null when not available. */
    suspend fun fdMessage(parts: List<Part>): SipResponse? = null
}

/** [McdataMediaPeer] on the running [McxClient]. */
class ClientMediaPeer(private val client: McxClient) : McdataMediaPeer {
    private val mcdata = client.config.mcdata!!
    private val ua = client.ua
    override val userId get() = mcdata.userId
    override val clientId get() = client.config.clientId
    override fun localAddress(): String {
        val host = ua.flow?.connection?.localHost ?: throw SdsError("not connected")
        return runCatching { InetAddress.getByName(host) }.getOrNull()?.takeIf { it is Inet4Address }?.hostAddress ?: host
    }
    override fun contact(feature: String) = "<${ua.contactUri()}>;+g.3gpp.mcdata.$feature;+g.3gpp.icsi-ref=\"" +
        Icsi.encoded(if (feature == "fd") Icsi.MCDATA_FD else Icsi.MCDATA_SDS) + "\""
    override fun resourcePriority(level: String) = client.serviceResourcePriorities["mcdata"]?.get(level)?.takeIf { it.isNotBlank() }
    override fun aliasInUse() = client.aliasesOf(McService.MCDATA).value.let { a -> a.inUse?.takeIf { it in a.active } }
    override suspend fun invite(parts: List<Part>, headers: List<Pair<String, String>>): Pair<SipResponse, Dialog?> {
        val request = ua.newRequest("INVITE", mcdata.psi, NameAddr(mcdata.psi))
        headers.forEach { (k, v) -> request.headers.add(k, v) }
        val (type, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", type)
        val invite = SipRequest(request.method, request.uri, request.headers, body)
        val response = ua.send(invite)
        if (!response.isSuccess) return response to null
        val sent = ua.lastSent[invite.callId] ?: invite
        val dialog = Dialog.fromResponse(sent, response)
        ua.ack(dialog, sent)
        return response to dialog
    }
    override suspend fun reinvite(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
        val request = ua.inDialog(dialog, "INVITE")
        headers.forEach { (k, v) -> request.headers.add(k, v) }
        val (type, body) = Multipart.encode(parts)
        request.headers.add("Content-Type", type)
        val reinvite = SipRequest(request.method, request.uri, request.headers, body)
        val response = ua.send(reinvite, dialog)
        if (response.isSuccess) ua.ack(dialog, ua.lastSent[reinvite.callId] ?: reinvite)
        return response
    }
    override suspend fun bye(dialog: Dialog, headers: List<Pair<String, String>>) {
        val bye = ua.inDialog(dialog, "BYE")
        headers.forEach { (k, v) -> bye.headers.add(k, v) }
        runCatching { withTimeoutOrNull(5_000) { ua.send(bye, dialog) } }
    }
    override suspend fun info(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
        val request = ua.inDialog(dialog, "INFO")
        val (type, body) = if (parts.size == 1) parts[0].contentType to parts[0].content else Multipart.encode(parts)
        request.headers.add("Content-Type", type)
        // TS 24.282 §13.2.4.2.2 with RFC 6086 §4.2.1: the INFO package of MCData communication release.
        request.headers.add("Info-Package", CommunicationRelease.PACKAGE).add("Content-Disposition", "Info-Package")
        headers.forEach { (k, v) -> request.headers.add(k, v) }
        return ua.send(SipRequest(request.method, request.uri, request.headers, body), dialog)
    }
    override fun event(text: String) = client.event("mcdata media: $text")
    override val protection get() = client.mcdataProtection
    override suspend fun fdMessage(parts: List<Part>) = ClientFdPeer(client).send(parts)
}

/** An incoming INVITE and the means to answer it (a [ServerTransaction], or a test's). */
interface MediaInvite {
    val request: SipRequest
    val localTag: String
    /** Completes when the INVITE is cancelled (RFC 3261 §9.2). */
    val cancelled: kotlinx.coroutines.Deferred<Unit>? get() = null
    suspend fun respond(status: Int, body: ByteArray = ByteArray(0), contentType: String? = null, extra: List<Pair<String, String>> = emptyList())
}

/** One MCData communication over the media plane. */
class MediaCommunication(
    val id: Int,
    /** "sds" (standalone SDS), "session" (SDS session) or "fd". */
    val kind: String,
    val outgoing: Boolean,
    /** The other user (one-to-one), the originator (terminating) or null. */
    @Volatile var peer: String?,
    val group: String?,
    val conversationId: UUID = UUID.randomUUID(),
) {
    /** inviting, connecting, open, sent, delivered, offered, receiving, received, released, failed, refused. */
    @Volatile var state: String = "inviting"
    @Volatile var status: Int? = null
    @Volatile var warning: String? = null
    @Volatile var error: String? = null
    @Volatile var emergency = false
    @Volatile var imminentPeril = false
    @Volatile var messageId: UUID? = null
    @Volatile var security: String? = null
    /** The release of TS 24.282 §13.2: the server's notice or request, and this client's answer. */
    @Volatile var release: String? = null
    /** An FD: the file name, size and where it is kept. */
    @Volatile var fileName: String? = null
    @Volatile var fileSize: Long? = null
    @Volatile var filePath: String? = null
    @Volatile var sha256: String? = null
    val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
    internal var dialog: Dialog? = null
    internal var msrp: MsrpSession? = null
    internal var key: org.openmcx.client.security.KeyMessage? = null
    internal var offeredSdp: String? = null
    internal var localMedia: MsrpMedia? = null
    internal var file: FdMediaOffer? = null
    internal var decision: kotlinx.coroutines.CompletableDeferred<String>? = null
    val open get() = state == "open"
    val ended get() = state in setOf("released", "failed", "refused", "delivered", "received", "declined", "deferred")
}

/** The file of an FD over the media plane (TS 24.282 §10.2.5): its signalling and RFC 5547 description. */
class FdMediaOffer(val signalling: FdSignalling, val metadata: FileMetadata, val selector: String, val contents: ByteArray? = null)

interface MediaListener {
    fun changed(communication: MediaCommunication) {}
    /** A file arrived over the media plane (TS 24.582 §7.1.3.2). */
    fun file(communication: MediaCommunication, contents: ByteArray) {}
}

/**
 * The media-plane procedures: [sendStandalone] (TS 24.282 §9.2.3.2.3), [openSession], [sessionSend] and [close]
 * (§9.2.4.2.3, TS 24.582 §6.1.2.4), [setPriority] (§6.2.8), and the terminating side in [handle] (§9.2.3.2.4,
 * §9.2.4.2.4). Received messages go to [sds] (the same inbox and notifications as the signalling plane, §9.2.1.2,
 * TS 24.582 §6.1.2.6); notifications for a message received in a session go in that session (TS 24.582 §6.1.2.5.1).
 */
class McdataMediaService(
    private val scope: CoroutineScope,
    private val peer: McdataMediaPeer,
    private val sds: McdataService,
    private val connector: MsrpConnector,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : ServiceHandler {
    private val ids = AtomicInteger()
    private val _communications = MutableStateFlow<List<MediaCommunication>>(emptyList())
    val communications: StateFlow<List<MediaCommunication>> = _communications
    var listener: MediaListener? = null
    /** TS 24.282 §10.2.5.2.4 step 7: how an FD offer is decided ("ask" waits for [answerFile], "accept", "decline"). */
    @Volatile var fileDecision: (MediaCommunication) -> String = { "accept" }
    /** Where received files are kept (TS 24.582 §7.1.3.2); null: not stored, only given to [MediaListener.file]. */
    @Volatile var fileDirectory: java.io.File? = null

    /** The open SDS session (the last one opened or received). */
    val session: MediaCommunication? get() = _communications.value.lastOrNull { it.kind == "session" && it.open }

    private fun event(text: String) = peer.event(text)
    private fun changed(c: MediaCommunication) { _communications.value = _communications.value.toList(); listener?.changed(c) }
    private fun add(c: MediaCommunication) { _communications.value = (_communications.value + c).takeLast(50); listener?.changed(c) }
    fun communication(id: Int) = _communications.value.firstOrNull { it.id == id }
    fun clear() { _communications.value = _communications.value.filter { !it.ended } }

    private fun headers(feature: String, level: String? = null): List<Pair<String, String>> {
        val icsi = if (feature == "fd") Icsi.MCDATA_FD else Icsi.MCDATA_SDS
        return buildList {
            add("Accept-Contact" to "*;+g.3gpp.mcdata.$feature;require;explicit")
            add("Accept-Contact" to "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(icsi)}\";require;explicit")
            add("P-Preferred-Service" to icsi)
            add("Contact" to peer.contact(feature))
            add("Supported" to "timer")
            level?.let { l -> peer.resourcePriority(l)?.let { add("Resource-Priority" to it) } }
        }
    }

    private fun localMedia(direction: String, setup: String = "actpass", acceptTypes: List<String> =
        listOf(McdataService.SIGNALLING, McdataService.PAYLOAD)) =
        MsrpMedia.local(peer.localAddress(), MsrpMessage.newIdent(), setup, direction, acceptTypes)

    private fun sdpPart(media: MsrpMedia) = Part("application/sdp", media.encode(clock()).toByteArray())

    /**
     * TS 24.282 §9.2.3.2.1 step 2 and TS 33.180 §8.4: a one-to-one offer carries a new PCK (a=key-mgmt) when there is
     * no security context with the peer; a group communication uses the group's GMK.
     */
    private suspend fun protect(c: MediaCommunication, media: MsrpMedia, target: String, group: Boolean): MsrpMedia {
        val protection = peer.protection?.takeIf { it.protectsPayload } ?: return media
        return try {
            if (group) { c.key = protection.gmkFor(target); media }
            else { val (key, message) = protection.pckFor(target); c.key = key; if (message != null) media.copy(keyMgmt = message) else media }
        } catch (e: org.openmcx.client.security.KeyError) { throw SdsError("payload protection: ${e.message}") }
    }

    private suspend fun payload(c: MediaCommunication, encoded: ByteArray): ByteArray {
        val protection = peer.protection
        val sealed = c.key?.let { protection?.seal(encoded, it) } ?: encoded
        return protection?.sign(sealed) ?: sealed
    }

    private fun failure(response: SipResponse) = "${response.status} ${response.reason}" + (response.headers["Warning"]?.let { " ($it)" } ?: "")

    /** TS 24.582 §6.1.1.2.1 steps 1-4: the MSRP connection the answer describes, this client the active endpoint. */
    private suspend fun connect(c: MediaCommunication, local: MsrpMedia, remote: MsrpMedia) {
        if (remote.setup == "active") throw SdsError("the answer is a=setup:active: this client does not listen (RFC 6135)")
        val session = MsrpSession(scope, connector, local.uri, listener(c), trace = { event("call ${c.id} $it") })
        c.msrp = session
        c.state = "connecting"; changed(c)
        session.connect(remote.uri)
        c.security = session.security
        scope.launch {
            val cause = session.closed.await()
            if (c.state in setOf("open", "connecting", "receiving")) {
                event("communication ${c.id}: MSRP connection ended${cause?.let { ": ${it.message}" } ?: ""}")
            }
        }
    }

    private fun listener(c: MediaCommunication) = object : MsrpListener {
        override fun message(contentType: String, body: ByteArray, messageId: String) {
            scope.launch { runCatching { received(c, contentType, body) }.onFailure { event("communication ${c.id}: ${it.message}") } }
        }
        override fun report(messageId: String, status: Int, range: org.openmcx.client.msrp.ByteRange?) {
            event("communication ${c.id}: REPORT $status for $messageId")
        }
    }

    // Originating (TS 24.282 §9.2.3.2.3, §9.2.4.2.3).

    private fun requestInfo(requestType: String, target: String, group: Boolean, emergency: Boolean = false, imminent: Boolean = false,
                            alert: Boolean? = null) =
        McdataInfo(requestType = requestType, requestUri = if (group) target else null, clientId = peer.clientId,
            functionalAlias = peer.aliasInUse(), emergency = if (emergency) true else null, imminentPeril = if (imminent) true else null,
            alert = alert)

    private fun targetParts(target: String, group: Boolean, info: McdataInfo, media: MsrpMedia): MutableList<Part> {
        val parts = mutableListOf(sdpPart(media), Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (!group) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray())
        return parts
    }

    /** The INVITE of a communication, its 2xx and the MSRP connection; the communication fails otherwise. */
    private suspend fun establish(c: MediaCommunication, parts: List<Part>, headers: List<Pair<String, String>>, local: MsrpMedia) {
        c.localMedia = local
        val (response, dialog) = try { peer.invite(parts, headers) } catch (e: Exception) { fail(c, e.message ?: "INVITE failed"); throw SdsError(e.message ?: "INVITE failed") }
        c.status = response.status
        c.warning = response.headers["Warning"]
        if (dialog == null) { fail(c, failure(response), "refused"); throw SdsError(failure(response), response.status) }
        c.dialog = dialog
        val sdp = Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == "application/sdp" }
        try {
            val remote = MsrpMedia.parse(sdp?.text ?: throw SdsError("2xx without SDP"))
            connect(c, local, remote)
        } catch (e: Exception) {
            peer.bye(dialog)
            fail(c, e.message ?: "media plane failed")
            throw (e as? SdsError) ?: SdsError(e.message ?: "media plane failed")
        }
    }

    private fun fail(c: MediaCommunication, why: String, state: String = "failed") {
        c.error = why; c.state = state
        c.msrp?.close()
        event("communication ${c.id} ${c.kind}: $state: $why")
        changed(c)
    }

    /**
     * TS 24.282 §9.2.3.2.3 with TS 24.582 §6.1.1.2: a standalone SDS whose payload goes over the media plane: the INVITE
     * ("a=sendonly", "actpass", §9.2.3.2.1), the MSRP connection, one SEND with the SDS SIGNALLING PAYLOAD and DATA
     * PAYLOAD (§6.1.1.2.4), then on its 200 the BYE with Reason "SIP;cause=200;text=\"transmission succeeded\"".
     */
    suspend fun sendStandalone(target: String, text: String, group: Boolean, disposition: DispositionRequest? = null,
                               conversationId: UUID = UUID.randomUUID(), inReplyTo: UUID? = null): SentSds {
        val c = MediaCommunication(ids.incrementAndGet(), "sds", true, target.takeIf { !group }, target.takeIf { group }, conversationId)
        add(c)
        val local = protect(c, localMedia("sendonly"), target, group)
        val info = requestInfo(if (group) "group-sds" else "one-to-one-sds", target, group)
        event("communication ${c.id}: standalone SDS of ${text.length} characters to $target over the media plane")
        establish(c, targetParts(target, group, info, local), headers("sds"), local)
        val signalling = SdsSignalling(clock(), conversationId, UUID.randomUUID(), inReplyTo, disposition = disposition)
        c.messageId = signalling.messageId
        try {
            c.msrp!!.sendParts(listOf(Part(McdataService.SIGNALLING, signalling.encode()),
                Part(McdataService.PAYLOAD, payload(c, DataPayload(listOf(Payload.text(text))).encode()))))
        } catch (e: Exception) {
            // TS 24.582 §6.1.1.2.1: the message could not be sent; the SIP session ends.
            c.dialog?.let { peer.bye(it) }
            fail(c, "SDS not sent: ${e.message}")
            throw SdsError("SDS not sent: ${e.message}")
        }
        c.state = "delivered"; changed(c)
        c.msrp?.close()
        c.dialog?.let { peer.bye(it, listOf("Reason" to "SIP;cause=200;text=\"transmission succeeded\"")) }
        event("communication ${c.id}: SDS ${signalling.messageId} sent, session released")
        return SentSds(signalling.messageId, conversationId, target, group, text, disposition, c.status ?: 200, dppkId = c.key?.csbId,
            plane = "media").also { sds.recordSent(it) }
    }

    /**
     * TS 24.282 §9.2.4.2.3: an SDS session, one-to-one or group, "a=sendrecv"; [priority] "emergency" (§6.2.8.3.2
     * one-to-one, §6.2.8.1.1 group, with an emergency alert when [alert]) or "imminent" (§6.2.8.1.9, group only).
     */
    suspend fun openSession(target: String, group: Boolean, priority: String = "normal", alert: Boolean = false): MediaCommunication {
        val emergency = priority == "emergency"; val imminent = priority == "imminent"
        if (imminent && !group) throw SdsError("imminent peril applies to group communications")
        if (alert && !(emergency && group)) throw SdsError("an emergency alert goes with an emergency group communication")
        val c = MediaCommunication(ids.incrementAndGet(), "session", true, target.takeIf { !group }, target.takeIf { group })
        c.emergency = emergency; c.imminentPeril = imminent
        add(c)
        val local = protect(c, localMedia("sendrecv"), target, group)
        val info = requestInfo(if (group) "group-sds-session" else "one-to-one-sds-session", target, group, emergency, imminent,
            alert = if (emergency) alert else null)
        event("communication ${c.id}: SDS session with $target${if (priority != "normal") " ($priority)" else ""}")
        establish(c, targetParts(target, group, info, local),
            headers("sds", if (emergency) "emergency" else if (imminent) "imminent_peril" else null), local)
        c.state = "open"; changed(c)
        return c
    }

    /** TS 24.582 §6.1.2.4: an SDS message in the open session; any participant may send, so the Sender IE names this user. */
    suspend fun sessionSend(text: String, disposition: DispositionRequest? = null, c: MediaCommunication? = session): SentSds {
        val s = c?.takeIf { it.open } ?: throw SdsError("no SDS session is open")
        val signalling = SdsSignalling(clock(), s.conversationId, UUID.randomUUID(), disposition = disposition, sender = peer.userId)
        s.msrp!!.sendParts(listOf(Part(McdataService.SIGNALLING, signalling.encode()),
            Part(McdataService.PAYLOAD, payload(s, DataPayload(listOf(Payload.text(text))).encode()))))
        s.messages += "out: $text"
        changed(s)
        event("communication ${s.id}: session SDS ${signalling.messageId} sent")
        val target = s.group ?: s.peer ?: ""
        return SentSds(signalling.messageId, s.conversationId, target, s.group != null, text, disposition, 200, dppkId = s.key?.csbId,
            plane = "session").also { sds.recordSent(it) }
    }

    /** TS 24.582 §6.1.2.5.3: an SDS NOTIFICATION in the session, a SEND of the signalling body alone. */
    private suspend fun sendNotification(c: MediaCommunication, notification: SdsNotification): Boolean {
        if (!c.open) return false
        c.msrp?.send(McdataService.SIGNALLING, notification.encode()) ?: return false
        return true
    }

    /** TS 24.282 §6.2.8.1.17: release the communication with a BYE; [reason] e.g. for an ad hoc release. */
    suspend fun close(c: MediaCommunication? = session, reason: String? = null) {
        val s = c ?: throw SdsError("no media plane communication is open")
        s.state = "released"; changed(s)
        s.msrp?.close()
        s.dialog?.let { peer.bye(it, listOfNotNull(reason?.let { r -> "Reason" to r })) }
        event("communication ${s.id}: released")
    }

    /**
     * A re-INVITE in the session changes its priority: TS 24.282 §6.2.8.3.2 / §6.2.8.4.3 (one-to-one emergency and its
     * cancel), §6.2.8.1.1 / §6.2.8.1.3 (group emergency), §6.2.8.1.9 / §6.2.8.1.11 (group imminent peril). The offer is
     * the media as established (this client the active endpoint, RFC 4145).
     */
    suspend fun setPriority(emergency: Boolean, enable: Boolean, c: MediaCommunication? = session): MediaCommunication {
        val s = c?.takeIf { it.open } ?: throw SdsError("no SDS session is open")
        val group = s.group != null
        if (!emergency && !group) throw SdsError("imminent peril applies to group communications")
        val info = McdataInfo(requestType = if (group) "group-sds-session" else "one-to-one-sds-session", requestUri = s.group,
            clientId = peer.clientId, emergency = if (emergency) enable else null, imminentPeril = if (!emergency) enable else null,
            alert = if (emergency && enable) false else null)
        val local = (s.localMedia ?: localMedia("sendrecv")).copy(setup = "active")
        // §6.2.8.1.2, §6.2.8.1.12, §6.2.8.3.3: the priority asked for; a cancellation carries the normal one.
        val level = if (!enable) null else if (emergency) "emergency" else "imminent_peril"
        val headers = buildList {
            add("Contact" to peer.contact("sds"))
            level?.let { l -> peer.resourcePriority(l)?.let { add("Resource-Priority" to it) } }
        }
        val response = peer.reinvite(s.dialog ?: throw SdsError("no dialog"), listOf(sdpPart(local),
            Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())), headers)
        s.status = response.status; s.warning = response.headers["Warning"]
        if (!response.isSuccess) { changed(s); throw SdsError("priority change: ${failure(response)}", response.status) }
        if (emergency) s.emergency = enable else s.imminentPeril = enable
        changed(s)
        event("communication ${s.id}: ${if (emergency) "emergency" else "imminent peril"} ${if (enable) "on" else "off"}")
        return s
    }

    // Terminating (TS 24.282 §9.2.3.2.4, §9.2.4.2.4, §10.2.5.2.4).

    override suspend fun handle(tx: ServerTransaction): Boolean = handle(object : MediaInvite {
        override val request get() = tx.request
        override val localTag get() = tx.localTag
        override val cancelled get() = tx.cancelled
        override suspend fun respond(status: Int, body: ByteArray, contentType: String?, extra: List<Pair<String, String>>) {
            tx.respond(status, body = body, contentType = contentType, extra = extra)
        }
    })

    private fun ours(request: SipRequest) = _communications.value.lastOrNull { it.dialog?.callId == request.callId }

    suspend fun handle(tx: MediaInvite): Boolean {
        val request = tx.request
        val existing = ours(request)
        when (request.method) {
            "BYE" -> {
                val c = existing ?: return false
                tx.respond(200)
                c.release = c.release ?: request.headers["Reason"]
                released(c, "released by the server")
                return true
            }
            "INFO" -> { val c = existing ?: return false; info(c, tx); return true }
            "INVITE" -> {}
            else -> return false
        }
        if (existing != null) { reinvited(existing, tx); return true }
        if (request.to.tag != null) return false
        val service = request.headers["P-Asserted-Service"]?.trim()
        val fd = service == Icsi.MCDATA_FD
        if (service != Icsi.MCDATA_SDS && !fd) return false
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrElse { tx.respond(400); return true }
        val sdp = parts.firstOrNull { it.mediaType == "application/sdp" }?.text
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
        if (sdp == null || info == null) { tx.respond(488); return true }
        // IP connectivity has its own media (TS 24.282 §20.2.2), not MSRP: not this service's.
        val offer = try { MsrpMedia.parse(sdp) } catch (e: McdataCodecError) { tx.respond(488); event("offer refused: ${e.message}"); return true }
        // This client does not listen: an offer that would make it the passive endpoint is refused.
        if (offer.setup == "active") { tx.respond(488); event("offer with a=setup:active refused: this client does not listen"); return true }
        if (info.callingUserId.isNullOrEmpty()) { tx.respond(403); return true }
        val session = info.requestType?.endsWith("-sds-session") == true
        val kind = if (fd) "fd" else if (session) "session" else "sds"
        val c = MediaCommunication(ids.incrementAndGet(), kind, false, info.callingUserId, info.callingGroupId)
        c.emergency = info.emergency == true
        c.imminentPeril = info.imminentPeril == true
        c.offeredSdp = sdp
        // TS 24.282 §9.2.3.2.4 step 3: the PCK of a one-to-one communication; 488 with warning 136 when it fails.
        offer.keyMgmt?.let { key ->
            if (info.callingGroupId != null) { tx.respond(488); return true }
            try {
                (peer.protection ?: throw org.openmcx.client.security.KeyError("MCData key management client unavailable"))
                    .acceptPck(key, info.callingUserId)
            } catch (e: Exception) {
                peer.protection?.failures?.incrementAndGet()
                event("PCK from ${info.callingUserId} refused: ${e.message}")
                tx.respond(488, extra = listOf("Warning" to McdataProtection.WARNING_136)); return true
            }
        }
        if (fd) { if (!fileOffer(c, tx, parts, offer)) return true } else add(c)
        answer(c, tx, offer)
        return true
    }

    /**
     * TS 24.282 §9.2.3.2.2, §9.2.4.2.2, §10.2.5.2.2: the answer, "active" (RFC 4145; port 9: this endpoint does not
     * accept), "a=sendrecv" for a session else "a=recvonly", then the connection to the offered stream; the 200 (OK) has
     * "timer" in Require and the service's feature tag and ICSI in Contact (§9.2.3.2.4 steps 5, 7, 8).
     */
    private suspend fun answer(c: MediaCommunication, tx: MediaInvite, offer: MsrpMedia) {
        val file = c.file
        var local = localMedia(if (c.kind == "session") "sendrecv" else "recvonly", setup = "active",
            acceptTypes = if (file != null) listOf(McdataService.SIGNALLING) else listOf(McdataService.SIGNALLING, McdataService.PAYLOAD))
        if (file != null) local = local.copy(fileSelector = offer.fileSelector, fileTransferId = offer.fileTransferId)
        c.localMedia = local
        val request = tx.request
        c.dialog = Dialog.fromRequest(request, tx.localTag)
        val expires = request.headers["Session-Expires"]?.substringBefore(';')?.trim()
        tx.respond(200, local.encode(clock()).toByteArray(), "application/sdp", listOfNotNull(
            "Contact" to peer.contact(if (file != null) "fd" else "sds"), "Require" to "timer", "Supported" to "timer",
            expires?.let { "Session-Expires" to "$it;refresher=uas" }))
        c.state = "connecting"; changed(c)
        event("communication ${c.id}: ${c.kind} from ${c.peer}${c.group?.let { " on $it" } ?: ""} answered")
        scope.launch {
            try {
                connect(c, local, offer)
                c.state = when (c.kind) { "session" -> "open"; "fd" -> "receiving"; else -> "receiving" }
                changed(c)
            } catch (e: Exception) {
                fail(c, "MSRP connection: ${e.message}")
                c.dialog?.let { peer.bye(it) }
            }
        }
    }

    /** TS 24.282 §6.2.8.4.2 and §9.2.4.2.4 steps 4A, 4B: the server's re-INVITE changes the session's priority. */
    private suspend fun reinvited(c: MediaCommunication, tx: MediaInvite) {
        val parts = runCatching { Multipart.parse(tx.request.contentType, tx.request.body) }.getOrDefault(emptyList())
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
        info?.emergency?.let { c.emergency = it }
        info?.imminentPeril?.let { c.imminentPeril = it }
        val local = (c.localMedia ?: localMedia("sendrecv", "active")).copy(setup = "active")
        tx.respond(200, local.encode(clock()).toByteArray(), "application/sdp", listOf("Contact" to peer.contact("sds")))
        event("communication ${c.id}: priority from the server: emergency ${c.emergency}, imminent peril ${c.imminentPeril}")
        changed(c)
    }

    private fun released(c: MediaCommunication, why: String) {
        if (c.ended) return
        c.state = if (c.kind == "fd" && !c.outgoing && c.filePath != null) "received" else "released"
        c.msrp?.close()
        event("communication ${c.id}: $why")
        changed(c)
    }

    /** TS 24.582 §6.1.1.3.2, §6.1.2.6: the MCData bodies of a received SEND, as a message of the signalling plane. */
    private suspend fun received(c: MediaCommunication, contentType: String, body: ByteArray) {
        if (contentType.substringBefore(';').trim() == FILE) return fileReceived(c, body)
        val parts = if (contentType.startsWith("multipart/")) Multipart.parse(contentType, body) else listOf(Part(contentType, body))
        val signalling = parts.firstOrNull { it.mediaType == McdataService.SIGNALLING } ?: run { event("communication ${c.id}: SEND without signalling"); return }
        when (MessageType.of(signalling.content)) {
            MessageType.SDS_NOTIFICATION -> {
                val notification = SdsNotification.decode(signalling.content)
                sds.notification(McdataInfo(callingUserId = notification.sender ?: c.peer, callingGroupId = c.group), notification)
            }
            MessageType.SDS_SIGNALLING_PAYLOAD -> {
                val decoded = SdsSignalling.decode(signalling.content)
                // Any participant of a session may send: the Sender IE names them.
                val info = McdataInfo(callingUserId = decoded.sender ?: c.peer, callingGroupId = c.group)
                val payload = parts.firstOrNull { it.mediaType == McdataService.PAYLOAD }?.content ?: ByteArray(0)
                val opened = sds.openPayload(emptyList(), info, payload)
                if (opened.data == null) { event("communication ${c.id}: payload refused"); return }
                val data = DataPayload.decode(opened.data)
                c.messages += "in ${info.callingUserId}: " + data.payloads.filter { it.type == PayloadType.TEXT }.joinToString("\n") { it.text }
                changed(c)
                sds.received(info, decoded, data, opened.dppkId, opened.authenticated, plane = if (c.kind == "session") "session" else "media",
                    inBand = if (c.kind == "session") { n -> sendNotification(c, n) } else null)
            }
            else -> event("communication ${c.id}: ignored message type ${MessageType.of(signalling.content)}")
        }
    }

    // FD over the media plane (TS 24.282 §10.2.5): FdMedia.kt.

    /** TS 24.282 §10.2.5.2.3: send [contents] as the file [name] over the media plane. */
    suspend fun sendFile(target: String, group: Boolean, name: String, contents: ByteArray, type: String = "application/octet-stream",
                         mandatory: Boolean = false, completion: Boolean = false, priority: String = "normal"): MediaCommunication =
        FdMedia.send(this, target, group, name, contents, type, mandatory, completion, priority)

    /** TS 24.282 §10.2.5.2.4 7)b)-d): the user's decision ("accept", "decline", "defer") on the offered file [id] (the latest). */
    fun answerFile(decision: String, id: Int? = null): Boolean {
        require(decision in setOf("accept", "decline", "defer")) { "accept, decline or defer" }
        val c = _communications.value.lastOrNull { it.decision != null && (id == null || it.id == id) } ?: return false
        return c.decision?.complete(decision) ?: false
    }

    /** TS 24.282 §10.2.5.2.4 steps 5-7: the file offered; false when the INVITE was answered (refused, declined). */
    private suspend fun fileOffer(c: MediaCommunication, tx: MediaInvite, parts: List<Part>, offer: MsrpMedia): Boolean =
        FdMedia.offer(this, c, tx, parts, offer)

    private suspend fun fileReceived(c: MediaCommunication, body: ByteArray) = FdMedia.received(this, c, body)

    private suspend fun info(c: MediaCommunication, tx: MediaInvite) = Release.received(this, c, tx)

    /** TS 24.282 §13.2.4 to §13.2.6: a communication release request of this user ([Release.send]). */
    suspend fun release(action: String, reason: String? = null, query: Boolean = false, c: MediaCommunication? = null): Int =
        Release.send(this, c ?: _communications.value.lastOrNull { !it.ended && it.dialog != null } ?: throw SdsError("no communication is open"),
            action, reason, query)

    internal val internals = Internals()
    /** What the FD and release procedures of this service reach. */
    inner class Internals {
        val peer get() = this@McdataMediaService.peer
        val clock get() = this@McdataMediaService.clock
        fun event(text: String) = this@McdataMediaService.event(text)
        fun changed(c: MediaCommunication) = this@McdataMediaService.changed(c)
        fun add(c: MediaCommunication) = this@McdataMediaService.add(c)
        fun nextId() = ids.incrementAndGet()
        fun released(c: MediaCommunication, why: String) = this@McdataMediaService.released(c, why)
        fun fail(c: MediaCommunication, why: String, state: String = "failed") = this@McdataMediaService.fail(c, why, state)
        suspend fun protect(c: MediaCommunication, media: MsrpMedia, target: String, group: Boolean) = this@McdataMediaService.protect(c, media, target, group)
        fun localMedia(direction: String, setup: String = "actpass", acceptTypes: List<String>) = this@McdataMediaService.localMedia(direction, setup, acceptTypes)
        fun headers(feature: String, level: String? = null) = this@McdataMediaService.headers(feature, level)
        fun requestInfo(requestType: String, target: String, group: Boolean, emergency: Boolean = false, imminent: Boolean = false) =
            this@McdataMediaService.requestInfo(requestType, target, group, emergency, imminent)
        fun targetParts(target: String, group: Boolean, info: McdataInfo, media: MsrpMedia) = this@McdataMediaService.targetParts(target, group, info, media)
        suspend fun establish(c: MediaCommunication, parts: List<Part>, headers: List<Pair<String, String>>, local: MsrpMedia) =
            this@McdataMediaService.establish(c, parts, headers, local)
        val listener get() = this@McdataMediaService.listener
        val fileDecision get() = this@McdataMediaService.fileDecision
        val scope get() = this@McdataMediaService.scope
    }

    override suspend fun flowLost() {
        _communications.value.filter { !it.ended }.forEach { it.msrp?.close(); it.state = "failed"; it.error = "flow lost"; changed(it) }
    }

    override suspend fun logOff() {
        _communications.value.filter { !it.ended }.forEach { runCatching { close(it) } }
    }

    companion object {
        /** TS 24.582 §7.1.2.1: the content type of a file in an MSRP SEND. */
        const val FILE = "application/vnd.3gpp.mcdata-file"
    }
}
