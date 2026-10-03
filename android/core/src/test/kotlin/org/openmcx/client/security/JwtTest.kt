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
    fun rejectsOtherAlgorithms() {
        assertThrows(JwtError::class.java) { Jwt.parse(sign("{}")).verifyRs256(listOf(jwk), "k1", "none") }
    }
}
