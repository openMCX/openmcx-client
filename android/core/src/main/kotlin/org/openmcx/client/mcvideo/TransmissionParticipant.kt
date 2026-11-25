//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The transmission participant's states (TS 24.581 §6.2.4, on-network): 'Start-stop', 'U: has no permission to
 * transmit', 'U: pending request to transmit', 'U: has permission to transmit', 'U: pending end of transmission'
 * ([PENDING_RELEASE]) and 'U: queued transmission'.
 */
enum class TxState { START_STOP, NO_PERMISSION, PENDING_REQUEST, HAS_PERMISSION, PENDING_RELEASE, QUEUED }

/**
 * The reception control states of the one basic reception instance (TS 24.581 §6.2.5): 'U: reception controller' with
 * no instance ([IDLE]), 'U: pending request to receive', 'U: has permission to receive', 'U: pending reception release'
 * and 'terminated'.
 */
enum class RxState { IDLE, PENDING_REQUEST, HAS_PERMISSION, PENDING_RELEASE, TERMINATED }

/**
 * TS 24.581 §11.1.1 and §11.2.1: the transmission participant's timers T100 to T104 (ms) and the upper limits of the
 * counters C100 to C104. The values are provisioned (TS 24.484 §9.4.2.7) and TS 24.581 gives none: one second each
 * keeps the three transmissions within the 6 s of table 11.1.1-1 NOTEs 1 to 4; the counter limits are the defaults of
 * table 11.2.1-1. The same defaults as the Linux client (`mcvideo.transmission_timers`).
 */
data class TransmissionTimers(
    val t100Ms: Long = 1000, val t101Ms: Long = 1000, val t102Ms: Long = 1000, val t103Ms: Long = 1000, val t104Ms: Long = 1000,
    val c100: Int = 3, val c101: Int = 3, val c102: Int = 3, val c103: Int = 3, val c104: Int = 3,
) {
    init {
        require(listOf(t100Ms, t101Ms, t102Ms, t103Ms, t104Ms).all { it > 0 } && listOf(c100, c101, c102, c103, c104).all { it > 0 }) {
            "invalid transmission participant timer configuration"
        }
    }
}

/** What the transmission participant tells the call about. */
interface TransmissionListener {
    /** We may transmit: send audio with [audioSsrc] and video with [videoSsrc]. */
    fun granted(audioSsrc: Long?, videoSsrc: Long?, durationSeconds: Int?) {}
    /** Stop sending media now; returns once the last packet is out. */
    suspend fun stopTransmitting() {}
    fun taken(user: String?) {}
    fun idle() {}
    fun rejected(cause: Int?, text: String?) {}
    fun revoked(cause: Int?) {}
    fun requestFailed() {}
    /** The server accepted (or refused, with [rejectCause]) our reception of the current transmission. */
    fun reception(receiving: Boolean, rejectCause: Int?) {}
    /** The answer to our remote transmission (cancel) request: done or not. */
    fun remoteResult(start: Boolean, done: Boolean?) {}
    /** Our request is queued (TS 24.581 §6.2.4.4.5, §6.2.4.9.2) at [position] (null: not given). */
    fun queued(position: Int?) {}
    /** A timer's counter reached its upper limit: "transmission request timeout", ... (TS 24.581 §6.2.4.4.4 and others). */
    fun timeout(what: String) {}
    /** The transmission ended: our Transmission end request answered, or ended by the server (§6.2.4.5.7). */
    fun ended(byServer: Boolean) {}
}

