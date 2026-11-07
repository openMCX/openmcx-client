//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** RFC 3310, RFC 4169, RFC 3329 and TS 33.203 annexes H and I, against the Linux client's reference vectors. */
class ImsAkaTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun digestResponsesOfRfc2617AndRfc7616() {
        assertEquals("6629fae49393a05397450978507c4ef1", ImsAka.response(AkaAlgorithm.AKAV1_MD5, "Mufasa",
            "testrealm@host.com", "Circle Of Life".toByteArray(), "GET", "/dir/index.html",
            "dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b", "auth"))
        assertEquals("753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1", ImsAka.response(
            AkaAlgorithm.AKAV2_SHA256, "Mufasa", "http-auth@example.org", "Circle of Life".toByteArray(), "GET",
            "/dir/index.html", "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", "00000001",
            "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ", "auth"))
    }

    @Test fun akav1TakesTheBinaryResAsThePassword() {
        val res = hex("8001ff7f00102030")
        assertArrayEquals(res, ImsAka.password(AkaAlgorithm.AKAV1_MD5, res, ByteArray(16), ByteArray(16)))
        // An RES with octets that are not UTF-8: hashed as octets.
        assertEquals("c131de35caa55afd937d904c1bfcc8be", ImsAka.response(AkaAlgorithm.AKAV1_MD5, "card@mcx.test",
            "mcx.test", res, "REGISTER", "sip:mcx.test", "NONCE", "00000001", "abcd", "auth"))
    }

    @Test fun akav2PasswordIsTheBase64PrfOfResIkCk() {
        val ck = ByteArray(16) { it.toByte() }; val ik = ByteArray(16) { (16 + it).toByte() }
        assertEquals("YpeJs+LyWsYdLeeJGcfqtdyAbN8PeZbgSZb8e9y6D/A=",
            String(ImsAka.password(AkaAlgorithm.AKAV2_SHA256, ByteArray(8) { 0xab.toByte() }, ck, ik)))
    }

    @Test fun nonceGivesRandAndAutnAndRefusesShortOnes() {
        val raw = ByteArray(40) { it.toByte() }
        val nonce = ImsAka.parseNonce(Base64.getEncoder().encodeToString(raw))!!
        assertArrayEquals(raw.copyOfRange(0, 16), nonce.rand)
        assertArrayEquals(raw.copyOfRange(16, 32), nonce.autn)
        assertNull(ImsAka.parseNonce(Base64.getEncoder().encodeToString(ByteArray(31))))
        assertNull(ImsAka.parseNonce("not base64 !"))
        assertEquals(AkaAlgorithm.AKAV2_SHA256, AkaAlgorithm.of("akav2-sha-256"))
        assertNull(AkaAlgorithm.of("MD5"))
    }

    @Test fun offersFollowThePreferenceAndLeaveOutAesGmac() {
        val text = SecAgree.format(SecAgree.offers(1000, 1001, 5100, 5101))
        assertEquals("ipsec-3gpp;q=0.9;alg=null;ealg=aes-gcm-us;prot=esp;mod=trans;spi-c=1000;spi-s=1001;port-c=5100;port-s=5101",
            text.substringBefore(", "))
        assertEquals(6, SecAgree.parse(text).size)
        assertEquals(false, text.contains("gmac"))
    }

    @Test fun selectionTakesTheServersFirstSupportedPair() {
        // The P-CSCF's list by priority; AES-GMAC first here: skipped, the next supported pair is taken.
        val server = SecAgree.parse(
            "ipsec-3gpp;q=0.9;alg=aes-gmac-us;prot=esp;mod=trans;spi-c=1;spi-s=2;port-c=6100;port-s=6101, " +
                "ipsec-3gpp;q=0.5;alg=hmac-sha2-256-128;ealg=aes-cbc;prot=esp;mod=trans;spi-c=1;spi-s=2;port-c=6100;port-s=6101, " +
                "ipsec-3gpp;q=0.7;alg=null;ealg=aes-gcm-us;prot=esp;mod=trans;spi-c=1;spi-s=2;port-c=6100;port-s=6101")
        assertEquals("aes-gcm-us", SecAgree.select(server)?.ealg)
        // No ealg: the NULL encryption (TS 33.203 clause 7.2).
        val noEalg = SecAgree.parse("ipsec-3gpp;alg=hmac-sha-1-96;spi-c=1;spi-s=2;port-c=6100;port-s=6101")
        assertEquals("hmac-sha-1-96", SecAgree.select(noEalg)?.alg)
        assertNull(SecAgree.select(SecAgree.parse("ipsec-3gpp;alg=aes-gmac;prot=esp;spi-c=1;spi-s=2;port-c=6100;port-s=6101")))
        assertNull(SecAgree.select(SecAgree.parse("tls;q=0.5")))
    }

    @Test fun espKeysOfAnnexI() {
        val ck = ByteArray(16) { it.toByte() }; val ik = ByteArray(16) { (16 + it).toByte() }
        val sha = EspKeys.derive("hmac-sha2-256-128", "aes-cbc", ck, ik)
        assertEquals("613ace97911f126b47be31fa5e53f704f0c4d1f9f4f9e642cc6e33ea8bae5155", sha.integrity.hex())
        assertArrayEquals(ck, sha.encryption)
        assertEquals(0, sha.salt.size)
        assertArrayEquals(ik + ByteArray(4), EspKeys.derive("hmac-sha-1-96", "null", ck, ik).integrity)
        val gcm = EspKeys.derive("null", "aes-gcm-us", ck, ik)
        assertEquals("99ca2318", gcm.salt.hex())
        assertEquals("99ca2318", EspKeys.saSalt(gcm.salt, "aes-gcm-us", toUe = false, fromServer = false).hex())
        assertEquals("99ca231b", EspKeys.saSalt(gcm.salt, "aes-gcm-us", toUe = true, fromServer = true).hex())
        assertEquals("99ca2319", EspKeys.saSalt(gcm.salt, "aes-gcm-us", toUe = true, fromServer = false).hex())
        // Without "-us" one salt for every SA.
        assertEquals("99ca2318", EspKeys.saSalt(gcm.salt, "aes-gcm", toUe = true, fromServer = true).hex())
    }
}
