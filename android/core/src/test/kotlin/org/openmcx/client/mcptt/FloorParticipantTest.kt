package org.openmcx.client.mcptt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloorParticipantTest {
    private val server = 0xD741F472L
    private val sent = mutableListOf<FloorMessage>()
    private val events = mutableListOf<String>()
    private val listener = object : FloorListener {
        override fun granted(mediaSsrc: Long, durationSeconds: Int?) { events += "granted %08x %s".format(mediaSsrc, durationSeconds) }
        override suspend fun stopTalking() { events += "stop" }
        override fun taken(party: String?, mediaSsrc: Long?) { events += "taken $party" }
        override fun idle() { events += "idle" }
        override fun denied(cause: Int?, text: String?) { events += "denied $cause" }
        override fun revoked(cause: Int?) { events += "revoked $cause" }
        override fun requestFailed() { events += "failed" }
    }

    private fun TestScope.participant() = FloorParticipant(backgroundScope, server, { sent += it }, listener)

    private fun granted(ssrc: Long) = FloorMessage(FloorType.GRANTED, 1, true, listOf(
        FloorField.FLOOR_INDICATOR to FloorMessage.u16(FloorIndicator.NORMAL), FloorField.DURATION to FloorMessage.u16(30),
        FloorField.SSRC to byteArrayOf((ssrc shr 24).toByte(), (ssrc shr 16).toByte(), (ssrc shr 8).toByte(), ssrc.toByte(), 0, 0)))

    private fun idle(seq: Int) = FloorMessage(FloorType.IDLE, 1, true, listOf(FloorField.SEQUENCE to FloorMessage.u16(seq)))
    private fun taken(seq: Int, party: String) = FloorMessage(FloorType.TAKEN, 1, true,
        listOf(FloorField.SEQUENCE to FloorMessage.u16(seq), FloorField.GRANTED_PARTY to party.toByteArray()))

    @Test
    fun requestGrantTalkRelease() = runTest {
        val p = participant()
        p.start()
        p.receive(idle(1))
        assertEquals(FloorMessage.ack(server, FloorType.IDLE), sent.removeAt(0))
        p.request(); runCurrent()
        assertEquals(FloorType.REQUEST, sent.removeAt(0).type)
        assertEquals(FloorState.PENDING_REQUEST, p.state.value)
        p.receive(granted(0x93A1937FL))
        assertEquals(FloorMessage.ack(server, FloorType.GRANTED), sent.removeAt(0))
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        advanceTimeBy(5000)
        assertEquals("no request retransmission after the grant", 0, sent.size)
        p.release(); runCurrent()
        assertEquals(FloorType.RELEASE, sent.removeAt(0).type)
        p.receive(idle(2))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals(listOf("idle", "granted 93a1937f 30", "stop", "idle"), events)
    }

    @Test
    fun requestRetransmitsThenGivesUp() = runTest {
        val p = participant()
        p.start()
        p.request(); runCurrent()
        advanceTimeBy(1001); advanceTimeBy(1000)
        assertEquals(3, sent.count { it.type == FloorType.REQUEST })
        advanceTimeBy(1000)
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals(listOf("failed"), events)
    }

    @Test
    fun denyAndTakenWhilePending() = runTest {
        val p = participant()
        p.start()
        p.request(); runCurrent()
        p.receive(FloorMessage(FloorType.DENY, 1, true, listOf(FloorField.REJECT_CAUSE to FloorMessage.u16(1))))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        p.request(); runCurrent()
        p.receive(taken(3, "sip:bob.mcptt@mcx.test"))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals(listOf("denied 1", "taken sip:bob.mcptt@mcx.test"), events)
    }

    @Test
    fun revokeStopsMediaAndReleases() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(granted(5))
        sent.clear()
        p.receive(FloorMessage(FloorType.REVOKE, 1, false, listOf(FloorField.REJECT_CAUSE to FloorMessage.u16(2))))
        runCurrent()
        assertEquals(listOf(FloorType.RELEASE), sent.map { it.type })
        assertEquals(FloorState.PENDING_RELEASE, p.state.value)
        assertEquals(listOf("granted 00000005 30", "stop", "revoked 2"), events)
    }

    @Test
    fun staleSequenceNumbersAreIgnored() = runTest {
        val p = participant()
        p.start()
        p.receive(taken(5, "sip:bob.mcptt@mcx.test"))
        p.receive(idle(4))
        assertEquals("sip:bob.mcptt@mcx.test", p.talker)
        p.receive(idle(6))
        assertEquals(null, p.talker)
    }
}
