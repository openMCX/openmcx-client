//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.idms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.openmcx.client.http.HttpClient
import org.openmcx.client.security.Jwk
import org.openmcx.client.security.Jwt
import org.openmcx.client.security.b64urlEncode
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

open class IdmsError(message: String) : Exception(message)

/** IdMS refused a refresh token (invalid_grant): revoked, lapsed, or the account is no longer active. */
class IdmsRefused(message: String) : IdmsError(message)

/** The tokens of one login (TS 24.482), with the identities the ID token asserts. */
data class Tokens(
    val accessToken: String,
    val idToken: String,
    /** Expiry of the access token, seconds since the epoch. */
    val expiresAt: Long,
    val mcpttId: String?,
    val mcdataId: String?,
    val sipUri: String?,
    /** The MC service client ID the IdMS bound into the token. */
    val clientId: String?,
    /** The refresh token (TS 33.180 B.5), when the IdMS issues one. */
    val refreshToken: String? = null,
    /** The nonce of the authentication; the ID tokens of later refreshes repeat it (OIDC Core §12.2). */
    val nonce: String = "",
)

/** An authorization request in progress: what the IdMS must echo back, and the PKCE verifier. */
data class AuthorizationRequest(val url: String, val state: String, val nonce: String, val verifier: String)

/**
 * OIDC authorization code flow with PKCE (TS 24.482 §6, RFC 7636) against the IdMS.
 *
 * The interactive path hands [authorizationUrl] to a browser and completes with
 * [exchange] once the redirect arrives. The OpenMCX lab IdMS has no login page: its
 * authorization endpoint returns a request ID that is completed by a credential POST
 * to `/login` ([loginDirect]).
 */
