//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.idms

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.security.b64urlEncode
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/** A fake IdMS with the lab's endpoints (docs/android/wire/phase1-startup.md §1). */
private class FakeIdms(var nonceOverride: String? = null, var stateOverride: String? = null) : HttpClient {
    /** What a refresh answers with: an ID token (with the nonce) and a new refresh token, each optional. */
    var refreshIdToken = true
    var refreshNonce = true
    var rotate = true
    /** The ID token's aud (JSON), its iat and further claims, and the token responses' token_type. */
    var audience = "\"mcx-lab-client\""
    var issuedAt = true
    var extraClaims = ""
    var tokenType = "Bearer"
    /** The authorization endpoint answers a client accepting text/html with the user authentication form (as the
     * OpenMCX IdMS and third-party ones do); false: with the request ID of the OpenMCX 1.0.0 IdMS only. */
    var form = true
    var cookie: String? = null
    /** The discovery documents fetched. */
    var discoveries = 0
    val issuer = "https://idms:8443"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    var pending: Map<String, String> = emptyMap()
    private var code: String? = null
    var refresh = 0 // the current refresh token is "RT$refresh"; each use rotates it

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body.toByteArray())
    private fun query(text: String) = text.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun iat(now: Long) = if (issuedAt) """"iat":$now,""" else ""

    private fun jwt(claims: String): String {
        val input = b64urlEncode("""{"alg":"RS256","kid":"k1","typ":"JWT"}""".toByteArray()) + "." + b64urlEncode(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(input.toByteArray()); sign() }
        return input + "." + b64urlEncode(sig)
    }

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val uri = URI(url)
        // The TS 37.579-1 table 5.5.8.1-1 paths of the configured <idms-auth-endpoint> and <idms-token-endpoint>.
        // The form's relative action resolves under /idms/ then.
        val path = mapOf("/idms/auth" to "/authorize", "/idms/userauth" to "/userauth", "/idms/token" to "/token",
            "/idms/keys" to "/jwks")[uri.path] ?: uri.path
        if (path == "/.well-known/openid-configuration") discoveries++
        return when (path) {
            "/.well-known/openid-configuration" -> ok("""{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
                "token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}""")
            "/authorize" -> {
                pending = query(uri.rawQuery)
                if (!form || headers["Accept"]?.contains("text/html") != true) return ok("""{"request_id":"r1"}""")
                // TS 37.579-1 table 5.3.2.3.2-5: the user authentication form, its action relative to the page.
                HttpResponse(200, mapOf("Content-Type" to listOf("text/html; charset=utf-8"),
                    "Set-Cookie" to listOf("AUTH_SESSION=s1; Path=/; Secure; HttpOnly")),
                    """<!doctype html><html><head><style>p{color:red}</style></head><body><!-- <form> -->
                    <form action="userauth?request_id=r1&amp;tab=1" method="POST">
                    <input type="text" name="user"><input type="password" name="password">
                    <input type="hidden" name="credentialId" value=""><input type="submit" value="Login">
                    </form></body></html>""".toByteArray())
            }
            "/userauth" -> {
                val fields = query(String(body!!))
                if (method != "POST" || uri.rawQuery != "request_id=r1&tab=1" ||
                    headers["Cookie"] != "AUTH_SESSION=s1" || fields.keys != setOf("user", "password", "credentialId"))
                    return HttpResponse(400, emptyMap(), """{"error":"invalid_request"}""".toByteArray())
                cookie = headers["Cookie"]
                // RFC 6749 §4.1.2.1: a refused user goes back to the redirect URI with the error.
                val signedIn = fields["user"] == "alice" && fields["password"] == "secret"
                if (signedIn) code = "c1"
                val result = if (signedIn) "code=c1" else "error=access_denied"
                val state = stateOverride ?: pending["state"]
                HttpResponse(302, mapOf("Location" to listOf("${pending["redirect_uri"]}?$result&state=$state")), ByteArray(0))
            }
            "/login" -> {
                val form = query(String(body!!))
                if (form["password"] != "secret") return HttpResponse(401, emptyMap(), """{"error":"access_denied"}""".toByteArray())
                code = "c1"
                HttpResponse(302, mapOf("Location" to listOf("${pending["redirect_uri"]}?code=c1&state=${stateOverride ?: pending["state"]}")), ByteArray(0))
            }
            "/token" -> {
                val form = query(String(body!!))
                if (form["grant_type"] == "refresh_token") {
                    if (form["refresh_token"] != "RT$refresh") return HttpResponse(400, emptyMap(), """{"error":"invalid_grant"}""".toByteArray())
                    if (rotate) refresh++
                    val now = System.currentTimeMillis() / 1000
                    val nonce = if (refreshNonce) """"nonce":"${pending["nonce"]}",""" else ""
                    val id = jwt("""{"iss":"$issuer","aud":$audience,"sub":"sip:alice.mcptt@mcx.test","exp":${now + 3600},
                        ${iat(now)}$extraClaims$nonce"mcptt_id":"sip:alice.mcptt@mcx.test","client_id":"urn:uuid:1"}""")
                    val idPart = if (refreshIdToken) ""","id_token":"$id"""" else ""
                    val refreshPart = if (rotate) ""","refresh_token":"RT$refresh"""" else ""
                    return ok("""{"access_token":"AT$refresh","expires_in":1800$idPart$refreshPart,"token_type":"$tokenType"}""")
                }
                val challenge = b64urlEncode(MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray()))
                if (form["code"] != code || challenge != pending["code_challenge"]) return HttpResponse(400, emptyMap(), ByteArray(0))
                val now = System.currentTimeMillis() / 1000
                val id = jwt("""{"iss":"$issuer","aud":$audience,"sub":"sip:alice.mcptt@mcx.test","exp":${now + 3600},
                    ${iat(now)}$extraClaims"nonce":"${nonceOverride ?: pending["nonce"]}","mcptt_id":"sip:alice.mcptt@mcx.test","mcdata_id":"sip:alice.mcdata@mcx.test",
                    "mcvideo_id":"sip:alice.mcvideo@mcx.test","sip_uri":"sip:alice@mcx.test","client_id":"urn:uuid:1"}""")
                ok("""{"access_token":"AT","expires_in":3600,"id_token":"$id","refresh_token":"RT$refresh","token_type":"$tokenType"}""")
            }
            "/jwks" -> {
                val pub = keys.public as RSAPublicKey
                val n = b64urlEncode(pub.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
                ok("""{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","n":"$n","e":"${b64urlEncode(pub.publicExponent.toByteArray())}"}]}""")
            }
            else -> HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }
}

