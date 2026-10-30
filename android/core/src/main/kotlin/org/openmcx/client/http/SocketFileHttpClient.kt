//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.http

import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.openmcx.client.security.TlsProfile

/**
 * Streaming HTTP/1.1 for FD, retaining the name checks, endpoint map and home HTTP proxy (TS 24.482
 * annex A.2.1.2: TLS tunnel to the proxy, absolute-form request target) of SocketHttpClient.
 */
class SocketFileHttpClient(private val ssl: SSLContext?, private val endpoints: Endpoints = Endpoints.DIRECT,
                           private val timeoutMs: Int = 15_000, proxy: String? = null) : FileHttpClient {
    private val via: URI? = proxy?.let { proxyUri(it) }

    override suspend fun upload(url: String, headers: Map<String, String>, info: ByteArray, file: File,
                                filename: String, progress: (Long) -> Unit): HttpResponse {
        require(filename.isNotBlank() && filename.none { it.isISOControl() }) { "invalid filename" }
        require(info.size <= 65536) { "upload metadata too large" }
        val name = filename.replace('"', '_').replace('\\', '_').replace('/', '_')
        val boundary = "mcx-fd-${UUID.randomUUID()}"
        val prefix = ("--$boundary\r\nContent-Type: application/vnd.3gpp.mcdata-info+xml\r\n\r\n").toByteArray() + info +
            ("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"$name\"\r\n" +
                "Content-Length: ${file.length()}\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val size = file.length()
        val length = Math.addExact(size, prefix.size.toLong() + suffix.size)
        return exchange(url) { socket, uri ->
            val out = socket.getOutputStream()
            writeHead(out, uri, "POST", headers + ("Content-Type" to "multipart/mixed; boundary=$boundary"), length)
            out.write(prefix)
            file.inputStream().use { copy(it, out, size, size, progress) }
            out.write(suffix); out.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input)
            val control = ByteArrayOutputStream()
            readBody(input, head, control, 65536) {}
            HttpResponse(head.status, head.headers, control.toByteArray())
        }
    }

    override suspend fun download(url: String, headers: Map<String, String>, destination: File,
                                  maxBytes: Long?, progress: (Long) -> Unit): HttpResponse {
        require(maxBytes == null || maxBytes >= 0) { "negative download limit" }
        try {
            return exchange(url) { socket, uri ->
                writeHead(socket.getOutputStream(), uri, "GET", headers, null)
                val input = BufferedInputStream(socket.getInputStream())
                val head = readHead(input)
                if (head.status != 200) {
                    destination.delete()
                    val control = ByteArrayOutputStream()
                    readBody(input, head, control, 65536) {}
                    HttpResponse(head.status, head.headers, control.toByteArray())
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { readBody(input, head, it, maxBytes, progress) }
                    HttpResponse(head.status, head.headers, ByteArray(0))
                }
            }
        } catch (e: Throwable) { destination.delete(); throw e }
    }

    private suspend fun <T> exchange(url: String, action: suspend (Socket, URI) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val uri = URI(url)
            require(uri.scheme in listOf("https", "http") && uri.host != null && uri.userInfo == null && uri.fragment == null) { "invalid file URL" }
            // TS 33.180 clauses 6.1.1 and 6.1.2: TLS to the HTTP server endpoint; an http file URL only inside the
            // TLS tunnel to the home HTTP proxy.
            if (via == null && uri.scheme != "https") throw HttpError("not an https URI (TS 33.180 clause 6.1.1): $url")
            // The connection: to the proxy when there is one (always TLS), else to the file's server.
            val connection = via ?: uri
            val https = via != null || uri.scheme == "https"
            val port = connection.port.takeIf { it > 0 } ?: if (https) 443 else 80
            val plain = Socket()
            // Cancellation resumes this sibling and closes blocking socket I/O immediately.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runCatching { plain.close() } }
            }
            try {
                val (address, targetPort) = endpoints.resolve(connection.host, port)
                plain.soTimeout = timeoutMs
                plain.connect(InetSocketAddress(address, targetPort), timeoutMs)
                val socket = if (https) {
                    val context = ssl ?: SSLContext.getDefault()
                    (context.socketFactory.createSocket(plain, connection.host, port, true) as SSLSocket).apply {
                        soTimeout = timeoutMs
                        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        TlsProfile.apply(this)
                        startHandshake()
                    }
                } else plain
                socket.use { action(it, uri) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally { closer.cancel(); plain.close() }
        }
    }

    private fun writeHead(out: OutputStream, uri: URI, method: String, headers: Map<String, String>, length: Long?) {
        val path = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val target = if (via != null) "${uri.scheme}://${uri.rawAuthority}$path" else path
        val head = StringBuilder("$method $target HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\nConnection: close\r\n")
        headers.forEach { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' }) { "invalid HTTP header" }
            require(!name.equals("Host", true) && !name.equals("Content-Length", true) && !name.equals("Transfer-Encoding", true)) { "reserved HTTP header" }
            head.append("$name: $value\r\n")
        }
        length?.let { head.append("Content-Length: $it\r\n") }
        head.append("\r\n"); out.write(head.toString().toByteArray()); out.flush()
    }

    private data class Head(val status: Int, val headers: Map<String, List<String>>) {
        fun header(name: String) = headers.entries.find { it.key.equals(name, true) }?.value?.firstOrNull()
    }
    private fun readHead(input: InputStream): Head {
        var total = 0
        val status = line(input).also { total += it.length }.split(' ').getOrNull(1)?.toIntOrNull() ?: throw HttpError("invalid status line")
        val headers = linkedMapOf<String, MutableList<String>>()
        while (true) {
            val l = line(input); total += l.length
            if (total > 65536) throw HttpError("HTTP headers too large")
            if (l.isEmpty()) break
            val colon = l.indexOf(':'); if (colon <= 0) throw HttpError("invalid HTTP header")
            headers.getOrPut(l.substring(0, colon)) { mutableListOf() }.add(l.substring(colon + 1).trim())
        }
        if (status in 100..199) return readHead(input)
        return Head(status, headers)
    }
    private suspend fun readBody(input: InputStream, head: Head, out: OutputStream, maxBytes: Long?, progress: (Long) -> Unit) {
        if (head.status in 100..199 || head.status in listOf(204, 304)) return
        when {
            head.header("Transfer-Encoding") != null -> {
                if (!head.header("Transfer-Encoding").equals("chunked", true)) throw HttpError("unsupported transfer encoding")
                var total = 0L
                while (true) {
                    val size = line(input).substringBefore(';').trim().toLongOrNull(16) ?: throw HttpError("invalid chunk")
                    if (size < 0) throw HttpError("negative chunk")
                    if (size == 0L) {
                        var trailers = 0
                        while (true) { val l = line(input); trailers += l.length; if (trailers > 65536) throw HttpError("trailers too large"); if (l.isEmpty()) break }
                        break
                    }
                    if (maxBytes != null && size > maxBytes - total) throw HttpError("download exceeds size limit")
                    val base = total
                    copy(input, out, size, size) { progress(base + it) }
                    total = Math.addExact(total, size)
                    if (line(input).isNotEmpty()) throw HttpError("invalid chunk terminator")
                }
            }
            head.header("Content-Length") != null -> {
                val size = head.header("Content-Length")!!.toLongOrNull() ?: throw HttpError("invalid content length")
                if (size < 0 || maxBytes != null && size > maxBytes) throw HttpError("download exceeds size limit")
                copy(input, out, size, maxBytes, progress)
            }
            else -> copy(input, out, null, maxBytes, progress)
        }
    }
    private suspend fun copy(input: InputStream, out: OutputStream, exact: Long?, limit: Long?, progress: (Long) -> Unit) {
        val buffer = ByteArray(65536); var copied = 0L
        while (exact == null || copied < exact) {
            currentCoroutineContext().ensureActive()
            val count = if (exact == null) buffer.size else minOf(buffer.size.toLong(), exact - copied).toInt()
            val n = input.read(buffer, 0, count)
            if (n < 0) { if (exact != null) throw HttpError("incomplete HTTP body"); break }
            if (limit != null && n > limit - copied) throw HttpError("download exceeds size limit")
            out.write(buffer, 0, n); copied += n; progress(copied)
        }
    }
    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw HttpError("incomplete HTTP line")
            if (b == 10) return out.toString("ISO-8859-1").removeSuffix("\r")
            if (out.size() >= 8192) throw HttpError("HTTP line too long")
            out.write(b)
        }
    }
}
