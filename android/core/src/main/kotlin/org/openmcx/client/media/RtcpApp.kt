package org.openmcx.client.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RtcpAppError(message: String) : Exception(message)

/**
 * An RTCP APP packet (RFC 3550 §6.7) carrying TS 24.380-style fields: an ID, a length and a
 * value padded to 32 bits; IDs from 192 have a two-octet length. MCPTT floor control (`MCPT`)
 * and MCVideo transmission control (`MCV0`–`MCV2`) share this layout.
 */
class RtcpApp(val subtype: Int, val ssrc: Long, val name: String, val fields: List<Pair<Int, ByteArray>>) {
    fun encode(): ByteArray {
        require(name.length == 4) { "APP name must be 4 characters" }
        val body = ByteArrayOutputStream()
        for ((id, value) in fields) {
            if (id >= 192) {
                body.write(id); body.write(value.size shr 8); body.write(value.size and 0xff); body.write(value)
                repeat(pad(3 + value.size)) { body.write(0) }
            } else {
                require(value.size <= 255) { "field $id too long" }
                body.write(id); body.write(value.size); body.write(value)
                repeat(pad(2 + value.size)) { body.write(0) }
            }
        }
        val fieldsBytes = body.toByteArray()
        val total = 12 + fieldsBytes.size
        return ByteBuffer.allocate(total).apply {
            put((0x80 or (subtype and 0x1f)).toByte())
            put(204.toByte())
            putShort((total / 4 - 1).toShort())
            putInt(ssrc.toInt())
            put(name.toByteArray(Charsets.US_ASCII))
            put(fieldsBytes)
        }.array()
    }

    companion object {
        private fun pad(n: Int) = (4 - n % 4) % 4

        fun decode(data: ByteArray, length: Int = data.size): RtcpApp {
            if (length < 12 || length % 4 != 0) throw RtcpAppError("bad APP packet length $length")
            val b0 = data[0].toInt() and 0xff
            if (b0 and 0xc0 != 0x80) throw RtcpAppError("not RTCP version 2")
            if (b0 and 0x20 != 0) throw RtcpAppError("padding not allowed")
            if (data[1].toInt() and 0xff != 204) throw RtcpAppError("not an APP packet")
            val words = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
            if ((words + 1) * 4 != length) throw RtcpAppError("length field ${words + 1} words, packet $length octets")
            val ssrc = ByteBuffer.wrap(data, 4, 4).int.toLong() and 0xffffffffL
            val name = String(data, 8, 4, Charsets.US_ASCII)
            val fields = mutableListOf<Pair<Int, ByteArray>>()
            var pos = 12
            while (pos < length) {
                val id = data[pos].toInt() and 0xff
                val (len, header) = if (id >= 192) {
                    if (pos + 3 > length) throw RtcpAppError("truncated field")
                    (((data[pos + 1].toInt() and 0xff) shl 8) or (data[pos + 2].toInt() and 0xff)) to 3
                } else {
                    if (pos + 2 > length) throw RtcpAppError("truncated field")
                    (data[pos + 1].toInt() and 0xff) to 2
                }
                if (pos + header + len > length) throw RtcpAppError("field $id overruns the packet")
                fields += id to data.copyOfRange(pos + header, pos + header + len)
                pos += header + len + pad(header + len)
            }
            return RtcpApp(b0 and 0x1f, ssrc, name, fields)
        }
    }
}
