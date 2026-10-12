package org.openmcx.client.mcvideo

import java.io.ByteArrayOutputStream

class H264Error(message: String) : Exception(message)

/** One RTP payload of a picture, and whether it ends the picture (the RTP marker). */
class H264Payload(val bytes: ByteArray, val marker: Boolean)

/**
 * H.264 over RTP (RFC 6184) in packetization mode 1, as the OpenMCX MCVideo server relays it:
 * a NAL unit that fits is sent alone (§5.6), a larger one in FU-A fragments (§5.8); the marker
 * is set on the last packet of each access unit.
 */
object H264Rtp {
    const val MAX_PAYLOAD = 1200
    const val FU_A = 28
    const val STAP_A = 24

    fun nalType(nal: ByteArray) = nal[0].toInt() and 0x1f

    /** An IDR picture, or parameter sets that start one. */
    fun isKeyFrame(nals: List<ByteArray>) = nals.any { nalType(it) == 5 }

    /** Split an Annex B byte stream (as MediaCodec produces) into NAL units without start codes. */
    fun splitAnnexB(stream: ByteArray, offset: Int = 0, length: Int = stream.size - offset): List<ByteArray> {
        val end = offset + length
        val starts = mutableListOf<Pair<Int, Int>>() // (start of NAL, start of its start code)
        var i = offset
        while (i + 2 < end) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0) {
                if (stream[i + 2].toInt() == 1) { starts += (i + 3) to i; i += 3; continue }
                if (i + 3 < end && stream[i + 2].toInt() == 0 && stream[i + 3].toInt() == 1) { starts += (i + 4) to i; i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return if (length > 0) listOf(stream.copyOfRange(offset, end)) else emptyList()
        return starts.mapIndexed { n, (start, _) ->
            val stop = if (n + 1 < starts.size) starts[n + 1].second else end
            stream.copyOfRange(start, stop)
        }.filter { it.isNotEmpty() }
    }

    /** NAL units as an Annex B byte stream (4-byte start codes), as MediaCodec decoders take them. */
    fun joinAnnexB(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(nal) }
        return out.toByteArray()
    }

    /** The RTP payloads of one access unit. */
    fun packetize(nals: List<ByteArray>, maxPayload: Int = MAX_PAYLOAD): List<H264Payload> {
        val out = mutableListOf<H264Payload>()
        for ((n, nal) in nals.withIndex()) {
            val lastNal = n == nals.lastIndex
            if (nal.isEmpty()) continue
            if (nal.size <= maxPayload) { out += H264Payload(nal, lastNal); continue }
            val indicator = (nal[0].toInt() and 0xe0) or FU_A
            val type = nal[0].toInt() and 0x1f
            var pos = 1
            val chunk = maxPayload - 2
            while (pos < nal.size) {
                val size = minOf(chunk, nal.size - pos)
                val start = pos == 1
                val end = pos + size == nal.size
                val header = (if (start) 0x80 else 0) or (if (end) 0x40 else 0) or type
                out += H264Payload(byteArrayOf(indicator.toByte(), header.toByte()) + nal.copyOfRange(pos, pos + size), lastNal && end)
                pos += size
            }
        }
        return out
    }
}

/**
 * Reassembles access units from RTP payloads (Single NAL unit, STAP-A, FU-A). A lost packet
 * (a gap in the sequence numbers) discards the access unit it belongs to; [lost] counts them.
 */
class H264Depacketizer {
    private var expectedSeq: Int? = null
    private var timestamp: Long? = null
    private val nals = mutableListOf<ByteArray>()
    private var fragment: ByteArrayOutputStream? = null
    private var broken = false
    var lost = 0
        private set

    /** Feed one packet; returns the completed access units (usually none or one). */
    fun push(sequence: Int, timestamp: Long, marker: Boolean, payload: ByteArray): List<List<ByteArray>> {
        val done = mutableListOf<List<ByteArray>>()
        val gap = expectedSeq.let { it != null && sequence != it }
        expectedSeq = (sequence + 1) and 0xffff
        // A new timestamp before the marker: the previous access unit lost its last packet(s).
        if (this.timestamp != null && this.timestamp != timestamp) { broken = true; finish()?.let { done += it } }
        // Any gap may also have taken this picture's first packets (a picture can have several slices, each a
        // whole NAL unit): which picture the lost packets belonged to cannot be known, so this one is damaged too.
        if (gap) { broken = true; fragment = null }
        this.timestamp = timestamp
        if (payload.isEmpty()) throw H264Error("empty H.264 payload")
        when (val type = payload[0].toInt() and 0x1f) {
            in 1..23 -> nals += payload
            H264Rtp.STAP_A -> {
                var pos = 1
                while (pos < payload.size) {
                    if (pos + 2 > payload.size) throw H264Error("truncated STAP-A")
                    val size = ((payload[pos].toInt() and 0xff) shl 8) or (payload[pos + 1].toInt() and 0xff)
                    pos += 2
                    if (size == 0 || pos + size > payload.size) throw H264Error("STAP-A size")
                    nals += payload.copyOfRange(pos, pos + size); pos += size
                }
            }
            H264Rtp.FU_A -> {
                if (payload.size < 2) throw H264Error("truncated FU-A")
                val header = payload[1].toInt() and 0xff
                val start = header and 0x80 != 0
                val end = header and 0x40 != 0
                if (start && end) throw H264Error("FU-A with both start and end")
                if (start) {
                    fragment = ByteArrayOutputStream().apply { write((payload[0].toInt() and 0xe0) or (header and 0x1f)) }
                }
                val f = fragment
                if (f == null) broken = true
                else {
                    f.write(payload, 2, payload.size - 2)
                    if (end) { nals += f.toByteArray(); fragment = null }
                }
            }
            else -> throw H264Error("unsupported NAL unit type $type")
        }
        if (marker) finish()?.let { done += it }
        return done
    }

    private fun finish(): List<ByteArray>? {
        val unit = nals.toList()
        val ok = !broken && fragment == null && unit.isNotEmpty()
        if (!ok && (unit.isNotEmpty() || broken)) lost++
        nals.clear(); fragment = null; broken = false; timestamp = null
        return if (ok) unit else null
    }

    /** A new sender: forget everything (new SSRC and sequence space). */
    fun reset() { expectedSeq = null; timestamp = null; nals.clear(); fragment = null; broken = false }
}