/**
 * The transmission participant of one MCVideo call (TS 24.581 §6.2.4) and its basic reception control (§6.2.5), with
 * the timers T100 to T104 and counters C100 to C104 of §11.1.1 and §11.2.1:
 * - T100/C100 for the Transmission Request (§6.2.4.3.2, §6.2.4.4.3, §6.2.4.4.4), also for an implicit request in the SDP
 *   (§6.2.4.2.2 step 4: started without sending; its expiry sends the request);
 * - T101/C101 for the Transmission end request (§6.2.4.4.7, §6.2.4.5.3, §6.2.4.5.5 step 4a, §6.2.4.9.4, §6.2.4.6.2 and
 *   §6.2.4.6.3), stopped by the Transmission end response (§6.2.4.6.4);
 * - T102/C102 for the Queue Position Request (§6.2.4.9.3, §6.2.4.9.7; at the limit, §6.2.4.9.8, a Transmission end
 *   request);
 * - T103/C103 for the Receive Media Request (§6.2.5.3.3, §6.2.5.4.3, §6.2.5.4.4), stopped by the Receive Media Response;
 * - T104/C104 for the Media reception end request (§6.2.5.5.3, §6.2.5.6.2, §6.2.5.6.3), stopped by its response.
 *
 * A Media Transmission Notification with Reception Mode '0' starts the reception at once; one with '1', like a Media
 * Reception Notification, is accepted at once with a Receive Media Request (automatic acceptance). [serverSsrc] is the
 * server's `mc_transmission_ssrc`. [priority] and [receptionPriority] are the Transmission Priority and Reception
 * Priority to request (already capped by the negotiated maxima, [TransmissionPriority.request]); null leaves the field
 * out (the default priority, §9.2.3.2, §9.2.3.19).
 */
