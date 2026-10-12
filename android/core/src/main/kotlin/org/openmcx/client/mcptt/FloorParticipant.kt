//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The on-network floor participant's states (TS 24.380 §6.2.4), 'U: queued' (§6.2.4.9) included. */
enum class FloorState { START_STOP, NO_PERMISSION, PENDING_REQUEST, QUEUED, HAS_PERMISSION, PENDING_RELEASE }

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
    /** The request is queued (§6.2.4.4.9), or its place in the queue changed (§6.2.4.9.7): [position] 255 is "queued, position not told". */
    fun queued(position: Int?, priority: Int?) {}
    /** The floor is granted to our queued request and awaits the user's acceptance within T132 (§6.2.4.9.4). */
    fun queuedGrantOffered(seconds: Int) {}
    /** T132 ran out before the user accepted: the floor was given back (§6.2.4.9.13). */
    fun queuedGrantLapsed() {}
    /** The Floor Queue Position Request got no answer after every retransmission: the request was withdrawn (§6.2.4.9.11). */
    fun queueTimeout() {}
}

/**
 * The floor participant of one MCPTT call (TS 24.380 §6.2.4, on-network): requests, releases, acknowledgements and the
 * T100/T101 retransmissions, and, when the call negotiated queueing (`mc_queueing`, §14.2.2), the queued request
 * (§6.2.4.9): its position (T104/C104) and a queued grant the user accepts within T132.
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
    private val t104Ms: Long = 1000, private val c104: Int = 3,
    private val t132Ms: Long = 2000,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(FloorState.START_STOP)
    val state: StateFlow<FloorState> = _state
    private var timer: Job? = null
    /** T104 (Floor Queue Position Request) or T132 (Queued granted user action), while 'U: queued'. */
    private var queueTimer: Job? = null
    private var lastSequence: Int? = null

    /** The talker's media SSRC while someone else has the floor, or ours while we do. */
    @Volatile var currentMediaSsrc: Long? = null
        private set
    @Volatile var talker: String? = null
        private set

    private val _queuePosition = MutableStateFlow<Int?>(null)
    /** Our place in the floor request queue while 'U: queued' (§8.2.3.5; 255: queued, position not told). */
    val queuePosition: StateFlow<Int?> = _queuePosition
    private val _offered = MutableStateFlow(false)
    /** A queued grant awaits the user's acceptance: T132 runs (§6.2.4.9.4). */
    val offered: StateFlow<Boolean> = _offered
    private var offer: Pair<Long, Int?>? = null

    /**
     * Accept a queued grant at once: the user still holds PTT, which is the user's indication to send media
     * (§6.2.4.9.12). Without it (a request made without holding PTT), the grant waits for [confirm].
     */
    @Volatile var autoConfirm: Boolean = true

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

    /** The user released PTT (or cancelled a pending request, or left the queue). */
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
            // §6.2.4.9.6: leave the queue: Floor Release, T100; T104 and T132 stop.
            FloorState.QUEUED -> { leaveQueue(); startRelease() }
            else -> Unit
        }
    }

    /**
     * The user asks for the queue position (§6.2.4.9.9): only while queued and before a grant (T132 not running).
     * Returns false when there is no queued request to ask about.
     */
    suspend fun requestQueuePosition(): Boolean = lock.withLock {
        if (_state.value != FloorState.QUEUED || _offered.value) return@withLock false
        queueTimer?.cancel()
        queueTimer = scope.launch {
            repeat(c104) {
                send(FloorMessage.queuePositionRequest(serverSsrc))
                delay(t104Ms)
            }
            lock.withLock {
                // T104 expired C104 times (§6.2.4.9.11): tell the user, withdraw the request.
                if (_state.value == FloorState.QUEUED && !_offered.value) {
                    listener.queueTimeout()
                    queueTimer = null // this job: leaveQueue must not cancel it before the release is sent
                    leaveQueue()
                    startRelease()
                }
            }
        }
        true
    }

    /** The user accepts the queued grant (§6.2.4.9.12). Returns false when no grant awaits acceptance. */
    suspend fun confirm(): Boolean = lock.withLock { confirmLocked() }

    private fun confirmLocked(): Boolean {
        if (_state.value != FloorState.QUEUED || !_offered.value) return false
        val (ssrc, duration) = offer ?: return false
        leaveQueue()
        _state.value = FloorState.HAS_PERMISSION
        currentMediaSsrc = ssrc; talker = null
        listener.granted(ssrc, duration)
        return true
    }

    /** Stop T104 and T132 and forget the queue position and any offered grant. */
    private fun leaveQueue() {
        queueTimer?.cancel(); queueTimer = null
        _queuePosition.value = null
        _offered.value = false
        offer = null
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
        leaveQueue()
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
                // §6.2.4.9.4: a grant to our queued request: T104 stops, T132 starts, we stay queued until the user
                // accepts. The server repeats Floor Granted until our media arrives (T20): a repeat does not restart T132.
                FloorState.QUEUED -> if (!_offered.value) {
                    queueTimer?.cancel()
                    _queuePosition.value = null
                    _offered.value = true
                    offer = (message.mediaSsrc ?: 0) to message.durationSeconds
                    queueTimer = scope.launch {
                        delay(t132Ms)
                        lock.withLock {
                            // §6.2.4.9.13: T132 expired: Floor Release, 'U: has no permission'.
                            if (_state.value == FloorState.QUEUED && _offered.value) {
                                queueTimer = null // this job: leaveQueue must not cancel it before the release is sent
                                leaveQueue()
                                send(FloorMessage.release(serverSsrc))
                                _state.value = FloorState.NO_PERMISSION
                                listener.queuedGrantLapsed()
                            }
                        }
                    }
                    listener.queuedGrantOffered(((t132Ms + 999) / 1000).toInt())
                    if (autoConfirm) confirmLocked()
                }
                FloorState.HAS_PERMISSION -> Unit // a retransmitted grant: already acknowledged
                // A grant that crossed our release: release again (§6.2.4.6).
                FloorState.PENDING_RELEASE -> Unit
                else -> Unit
            }
            FloorType.DENY -> when (_state.value) {
                FloorState.PENDING_REQUEST -> {
                    timer?.cancel()
                    _state.value = FloorState.NO_PERMISSION
                    listener.denied(message.rejectCause, message.rejectText)
                }
                // §6.2.4.9.5: the queued request is denied: T104 stops.
                FloorState.QUEUED -> {
                    leaveQueue()
                    _state.value = FloorState.NO_PERMISSION
                    listener.denied(message.rejectCause, message.rejectText)
                }
                else -> Unit
            }
            FloorType.QUEUE_POSITION_INFO -> when (_state.value) {
                // §6.2.4.4.9: the request is queued: T101 stops, 'U: queued'.
                FloorState.PENDING_REQUEST -> {
                    timer?.cancel()
                    _state.value = FloorState.QUEUED
                    _queuePosition.value = message.queuePosition
                    listener.queued(message.queuePosition, message.queuePriority)
                }
                // §6.2.4.9.7: the position, as asked or as it changed: T104 stops.
                FloorState.QUEUED -> {
                    if (!_offered.value) {
                        queueTimer?.cancel(); queueTimer = null
                        _queuePosition.value = message.queuePosition
                        listener.queued(message.queuePosition, message.queuePriority)
                    }
                }
                else -> Unit
            }
            FloorType.TAKEN -> if (fresh(message)) {
                when (_state.value) {
                    FloorState.PENDING_REQUEST, FloorState.PENDING_RELEASE -> { timer?.cancel(); _state.value = FloorState.NO_PERMISSION }
                    FloorState.HAS_PERMISSION -> { listener.stopTalking(); _state.value = FloorState.NO_PERMISSION }
                    // §6.2.4.9.3: someone else talks; the request stays queued.
                    else -> Unit
                }
                currentMediaSsrc = message.mediaSsrc; talker = message.grantedParty
                listener.taken(message.grantedParty, message.mediaSsrc)
            }
            FloorType.IDLE -> if (fresh(message)) {
                when (_state.value) {
                    FloorState.PENDING_RELEASE -> { timer?.cancel(); _state.value = FloorState.NO_PERMISSION }
                    FloorState.HAS_PERMISSION -> { listener.stopTalking(); _state.value = FloorState.NO_PERMISSION }
                    // §6.2.4.9.8: the floor is idle: T104 stops, 'U: has no permission'.
                    FloorState.QUEUED -> { leaveQueue(); _state.value = FloorState.NO_PERMISSION }
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
