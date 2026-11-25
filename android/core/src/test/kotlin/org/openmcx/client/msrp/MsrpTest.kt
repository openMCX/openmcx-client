//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.msrp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** RFC 4975 framing and the active endpoint of RFC 6135 against a passive peer on loopback. */
class MsrpTest {
    private val local = MsrpUri("10.0.0.2", 9, "localsess")
    private val remote = MsrpUri("10.0.0.1", 2855, "serversess")

    @Test fun uriRoundTrip() {
        val uri = MsrpUri.parse("msrps://bob.example.com:8888/9di4eae923wzd;tcp")
        assertEquals(MsrpUri("bob.example.com", 8888, "9di4eae923wzd", "tcp", true), uri)
        assertEquals("msrps://bob.example.com:8888/9di4eae923wzd;tcp", uri.text())
        assertEquals(MsrpUri("[2001:db8::1]", 2855, "s"), MsrpUri.parse("msrp://[2001:db8::1]:2855/s;tcp"))
        for (bad in listOf("sip:x", "msrp://host:1/s", "msrp://:1/s;tcp", "msrp://h:99999/s;tcp"))
            runCatching { MsrpUri.parse(bad) }.onSuccess { fail("accepted $bad") }
    }

    @Test fun byteRange() {
        assertEquals(ByteRange(1, 2048, 5000), ByteRange.parse("1-2048/5000"))
        assertEquals(ByteRange(2049, null, null), ByteRange.parse("2049-*/*"))
        runCatching { ByteRange.parse("0-1/1") }.onSuccess { fail("a range starts at 1") }
    }

    @Test fun sendEncodesAsRfc4975() {
        val send = MsrpMessage("a786hjs2", method = "SEND", toPath = listOf(remote), fromPath = listOf(local), messageId = "87652491",
            byteRange = ByteRange(1, 5, 5), successReport = "yes", contentType = "text/plain", body = "Hello".toByteArray())
        assertEquals("MSRP a786hjs2 SEND\r\nTo-Path: msrp://10.0.0.1:2855/serversess;tcp\r\nFrom-Path: msrp://10.0.0.2:9/localsess;tcp\r\n" +
            "Message-ID: 87652491\r\nSuccess-Report: yes\r\nByte-Range: 1-5/5\r\nContent-Type: text/plain\r\n\r\nHello\r\n-------a786hjs2$\r\n",
            String(send.encode()))
        val response = send.response(200, "OK", remote)
        assertEquals("MSRP a786hjs2 200 OK\r\nTo-Path: msrp://10.0.0.2:9/localsess;tcp\r\nFrom-Path: msrp://10.0.0.1:2855/serversess;tcp\r\n" +
            "-------a786hjs2$\r\n", String(response.encode()))
    }

    @Test fun decoderReassemblesAcrossReadsWithBinaryBodies() {
        val body = ByteArray(3000) { (it % 256).toByte() } // includes CR, LF and '-' octets
        val messages = listOf(
            MsrpMessage("t1", method = "SEND", toPath = listOf(local), fromPath = listOf(remote), messageId = "m",
                byteRange = ByteRange(1, 3000, 6000), contentType = "application/vnd.3gpp.mcdata-file", body = body, continuation = '+'),
            MsrpMessage("t2", status = 200, comment = "OK", toPath = listOf(local), fromPath = listOf(remote)),
            MsrpMessage("t3", method = "REPORT", toPath = listOf(local), fromPath = listOf(remote), messageId = "m",
                byteRange = ByteRange(1, 6000, 6000), reportStatus = "000 200 OK"))
        val wire = messages.map { it.encode() }.reduce { a, b -> a + b }
        val decoder = MsrpDecoder()
        val out = mutableListOf<MsrpMessage>()
        var at = 0
        while (at < wire.size) { val n = minOf(97, wire.size - at); out += decoder.feed(wire.copyOfRange(at, at + n)); at += n }
        assertEquals(3, out.size)
        assertArrayEquals(body, out[0].body)
        assertEquals('+', out[0].continuation)
        assertEquals(ByteRange(1, 3000, 6000), out[0].byteRange)
        assertEquals(200, out[1].status)
        assertEquals(200, out[2].reportCode)
        assertNull(out[2].contentType)
    }

    @Test fun decoderRefusesWhatIsNotMsrp() {
        runCatching { MsrpDecoder().feed("SIP/2.0 200 OK\r\n\r\n".toByteArray()) }.onSuccess { fail("accepted SIP") }
        runCatching { MsrpDecoder(maxMessage = 100).feed(("MSRP t SEND\r\n" + "x".repeat(200)).toByteArray()) }.onSuccess { fail("no size bound") }
    }

