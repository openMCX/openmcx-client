//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.mcptt.SdpNegotiationError
import kotlin.random.Random

class TransmissionTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** The server's literal Transmission Request fixture (tests/test_transmission.cpp). */
    @Test fun requestIsByteExact() {
        val request = TransmissionMessage.request(0x11223344, priority = 5)
        assertArrayEquals(bytes(0x80, 204, 0, 3, 0x11, 0x22, 0x33, 0x44, 'M'.code, 'C'.code, 'V'.code, '0'.code, 0, 2, 5, 0), request.encode())
        assertEquals(request, TransmissionMessage.decode(request.encode()))
    }

    @Test fun grantedCarriesAudioAndVideoSsrc() {
        val granted = TransmissionMessage(TxType.GRANTED, 7, ackRequired = true, fields = listOf(
            TxField.DURATION to TransmissionMessage.u16(30),
            TxField.AUDIO_SSRC to TransmissionMessage.ssrcValue(0xa1a2a3a4),
            TxField.VIDEO_SSRC to TransmissionMessage.ssrcValue(0xb1b2b3b4)))
        val encoded = granted.encode()
        assertEquals(0x80 or 0x10, encoded[0].toInt() and 0xff)
        assertEquals("MCV1", String(encoded, 8, 4))
        val back = TransmissionMessage.decode(encoded)
        assertEquals(granted, back)
        assertEquals(30, back.durationSeconds)
        assertEquals(0xa1a2a3a4, back.audioSsrc)
        assertEquals(0xb1b2b3b4, back.videoSsrc)
    }

    @Test fun subtypesAndNamesMatchTheServer() {
        assertEquals(0x80 or 15, TransmissionMessage(TxType.IDLE, 1).encode()[0].toInt() and 0xff)
        assertEquals(0x80 or 4, TransmissionMessage(TxType.ACK, 1).encode()[0].toInt() and 0xff)
        assertEquals(0x80 or 4, TransmissionMessage.receiveMedia(1).encode()[0].toInt() and 0xff)
        assertEquals("MCV0", String(TransmissionMessage.release(1).encode(), 8, 4))
        assertEquals(TxType.MEDIA_RECEPTION_NOTIFICATION, TransmissionMessage.decode(TransmissionMessage(TxType.MEDIA_RECEPTION_NOTIFICATION, 9,
            fields = listOf(TxField.USER_ID to "sip:alice.mcvideo@mcx.test".toByteArray())).encode()).type)
        val taken = TransmissionMessage.decode(TransmissionMessage(TxType.TAKEN, 1, fields = listOf(
            TxField.TRANSMITTING_USER_ID to "sip:bob.mcvideo@mcx.test".toByteArray(), TxField.MESSAGE_SEQUENCE to TransmissionMessage.u16(3))).encode())
        assertEquals("sip:bob.mcvideo@mcx.test", taken.transmittingUser); assertEquals(3, taken.sequence)
    }

    /** The server's Media Transmission Notification (TS 24.581 §9.2.13): the transmitter, its SSRCs and the Reception Mode. */
    @Test fun mediaTransmissionNotification() {
        fun notification(mode: Int) = TransmissionMessage(TxType.MEDIA_TRANSMISSION_NOTIFICATION, 9, fields = listOf(
            TxField.TRANSMITTING_USER_ID to "sip:bob.mcvideo@mcx.test".toByteArray(),
            TxField.AUDIO_SSRC to TransmissionMessage.ssrcValue(0xa1a2a3a4),
            TxField.VIDEO_SSRC to TransmissionMessage.ssrcValue(0xb1b2b3b4),
            TxField.PERMISSION_TO_REQUEST to TransmissionMessage.u16(1),
            TxField.INDICATOR to TransmissionMessage.u16(0x1000),
            TxField.RECEPTION_MODE to TransmissionMessage.u16(mode)))
        val encoded = notification(1).encode()
        assertEquals(0x80 or 6, encoded[0].toInt() and 0xff); assertEquals("MCV1", String(encoded, 8, 4))
        // §9.2.3.22: field ID 22, length 2, the value in two octets, as the server builds it.
        val at = encoded.indices.first { encoded[it].toInt() == 22 && encoded[it + 1].toInt() == 2 }
        assertArrayEquals(bytes(22, 2, 0, 1), encoded.copyOfRange(at, at + 4))
        val manual = TransmissionMessage.decode(encoded)
        assertEquals(TxType.MEDIA_TRANSMISSION_NOTIFICATION, manual.type)
        assertEquals("sip:bob.mcvideo@mcx.test", manual.transmittingUser)
        assertEquals(0xa1a2a3a4, manual.audioSsrc); assertEquals(0xb1b2b3b4, manual.videoSsrc)
        assertEquals(true, manual.permissionToRequest); assertEquals(0x1000, manual.indicator)
        assertEquals(false, manual.automaticReception)
        assertEquals(true, TransmissionMessage.decode(notification(0).encode()).automaticReception)
        assertNull(TransmissionMessage(TxType.MEDIA_TRANSMISSION_NOTIFICATION, 9).automaticReception)
        for (bad in listOf(bytes(0), bytes(0, 2), bytes(1, 0), bytes(0, 0, 0))) {
            val packet = TransmissionMessage(TxType.MEDIA_TRANSMISSION_NOTIFICATION, 9, fields = listOf(TxField.RECEPTION_MODE to bad)).encode()
            assertTrue(runCatching { TransmissionMessage.decode(packet) }.exceptionOrNull() is TransmissionCodecError)
        }
    }

    @Test fun remoteTransmissionRequestAndResult() {
        val start = TransmissionMessage.remote(9, "sip:bob.mcvideo@mcx.test", start = true)
        val encoded = start.encode()
        assertEquals("MCV0", String(encoded, 8, 4)); assertEquals(7, encoded[0].toInt() and 0x1f)
        assertEquals("sip:bob.mcvideo@mcx.test", TransmissionMessage.decode(encoded).userId)
        assertEquals(TxType.REMOTE_CANCEL_REQUEST, TransmissionMessage.remote(9, "u", start = false).type)
        fun response(result: Int) = TransmissionMessage(TxType.REMOTE_RESPONSE, 7, fields = listOf(TxField.RESULT to bytes(result, 0)))
        assertEquals(true, TransmissionMessage.decode(response(1).encode()).result)
        assertEquals(false, TransmissionMessage.decode(response(0).encode()).result)
        assertNull(TransmissionMessage(TxType.REMOTE_RESPONSE, 7).result)
    }

    @Test fun malformedPacketsAreRefused() {
        val void0 = TransmissionMessage.request(1).encode().also { it[0] = (0x80 or 5).toByte() }
        val void1 = TransmissionMessage(TxType.GRANTED, 1).encode().also { it[0] = (0x80 or 9).toByte() }
        val ackAck = TransmissionMessage(TxType.ACK, 1).encode().also { it[0] = (0x80 or 0x14).toByte() }
        val floor = TransmissionMessage.request(1).encode().also { "MCPT".toByteArray().copyInto(it, 8) }
        val badSsrc = TransmissionMessage(TxType.GRANTED, 1, fields = listOf(TxField.VIDEO_SSRC to bytes(1, 2, 3, 4, 0, 1))).encode()
        for (bad in listOf(void0, void1, ackAck, floor, badSsrc, bytes(0x80, 204, 0, 9)))
            assertTrue(runCatching { TransmissionMessage.decode(bad) }.exceptionOrNull() is TransmissionCodecError)
    }
}

