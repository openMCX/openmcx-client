//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

private fun bytes(hex: String): ByteArray = hex.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** The test data of RFC 6507 Appendix A. */
class EccsiTest {
    private val kpak = Eccsi.P256.decode(bytes("""04
        50D4670B DE75244F 28D2838A 0D25558A 7A72686D 4522D4C8 273FB644 2AEBFA93
        DBDD3755 1AFD263B 5DFD617F 3960C65A 8C298850 FF99F203 66DCE7D4 367217F4"""))
    private val eccsi = Eccsi(kpak)
    private val id = "2011-02\u0000tel:+447700900123\u0000".toByteArray()
    private val pvt = Eccsi.P256.decode(bytes("""04
        758A1427 79BE89E8 29E71984 CB40EF75 8CC4AD77 5FC5B9A3 E1C8ED52 F6FA36D9
        A79D2476 92F4EDA3 A6BDAB77 D6AA6474 A464AE49 34663C52 65BA7018 BA091F79"""))
    private val ssk = Curve.hex("23F374AE 1F4033F3 E9DBDDAA EF20F4CF 0B86BBD5 A138A5AE 9E7E006B 34489A0D")
    private val message = "message\u0000".toByteArray()

    @Test fun kpakIsKsakTimesG() = assertEquals(kpak, Eccsi.P256.multiply(Eccsi.G, BigInteger.valueOf(0x12345)))

    @Test fun identityHashAndKeyValidation() {
        assertEquals(Curve.hex("490F3FEB BC1C902F 6289723D 7F8CBF79 DB889308 49D19F38 F0295B5C 276C14D1"), eccsi.identityHash(id, pvt))
        assertTrue(eccsi.validate(id, ssk, pvt))
        assertFalse(eccsi.validate(id, ssk.add(BigInteger.ONE), pvt))
    }

    @Test fun rfcSignatureVerifies() {
        val sig = bytes("""269D4C8F DEB66A74 E4EF8C0D 5DCC597D DFE6029C 2AFFC493 6008CD2C C1045D81
            E09B528D 0EF8D6DF 1AA3ECBF 80110CFC EC9FC682 52CEBB67 9F413484 6940CCFD""") + Eccsi.P256.encode(pvt)
        assertTrue(eccsi.verify(id, message, sig))
        assertFalse(eccsi.verify(id, "massage\u0000".toByteArray(), sig))
        assertFalse(eccsi.verify("2011-03\u0000tel:+447700900123\u0000".toByteArray(), message, sig))
    }

    @Test fun signAndVerify() {
        val sig = eccsi.sign(id, ssk, pvt, message)
        assertEquals(129, sig.size)
        assertTrue(eccsi.verify(id, message, sig))
        sig[40] = (sig[40].toInt() xor 1).toByte()
        assertFalse(eccsi.verify(id, message, sig))
    }
}