class TransmissionParticipant(
    private val scope: CoroutineScope,
    private val serverSsrc: Long,
    private val send: suspend (TransmissionMessage) -> Unit,
    private val listener: TransmissionListener,
    private val priority: Int? = null,
    private val autoReceive: Boolean = true,
    private val timers: TransmissionTimers = TransmissionTimers(),
    private val receptionPriority: Int? = null,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(TxState.START_STOP)
    val state: StateFlow<TxState> = _state
    private val _reception = MutableStateFlow(RxState.IDLE)
    val reception: StateFlow<RxState> = _reception
    /** T100, T101 or T102: one runs at a time in the basic machine. */
    private var timer: Job? = null
    /** T103 or T104. */
    private var receptionTimer: Job? = null
    private var lastSequence: Int? = null

    /** Who is transmitting (null when idle or when we are). */
    @Volatile var transmitter: String? = null
        private set
    /** Whether the server relays the current transmission to us. */
    @Volatile var receiving = false
        private set
    /** The transmitting user's Audio and Video SSRCs, from the last Media Transmission Notification. */
    @Volatile var transmitterAudioSsrc: Long? = null
        private set
    @Volatile var transmitterVideoSsrc: Long? = null
        private set
    /** The transmitting user's functional alias, if the notification carried one. */
    @Volatile var transmitterAlias: String? = null
        private set
    /** Our place in the queue, from the last Queue Position Info (§9.2.3.4), while queued. */
    @Volatile var queuePosition: Int? = null
        private set
    /** The timeouts so far, oldest first (the counters' upper limits reached). */
    val timeouts: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun requestMessage() = TransmissionMessage.request(serverSsrc, priority)

    /**
     * The call is established. With [implicit], this client asked for the transmission in the SDP (a push's sender, a
     * pulled user): TS 24.581 §6.2.4.2.2 step 4, "shall start timer T100 (Transmission Request) and initialise counter
     * C100 (Transmission Request) to 1" and enter 'U: pending request to transmit', nothing being sent; T100's expiry
     * sends the Transmission Request (§6.2.4.4.3).
     */
    suspend fun start(implicit: Boolean = false) = lock.withLock {
        if (_state.value != TxState.START_STOP) return@withLock
        if (implicit) {
            _state.value = TxState.PENDING_REQUEST
            run(requestMessage(), timers.t100Ms, timers.c100, sentFirst = false, ::requestExpired)
        } else _state.value = TxState.NO_PERMISSION
    }

    /**
     * TS 24.581 §12.1.2.2 NOTE 3 and §14.2.4: the SDP answer's "mc_granted" grants our implicit request (offered with
     * "mc_granted"): handled as a Transmission Granted (§6.2.4.4.6 steps 3 to 5), T100 stopped. A Transmission Granted
     * the server sends afterwards (the NOTE to §14.2.4) finds 'U: has permission to transmit' and is not a second grant.
     */
    suspend fun grantedInAnswer(audioSsrc: Long?, videoSsrc: Long?) = lock.withLock {
        if (_state.value != TxState.PENDING_REQUEST && _state.value != TxState.NO_PERMISSION) return@withLock
        timer?.cancel()
        _state.value = TxState.HAS_PERMISSION
        transmitter = null
        listener.granted(audioSsrc, videoSsrc, null)
    }

    /** Ask the server to start or end [user]'s transmission (TS 23.281 §7.7.1.3.7, §7.7.1.3.6.3). */
    suspend fun remote(user: String, start: Boolean) = lock.withLock { send(TransmissionMessage.remote(serverSsrc, user, start)) }

    /** The user pressed Transmit: §6.2.4.3.2, Transmission Request, T100 started with C100 at 1. */
    suspend fun request() = lock.withLock {
        if (_state.value != TxState.NO_PERMISSION) return@withLock
        _state.value = TxState.PENDING_REQUEST
        run(requestMessage(), timers.t100Ms, timers.c100, sentFirst = true, ::requestExpired)
    }

    /** §6.2.4.4.4: T100 expired N times: 'U: has no permission to transmit'. */
    private suspend fun requestExpired() {
        if (_state.value != TxState.PENDING_REQUEST) return
        _state.value = TxState.NO_PERMISSION
        timeout("transmission request timeout")
        listener.requestFailed()
    }

    /**
     * The user released Transmit, or cancelled a pending or queued request: §6.2.4.5.3 (with the media stopped first),
     * §6.2.4.4.7 (T100 stopped) and §6.2.4.9.4: Transmission end request, T101 started with C101 at 1, 'U: pending end
     * of transmission'. On-network the participant never sends Transmission Release (§9.2.7): no state of §6.2.4 does.
     */
    suspend fun release() = lock.withLock {
        when (_state.value) {
            TxState.HAS_PERMISSION, TxState.PENDING_REQUEST, TxState.QUEUED -> {
                if (_state.value == TxState.HAS_PERMISSION) listener.stopTransmitting()
                startEnd()
            }
            else -> Unit
        }
    }

    private suspend fun startEnd() {
        _state.value = TxState.PENDING_RELEASE
        queuePosition = null
        run(TransmissionMessage.endRequest(serverSsrc), timers.t101Ms, timers.c101, sentFirst = true) {
            // §6.2.4.6.3: T101 expired N times: 'U: has no permission to transmit'.
            if (_state.value == TxState.PENDING_RELEASE) {
                _state.value = TxState.NO_PERMISSION
                timeout("transmission end request timeout")
            }
        }
    }

    /** §6.2.4.9.3: in 'U: queued transmission', Queue Position Request, T102 started with C102 at 1. */
    suspend fun queuePositionRequest(): Boolean = lock.withLock {
        if (_state.value != TxState.QUEUED) return@withLock false
        run(TransmissionMessage(TxType.QUEUE_POSITION_REQUEST, serverSsrc), timers.t102Ms, timers.c102, sentFirst = true) {
            // §6.2.4.9.8: T102 expired N times: Transmission end request, T101, 'U: pending end of transmission'.
            if (_state.value == TxState.QUEUED) {
                timeout("queued timeout")
                startEnd()
            }
        }
        true
    }

    /**
     * §6.2.5.5.3: the user stops receiving the current transmission: Media reception end request, T104 started with
     * C104 at 1, 'U: pending reception release'; §6.2.5.4.6 the same from 'U: pending request to receive' (T103 stopped).
     */
    suspend fun endReception(): Boolean = lock.withLock {
        if (_reception.value != RxState.HAS_PERMISSION && _reception.value != RxState.PENDING_REQUEST) return@withLock false
        _reception.value = RxState.PENDING_RELEASE
        receiving = false
        runReception(TransmissionMessage(TxType.RECEPTION_END_REQUEST, serverSsrc), timers.t104Ms, timers.c104) {
            // §6.2.5.6.3: T104 expired N times: 'terminated'.
            if (_reception.value == RxState.PENDING_RELEASE) { _reception.value = RxState.TERMINATED; timeout("receive media release timeout") }
        }
        true
    }

    /** §6.2.5.3.3: accept the notified transmission: Receive Media Request, T103 started with C103 at 1. */
    private suspend fun requestReception() {
        _reception.value = RxState.PENDING_REQUEST
        runReception(TransmissionMessage.receiveMedia(serverSsrc, receptionPriority), timers.t103Ms, timers.c103) {
            // §6.2.5.4.4: T103 expired N times: 'terminated'.
            if (_reception.value == RxState.PENDING_REQUEST) {
                _reception.value = RxState.TERMINATED
                timeout("receive media request timeout")
                listener.reception(false, null)
            }
        }
    }

    /** §6.2.4.7.2: MCVideo call release: every timer stops and no more control messages are sent. */
    suspend fun stop() = lock.withLock {
        timer?.cancel(); receptionTimer?.cancel()
        if (_state.value == TxState.HAS_PERMISSION) listener.stopTransmitting()
        _state.value = TxState.START_STOP
    }

    private fun timeout(what: String) { timeouts += what; listener.timeout(what) }

    /** A transmission control message from the server. */
    suspend fun receive(message: TransmissionMessage) = lock.withLock {
        if (_state.value == TxState.START_STOP || !message.type.fromServer) return@withLock
        if (message.ackRequired) send(TransmissionMessage.ack(serverSsrc, message.type))
        when (message.type) {
            // Granted after our request (§6.2.4.4.6 step 4, T100 stopped), while queued (§6.2.4.9.9, T102 stopped), or
            // unasked when another user started our transmission remotely.
            TxType.GRANTED -> if (_state.value == TxState.PENDING_REQUEST || _state.value == TxState.NO_PERMISSION || _state.value == TxState.QUEUED) {
                timer?.cancel()
                _state.value = TxState.HAS_PERMISSION
                transmitter = null; queuePosition = null
                listener.granted(message.audioSsrc, message.videoSsrc, message.durationSeconds)
            }
            // §6.2.4.4.2 steps 4 and 5: T100 stopped, 'U: has no permission to transmit'.
            TxType.REJECTED -> if (_state.value == TxState.PENDING_REQUEST) {
                timer?.cancel()
                _state.value = TxState.NO_PERMISSION
                listener.rejected(message.rejectCause, message.rejectText)
            }
            // §6.2.4.4.5 (T100's normal stop, table 11.1.1-1) and §6.2.4.9.2 (T102 stopped): 'U: queued transmission'.
            TxType.QUEUE_POSITION_INFO -> if (_state.value == TxState.PENDING_REQUEST || _state.value == TxState.QUEUED) {
                timer?.cancel()
                _state.value = TxState.QUEUED
                queuePosition = message.queuePosition
                listener.queued(queuePosition)
            }
            // §6.2.4.9.6 step 2: 'U: has no permission to transmit'.
            TxType.CANCEL_REQUEST_NOTIFY -> if (_state.value == TxState.QUEUED) {
                timer?.cancel()
                _state.value = TxState.NO_PERMISSION
                queuePosition = null
                listener.rejected(message.rejectCause, "queued request cancelled")
            }
            // §6.2.4.6.4 steps 4 and 5: T101 stopped, 'U: has no permission to transmit'.
            TxType.END_RESPONSE -> if (_state.value == TxState.PENDING_RELEASE) {
                timer?.cancel()
                _state.value = TxState.NO_PERMISSION
                listener.ended(byServer = false)
            }
            // §6.2.4.5.7: the server ends our permission: media stopped, Transmission End Response, no permission.
            TxType.END_REQUEST -> if (_state.value == TxState.HAS_PERMISSION) {
                listener.stopTransmitting()
                send(TransmissionMessage(TxType.END_RESPONSE, serverSsrc))
                _state.value = TxState.NO_PERMISSION
                listener.ended(byServer = true)
            }
            TxType.TAKEN -> if (fresh(message)) {
                when (_state.value) {
                    TxState.PENDING_REQUEST, TxState.PENDING_RELEASE -> { timer?.cancel(); _state.value = TxState.NO_PERMISSION }
                    TxState.HAS_PERMISSION -> { listener.stopTransmitting(); _state.value = TxState.NO_PERMISSION }
                    else -> Unit
                }
                transmitter = message.transmittingUser
                listener.taken(transmitter)
            }
            TxType.IDLE, TxType.ARBITRATION_RELEASE -> if (fresh(message)) {
                when (_state.value) {
                    // A server that answers the end with Idle alone: the transmission has ended all the same.
                    TxState.PENDING_RELEASE -> { timer?.cancel(); _state.value = TxState.NO_PERMISSION }
                    TxState.HAS_PERMISSION -> { listener.stopTransmitting(); _state.value = TxState.NO_PERMISSION }
                    else -> Unit
                }
                if (_state.value != TxState.PENDING_REQUEST && _state.value != TxState.QUEUED) {
                    transmitter = null; transmitterAudioSsrc = null; transmitterVideoSsrc = null; transmitterAlias = null
                    receiving = false; receptionTimer?.cancel(); _reception.value = RxState.IDLE
                    listener.idle()
                }
            }
            // §6.2.4.5.5: media stopped, then (4a) Transmission end request with T101, 'U: pending end of transmission'.
            TxType.REVOKED -> if (_state.value == TxState.HAS_PERMISSION) {
                listener.stopTransmitting()
                listener.revoked(message.rejectCause)
                startEnd()
            }
            TxType.MEDIA_TRANSMISSION_NOTIFICATION -> {
                // TS 24.581 §6.2.5.3.2 (reception control, 'U: reception controller'): tell the user (step 2), store the
                // transmitting user's ID, Audio and Video SSRCs (step 3) and functional alias (step 4). With Reception Mode
                // '0' the reception starts at once ('U: has permission to receive', step 5); with '1' the user accepts it
                // with a Receive Media Request (§6.2.5.3.3), sent at once with automatic acceptance. The transmission
                // control state (§6.2.4) does not change: the server sends this to the other participants.
                transmitter = message.transmittingUser
                transmitterAudioSsrc = message.audioSsrc
                transmitterVideoSsrc = message.videoSsrc
                transmitterAlias = message.functionalAlias
                listener.taken(transmitter)
                receptionTimer?.cancel()
                if (message.automaticReception == true) {
                    receiving = true
                    _reception.value = RxState.HAS_PERMISSION
                    listener.reception(true, null)
                } else {
                    receiving = false
                    _reception.value = RxState.IDLE
                    if (autoReceive) requestReception()
                }
            }
            TxType.MEDIA_RECEPTION_NOTIFICATION -> {
                // §6.2.4: a new transmission to receive; accepted at once (automatic reception).
                receiving = false
                message.userId?.let { transmitter = it }
                receptionTimer?.cancel()
                _reception.value = RxState.IDLE
                if (autoReceive) requestReception()
            }
            TxType.REMOTE_RESPONSE, TxType.REMOTE_CANCEL_RESPONSE -> listener.remoteResult(message.type == TxType.REMOTE_RESPONSE, message.result)
            // §6.2.5.4.5 (granted: T103 stopped, 'U: has permission to receive') and §6.2.5.4.2 (rejected: 'terminated').
            TxType.RECEIVE_MEDIA_RESPONSE -> {
                receptionTimer?.cancel()
                val cause = message.rejectCause
                receiving = cause == null
                _reception.value = if (receiving) RxState.HAS_PERMISSION else RxState.TERMINATED
                listener.reception(receiving, cause)
            }
            // §6.2.5.6.4: T104 stopped, 'terminated'.
            TxType.RECEPTION_END_RESPONSE -> if (_reception.value == RxState.PENDING_RELEASE) {
                receptionTimer?.cancel()
                _reception.value = RxState.TERMINATED
                listener.reception(false, null)
            }
            // §6.2.5.5.5: the server ends our reception: Media reception end response, 'terminated'.
            TxType.RECEPTION_END_REQUEST -> {
                send(TransmissionMessage(TxType.RECEPTION_END_RESPONSE, serverSsrc))
                receptionTimer?.cancel()
                receiving = false
                _reception.value = RxState.TERMINATED
                listener.reception(false, null)
            }
            else -> Unit
        }
    }

    /** Idle and Taken carry a sequence number; drop ones older than the last seen. */
    private fun fresh(message: TransmissionMessage): Boolean {
        val seq = message.sequence ?: return true
        val last = lastSequence
        if (last != null && ((seq - last) and 0xffff) >= 0x8000 && seq != last) return false
        lastSequence = seq
        return true
    }

    /**
     * A transmission timer for [message]: sent now unless [sentFirst] is false (the implicit request), the counter at 1;
     * on each expiry below [limit] sent again with the counter incremented (§6.2.4.4.3, §6.2.4.6.2, §6.2.4.9.7); at the
     * limit [expired] runs under the lock.
     */
    private suspend fun run(message: TransmissionMessage, periodMs: Long, limit: Int, sentFirst: Boolean, expired: suspend () -> Unit) {
        timer?.cancel()
        if (sentFirst) send(message)
        timer = scope.launch { cycle(message, periodMs, limit, expired) }
    }

    private suspend fun runReception(message: TransmissionMessage, periodMs: Long, limit: Int, expired: suspend () -> Unit) {
        receptionTimer?.cancel()
        send(message)
        receptionTimer = scope.launch { cycle(message, periodMs, limit, expired) }
    }

    /** After the first transmission (counter 1): again at each expiry below [limit], then [expired] under the lock. */
    private suspend fun cycle(message: TransmissionMessage, periodMs: Long, limit: Int, expired: suspend () -> Unit) {
        var count = 1
        while (true) {
            delay(periodMs)
            if (count >= limit) break
            send(message); count++
        }
        lock.withLock { expired() }
    }
}