class H264RtpTest {
    private fun nal(type: Int, size: Int, seed: Int) = ByteArray(size).also { Random(seed).nextBytes(it); it[0] = (0x60 or type).toByte() }

    @Test fun annexBSplitsAndJoins() {
        val nals = listOf(nal(7, 12, 1), nal(8, 4, 2), nal(5, 3000, 3))
        val stream = byteArrayOf(0, 0, 1) + nals[0] + byteArrayOf(0, 0, 0, 1) + nals[1] + byteArrayOf(0, 0, 0, 1) + nals[2]
        assertEquals(nals.map { it.toList() }, H264Rtp.splitAnnexB(stream).map { it.toList() })
        assertEquals(nals.map { it.toList() }, H264Rtp.splitAnnexB(H264Rtp.joinAnnexB(nals)).map { it.toList() })
    }

    @Test fun largeNalIsFragmentedAndReassembled() {
        val unit = listOf(nal(7, 12, 1), nal(8, 4, 2), nal(5, 5000, 3))
        val payloads = H264Rtp.packetize(unit)
        assertTrue(payloads.all { it.bytes.size <= H264Rtp.MAX_PAYLOAD })
        assertEquals(listOf(false, false) + List(payloads.size - 3) { false } + true, payloads.map { it.marker })
        val fu = payloads[2].bytes
        assertEquals(H264Rtp.FU_A, fu[0].toInt() and 0x1f)
        assertEquals(0x60, fu[0].toInt() and 0xe0) // the NRI is kept
        assertEquals(0x80 or 5, fu[1].toInt() and 0xff) // start bit and the NAL type
        assertEquals(0x40 or 5, payloads.last().bytes[1].toInt() and 0xff)
        val d = H264Depacketizer()
        val out = payloads.flatMapIndexed { i, p -> d.push(100 + i, 9000, p.marker, p.bytes) }
        assertEquals(1, out.size)
        assertEquals(unit.map { it.toList() }, out[0].map { it.toList() })
        assertTrue(H264Rtp.isKeyFrame(out[0]))
    }

