package org.openmcx.client.mcvideo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.security.KeyMessage

class CallProtectionTest {
    private val pck = KeyMessage(ByteArray(16) { it.toByte() }, ByteArray(16) { (16 + it).toByte() }, 0x10000001, 0)
    private val csk = KeyMessage(ByteArray(16) { (32 + it).toByte() }, ByteArray(16) { (48 + it).toByte() }, 0x20000001, 0)
    private val rtp = byteArrayOf(0x80.toByte(), 0x60, 0, 1, 0, 0, 1, 0x40, 0x12, 0x34, 0x56, 0x78, 1, 2, 3, 4, 5, 6, 7, 8)

    @Test fun privateCallStreamsPairUp() {
        val caller = CallProtection.privateCall(csk, pck, localInitiator = true)
        val called = CallProtection.privateCall(csk, pck, localInitiator = false)
        val sent = caller.protectRtp(rtp)!!
        assertFalse(sent.contentEquals(rtp))
        assertArrayEquals(rtp, called.unprotectRtp(sent))
        // The caller cannot open its own stream: the two directions have different CS-IDs.
        assertNull(CallProtection.privateCall(csk, pck, localInitiator = true).unprotectRtp(caller.protectRtp(rtp.copyOf().also { it[3] = 2 })!!))
        assertEquals(0, called.failures)
        assertNull(called.unprotectRtp(rtp)) // clear media is refused under protection
        assertEquals(1, called.failures)
    }

    @Test fun controlUnderTheCsk() {
        val rtcp = byteArrayOf(0x80.toByte(), 0xcc.toByte(), 0, 4, 0x12, 0x34, 0x56, 0x78, 'M'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte(), '0'.code.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        val phone = CallProtection(csk, null, null)
        val server = CallProtection(csk, null, null)
        val wire = phone.protectControl(rtcp)!!
        assertFalse(String(wire, Charsets.ISO_8859_1).contains("MCV0"))
        assertArrayEquals(rtcp, server.unprotectControl(wire))
        assertTrue(phone.protectsControl && !phone.protectsMedia)
        assertArrayEquals(rtp, phone.protectRtp(rtp)) // media in the clear when only control is protected
    }

    @Test fun protectedSdp() {
        val local = LocalVideoMedia("10.0.0.2", 20000, 20004, 20002, 111, 222, 333, protected = true, keyMgmt = "AAEC")
        val offer = McvideoSdp.offer(local)
        assertTrue(offer.contains("t=0 0\r\na=key-mgmt:mikey AAEC\r\nm=audio 20000 RTP/SAVP 96\r\n"))
        assertTrue(offer.contains("m=video 20004 RTP/SAVP 97\r\n"))
        val remote = McvideoSdp.remote(offer)
        assertTrue(remote.protected)
        assertEquals("AAEC", remote.keyMgmt)
        val clear = McvideoSdp.remote(McvideoSdp.offer(local.copy(protected = false, keyMgmt = null)))
        assertFalse(clear.protected); assertNull(clear.keyMgmt)
    }

    @Test fun groupMediaByTheSendersGukId() {
        val gmk = KeyMessage(ByteArray(16) { (64 + it).toByte() }, ByteArray(16) { (80 + it).toByte() }, 0x01234567, 0)
        val alice = "sip:alice.mcvideo@mcx.test"; val bob = "sip:bob.mcvideo@mcx.test"
        val sender = CallProtection.Group(csk, gmk, alice) { emptyList() }
        val receiver = CallProtection.Group(csk, gmk, bob) { listOf("sip:carol.mcvideo@mcx.test", alice) }
        val stranger = CallProtection.Group(csk, gmk, bob) { listOf("sip:carol.mcvideo@mcx.test") }
        val wire = sender.protectRtp(rtp)!!
        assertEquals(rtp.size + 16 + 8 + 4, wire.size) // tag, MKI (GMK-ID ‖ GUK-ID), ROC
        assertNull(stranger.unprotectRtp(wire)) // a sender it cannot name
        assertArrayEquals(rtp, receiver.unprotectRtp(wire))
        assertNull(receiver.unprotectRtp(wire)) // replay
    }
}
