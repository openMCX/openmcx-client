//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

/** What the camera sends: size, rate and target bitrate of the H.264 stream. */
data class VideoFormat(val width: Int, val height: Int, val frameRate: Int, val bitrate: Int)

/** One encoded picture (an access unit) as NAL units without start codes. */
class EncodedPicture(val nals: List<ByteArray>, val presentationUs: Long)

/** A running camera and H.264 encoder; pictures go to the callback given at [VideoDevices.capture]. */
interface VideoCapture : AutoCloseable {
    /** Make the next picture an IDR (a new receiver, or after loss). */
    fun requestKeyFrame()
}

/** An H.264 decoder that shows what it decodes. */
interface VideoRenderer : AutoCloseable {
    /** Decode and show one access unit; false when the decoder refused it. */
    fun render(nals: List<ByteArray>, presentationUs: Long): Boolean
    /** A new stream follows (another transmitter): forget the pictures of the last one. */
    fun reset() {}
}

/** The platform's camera, encoder and decoder (Android: Camera2 and MediaCodec). */
interface VideoDevices {
    fun capture(format: VideoFormat, output: (EncodedPicture) -> Unit): VideoCapture
    fun renderer(): VideoRenderer
}
