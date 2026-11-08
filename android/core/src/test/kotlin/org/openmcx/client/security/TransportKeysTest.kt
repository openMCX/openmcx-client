//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 3394, the ETSI test suite's padding and TS 33.180 F.1.4, against the same known answers as the shared C++ core. */
class TransportKeysTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val kek = ByteArray(32) { it.toByte() }
    private fun material(size: Int) = ByteArray(size) { (0x30 + it).toByte() }

    /** RFC 3394 sections 4.3 and 4.6: 128 and 256 bits of key data under a 256-bit KEK. */
    @Test fun rfc3394() {
        assertEquals("64e8c3f9ce0f5ba263e9777905818a2a93c8191e7d6e8ae7", TransportKeys.wrap(kek, hex("00112233445566778899aabbccddeeff")).hex())
        val data = hex("00112233445566778899aabbccddeeff000102030405060708090a0b0c0d0e0f")
        val wrapped = TransportKeys.wrap(kek, data)
        assertEquals("28c9f404c4b810f4cbccb35cfb87f8263f5786e2d80ed326cbc7f0e71a99f43bfb988b9b7a02dd21", wrapped.hex())
        assertArrayEquals(data, TransportKeys.unwrap(kek, wrapped))
        assertThrows(KeyError::class.java) { TransportKeys.unwrap(kek, wrapped.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }) }
        assertThrows(KeyError::class.java) { TransportKeys.unwrap(ByteArray(32) { 0xa0.toByte() }, wrapped) }
        assertThrows(IllegalArgumentException::class.java) { TransportKeys.wrap(kek, ByteArray(65)) }
        assertThrows(IllegalArgumentException::class.java) { TransportKeys.wrap(ByteArray(16), ByteArray(16)) }
    }

    /**
     * The test suite's padding (n - 1 zero octets and one octet n, then RFC 3394): known answers from an independent
     * implementation (as in the shared core's test), sizes, round trips and invalid padding octets.
     */
    @Test fun paddedWrap() {
        assertEquals("79e569d5137aa02f28fb1b0d44358c44c9e8055c2f4066892a87df2877b7da2783670ab6fb59fa2e203e8e12111f1508" +
            "312ba7d1f2c88a790956f68807b4528c2f300b9473bd7fe5e9c022f3f82a6ff8", TransportKeys.wrapKeyMaterial(kek, material(65)).hex())
        assertEquals("9e5239874f9bdfb102d6accf076496d4075175874a21b7acc17ac24a8ce7bae9c8d61e813b0ce52c727cd5dfffe1c000",
            TransportKeys.wrapKeyMaterial(kek, material(32)).hex())
        for (size in listOf(8, 32, 65, 257, 63, 64)) {
            val wrapped = TransportKeys.wrapKeyMaterial(kek, material(size))
            assertEquals((size / 8 + 1) * 8 + 8, wrapped.size)
            assertArrayEquals(material(size), TransportKeys.unwrapKeyMaterial(kek, wrapped))
        }
        assertEquals(272, TransportKeys.wrapKeyMaterial(kek, material(257)).size)
        fun badPadding(last: Int) = TransportKeys.wrap(kek, material(32).also { it[31] = last.toByte() })
        for (last in listOf(0, 9, 0xff)) assertThrows(KeyError::class.java) { TransportKeys.unwrapKeyMaterial(kek, badPadding(last)) }
        assertEquals(24, TransportKeys.unwrapKeyMaterial(kek, badPadding(8)).size)
    }

    /** TS 33.180 F.1.4: the whole KDF output for a 256-bit XPK, the 128 least significant bits for a 128-bit one. */
    @Test fun f14() {
        val user = "sip:alice@mcx.test"
        fun kdf(key: ByteArray, fc: Int, id: Long): ByteArray {
            val u = user.toByteArray()
            val s = byteArrayOf(fc.toByte()) + u + byteArrayOf(0, u.size.toByte(), (id ushr 24).toByte(), (id ushr 16).toByte(),
                (id ushr 8).toByte(), id.toByte(), 0, 4)
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(s)
        }
        val trk = Xpk(kek, 0x0a0b0c0d)
        assertArrayEquals(kdf(kek, 0x51, 0x0a0b0c0d), TransportKeys.protectionKey(trk, user, TransportKeys.CONFIDENTIALITY))
        assertArrayEquals(kdf(kek, 0x52, 0x0a0b0c0d), TransportKeys.protectionKey(trk, user, TransportKeys.INTEGRITY))
        val ink128 = ByteArray(16) { (0x50 + it).toByte() }
        assertArrayEquals(kdf(ink128, 0x52, 7).copyOfRange(16, 32), TransportKeys.protectionKey(Xpk(ink128, 7), user, TransportKeys.INTEGRITY))
        assertEquals("CgsMDQ==", trk.keyName)
        assertThrows(IllegalArgumentException::class.java) { Xpk(ByteArray(24), 1) }
    }
}
