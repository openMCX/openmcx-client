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
    val issuer = "https://idms:8443"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var pending: Map<String, String> = emptyMap()
    private var code: String? = null
    var refresh = 0 // the current refresh token is "RT$refresh"; each use rotates it

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body.toByteArray())
    private fun query(text: String) = text.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun jwt(claims: String): String {
        val input = b64urlEncode("""{"alg":"RS256","kid":"k1","typ":"JWT"}""".toByteArray()) + "." + b64urlEncode(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(input.toByteArray()); sign() }
        return input + "." + b64urlEncode(sig)
    }

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val uri = URI(url)
        return when (uri.path) {
            "/.well-known/openid-configuration" -> ok("""{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
                "token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}""")
            "/authorize" -> { pending = query(uri.rawQuery); ok("""{"request_id":"r1"}""") }
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
                    refresh++
                    val now = System.currentTimeMillis() / 1000
                    val id = jwt("""{"iss":"$issuer","aud":"mcx-lab-client","sub":"sip:alice.mcptt@mcx.test","exp":${now + 3600},
                        "nonce":"${pending["nonce"]}","mcptt_id":"sip:alice.mcptt@mcx.test","client_id":"urn:uuid:1"}""")
                    return ok("""{"access_token":"AT$refresh","expires_in":3600,"id_token":"$id","refresh_token":"RT$refresh","token_type":"Bearer"}""")
                }
                val challenge = b64urlEncode(MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray()))
                if (form["code"] != code || challenge != pending["code_challenge"]) return HttpResponse(400, emptyMap(), ByteArray(0))
                val now = System.currentTimeMillis() / 1000
                val id = jwt("""{"iss":"$issuer","aud":"mcx-lab-client","sub":"sip:alice.mcptt@mcx.test","exp":${now + 3600},
                    "nonce":"${nonceOverride ?: pending["nonce"]}","mcptt_id":"sip:alice.mcptt@mcx.test","mcdata_id":"sip:alice.mcdata@mcx.test",
                    "sip_uri":"sip:alice@mcx.test","client_id":"urn:uuid:1"}""")
                ok("""{"access_token":"AT","expires_in":3600,"id_token":"$id","refresh_token":"RT$refresh","token_type":"Bearer"}""")
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
    fun directLogin() = runTest {
        val tokens = client(FakeIdms()).loginDirect("alice", "secret")
        assertEquals("AT", tokens.accessToken)
        assertEquals("sip:alice.mcptt@mcx.test", tokens.mcpttId)
        assertEquals("urn:uuid:1", tokens.clientId)
    }

    @Test
    fun refreshTokenRenewsWithoutThePassword() = runTest {
        val idms = FakeIdms()
        val client = client(idms)
        val first = client.loginDirect("alice", "secret")
        assertEquals("RT0", first.refreshToken)
        val second = client.refresh(first.refreshToken!!, first.nonce)
        assertEquals("AT1", second.accessToken)
        assertEquals("RT1", second.refreshToken)
        assertEquals("sip:alice.mcptt@mcx.test", second.mcpttId)
        // The used token was rotated away: IdMS refuses it.
        assertThrows(IdmsRefused::class.java) { kotlinx.coroutines.runBlocking { client.refresh("RT0", first.nonce) } }
        // An ID token whose nonce is not the authentication's is refused.
        assertThrows(IdmsError::class.java) { kotlinx.coroutines.runBlocking { client.refresh(second.refreshToken!!, "other") } }
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
