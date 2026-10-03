package org.openmcx.client.app.media

import android.media.MediaCodec
import android.media.MediaFormat
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.SpeechDecoder
import org.openmcx.client.media.SpeechEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AMR-WB through the platform codecs (`c2.android.amrwb.encoder` / `.decoder` on AOSP),
 * used synchronously, one 20 ms frame in and one out. Both sides use the RFC 4867 §5.3
 * storage format: a header octet and the speech bits.
 */
object MediaCodecAmrWb : SpeechCodecFactory {
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AMR_WB
    private const val TIMEOUT_US = 20_000L

    /** Bit rates of AMR-WB modes 0–8 (TS 26.171). */
    private val BITRATES = intArrayOf(6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850)

    override fun encoder(mode: Int): SpeechEncoder = Encoder(mode)
    override fun decoder(): SpeechDecoder = Decoder()

    private fun format(): MediaFormat = MediaFormat.createAudioFormat(MIME, AmrWb.SAMPLE_RATE, 1)

    private abstract class Codec(encoder: Boolean, configure: (MediaCodec) -> Unit) : AutoCloseable {
        protected val codec: MediaCodec =
            (if (encoder) MediaCodec.createEncoderByType(MIME) else MediaCodec.createDecoderByType(MIME)).also {
                configure(it)
                it.start()
            }
        private val info = MediaCodec.BufferInfo()
        private var presentationUs = 0L

        /** Queue one input buffer and wait for the matching output. */
        protected fun process(input: ByteArray): ByteArray? {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            check(index >= 0) { "codec input not available" }
            codec.getInputBuffer(index)!!.apply { clear(); put(input) }
            codec.queueInputBuffer(index, 0, input.size, presentationUs, 0)
            presentationUs += 20_000
            repeat(10) {
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        val data = ByteArray(info.size)
                        buffer.position(info.offset); buffer.get(data)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        codec.releaseOutputBuffer(out, false)
                        if (!config && data.isNotEmpty()) return data
                    }
                    out == MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                }
            }
            return null
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private class Encoder(mode: Int) : Codec(true, { c ->
        c.configure(format().apply { setInteger(MediaFormat.KEY_BIT_RATE, BITRATES[mode.coerceIn(0, 8)]) },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }), SpeechEncoder {
        override fun encode(pcm: ShortArray): AmrWb.Frame {
            require(pcm.size == AmrWb.FRAME_SAMPLES)
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            val out = process(bytes.array()) ?: return AmrWb.Frame(AmrWb.FT_NO_DATA, false, ByteArray(0))
            return AmrWb.Frame.fromStorage(out)
        }
    }

    private class Decoder : Codec(false, { c -> c.configure(format(), null, null, 0) }), SpeechDecoder {
        override fun decode(frame: AmrWb.Frame?): ShortArray {
            // A lost frame goes to the decoder as NO_DATA, which it conceals.
            val input = (frame ?: AmrWb.Frame(AmrWb.FT_NO_DATA, false, ByteArray(0))).storage()
            val out = process(input) ?: return ShortArray(AmrWb.FRAME_SAMPLES)
            val samples = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(AmrWb.FRAME_SAMPLES) { if (it < samples.limit()) samples.get(it) else 0 }
        }
    }
}