    /** A passive endpoint (the MCData server's role): records what arrives, answers SENDs 200 unless [refuse]. */
    private class PassivePeer(val refuse: (MsrpMessage) -> Int? = { null }) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val uri = MsrpUri("127.0.0.1", server.localPort, "srv")
        val received = LinkedBlockingQueue<MsrpMessage>()
        @Volatile var socket: Socket? = null
        @Volatile var clientUri: MsrpUri? = null
        private val thread = Thread {
            val s = runCatching { server.accept() }.getOrNull() ?: return@Thread
            socket = s
            val decoder = MsrpDecoder()
            val buffer = ByteArray(8192)
            while (true) {
                val n = runCatching { s.getInputStream().read(buffer) }.getOrDefault(-1)
                if (n < 0) break
                for (m in decoder.feed(buffer, n)) {
                    received.put(m)
                    if (m.method == "SEND") {
                        if (clientUri == null) clientUri = m.fromPath.first()
                        val status = refuse(m) ?: 200
                        if (m.failureReport != "no") send(m.response(status, if (status == 200) "OK" else "Refused", uri))
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        @Synchronized fun send(m: MsrpMessage) { socket!!.getOutputStream().apply { write(m.encode()); flush() } }
        fun next(): MsrpMessage = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("nothing received")
        override fun close() { runCatching { socket?.close() }; server.close() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun session(listener: MsrpListener? = null, chunk: Int = 2048) =
        MsrpSession(scope, SocketMsrpConnector(), MsrpUri("127.0.0.1", 9, "cli"), listener, chunkSize = chunk, responseTimeoutMs = 5000)

    @Test fun activeEndpointBindsThenChunksWithByteRange() = runBlocking {
        PassivePeer().use { peer ->
            val session = session()
            session.connect(peer.uri)
            // RFC 6135 §4: the binding SEND is empty and names the session in From-Path.
            val bind = peer.next()
            assertEquals("SEND", bind.method)
            assertEquals(0, bind.body.size)
            assertEquals(listOf(peer.uri), bind.toPath)
            assertEquals("cli", bind.fromPath.first().session)
            val body = ByteArray(5000) { it.toByte() }
            val id = session.send("application/vnd.3gpp.mcdata-file", body)
            val chunks = (1..3).map { peer.next() }
            assertEquals(listOf(ByteRange(1, 2048, 5000), ByteRange(2049, 4096, 5000), ByteRange(4097, 5000, 5000)), chunks.map { it.byteRange })
            assertEquals(listOf('+', '+', '$'), chunks.map { it.continuation })
            assertTrue(chunks.all { it.messageId == id })
            assertArrayEquals(body, chunks.map { it.body }.reduce { a, b -> a + b })
            session.close()
        }
    }

    @Test fun refusedChunkAbortsTheMessage() = runBlocking {
        PassivePeer(refuse = { if (it.byteRange?.start == 2049L) 413 else null }).use { peer ->
            val session = session()
            session.connect(peer.uri)
            peer.next()
            val error = runCatching { session.send("text/plain", ByteArray(6000)) }.exceptionOrNull() as? MsrpError
            assertEquals(413, error?.status)
            peer.next(); peer.next()
            // RFC 4975 §7.1.1: the interrupted message ends with '#'.
            assertEquals('#', peer.next().continuation)
            session.close()
        }
    }

    @Test fun receivedMessagesAreReassembledAnsweredAndReported() = runBlocking {
        PassivePeer().use { peer ->
            val got = LinkedBlockingQueue<Pair<String, ByteArray>>()
            val reports = CopyOnWriteArrayList<Int>()
            val session = session(object : MsrpListener {
                override fun message(contentType: String, body: ByteArray, messageId: String) { got.put(contentType to body) }
                override fun report(messageId: String, status: Int, range: ByteRange?) { reports += status }
            })
            session.connect(peer.uri)
            peer.next()
            val client = peer.clientUri!!
            val parts = listOf(Part("application/vnd.3gpp.mcdata-signalling", byteArrayOf(1, 2)), Part("application/vnd.3gpp.mcdata-payload", ByteArray(3000) { 7 }))
            val (type, body) = Multipart.encode(parts)
            peer.send(MsrpMessage("s1", method = "SEND", toPath = listOf(client), fromPath = listOf(peer.uri), messageId = "mm",
                byteRange = ByteRange(1, 2048, body.size.toLong()), successReport = "yes", contentType = type, body = body.copyOfRange(0, 2048), continuation = '+'))
            peer.send(MsrpMessage("s2", method = "SEND", toPath = listOf(client), fromPath = listOf(peer.uri), messageId = "mm",
                byteRange = ByteRange(2049, body.size.toLong(), body.size.toLong()), successReport = "yes", contentType = type,
                body = body.copyOfRange(2048, body.size)))
            val (receivedType, receivedBody) = got.poll(5, TimeUnit.SECONDS)!!
            assertEquals(type, receivedType)
            assertEquals(parts, Multipart.parse(receivedType, receivedBody))
            assertEquals(listOf(200, 200), listOf(peer.next(), peer.next()).map { it.status })
            val report = peer.next()
            assertEquals("REPORT", report.method)
            assertEquals(200, report.reportCode)
            assertEquals(ByteRange(1, body.size.toLong(), body.size.toLong()), report.byteRange)
            // A SEND for another session is refused with 481; a REPORT on ours reaches the listener unanswered.
            peer.send(MsrpMessage("s3", method = "SEND", toPath = listOf(MsrpUri("127.0.0.1", 9, "other")), fromPath = listOf(peer.uri),
                messageId = "x", contentType = "text/plain", body = "x".toByteArray()))
            assertEquals(481, peer.next().status)
            peer.send(MsrpMessage("r1", method = "REPORT", toPath = listOf(client), fromPath = listOf(peer.uri), messageId = "y",
                reportStatus = "000 200 OK"))
            val end = System.currentTimeMillis() + 3000
            while (reports.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
            assertEquals(listOf(200), reports)
            session.close()
        }
    }

    @Test fun peerClosingEndsTheSession() = runBlocking {
        PassivePeer().use { peer ->
            val session = session()
            session.connect(peer.uri)
            peer.next()
            peer.socket!!.close()
            val cause = kotlinx.coroutines.withTimeout(5000) { session.closed.await() }
            assertTrue(cause is MsrpError)
            assertTrue(!session.isConnected)
        }
    }
}
