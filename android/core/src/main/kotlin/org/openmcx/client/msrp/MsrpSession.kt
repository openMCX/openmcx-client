//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.msrp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.http.Endpoints
import org.openmcx.client.security.TlsProfile
import org.openmcx.client.sip.Connection
import org.openmcx.client.sip.tlsVersion
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

// The MSRP endpoint of the MCData client: always the active one (RFC 6135), since the client never listens.
//
// Specifications: RFC 4975 §5.1 (chunking), §7.1 (SEND, Success-Report, Failure-Report), §7.2 (responses), §7.1.2 and
// §9 (REPORT); RFC 6135 §4 (the active endpoint binds the connection with an empty SEND); TS 24.582 §6.1.1.2.1, §7.1.2.1.

/** Opens the TCP or TLS connection of an MSRP session (`msrps`, RFC 4975 §14) to the passive endpoint's URI. */
fun interface MsrpConnector {
    suspend fun connect(peer: MsrpUri): Connection
}

/**
 * [MsrpConnector] on plain sockets. TLS uses [ssl] (the client certificate and trust anchors of the SIP flow) and checks
 * the peer's name when the URI carries one; [endpoints] may map a name to a provisioned address, as for SIP.
 */
class SocketMsrpConnector(
    private val ssl: SSLContext? = null,
    private val endpoints: Endpoints = Endpoints.DIRECT,
    private val connectTimeoutMs: Int = 10_000,
) : MsrpConnector {
    override suspend fun connect(peer: MsrpUri): Connection = withContext(Dispatchers.IO) {
        val plain = Socket()
        plain.tcpNoDelay = true
        val (address, port) = endpoints.resolve(peer.host, peer.port)
        plain.connect(InetSocketAddress(address, port), connectTimeoutMs)
        val socket = if (peer.secure) {
            val context = ssl ?: throw MsrpError("an msrps peer needs TLS")
            (context.socketFactory.createSocket(plain, peer.host, peer.port, true) as SSLSocket).apply {
                if (!peer.host.first().isDigit() && !peer.host.startsWith("[")) // RFC 4975 §14.2: the name in the URI
                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                TlsProfile.apply(this)
                startHandshake()
            }
        } else plain
        object : Connection {
            override val input = socket.getInputStream()
            override val output = socket.getOutputStream()
            override val localHost: String = plain.localAddress.hostAddress
            override val localPort = plain.localPort
            override val transport = if (peer.secure) "TLS" else "TCP"
            override val security: String? = (socket as? SSLSocket)?.session?.protocol?.let(::tlsVersion)
            override fun close() = socket.close()
        }
    }
}

/** What an [MsrpSession] reports: a complete received message, a REPORT on one of ours, the end of the connection. */
interface MsrpListener {
    fun message(contentType: String, body: ByteArray, messageId: String) {}
    fun report(messageId: String, status: Int, range: ByteRange?) {}
    fun closed(cause: Throwable?) {}
}

/**
 * One MSRP session over one connection, this client the active endpoint (RFC 6135): [connect] opens the connection to
 * the passive peer's URI and binds it with an empty SEND, answered 200 (TS 24.582 §6.1.1.2.1 steps 1-4); [send] sends a
 * message in SEND chunks of at most [chunkSize] octets with Byte-Range (RFC 4975 §5.1, §7.1.1) and returns once every
 * chunk is answered 200, failing (and aborting the rest, `#`) on another status. Received SENDs are answered (unless
 * Failure-Report "no", or "partial" for a 200: §7.1.2), reassembled by Message-ID and Byte-Range, and reported with a
 * success REPORT when they ask for one (§7.1.2: Success-Report "yes").
 */
