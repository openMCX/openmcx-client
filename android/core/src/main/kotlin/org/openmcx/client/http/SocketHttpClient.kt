package org.openmcx.client.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Where to connect for a host and port: the name itself, or an address provisioned for it
 * (a lab reached through published ports). TLS always uses the name, for SNI and for the
 * certificate check.
 */
fun interface Endpoints {
    fun resolve(host: String, port: Int): Pair<String, Int>

    companion object {
        val DIRECT = Endpoints { host, port -> host to port }

        /** From `"name:port" -> "address:port"` entries. */
        fun of(map: Map<String, String>) = if (map.isEmpty()) DIRECT else Endpoints { host, port ->
            map["$host:$port"]?.let { target ->
                val colon = target.lastIndexOf(':')
                target.substring(0, colon) to target.substring(colon + 1).toInt()
            } ?: (host to port)
        }
    }
}

/**
 * HTTP/1.1 over plain or TLS sockets (RFC 9112), one request per connection. Unlike
 * HttpURLConnection it can connect to a provisioned address while presenting and checking the
 * server's name. Redirects are not followed.
 */
class SocketHttpClient(
    private val ssl: SSLContext?,
    private val endpoints: Endpoints = Endpoints.DIRECT,
    private val timeoutMs: Int = 15_000,
) : HttpClient {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            val https = uri.scheme.equals("https", true)
            val host = uri.host ?: throw HttpError("no host in $url")
            val port = if (uri.port > 0) uri.port else if (https) 443 else 80
            val (address, targetPort) = endpoints.resolve(host, port)
            val plain = Socket().apply { soTimeout = timeoutMs; connect(InetSocketAddress(address, targetPort), timeoutMs) }
            val socket = if (https) {
                val context = ssl ?: SSLContext.getDefault()
                (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).apply {
                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    startHandshake()
                }
            } else plain
            socket.use {
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val defaultPort = if (https) 443 else 80
                val head = StringBuilder("$method $path HTTP/1.1\r\n")
                    .append("Host: ").append(if (port == defaultPort) host else "$host:$port").append("\r\n")
                    .append("Connection: close\r\n")
                headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
                head.append("\r\n")
                val out = socket.getOutputStream()
                out.write(head.toString().toByteArray())
                body?.let(out::write)
                out.flush()
                read(BufferedInputStream(socket.getInputStream()), method)
            }
        }

    private fun read(input: InputStream, method: String): HttpResponse {
        val status = line(input) ?: throw HttpError("no response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw HttpError("bad status line: $status")
        val headers = linkedMapOf<String, MutableList<String>>()
        while (true) {
            val l = line(input) ?: break
            if (l.isEmpty()) break
            val colon = l.indexOf(':')
            if (colon > 0) headers.getOrPut(l.substring(0, colon).trim()) { mutableListOf() } += l.substring(colon + 1).trim()
        }
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val body = when {
            method == "HEAD" || code in 100..199 || code == 204 || code == 304 -> ByteArray(0)
            header("Transfer-Encoding")?.contains("chunked", true) == true -> chunked(input)
            header("Content-Length") != null -> input.readNBytes(header("Content-Length")!!.trim().toInt())
            else -> input.readBytes()
        }
        return HttpResponse(code, headers, body)
    }

    private fun chunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = (line(input) ?: break).substringBefore(';').trim().toInt(16)
            if (size == 0) { while (!line(input).isNullOrEmpty()) Unit; break }
            out.write(input.readNBytes(size))
            line(input)
        }
        return out.toByteArray()
    }

    /** A CRLF-terminated line, or null at the end of the stream. */
    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
            out.write(b)
        }
    }
}
