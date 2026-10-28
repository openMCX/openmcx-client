//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class JwtTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwk = (pair.public as RSAPublicKey).let {
        Jwk("k1", b64urlEncode(it.modulus.toByteArray().dropWhile { b -> b == 0.toByte() }.toByteArray()),
            b64urlEncode(it.publicExponent.toByteArray()))
    }

    private fun sign(claims: String): String {
        val input = b64urlEncode("""{"alg":"RS256","kid":"k1"}""".toByteArray()) + "." + b64urlEncode(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(input.toByteArray()); sign() }
        return input + "." + b64urlEncode(sig)
    }

    @Test
    fun verifiesRs256() {
        Jwt.parse(sign("""{"sub":"alice"}""")).verifyRs256(listOf(jwk), "k1", "RS256")
    }

    @Test
    fun rejectsTamperedClaims() {
        val parts = sign("""{"sub":"alice"}""").split('.')
        val forged = parts[0] + "." + b64urlEncode("""{"sub":"mallory"}""".toByteArray()) + "." + parts[2]
        assertThrows(JwtError::class.java) { Jwt.parse(forged).verifyRs256(listOf(jwk), "k1", "RS256") }
    }

    @Test
    fun rejectsCriticalHeaderParameters() {
        // RFC 7515 §4.1.11: an extension this verifier does not understand.
        val input = b64urlEncode("""{"alg":"RS256","kid":"k1","crit":["exp"],"exp":1}""".toByteArray()) + "." +
            b64urlEncode("{}".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(input.toByteArray()); sign() }
        assertThrows(JwtError::class.java) { Jwt.parse(input + "." + b64urlEncode(sig)).verifyRs256(listOf(jwk), "k1", "RS256") }
    }

    @Test
    fun rejectsKeysShorterThan2048Bits() {
        // RFC 7518 §3.3: "A key of size 2048 bits or larger MUST be used".
        val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val smallJwk = (small.public as RSAPublicKey).let {
            Jwk("k1", b64urlEncode(it.modulus.toByteArray().dropWhile { b -> b == 0.toByte() }.toByteArray()),
                b64urlEncode(it.publicExponent.toByteArray()))
        }
        val input = b64urlEncode("""{"alg":"RS256","kid":"k1"}""".toByteArray()) + "." + b64urlEncode("{}".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(small.private); update(input.toByteArray()); sign() }
        assertThrows(JwtError::class.java) { Jwt.parse(input + "." + b64urlEncode(sig)).verifyRs256(listOf(smallJwk), "k1", "RS256") }
    }

    @Test
    fun rejectsOtherAlgorithms() {
        assertThrows(JwtError::class.java) { Jwt.parse(sign("{}")).verifyRs256(listOf(jwk), "k1", "none") }
    }
}
