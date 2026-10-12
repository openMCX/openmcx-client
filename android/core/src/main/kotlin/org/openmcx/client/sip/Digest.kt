//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.sip

import java.security.MessageDigest
import java.security.SecureRandom

/** A Digest challenge from WWW-Authenticate or Proxy-Authenticate (RFC 7616 §3.3, RFC 3261 §22). */
data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val algorithm: String = "MD5",
    val qop: List<String> = emptyList(),
    val opaque: String? = null,
    val stale: Boolean = false,
) {
    companion object {
        fun parse(header: String): DigestChallenge {
            val value = header.trim()
            if (!value.regionMatches(0, "Digest", 0, 6, ignoreCase = true)) throw SipParseError("not a Digest challenge: $header")
            val fields = authParams(value.substring(6))
            return DigestChallenge(
                realm = fields["realm"] ?: throw SipParseError("challenge without realm"),
                nonce = fields["nonce"] ?: throw SipParseError("challenge without nonce"),
                algorithm = fields["algorithm"] ?: "MD5",
                qop = fields["qop"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                opaque = fields["opaque"],
                stale = fields["stale"].equals("true", ignoreCase = true),
            )
        }

        /** `a="b", c=d` auth-params, unquoting values. */
        fun authParams(text: String): Map<String, String> = splitOutsideQuotes(text, ',').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) null
            else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).trim().removeSurrounding("\"").replace("\\\"", "\"")
        }.toMap()
    }
}

/** Computes Digest credentials (RFC 7616 §3.4, MD5 and SHA-256, qop auth or none). */
class DigestAuth(private val username: String, private val password: String, private val random: SecureRandom = SecureRandom()) {
    private var lastNonce: String? = null
    private var nonceCount = 0

    fun authorization(challenge: DigestChallenge, method: String, uri: String, cnonce: String = newCnonce()): String {
        val alg = challenge.algorithm.uppercase()
        val hash: (String) -> String = when (alg) {
            "MD5" -> { s -> hex("MD5", s) }
            "SHA-256" -> { s -> hex("SHA-256", s) }
            else -> throw IllegalArgumentException("unsupported digest algorithm ${challenge.algorithm}")
        }
        if (challenge.nonce != lastNonce) { lastNonce = challenge.nonce; nonceCount = 0 }
        val ha1 = hash("$username:${challenge.realm}:$password")
        val ha2 = hash("$method:$uri")
        val parts = mutableListOf(
            "username=\"$username\"", "realm=\"${challenge.realm}\"", "nonce=\"${challenge.nonce}\"", "uri=\"$uri\"",
        )
        val response = if ("auth" in challenge.qop) {
            nonceCount++
            val nc = "%08x".format(nonceCount)
            parts += listOf("qop=auth", "nc=$nc", "cnonce=\"$cnonce\"")
            hash("$ha1:${challenge.nonce}:$nc:$cnonce:auth:$ha2")
        } else {
            hash("$ha1:${challenge.nonce}:$ha2")
        }
        parts += "response=\"$response\""
        parts += "algorithm=${challenge.algorithm}"
        challenge.opaque?.let { parts += "opaque=\"$it\"" }
        return "Digest " + parts.joinToString(", ")
    }

    private fun newCnonce(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        fun hex(algorithm: String, text: String): String =
            MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
