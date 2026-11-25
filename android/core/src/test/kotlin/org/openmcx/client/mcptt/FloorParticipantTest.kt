//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        override fun queued(position: Int?, priority: Int?) { events += "queued $position" }
        override fun queuedGrantOffered(seconds: Int) { events += "offered ${seconds}s" }
        override fun queuedGrantLapsed() { events += "lapsed" }
        override fun queueTimeout() { events += "queue timeout" }
    }

    private fun TestScope.participant() = FloorParticipant(backgroundScope, server, { sent += it }, listener)

    private fun granted(ssrc: Long) = FloorMessage(FloorType.GRANTED, 1, true, listOf(
        FloorField.FLOOR_INDICATOR to FloorMessage.u16(FloorIndicator.NORMAL), FloorField.DURATION to FloorMessage.u16(30),
        FloorField.SSRC to byteArrayOf((ssrc shr 24).toByte(), (ssrc shr 16).toByte(), (ssrc shr 8).toByte(), ssrc.toByte(), 0, 0)))

    private fun idle(seq: Int) = FloorMessage(FloorType.IDLE, 1, true, listOf(FloorField.SEQUENCE to FloorMessage.u16(seq)))
    private fun taken(seq: Int, party: String) = FloorMessage(FloorType.TAKEN, 1, true,
        listOf(FloorField.SEQUENCE to FloorMessage.u16(seq), FloorField.GRANTED_PARTY to party.toByteArray()))

    /**
     * TS 24.379 §6.4 (§11.1.6.2.1.2): a client asked to take the floor (the listened-to user of a remotely initiated
     * ambient listening call) takes a Floor Granted that comes without a request; any other ignores it.
     */
    @Test
    fun expectedGrantWithoutRequest() = runTest {
        val p = participant()
        p.start()
        p.receive(granted(0x11223344L))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        p.grantExpected = true
        p.receive(granted(0x11223344L))
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        assertEquals("granted 11223344 30", events.last())
    }

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

    /**
     * TS 24.380 §6.2.4.5.4: a pre-empted talker (a higher priority request, §4.1.1.4) is revoked: the media stops, the user
     * is told with the cause, and the Floor Release repeats the Floor Revoke's G-bit (Dual floor, items 4 and 5), with the
     * call's indicator; then 'U: pending Release' until the Floor Taken of the new talker (§6.2.4.6).
     */
    @Test
    fun preemptedTalkerReleasesWithTheDualFloorBit() = runTest {
        val p = FloorParticipant(backgroundScope, server, { sent += it }, listener, priority = 10,
            indicator = { FloorIndicator.EMERGENCY })
        p.start(); p.request(); runCurrent()
        assertEquals(10, sent.single().priority)
        p.receive(granted(5))
        sent.clear()
        p.receive(FloorMessage(FloorType.REVOKE, 1, false, listOf(FloorField.REJECT_CAUSE to FloorMessage.u16(4),
            FloorField.FLOOR_INDICATOR to FloorMessage.u16(FloorIndicator.EMERGENCY or FloorIndicator.DUAL_FLOOR))))
        runCurrent()
        val release = sent.single { it.type == FloorType.RELEASE }
        assertEquals(FloorIndicator.EMERGENCY or FloorIndicator.DUAL_FLOOR, release.floorIndicator)
        assertTrue("revoked 4" in events)
        p.receive(taken(9, "sip:bob.mcptt@mcx.test"))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals("sip:bob.mcptt@mcx.test", p.talker)
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

    private fun queueInfo(position: Int) = FloorMessage(FloorType.QUEUE_POSITION_INFO, 1, false,
        listOf(FloorField.QUEUE_INFO to byteArrayOf(position.toByte(), 10)))

    /** TS 24.380 §6.2.4.4.9, §6.2.4.9.9, §6.2.4.9.7 and §6.2.4.9.6: queued, asked for the position, leaves the queue. */
    @Test
    fun queuedRequestPositionAndLeave() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        sent.clear()
        p.receive(queueInfo(1))
        assertEquals(FloorState.QUEUED, p.state.value)
        assertEquals(1, p.queuePosition.value)
        advanceTimeBy(5000)
        assertEquals("T101 stopped once queued", 0, sent.size)
        p.receive(taken(7, "sip:alice.mcptt@mcx.test"))
        assertEquals("§6.2.4.9.3: still queued while someone talks", FloorState.QUEUED, p.state.value)
        sent.clear()
        assertTrue(p.requestQueuePosition()); runCurrent()
        assertEquals(FloorMessage.queuePositionRequest(server), sent.removeAt(0))
        p.receive(queueInfo(2))
        assertEquals(2, p.queuePosition.value)
        advanceTimeBy(5000)
        assertEquals("T104 stopped by the answer", 0, sent.size)
        p.release(); runCurrent()
        assertEquals(FloorType.RELEASE, sent.removeAt(0).type)
        assertEquals(FloorState.PENDING_RELEASE, p.state.value)
        assertEquals(null, p.queuePosition.value)
        p.receive(taken(8, "sip:alice.mcptt@mcx.test"))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals(listOf("queued 1", "taken sip:alice.mcptt@mcx.test", "queued 2", "taken sip:alice.mcptt@mcx.test"), events)
    }

    /** §6.2.4.9.4 and §6.2.4.9.12: a grant to a queued request while PTT is held is accepted at once. */
    @Test
    fun queuedGrantWithPttHeld() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(queueInfo(1))
        p.receive(granted(0x11223344L))
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        assertEquals(listOf("queued 1", "offered 2s", "granted 11223344 30"), events)
    }

    /** §6.2.4.9.12: without PTT held, the grant waits for the user; a repeated Floor Granted (T20) does not restart T132. */
    @Test
    fun queuedGrantConfirmed() = runTest {
        val p = participant()
        p.autoConfirm = false
        p.start(); p.request(); runCurrent()
        p.receive(queueInfo(1))
        p.receive(granted(5))
        assertEquals(FloorState.QUEUED, p.state.value)
        assertTrue(p.offered.value)
        assertFalse("no position request while a grant waits (§6.2.4.9.9)", p.requestQueuePosition())
        advanceTimeBy(1500)
        p.receive(granted(5))
        advanceTimeBy(400)
        assertTrue(p.confirm())
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        assertFalse(p.offered.value)
        assertEquals(listOf("queued 1", "offered 2s", "granted 00000005 30"), events)
    }

    /** §6.2.4.9.13: T132 runs out: Floor Release, 'U: has no permission'; later grants are only acknowledged. */
    @Test
    fun queuedGrantLapses() = runTest {
        val p = participant()
        p.autoConfirm = false
        p.start(); p.request(); runCurrent()
        p.receive(queueInfo(1))
        p.receive(granted(5))
        advanceTimeBy(1500)
        p.receive(granted(5))
        sent.clear()
        advanceTimeBy(600)
        assertEquals(listOf(FloorType.RELEASE), sent.map { it.type })
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertFalse(p.confirm())
        p.receive(granted(5))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        advanceTimeBy(5000)
        assertEquals("no retransmitted release (§6.2.4.9.13 starts no T100)", 1, sent.count { it.type == FloorType.RELEASE })
        assertEquals(listOf("queued 1", "offered 2s", "lapsed"), events)
    }

    /** §6.2.4.9.10 and §6.2.4.9.11: an unanswered position request is repeated C104 times, then the request is withdrawn. */
    @Test
    fun queuePositionRequestTimesOut() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(queueInfo(1))
        sent.clear()
        p.requestQueuePosition(); runCurrent()
        advanceTimeBy(1001); advanceTimeBy(1000)
        assertEquals(3, sent.count { it.type == FloorType.QUEUE_POSITION_REQUEST })
        advanceTimeBy(1000)
        assertEquals(FloorType.RELEASE, sent.last().type)
        assertEquals(FloorState.PENDING_RELEASE, p.state.value)
        assertEquals(listOf("queued 1", "queue timeout"), events)
    }

    /** §6.2.4.9.8 and §6.2.4.9.5: Floor Idle or Floor Deny ends the queued request. */
    @Test
    fun idleOrDenyEndsTheQueuedRequest() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(queueInfo(1))
        p.receive(idle(9))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        p.request(); runCurrent()
        p.receive(queueInfo(1))
        p.receive(FloorMessage(FloorType.DENY, 1, false, listOf(FloorField.REJECT_CAUSE to FloorMessage.u16(1))))
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals(null, p.queuePosition.value)
        assertEquals(listOf("queued 1", "idle", "queued 1", "denied 1"), events)
    }

    private fun indicatorOf(m: FloorMessage) = m.floorIndicator

    /** TS 24.380 §6.2.4.3.5 1 b): the call type in the Floor Indicator of the Floor Request and Release, and bit F. */
    @Test
    fun floorIndicatorOfTheCall() = runTest {
        var indicator = FloorIndicator.EMERGENCY or FloorIndicator.QUEUEING
        val p = FloorParticipant(backgroundScope, server, { sent += it }, listener, indicator = { indicator })
        p.start()
        p.request(); runCurrent()
        assertEquals(FloorIndicator.EMERGENCY or FloorIndicator.QUEUEING, indicatorOf(sent.removeAt(0)))
        p.receive(granted(1)); sent.clear()
        indicator = FloorIndicator.NORMAL // the emergency state cancelled during the call
        p.release(); runCurrent()
        val release = sent.first { it.type == FloorType.RELEASE }
        assertEquals(FloorIndicator.NORMAL, indicatorOf(release))
    }

    /**
     * TS 24.380 §6.2.4.2.2 step 4: an accepted implicit floor request: 'U: pending Request' with T101 running and no
     * Floor Request sent at once; the Floor Granted that follows grants the floor; at T101's expiry the request goes.
     */
    @Test
    fun implicitRequestAwaitsTheGrant() = runTest {
        val p = participant()
        p.startImplicit(null); runCurrent()
        assertEquals(FloorState.PENDING_REQUEST, p.state.value)
        assertEquals(0, sent.size)
        p.receive(granted(0x11223344L))
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        assertEquals(listOf("granted 11223344 30"), events)
        advanceTimeBy(5000)
        assertEquals("no Floor Request after the grant", 0, sent.count { it.type == FloorType.REQUEST })
    }

    @Test
    fun implicitRequestRetransmitsAtT101() = runTest {
        val p = participant()
        p.startImplicit(null); runCurrent()
        advanceTimeBy(1001)
        assertEquals(1, sent.count { it.type == FloorType.REQUEST })
        advanceTimeBy(1000)
        assertEquals(2, sent.count { it.type == FloorType.REQUEST })
        advanceTimeBy(1000)
        assertEquals(FloorState.NO_PERMISSION, p.state.value)
        assertEquals(listOf("failed"), events)
    }

    /**
     * TS 24.380 §14.2.4, §6.2.4.4.2: "mc_granted" in the 200 (OK): 'U: has permission' at once; the Floor Granted that
     * follows (NOTE to §14.2.4) is acknowledged and is not a second grant.
     */
    @Test
    fun grantInTheAnswerIsTheOnlyGrant() = runTest {
        val p = participant()
        p.startImplicit(0x0A0B0C0DL); runCurrent()
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        assertEquals(listOf("granted 0a0b0c0d null"), events)
        p.receive(granted(0x0A0B0C0DL))
        assertEquals(FloorMessage.ack(server, FloorType.GRANTED), sent.removeAt(0))
        assertEquals(FloorState.HAS_PERMISSION, p.state.value)
        assertEquals(1, events.count { it.startsWith("granted") })
        advanceTimeBy(5000)
        assertEquals(0, sent.count { it.type == FloorType.REQUEST })
    }
}
