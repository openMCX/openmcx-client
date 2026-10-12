//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.http

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SocketFileHttpClientTest {
    private data class Request(val headers: Map<String, String>, val body: File)
    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) out.write(b) }
        return out.toString("UTF-8")
    }
    private fun headers(input: InputStream): Map<String, String> {
        line(input)
        val values = mutableMapOf<String, String>()
        while (true) {
            val l = line(input); if (l.isEmpty()) break
            values[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
        }
        return values
    }
    private fun peer(handler: (Socket) -> Unit): Pair<Int, CompletableFuture<Unit>> {
        val server = ServerSocket(0); val done = CompletableFuture<Unit>()
        thread(isDaemon = true) {
            try { server.use { it.accept().use(handler) }; done.complete(Unit) }
            catch (e: Throwable) { done.completeExceptionally(e) }
        }
        return server.localPort to done
    }
    private fun response(text: String) = peer { socket ->
        headers(socket.getInputStream()); socket.getOutputStream().write(text.toByteArray())
    }
    private fun digest(file: File, skip: Long = 0, count: Long = file.length()): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            input.skipNBytes(skip); var remaining = count; val buffer = ByteArray(8192)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0); hash.update(buffer, 0, n); remaining -= n
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun uploadStreamsMultipartAndMatchesBinaryBytes() = runBlocking<Unit> {
        val file = File.createTempFile("fd-source", ".bin")
        val raw = File.createTempFile("fd-request", ".bin")
        try {
            // Larger than the old 32 MiB proxy ceiling, generated without a whole-file array.
            file.outputStream().use { out -> val block = ByteArray(65536) { (it % 251).toByte() }; repeat(513) { out.write(block) } }
            val seen = CompletableFuture<Request>()
            val (port, done) = peer { socket ->
                val input = BufferedInputStream(socket.getInputStream()); val h = headers(input)
                var left = h.getValue("content-length").toLong()
                raw.outputStream().use { out -> val b = ByteArray(8192)
                    while (left > 0) { val n = input.read(b, 0, minOf(b.size.toLong(), left).toInt()); check(n > 0); out.write(b, 0, n); left -= n }
                }
                seen.complete(Request(h, raw))
                socket.getOutputStream().write("HTTP/1.1 201 Created\r\nLocation: https://proxy/content/f\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
            var progress = 0L
            val result = SocketFileHttpClient(null).upload("http://127.0.0.1:$port/files", mapOf("Authorization" to "Bearer test"),
                "<info/>".toByteArray(), file, "binary.bin") { progress = it }
            assertEquals(201, result.status); assertEquals(file.length(), progress)
            val request = seen.get(5, TimeUnit.SECONDS)
            assertEquals("Bearer test", request.headers["authorization"])
            assertEquals(raw.length(), request.headers.getValue("content-length").toLong())
            val prefix = raw.inputStream().use { String(it.readNBytes(4096), Charsets.ISO_8859_1) }
            val disposition = prefix.indexOf("Content-Disposition: attachment;")
            assertTrue(disposition > 0)
            val offset = prefix.indexOf("\r\n\r\n", disposition) + 4
            assertEquals(digest(file), digest(raw, offset.toLong(), file.length()))
            done.get(5, TimeUnit.SECONDS)
        } finally { file.delete(); raw.delete() }
    }

    @Test fun downloadHandlesLengthAndChunkedBodies() = runBlocking<Unit> {
        for (body in listOf("Content-Length: 9\r\n\r\nWikipedia", "Transfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;x=1\r\npedia\r\n0\r\nX-Note: done\r\n\r\n")) {
            val (port, done) = response("HTTP/1.1 200 OK\r\n$body")
            val file = File.createTempFile("fd-download", ".bin")
            try {
                var progress = 0L
                val r = SocketFileHttpClient(null).download("http://127.0.0.1:$port/f", emptyMap(), file, 9) { progress = it }
                assertEquals(200, r.status); assertEquals("Wikipedia", file.readText()); assertEquals(9L, progress)
                assertEquals(0, r.body.size); done.get(5, TimeUnit.SECONDS)
            } finally { file.delete() }
        }
    }

    @Test fun shortBodyAndOversizeAreFailures() {
        for (body in listOf("Content-Length: 9\r\n\r\nshort", "Content-Length: 10\r\n\r\n0123456789",
            "Transfer-Encoding: chunked\r\n\r\na\r\n0123456789\r\n0\r\n\r\n")) {
            val (port, _) = response("HTTP/1.1 200 OK\r\n$body")
            val file = File.createTempFile("fd-partial", ".bin")
            try {
                assertThrows(HttpError::class.java) { runBlocking {
                    SocketFileHttpClient(null).download("http://127.0.0.1:$port/f", emptyMap(), file, 9) {}
                } }
                assertFalse(file.exists())
            } finally { file.delete() }
        }
    }

    @Test fun cancellationClosesSocketAndDeletesPartialFile() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val (port, done) = peer { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\na".toByteArray())
            socket.getOutputStream().flush(); started.countDown()
            assertEquals(-1, socket.getInputStream().read())
        }
        val file = File.createTempFile("fd-cancel", ".bin")
        val job = launch(Dispatchers.IO) { SocketFileHttpClient(null, timeoutMs = 20_000).download("http://127.0.0.1:$port/f", emptyMap(), file, null) {} }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        withTimeout(3000) { job.cancelAndJoin() }
        assertFalse(file.exists()); done.get(3, TimeUnit.SECONDS)
    }

    @Test fun redirectDoesNotForwardAuthorization() = runBlocking<Unit> {
        val (port, done) = response("HTTP/1.1 302 Found\r\nLocation: https://untrusted.invalid/f\r\nContent-Length: 0\r\n\r\n")
        val file = File.createTempFile("fd-redirect", ".bin")
        val result = SocketFileHttpClient(null).download("http://127.0.0.1:$port/f", mapOf("Authorization" to "Bearer test"), file, 99) {}
        assertEquals(302, result.status); assertFalse(file.exists()); done.get(3, TimeUnit.SECONDS)
    }

    @Test fun filenameCannotInjectHeaders() {
        val file = File.createTempFile("fd-name", ".bin")
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                SocketFileHttpClient(null).upload("http://127.0.0.1:9/files", emptyMap(), ByteArray(0), file, "a\r\nInjected: yes") {}
            } }
        } finally { file.delete() }
    }
}
