package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.AudioSink
import org.openmcx.client.media.AudioSource
import org.openmcx.client.media.EncodedPicture
import org.openmcx.client.media.RtpPacket
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.SpeechDecoder
import org.openmcx.client.media.SpeechEncoder
import org.openmcx.client.media.VideoCapture
import org.openmcx.client.media.VideoDevices
import org.openmcx.client.media.VideoFormat
import org.openmcx.client.media.VideoRenderer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** The media session against a fake MCVideo server on the loopback interface. */
class VideoMediaSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loopback = InetAddress.getLoopbackAddress()
    private val rendered = mutableListOf<List<ByteArray>>()

    @After fun tearDown() = scope.cancel()

    /** A camera that sends an IDR then P pictures every 20 ms, with a 3000-octet slice to force FU-A. */
    private val devices = object : VideoDevices {
        override fun capture(format: VideoFormat, output: (EncodedPicture) -> Unit): VideoCapture {
            var running = true
            thread {
                var n = 0
                while (running) {
                    val nals = if (n % 30 == 0) listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3), ByteArray(3000) { (it + n).toByte() }.also { it[0] = 0x65 })
                    else listOf(ByteArray(400) { (it * n).toByte() }.also { it[0] = 0x41 })
                    output(EncodedPicture(nals, System.nanoTime() / 1000))
                    n++; Thread.sleep(20)
                }
            }
            return object : VideoCapture { override fun requestKeyFrame() {}; override fun close() { running = false } }
        }
        override fun renderer() = object : VideoRenderer {
            override fun render(nals: List<ByteArray>, presentationUs: Long): Boolean { synchronized(rendered) { rendered += nals }; return true }
            override fun close() {}
        }
    }

    private val silence = object : AudioDevices {
        override fun source(duplex: Boolean) = object : AudioSource { override fun read(frame: ShortArray): Boolean { Thread.sleep(20); frame.fill(0); return true }; override fun close() {} }
        override fun sink() = object : AudioSink { override fun write(frame: ShortArray) {}; override fun close() {} }
    }
    private val codecs = object : SpeechCodecFactory {
        override fun encoder(mode: Int) = object : SpeechEncoder {
            override fun encode(pcm: ShortArray) = AmrWb.Frame(2, true, ByteArray((AmrWb.frameBits(2) + 7) / 8))
            override fun close() {}
        }
        override fun decoder() = object : SpeechDecoder { override fun decode(frame: AmrWb.Frame?) = ShortArray(AmrWb.FRAME_SAMPLES); override fun close() {} }
    }

    private fun receive(socket: DatagramSocket, timeoutMs: Int = 3000): Pair<ByteArray, InetSocketAddress>? {
        socket.soTimeout = timeoutMs
        val buffer = ByteArray(2048)
        val p = DatagramPacket(buffer, buffer.size)
        return try { socket.receive(p); buffer.copyOf(p.length) to (p.socketAddress as InetSocketAddress) } catch (e: SocketTimeoutException) { null }
    }

    @Test fun grantedTransmissionSendsVideoAndAudioWithTheGrantedSsrcs() = runBlocking {
        val server = listOf(DatagramSocket(0, loopback), DatagramSocket(0, loopback), DatagramSocket(0, loopback)) // audio, video, control
        val ports = VideoPorts.bind(loopback, 30000..30400)
        val local = LocalVideoMedia("127.0.0.1", ports.audio.localPort, ports.video.localPort, ports.control.localPort, 111, 222, 333)
        val session = VideoMediaSession(scope, ports, local, VideoFormat(320, 240, 15, 300_000), devices, codecs, silence, 2)
        val remote = RemoteVideoMedia("127.0.0.1", server[0].localPort, 96, false, server[1].localPort, 97, server[2].localPort, 4242, null)
        session.start(remote, 97)
        delay(100)
        session.control.request()
        val (request, from) = receive(server[2])!!
        val req = TransmissionMessage.decode(request)
        assertEquals(TxType.REQUEST, req.type); assertEquals(4242L, req.ssrc)
        assertEquals(ports.control.localPort, from.port) // symmetric: sent from the control socket
        val granted = TransmissionMessage(TxType.GRANTED, 111, fields = listOf(
            TxField.AUDIO_SSRC to TransmissionMessage.ssrcValue(0xaaaa), TxField.VIDEO_SSRC to TransmissionMessage.ssrcValue(0xbbbb))).encode()
        server[2].send(DatagramPacket(granted, granted.size, from))
        // Video: FU-A fragments of the IDR then pictures, all with the granted SSRC, reassembling cleanly.
        val depacketizer = H264Depacketizer()
        val pictures = mutableListOf<List<ByteArray>>()
        withTimeout(5000) {
            while (pictures.size < 5) {
                val (bytes, src) = receive(server[1])!!
                val rtp = RtpPacket.parse(bytes)!!
                assertEquals(0xbbbbL, rtp.ssrc); assertEquals(97, rtp.payloadType); assertEquals(ports.video.localPort, src.port)
                assertTrue(rtp.payload.size <= H264Rtp.MAX_PAYLOAD)
                pictures += depacketizer.push(rtp.sequence, rtp.timestamp, rtp.marker, rtp.payload)
            }
        }
        assertTrue(H264Rtp.isKeyFrame(pictures.first()))
        assertEquals(0, depacketizer.lost)
        val audio = RtpPacket.parse(receive(server[0])!!.first)!!
        assertEquals(0xaaaaL, audio.ssrc); assertEquals(96, audio.payloadType)
        withTimeout(2000) { while (session.stats.picturesSent.get() < 5) delay(10) } // counted once a picture is out
        // Release: the camera stops and a Transmission Release goes to the server.
        session.control.release()
        var released = false
        while (!released) released = TransmissionMessage.decode(receive(server[2])!!.first).type == TxType.RELEASE
        session.stop()
        server.forEach { it.close() }
    }

    @Test fun receptionIsAcceptedAndVideoDecodedFromTheKeyPicture() = runBlocking {
        val server = listOf(DatagramSocket(0, loopback), DatagramSocket(0, loopback), DatagramSocket(0, loopback))
        val ports = VideoPorts.bind(loopback, 30000..30400)
        val local = LocalVideoMedia("127.0.0.1", ports.audio.localPort, ports.video.localPort, ports.control.localPort, 111, 222, 333)
        val session = VideoMediaSession(scope, ports, local, VideoFormat(320, 240, 15, 300_000), devices, codecs, silence, 2)
        session.start(RemoteVideoMedia("127.0.0.1", server[0].localPort, 96, false, server[1].localPort, 97, server[2].localPort, 4242, null), 97)
        delay(100)
        val control = InetSocketAddress(loopback, ports.control.localPort)
        fun sendControl(m: TransmissionMessage) { val b = m.encode(); server[2].send(DatagramPacket(b, b.size, control)) }
        sendControl(TransmissionMessage(TxType.TAKEN, 111, fields = listOf(TxField.TRANSMITTING_USER_ID to "sip:bob.mcvideo@mcx.test".toByteArray())))
        sendControl(TransmissionMessage(TxType.MEDIA_RECEPTION_NOTIFICATION, 111, fields = listOf(TxField.USER_ID to "sip:bob.mcvideo@mcx.test".toByteArray())))
        assertEquals(TxType.RECEIVE_MEDIA_REQUEST, TransmissionMessage.decode(receive(server[2])!!.first).type)
        sendControl(TransmissionMessage(TxType.RECEIVE_MEDIA_RESPONSE, 111))
        // A message for someone else's SSRC is ignored.
        sendControl(TransmissionMessage(TxType.IDLE, 999))
        // Video joining mid-stream: a P picture first (skipped), then an IDR and more.
        val video = InetSocketAddress(loopback, ports.video.localPort)
        var seq = 1
        fun sendPicture(nals: List<ByteArray>, ts: Long) = H264Rtp.packetize(nals).forEach { p ->
            val b = RtpPacket(97, seq++, ts, 0xcccc, p.bytes, p.marker).encode(); server[1].send(DatagramPacket(b, b.size, video))
        }
        sendPicture(listOf(byteArrayOf(0x41, 9, 9)), 1000)
        sendPicture(listOf(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2), ByteArray(2500) { 7 }.also { it[0] = 0x65 }), 7000)
        sendPicture(listOf(byteArrayOf(0x41, 5, 5)), 13000)
        withTimeout(3000) { while (session.stats.picturesReceived.get() < 2) delay(20) }
        assertEquals(1, session.stats.skippedBeforeKey.get())
        assertEquals("sip:bob.mcvideo@mcx.test", session.control.transmitter)
        // The Receive Media Response is handled on the control port's own coroutine: wait for it rather than race it.
        withTimeout(2000) { while (!session.control.receiving) delay(10) }
        assertTrue(session.control.receiving)
        assertEquals(TxState.NO_PERMISSION, session.control.state.value)
        assertEquals(2, session.stats.received.size)
        session.stop()
        server.forEach { it.close() }
    }
}
