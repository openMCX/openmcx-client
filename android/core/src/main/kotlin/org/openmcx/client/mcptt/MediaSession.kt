//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.AudioSink
import org.openmcx.client.media.AudioSource
import org.openmcx.client.media.JitterBuffer
import org.openmcx.client.media.RtpPacket
import org.openmcx.client.media.SpeechCodecFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** A record of one talk burst's payloads, for the end-to-end speech checks. */
class TalkBurst(
    val direction: String, // "sent" or "received"
    val ssrc: Long,
    /** Wall-clock milliseconds: first packet sent or received. */
    @Volatile var startedAt: Long,
) {
    /** SHA-256 (first 8 octets, hex) of every AMR-WB RTP payload, in order. */
    val payloadHashes: MutableList<String> = CopyOnWriteArrayList()
    /** Sent: when the floor was requested and granted. Received: when the first frame was played out. */
    @Volatile var requestedAt: Long = 0
    @Volatile var grantedAt: Long = 0
    @Volatile var firstPlayoutAt: Long = 0
    @Volatile var concealed: Int = 0
    @Volatile var late: Int = 0
}

/** The UDP ports of one call: RTP, RTCP (advertised, silent) and floor control. */
class MediaPorts private constructor(val audio: DatagramSocket, val rtcp: DatagramSocket, val floor: DatagramSocket) {
    val audioPort get() = audio.localPort
    val floorPort get() = floor.localPort

    fun close() { audio.close(); rtcp.close(); floor.close() }

    companion object {
        private val random = SecureRandom()

        /** Bind audio on an even port P, RTCP on P+1 and floor on P+2, within [range]. */
        fun bind(address: InetAddress, range: IntRange): MediaPorts {
            val slots = (range.first..range.last - 2 step 4).map { it + (it and 1) }.shuffled(random)
            for (p in slots) {
                val sockets = mutableListOf<DatagramSocket>()
                try {
                    for (port in listOf(p, p + 1, p + 2)) sockets += DatagramSocket(InetSocketAddress(address, port))
                    return MediaPorts(sockets[0], sockets[1], sockets[2])
                } catch (e: java.net.BindException) {
                    sockets.forEach { it.close() }
                }
            }
            throw java.io.IOException("no free media ports in $range")
        }
    }
}

/**
 * The media plane of one MCPTT call: floor control on its UDP port, RTP speech on the
 * audio port, both symmetric (sent from the socket they are received on, as the server
 * requires). A talk burst runs while the floor participant holds permission.
 */
