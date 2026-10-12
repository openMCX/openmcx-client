//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openmcx.client.http.UrlConnectionHttpClient
import org.openmcx.client.idms.IdmsClient
import org.openmcx.client.idms.LoopbackRedirect
import org.openmcx.client.security.b64urlEncode
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.concurrent.thread

/**
 * The interactive login on a real device: the authorization request opens in a Custom Tab
 * (the system browser), the IdMS redirects the browser to the app's loopback redirect URI
 * (RFC 8252 §7.3), and the app exchanges the code. The IdMS here is a minimal one in the test
 * process, standing in for an IdMS with a login page (the OpenMCX lab IdMS has none). Its
 * authorization endpoint answers as for a user who has already signed in.
 */
@RunWith(AndroidJUnit4::class)
class BrowserLoginTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val issuer = "http://127.0.0.1:${server.localPort}"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var authorize: Map<String, String> = emptyMap()
    @Volatile private var browserVisited = false

    private fun query(text: String) = text.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private fun jwt(claims: String): String {
        val input = b64urlEncode("""{"alg":"RS256","kid":"k1"}""".toByteArray()) + "." + b64urlEncode(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(input.toByteArray()); sign() }
        return "$input.${b64urlEncode(sig)}"
    }

    private val idms = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            // One thread per connection: browsers open speculative connections that never send a request.
            thread(isDaemon = true) { try { socket.soTimeout = 30_000; socket.use {
                val input = it.getInputStream().bufferedReader()
                val request = input.readLine() ?: return@use
                var length = 0
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                val body = CharArray(length).also { buf -> var n = 0; while (n < length) n += input.read(buf, n, length - n) }.concatToString()
                val target = request.split(' ')[1]
                val path = target.substringBefore('?')
                val (status, headers, content) = when (path) {
                    "/.well-known/openid-configuration" -> Triple("200 OK", "Content-Type: application/json\r\n",
                        """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}""")
                    "/authorize" -> {
                        browserVisited = true
                        authorize = query(target.substringAfter('?'))
                        Triple("302 Found", "Location: ${authorize["redirect_uri"]}?code=c1&state=${authorize["state"]}\r\n", "")
                    }
                    "/token" -> {
                        val form = query(body)
                        val challenge = b64urlEncode(MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray()))
                        if (form["code"] != "c1" || challenge != authorize["code_challenge"]) Triple("400 Bad Request", "", "")
                        else {
                            val now = System.currentTimeMillis() / 1000
                            val id = jwt("""{"iss":"$issuer","aud":"mcx-lab-client","exp":${now + 3600},"nonce":"${authorize["nonce"]}",""" +
                                """"mcptt_id":"sip:alice.mcptt@mcx.test","client_id":"urn:uuid:1"}""")
                            Triple("200 OK", "Content-Type: application/json\r\n", """{"access_token":"AT","expires_in":3600,"id_token":"$id"}""")
                        }
                    }
                    "/jwks" -> {
                        val pub = keys.public as RSAPublicKey
                        val n = b64urlEncode(pub.modulus.toByteArray().dropWhile { b -> b == 0.toByte() }.toByteArray())
                        Triple("200 OK", "Content-Type: application/json\r\n",
                            """{"keys":[{"kty":"RSA","kid":"k1","n":"$n","e":"${b64urlEncode(pub.publicExponent.toByteArray())}"}]}""")
                    }
                    else -> Triple("404 Not Found", "", "")
                }
                it.getOutputStream().write("HTTP/1.1 $status\r\n${headers}Content-Length: ${content.toByteArray().size}\r\nConnection: close\r\n\r\n$content".toByteArray())
            } } catch (e: Exception) { android.util.Log.w("BrowserLoginTest", "fake IdMS: ${e.message}") } }
        }
    }

    @After
    fun stop() = server.close()

    @Test
    fun signsInThroughTheBrowserAndLoopbackRedirect() = runBlocking {
        LoopbackRedirect("http://127.0.0.1:0/callback").use { loopback ->
            val client = IdmsClient(UrlConnectionHttpClient(null), issuer, "mcx-lab-client", loopback.boundUri)
            val request = client.authorizationUrl()
            customTabBrowser(context).open(request.url)
            val redirect = withTimeout(60_000) { loopback.await() }
            val tokens = client.complete(request, redirect)
            assertEquals(true, browserVisited)
            assertEquals("AT", tokens.accessToken)
            assertEquals("sip:alice.mcptt@mcx.test", tokens.mcpttId)
        }
    }
}
