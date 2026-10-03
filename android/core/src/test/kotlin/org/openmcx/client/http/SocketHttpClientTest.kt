package org.openmcx.client.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

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
}
