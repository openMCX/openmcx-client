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
    val mcvideoId: String?,
    val sipUri: String?,
    /** The MC service client ID the IdMS bound into the token. */
    val clientId: String?,
    /** The refresh token (TS 33.180 B.5), when the IdMS issues one. */
    val refreshToken: String? = null,
    /** The nonce of the authentication; an ID token of a later refresh repeats it, if it has one (OIDC Core §12.2). */
    val nonce: String = "",
)

/**
 * The IdMS endpoints of the initial MC UE configuration (TS 24.484 §7.2.2.7 item 4): `<idms-auth-endpoint>`,
 * `<idms-token-endpoint>`, and a JWKS URI; null where not configured.
 */
data class IdmsEndpoints(val auth: String? = null, val token: String? = null, val jwks: String? = null)

/** An authorization request in progress: what the IdMS must echo back, and the PKCE verifier. */
data class AuthorizationRequest(val url: String, val state: String, val nonce: String, val verifier: String)

/**
 * OIDC authorization code flow with PKCE (TS 24.482 §6, RFC 7636) against the IdMS.
 *
 * The interactive path hands [authorizationUrl] to a browser and completes with
 * [exchange] once the redirect arrives. The configured-credential path ([loginDirect]) fills
 * the IdMS's user authentication form itself, as the Linux client does.
 */
