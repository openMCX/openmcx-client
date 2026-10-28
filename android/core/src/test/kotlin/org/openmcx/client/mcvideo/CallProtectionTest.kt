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
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KeyProperties

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

    // TS 33.180 E.6.10 at every packet, by an injected clock: usable 1 s before the Expiry time, not from it on.
    private val expiry = 2_000_000_000L
    private fun dated(key: KeyMessage, type: Int, expiry: Long = this.expiry, status: Long = 1) =
        KeyMessage(key.key, key.random, key.csbId, key.ntpSeconds, KeyProperties(type, status, 0, expiry))
    private val rtcp = byteArrayOf(0x80.toByte(), 0xcc.toByte(), 0, 4, 0x12, 0x34, 0x56, 0x78) +
        "MCV0".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test fun pckNotUsedPastItsExpiryTime() {
        var now = expiry - 1
        val key = dated(pck, 1)
        val caller = CallProtection.privateCall(null, key, localInitiator = true) { now }
        val called = CallProtection.privateCall(null, key, localInitiator = false) { now }
        val wire = caller.protectRtp(rtp)!!
        assertArrayEquals(rtp, called.unprotectRtp(wire))
        val next = caller.protectRtp(rtp.copyOf().also { it[3] = 2 })!!
        now = expiry
        assertNull(caller.protectRtp(rtp.copyOf().also { it[3] = 3 })) // not sent, never in the clear
        assertNull(called.unprotectRtp(next)) // dropped
        assertEquals(0, called.failures)
        // Expiry time 0, or no Key Parameters: the PCK never expires; a revoked one is not used.
        now = 4_000_000_000L
        assertTrue(CallProtection.privateCall(null, dated(pck, 1, expiry = 0), true) { now }.protectRtp(rtp) != null)
        assertTrue(CallProtection.privateCall(null, pck, true) { now }.protectRtp(rtp) != null)
        val revoked = dated(pck, 1, expiry = 0, status = 0)
        assertNull(CallProtection.privateCall(null, revoked, true) { now }.protectRtp(rtp))
    }

    @Test fun cskNotUsedPastItsExpiryTime() {
        var now = expiry - 1
        val key = dated(csk, 2)
        val phone = CallProtection(key, null, null, clock = { now })
        val server = CallProtection(key, null, null, clock = { now })
        val wire = phone.protectControl(rtcp)!!
        assertArrayEquals(rtcp, server.unprotectControl(wire))
        val next = phone.protectControl(rtcp)!!
        now = expiry
        assertNull(phone.protectControl(rtcp))
        assertNull(server.unprotectControl(next))
        assertArrayEquals(rtp, phone.protectRtp(rtp)) // media without a media key stays as configured (clear)
    }

    @Test fun gmkNotUsedPastItsExpiryTime() {
        var now = expiry - 1
        val clear = KeyMessage(ByteArray(16) { (64 + it).toByte() }, ByteArray(16) { (80 + it).toByte() }, 0x01234567,
            0)
        val gmk = dated(clear, 0)
        val alice = "sip:alice.mcvideo@mcx.test"
        val sender = CallProtection.Group(null, gmk, alice, { now }) { emptyList() }
        val receiver = CallProtection.Group(null, gmk, "sip:bob.mcvideo@mcx.test", { now }) { listOf(alice) }
        assertArrayEquals(rtp, receiver.unprotectRtp(sender.protectRtp(rtp)!!))
        val next = sender.protectRtp(rtp.copyOf().also { it[3] = 2 })!!
        now = expiry
        assertNull(sender.protectRtp(rtp.copyOf().also { it[3] = 3 }))
        assertNull(receiver.unprotectRtp(next))
        assertEquals(0, receiver.failures)
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
