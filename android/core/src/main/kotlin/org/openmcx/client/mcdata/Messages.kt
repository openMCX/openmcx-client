//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

class McdataCodecError(message: String) : Exception(message)

/** SDS disposition request type (TS 24.282 §15.2.3), sent as the half-octet TV IE `0x8-`. */
enum class DispositionRequest(val code: Int) {
    DELIVERY(1), READ(2), DELIVERY_AND_READ(3);

    val wantsDelivery get() = this != READ
    val wantsRead get() = this != DELIVERY

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw McdataCodecError("reserved disposition request $code")
    }
}

/** SDS disposition notification type (§15.2.5). */
enum class DispositionNotification(val code: Int) {
    UNDELIVERED(1), DELIVERED(2), READ(3), DELIVERED_AND_READ(4), DISPOSITION_PREVENTED(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw McdataCodecError("reserved notification type $code")
    }
}

/** Payload content types (§15.2.13, table 15.2.13-2). */
object PayloadType {
    const val TEXT = 1
    const val BINARY = 2
    const val HYPERLINKS = 3
    const val FILEURL = 4
    const val LOCATION = 5
    const val ENHANCED_STATUS = 6
    const val LOCATION_ALTITUDE = 8
    const val LOCATION_TIMESTAMP = 9
    const val CODED_TEXT = 10
}

/** Message types (§15.2.2); bits 8–7 (protected, authenticated) are always sent as 0. */
object MessageType {
    const val SDS_SIGNALLING_PAYLOAD = 0x01
    const val DATA_PAYLOAD = 0x03
    const val SDS_NOTIFICATION = 0x05

    fun of(body: ByteArray): Int = if (body.isEmpty()) -1 else body[0].toInt() and 0x3f
}

private object Iei {
    const val IN_REPLY_TO = 0x21
    const val APPLICATION_ID = 0x22
    const val DISPOSITION_HALF = 0x8
    const val EXTENDED_APPLICATION_ID = 0x7D
    const val USER_LOCATION = 0x7E
    const val SENDER = 0x51
    const val APPLICATION_METADATA = 0x53
    const val PAYLOAD = 0x78
    const val PROTECTED_PAYLOAD = 0x7A
}

/** SDS SIGNALLING PAYLOAD (§15.1.2). */
data class SdsSignalling(
    val time: Long,
    val conversationId: UUID,
    val messageId: UUID,
    val inReplyTo: UUID? = null,
    val applicationId: Int? = null,
    val disposition: DispositionRequest? = null,
    val extendedApplicationId: ByteArray? = null,
    val sender: String? = null,
) {
    fun encode(): ByteArray = Writer().apply {
        u8(MessageType.SDS_SIGNALLING_PAYLOAD)
        dateTime(time); uuid(conversationId); uuid(messageId)
        inReplyTo?.let { u8(Iei.IN_REPLY_TO); uuid(it) }
        applicationId?.let { u8(Iei.APPLICATION_ID); u8(it) }
        disposition?.let { u8((Iei.DISPOSITION_HALF shl 4) or it.code) }
        extendedApplicationId?.let { tlvE(Iei.EXTENDED_APPLICATION_ID, it) }
        sender?.let { tlvE(Iei.SENDER, it.toByteArray()) }
    }.bytes()

    override fun equals(other: Any?) = other is SdsSignalling && time == other.time && conversationId == other.conversationId &&
        messageId == other.messageId && inReplyTo == other.inReplyTo && applicationId == other.applicationId &&
        disposition == other.disposition && sender == other.sender &&
        (extendedApplicationId ?: ByteArray(0)).contentEquals(other.extendedApplicationId ?: ByteArray(0))
    override fun hashCode() = messageId.hashCode()

    companion object {
        fun decode(data: ByteArray): SdsSignalling {
            val r = Reader(data)
            r.expectType(MessageType.SDS_SIGNALLING_PAYLOAD)
            val time = r.dateTime(); val conversation = r.uuid(); val message = r.uuid()
            var inReplyTo: UUID? = null; var app: Int? = null; var disposition: DispositionRequest? = null
            var ext: ByteArray? = null; var sender: String? = null
            val seen = mutableSetOf<Int>()
            while (r.remaining > 0) {
                val octet = r.u8()
                val iei = if (octet shr 4 == Iei.DISPOSITION_HALF) Iei.DISPOSITION_HALF else octet
                if (!seen.add(iei)) throw McdataCodecError("duplicate IE 0x%02x".format(iei))
                when (iei) {
                    Iei.IN_REPLY_TO -> inReplyTo = r.uuid()
                    Iei.APPLICATION_ID -> app = r.u8()
                    Iei.DISPOSITION_HALF -> disposition = DispositionRequest.of(octet and 0x0f)
                    Iei.EXTENDED_APPLICATION_ID -> ext = r.lengthValue()
                    Iei.SENDER -> sender = String(r.lengthValue())
                    Iei.USER_LOCATION, Iei.APPLICATION_METADATA -> r.lengthValue()
                    else -> throw McdataCodecError("unknown IE 0x%02x in SDS SIGNALLING PAYLOAD".format(octet))
                }
            }
            return SdsSignalling(time, conversation, message, inReplyTo, app, disposition, ext, sender)
        }
    }
}

/** One payload of a DATA PAYLOAD (§15.2.13): content type and data. */
data class Payload(val type: Int, val data: ByteArray) {
    val text: String get() = String(data, Charsets.UTF_8)
    override fun equals(other: Any?) = other is Payload && type == other.type && data.contentEquals(other.data)
    override fun hashCode() = type * 31 + data.contentHashCode()