/** The test data of RFC 6508 Appendix A, with parameter set 1 of RFC 6509. */
class SakkeTest {
    private val z = EcPoint(Curve.hex("""
        5958EF1B 1679BF09 9B3A030D F255AA6A 23C1D8F1 43D4D23F 753E69BD 27A832F3 8CB4AD53 DDEF4260 B0FE8BB4 5C4C1FF5
        10EFFE30 0367A37B 61F701D9 14AEF097 24825FA0 707D61A6 DFF4FBD7 273566CD DE352A0B 04B7C16A 78309BE6 40697DE7
        47613A5F C195E8B9 F328852A 579DB8F9 9B1D0034 479EA9C5 595F47C4 B2F54FF2"""), Curve.hex("""
        1508D375 14DCF7A8 E143A605 8C09A6BF 2C9858CA 37C25806 5AE6BF75 32BC8B5B 63383866 E0753C5A C0E72709 F8445F2E
        6178E065 857E0EDA 10F68206 B63505ED 87E534FB 2831FF95 7FB7DC61 9DAE6130 1EEACC2F DA3680EA 4999258A 833CEA8F
        C67C6D19 487FB449 059F26CC 8AAB655A B58B7CC7 96E24E9A 39409575 4F5F8BAE"""))
    private val sakke = Sakke(z)
    private val b = bytes("3230 31312D30 32007465 6C3A2B34 34373730 30393030 31323300")
    private val ssv = bytes("12345678 9ABCDEF0 12345678 9ABCDEF0")
    private val rb = byteArrayOf(4) + bytes("""
        44E8AD44 AB8592A6 A5A3DDCA 5CF896C7 18043606 A01D650D EF37A01F 37C228C3 32FC3173 54E2C274 D4DAF8AD 001054C7
        6CE57971 C6F4486D 57230432 61C506EB F5BE438F 53DE04F0 67C776E0 DD3B71A6 29013328 3725A532 F21AF145 126DC1D7
        77ECC27B E50835BD 28098B8A 73D9F801 D893793A 41FF5C49 B87E79F2 BE4D56CE""") + bytes("""
        557E134A D85BB1D4 B9CE4F8B E4B08A12 BABF55B1 D6F1D7A6 38019EA2 8E15AB1C 9F76375F DD1210D4 F4351B9A 009486B7
        F3ED46C9 65DED2D8 0DADE4F3 8C6721D5 2C3AD103 A10EBD29 59248B4E F006836B F097448E 6107C9ED EE9FB704 823DF199
        F832C905 AE45F8A2 47A072D8 EF729EAB C5E27574 B07739B3 4BE74A53 2F747B86""")
    private val h = bytes("89E0BC66 1AA1E916 38E6ACC8 4E496507")
    private val kb = EcPoint(Curve.hex("""
        93AF67E5 007BA6E6 A80DA793 DA300FA4 B52D0A74 E25E6E7B 2B3D6EE9 D18A9B5C 5023597B D82D8062 D3401956 3BA1D25C
        0DC56B7B 979D74AA 50F29FBF 11CC2C93 F5DFCA61 5E609279 F6175CEA DB00B58C 6BEE1E7A 2A47C4F0 C456F052 59A6FA94
        A634A40D AE1DF593 D4FECF68 8D5FC678 BE7EFC6D F3D68353 25B83B2C 6E69036B"""), Curve.hex("""
        155F0A27 241094B0 4BFB0BDF AC6C670A 65C325D3 9A069F03 659D44CA 27D3BE8D F311172B 55416018 1CBE94A2 A783320C
        ED590BC4 2644702C F371271E 496BF20F 588B78A1 BC01ECBB 6559934B DD2FB65D 2884318A 33D1A42A DF5E33CC 5800280B
        28356497 F87135BA B9612A17 26042440 9AC15FEE 996B744C 33215123 5DECB0F5"""))

    @Test fun parameters() {
        assertTrue(Sakke.E.isOnCurve(Sakke.P) && Sakke.E.isOnCurve(z) && Sakke.E.isOnCurve(kb))
        assertEquals(Sakke.PRIME.add(BigInteger.ONE), Sakke.Q.shiftLeft(2))
        assertEquals(Sakke.G_REPR, Sakke.pairing(Sakke.P, Sakke.P))
        assertEquals(z, Sakke.E.multiply(Sakke.P, Curve.hex("AFF429D3 5F84B110 D094803B 3595A6E2 998BC99F")))
    }

    @Test fun hashToIntegerRange() = assertEquals(Curve.hex("""
        13EE3E1B 8DAC5DB1 68B1CEB3 2F0566A4 C273693F 78BAFFA2 A2EE6A68 6E6BD90F 8206CCAB 84E7F42E D39BD4FB 131012EC
        CA2ECD21 19414560 C17CAB46 B956A80F 58A3302E B3E2C9A2 28FBA7ED 34D8ACA2 392DA1FF B0B17B23 20AE09AA EDFD0235
        F6FE0EB6 5337A63F 9CC97728 B8E5AD04 60FADE14 4369AA5B 21662132 47712096"""), Sakke.hashToRange(ssv + b, Sakke.Q))

    @Test fun encapsulationIsTheRfcData() = assertArrayEquals(rb + h, sakke.encapsulate(b, ssv))

    @Test fun decapsulation() {
        assertTrue(sakke.validate(b, kb))
        assertArrayEquals(ssv, sakke.decapsulate(b, kb, rb + h))
        val tampered = rb + h.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(sakke.decapsulate(b, kb, tampered))
    }
}
