package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.JitterBuffer
import org.openmcx.client.media.RtpPacket
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.VideoCapture
import org.openmcx.client.media.VideoDevices
import org.openmcx.client.media.VideoFormat
import org.openmcx.client.media.VideoRenderer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The UDP ports of one MCVideo call: audio and its RTCP, transmission control, video and its RTCP. */
class VideoPorts private constructor(
    val audio: DatagramSocket, val audioRtcp: DatagramSocket, val control: DatagramSocket,
    val video: DatagramSocket, val videoRtcp: DatagramSocket,
) {
    fun close() = listOf(audio, audioRtcp, control, video, videoRtcp).forEach { it.close() }

    companion object {
        private val random = SecureRandom()

        /** Audio on an even port P, its RTCP on P+1, control on P+2, video on P+4 and its RTCP on P+5, within [range]. */
        fun bind(address: InetAddress, range: IntRange): VideoPorts {
            val slots = (range.first..range.last - 5 step 6).map { it + (it and 1) }.filter { it + 5 <= range.last }.shuffled(random)
            for (p in slots) {
                val sockets = mutableListOf<DatagramSocket>()
                try {
                    for (port in listOf(p, p + 1, p + 2, p + 4, p + 5)) sockets += DatagramSocket(InetSocketAddress(address, port))
                    return VideoPorts(sockets[0], sockets[1], sockets[2], sockets[3], sockets[4])
                } catch (e: java.net.BindException) {
                    sockets.forEach { it.close() }
                }
            }
            throw java.io.IOException("no free media ports in $range")
        }
    }
}

/** Counters and picture fingerprints of one call, for the status view and the lab checks. */
class VideoStats {
    val picturesSent = AtomicInteger()
    val picturesReceived = AtomicInteger()
    val undecoded = AtomicInteger()
    val skippedBeforeKey = AtomicInteger()
    val audioSent = AtomicInteger()
    val audioReceived = AtomicInteger()
    @Volatile var lost = 0
    /** "<ssrc>:<rtp timestamp>" to a hash of the picture's NAL units, as sent and as received. */
    val sent: MutableMap<String, String> = ConcurrentHashMap()
    val received: MutableMap<String, String> = ConcurrentHashMap()