class IdmsTest {
    private fun client(idms: FakeIdms) = IdmsClient(idms, idms.issuer, "mcx-lab-client", "http://127.0.0.1:8900/callback")

    @Test
    fun configuredEndpointsAreUsedWithoutDiscovery() = runTest {
        // TS 24.484 §7.2.2.7 item 4: <idms-auth-endpoint>, <idms-token-endpoint> (and a JWKS URI) used as given.
        val idms = FakeIdms()
        val endpoints =
            IdmsEndpoints("${idms.issuer}/idms/auth", "${idms.issuer}/idms/token", "${idms.issuer}/idms/keys")
        val client =
            IdmsClient(idms, idms.issuer, "mcx-lab-client", "http://127.0.0.1:8900/callback", endpoints = endpoints)
        val tokens = client.loginDirect("alice", "secret")
        assertEquals("sip:alice.mcptt@mcx.test", tokens.mcpttId)
        assertEquals("AT1", client.refresh(tokens).accessToken)
        assertEquals(0, idms.discoveries)
        // Without a configured JWKS URI, only that one comes from the discovery document.
        val partly = FakeIdms()
        IdmsClient(partly, partly.issuer, "mcx-lab-client", "http://127.0.0.1:8900/callback",
            endpoints = IdmsEndpoints("${partly.issuer}/idms/auth", "${partly.issuer}/idms/token"))
            .loginDirect("alice", "secret")
        assertEquals(1, partly.discoveries)
        assertThrows(IdmsError::class.java) {
            IdmsClient(idms, idms.issuer, "mcx-lab-client", "http://127.0.0.1:8900/callback",
                endpoints = IdmsEndpoints(token = "http://idms:8443/idms/token"))
        }
    }

    @Test
    fun directLogin() = runTest {
        val tokens = client(FakeIdms()).loginDirect("alice", "secret")
        assertEquals("AT", tokens.accessToken)
        assertEquals("sip:alice.mcptt@mcx.test", tokens.mcpttId)
        assertEquals("urn:uuid:1", tokens.clientId)
        assertEquals("sip:alice.mcvideo@mcx.test", tokens.mcvideoId)
    }

