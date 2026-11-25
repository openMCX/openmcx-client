//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * FD over the media plane (TS 24.282 §10.2.5, TS 24.582 §7): the file offered in the SDP with its RFC 5547 attributes and
 * sent as the body of an MSRP SEND of type application/vnd.3gpp.mcdata-file, chunked; received, it is checked against
 * the size and hash of the file selector and kept.
 */
internal object FdMedia {
    private val date = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss Z", Locale.US).withZone(ZoneOffset.UTC)
    const val DECLINED = "399 mcdata \"110 user declined the call invitation\""
    const val DEFERRED = "399 mcdata \"231 user deferred the call invitation\""

    /**
     * TS 24.282 §10.2.5.2.1 and §10.2.5.2.3: the INVITE (mcdata-info "one-to-one-fd" or "group-fd", the FD SIGNALLING
     * PAYLOAD without Payload or Metadata IE, §6.2.2.3), the offer with "a=accept-types" of the signalling type only,
     * "a=file-selector", "a=file-transfer-id", "a=file-date" and "a=file-description" (RFC 5547), then the file in one
     * SEND (TS 24.582 §7.1.2.1) and the BYE once it is answered. [priority] "emergency" or "imminent" (§10.2.5.2.3
     * 8)a0), 9)c)-d)).
     */
    suspend fun send(service: McdataMediaService, target: String, group: Boolean, name: String, contents: ByteArray, type: String,
                     mandatory: Boolean, completion: Boolean, priority: String): MediaCommunication {
        val s = service.internals
        val emergency = priority == "emergency"; val imminent = priority == "imminent"
        if (priority != "normal" && AdhocTarget.of(target) != null) throw SdsError("ad hoc group emergency and imminent peril communications are not implemented")
        if (imminent && !group) throw SdsError("imminent peril applies to group communications")
        val c = service.outgoing("fd", target, group)
        c.emergency = emergency; c.imminentPeril = imminent
        s.add(c)
        var local = s.protect(c, s.localMedia("sendonly", acceptTypes = listOf(McdataService.SIGNALLING)), target, group)
        // RFC 5547: the selector describes what is transferred, which for a protected file is its protected series.
        val transferred = c.key?.let { key -> s.peer.protection?.sealFile(contents, key) } ?: contents
        val sha = FdService.sha256Bytes(transferred)
        val selector = FileMetadata(name, type, transferred.size.toLong(), sha).encode().removePrefix("file-selector:")
        local = local.copy(fileSelector = selector, fileTransferId = org.openmcx.client.msrp.MsrpMessage.newIdent(16),
            fileDate = "creation:\"${date.format(Instant.ofEpochSecond(s.clock()))}\"", fileDescription = name)
        val signalling = FdSignalling(s.clock(), UUID.randomUUID(), UUID.randomUUID(), "", completionRequested = completion,
            mandatoryDownload = mandatory)
        c.messageId = signalling.messageId
        c.fileName = name; c.fileSize = transferred.size.toLong(); c.sha256 = sha
        val info = s.requestInfo(if (group) "group-fd" else "one-to-one-fd", target, group, emergency, imminent)
        val parts = s.targetParts(target, group, info, local)
        parts += Part(McdataService.SIGNALLING, signalling.encode(mediaPlane = true))
        s.event("communication ${c.id}: file $name (${transferred.size} octets) to $target over the media plane")
        s.establish(c, parts, s.headers("fd", if (emergency) "emergency" else if (imminent) "imminent_peril" else null), local)
        try {
            c.msrp!!.send(McdataMediaService.FILE, transferred)
        } catch (e: Exception) {
            c.dialog?.let { s.peer.bye(it) }
            s.fail(c, "file not sent: ${e.message}")
            throw SdsError("file not sent: ${e.message}")
        }
        c.state = "delivered"; s.changed(c)
        c.msrp?.close()
        c.dialog?.let { s.peer.bye(it) }
        s.event("communication ${c.id}: file sent, session released")
        return c
    }