    fun record(map: MutableMap<String, String>, ssrc: Long, timestamp: Long, nals: List<ByteArray>) {
        if (map.size >= 2000) return
        val digest = MessageDigest.getInstance("SHA-256")
        nals.forEach(digest::update)
        map["$ssrc:$timestamp"] = digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The media plane of one MCVideo call (TS 24.281 §6.2, TS 24.581): transmission control on its
 * port, and, while this client holds the transmission, camera video (H.264 over RTP, RFC 6184)
 * and microphone speech (AMR-WB) sent with the SSRCs the server granted. The current
 * transmitter's video is decoded from its next key picture; its speech is played out.
 * All streams are symmetric: sent from the socket they are received on.
 */
class VideoMediaSession(
    private val scope: CoroutineScope,
    private val ports: VideoPorts,
    val local: LocalVideoMedia,
    private val format: VideoFormat,
    private val video: VideoDevices,
    private val codecs: SpeechCodecFactory,
    private val audio: AudioDevices,
    private val amrMode: Int,
    private val onEvent: (String) -> Unit = {},
) : TransmissionListener {
    @Volatile var remote: RemoteVideoMedia? = null
        private set
    /** The payload type the server sends us video with: ours in the offer or answer. */
    @Volatile private var videoPt = McvideoSdp.VIDEO_PT
    lateinit var control: TransmissionParticipant
        private set
    val isStarted get() = this::control.isInitialized
    val stats = VideoStats()
    /** The call's packet keys (TS 24.281 4.7); null while everything is in the clear. Set before [start]. */
    @Volatile var protection: CallProtection? = null
    /** The call's transmission events (the UI and the service). */
    var events: TransmissionListener? = null
    /** The decoder of received video, from the first key picture on (the app shows its output). */
    @Volatile var renderer: VideoRenderer? = null
        private set

    private val jobs = mutableListOf<Job>()
    private var transmitting: Job? = null
    private var capture: VideoCapture? = null
    private var playout: Job? = null
    private val jitter = JitterBuffer()
    private val random = SecureRandom()
    private var audioSeq = random.nextInt(0xffff)
    private var audioTs = random.nextInt().toLong() and 0xffffffffL
    private var videoSeq = random.nextInt(0xffff)
    private var videoTsBase = random.nextInt().toLong() and 0xffffffffL
    @Volatile private var sendingVideoSsrc: Long = local.videoSsrc

    fun start(remote: RemoteVideoMedia, ownVideoPayloadType: Int) {
        val implicit = local.implicitRequest && remote.transmissionControl
        this.remote = remote
        videoPt = ownVideoPayloadType
        control = TransmissionParticipant(scope, remote.controlSsrc ?: 0, ::sendControl, this, remote.priority)
        jobs += scope.launch(Dispatchers.IO) { receiveVideo() }
        jobs += scope.launch(Dispatchers.IO) { receiveAudio() }
        jobs += scope.launch(Dispatchers.IO) {
            // Started before the control port is read: the server may grant at once (a push to it), and a message
            // that finds the participant not yet started is dropped. Meanwhile it waits in the socket's buffer.
            control.start(implicit)
            // Without transmission control each party sends at once, with the SSRCs of its own SDP.
            if (remote.transmissionControl) receiveControl() else granted(null, null, null)
        }
    }

    private suspend fun sendControl(message: TransmissionMessage) {
        val r = remote ?: return
        val port = r.controlPort ?: return
        val clear = message.encode()
        val data = protection?.let { it.protectControl(clear) ?: return onEvent("control: protection failed") } ?: clear
        onEvent("control → $message")
        withContext(Dispatchers.IO) { ports.control.send(DatagramPacket(data, data.size, InetSocketAddress(r.address, port))) }
    }

    private suspend fun receiveControl() {
        val buffer = ByteArray(1500)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { ports.control.receive(packet) } catch (e: Exception) { return }
            val raw = buffer.copyOf(packet.length)
            val clear = protection?.let { it.unprotectControl(raw) ?: run { onEvent("control: SRTCP failed to authenticate"); null } } ?: raw.takeIf { protection == null }
            if (clear == null) continue
            val message = try { TransmissionMessage.decode(clear) } catch (e: TransmissionCodecError) {
                onEvent("control: bad packet: ${e.message}"); continue
            }
            // The server's messages to us carry our mc_transmission_ssrc.
            if (message.ssrc != local.controlSsrc) { onEvent("control: foreign SSRC in $message"); continue }
            onEvent("control ← $message")
            control.receive(message)
        }
    }

    // Receiving.

    private suspend fun receiveVideo() {
        val buffer = ByteArray(2048)
        val depacketizer = H264Depacketizer()
        var ssrc: Long? = null
        var lastTimestamp = 0L
        var awaitingKey = true
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { ports.video.receive(packet) } catch (e: Exception) { return }
            val data = protection?.let { it.unprotectRtp(buffer.copyOf(packet.length)) ?: continue } ?: buffer.copyOf(packet.length)
            val rtp = RtpPacket.parse(data, data.size) ?: continue
            if (rtp.payloadType != videoPt || control.state.value == TxState.HAS_PERMISSION) continue
            // The same transmitter's next transmission keeps its SSRC but is a new stream (a new encoder, maybe another
            // camera and size): a jump of the RTP time backwards, or more than 10 s forwards, starts it like a new SSRC.
            val step = (rtp.timestamp - lastTimestamp).toInt()
            val restart = rtp.ssrc == ssrc && (step < -45_000 || step > 900_000)
            if (rtp.ssrc != ssrc || restart) {
                // A new transmitter: its own SSRC and sequence space (TS 24.581 §9.2.5).
                ssrc = rtp.ssrc; depacketizer.reset(); awaitingKey = true
                // The decoder starts afresh too (the same decoder: it keeps the surface), as the picture size may differ.
                renderer?.let { r -> runCatching { r.reset() }.onFailure { onEvent("video decoder reset: ${it.message}") } }
                onEvent((if (restart) "video restarts from ssrc %08x" else "video from ssrc %08x").format(rtp.ssrc))
            }
            lastTimestamp = rtp.timestamp
            val units = try { depacketizer.push(rtp.sequence, rtp.timestamp, rtp.marker, rtp.payload) } catch (e: H264Error) {
                onEvent("video: ${e.message}"); continue
            }
            stats.lost = depacketizer.lost
            for (unit in units) {
                if (awaitingKey && !H264Rtp.isKeyFrame(unit)) { stats.skippedBeforeKey.incrementAndGet(); continue }
                awaitingKey = false
                stats.record(stats.received, rtp.ssrc, rtp.timestamp, unit)
                // A decoder that cannot be made or refuses a picture costs that picture, not the stream.
                val decoder = renderer ?: runCatching { video.renderer() }.onFailure {
                    // No decoder now: try again at the next key picture, not at every picture.
                    onEvent("video decoder: ${it.message}"); awaitingKey = true
                }.getOrNull()?.also { renderer = it }
                if (decoder == null) { stats.undecoded.incrementAndGet(); continue }
                val shown = runCatching { decoder.render(unit, rtp.timestamp * 1000 / 90) }
                    .onFailure { onEvent("video decoder: ${it.message}") }.getOrDefault(false)
                // A picture the decoder did not take is counted; decoding goes on (a phone's encoder sends key pictures
                // seconds apart, so waiting for the next one would cost far more than the decoder's own recovery).
                if (shown) stats.picturesReceived.incrementAndGet() else stats.undecoded.incrementAndGet()
            }
        }
    }