    @Test
    fun directLoginFillsTheAuthenticationForm() = runTest {
        // TS 37.579-1 table 5.3.2.3.1-1 steps 3a1 to 9: the form posted with the page's cookie, not POST /login.
        val idms = FakeIdms()
        assertEquals("AT", client(idms).loginDirect("alice", "secret").accessToken)
        assertEquals("AUTH_SESSION=s1", idms.cookie)
        assertThrows(IdmsError::class.java) {
            kotlinx.coroutines.runBlocking { client(FakeIdms()).loginDirect("alice", "wrong") }
        }
    }

    @Test
    fun directLoginFallsBackToTheRequestId() = runTest {
        // The IdMS of OpenMCX 1.0.0: no form, the request ID for POST /login.
        val idms = FakeIdms().apply { form = false }
        assertEquals("AT", client(idms).loginDirect("alice", "secret").accessToken)
        assertEquals(null, idms.cookie)
    }

    @Test
    fun authenticationFormIsReadStrictly() {
        val form = IdmsForm.parse("""<form method=post action='/a?x=1&amp;y=&#50;'><input name=u type=email>
            <input type=password name=p><input type=checkbox name=c><input type=radio name=r value=v checked>
            <input name=d disabled></form>""")!!
        assertEquals("/a?x=1&y=2", form.action)
        assertEquals(mapOf("u" to "alice", "p" to "secret", "r" to "v"), IdmsForm.fields(form, "alice", "secret"))
        assertEquals(null, IdmsForm.parse("<p>no form</p>"))
        for (page in listOf("<form></form><form></form>", "<form><select name=s></select></form>",
                "<form><input type=file name=f></form>", "<form><input name=a", "<form>"))
            assertThrows(IdmsError::class.java) { IdmsForm.parse(page) }
        val noPassword = IdmsForm.parse("<form><input name=u></form>")!!
        assertThrows(IdmsError::class.java) { IdmsForm.fields(noPassword, "a", "b") }
        // RFC 3986 §5.2.2.
        val base = "https://idms:8443/realms/mcx/auth?client_id=c"
        assertEquals("https://idms:8443/realms/mcx/login?s=1", IdmsForm.resolve(base, "login?s=1"))
        assertEquals("https://idms:8443/userauth", IdmsForm.resolve(base, "/userauth"))
        assertEquals("https://idms:8443/realms/mcx/auth?r=1", IdmsForm.resolve(base, "?r=1"))
        assertEquals("https://other/x", IdmsForm.resolve(base, "//other/x"))
        for (action in listOf("http://idms/x", "javascript:x", "../x", "/a/./b"))
            assertThrows(IdmsError::class.java) { IdmsForm.resolve(base, action) }
        assertEquals("a=1; b=2", IdmsForm.cookies(mapOf("set-cookie" to listOf("a=1; Path=/", "b=2"))))
    }

    @Test
    fun authorizationRequestCarriesTheRequiredParameters() = runTest {
        // TS 33.180 table B.4.2.2-1.
        val idms = FakeIdms()
        client(idms).loginDirect("alice", "secret")
        assertEquals("code", idms.pending["response_type"])
        assertEquals("3gpp:acr:password", idms.pending["acr_values"])
        assertEquals("S256", idms.pending["code_challenge_method"])
    }

    @Test
    fun scopeFollowsTheConfiguredServices() {
        assertEquals("openid 3gpp:mc:ptt_service 3gpp:mc:ptt_config_management_service 3gpp:mc:ptt_group_management_service",
            IdmsClient.scope(listOf("ptt")))
        assertEquals("openid" +
            " 3gpp:mc:ptt_service 3gpp:mc:ptt_config_management_service 3gpp:mc:ptt_group_management_service" +
            " 3gpp:mc:data_service 3gpp:mc:data_config_management_service 3gpp:mc:data_group_management_service" +
            " 3gpp:mc:video_service 3gpp:mc:video_config_management_service 3gpp:mc:video_group_management_service" +
            " 3gpp:mc:ptt_key_management_service 3gpp:mc:video_key_management_service",
            IdmsClient.scope(listOf("ptt", "data", "video"), listOf("ptt", "video")))
    }

