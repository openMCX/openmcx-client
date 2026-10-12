//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Speech input: 20 ms frames of 16 kHz mono PCM. */
interface AudioSource : AutoCloseable {
    /** Fill [frame] with the next 20 ms; false at the end of the source. May block for real time. */
    fun read(frame: ShortArray): Boolean
}

/** Speech output: 20 ms frames of 16 kHz mono PCM. */
interface AudioSink : AutoCloseable {
    fun write(frame: ShortArray)
}

/** Opens the platform's audio devices, or files in test builds. */
interface AudioDevices {
    /** The microphone; [duplex] when the other side is heard at the same time (echo cancellation matters). */
    fun source(duplex: Boolean = false): AudioSource
    fun sink(): AudioSink
}

/** Reads a 16 kHz mono 16-bit PCM WAV file (RIFF, format 1). */
class WavSource(data: ByteArray) : AudioSource {
    private val pcm: ShortArray
    private var position = 0

    init {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size >= 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WAVE") { "not a WAV file" }
        var offset = 12
        var samples: ShortArray? = null
        while (offset + 8 <= data.size) {
            val id = String(data, offset, 4)
            val size = b.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    require(b.getShort(body).toInt() == 1) { "WAV is not PCM" }
                    require(b.getShort(body + 2).toInt() == 1) { "WAV is not mono" }
                    require(b.getInt(body + 4) == AmrWb.SAMPLE_RATE) { "WAV is not 16 kHz" }
                    require(b.getShort(body + 14).toInt() == 16) { "WAV is not 16-bit" }
                }
                "data" -> {
                    val n = minOf(size, data.size - body) / 2
                    samples = ShortArray(n) { b.getShort(body + 2 * it) }
                }
            }
            offset = body + size + (size and 1)
        }
        pcm = samples ?: throw IllegalArgumentException("WAV without data")
    }

    override fun read(frame: ShortArray): Boolean {
        if (position >= pcm.size) return false
        for (i in frame.indices) frame[i] = if (position + i < pcm.size) pcm[position + i] else 0
        position += frame.size
        return true
    }

    override fun close() {}
}

/** Writes 16 kHz mono 16-bit PCM to a WAV file, fixing the header on close. */
class WavSink(private val file: File) : AudioSink {
    private val out = ByteArrayOutputStream()

    override fun write(frame: ShortArray) {
        val b = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        frame.forEach { b.putShort(it) }
        out.write(b.array())
    }

    override fun close() {
        val data = out.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(AmrWb.SAMPLE_RATE)
            putInt(AmrWb.SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(header.array()); it.write(data) }
    }
}
