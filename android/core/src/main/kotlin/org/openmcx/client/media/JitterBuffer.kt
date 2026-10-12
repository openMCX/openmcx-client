//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import java.util.TreeMap

/**
 * An adaptive playout buffer for one talk burst of 20 ms AMR-WB frames.
 *
 * Frames are keyed by RTP timestamp (one packet may carry several frames). Playout
 * starts once [target] frames are buffered; after that [pull] is called every 20 ms and
 * returns the next frame, or null for a missing one (the decoder conceals it). The
 * target depth follows the observed interarrival jitter (RFC 3550 §6.4.1), between
 * [minDepth] and [maxDepth] frames. Frames that arrive after their playout time are
 * dropped and counted. Safe for one pushing and one pulling thread.
 */
class JitterBuffer(
    private val minDepth: Int = 2,
    private val maxDepth: Int = 10,
    private val samplesPerFrame: Int = AmrWb.FRAME_SAMPLES,
    private val clockRate: Int = AmrWb.SAMPLE_RATE,
) {
    private val frames = TreeMap<Long, AmrWb.Frame>()
    private var next: Long? = null // RTP timestamp of the next frame to play (unwrapped)
    private var playing = false
    private var jitter = 0.0 // in timestamp units
    private var lastTransit: Long? = null
    private var highest: Long? = null

    var received = 0; private set
    var late = 0; private set
    var concealed = 0; private set

    /** The current target depth in frames. */
    val target: Int get() = (minDepth + Math.ceil(2 * jitter / samplesPerFrame).toInt()).coerceIn(minDepth, maxDepth)
    val depth: Int @Synchronized get() = frames.size

    /** Add the frames of one RTP packet, received at [arrivalMs]. */
    @Synchronized
    fun push(timestamp: Long, packetFrames: List<AmrWb.Frame>, arrivalMs: Long) {
        val ts = unwrap(timestamp)
        val arrival = arrivalMs * clockRate / 1000
        lastTransit?.let { jitter += (Math.abs(arrival - ts - it) - jitter) / 16.0 }
        lastTransit = arrival - ts
        packetFrames.forEachIndexed { i, frame ->
            val key = ts + i.toLong() * samplesPerFrame
            if (next != null && key < next!!) { late++; return@forEachIndexed }
            if (frame.ft == AmrWb.FT_NO_DATA) return@forEachIndexed
            frames[key] = frame
            received++
        }
        if (next == null) next = ts
    }

    /** The frame for the next 20 ms, null to conceal, or [Silence] before playout starts or after the burst drains. */
    @Synchronized
    fun pull(): Pull {
        if (!playing) {
            if (frames.size < target) return Pull.Silence
            playing = true
            next = frames.firstKey()
        }
        val key = next ?: return Pull.Silence
        if (frames.isEmpty()) { playing = false; next = null; return Pull.Silence }
        next = key + samplesPerFrame
        val frame = frames.remove(key)
        // Drop stale entries below the playout point, and shed depth when far above target.
        while (frames.isNotEmpty() && frames.firstKey() < next!!) { frames.pollFirstEntry(); late++ }
        if (frames.size > target + 2) { next = frames.firstKey() }
        return if (frame != null) Pull.Frame(frame) else { concealed++; Pull.Lost }
    }

    @Synchronized
    fun reset() {
        frames.clear(); next = null; playing = false; lastTransit = null; highest = null; jitter = 0.0
    }

    /** Extend 32-bit RTP timestamps to a monotonic 64-bit value. */
    private fun unwrap(ts: Long): Long {
        val h = highest ?: return ts.also { highest = it }
        val base = h and 0xffffffffL.inv()
        var candidate = base or ts
        if (candidate - h > 0x80000000L) candidate -= 0x100000000L
        else if (h - candidate > 0x80000000L) candidate += 0x100000000L
        if (candidate > h) highest = candidate
        return candidate
    }

    sealed interface Pull {
        data class Frame(val frame: AmrWb.Frame) : Pull
        data object Lost : Pull
        data object Silence : Pull
    }
}
