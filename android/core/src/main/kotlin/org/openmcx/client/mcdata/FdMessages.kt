//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import java.util.UUID

/** HTTP FD messages, TS 24.282 15.1.3, 15.1.6 and 15.1.9. */
data class FdSignalling(
    val time: Long, val conversationId: UUID, val messageId: UUID, val fileUrl: String,
    val metadata: String? = null, val completionRequested: Boolean = false,
    val mandatoryDownload: Boolean = false, val applicationId: Int? = null,
    val sender: String? = null, val inReplyTo: UUID? = null,
) {
    fun encode(): ByteArray = Writer().apply {
        require(fileUrl.isNotBlank()) { "HTTP FD requires a FILEURL" }
        fdHeader(2, time, conversationId, messageId)
        inReplyTo?.let { u8(0x21); uuid(it) }
        applicationId?.let { require(it in 0..255); u8(0x22); u8(it) }
        if (completionRequested) u8(0x91)
        if (mandatoryDownload) u8(0xa1)
        tlvE(0x78, byteArrayOf(4) + fileUrl.toByteArray(Charsets.UTF_8))
        metadata?.let { tlvE(0x79, it.toByteArray(Charsets.UTF_8)) }
        sender?.let { tlvE(0x51, it.toByteArray(Charsets.UTF_8)) }
    }.bytes()

    companion object {
        fun decode(data: ByteArray): FdSignalling {
            val r = Reader(data); r.expectType(2)
            val time = r.dateTime(); val conversation = r.uuid(); val message = r.uuid()
            var url: String? = null; var metadata: String? = null; var sender: String? = null
            var completion = false; var mandatory = false; var app: Int? = null; var reply: UUID? = null
            val seen = mutableSetOf<Int>()
            while (r.remaining > 0) {
                val octet = r.u8()
                val ie = if (octet shr 4 in 9..10) octet shr 4 else octet
                if (!seen.add(ie)) throw McdataCodecError("duplicate FD IE")
                when (ie) {
                    9, 10 -> {
                        if (octet and 15 != 1) throw McdataCodecError("reserved FD half-octet value")
                        if (ie == 9) completion = true else mandatory = true
                    }
                    0x21 -> reply = r.uuid()
                    0x22 -> app = r.u8()
                    0x78 -> {
                        val value = r.lengthValue()
                        if (value.size < 2 || value[0].toInt() != 4) throw McdataCodecError("FD requires one FILEURL")
                        url = String(value, 1, value.size - 1, Charsets.UTF_8)
                    }
                    0x79 -> metadata = String(r.lengthValue(), Charsets.UTF_8)
                    0x51 -> sender = String(r.lengthValue(), Charsets.UTF_8)
                    0x7d, 0x7e, 0x53 -> r.lengthValue()
                    else -> throw McdataCodecError("unsupported FD IE $ie")
                }
            }
            if (url.isNullOrBlank()) throw McdataCodecError("HTTP FD requires a FILEURL")
            return FdSignalling(time, conversation, message, url, metadata, completion, mandatory, app, sender, reply)
        }
    }
}

enum class FdNotificationType(val code: Int) {
    ACCEPTED(1), REJECTED(2), COMPLETED(3), DEFERRED(4);
    companion object { fun of(code: Int) = entries.find { it.code == code } ?: throw McdataCodecError("reserved FD notification") }
}

enum class FdNetworkNotificationType(val code: Int) {
    EXPIRED(1), DELETED(2);
    companion object { fun of(code: Int) = entries.find { it.code == code } ?: throw McdataCodecError("reserved FD network notification") }
}

data class FdNotification(
    val type: FdNotificationType, val time: Long, val conversationId: UUID, val messageId: UUID,
    val sender: String? = null, val applicationId: Int? = null,
) {
    fun encode(): ByteArray = Writer().apply {
        u8(6); u8(type.code); fdHeader(null, time, conversationId, messageId)
        applicationId?.let { require(it in 0..255); u8(0x22); u8(it) }
        sender?.let { tlvE(0x51, it.toByteArray(Charsets.UTF_8)) }
    }.bytes()
    companion object {
        fun decode(data: ByteArray): FdNotification {
            val r = Reader(data); r.expectType(6)
            val type = FdNotificationType.of(r.u8()); val time = r.dateTime(); val conversation = r.uuid(); val message = r.uuid()
            var sender: String? = null; var app: Int? = null; val seen = mutableSetOf<Int>()
            while (r.remaining > 0) {
                val ie = r.u8()
                if (!seen.add(ie)) throw McdataCodecError("duplicate FD notification IE")
                when (ie) {
                    0x22 -> app = r.u8()
                    0x51 -> sender = String(r.lengthValue(), Charsets.UTF_8)
                    0x7d -> r.lengthValue()
                    else -> throw McdataCodecError("unsupported FD notification IE")
                }
            }
            return FdNotification(type, time, conversation, message, sender, app)
        }
    }
}

data class FdNetworkNotification(val type: FdNetworkNotificationType, val time: Long, val conversationId: UUID,
                                 val messageId: UUID, val applicationId: Int? = null) {
    fun encode(): ByteArray = Writer().apply {
        u8(9); u8(type.code); fdHeader(null, time, conversationId, messageId)
        applicationId?.let { require(it in 0..255); u8(0x22); u8(it) }
    }.bytes()
    companion object {
        fun decode(data: ByteArray): FdNetworkNotification {
            val r = Reader(data); r.expectType(9)
            val type = FdNetworkNotificationType.of(r.u8()); val time = r.dateTime(); val conversation = r.uuid(); val message = r.uuid()
            val app = if (r.remaining > 0) {
                if (r.u8() != 0x22) throw McdataCodecError("unsupported FD network IE")
                r.u8()
            } else null
            if (r.remaining != 0) throw McdataCodecError("duplicate FD network IE")
            return FdNetworkNotification(type, time, conversation, message, app)
        }
    }
}

private fun Writer.fdHeader(type: Int?, time: Long, conversation: UUID, message: UUID) {
    require(time in 0..0xffffffffffL) { "timestamp outside 40 bits" }
    type?.let(::u8); dateTime(time); uuid(conversation); uuid(message)
}
