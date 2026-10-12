//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechLevelTest {
    private fun tone(amplitude: Double, n: Int, start: Int = 0) = ShortArray(320) { i -> (amplitude * sin(2 * PI * 300 * (start + i) / 16000.0)).toInt().toShort() }
    private fun rms(f: ShortArray) = sqrt(f.sumOf { it.toDouble() * it } / f.size)
    private fun noise(level: Int, seed: Int) = java.util.Random(seed.toLong()).let { r -> ShortArray(320) { (r.nextGaussian() * level).toInt().toShort() } }

    @Test fun quietSpeechIsBroughtUp() {
        val agc = SpeechLevel()
        repeat(50) { agc.process(noise(10, it)) } // a moment of background first
        var last = 0.0
        repeat(100) { i -> val f = tone(400.0, 320, i * 320); agc.process(f); last = rms(f) }
        assertTrue("rms $last", last in 2200.0..3300.0) // 400 × 0.707 ≈ 283 RMS raised about tenfold, capped at 8×
    }

    @Test fun loudSpeechIsNotClipped() {
        val agc = SpeechLevel()
        repeat(20) { i -> val f = tone(30000.0, 320, i * 320); agc.process(f); assertTrue(f.all { it in -32767..32767 }); assertTrue(agc.gain <= 1.0) }
    }

    @Test fun pausesDoNotPumpTheNoise() {
        val agc = SpeechLevel()
        repeat(200) { agc.process(noise(40, it)) }
        assertTrue("gain ${agc.gain}", agc.gain == 1.0) // background alone never raises the gain
    }
}