class MediaSession(
    private val scope: CoroutineScope,
    private val ports: MediaPorts,
    val local: LocalMedia,
    private val codecs: SpeechCodecFactory,
    private val audio: AudioDevices,
    private val amrMode: Int,
    private val onEvent: (String) -> Unit = {},
) : FloorListener {
    @Volatile private var remote: RemoteMedia? = null
    lateinit var floor: FloorParticipant
        private set
    val isStarted: Boolean get() = this::floor.isInitialized
    private val jobs = mutableListOf<Job>()
    private var talking: Job? = null
    private var playout: Job? = null
    /** The speech encoder, kept for the call: making one takes a phone up to a few hundred milliseconds. */
    private var encoder: org.openmcx.client.media.SpeechEncoder? = null
    private val encoderLock = Any()
    /** The microphone, opened when PTT is pressed so that speech is sent from the grant on (see [prepareTalk]). */
    @Volatile private var preparing: kotlinx.coroutines.Deferred<AudioSource?>? = null
    private val jitter = JitterBuffer()
    private var sink: AudioSink? = null
    private var decoder: org.openmcx.client.media.SpeechDecoder? = null
    private var sequence = SecureRandom().nextInt(0xffff)
    private var timestamp = SecureRandom().nextInt().toLong() and 0xffffffffL
    private var receivingSsrc: Long? = null
    @Volatile private var playingBurst: TalkBurst? = null

    val bursts: MutableList<TalkBurst> = CopyOnWriteArrayList()
    /** When the user last pressed PTT, for the access time (TS 22.179 KPI 1). */
    @Volatile var requestedAt: Long = 0
    /** Listener for the call's own floor events (the UI and the service). */
    var floorEvents: FloorListener? = null
    /**
     * A call without floor control (TS 24.379 §11.1.2): both parties speak at once, like a telephone call. The
     * microphone is sent from the start to the end of the call, unless [muted]; no floor message is exchanged.
     */
    val duplex: Boolean get() = remote?.floorControl == false
    /** Duplex calls: the microphone is not sent (the RTP timestamps still advance). */
    @Volatile var muted = false

    /** Start once the SDP negotiation is done (the call is established). */
    fun start(remote: RemoteMedia, priority: Int? = remote.floorPriority) {
        this.remote = remote
        floor = FloorParticipant(scope, remote.floorSsrc, ::sendFloor, this, priority)
        jobs += scope.launch(Dispatchers.IO) { receiveAudio() }
        if (!remote.floorControl) {
            // No floor: speak at once, with the SSRC our SDP announced.
            talking = scope.launch(Dispatchers.IO) { talk(remote, local.audioSsrc, null) }
            return
        }
        jobs += scope.launch(Dispatchers.IO) { receiveFloor() }
        scope.launch { floor.start() }
    }

    private suspend fun sendFloor(message: FloorMessage) {
        val r = remote ?: return
        val data = message.encode()
        onEvent("floor → ${message}")
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ports.floor.send(DatagramPacket(data, data.size, InetSocketAddress(r.address, r.floorPort)))
        }
    }

    private suspend fun receiveFloor() {
        val buffer = ByteArray(1500)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { ports.floor.receive(packet) } catch (e: Exception) { return }
            val message = try { FloorMessage.decode(buffer.copyOf(packet.length)) } catch (e: FloorCodecError) {
                onEvent("floor: bad packet: ${e.message}"); continue
            }
            onEvent("floor ← $message")
            floor.receive(message)
        }
    }

    private suspend fun receiveAudio() {
        val buffer = ByteArray(1500)
        var burst: TalkBurst? = null
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { ports.audio.receive(packet) } catch (e: Exception) { return }
            val rtp = RtpPacket.parse(buffer, packet.length) ?: continue
            if (!duplex) {
                val expected = floor.currentMediaSsrc
                if (floor.state.value == FloorState.HAS_PERMISSION) continue // our own floor: ignore stray media
                if (expected != null && rtp.ssrc != expected) continue
            }
            if (burst == null || burst.ssrc != rtp.ssrc) {
                burst = TalkBurst("received", rtp.ssrc, System.currentTimeMillis()).also { bursts += it }
                receivingSsrc = rtp.ssrc
                jitter.reset()
            }
            // Playout stops after a second without speech; it starts again with the next packet.
            startPlayout(burst)
            burst.payloadHashes += hash(rtp.payload)
            val frames = try { AmrWb.unpack(rtp.payload, remote?.octetAligned == true) } catch (e: IllegalArgumentException) { continue }
            jitter.push(rtp.timestamp, frames, System.currentTimeMillis())
        }
    }

    private fun startPlayout(burst: TalkBurst) {
        playingBurst = burst
        if (playout?.isActive == true) return
        playout = scope.launch(Dispatchers.IO) {
            val out = audio.sink().also { sink = it }
            val dec = codecs.decoder().also { decoder = it }
            var idle = 0
            try {
                var next = System.nanoTime()
                while (isActive && idle < 50) { // stop after 1 s without frames
                    val frame = when (val p = jitter.pull()) {
                        is JitterBuffer.Pull.Frame -> { idle = 0; dec.decode(p.frame) }
                        JitterBuffer.Pull.Lost -> { idle = 0; dec.decode(null) }
                        JitterBuffer.Pull.Silence -> { idle++; null }
                    }
                    if (frame != null) {
                        playingBurst?.let { b -> if (b.firstPlayoutAt == 0L) b.firstPlayoutAt = System.currentTimeMillis() }
                        out.write(frame)
                    }
                    playingBurst?.let { b -> b.concealed = jitter.concealed; b.late = jitter.late }
                    next += 20_000_000L
                    delay(((next - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
                }
            } finally {
                out.close(); dec.close(); sink = null; decoder = null
            }
        }
    }

    private fun speechEncoder(): org.openmcx.client.media.SpeechEncoder =
        synchronized(encoderLock) { encoder ?: codecs.encoder(AmrWb.sendMode(amrMode, remote?.modeSet ?: emptyList())).also { encoder = it } }

    /**
     * PTT pressed: open the microphone (and the encoder) while the floor is requested, so that sending starts when it
     * is granted rather than after the phone has set them up (up to some 400 ms, cut from the start of what is said).
     * The recorder buffers only a few frames, so nothing said long before the grant is sent.
     */
    fun prepareTalk() {
        if (!isStarted || duplex || preparing != null) return
        preparing = scope.async(Dispatchers.IO) { runCatching { speechEncoder(); audio.source(duplex = false) }.getOrNull() }
    }

    /** The floor was not granted after all (released, denied, failed): close the microphone opened for it. */
    fun cancelPrepared() {
        val p = preparing ?: return
        preparing = null
        scope.launch { runCatching { p.await()?.close() } }
    }

    private suspend fun takeSource(): AudioSource {
        val p = preparing
        preparing = null
        return p?.await() ?: audio.source(duplex = duplex)
    }

    // FloorListener: the participant's decisions drive the media.

    override fun granted(mediaSsrc: Long, durationSeconds: Int?) {
        floorEvents?.granted(mediaSsrc, durationSeconds)
        val r = remote ?: return
        talking?.cancel()
        talking = scope.launch(Dispatchers.IO) { talk(r, mediaSsrc, durationSeconds) }
    }

    private suspend fun talk(r: RemoteMedia, ssrc: Long, durationSeconds: Int?) {
        val grantedAt = System.currentTimeMillis()
        val burst = TalkBurst("sent", ssrc, 0).also { it.requestedAt = requestedAt; it.grantedAt = grantedAt; bursts += it }
        val source = takeSource()
        val encoder = speechEncoder()
        val target = InetSocketAddress(r.address, r.audioPort)
        val pcm = ShortArray(AmrWb.FRAME_SAMPLES)
        val limit = durationSeconds?.let { System.currentTimeMillis() + it * 1000L }
        var first = true
        try {
            while (currentCoroutineContext().isActive && (duplex || floor.state.value == FloorState.HAS_PERMISSION)) {
                if (!source.read(pcm)) break
                if (!currentCoroutineContext().isActive) break
                if (limit != null && System.currentTimeMillis() >= limit) break
                if (muted) { timestamp = (timestamp + AmrWb.FRAME_SAMPLES) and 0xffffffffL; first = true; continue }
                val frame = encoder.encode(pcm)
                if (frame.ft == AmrWb.FT_NO_DATA) continue
                val payload = AmrWb.pack(listOf(frame), r.octetAligned)
                val packet = RtpPacket(r.payloadType, sequence, timestamp, ssrc, payload, marker = first).encode()
                ports.audio.send(DatagramPacket(packet, packet.size, target))
                if (first) burst.startedAt = System.currentTimeMillis()
                burst.payloadHashes += hash(payload)
                sequence = (sequence + 1) and 0xffff
                timestamp = (timestamp + AmrWb.FRAME_SAMPLES) and 0xffffffffL
                first = false
            }
        } finally {
            source.close()
        }
        // The source ended (a file) or the granted time is up: release the floor, from outside
        // this job, since releasing waits for the job to end.
        if (!duplex && floor.state.value == FloorState.HAS_PERMISSION) scope.launch { floor.release() }
    }

    override suspend fun stopTalking() {
        val job = talking
        if (job != null && job != currentCoroutineContext()[Job]) job.cancelAndJoin()
        floorEvents?.stopTalking()
    }
    override fun taken(party: String?, mediaSsrc: Long?) { floorEvents?.taken(party, mediaSsrc) }
    override fun idle() { floorEvents?.idle() }
    override fun denied(cause: Int?, text: String?) { cancelPrepared(); floorEvents?.denied(cause, text) }
    override fun revoked(cause: Int?) { cancelPrepared(); floorEvents?.revoked(cause) }
    override fun requestFailed() { cancelPrepared(); floorEvents?.requestFailed() }
    override fun queued(position: Int?, priority: Int?) { floorEvents?.queued(position, priority) }
    override fun queuedGrantOffered(seconds: Int) { floorEvents?.queuedGrantOffered(seconds) }
    override fun queuedGrantLapsed() { cancelPrepared(); floorEvents?.queuedGrantLapsed() }
    override fun queueTimeout() { cancelPrepared(); floorEvents?.queueTimeout() }

    suspend fun stop() {
        if (this::floor.isInitialized) floor.stop()
        cancelPrepared()
        talking?.cancelAndJoin()
        synchronized(encoderLock) { encoder?.close(); encoder = null }
        playout?.cancel()
        jobs.forEach { it.cancel() }
        ports.close()
    }

    private fun hash(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).take(8).joinToString("") { "%02x".format(it) }
}
