package org.openmcx.client.mcptt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The on-network floor participant's states (TS 24.380 §6.2.4). */
enum class FloorState { START_STOP, NO_PERMISSION, PENDING_REQUEST, HAS_PERMISSION, PENDING_RELEASE }

/** What the floor participant tells the call about (media and the user). */
interface FloorListener {
    /** We may talk: send RTP with [mediaSsrc] for at most [durationSeconds]. */
    fun granted(mediaSsrc: Long, durationSeconds: Int?) {}
    /** Stop sending media now (released, revoked or the call ended); returns once the last packet is out. */
    suspend fun stopTalking() {}
    /** Another participant talks; their RTP carries [mediaSsrc]. */
    fun taken(party: String?, mediaSsrc: Long?) {}
    fun idle() {}
    fun denied(cause: Int?, text: String?) {}
    fun revoked(cause: Int?) {}
    /** The floor request got no answer after every retransmission. */
    fun requestFailed() {}
}

/**
 * The floor participant of one MCPTT call (TS 24.380 §6.2.4, on-network, without
 * queueing): requests, releases, acknowledgements and the T100/T101 retransmissions.
 * [serverSsrc] is the floor control server's `mc_floor_ssrc`, which our messages carry.
 */
class FloorParticipant(
    private val scope: CoroutineScope,
    private val serverSsrc: Long,
    private val send: suspend (FloorMessage) -> Unit,
    private val listener: FloorListener,
    private val priority: Int? = null,
    private val t100Ms: Long = 1000, private val c100: Int = 3,
    private val t101Ms: Long = 1000, private val c101: Int = 3,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(FloorState.START_STOP)
    val state: StateFlow<FloorState> = _state
    private var timer: Job? = null
    private var lastSequence: Int? = null

    /** The talker's media SSRC while someone else has the floor, or ours while we do. */
    @Volatile var currentMediaSsrc: Long? = null
        private set
    @Volatile var talker: String? = null
        private set

    /** The call is established: floor messages may flow (§6.2.4.2). */
    suspend fun start() = lock.withLock { if (_state.value == FloorState.START_STOP) _state.value = FloorState.NO_PERMISSION }

    /** The user pressed PTT. */
    suspend fun request() = lock.withLock {
        if (_state.value != FloorState.NO_PERMISSION) return@withLock
        _state.value = FloorState.PENDING_REQUEST
        retransmit(FloorMessage.request(serverSsrc, priority), t101Ms, c101) {
            // T101 expired C101 times (§6.2.4.4.4).
            if (_state.value == FloorState.PENDING_REQUEST) { _state.value = FloorState.NO_PERMISSION; listener.requestFailed() }
        }
    }

    /** The user released PTT (or cancelled a pending request). */
    suspend fun release() = lock.withLock {
        when (_state.value) {
            FloorState.HAS_PERMISSION, FloorState.PENDING_REQUEST -> {
                if (_state.value == FloorState.HAS_PERMISSION) {
                    listener.stopTalking()
                    // The last speech packet and the Floor Release go on different ports: give the packet a head start
                    // so that the server has relayed it before it takes the floor back.
                    delay(RELEASE_AFTER_SPEECH_MS)
                }
                startRelease()
            }
            else -> Unit
        }
    }

    private suspend fun startRelease() {
        _state.value = FloorState.PENDING_RELEASE
        retransmit(FloorMessage.release(serverSsrc), t100Ms, c100) {
            // T100 expired C100 times: consider the floor released (§6.2.4.6.3).
            if (_state.value == FloorState.PENDING_RELEASE) _state.value = FloorState.NO_PERMISSION
        }
    }

    /** The call ended (§6.2.4.9): stop everything. */
    suspend fun stop() = lock.withLock {
        timer?.cancel()
        if (_state.value == FloorState.HAS_PERMISSION) listener.stopTalking()
        _state.value = FloorState.START_STOP
    }

    /** A floor control message from the floor control server. */
    suspend fun receive(message: FloorMessage) = lock.withLock {
        if (_state.value == FloorState.START_STOP) return@withLock
        if (message.ackRequired) send(FloorMessage.ack(serverSsrc, message.type))
        when (message.type) {
            FloorType.GRANTED -> when (_state.value) {
                FloorState.PENDING_REQUEST -> {
                    timer?.cancel()
                    _state.value = FloorState.HAS_PERMISSION
                    currentMediaSsrc = message.mediaSsrc; talker = null
                    listener.granted(message.mediaSsrc ?: 0, message.durationSeconds)
                }
                FloorState.HAS_PERMISSION -> Unit // a retransmitted grant: already acknowledged
                // A grant that crossed our release: release again (§6.2.4.6).
                FloorState.PENDING_RELEASE -> Unit
                else -> Unit
            }
            FloorType.DENY -> if (_state.value == FloorState.PENDING_REQUEST) {
                timer?.cancel()
                _state.value = FloorState.NO_PERMISSION
                listener.denied(message.rejectCause, message.rejectText)
            }
            FloorType.TAKEN -> if (fresh(message)) {
                when (_state.value) {
                    FloorState.PENDING_REQUEST, FloorState.PENDING_RELEASE -> { timer?.cancel(); _state.value = FloorState.NO_PERMISSION }
                    FloorState.HAS_PERMISSION -> { listener.stopTalking(); _state.value = FloorState.NO_PERMISSION }
                    else -> Unit
                }
                currentMediaSsrc = message.mediaSsrc; talker = message.grantedParty
                listener.taken(message.grantedParty, message.mediaSsrc)
            }
            FloorType.IDLE -> if (fresh(message)) {
                when (_state.value) {
                    FloorState.PENDING_RELEASE -> { timer?.cancel(); _state.value = FloorState.NO_PERMISSION }
                    FloorState.HAS_PERMISSION -> { listener.stopTalking(); _state.value = FloorState.NO_PERMISSION }
                    else -> Unit
                }
                if (_state.value != FloorState.PENDING_REQUEST) { currentMediaSsrc = null; talker = null; listener.idle() }
            }
            FloorType.REVOKE -> if (_state.value == FloorState.HAS_PERMISSION) {
                listener.stopTalking()
                listener.revoked(message.rejectCause)
                startRelease()
            }
            FloorType.ACK -> Unit
        }
    }

    /** Idle and Taken carry a sequence number; drop ones older than the last seen (§6.2.4.3). */
    private fun fresh(message: FloorMessage): Boolean {
        val seq = message.sequence ?: return true
        val last = lastSequence
        if (last != null && ((seq - last) and 0xffff) >= 0x8000 && seq != last) return false
        lastSequence = seq
        return true
    }

    private fun retransmit(message: FloorMessage, periodMs: Long, limit: Int, expired: suspend () -> Unit) {
        timer?.cancel()
        timer = scope.launch {
            repeat(limit) {
                send(message)
                delay(periodMs)
            }
            lock.withLock { expired() }
        }
    }

    companion object {
        /** Two speech frames: the time between the last packet and the Floor Release (see [release]). */
        const val RELEASE_AFTER_SPEECH_MS = 40L
    }
}
