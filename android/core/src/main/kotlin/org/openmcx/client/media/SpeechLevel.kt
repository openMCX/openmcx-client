//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import kotlin.math.sqrt

/**
 * Automatic level control for a talk burst's microphone audio, frame by frame (20 ms): brings speech towards
 * [target] RMS, with at most [maxGain]. The gain rises slowly (about 1 dB per frame) and only on frames well
 * above the background noise, so pauses do not pump the noise up; it falls at once when speech is too loud, and
 * a limiter keeps every sample in range. Phones' microphones and their voice processing differ a lot in level:
 * this makes a talker equally audible from any of them.
 */
class SpeechLevel(private val target: Double = 3000.0, private val maxGain: Double = 8.0) {
    /** The gain applied to the last frame. */
    var gain = 1.0
        private set
    /** The background noise level (RMS), tracked from the quietest frames. */
    var noise = 0.0
        private set

    fun process(frame: ShortArray) {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size.coerceAtLeast(1))
        // Noise floor: follow quieter frames at once, louder ones slowly.
        noise = if (noise == 0.0 || rms < noise) rms else noise * 1.002 + 0.01
        val speech = rms > 3 * noise && rms > 30
        if (speech) {
            val wanted = (target / rms).coerceIn(0.1, maxGain)
            gain = if (wanted < gain) wanted else minOf(wanted, gain * 1.12)
        }
        if (gain == 1.0) return
        for (i in frame.indices) {
            val v = frame[i] * gain
            // Soft limit above 24000, hard stop at full scale.
            val limited = if (v > 24000) 24000 + (v - 24000) / 4 else if (v < -24000) -24000 + (v + 24000) / 4 else v
            frame[i] = limited.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }
}