    @Test fun stapAFromTheServerFixture() {
        val stap = byteArrayOf(0x78, 0, 2, 0x67, 0x42, 0, 3, 0x68, 0xce.toByte(), 0x38)
        val out = H264Depacketizer().push(7, 300, true, stap)
        assertEquals(listOf(listOf<Byte>(0x67, 0x42), listOf<Byte>(0x68, 0xce.toByte(), 0x38)), out.single().map { it.toList() })
        assertTrue(runCatching { H264Depacketizer().push(4, 1, true, byteArrayOf(0x78, 0, 9, 1)) }.exceptionOrNull() is H264Error)
        assertTrue(runCatching { H264Depacketizer().push(5, 1, true, byteArrayOf(0x7c, 0xc5.toByte(), 1)) }.exceptionOrNull() is H264Error)
    }

    @Test fun lossDiscardsOnlyTheDamagedPicture() {
        val first = H264Rtp.packetize(listOf(nal(5, 4000, 1)))
        val second = H264Rtp.packetize(listOf(nal(1, 3000, 2)))
        val d = H264Depacketizer()
        var seq = 0
        val out = mutableListOf<List<ByteArray>>()
        first.forEachIndexed { i, p -> if (i != 1) out += d.push(seq, 1000, p.marker, p.bytes); seq++ } // one packet lost
        second.forEach { p -> out += d.push(seq++, 7000, p.marker, p.bytes) }
        assertEquals(1, out.size)
        assertEquals(1, H264Rtp.nalType(out[0][0]))
        assertEquals(1, d.lost)
        // A picture whose marker packet is lost is dropped when the next one starts; the lost packets could
        // also have been the next one's first slice, so after the gap it is dropped too.
        val e = H264Depacketizer()
        val third = H264Rtp.packetize(listOf(nal(1, 3000, 3)))
        third.dropLast(1).forEachIndexed { i, p -> e.push(i, 1, p.marker, p.bytes) }
        assertTrue(e.push(third.size, 2, true, nal(1, 20, 4)).isEmpty())
        assertEquals(2, e.lost)
        // Without a gap the next picture after an ended one is whole.
        assertEquals(1, e.push(third.size + 1, 3, true, nal(1, 20, 5)).size)
        // A gap that ends in the middle of a fragmented NAL unit damages the new picture too.
        val f = H264Depacketizer()
        val fourth = H264Rtp.packetize(listOf(nal(1, 3000, 5)))
        f.push(0, 1, true, nal(1, 10, 6))
        fourth.drop(1).forEachIndexed { i, p -> f.push(2 + i, 2, p.marker, p.bytes) }
        assertEquals(1, f.lost)
        // A picture of two slices whose first slice is lost, after a picture that ended cleanly: dropped.
        val g = H264Depacketizer()
        assertEquals(1, g.push(0, 1, true, nal(5, 10, 7)).size)
        val slices = H264Rtp.packetize(listOf(nal(1, 50, 8), nal(1, 50, 9)))
        assertTrue(g.push(2, 2, slices[1].marker, slices[1].bytes).isEmpty()) // slices[0] (seq 1) lost
        assertEquals(1, g.lost)
    }
}