    private suspend fun receiveAudio() {
        val buffer = ByteArray(1500)
        var ssrc: Long? = null
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { ports.audio.receive(packet) } catch (e: Exception) { return }
            val data = protection?.let { it.unprotectRtp(buffer.copyOf(packet.length)) ?: continue } ?: buffer.copyOf(packet.length)
            val rtp = RtpPacket.parse(data, data.size) ?: continue
            if (control.state.value == TxState.HAS_PERMISSION) continue
            if (rtp.ssrc != ssrc) { ssrc = rtp.ssrc; jitter.reset() }
            val frames = try { AmrWb.unpack(rtp.payload, remote?.octetAligned == true) } catch (e: IllegalArgumentException) { continue }
            stats.audioReceived.incrementAndGet()
            jitter.push(rtp.timestamp, frames, System.currentTimeMillis())
            startPlayout()
        }
    }

    private fun startPlayout() {
        if (playout?.isActive == true) return
        playout = scope.launch(Dispatchers.IO) {
            val out = audio.sink()
            val dec = codecs.decoder()
            var idle = 0
            try {
                var next = System.nanoTime()
                while (isActive && idle < 50) {
                    val frame = when (val p = jitter.pull()) {
                        is JitterBuffer.Pull.Frame -> { idle = 0; dec.decode(p.frame) }
                        JitterBuffer.Pull.Lost -> { idle = 0; dec.decode(null) }
                        JitterBuffer.Pull.Silence -> { idle++; null }
                    }
                    if (frame != null) out.write(frame)
                    next += 20_000_000L
                    delay(((next - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
                }
            } finally { out.close(); dec.close() }
        }
    }

    // Transmitting.

    override fun granted(audioSsrc: Long?, videoSsrc: Long?, durationSeconds: Int?) {
        events?.granted(audioSsrc, videoSsrc, durationSeconds)
        val r = remote ?: return
        transmitting?.cancel()
        transmitting = scope.launch(Dispatchers.IO) { transmit(r, audioSsrc ?: local.audioSsrc, videoSsrc ?: local.videoSsrc, durationSeconds) }
    }

    private suspend fun transmit(r: RemoteVideoMedia, audioSsrc: Long, videoSsrc: Long, durationSeconds: Int?) {
        // The granted Video SSRC replaces ours: the server checks every packet against it.
        sendingVideoSsrc = videoSsrc
        val videoTarget = InetSocketAddress(r.address, r.videoPort)
        var origin: Long? = null
        var lastOffset = -1L
        val cam = video.capture(format) { picture ->
            // RTP time from the pictures' own capture times (90 kHz), strictly increasing: each picture its own timestamp.
            val first = origin ?: picture.presentationUs.also { origin = it }
            val offset = maxOf((picture.presentationUs - first) * 90 / 1000, lastOffset + 1)
            lastOffset = offset
            val timestamp = (videoTsBase + offset) and 0xffffffffL
            val payloads = H264Rtp.packetize(picture.nals)
            // A key picture is dozens of packets: spread them over half a frame interval rather than
            // bursting them into the network's (Wi-Fi's) queues.
            val gapNanos = if (payloads.size > PACING_THRESHOLD) 500_000_000L / format.frameRate / payloads.size else 0L
            for (payload in payloads) {
                val clear = RtpPacket(r.videoPayloadType, videoSeq, timestamp, sendingVideoSsrc, payload.bytes, payload.marker).encode()
                // Under protection a packet that cannot be protected is dropped, never sent in the clear.
                val bytes = protection.let { if (it == null) clear else it.protectRtp(clear) }
                if (bytes != null) runCatching { ports.video.send(DatagramPacket(bytes, bytes.size, videoTarget)) }
                videoSeq = (videoSeq + 1) and 0xffff
                if (gapNanos > 0) java.util.concurrent.locks.LockSupport.parkNanos(gapNanos)
            }
            stats.record(stats.sent, sendingVideoSsrc, timestamp, picture.nals)
            stats.picturesSent.incrementAndGet()
        }
        capture = cam
        cam.requestKeyFrame()
        onEvent("transmitting: audio ssrc %08x, video ssrc %08x".format(audioSsrc, videoSsrc))
        if (!r.transmissionControl) {
            // Two-way video: nobody holds the transmission, so the server relays no audio (TS 24.281 §10.2
            // without transmission control); the camera runs until the call ends.
            try { kotlinx.coroutines.awaitCancellation() } finally { cam.close(); capture = null }
        }
        val source = audio.source()
        // TS 26.179 §4.1.3: the configured mode within the peer's mode-set.
        val encoder = codecs.encoder(AmrWb.sendMode(amrMode, remote?.modeSet ?: emptyList()))
        val audioTarget = InetSocketAddress(r.address, r.audioPort)
        val pcm = ShortArray(AmrWb.FRAME_SAMPLES)
        val limit = durationSeconds?.let { System.currentTimeMillis() + it * 1000L }
        var first = true
        try {
            while (currentCoroutineContext().isActive) {
                if (r.transmissionControl && control.state.value != TxState.HAS_PERMISSION) break
                if (limit != null && System.currentTimeMillis() >= limit) break
                if (!source.read(pcm)) { delay(20); continue }
                val frame = encoder.encode(pcm)
                if (frame.ft == AmrWb.FT_NO_DATA) continue
                val payload = AmrWb.pack(listOf(frame), r.octetAligned)
                val clear = RtpPacket(r.audioPayloadType, audioSeq, audioTs, audioSsrc, payload, marker = first).encode()
                val bytes = protection.let { if (it == null) clear else it.protectRtp(clear) }
                if (bytes != null) runCatching { ports.audio.send(DatagramPacket(bytes, bytes.size, audioTarget)) }
                stats.audioSent.incrementAndGet()
                audioSeq = (audioSeq + 1) and 0xffff
                audioTs = (audioTs + AmrWb.FRAME_SAMPLES) and 0xffffffffL
                first = false
            }
        } finally {
            source.close(); encoder.close()
            cam.close(); capture = null
            // The next transmission goes on from here (a second after this one's last picture): RTP time keeps increasing
            // for the SSRC (RFC 3550 section 5.1), so a receiver does not see the next transmission start in the past.
            videoTsBase = (videoTsBase + maxOf(lastOffset, 0L) + 90_000) and 0xffffffffL
        }
        if (r.transmissionControl && control.state.value == TxState.HAS_PERMISSION) scope.launch { control.release() }
    }

    override suspend fun stopTransmitting() {
        val job = transmitting
        if (job != null && job != currentCoroutineContext()[Job]) job.cancelAndJoin()
        events?.stopTransmitting()
    }
    override fun taken(user: String?) { events?.taken(user) }
    override fun idle() { events?.idle() }
    override fun rejected(cause: Int?, text: String?) { events?.rejected(cause, text) }
    override fun revoked(cause: Int?) { events?.revoked(cause) }
    override fun requestFailed() { events?.requestFailed() }
    override fun reception(receiving: Boolean, rejectCause: Int?) { events?.reception(receiving, rejectCause) }
    override fun remoteResult(start: Boolean, done: Boolean?) { events?.remoteResult(start, done) }

    companion object {
        /** Pictures of more packets than this are paced. */
        const val PACING_THRESHOLD = 4
    }

    suspend fun stop() {
        if (isStarted) control.stop()
        transmitting?.cancel()
        playout?.cancel()
        jobs.forEach { it.cancel() }
        runCatching { capture?.close() }
        runCatching { renderer?.close() }; renderer = null
        ports.close()
    }
}
