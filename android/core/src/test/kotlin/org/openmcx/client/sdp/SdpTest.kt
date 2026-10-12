//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.sdp

import org.junit.Assert.assertEquals
import org.junit.Test

class SdpTest {
    private val offer = "v=0\r\no=- 1 1 IN IP4 10.0.0.1\r\ns=-\r\nc=IN IP4 10.0.0.1\r\nt=0 0\r\n" +
        "m=audio 20000 RTP/AVP 97\r\ni=speech\r\na=rtpmap:97 AMR-WB/16000/1\r\na=fmtp:97 mode-change-capability=2; max-red=0\r\n" +
        "a=rtcp:20001\r\na=sendrecv\r\n" +
        "m=application 20002 udp MCPTT\r\nc=IN IP4 10.0.0.2\r\na=fmtp:MCPTT mc_queueing;mc_priority=4\r\n"

    @Test
    fun parsesAndRoundTrips() {
        val sdp = SessionDescription.parse(offer)
        assertEquals(2, sdp.media.size)
        val audio = sdp.media[0]
        assertEquals("AMR-WB/16000/1", audio.rtpmap[97])
        assertEquals("mode-change-capability=2; max-red=0", audio.fmtp("97"))
        assertEquals(20001, audio.rtcpPort)
        assertEquals("speech", audio.info)
        assertEquals("10.0.0.1", sdp.address(audio))
        val floor = sdp.media[1]
        assertEquals("10.0.0.2", sdp.address(floor))
        assertEquals("mc_queueing;mc_priority=4", floor.fmtp("MCPTT"))
        assertEquals(offer, sdp.encode())
    }
}