class IdmsClient(
    private val http: HttpClient,
    private val issuer: String,
    private val clientId: String,
    private val redirectUri: String,
    private val scope: String = "openid 3gpp:mc:ptt_service 3gpp:mc:data_service",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * TS 24.484 §7.2.2.7 item 4: the configured `<idms-auth-endpoint>`, `<idms-token-endpoint>` and a JWKS URI (OIDC
     * Core §10, keys "by other means"), used as given (https, TS 33.180 §5.1.2.1); each one not configured comes from
     * the issuer's discovery document.
     */
    private val endpoints: IdmsEndpoints = IdmsEndpoints(),
) {
    private val random = SecureRandom()
    private var discovery: JsonObject? = null

    private fun token(bytes: Int) = b64urlEncode(ByteArray(bytes).also(random::nextBytes))

    init {
        listOfNotNull(endpoints.auth, endpoints.token, endpoints.jwks).forEach {
            if (!it.startsWith("https://")) throw IdmsError("configured IdMS endpoint $it is not an https URI")
        }
    }

    /** The configured endpoint, or the one of the discovery document (fetched only then). */
    private suspend fun endpoint(configured: String?, name: String): String =
        configured ?: discover().str(name) ?: throw IdmsError("no $name")

    private suspend fun discover(): JsonObject = discovery ?: run {
        val r = http.get("$issuer/.well-known/openid-configuration")
        if (r.status != 200) throw IdmsError("discovery: HTTP ${r.status}")
        Json.parseToJsonElement(r.text).jsonObject.also {
            if (it.str("issuer") != issuer) throw IdmsError("discovery issuer ${it.str("issuer")} is not $issuer")
            discovery = it
        }
    }

    suspend fun authorizationUrl(): AuthorizationRequest {
        val endpoint = endpoint(endpoints.auth, "authorization_endpoint")
        val verifier = token(48)
        val challenge = b64urlEncode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = token(16)
        val nonce = token(16)
        val query = HttpClient.formEncode(linkedMapOf(
            "response_type" to "code", "client_id" to clientId, "redirect_uri" to redirectUri, "scope" to scope,
            "state" to state, "nonce" to nonce,
            // TS 33.180 table B.4.2.2-1: REQUIRED, the password-based ACR value.
            "acr_values" to "3gpp:acr:password",
            "code_challenge" to challenge, "code_challenge_method" to "S256",
        ))
        return AuthorizationRequest("$endpoint?$query", state, nonce, verifier)
    }

    /**
     * The login with configured credentials, as the Linux client's oidc_login: TS 24.482 §6.2.1 and TS 33.180
     * §B.4.2.2-B.4.2.5, with the test view of TS 37.579-1 table 5.3.2.3.1-1 steps 3a1 to 9: the Authentication
     * Request (an HTTP GET of the authorization endpoint), the IdMS's HTML page with the user authentication form
     * (table 5.3.2.3.2-5), the form posted to its action with the user name and password (table 5.5.4.3-1, condition
     * USERAUTH) and the cookies the page set, the 302 (Found) to the redirect URI with the authorization code, and
     * the code exchange. An IdMS that offers no form but the OpenMCX request ID (the IdMS of OpenMCX 1.0.0) is
     * answered with POST {issuer}/login.
     */
    suspend fun loginDirect(username: String, password: String): Tokens {
        val request = authorizationUrl()
        val page = http.get(request.url, mapOf("Accept" to "text/html, application/json;q=0.5"))
        if (page.status != 200) throw IdmsError("authorization request: HTTP ${page.status} ${page.text}")
        val login = if (page.header("Content-Type")?.lowercase()?.startsWith("text/html") == true) {
            val form = IdmsForm.parse(page.text) ?: throw IdmsError("the IdMS page has no user authentication form")
            // A GET form would put the password in the URI.
            if (form.method != "post") throw IdmsError("the IdMS user authentication form is not a POST")
            val cookies = IdmsForm.cookies(page.headers)
            http.postForm(IdmsForm.resolve(request.url, form.action), IdmsForm.fields(form, username, password),
                if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies))
        } else {
            val requestId = Json.parseToJsonElement(page.text).jsonObject.str("request_id")
                ?: throw IdmsError("the IdMS offers neither a user authentication form nor a request ID")
            http.postForm("$issuer/login",
                mapOf("request_id" to requestId, "username" to username, "password" to password))
        }
        if (login.status != 302) throw IdmsError("IdMS user authentication failed: HTTP ${login.status} ${login.text}")
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
        val endpoint = endpoint(endpoints.token, "token_endpoint")
        val r = http.postForm(endpoint, mapOf("grant_type" to "authorization_code", "client_id" to clientId,
            "redirect_uri" to redirectUri, "code" to code, "code_verifier" to request.verifier))
        if (r.status != 200) throw IdmsError("token request: HTTP ${r.status} ${r.text}")
        return tokens(r.text, request.nonce)
    }

    /**
     * New tokens from the refresh token of [current] (TS 33.180 B.5, RFC 6749 §6), without the user's
     * password. A refusal throws [IdmsRefused]. The answer's ID token and next refresh token are optional
     * (TS 33.180 table B.5.3-1): without them the identity and the refresh token stay those of [current].
     */
    suspend fun refresh(current: Tokens): Tokens {
        val refreshToken = current.refreshToken ?: throw IdmsError("no refresh token")
        val endpoint = endpoint(endpoints.token, "token_endpoint")
        val r = http.postForm(endpoint, mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken))
        if (r.status == 400 || r.status == 401) throw IdmsRefused("IdMS refused the refresh token")
        if (r.status != 200) throw IdmsError("token refresh: HTTP ${r.status} ${r.text}")
        return tokens(r.text, current.nonce, current)
    }

    private suspend fun tokens(text: String, nonce: String, previous: Tokens? = null): Tokens {
        val body = Json.parseToJsonElement(text).jsonObject
        val access = body.str("access_token") ?: throw IdmsError("no access token")
        // RFC 6749 §7.1: "The client MUST NOT use an access token if it does not understand the token type"; TS 33.180
        // §B.10 and tables B.4.2.5-1 and B.5.3-1: "bearer" (RFC 6749 §5.1: "Value is case insensitive").
        if (!body.str("token_type").equals("bearer", ignoreCase = true)) throw IdmsError("token type ${body.str("token_type")}")
        // TS 33.180 annex B.12: the client takes the access token's lifetime from expires_in.
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.longOrNull
        val refreshToken = body.str("refresh_token") ?: previous?.refreshToken
        val id = body.str("id_token")
        if (id == null) {
            if (previous == null) throw IdmsError("no ID token")
            return previous.copy(accessToken = access, refreshToken = refreshToken,
                expiresAt = clock() + (expiresIn ?: throw IdmsError("no expires_in")))
        }
        val claims = verifyIdToken(id, nonce, refreshed = previous != null)
        return Tokens(
            accessToken = access, idToken = id,
            expiresAt = expiresIn?.let { clock() + it } ?: (claims["exp"] as? JsonPrimitive)?.longOrNull ?: (clock() + 3600),
            // TS 33.180 table B.2.1.3-1: the MCX claims of the ID token.
            mcpttId = claims.str("mcptt_id"), mcdataId = claims.str("mcdata_id"), mcvideoId = claims.str("mcvideo_id"),
            sipUri = claims.str("sip_uri"), clientId = claims.str("client_id"),
            refreshToken = refreshToken, nonce = nonce,
        )
    }

    /**
     * Verify the ID token (OIDC Core §3.1.3.7): signature, issuer, audience, nonce and lifetime, and the claims TS 33.180
     * table B.2.1.2-1 requires (sub, iat). The ID token of a refresh may leave the nonce out (OIDC Core §12.2).
     */
    private suspend fun verifyIdToken(token: String, nonce: String, refreshed: Boolean = false): JsonObject {
        val jwt = Jwt.parse(token)
        val header = Json.parseToJsonElement(jwt.headerJson).jsonObject
        val jwksUri = endpoint(endpoints.jwks, "jwks_uri")
        val r = http.get(jwksUri)
        if (r.status != 200) throw IdmsError("JWKS: HTTP ${r.status}")
        val keys = Json.parseToJsonElement(r.text).jsonObject["keys"]!!.jsonArray.map { it.jsonObject }
            .filter { it.str("kty") == "RSA" }.map { Jwk(it.str("kid"), it.str("n")!!, it.str("e")!!) }
        jwt.verifyRs256(keys, header.str("kid"), header.str("alg"))
        val claims = Json.parseToJsonElement(jwt.claimsJson).jsonObject
        if (claims.str("iss") != issuer) throw IdmsError("ID token issuer ${claims.str("iss")}")
        val aud = claims["aud"]
        val audiences = if (aud is JsonPrimitive) listOf(aud.content) else aud?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        // OIDC Core §3.1.3.7 step 3: rejected "if it contains additional audiences not trusted by the Client" (this
        // client trusts no other).
        if (audiences.isEmpty() || audiences.any { it != clientId }) throw IdmsError("ID token audience $audiences")
        if (claims.str("nonce") != nonce && !(refreshed && claims["nonce"] == null)) throw IdmsError("ID token nonce mismatch")
        if (claims.str("sub").isNullOrEmpty()) throw IdmsError("ID token without sub")
        if ((claims["iat"] as? JsonPrimitive)?.longOrNull == null) throw IdmsError("ID token without iat")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: throw IdmsError("ID token without exp")
        if (exp < clock() - 60) throw IdmsError("ID token expired")
        // RFC 7519 §4.1.5: not "accepted for processing" before nbf.
        claims["nbf"]?.let { nbf ->
            val notBefore = (nbf as? JsonPrimitive)?.longOrNull ?: throw IdmsError("ID token with an invalid nbf")
            if (notBefore > clock()) throw IdmsError("ID token not yet valid")
        }
        return claims
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        /**
         * The scope of the authentication request (TS 33.180 table B.4.2.2-1): "openid" and, for each configured
         * service ("ptt", "data", "video"), the service itself and the CMS and GMS that serve its documents, plus
         * the KMS of each protected service.
         */
        fun scope(services: List<String>, protectedServices: List<String> = emptyList()): String =
            (listOf("openid") +
                services.flatMap { listOf("3gpp:mc:${it}_service", "3gpp:mc:${it}_config_management_service",
                    "3gpp:mc:${it}_group_management_service") } +
                protectedServices.map { "3gpp:mc:${it}_key_management_service" }).joinToString(" ")
    }
}
