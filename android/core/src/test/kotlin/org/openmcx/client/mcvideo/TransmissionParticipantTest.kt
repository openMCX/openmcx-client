//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push, pull and remote transmission in the transmission participant. */
class TransmissionParticipantTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = mutableListOf<TransmissionMessage>()
    private val events = mutableListOf<String>()
    private val listener = object : TransmissionListener {
        override fun granted(audioSsrc: Long?, videoSsrc: Long?, durationSeconds: Int?) { events += "granted $videoSsrc" }
        override suspend fun stopTransmitting() { events += "stop" }
        override fun revoked(cause: Int?) { events += "revoked $cause" }
        override fun remoteResult(start: Boolean, done: Boolean?) { events += "remote ${if (start) "start" else "stop"} $done" }
        override fun taken(user: String?) { events += "taken $user" }
        override fun idle() { events += "idle" }
        override fun reception(receiving: Boolean, rejectCause: Int?) { events += "reception $receiving $rejectCause" }
    }
    private val participant = TransmissionParticipant(scope, 42, { synchronized(sent) { sent += it } }, listener)
    private fun notification(mode: Int) = TransmissionMessage(TxType.MEDIA_TRANSMISSION_NOTIFICATION, 42, fields = listOf(
        TxField.TRANSMITTING_USER_ID to "sip:bob.mcvideo@mcx.test".toByteArray(),
        TxField.AUDIO_SSRC to TransmissionMessage.ssrcValue(0x11), TxField.VIDEO_SSRC to TransmissionMessage.ssrcValue(0x22),
        TxField.RECEPTION_MODE to TransmissionMessage.u16(mode)))
    private fun granted() = TransmissionMessage(TxType.GRANTED, 42, fields = listOf(TxField.VIDEO_SSRC to TransmissionMessage.ssrcValue(7)))

    @After fun tearDown() = scope.cancel()

    /** The sender of a push waits for the grant of its implicit request without sending one. */
    @Test fun implicitRequestIsGrantedWithoutARequest() = runBlocking {
        participant.start(implicit = true)
        assertEquals(TxState.PENDING_REQUEST, participant.state.value)
        participant.receive(granted())
        assertEquals(TxState.HAS_PERMISSION, participant.state.value)
        assertEquals(listOf("granted 7"), events)
        assertTrue(sent.none { it.type == TxType.REQUEST })
    }

    /** Another user starts our transmission (unasked grant), then ends it (revoke, cause 5). */
    @Test fun remoteStartAndStop() = runBlocking {
        participant.start()
        participant.receive(granted())
        assertEquals(TxState.HAS_PERMISSION, participant.state.value)
        participant.receive(TransmissionMessage(TxType.REVOKED, 42, fields = listOf(TxField.REJECT_CAUSE to TransmissionMessage.u16(5))))
        assertEquals(listOf("granted 7", "stop", "revoked 5"), events)
        assertEquals(TxState.PENDING_RELEASE, participant.state.value)
    }

    @Test fun remoteRequestAndItsResult() = runBlocking {
        participant.start()
        participant.remote("sip:bob.mcvideo@mcx.test", start = true)
        assertEquals(TxType.REMOTE_REQUEST, sent.last().type)
        assertEquals("sip:bob.mcvideo@mcx.test", sent.last().userId)
        participant.receive(TransmissionMessage(TxType.REMOTE_RESPONSE, 42, fields = listOf(TxField.RESULT to byteArrayOf(0, 0))))
        participant.receive(TransmissionMessage(TxType.REMOTE_CANCEL_RESPONSE, 42, fields = listOf(TxField.RESULT to byteArrayOf(1, 0))))
        assertEquals(listOf("remote start false", "remote stop true"), events)
    }

    /**
     * Media Transmission Notification with Reception Mode '0' (TS 24.581 §6.2.5.3.2 step 5): the transmitter and its
     * SSRCs are known and the reception starts at once, without a Receive Media Request; Idle clears them.
     */
    @Test fun automaticReceptionStartsAtOnce() = runBlocking {
        participant.start()
        participant.receive(notification(0))
        assertEquals("sip:bob.mcvideo@mcx.test", participant.transmitter)
        assertEquals(0x11L, participant.transmitterAudioSsrc); assertEquals(0x22L, participant.transmitterVideoSsrc)
        assertTrue(participant.receiving)
        assertTrue(sent.none { it.type == TxType.RECEIVE_MEDIA_REQUEST })
        assertEquals(listOf("taken sip:bob.mcvideo@mcx.test", "reception true null"), events)
        assertEquals(TxState.NO_PERMISSION, participant.state.value)
        participant.receive(TransmissionMessage(TxType.IDLE, 42))
        assertEquals(null, participant.transmitter); assertEquals(null, participant.transmitterVideoSsrc)
        assertFalse(participant.receiving)
    }

    /** With Reception Mode '1' the user accepts with a Receive Media Request (§6.2.5.3.3), automatic here; the answer starts it. */
    @Test fun manualReceptionAsksForTheMedia() = runBlocking {
        participant.start()
        participant.receive(notification(1))
        assertEquals("sip:bob.mcvideo@mcx.test", participant.transmitter)
        assertFalse(participant.receiving)
        assertEquals(TxType.RECEIVE_MEDIA_REQUEST, sent.single().type)
        participant.receive(TransmissionMessage(TxType.RECEIVE_MEDIA_RESPONSE, 42))
        assertTrue(participant.receiving)
        assertEquals(listOf("taken sip:bob.mcvideo@mcx.test", "reception true null"), events)
    }

    /** The notification acknowledged when asked (§6.2.5.3.2 step 1), and our own transmission state left as it is. */
    @Test fun notificationIsAcknowledgedAndKeepsTheTransmissionState() = runBlocking {
        participant.start()
        participant.request()
        participant.receive(notification(0).copy(ackRequired = true))
        val ack = sent.first { it.type == TxType.ACK }
        assertEquals(6, ack.field(TxField.MESSAGE_TYPE)!![0].toInt())
        assertEquals(TxState.PENDING_REQUEST, participant.state.value)
    }
}
