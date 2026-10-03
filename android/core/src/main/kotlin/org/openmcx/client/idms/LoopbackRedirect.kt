package org.openmcx.client.idms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI

/**
 * Receives an OAuth redirect on the loopback interface (RFC 8252 §7.3), for a native app
 * whose redirect URI is `http://127.0.0.1:<port>/<path>`. The browser is sent to the
 * authorization endpoint; the IdMS redirects it back here, and [await] returns the full
 * redirect URI for [IdmsClient.complete].
 */
class LoopbackRedirect(redirectUri: String) : AutoCloseable {
    private val uri = URI(redirectUri)
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(uri.host), if (uri.port > 0) uri.port else 0))
    }

    /** The redirect URI with the port actually bound (the IdMS must accept it). */
    val boundUri: String = "http://${uri.host}:${server.localPort}${uri.rawPath}"

    suspend fun await(timeoutMs: Int = 300_000): String = withContext(Dispatchers.IO) {
        server.soTimeout = timeoutMs
        while (true) {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@use
                while (!reader.readLine().isNullOrEmpty()) Unit // headers
                val target = line.split(' ').getOrNull(1) ?: ""
                val ours = target.substringBefore('?') == uri.rawPath
                val body = if (ours) "Signed in. You can return to the app." else "Not found"
                val status = if (ours) "200 OK" else "404 Not Found"
                socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                if (ours) return@withContext "http://${uri.host}:${server.localPort}$target"
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    override fun close() = server.close()
}