class McvideoSdpTest {
    private val local = LocalVideoMedia("10.0.0.2", 20000, 20004, 20002, 111, 222, 333)

    @Test fun offerHasAudioVideoAndTransmissionControl() {
        val offer = McvideoSdp.offer(local)
        assertTrue(offer.contains("m=audio 20000 RTP/AVP 96\r\n"))
        assertTrue(offer.contains("a=rtpmap:96 AMR-WB/16000/1\r\n"))
        assertTrue(offer.contains("a=ssrc:222 cname:mcvideo\r\n"))
        assertTrue(offer.contains("m=video 20004 RTP/AVP 97\r\n"))
        assertTrue(offer.contains("a=fmtp:97 profile-level-id=42e01f;packetization-mode=1\r\n"))
        assertTrue(offer.contains("a=rtcp:20005\r\n"))
        assertTrue(offer.contains("a=ssrc:333 cname:mcvideo\r\n"))
        assertTrue(offer.contains("m=application 20002 udp MCVideo\r\na=fmtp:MCVideo mc_transmission_ssrc=111\r\n"))
        assertFalse(offer.contains("mc_queueing"))
        val parsed = McvideoSdp.remote(offer)
        assertEquals(listOf(20000, 96, 20004, 97, 20002), listOf(parsed.audioPort, parsed.audioPayloadType, parsed.videoPort, parsed.videoPayloadType, parsed.controlPort))
        assertEquals(111L, parsed.controlSsrc)
        assertFalse(McvideoSdp.offer(local.copy(controlPort = null)).contains("m=application"))
    }

    /** An answer shaped like the server's (protocol.cpp make_sdp), with its own payload types. */
    @Test fun serverAnswerIsRead() {
        val answer = "v=0\r\no=- 5 1 IN IP4 172.20.0.9\r\ns=MCVideo\r\nc=IN IP4 172.20.0.9\r\nt=0 0\r\n" +
            "m=audio 4200 RTP/AVP 100\r\na=rtpmap:100 AMR-WB/16000/1\r\na=fmtp:100 octet-align=0;mode-change-capability=2\r\na=rtcp:4201\r\n" +
            "m=video 6200 RTP/AVP 101\r\na=rtpmap:101 H264/90000\r\na=fmtp:101 profile-level-id=42e01f;packetization-mode=1\r\na=rtcp:6201\r\n" +
            "m=application 5200 udp MCVideo\r\na=fmtp:MCVideo mc_transmission_ssrc=4242:mc_priority=3\r\n"
        val r = McvideoSdp.remote(answer)
        assertEquals("172.20.0.9", r.address)
        assertEquals(listOf(4200, 100, 6200, 101, 5200), listOf(r.audioPort, r.audioPayloadType, r.videoPort, r.videoPayloadType, r.controlPort))
        assertEquals(4242L, r.controlSsrc); assertEquals(3, r.priority)
        val noControl = McvideoSdp.remote(answer.substringBefore("m=application"))
        assertFalse(noControl.transmissionControl); assertNull(noControl.controlSsrc)
        val mine = McvideoSdp.answer(r, local)
        assertTrue(mine.contains("m=video 20004 RTP/AVP 101\r\n") && mine.contains("mc_priority=3"))
        assertTrue(runCatching { McvideoSdp.remote(answer.replace("packetization-mode=1", "packetization-mode=0")) }.exceptionOrNull() is SdpNegotiationError)
    }

