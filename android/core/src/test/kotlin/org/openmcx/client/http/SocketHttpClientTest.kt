//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertThrows

class SocketHttpClientTest {
    /** A one-shot server answering with [response]; returns its port and a holder for the request it saw. */
    private fun serve(response: String): Pair<Int, StringBuilder> {
        val server = ServerSocket(0)
        val seen = StringBuilder()
        thread(isDaemon = true) {
            server.use { s -> s.accept().use { c ->
                val input = c.getInputStream().bufferedReader()
                while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break; seen.append(l).append('\n') }
                c.getOutputStream().write(response.toByteArray())
            } }
        }
        return server.localPort to seen
    }

    @Test
    fun contentLengthAndHostName() = runBlocking {
        val (port, seen) = serve("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
        // The name "idms" does not resolve here: the endpoint map sends the connection to loopback.
        val client = SocketHttpClient(null, Endpoints.of(mapOf("idms:8443" to "127.0.0.1:$port")))
        val r = client.get("http://idms:8443/.well-known/openid-configuration", mapOf("Authorization" to "Bearer t"))
        assertEquals(200, r.status)
        assertEquals("{}", r.text)
        assertEquals("application/json", r.header("content-type"))
        assertEquals("GET /.well-known/openid-configuration HTTP/1.1", seen.lines().first())
        assertEquals(true, seen.contains("Host: idms:8443\n") && seen.contains("Authorization: Bearer t\n"))
    }

    @Test
    fun chunkedBodyAndRedirectNotFollowed() = runBlocking {
        val (port, _) = serve("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:8900/callback?code=c\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "4\r\nWiki\r\n5;x=1\r\npedia\r\n0\r\n\r\n")
        val r = SocketHttpClient(null).postForm("http://127.0.0.1:$port/login", mapOf("a" to "b"))
        assertEquals(302, r.status)
        assertEquals("http://127.0.0.1:8900/callback?code=c", r.header("Location"))
        assertEquals("Wikipedia", r.text)
    }

    /** A TLS context with the test-only key and certificate of proxy.test (test resource), trusting that certificate. */
    private fun proxyTls(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply {
            SocketHttpClientTest::class.java.classLoader!!.getResourceAsStream("proxy-test.p12").use { load(it, "test".toCharArray()) }
        }
        return SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test".toCharArray()) }.keyManagers,
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers, null)
        }
    }

    /** A one-shot TLS server (proxy.test's certificate) answering with [response]; its port and the request it saw. */
    private fun serveTls(context: SSLContext, response: String): Pair<Int, StringBuilder> {
        val server = context.serverSocketFactory.createServerSocket(0)
        val seen = StringBuilder()
        thread(isDaemon = true) {
            server.use { s -> runCatching { s.accept().use { c ->
                val input = c.getInputStream().bufferedReader()
                while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break; seen.append(l).append('\n') }
                c.getOutputStream().write(response.toByteArray())
            } } }
        }
        return server.localPort to seen
    }

    /**
     * TS 24.482 annex A.2.1.2 with RFC 9112 §3.2.2: through the home HTTP proxy the TLS tunnel is checked against
     * the proxy's FQDN, not the target's, an http target goes through it too, and the request line carries the
     * target URI in absolute-form with the target's authority in Host.
     */
    @Test
    fun homeHttpProxy() = runBlocking {
        val tls = proxyTls()
        val (port, seen) = serveTls(tls, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        val client = SocketHttpClient(tls, Endpoints.of(mapOf("proxy.test:8443" to "127.0.0.1:$port")), proxy = "https://proxy.test:8443")
        val r = client.get("http://xcap.cms.test/xcap-root/a?b=c", mapOf("Authorization" to "Bearer t"))
        assertEquals(200, r.status)
        assertEquals("GET http://xcap.cms.test/xcap-root/a?b=c HTTP/1.1", seen.lines().first())
        assertEquals(true, seen.contains("Host: xcap.cms.test\n") && !seen.contains("Host: proxy.test"))

        val (other, _) = serveTls(tls, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        val misnamed = SocketHttpClient(tls, Endpoints.of(mapOf("other.test:443" to "127.0.0.1:$other")), proxy = "https://other.test")
        assertThrows(Exception::class.java) { runBlocking { misnamed.get("https://proxy.test/") } }
        for (uri in listOf("http://proxy.test", "https://proxy.test/path", "https://u@proxy.test", "proxy.test"))
            assertThrows(IllegalArgumentException::class.java) { SocketHttpClient(tls, proxy = uri) }
        assertThrows(HttpError::class.java) { runBlocking { client.get("ftp://kms.test/") } }
        Unit
    }
}