class MsrpSession(
    private val scope: CoroutineScope,
    private val connector: MsrpConnector,
    /** The local URI: its session-id names this session in the peer's To-Path. */
    val local: MsrpUri,
    var listener: MsrpListener? = null,
    private val chunkSize: Int = 2048,
    private val responseTimeoutMs: Long = 30_000,
    private val maxMessage: Int = 8 * 1024 * 1024,
    private val trace: (String) -> Unit = {},
) {
    private var connection: Connection? = null
    @Volatile var peer: MsrpUri? = null
        private set
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<MsrpMessage>>()
    private val partial = ConcurrentHashMap<String, Incoming>()
    private var reader: Job? = null
    @Volatile var isConnected = false
        private set
    /** Completes when the connection ends (null when closed locally). */
    val closed = CompletableDeferred<Throwable?>()
    /** The protection of the connection, e.g. "TLS 1.3"; null for TCP. */
    val security: String? get() = connection?.security

    private class Incoming(val type: String, val total: Long?) {
        val data = ByteArrayOutputStream()
        var buffer: ByteArray? = total?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.let { ByteArray(it.toInt()) }
        var received = 0L
    }

    /** RFC 6135 §4 with TS 24.582 §6.1.1.2.1 steps 1-4: connect, bind with an empty SEND and wait for its 200. */
    suspend fun connect(peer: MsrpUri) {
        this.peer = peer
        val c = connector.connect(peer)
        connection = c
        isConnected = true
        reader = scope.launch(Dispatchers.IO) { read(c) }
        // The binding SEND names this session in From-Path; it carries no body and, answered, no further report.
        val bind = MsrpMessage(MsrpMessage.newIdent(), method = "SEND", toPath = listOf(peer), fromPath = listOf(local),
            messageId = MsrpMessage.newIdent())
        val answer = transact(bind)
        if (answer.status != 200) throw MsrpError("the binding SEND was answered ${answer.status} ${answer.comment ?: ""}", answer.status)
    }

    /**
     * RFC 4975 §5.1 and §7.1.1: send [body] as one message of [contentType] in chunks; [successReport] asks the peer for
     * a success REPORT (§7.1.2). Returns the Message-ID.
     */
    suspend fun send(contentType: String, body: ByteArray, successReport: Boolean = false, messageId: String = MsrpMessage.newIdent()): String {
        val peer = peer ?: throw MsrpError("the MSRP session is not connected")
        val total = body.size.toLong()
        var at = 0
        do {
            val end = minOf(at + chunkSize, body.size)
            val last = end == body.size
            val chunk = MsrpMessage(MsrpMessage.newIdent(), method = "SEND", toPath = listOf(peer), fromPath = listOf(local),
                messageId = messageId, byteRange = ByteRange(at + 1L, end.toLong(), total),
                successReport = if (successReport) "yes" else null, contentType = contentType,
                body = body.copyOfRange(at, end), continuation = if (last) '$' else '+')
            val answer = transact(chunk)
            if (answer.status != 200) {
                // RFC 4975 §7.1.1: the rest of the message is not sent; an interrupted message ends with '#'.
                if (!last) runCatching { write(MsrpMessage(MsrpMessage.newIdent(), method = "SEND", toPath = listOf(peer),
                    fromPath = listOf(local), messageId = messageId, byteRange = ByteRange(end + 1L, end.toLong(), total),
                    continuation = '#').encode()) }
                throw MsrpError("SEND answered ${answer.status} ${answer.comment ?: ""}".trim(), answer.status)
            }
            at = end
        } while (at < body.size)
        return messageId
    }

    /** TS 24.582 §6.4.1: several MCData bodies in one multipart/mixed message body (RFC 2046). */
    suspend fun sendParts(parts: List<org.openmcx.client.body.Part>, successReport: Boolean = false): String {
        if (parts.size == 1) return send(parts[0].contentType, parts[0].content, successReport)
        val (type, body) = org.openmcx.client.body.Multipart.encode(parts)
        return send(type, body, successReport)
    }

    private suspend fun transact(request: MsrpMessage): MsrpMessage {
        val answer = CompletableDeferred<MsrpMessage>()
        pending[request.transaction] = answer
        try {
            write(request.encode())
            trace("send $request")
            return withTimeoutOrNull(responseTimeoutMs) { answer.await() }
                ?: throw MsrpError("no response to ${request.method} ${request.transaction}", 408)
        } finally {
            pending.remove(request.transaction)
        }
    }

    private suspend fun write(bytes: ByteArray) = writeLock.withLock {
        val c = connection ?: throw MsrpError("the MSRP session is not connected")
        withContext(Dispatchers.IO) { c.output.write(bytes); c.output.flush() }
    }

    private suspend fun read(c: Connection) {
        val decoder = MsrpDecoder(maxMessage)
        val buffer = ByteArray(16 * 1024)
        var cause: Throwable? = null
        try {
            while (true) {
                val n = c.input.read(buffer)
                if (n < 0) { cause = MsrpError("MSRP connection closed by the peer"); break }
                for (message in decoder.feed(buffer, n)) received(message)
            }
        } catch (e: Throwable) {
            cause = if (isConnected) e else null
        }
        isConnected = false
        pending.values.forEach { it.completeExceptionally(cause ?: MsrpError("MSRP session closed")) }
        runCatching { c.close() }
        if (closed.complete(cause)) listener?.closed(cause)
    }

    private suspend fun received(message: MsrpMessage) {
        trace("recv $message")
        if (!message.isRequest) { pending[message.transaction]?.complete(message); return }
        when (message.method) {
            // RFC 4975 §7.1.2: a REPORT is never answered.
            "REPORT" -> message.messageId?.let { listener?.report(it, message.reportCode ?: 0, message.byteRange) }
            "SEND" -> receivedSend(message)
            // RFC 4975 §12: an unknown method is answered 501.
            else -> answer(message, 501, "Not Implemented")
        }
    }

    private suspend fun answer(request: MsrpMessage, status: Int, comment: String) {
        val failure = request.failureReport?.lowercase()
        if (failure == "no" || (failure == "partial" && status == 200)) return
        runCatching { write(request.response(status, comment, local).encode()) }
    }

    private suspend fun receivedSend(message: MsrpMessage) {
        // RFC 4975 §7.3.1: a request for another session is answered 481.
        if (message.toPath.firstOrNull()?.sameSession(local) == false) { answer(message, 481, "Session Does Not Exist"); return }
        val id = message.messageId ?: run { answer(message, 400, "Bad Request"); return }
        if (message.aborted) { partial.remove(id); answer(message, 200, "OK"); return }
        // RFC 6135: an empty SEND (a binding) delivers nothing.
        if (message.body.isEmpty() && message.complete && !partial.containsKey(id)) { answer(message, 200, "OK"); return }
        val range = message.byteRange
        val incoming = partial.getOrPut(id) { Incoming(message.contentType ?: "application/octet-stream", range?.total) }
        if ((range?.total ?: incoming.received) > maxMessage || incoming.received + message.body.size > maxMessage) {
            partial.remove(id); answer(message, 413, "Message Too Large"); return
        }
        val buf = incoming.buffer
        val start = (range?.start ?: (incoming.received + 1)) - 1
        if (buf != null && start + message.body.size <= buf.size) System.arraycopy(message.body, 0, buf, start.toInt(), message.body.size)
        else incoming.data.write(message.body)
        incoming.received += message.body.size
        answer(message, 200, "OK")
        if (!message.complete) return
        partial.remove(id)
        val body = incoming.buffer?.takeIf { incoming.received == it.size.toLong() } ?: incoming.data.toByteArray()
        // RFC 4975 §7.1.2: a success REPORT for the whole message, Status 000 200.
        if (message.successReport?.lowercase() == "yes") runCatching {
            write(MsrpMessage(MsrpMessage.newIdent(), method = "REPORT", toPath = listOf(message.fromPath.first()), fromPath = listOf(local),
                messageId = id, byteRange = ByteRange(1, body.size.toLong(), body.size.toLong()), reportStatus = "000 200 OK").encode())
        }
        listener?.message(incoming.type, body, id)
    }

    /** Close the connection; the session ends. */
    fun close() {
        isConnected = false
        runCatching { connection?.close() }
        reader?.cancel()
        if (closed.complete(null)) listener?.closed(null)
    }
}