    @Test fun implicitRequestIsOffered() {
        assertTrue(McvideoSdp.offer(local.copy(implicitRequest = true)).contains("a=fmtp:MCVideo mc_transmission_ssrc=111;mc_implicit_request\r\n"))
        assertFalse(McvideoSdp.offer(local).contains("mc_implicit_request"))
    }

    @Test fun pullAndPushUrls() {
        val xml = McvideoInfo(sessionType = "one-from-server video pull", videoPullUrl = "https://mcvideo-server/recordings/1").encode()
        assertTrue(xml.contains("<video-pull-url type=\"Normal\"><mcvideoURI>https://mcvideo-server/recordings/1</mcvideoURI>"))
        assertEquals("https://mcvideo-server/recordings/1", McvideoInfo.parse(xml).videoPullUrl)
        val info = "<?xml version=\"1.0\"?><mcvideoinfo xmlns=\"urn:3gpp:ns:mcvideoInfo:1.0\"><mcvideo-Params>" +
            "<session-type>one-to-server video push</session-type><anyExt><video-push-url>https://mcvideo-server/recordings/2</video-push-url></anyExt>" +
            "</mcvideo-Params></mcvideoinfo>"
        assertEquals("https://mcvideo-server/recordings/2", McvideoInfo.parse(info).videoPushUrl)
    }

    @Test fun ambientViewingDirections() {
        val viewing = McvideoSdp.offer(local.copy(direction = "recvonly"))
        assertTrue(viewing.contains("a=recvonly\r\n") && !viewing.contains("a=sendrecv"))
        assertEquals("recvonly", McvideoSdp.remote(viewing).direction)
        assertEquals("sendrecv", McvideoSdp.remote(McvideoSdp.offer(local)).direction)
        assertEquals("recvonly", McvideoSdp.answerDirection("sendonly"))
        assertEquals("sendonly", McvideoSdp.answerDirection("recvonly"))
        assertEquals("sendrecv", McvideoSdp.answerDirection("sendrecv"))
    }

    @Test fun ambientViewingInfo() {
        val xml = McvideoInfo(sessionType = "ambient-viewing", callingUserId = "sip:alice.mcvideo@mcx.test", ambientViewingType = "remote-init").encode()
        assertTrue(xml.contains("<session-type>ambient-viewing</session-type>"))
        assertTrue(xml.contains("<anyExt><ambient-viewing-type>remote-init</ambient-viewing-type></anyExt>"))
        assertEquals("remote-init", McvideoInfo.parse(xml).ambientViewingType)
        // The server's BYE to the viewing user (§15.4.3).
        val bye = "<?xml version=\"1.0\"?><mcvideoinfo xmlns=\"urn:3gpp:ns:mcvideoInfo:1.0\"><mcvideo-Params>" +
            "<anyExt><release-reason>call-request-for-viewed-to-client</release-reason></anyExt></mcvideo-Params></mcvideoinfo>"
        assertEquals("call-request-for-viewed-to-client", McvideoInfo.parse(bye).releaseReason)
        assertTrue(McvideoService.releaseCapability(listOf("*;+g.3gpp.mcvideo.ambient-viewing-call-release")))
        assertFalse(McvideoService.releaseCapability(listOf("*;+g.3gpp.mcptt.ambient-listening-call-release")))
    }

    @Test fun infoUsesTheMcvideoNames() {
        val xml = McvideoInfo(sessionType = "private", callingUserId = "sip:alice.mcvideo@mcx.test", clientId = "urn:uuid:1").encode()
        assertTrue(xml.contains("urn:3gpp:ns:mcvideoInfo:1.0") && xml.contains("<session-type>private</session-type>"))
        assertTrue(xml.contains("<mcvideo-calling-user-id type=\"Normal\"><mcvideoURI>sip:alice.mcvideo@mcx.test</mcvideoURI>"))
        assertEquals("sip:alice.mcvideo@mcx.test", McvideoInfo.parse(xml).callingUserId)
    }
}
