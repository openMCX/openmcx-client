package org.openmcx.client.media

/** Encodes 20 ms of 16 kHz mono PCM ([AmrWb.FRAME_SAMPLES] samples) into one AMR-WB frame. */
interface SpeechEncoder : AutoCloseable {
    fun encode(pcm: ShortArray): AmrWb.Frame
}

/** Decodes one AMR-WB frame into 20 ms of PCM; a null frame asks for concealment of a lost frame. */
interface SpeechDecoder : AutoCloseable {
    fun decode(frame: AmrWb.Frame?): ShortArray
}

/** Creates codec instances; one encoder and one decoder per media stream. */
interface SpeechCodecFactory {
    fun encoder(mode: Int): SpeechEncoder
    fun decoder(): SpeechDecoder
}
