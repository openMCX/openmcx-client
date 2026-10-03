package org.openmcx.client.app.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.AudioSink
import org.openmcx.client.media.AudioSource
import org.openmcx.client.media.SpeechLevel
import java.io.File

/** The microphone and the voice-call output stream. */
object PlatformAudio : AudioDevices {
    private val bytesPerFrame = AmrWb.FRAME_SAMPLES * 2

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before a talk burst starts
    override fun source(duplex: Boolean): AudioSource {
        val min = AudioRecord.getMinBufferSize(AmrWb.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // A talk burst is half duplex: the plain microphone, with our own level control, since on some phones (a
        // Xiaomi here) the voice-call source's processing takes speech down to the noise floor, some 20 dB below
        // the plain microphone. A duplex call hears the other side meanwhile, so it needs the voice-call source's
        // echo cancellation; the level control then makes up for its lower level with more gain.
        val record = AudioRecord(if (duplex) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            AmrWb.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, bytesPerFrame * 4))
        val level = SpeechLevel(maxGain = if (duplex) 16.0 else 8.0)
        record.startRecording()
        return object : AudioSource {
            override fun read(frame: ShortArray): Boolean {
                var filled = 0
                while (filled < frame.size) {
                    val n = record.read(frame, filled, frame.size - filled)
                    if (n <= 0) return false
                    filled += n
                }
                level.process(frame)
                return true
            }

            override fun close() {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    override fun sink(): AudioSink {
        val min = AudioTrack.getMinBufferSize(AmrWb.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(AmrWb.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, bytesPerFrame * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        return object : AudioSink {
            override fun write(frame: ShortArray) { track.write(frame, 0, frame.size) }
            override fun close() {
                runCatching { track.stop() }
                track.release()
            }
        }
    }
}

/**
 * Test audio: the next talk burst plays [nextSource] (a WAV file) instead of the microphone,
 * and received speech is written to WAV files in [receivedDir]. Used by the lab tests,
 * where the devices have no microphone. With [play], received speech is also played through
 * [fallback], so a debug build on a real phone can still be heard.
 */
class FileAudio(private val receivedDir: File, private val fallback: AudioDevices, private val play: Boolean = false) : AudioDevices {
    @Volatile var nextSource: File? = null
    @Volatile var lastReceived: File? = null

    override fun source(duplex: Boolean): AudioSource = nextSource?.let { file ->
        nextSource = null
        val wav = org.openmcx.client.media.WavSource(file.readBytes())
        // Pace the file in real time, as a microphone would deliver it.
        object : AudioSource {
            private var start = 0L
            private var frames = 0
            override fun read(frame: ShortArray): Boolean {
                if (start == 0L) start = System.nanoTime()
                val due = start + frames * 20_000_000L
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                frames++
                return wav.read(frame)
            }
            override fun close() = wav.close()
        }
    } ?: fallback.source(duplex)

    override fun sink(): AudioSink {
        receivedDir.mkdirs()
        val file = File(receivedDir, "received-${System.currentTimeMillis()}.wav")
        lastReceived = file
        val wav = org.openmcx.client.media.WavSink(file)
        val speaker = if (play) runCatching { fallback.sink() }.getOrNull() else null
        speaker ?: return wav
        return object : AudioSink {
            override fun write(frame: ShortArray) { wav.write(frame); speaker.write(frame) }
            override fun close() { wav.close(); speaker.close() }
        }
    }
}