/**
 * TS 24.581 §14.2.3, §14.2.6, §14.3.3, §14.4 and §12.1.2.2: the transmission and reception priorities of a call, from
 * the configured priority (the MCVideo user profile's <Priority>, TS 24.484 §9.3.2.1).
 */
object TransmissionPriority {
    /**
     * §14.2.3 and §14.2.6: "mc_priority" / "mc_reception_priority" offered "when a ... priority different than the
     * default priority is required", with the configured value; the SDP syntax has at most two digits (§12.1.2.3).
     */
    fun offered(configured: Int): Int? = if (configured <= 0) null else minOf(configured, 99)

    /**
     * The negotiated maximum: as answerer, the offered value returned (§14.3.3); as offerer, an answer parameter the
     * offer lacked is discarded (§14.4) and the answer's value is at most the offered one (§12.1.2.2); absent: the
     * default priority (NOTEs 1 and 2).
     */
    fun negotiated(answerer: Boolean, ours: Int?, theirs: Int?): Int? =
        if (answerer) theirs else if (ours == null || theirs == null) null else minOf(ours, theirs)

    /**
     * §6.2.4.3.2 step 2a and §6.2.5.3.3 step 1a: the priority field of a request, "not higher than negotiated"; none
     * (the field left out, the default priority of §9.2.3.2 and §9.2.3.19) for the default or nothing negotiated.
     */
    fun request(configured: Int, negotiated: Int?): Int? =
        if (configured <= 0 || negotiated == null || negotiated == 0) null else minOf(configured, negotiated)
}
