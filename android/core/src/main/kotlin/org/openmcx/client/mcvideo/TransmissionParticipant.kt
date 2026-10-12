package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The transmission participant's states (TS 24.581 §6.2.4, on-network, without queueing). */
enum class TxState { START_STOP, NO_PERMISSION, PENDING_REQUEST, HAS_PERMISSION, PENDING_RELEASE }

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
}

/**
 * The transmission participant of one MCVideo call (TS 24.581 §6.2.4): transmission requests
 * and releases with retransmission (T100/T101 as in TS 24.380), and reception control (§6.2.5): a
 * Media Transmission Notification with Reception Mode '0' starts the reception at once, and one with
 * '1', like a Media Reception Notification, is accepted at once with a Receive Media Request
 * (automatic acceptance). [serverSsrc] is the server's `mc_transmission_ssrc`.
 */
class TransmissionParticipant(
    private val scope: CoroutineScope,
    private val serverSsrc: Long,
    private val send: suspend (TransmissionMessage) -> Unit,
    private val listener: TransmissionListener,
    private val priority: Int? = null,
    private val autoReceive: Boolean = true,
    private val t100Ms: Long = 1000, private val c100: Int = 3,
    private val t101Ms: Long = 1000, private val c101: Int = 3,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(TxState.START_STOP)
    val state: StateFlow<TxState> = _state
    private var timer: Job? = null
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

    /**
     * The call is established. With [implicit], this client asked for the transmission in the SDP (a
     * push's sender, a pulled user): it waits for the grant without sending a request.
     */
    suspend fun start(implicit: Boolean = false) = lock.withLock {
        if (_state.value == TxState.START_STOP) _state.value = if (implicit) TxState.PENDING_REQUEST else TxState.NO_PERMISSION
    }

    /** Ask the server to start or end [user]'s transmission (TS 23.281 §7.7.1.3.7, §7.7.1.3.6.3). */
    suspend fun remote(user: String, start: Boolean) = lock.withLock { send(TransmissionMessage.remote(serverSsrc, user, start)) }

    /** The user pressed Transmit. */
    suspend fun request() = lock.withLock {
        if (_state.value != TxState.NO_PERMISSION) return@withLock
        _state.value = TxState.PENDING_REQUEST
        retransmit(TransmissionMessage.request(serverSsrc, priority), t101Ms, c101) {
            if (_state.value == TxState.PENDING_REQUEST) { _state.value = TxState.NO_PERMISSION; listener.requestFailed() }
        }
    }

    /** The user released Transmit (or cancelled a pending request). */
    suspend fun release() = lock.withLock {
        when (_state.value) {
            TxState.HAS_PERMISSION, TxState.PENDING_REQUEST -> {
                if (_state.value == TxState.HAS_PERMISSION) listener.stopTransmitting()
                startRelease()
            }
            else -> Unit
        }
    }

    private fun startRelease() {
        _state.value = TxState.PENDING_RELEASE
        retransmit(TransmissionMessage.release(serverSsrc), t100Ms, c100) {
            if (_state.value == TxState.PENDING_RELEASE) _state.value = TxState.NO_PERMISSION
        }
    }

    suspend fun stop() = lock.withLock {
        timer?.cancel()
        if (_state.value == TxState.HAS_PERMISSION) listener.stopTransmitting()
        _state.value = TxState.START_STOP
    }

    /** A transmission control message from the server. */
    suspend fun receive(message: TransmissionMessage) = lock.withLock {
        if (_state.value == TxState.START_STOP || !message.type.fromServer) return@withLock
        if (message.ackRequired) send(TransmissionMessage.ack(serverSsrc, message.type))
        when (message.type) {
            // Granted after our request or an implicit one, or unasked when another user started our transmission remotely.
            TxType.GRANTED -> if (_state.value == TxState.PENDING_REQUEST || _state.value == TxState.NO_PERMISSION) {
                timer?.cancel()
                _state.value = TxState.HAS_PERMISSION
                transmitter = null
                listener.granted(message.audioSsrc, message.videoSsrc, message.durationSeconds)
            }
            TxType.REJECTED -> if (_state.value == TxState.PENDING_REQUEST) {
                timer?.cancel()
                _state.value = TxState.NO_PERMISSION
                listener.rejected(message.rejectCause, message.rejectText)
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
                    TxState.PENDING_RELEASE -> { timer?.cancel(); _state.value = TxState.NO_PERMISSION }
                    TxState.HAS_PERMISSION -> { listener.stopTransmitting(); _state.value = TxState.NO_PERMISSION }
                    else -> Unit
                }
                if (_state.value != TxState.PENDING_REQUEST) {
                    transmitter = null; transmitterAudioSsrc = null; transmitterVideoSsrc = null; transmitterAlias = null
                    receiving = false; listener.idle()
                }
            }
            TxType.REVOKED -> if (_state.value == TxState.HAS_PERMISSION) {
                listener.stopTransmitting()
                listener.revoked(message.rejectCause)
                startRelease()
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
                if (message.automaticReception == true) {
                    receiving = true
                    listener.reception(true, null)
                } else {
                    receiving = false
                    if (autoReceive) send(TransmissionMessage.receiveMedia(serverSsrc))
                }
            }
            TxType.MEDIA_RECEPTION_NOTIFICATION -> {
                // §6.2.4: a new transmission to receive; accepted at once (automatic reception).
                receiving = false
                message.userId?.let { transmitter = it }
                if (autoReceive) send(TransmissionMessage.receiveMedia(serverSsrc))
            }
            TxType.REMOTE_RESPONSE, TxType.REMOTE_CANCEL_RESPONSE -> listener.remoteResult(message.type == TxType.REMOTE_RESPONSE, message.result)
            TxType.RECEIVE_MEDIA_RESPONSE -> {
                val cause = message.rejectCause
                receiving = cause == null
                listener.reception(receiving, cause)
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

    private fun retransmit(message: TransmissionMessage, periodMs: Long, limit: Int, expired: suspend () -> Unit) {
        timer?.cancel()
        timer = scope.launch {
            repeat(limit) { send(message); delay(periodMs) }
            lock.withLock { expired() }
        }
    }
}
