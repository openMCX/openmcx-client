package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    }
    private val participant = TransmissionParticipant(scope, 42, { synchronized(sent) { sent += it } }, listener)
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
}