    /**
     * TS 24.282 §10.2.5.2.4 steps 5-7: the file metadata from the SDP, the FD SIGNALLING PAYLOAD from the INVITE; a
     * mandatory download is received, otherwise the user decides (accept, decline: 480 with warning 110, defer: 480
     * with warning 231), the INVITE waiting with a 180 meanwhile. Returns true to answer it 200.
     */
    suspend fun offer(service: McdataMediaService, c: MediaCommunication, tx: MediaInvite, parts: List<Part>, offer: MsrpMedia): Boolean {
        val s = service.internals
        val signallingPart = parts.firstOrNull { it.mediaType == McdataService.SIGNALLING }
        val selector = offer.fileSelector
        if (signallingPart == null || selector == null) { tx.respond(488); s.event("FD offer without signalling or file selector"); return false }
        val (signalling, metadata) = try {
            FdSignalling.decode(signallingPart.content, mediaPlane = true) to FileMetadata.parse("file-selector:$selector")
        } catch (e: McdataCodecError) { tx.respond(488); s.event("FD offer refused: ${e.message}"); return false }
        c.file = FdMediaOffer(signalling, metadata, selector)
        c.messageId = signalling.messageId
        c.fileName = metadata.name; c.fileSize = metadata.size; c.sha256 = metadata.sha256
        c.state = "offered"
        s.add(c)
        val decision = if (signalling.mandatoryDownload) "accept" else s.fileDecision(c)
        val final = when (decision) {
            "ask" -> {
                tx.respond(180)
                s.changed(c)
                val answer = CompletableDeferred<String>().also { c.decision = it }
                val cancelled = tx.cancelled
                withTimeoutOrNull(120_000) {
                    select<String> {
                        answer.onAwait { it }
                        if (cancelled != null) cancelled.onAwait { "cancelled" }
                    }
                } ?: "defer"
            }
            else -> decision
        }
        c.decision = null
        return when (final) {
            "accept" -> true
            "cancelled" -> { c.state = "released"; s.changed(c); s.event("communication ${c.id}: FD offer cancelled"); false }
            else -> {
                tx.respond(480, extra = listOf("Warning" to if (final == "defer") DEFERRED else DECLINED))
                c.state = if (final == "defer") "deferred" else "declined"; s.changed(c)
                s.event("communication ${c.id}: file ${metadata.name} $final")
                false
            }
        }
    }

    /**
     * TS 24.582 §7.1.3.2: the file is complete: checked against the selector's size and hash (RFC 5547), opened when
     * protected (TS 33.180 §8.5.4), kept, and the FD NOTIFICATION "completed" sent when asked for (TS 24.282 §12.2.1.1
     * with §6.2.3.2).
     */
    suspend fun received(service: McdataMediaService, c: MediaCommunication, body: ByteArray) {
        val s = service.internals
        val offer = c.file ?: run { s.event("communication ${c.id}: a file without an offer"); return }
        val sha = FdService.sha256Bytes(body)
        if (body.size.toLong() != offer.metadata.size || (offer.metadata.sha256 != null && offer.metadata.sha256 != sha)) {
            s.fail(c, "file ${offer.metadata.name}: ${body.size} octets, hash $sha, not as offered", "corrupt"); return
        }
        // An ad hoc group has no group key: its files come in the clear (as they are sent, McdataMediaService.protect).
        val contents = s.peer.protection?.takeIf { it.protectsPayload && !c.adhoc }?.let { p ->
            try { p.openFile(body, c.peer, c.group) } catch (e: Exception) {
                p.failures.incrementAndGet()
                s.fail(c, "file undecryptable: ${e.message}", "undecryptable"); return
            }
        } ?: body
        val directory = service.fileDirectory
        if (directory != null) {
            directory.mkdirs()
            val target = File(directory, FdService.localName(offer.metadata.name))
            target.writeBytes(contents)
            c.filePath = target.path
        }
        c.state = "received"; s.changed(c)
        s.event("communication ${c.id}: file ${offer.metadata.name} received (${contents.size} octets)")
        s.listener?.file(c, contents)
        if (offer.signalling.completionRequested) {
            val notice = FdNotification(FdNotificationType.COMPLETED, s.clock(), offer.signalling.conversationId, offer.signalling.messageId,
                sender = s.peer.userId)
            val parts = listOf(Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingGroupId = c.group).encode().toByteArray()),
                Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOfNotNull(c.peer)).toByteArray()),
                Part(McdataService.SIGNALLING, notice.encode()))
            val response = runCatching { s.peer.fdMessage(parts) }
            s.event("communication ${c.id}: COMPLETED to ${c.peer}: ${response.getOrNull()?.status ?: response.exceptionOrNull()?.message}")
        }
    }
}
