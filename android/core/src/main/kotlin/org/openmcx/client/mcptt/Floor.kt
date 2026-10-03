package org.openmcx.client.mcptt

import org.openmcx.client.media.RtcpApp
import org.openmcx.client.media.RtcpAppError

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class FloorCodecError(message: String) : Exception(message)

/** Floor control message types (TS 24.380 §8.2.2), the low 4 bits of the APP subtype. */
object FloorType {
    const val REQUEST = 0
    const val GRANTED = 1
    const val TAKEN = 2
    const val DENY = 3
    const val RELEASE = 4
    const val IDLE = 5
    const val REVOKE = 6
    const val QUEUE_POSITION_REQUEST = 8
    const val QUEUE_POSITION_INFO = 9
    const val ACK = 10

    fun name(type: Int) = when (type) {
        REQUEST -> "Floor Request"; GRANTED -> "Floor Granted"; TAKEN -> "Floor Taken"; DENY -> "Floor Deny"
        RELEASE -> "Floor Release"; IDLE -> "Floor Idle"; REVOKE -> "Floor Revoke"
        QUEUE_POSITION_REQUEST -> "Floor Queue Position Request"; QUEUE_POSITION_INFO -> "Floor Queue Position Info"
        ACK -> "Floor Ack"; else -> "type $type"
    }
}

/** Floor control field IDs (TS 24.380 §8.2.3). */
object FloorField {
    const val PRIORITY = 0
    const val DURATION = 1
    const val REJECT_CAUSE = 2
    const val QUEUE_INFO = 3
    const val GRANTED_PARTY = 4
    const val PERMISSION_TO_REQUEST = 5
    const val USER_ID = 6
    const val SEQUENCE = 8
    const val SOURCE = 10
    const val MESSAGE_TYPE = 12
    const val FLOOR_INDICATOR = 13
    const val SSRC = 14
    const val LOCATION = 19
}

/** Floor Indicator bits (§8.2.3.15). */
object FloorIndicator {
    const val NORMAL = 0x8000
    const val BROADCAST = 0x4000
    const val SYSTEM = 0x2000
    const val EMERGENCY = 0x1000
    const val IMMINENT_PERIL = 0x0800
    const val QUEUEING = 0x0400
    const val DUAL_FLOOR = 0x0200
    const val TEMPORARY_GROUP = 0x0100
    const val MULTI_TALKER = 0x0080
}

/**
 * One floor control message: an RTCP APP packet (PT 204, name `MCPT`) whose subtype
 * carries the message type and the acknowledgement-required bit (TS 24.380 §8.1).
 */
data class FloorMessage(
    val type: Int,
    val ssrc: Long,
    val ackRequired: Boolean = false,
    val fields: List<Pair<Int, ByteArray>> = emptyList(),
) {
    fun field(id: Int): ByteArray? = fields.firstOrNull { it.first == id }?.second
    private fun u16(id: Int): Int? = field(id)?.takeIf { it.size >= 2 }?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }

    val priority: Int? get() = field(FloorField.PRIORITY)?.firstOrNull()?.toInt()?.and(0xff)
    val durationSeconds: Int? get() = u16(FloorField.DURATION)
    val rejectCause: Int? get() = u16(FloorField.REJECT_CAUSE)
    val rejectText: String? get() = field(FloorField.REJECT_CAUSE)?.takeIf { it.size > 2 }?.let { String(it, 2, it.size - 2) }
    val grantedParty: String? get() = field(FloorField.GRANTED_PARTY)?.let { String(it, Charsets.UTF_8) }
    val permissionToRequest: Int? get() = u16(FloorField.PERMISSION_TO_REQUEST)
    val sequence: Int? get() = u16(FloorField.SEQUENCE)
    val source: Int? get() = u16(FloorField.SOURCE)
    /** For a Floor Ack: the acknowledged message type. */
    val acknowledgedType: Int? get() = field(FloorField.MESSAGE_TYPE)?.firstOrNull()?.toInt()?.and(0x1f)
    val floorIndicator: Int? get() = u16(FloorField.FLOOR_INDICATOR)
    /** The SSRC field: the talker's media SSRC. */
    val mediaSsrc: Long? get() = field(FloorField.SSRC)?.takeIf { it.size >= 4 }?.let { ByteBuffer.wrap(it, 0, 4).int.toLong() and 0xffffffffL }

    fun encode(): ByteArray = RtcpApp((if (ackRequired) 0x10 else 0) or (type and 0x0f), ssrc, "MCPT", fields).encode()

    override fun toString(): String = "${FloorType.name(type)}${if (ackRequired) " (ack)" else ""} ssrc=%08x %s".format(ssrc,
        fields.joinToString(" ") { (id, v) -> "$id=" + v.joinToString("") { "%02x".format(it) } })

    override fun equals(other: Any?) = other is FloorMessage && type == other.type && ssrc == other.ssrc &&
        ackRequired == other.ackRequired && fields.size == other.fields.size &&
        fields.zip(other.fields).all { (a, b) -> a.first == b.first && a.second.contentEquals(b.second) }
    override fun hashCode() = type * 31 + ssrc.hashCode()

    companion object {
        val NAME = "MCPT".toByteArray()

        fun decode(data: ByteArray, length: Int = data.size): FloorMessage {
            val app = try { RtcpApp.decode(data, length) } catch (e: RtcpAppError) { throw FloorCodecError(e.message ?: "bad packet") }
            if (app.name != "MCPT") throw FloorCodecError("APP name is not MCPT")
            return FloorMessage(app.subtype and 0x0f, app.ssrc, app.subtype and 0x10 != 0, app.fields)
        }

        fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        /** Floor Request (§8.2.4), with the floor indicator and, when set, the priority. */
        fun request(ssrc: Long, priority: Int? = null, indicator: Int = FloorIndicator.NORMAL) = FloorMessage(FloorType.REQUEST, ssrc,
            fields = listOfNotNull(priority?.let { FloorField.PRIORITY to byteArrayOf(it.toByte(), 0) }, FloorField.FLOOR_INDICATOR to u16(indicator)))

        /** Floor Release (§8.2.8), optionally asking the server to acknowledge it. */
        fun release(ssrc: Long, ackRequired: Boolean = false, indicator: Int = FloorIndicator.NORMAL) =
            FloorMessage(FloorType.RELEASE, ssrc, ackRequired, listOf(FloorField.FLOOR_INDICATOR to u16(indicator)))

        /** Floor Ack (§8.2.12) for [type], from the floor participant (source 0). */
        fun ack(ssrc: Long, type: Int) = FloorMessage(FloorType.ACK, ssrc,
            fields = listOf(FloorField.SOURCE to u16(0), FloorField.MESSAGE_TYPE to byteArrayOf(type.toByte(), 0)))
    }
}
