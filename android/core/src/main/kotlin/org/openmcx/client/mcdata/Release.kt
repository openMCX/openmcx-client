//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part

/** TS 24.282 table 15.2.20-1: the type of a COMMUNICATION RELEASE message. */
enum class ReleaseType(val code: Int) {
    INTENT_TO_RELEASE(1), EXTENSION_REQUEST(2), EXTENSION_RESPONSE(3), AUTH_USER_RELEASE_REQUEST(4);
    companion object { fun of(code: Int) = entries.find { it.code == code } ?: throw McdataCodecError("reserved comm release information type") }
}

/**
 * TS 24.282 §15.1.10.1: COMMUNICATION RELEASE, carried in SIP INFO requests of the g.3gpp.mcdata-com-release package
 * (§13.2.4 to §13.2.6). The Data query type IE (§15.2.19) asks for the remaining amount of data; the Extension response
 * type IE (§15.2.21) answers an extension request (1 accepted, 2 rejected).
 */
data class CommunicationRelease(val type: ReleaseType, val remainingDataQuery: Boolean = false, val extensionAccepted: Boolean? = null) {
    fun encode(): ByteArray = Writer().apply {
        u8(MESSAGE_TYPE); u8(type.code)
        if (remainingDataQuery) u8((DATA_QUERY shl 4) or 1)
        extensionAccepted?.let { u8((EXTENSION_RESPONSE shl 4) or if (it) 1 else 2) }
    }.bytes()

    companion object {
        const val MESSAGE_TYPE = 0x0a
        const val DATA_QUERY = 0xb
        const val EXTENSION_RESPONSE = 0xc
        /** TS 24.282 annex H.1.2.4. */
        const val PACKAGE = "g.3gpp.mcdata-com-release"

        fun decode(data: ByteArray): CommunicationRelease {
            val r = Reader(data); r.expectType(MESSAGE_TYPE)
            val type = ReleaseType.of(r.u8())
            var query = false; var extension: Boolean? = null
            val seen = mutableSetOf<Int>()
            while (r.remaining > 0) {
                val octet = r.u8()
                if (!seen.add(octet shr 4)) throw McdataCodecError("duplicate COMMUNICATION RELEASE IE")
                when (octet shr 4) {
                    DATA_QUERY -> { if (octet and 15 != 1) throw McdataCodecError("reserved data query type"); query = true }
                    EXTENSION_RESPONSE -> extension = when (octet and 15) { 1 -> true; 2 -> false; else -> throw McdataCodecError("reserved extension response type") }
                    else -> throw McdataCodecError("unknown COMMUNICATION RELEASE IE")
                }
            }
            return CommunicationRelease(type, query, extension)
        }
    }
}

/** MCData communication release over the media plane (TS 24.282 §13.2.4 to §13.2.6), client side. */
internal object Release {
    /** The reason-text of a Reason header field (RFC 3326 §2), or the field itself without one. */
    fun reasonText(header: String?): String? = header?.let { Regex("text=\"([^\"]*)\"").find(it)?.groupValues?.get(1) ?: it }

    /**
     * TS 24.282 §13.2.4.2.1, §13.2.4.2.3, §13.2.6.2.2.2, §13.2.6.2.2.3: the server's INFO in the dialog of the
     * communication: the intent to release (answering a remaining data query in an INFO of its own, §13.2.4.2.1 step 2c),
     * an extension request for the authorised user, the answer to this user's extension request, the remaining data of the
     * originator. Anything else is refused 403.
     */
    suspend fun received(service: McdataMediaService, c: MediaCommunication, tx: MediaInvite) {
        val s = service.internals
        val request = tx.request
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrDefault(emptyList())
        val reason = reasonText(request.headers["Reason"])
        parts.firstOrNull { it.mediaType == McdataService.PAYLOAD }?.let { part ->
            val remaining = runCatching { DataPayload.decode(part.content).payloads.joinToString("") { it.text } }.getOrNull()
            c.release = "remaining data: $remaining"
            tx.respond(200); s.changed(c); s.event("communication ${c.id}: remaining data of the originator: $remaining")
            return
        }
        val message = parts.firstOrNull { it.mediaType == McdataService.SIGNALLING }?.let {
            runCatching { CommunicationRelease.decode(it.content) }.getOrNull()
        } ?: run { tx.respond(403); return }
        when (message.type) {
            ReleaseType.INTENT_TO_RELEASE -> {
                c.release = "release pending" + (reason?.let { ": $it" } ?: "")
                tx.respond(200)
                if (message.remainingDataQuery) {
                    // Nothing of this client's waits to be sent once a SEND is answered: the remaining amount is 0.
                    val payload = DataPayload(listOf(Payload.text("0"))).encode()
                    c.dialog?.let { d -> runCatching { s.peer.info(d, listOf(Part(McdataService.PAYLOAD, payload)), emptyList()) } }
                }
            }
            ReleaseType.EXTENSION_REQUEST -> { c.release = "extension requested"; tx.respond(200) }
            ReleaseType.EXTENSION_RESPONSE -> { c.release = if (message.extensionAccepted == true) "extended" else "extension refused"; tx.respond(200) }
            else -> { tx.respond(403); return }
        }
        s.changed(c)
        s.event("communication ${c.id}: ${c.release}")
    }

    /**
     * TS 24.282 §13.2.4.2.2 (extension request, "extend"), §13.2.5.2.2.1 (the authorised user's release, "now"),
     * §13.2.6.2.2.1 (intent to release, "intent", optionally with the remaining data query), §13.2.6.2.2.4 (the
     * authorised user's extension response, "accept" or "reject"): an INFO of the g.3gpp.mcdata-com-release package in the
     * communication's dialog, the Reason "SIP;cause=200;text=..." when given; mcdata-info with the client ID for "now" and
     * "intent" (step 4a). Returns the status.
     */
    suspend fun send(service: McdataMediaService, c: MediaCommunication, action: String, reason: String?, query: Boolean): Int {
        val s = service.internals
        val dialog = c.dialog ?: throw SdsError("communication ${c.id} has no dialog")
        val message = when (action) {
            "extend" -> CommunicationRelease(ReleaseType.EXTENSION_REQUEST)
            "now" -> CommunicationRelease(ReleaseType.AUTH_USER_RELEASE_REQUEST)
            "intent" -> CommunicationRelease(ReleaseType.INTENT_TO_RELEASE, remainingDataQuery = query)
            "accept", "reject" -> CommunicationRelease(ReleaseType.EXTENSION_RESPONSE, extensionAccepted = action == "accept")
            else -> throw SdsError("release action: extend, now, intent, accept or reject")
        }
        val parts = buildList {
            if (action == "now" || action == "intent")
                add(Part(McdataInfo.CONTENT_TYPE, McdataInfo(clientId = s.peer.clientId).encode().toByteArray()))
            add(Part(McdataService.SIGNALLING, message.encode()))
        }
        val headers = listOfNotNull(reason?.let { "Reason" to "SIP;cause=200;text=\"$it\"" })
        val response = s.peer.info(dialog, parts, headers)
        c.release = "$action: ${response.status}"
        s.changed(c)
        s.event("communication ${c.id}: release $action answered ${response.status}")
        return response.status
    }
}