class IdmsClient(
    private val http: HttpClient,
    private val issuer: String,
    private val clientId: String,
    private val redirectUri: String,
    private val scope: String = "openid 3gpp:mc:ptt_service 3gpp:mc:data_service",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val random = SecureRandom()
    private var discovery: JsonObject? = null

    private fun token(bytes: Int) = b64urlEncode(ByteArray(bytes).also(random::nextBytes))

    private suspend fun discover(): JsonObject = discovery ?: run {
        val r = http.get("$issuer/.well-known/openid-configuration")
        if (r.status != 200) throw IdmsError("discovery: HTTP ${r.status}")
        Json.parseToJsonElement(r.text).jsonObject.also {
            if (it.str("issuer") != issuer) throw IdmsError("discovery issuer ${it.str("issuer")} is not $issuer")
            discovery = it
        }
    }

    suspend fun authorizationUrl(): AuthorizationRequest {
        val endpoint = discover().str("authorization_endpoint") ?: throw IdmsError("no authorization endpoint")
        val verifier = token(48)
        val challenge = b64urlEncode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = token(16)
        val nonce = token(16)
        val query = HttpClient.formEncode(linkedMapOf(
            "response_type" to "code", "client_id" to clientId, "redirect_uri" to redirectUri, "scope" to scope,
            "state" to state, "nonce" to nonce, "code_challenge" to challenge, "code_challenge_method" to "S256",
        ))
        return AuthorizationRequest("$endpoint?$query", state, nonce, verifier)
    }

    /** The lab login: authorization request, credential POST, then the code exchange. */
    suspend fun loginDirect(username: String, password: String): Tokens {
        val request = authorizationUrl()
        val authz = http.get(request.url)
        if (authz.status != 200) throw IdmsError("authorization request: HTTP ${authz.status} ${authz.text}")
        val requestId = Json.parseToJsonElement(authz.text).jsonObject.str("request_id")
            ?: throw IdmsError("authorization response without request_id")
        val login = http.postForm("$issuer/login", mapOf("request_id" to requestId, "username" to username, "password" to password))
        if (login.status !in 300..399) throw IdmsError("login: HTTP ${login.status} ${login.text}")
        return complete(request, login.header("Location") ?: throw IdmsError("login redirect without Location"))
    }

    /** Complete a login from the redirect to [redirectUri]: check it, then exchange the code. */
    suspend fun complete(request: AuthorizationRequest, redirect: String): Tokens {
        if (!redirect.startsWith("$redirectUri?")) throw IdmsError("redirect not addressed to $redirectUri")
        val params = URI(redirect).rawQuery.split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        params["error"]?.let { throw IdmsError("authorization error: $it") }
        if (params["state"] != request.state) throw IdmsError("state mismatch")
        return exchange(request, params["code"] ?: throw IdmsError("redirect without code"))
    }

    suspend fun exchange(request: AuthorizationRequest, code: String): Tokens {
        val endpoint = discover().str("token_endpoint") ?: throw IdmsError("no token endpoint")
        val r = http.postForm(endpoint, mapOf("grant_type" to "authorization_code", "client_id" to clientId,
            "redirect_uri" to redirectUri, "code" to code, "code_verifier" to request.verifier))
        if (r.status != 200) throw IdmsError("token request: HTTP ${r.status} ${r.text}")
        return tokens(r.text, request.nonce)
    }

    /**
     * New tokens from the refresh token (TS 33.180 B.6, RFC 6749 §6), without the user's
     * password; the IdMS returns the next refresh token too. A refusal throws [IdmsRefused].
     */
    suspend fun refresh(refreshToken: String, nonce: String): Tokens {
        val endpoint = discover().str("token_endpoint") ?: throw IdmsError("no token endpoint")
        val r = http.postForm(endpoint, mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken))
        if (r.status == 400 || r.status == 401) throw IdmsRefused("IdMS refused the refresh token")
        if (r.status != 200) throw IdmsError("token refresh: HTTP ${r.status} ${r.text}")
        return tokens(r.text, nonce)
    }

    private suspend fun tokens(text: String, nonce: String): Tokens {
        val body = Json.parseToJsonElement(text).jsonObject
        val access = body.str("access_token") ?: throw IdmsError("no access token")
        val id = body.str("id_token") ?: throw IdmsError("no ID token")
        val claims = verifyIdToken(id, nonce)
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.longOrNull
        return Tokens(
            accessToken = access, idToken = id,
            expiresAt = expiresIn?.let { clock() + it } ?: (claims["exp"] as? JsonPrimitive)?.longOrNull ?: (clock() + 3600),
            mcpttId = claims.str("mcptt_id"), mcdataId = claims.str("mcdata_id"),
            sipUri = claims.str("sip_uri"), clientId = claims.str("client_id"),
            refreshToken = body.str("refresh_token"), nonce = nonce,
        )
    }

    /** Verify the ID token (OIDC Core §3.1.3.7): signature, issuer, audience, nonce and lifetime. */
    private suspend fun verifyIdToken(token: String, nonce: String): JsonObject {
        val jwt = Jwt.parse(token)
        val header = Json.parseToJsonElement(jwt.headerJson).jsonObject
        val jwksUri = discover().str("jwks_uri") ?: throw IdmsError("no JWKS URI")
        val r = http.get(jwksUri)
        if (r.status != 200) throw IdmsError("JWKS: HTTP ${r.status}")
        val keys = Json.parseToJsonElement(r.text).jsonObject["keys"]!!.jsonArray.map { it.jsonObject }
            .filter { it.str("kty") == "RSA" }.map { Jwk(it.str("kid"), it.str("n")!!, it.str("e")!!) }
        jwt.verifyRs256(keys, header.str("kid"), header.str("alg"))
        val claims = Json.parseToJsonElement(jwt.claimsJson).jsonObject
        if (claims.str("iss") != issuer) throw IdmsError("ID token issuer ${claims.str("iss")}")
        val aud = claims["aud"]
        val audiences = if (aud is JsonPrimitive) listOf(aud.content) else aud?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        if (clientId !in audiences) throw IdmsError("ID token audience $audiences")
        if (claims.str("nonce") != nonce) throw IdmsError("ID token nonce mismatch")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: throw IdmsError("ID token without exp")
        if (exp < clock() - 60) throw IdmsError("ID token expired")
        return claims
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
