//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import java.nio.Buffer
import java.nio.ByteBuffer

/** An RTP packet (RFC 3550 §5.1), without header extensions on send. */
data class RtpPacket(
    val payloadType: Int,
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray,
    val marker: Boolean = false,
    val csrc: List<Long> = emptyList(),
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(12 + 4 * csrc.size + payload.size)
        buffer.put((0x80 or csrc.size).toByte())
        buffer.put(((if (marker) 0x80 else 0) or (payloadType and 0x7f)).toByte())
        buffer.putShort(sequence.toShort())
        buffer.putInt(timestamp.toInt())
        buffer.putInt(ssrc.toInt())
        csrc.forEach { buffer.putInt(it.toInt()) }
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?) = other is RtpPacket && payloadType == other.payloadType && sequence == other.sequence &&
        timestamp == other.timestamp && ssrc == other.ssrc && marker == other.marker && csrc == other.csrc &&
        payload.contentEquals(other.payload)
    override fun hashCode() = (sequence * 31 + ssrc.hashCode()) * 31 + payload.contentHashCode()

    companion object {
        /** Parse an RTP packet, or null when [data] is not RTP version 2. */
        fun parse(data: ByteArray, length: Int = data.size): RtpPacket? {
            if (length < 12 || (data[0].toInt() and 0xc0) != 0x80) return null
            val buffer = ByteBuffer.wrap(data, 0, length)
            val b0 = buffer.get().toInt() and 0xff
            val b1 = buffer.get().toInt() and 0xff
            val sequence = buffer.short.toInt() and 0xffff
            val timestamp = buffer.int.toLong() and 0xffffffffL
            val ssrc = buffer.int.toLong() and 0xffffffffL
            val cc = b0 and 0x0f
            if (length < 12 + 4 * cc) return null
            val csrc = List(cc) { buffer.int.toLong() and 0xffffffffL }
            if (b0 and 0x10 != 0) { // header extension (§5.3.1)
                if (buffer.remaining() < 4) return null
                buffer.short
                val words = buffer.short.toInt() and 0xffff
                if (buffer.remaining() < 4 * words) return null
                // Through Buffer: the covariant ByteBuffer.position(int) of JDK 9 is not in the Android SDK.
                (buffer as Buffer).position(buffer.position() + 4 * words)
            }
            var end = length
            if (b0 and 0x20 != 0) end -= data[length - 1].toInt() and 0xff // padding
            if (end < buffer.position()) return null
            return RtpPacket(b1 and 0x7f, sequence, timestamp, ssrc, data.copyOfRange(buffer.position(), end), b1 and 0x80 != 0, csrc)
        }

        /** True if [data] looks like RTCP (RFC 5761 §4: payload types 200–204 in the second octet). */
        fun isRtcp(data: ByteArray, length: Int = data.size): Boolean =
            length >= 8 && (data[0].toInt() and 0xc0) == 0x80 && (data[1].toInt() and 0xff) in 192..223
    }
}