    @Test
    fun refreshTokenRenewsWithoutThePassword() = runTest {
        val idms = FakeIdms()
        val client = client(idms)
        val first = client.loginDirect("alice", "secret")
        assertEquals("RT0", first.refreshToken)
        val second = client.refresh(first)
        assertEquals("AT1", second.accessToken)
        assertEquals("RT1", second.refreshToken)
        assertEquals("sip:alice.mcptt@mcx.test", second.mcpttId)
        // The used token was rotated away: IdMS refuses it.
        assertThrows(IdmsRefused::class.java) { kotlinx.coroutines.runBlocking { client.refresh(first) } }
        // An ID token whose nonce is not the authentication's is refused.
        assertThrows(IdmsError::class.java) { kotlinx.coroutines.runBlocking { client.refresh(second.copy(nonce = "other")) } }
    }

    @Test
    fun refreshAnswerWithoutIdTokenNonceOrNewRefreshToken() = runTest {
        // TS 33.180 table B.5.3-1 and OIDC Core §12.2: the ID token, its nonce and a new refresh token are optional.
        val idms = FakeIdms()
        val client = client(idms)
        val first = client.loginDirect("alice", "secret")
        idms.refreshNonce = false
        val second = client.refresh(first)
        assertEquals("sip:alice.mcptt@mcx.test", second.mcpttId)
        idms.refreshIdToken = false
        idms.rotate = false
        val third = client.refresh(second)
        assertEquals("AT1", third.accessToken)
        assertEquals("RT1", third.refreshToken)
        assertEquals(second.idToken, third.idToken)
        assertEquals("sip:alice.mcptt@mcx.test", third.mcpttId)
    }

    @Test
    fun acceptsTheCaseOfTheBearerTokenType() = runTest {
        // RFC 6749 §5.1: the token_type value is case insensitive.
        assertEquals("AT", client(FakeIdms().apply { tokenType = "bearer" }).loginDirect("alice", "secret").accessToken)
    }

    @Test
    fun rejectsIdTokensAndTokenTypesTheProfileDoesNotAllow() = runTest {
        val refused = listOf<FakeIdms.() -> Unit>(
            // OIDC Core §3.1.3.7 step 3: an additional audience.
            { audience = """["mcx-lab-client","account"]""" },
            // TS 33.180 table B.2.1.2-1: iat is REQUIRED.
            { issuedAt = false },
            // RFC 7519 §4.1.5: not before a future nbf.
            { extraClaims = """"nbf":${System.currentTimeMillis() / 1000 + 600},""" },
            // RFC 6749 §7.1: an access token of a type this client does not understand.
            { tokenType = "DPoP" },
        )
        for (change in refused) {
            val idms = FakeIdms().apply(change)
            assertThrows(IdmsError::class.java) { kotlinx.coroutines.runBlocking { client(idms).loginDirect("alice", "secret") } }
        }
        // An audience list of the client alone is accepted.
        client(FakeIdms().apply { audience = """["mcx-lab-client"]""" }).loginDirect("alice", "secret")
    }

    @Test
    fun rejectsBadCredentialsStateAndNonce() = runTest {
        assertThrows(IdmsError::class.java) { kotlinx.coroutines.runBlocking { client(FakeIdms()).loginDirect("alice", "wrong") } }
        assertThrows(IdmsError::class.java) { kotlinx.coroutines.runBlocking { client(FakeIdms(stateOverride = "x")).loginDirect("alice", "secret") } }
        assertThrows(IdmsError::class.java) { kotlinx.coroutines.runBlocking { client(FakeIdms(nonceOverride = "x")).loginDirect("alice", "secret") } }
    }
}

class LoopbackRedirectTest {
    @Test
    fun capturesTheRedirect() = kotlinx.coroutines.runBlocking {
        val scope = this
        LoopbackRedirect("http://127.0.0.1:0/callback").use { loopback ->
            val waiting = scope.async(kotlinx.coroutines.Dispatchers.IO) { loopback.await(5_000) }
            val port = java.net.URI(loopback.boundUri).port
            val miss = java.net.URI("http://127.0.0.1:$port/favicon.ico").toURL().openConnection() as java.net.HttpURLConnection
            assertEquals(404, miss.responseCode)
            val hit = java.net.URI("http://127.0.0.1:$port/callback?code=c1&state=s1").toURL().openConnection() as java.net.HttpURLConnection
            assertEquals(200, hit.responseCode)
            assertEquals("http://127.0.0.1:$port/callback?code=c1&state=s1", waiting.await())
        }
    }
}
