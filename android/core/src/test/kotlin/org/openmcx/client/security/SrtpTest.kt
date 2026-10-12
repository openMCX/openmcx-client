package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String): ByteArray = s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class SrtpTest {
    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
    private val key = hex("000102030405060708090a0b0c0d0e0f")
    private val random = hex("101112131415161718191a1b1c1d1e1f")
    private fun km(m: SecurityMaterial) = m.masterKey + m.masterSalt

    /** The OpenMCX server's known answers (tests/test_security.cpp), themselves from the TLS P_SHA256 vector and Python hmac. */
    @Test fun keyDerivationKnownAnswers() {
        assertArrayEquals(hex("e3f229ba727be17b8d122620557cd453c2aab21d07c3d495329b52d4e61edb5a6b301791e90d35c9c9a46b4e14baf9af0fa022f7077def17abfd3797c0564bab4fbc91666e9def9b97fce34f796789baa48082d122ee42c5a72e5a5110fff70187347b66"),
            SrtpKeys.mikeyPrf(hex("9bbe436ba940f017b17652849a71db35"), hex("74657374206c6162656ca0ba9f936cda311827a6f796ffd5198c"), 100))
        val pck = KeyMessage(key, random, 0x10000001, 0)
        assertArrayEquals(hex("6b488f6975ebe3bef880c161b58871f385f6d3b4cd08ed7d02ff1cc6"), km(SrtpKeys.privateCall(pck, true, mcvideo = true)))
        assertArrayEquals(hex("2238a6bb9f615aca5eaf957626a9332a2c300b21753a2cec34c5bff0"), km(SrtpKeys.privateCall(pck, false, mcvideo = true)))
        assertArrayEquals(hex("1304a8afefd01e46077cb28fc5a4990b31890b87e3fc8b3529782403"), km(SrtpKeys.privateCall(pck, true, mcvideo = false)))
        val csk = KeyMessage(key, random, 0x20000001, 0)
        assertArrayEquals(hex("51347d6b7ad6c6417991967bc7a571f0615770888a6f4e7af08a037a"), km(SrtpKeys.floorControl(csk, mcvideo = true)))
        assertArrayEquals(hex("20000001"), SrtpKeys.floorControl(csk, true).mki)
        val gmk = KeyMessage(key, random, 0x00112233, 0)
        val group = SrtpKeys.groupCall(gmk, 0x00112233, 0x01234567, mcvideo = true)
        assertArrayEquals(hex("203fa563df0d041fbe68280782286933a7a0e72a464284bec66e6e74"), km(group))
        assertArrayEquals(hex("0011223301234567"), group.mki)
        assertEquals(0x027cef1bL, SrtpKeys.gukId(key, 0x00112233, "sip:alice@example.org"))
    }

    /** RFC 7714 §16.1.1 and §17.1, with the session key and salt given directly. */
    @Test fun rfc7714Vectors() {
        val salt = hex("517569642070726f2071756f")
        val keys = SecureStream.SessionKeys(key, salt, key, salt)
        val material = SecurityMaterial(ByteArray(16), ByteArray(12), ByteArray(0), media = true)
        val rtp = SecureStream(material, 0x5501a0b2, keys)
        val packet = hex("8040f17b8041f8d35501a0b247616c6c69612065 7374206f6d6e697320646976 69736120696e2070 61727465732074726573")
        val secured = hex("8040f17b 8041f8d3 5501a0b2 f24de3a3 fb34de6c acba861c 9d7e4bca be633bd5 0d294e6f 42a5f47a 51c7d19b 36de3adf 8833899d 7f27beb1 6a9152cf 765ee439 0cce")
        assertArrayEquals(secured, rtp.protectRtp(packet))
        assertArrayEquals(packet, SecureStream(material, 0x5501a0b2, keys).unprotectRtp(secured))
        val rtcp = SecureStream(material, 0x4d617273, keys).apply { sendRtcpIndex = 0x5d4 }
        val out = rtcp.protectRtcp(hex("81c8000d 4d617273 4e545031 4e545032 52545020 0000042a 0000e930 4c756e61 deadbeef deadbeef deadbeef deadbeef deadbeef"))
        assertArrayEquals(hex("81c8000d 4d617273 63e94885 dcdab67c a727d766 2f6b7e99 7ff5c0f7 6c06f32d c676a5f1 730d6fda 4ce09b46 86303ded 0bb9275b c84aa458 96cf4d2f c5abf872 45d9eade 800005d4"), out)
    }

    /** The server's round trips: sizes, MKI position, direction, tampering and replay. */
    @Test fun roundTrips() {
        val pck = KeyMessage(key, random, 0x10000001, 0)
        val initiator = SrtpKeys.privateCall(pck, true, true)
        val tx = SecureStream(initiator, 0x12345678); val rx = SecureStream(initiator, 0x12345678)
        val wrongDirection = SecureStream(SrtpKeys.privateCall(pck, false, true), 0x12345678)
        val packet = hex("8060000100000140123456780102030405060708")
        val secured = tx.protectRtp(packet)
        assertEquals(packet.size + 20, secured.size)
        assertArrayEquals(initiator.mki, secured.copyOfRange(secured.size - 4, secured.size))
        fails { wrongDirection.unprotectRtp(secured) }
        assertArrayEquals(packet, rx.unprotectRtp(secured))
        fails { rx.unprotectRtp(secured) }
        val floor = SrtpKeys.floorControl(KeyMessage(key, random, 0x20000001, 0), true)
        val ftx = SecureStream(floor, 0x12345678); val frx = SecureStream(floor, 0x12345678)
        val rtcp = hex("80cc0004123456784d4350540102030405060708")
        val s = ftx.protectRtcp(rtcp)
        assertEquals(rtcp.size + 24, s.size)
        val tampered = s.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        fails { frx.unprotectRtcp(tampered) }
        assertArrayEquals(rtcp, frx.unprotectRtcp(s))
        fails { frx.unprotectRtcp(s) }
        fails { frx.unprotectRtcp(rtcp) }
        fails { ftx.protectRtp(packet) }
        // Reordered packets within the window are accepted once.
        val a = tx.protectRtp(packet.copyOf().also { it[3] = 2 }); val b = tx.protectRtp(packet.copyOf().also { it[3] = 3 })
        assertArrayEquals(packet.copyOf().also { it[3] = 3 }, rx.unprotectRtp(b))
        assertArrayEquals(packet.copyOf().also { it[3] = 2 }, rx.unprotectRtp(a))
        fails { rx.unprotectRtp(a) }
    }
}
