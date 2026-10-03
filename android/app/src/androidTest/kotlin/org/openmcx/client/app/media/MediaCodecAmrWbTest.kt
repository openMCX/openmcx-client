package org.openmcx.client.app.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openmcx.client.media.AmrWb
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class MediaCodecAmrWbTest {
    private fun tone(frame: Int) = ShortArray(AmrWb.FRAME_SAMPLES) {
        val t = (frame * AmrWb.FRAME_SAMPLES + it).toDouble() / AmrWb.SAMPLE_RATE
        (8000 * sin(2 * PI * 440 * t)).toInt().toShort()
    }

    private fun rms(pcm: ShortArray) = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    @Test
    fun encodesAtTheRequestedModeAndDecodesBack() {
        val encoder = MediaCodecAmrWb.encoder(2)
        val decoder = MediaCodecAmrWb.decoder()
        val frames = (0 until 50).map { encoder.encode(tone(it)) }.filter { it.ft != AmrWb.FT_NO_DATA }
        assertTrue("encoder produced ${frames.size} frames", frames.size >= 45)
        frames.forEach {
            assertEquals(2, it.ft)
            assertEquals((AmrWb.frameBits(2) + 7) / 8, it.speech.size)
        }
        val decoded = frames.map { decoder.decode(it) }
        val level = rms(decoded.drop(10).flatMap { it.toList() }.toShortArray())
        assertTrue("decoded tone level $level", level > 2000)
        encoder.close(); decoder.close()
    }

    @Test
    fun rtpPayloadsRoundTripThroughTheDecoder() {
        val encoder = MediaCodecAmrWb.encoder(8)
        val decoder = MediaCodecAmrWb.decoder()
        repeat(20) {
            val frame = encoder.encode(tone(it))
            if (frame.ft == AmrWb.FT_NO_DATA) return@repeat
            val back = AmrWb.unpack(AmrWb.pack(listOf(frame), octetAligned = false), octetAligned = false).single()
            assertEquals(frame, back)
            assertEquals(AmrWb.FRAME_SAMPLES, decoder.decode(back).size)
        }
        assertEquals(AmrWb.FRAME_SAMPLES, decoder.decode(null).size)
        encoder.close(); decoder.close()
    }
}
