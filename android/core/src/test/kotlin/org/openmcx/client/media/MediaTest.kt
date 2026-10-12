//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MediaTest {
    private fun frame(ft: Int, seed: Int): AmrWb.Frame {
        val bits = AmrWb.frameBits(ft)
        val speech = Random(seed).nextBytes((bits + 7) / 8)
        // Zero the padding bits after the last speech bit, as the storage format requires.
        if (bits % 8 != 0) speech[speech.lastIndex] = (speech.last().toInt() and (0xff shl (8 - bits % 8))).toByte()
        return AmrWb.Frame(ft, true, speech)
    }

    @Test
    fun rtpRoundTrip() {
        val p = RtpPacket(97, 65535, 0xfffffff0L, 0xdeadbeefL, byteArrayOf(1, 2, 3), marker = true)
        val bytes = p.encode()
        assertEquals(0x80, bytes[0].toInt() and 0xff)
        assertEquals(0x80 or 97, bytes[1].toInt() and 0xff)
        assertEquals(p, RtpPacket.parse(bytes))
    }

    @Test
    fun rtpRejectsOtherVersionsAndSpotsRtcp() {
        assertNull(RtpPacket.parse(ByteArray(12)))
        assertTrue(RtpPacket.isRtcp(byteArrayOf(0x80.toByte(), 204.toByte(), 0, 2, 0, 0, 0, 1)))
    }

    /** RFC 4867 §4.3: one 12.65 kbit/s frame is 4 + 6 + 253 bits = 33 octets. */
    @Test
    fun bandwidthEfficientSizesAndHeader() {
        val f = frame(2, 1)
        val payload = AmrWb.pack(listOf(f), octetAligned = false)
        assertEquals((4 + 6 + 253 + 7) / 8, payload.size)
        // CMR=15, F=0, FT=2, Q=1: 1111 0 0010 1 …
        assertEquals(0xf1, payload[0].toInt() and 0xff)
        assertEquals(0x40, payload[1].toInt() and 0xc0)
        assertEquals(listOf(f), AmrWb.unpack(payload, octetAligned = false))
    }

    /** RFC 4867 §4.4: CMR octet, one ToC octet per frame, then each frame octet aligned. */
    @Test
    fun octetAlignedLayout() {
        val frames = listOf(frame(8, 2), frame(0, 3), frame(AmrWb.FT_SID, 4))
        val payload = AmrWb.pack(frames, octetAligned = true)
        assertEquals(0xf0, payload[0].toInt() and 0xff)
        assertEquals((1 shl 7) or (8 shl 3) or (1 shl 2), payload[1].toInt() and 0xff)
        assertEquals(1 + 3 + 60 + 17 + 5, payload.size)
        assertEquals(frames, AmrWb.unpack(payload, octetAligned = true))
    }

    @Test
    fun storageFormatRoundTrip() {
        val frames = listOf(frame(2, 5), frame(AmrWb.FT_SID, 6))
        val stream = frames.flatMap { it.storage().toList() }.toByteArray()
        assertEquals(frames, AmrWb.Frame.splitStorage(stream))
        assertArrayEquals(frames[0].storage(), AmrWb.Frame.fromStorage(stream).storage())
    }
}

class WavTest {
    @org.junit.Test
    fun sinkThenSourceRoundTrip() {
        val file = java.io.File.createTempFile("mcx", ".wav")
        val frames = List(3) { n -> ShortArray(AmrWb.FRAME_SAMPLES) { (it * n).toShort() } }
        WavSink(file).use { sink -> frames.forEach(sink::write) }
        val source = WavSource(file.readBytes())
        val buffer = ShortArray(AmrWb.FRAME_SAMPLES)
        frames.forEach { expected -> assertTrue(source.read(buffer)); org.junit.Assert.assertArrayEquals(expected, buffer) }
        assertTrue(!source.read(buffer))
        file.delete()
    }
}
