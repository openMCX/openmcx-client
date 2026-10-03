package org.openmcx.client.security

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class JwtError(message: String) : Exception(message)

/** A JSON Web Key (RFC 7517), RSA only. */
data class Jwk(val kid: String?, val n: String, val e: String) {
    fun publicKey(): PublicKey = KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(BigInteger(1, b64url(n)), BigInteger(1, b64url(e))),
    )
}

/** A compact JWS (RFC 7515) with its decoded header and claims (as JSON text, parsed by the caller). */
class Jwt(val headerJson: String, val claimsJson: String, private val signingInput: ByteArray, private val signature: ByteArray) {
    /** Verify an RS256 signature with one of [keys], chosen by `kid` when the header names one. */
    fun verifyRs256(keys: List<Jwk>, kid: String?, alg: String?) {
        if (alg != "RS256") throw JwtError("unsupported JWS algorithm $alg")
        val candidates = if (kid != null) keys.filter { it.kid == kid } else keys
        if (candidates.isEmpty()) throw JwtError("no key for kid $kid")
        val ok = candidates.any { key ->
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key.publicKey()); update(signingInput); verify(signature)
            }
        }
        if (!ok) throw JwtError("bad JWS signature")
    }

    companion object {
        fun parse(token: String): Jwt {
            val parts = token.split('.')
            if (parts.size != 3) throw JwtError("malformed JWT")
            return try {
                Jwt(String(b64url(parts[0])), String(b64url(parts[1])), "${parts[0]}.${parts[1]}".toByteArray(), b64url(parts[2]))
            } catch (e: IllegalArgumentException) {
                throw JwtError("malformed JWT")
            }
        }
    }
}

fun b64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))
fun b64urlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