    companion object {
        fun text(text: String) = Payload(PayloadType.TEXT, text.toByteArray(Charsets.UTF_8))
    }
}

/** DATA PAYLOAD (§15.1.4). */
data class DataPayload(val payloads: List<Payload>) {
    /** The payload size for the SDS limits: the sum of the payload data (§9.2.2.3.1 NOTE 4). */
    val size: Int get() = payloads.sumOf { it.data.size }

    fun encode(): ByteArray {
        require(payloads.size in 1..255) { "1 to 255 payloads" }
        return Writer().apply {
            u8(MessageType.DATA_PAYLOAD); u8(payloads.size)
            payloads.forEach { tlvE(Iei.PAYLOAD, byteArrayOf(it.type.toByte()) + it.data) }
        }.bytes()
    }

    companion object {
        fun decode(data: ByteArray): DataPayload {
            val r = Reader(data)
            r.expectType(MessageType.DATA_PAYLOAD)
            val count = r.u8()
            val payloads = mutableListOf<Payload>()
            while (r.remaining > 0) {
                when (val iei = r.u8()) {
                    Iei.PAYLOAD -> {
                        val value = r.lengthValue()
                        if (value.isEmpty()) throw McdataCodecError("empty Payload IE")
                        payloads += Payload(value[0].toInt() and 0xff, value.copyOfRange(1, value.size))
                    }
                    Iei.PROTECTED_PAYLOAD -> throw McdataCodecError("protected payloads are not supported")
                    else -> throw McdataCodecError("unknown IE 0x%02x in DATA PAYLOAD".format(iei))
                }
            }
            if (payloads.size != count) throw McdataCodecError("DATA PAYLOAD announces $count payloads, has ${payloads.size}")
            return DataPayload(payloads)
        }
    }
}

/** SDS NOTIFICATION (§15.1.5). */
data class SdsNotification(
    val type: DispositionNotification,
    val time: Long,
    val conversationId: UUID,
    val messageId: UUID,
    val applicationId: Int? = null,
    val extendedApplicationId: ByteArray? = null,
    val sender: String? = null,
) {
    fun encode(): ByteArray = Writer().apply {
        u8(MessageType.SDS_NOTIFICATION); u8(type.code)
        dateTime(time); uuid(conversationId); uuid(messageId)
        applicationId?.let { u8(Iei.APPLICATION_ID); u8(it) }
        extendedApplicationId?.let { tlvE(Iei.EXTENDED_APPLICATION_ID, it) }
        sender?.let { tlvE(Iei.SENDER, it.toByteArray()) }
    }.bytes()

    override fun equals(other: Any?) = other is SdsNotification && type == other.type && time == other.time &&
        conversationId == other.conversationId && messageId == other.messageId && applicationId == other.applicationId &&
        sender == other.sender && (extendedApplicationId ?: ByteArray(0)).contentEquals(other.extendedApplicationId ?: ByteArray(0))
    override fun hashCode() = messageId.hashCode() * 31 + type.hashCode()

    companion object {
        fun decode(data: ByteArray): SdsNotification {
            val r = Reader(data)
            r.expectType(MessageType.SDS_NOTIFICATION)
            val type = DispositionNotification.of(r.u8())
            val time = r.dateTime(); val conversation = r.uuid(); val message = r.uuid()
            var app: Int? = null; var ext: ByteArray? = null; var sender: String? = null
            val seen = mutableSetOf<Int>()
            while (r.remaining > 0) {
                val iei = r.u8()
                if (!seen.add(iei)) throw McdataCodecError("duplicate IE 0x%02x".format(iei))
                when (iei) {
                    Iei.APPLICATION_ID -> app = r.u8()
                    Iei.EXTENDED_APPLICATION_ID -> ext = r.lengthValue()
                    Iei.SENDER -> sender = String(r.lengthValue())
                    else -> throw McdataCodecError("unknown IE 0x%02x in SDS NOTIFICATION".format(iei))
                }
            }
            return SdsNotification(type, time, conversation, message, app, ext, sender)
        }
    }
}

internal class Writer {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = out.write(v and 0xff)
    fun bytes(): ByteArray = out.toByteArray()

    /** Date and time (§15.2.8): 40-bit seconds since the epoch, big-endian. */
    fun dateTime(seconds: Long) { for (shift in 32 downTo 0 step 8) u8((seconds shr shift).toInt()) }

    fun uuid(id: UUID) {
        out.write(ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array())
    }

    /** TLV-E: IEI, 2-octet length, value. */
    fun tlvE(iei: Int, value: ByteArray) {
        require(value.size <= 0xffff) { "IE too long" }
        u8(iei); u8(value.size shr 8); u8(value.size); out.write(value)
    }
}

internal class Reader(private val data: ByteArray) {
    private var pos = 0
    val remaining get() = data.size - pos

    private fun need(n: Int) { if (remaining < n) throw McdataCodecError("truncated message") }
    fun u8(): Int { need(1); return data[pos++].toInt() and 0xff }

    fun expectType(type: Int) {
        val octet = u8()
        if (octet and 0xc0 != 0) throw McdataCodecError("protected or authenticated messages are not supported")
        if (octet != type) throw McdataCodecError("message type $octet, expected $type")
    }

    fun dateTime(): Long { var v = 0L; repeat(5) { v = (v shl 8) or u8().toLong() }; return v }

    fun uuid(): UUID {
        need(16)
        val b = ByteBuffer.wrap(data, pos, 16)
        pos += 16
        return UUID(b.long, b.long)
    }

    fun lengthValue(): ByteArray {
        val length = (u8() shl 8) or u8()
        need(length)
        return data.copyOfRange(pos, pos + length).also { pos += length }
    }
}
