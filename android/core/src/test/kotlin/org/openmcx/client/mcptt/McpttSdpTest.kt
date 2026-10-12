package org.openmcx.client.mcptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpttSdpTest {
    private val local = LocalMedia("10.0.0.2", 20000, 20002, 111)

    @Test fun withFloorControl() {
        val offer = McpttSdp.offer(local)
        assertTrue(offer.contains("m=application 20002 udp MCPTT\r\na=fmtp:MCPTT mc_floor_ssrc=111\r\n"))
        assertFalse(offer.contains("a=ssrc"))
        val remote = McpttSdp.remote(offer)
        assertTrue(remote.floorControl); assertEquals(111L, remote.floorSsrc)
    }

    /** TS 24.379 §11.1.2.2: no media-floor control section, and the SSRC the speech is sent with. */
    @Test fun withoutFloorControl() {
        val offer = McpttSdp.offer(local.copy(floorControl = false, audioSsrc = 222))
        assertFalse(offer.contains("m=application"))
        assertTrue(offer.contains("a=ssrc:222 cname:mcptt\r\n"))
        val remote = McpttSdp.remote(offer)
        assertFalse(remote.floorControl)
        // The answer to an offer without floor control has none either.
        val answer = McpttSdp.answer(remote, local.copy(floorControl = false, audioSsrc = 333))
        assertFalse(answer.contains("m=application"))
        assertTrue(answer.contains("a=ssrc:333 cname:mcptt"))
        assertFalse(McpttSdp.remote(answer).floorControl)
    }

    /** The server's answer to a call without floor control: audio only. */
    @Test fun serverAnswerWithoutFloor() {
        val sdp = "v=0\r\no=- 5 1 IN IP4 172.20.0.5\r\ns=MCPTT\r\nc=IN IP4 172.20.0.5\r\nt=0 0\r\n" +
            "m=audio 3000 RTP/AVP 96\r\ni=speech\r\na=rtpmap:96 AMR-WB/16000/1\r\na=fmtp:96 octet-align=0;mode-change-capability=2\r\n" +
            "a=ptime:20\r\na=maxptime:240\r\na=sendrecv\r\na=rtcp:3001\r\na=ssrc:4242 cname:mcptt\r\n"
        val r = McpttSdp.remote(sdp)
        assertFalse(r.floorControl); assertEquals(3000, r.audioPort); assertEquals(0, r.floorPort)
    }

    /** TS 26.179 §4.1.3: max-red 0, ptime 20 and maxptime 240 offered, no mode-set; a peer's mode-set limits the mode sent. */
    @Test fun amrWbPerTs26179() {
        val offer = McpttSdp.offer(local)
        assertTrue(offer.contains("mode-change-capability=2;max-red=0\r\n"))
        assertTrue(offer.contains("a=ptime:20\r\na=maxptime:240\r\n"))
        assertFalse(offer.contains("mode-set"))
        val remote = McpttSdp.remote(offer.replace("max-red=0", "max-red=0;mode-set=0,1,2"))
        assertEquals(listOf(0, 1, 2), remote.modeSet)
        assertEquals(2, org.openmcx.client.media.AmrWb.sendMode(2, remote.modeSet))
        assertEquals(2, org.openmcx.client.media.AmrWb.sendMode(8, remote.modeSet))
        assertEquals(4, org.openmcx.client.media.AmrWb.sendMode(1, listOf(4, 6)))
        assertEquals(2, org.openmcx.client.media.AmrWb.sendMode(2, emptyList()))
        org.junit.Assert.assertThrows(SdpNegotiationError::class.java) { McpttSdp.remote(offer.replace("max-red=0", "mode-set=9")) }
    }

    /** TS 24.380 §14.2.2: `mc_queueing` offered when we queue, answered only when both sides support it. */
    @Test fun queueing() {
        val offer = McpttSdp.offer(local.copy(queueing = true))
        assertTrue(offer.contains("a=fmtp:MCPTT mc_floor_ssrc=111;mc_queueing\r\n"))
        val remote = McpttSdp.remote(offer)
        assertTrue(remote.queueing)
        assertTrue(McpttSdp.answer(remote, local.copy(queueing = true)).contains(";mc_queueing"))
        assertFalse("not answered when we do not queue", McpttSdp.answer(remote, local).contains("mc_queueing"))
        assertFalse("not answered when not offered", McpttSdp.answer(McpttSdp.remote(McpttSdp.offer(local)), local.copy(queueing = true)).contains("mc_queueing"))
        assertFalse(McpttSdp.offer(local).contains("mc_queueing"))
    }
}
