//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

/**
 * AMR-WB frames and their RTP payload format (RFC 4867).
 *
 * A frame is held in the storage format of RFC 4867 §5.3: one header octet
 * (`0 FT(4) Q 0 0`) followed by the speech bits, padded to an octet. This is what
 * the Android AMR-WB encoder produces and the decoder takes, so frames pass
 * between codec and network without conversion beyond the payload packing.
 */
object AmrWb {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 320 // 20 ms
    const val FT_SID = 9
    const val FT_NO_DATA = 15
    /** CMR 15: no mode request (§4.3.1). */
    const val CMR_NONE = 15

    /** Speech bits for frame types 0–8 (modes 6.60 … 23.85 kbit/s) and 9 (SID); TS 26.201 table 2. */
    private val BITS = intArrayOf(132, 177, 253, 285, 317, 365, 397, 461, 477, 40)

    fun frameBits(ft: Int): Int = if (ft in BITS.indices) BITS[ft] else 0

    /**
     * TS 26.179 §4.1.3 with RFC 4867 §8.1: the mode to send with when [wanted] is configured: [wanted] if the peer's
     * `mode-set` allows it (or the peer gives none), else the highest allowed mode below it, else the lowest allowed.
     */
    fun sendMode(wanted: Int, modeSet: List<Int>): Int = when {
        modeSet.isEmpty() || wanted in modeSet -> wanted
        else -> modeSet.filter { it < wanted }.maxOrNull() ?: modeSet.min()
    }

    /** RFC 4867 §8.1: the `mode-set` of an fmtp line (modes 0–8); empty when absent. */
    fun modeSet(value: String?): List<Int> = value?.split(',')?.map { m ->
        m.trim().toIntOrNull()?.takeIf { it in 0..8 } ?: throw IllegalArgumentException("mode-set $value")
    }?.distinct() ?: emptyList()

    /** A frame: frame type, quality bit and speech bits (in [frameBits] bits, MSB first). */
    data class Frame(val ft: Int, val quality: Boolean, val speech: ByteArray) {
        /** The storage-format octets of this frame. */
        fun storage(): ByteArray = byteArrayOf(((ft shl 3) or (if (quality) 0x04 else 0)).toByte()) + speech

        override fun equals(other: Any?) = other is Frame && ft == other.ft && quality == other.quality && speech.contentEquals(other.speech)
        override fun hashCode() = ft * 31 + speech.contentHashCode()

        companion object {
            /** One frame from storage-format octets starting at [offset]. */
            fun fromStorage(data: ByteArray, offset: Int = 0): Frame {
                val header = data[offset].toInt()
                val ft = (header shr 3) and 0x0f
                val octets = (frameBits(ft) + 7) / 8
                require(offset + 1 + octets <= data.size) { "truncated AMR-WB frame" }
                return Frame(ft, header and 0x04 != 0, data.copyOfRange(offset + 1, offset + 1 + octets))
            }

            /** Every frame in a storage-format stream (without the `#!AMR-WB\n` magic). */
            fun splitStorage(data: ByteArray): List<Frame> {
                val frames = mutableListOf<Frame>()
                var offset = 0
                while (offset < data.size) {
                    val frame = fromStorage(data, offset)
                    frames += frame
                    offset += 1 + frame.speech.size
                }
                return frames
            }
        }
    }

    /** Pack [frames] into one RTP payload (§4.3 bandwidth-efficient, or §4.4 octet-aligned). */
    fun pack(frames: List<Frame>, octetAligned: Boolean, cmr: Int = CMR_NONE): ByteArray {
        require(frames.isNotEmpty())
        val w = BitWriter()
        if (octetAligned) {
            w.write(cmr, 4); w.write(0, 4)
            frames.forEachIndexed { i, f -> w.write(if (i < frames.lastIndex) 1 else 0, 1); w.write(f.ft, 4); w.write(if (f.quality) 1 else 0, 1); w.write(0, 2) }
            frames.forEach { f -> w.writeBits(f.speech, frameBits(f.ft)); w.align() }
        } else {
            w.write(cmr, 4)
            frames.forEachIndexed { i, f -> w.write(if (i < frames.lastIndex) 1 else 0, 1); w.write(f.ft, 4); w.write(if (f.quality) 1 else 0, 1) }
            frames.forEach { f -> w.writeBits(f.speech, frameBits(f.ft)) }
            w.align()
        }
        return w.toByteArray()
    }

    /** Unpack an RTP payload into its frames. */
    fun unpack(payload: ByteArray, octetAligned: Boolean): List<Frame> {
        val r = BitReader(payload)
        r.read(4) // CMR
        if (octetAligned) r.read(4)
        val toc = mutableListOf<Pair<Int, Boolean>>()
        do {
            val follow = r.read(1) == 1
            val ft = r.read(4)
            val q = r.read(1) == 1
            if (octetAligned) r.read(2)
            toc += ft to q
        } while (follow)
        return toc.map { (ft, q) ->
            if (ft > FT_SID && ft != FT_NO_DATA) throw IllegalArgumentException("invalid AMR-WB frame type $ft")
            val speech = r.readBits(frameBits(ft))
            if (octetAligned) r.align()
            Frame(ft, q, speech)
        }
    }

    private class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) bit((value shr i) and 1)
        }

        fun writeBits(data: ByteArray, bits: Int) {
            for (i in 0 until bits) bit((data[i / 8].toInt() shr (7 - i % 8)) and 1)
        }

        private fun bit(b: Int) {
            current = (current shl 1) or b
            if (++count == 8) { out.write(current); current = 0; count = 0 }
        }

        fun align() { while (count != 0) bit(0) }
        fun toByteArray(): ByteArray { align(); return out.toByteArray() }
    }

    private class BitReader(private val data: ByteArray) {
        private var position = 0

        fun read(bits: Int): Int {
            var value = 0
            repeat(bits) { value = (value shl 1) or bit() }
            return value
        }

        fun readBits(bits: Int): ByteArray {
            val out = ByteArray((bits + 7) / 8)
            for (i in 0 until bits) if (bit() == 1) out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
            return out
        }

        private fun bit(): Int {
            require(position < data.size * 8) { "AMR-WB payload too short" }
            val b = (data[position / 8].toInt() shr (7 - position % 8)) and 1
            position++
            return b
        }

        fun align() { position = (position + 7) / 8 * 8 }
    }
}
