package org.openmcx.client.sip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.http.Endpoints
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** A connected byte stream to the SIP core. */
interface Connection : Closeable {
    val input: InputStream
    val output: OutputStream
    /** The local address and port of the connection, for Via and Contact. */
    val localHost: String
    val localPort: Int
    val transport: String
}

/** Opens connections to the SIP core. */
fun interface Connector {
    suspend fun connect(host: String, port: Int): Connection
}

/**
 * Opens TCP or TLS connections with plain sockets. With TLS, [ssl] carries the client
 * certificate (mutual TLS) and the trust anchors, and the server name is checked. [endpoints]
 * may send the connection to a provisioned address; TLS still uses the name.
 */
class SocketConnector(
    private val transport: String,
    private val ssl: SSLContext? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val endpoints: Endpoints = Endpoints.DIRECT,
) : Connector {
    override suspend fun connect(host: String, port: Int): Connection = withContext(Dispatchers.IO) {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.keepAlive = true
        val (address, targetPort) = endpoints.resolve(host, port)
        plain.connect(InetSocketAddress(address, targetPort), connectTimeoutMs)
        val socket = if (transport.equals("TLS", true)) {
            val context = ssl ?: throw IllegalStateException("TLS without an SSL context")
            (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).apply {
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                startHandshake()
            }
        } else plain
        object : Connection {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override val localHost: String = plain.localAddress.hostAddress
            override val localPort: Int = plain.localPort
            override val transport: String = this@SocketConnector.transport.uppercase()
            override fun close() = socket.close()
        }
    }
}

/**
 * One outbound flow to the SIP core (RFC 5626): every request and response to and from
 * this client travels on it. It sends double-CRLF keepalives and fails the flow when no
 * pong comes back within [pongTimeoutMs] (RFC 5626 §4.4.1).
 */
class SipFlow(
    private val scope: CoroutineScope,
    val connection: Connection,
    private val onMessage: suspend (SipMessage) -> Unit,
    private val trace: (String, SipMessage) -> Unit = { _, _ -> },
    private val keepaliveMs: Long = 25_000,
    private val pongTimeoutMs: Long = 10_000,
) {
    private val writeLock = Mutex()
    private var pong: CompletableDeferred<Unit>? = null
    private var reader: Job? = null
    private var keepalive: Job? = null

    /** Completes when the flow ends, with the cause (null when closed locally). */
    val closed = CompletableDeferred<Throwable?>()

    /** True once a pong has been seen: the core supports RFC 5626 keepalives. */
    @Volatile var pongSeen = false
        private set

    fun start() {
        reader = scope.launch(Dispatchers.IO) { read() }
        if (keepaliveMs > 0) keepalive = scope.launch { keepalives() }
    }

    suspend fun send(message: SipMessage) {
        trace("send", message)
        write(message.encode())
    }

    private suspend fun write(data: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            connection.output.write(data)
            connection.output.flush()
        }
    }

    private suspend fun read() {
        val framer = StreamFramer()
        val buffer = ByteArray(16384)
        val cause = try {
            while (true) {
                val n = connection.input.read(buffer)
                if (n < 0) break
                for (item in framer.feed(buffer, n)) when (item) {
                    is Framed.Keepalive -> if (item.ping) write(PONG) else { pongSeen = true; pong?.complete(Unit) }
                    is Framed.Message -> { trace("recv", item.message); onMessage(item.message) }
                }
            }
            FlowClosed("connection closed by the peer")
        } catch (e: Exception) {
            e
        }
        close(cause)
    }

    private suspend fun keepalives() {
        while (scope.isActive && !closed.isCompleted) {
            delay(keepaliveMs)
            if (!probe()) return
        }
    }

    private val pingLock = Mutex()

    /**
     * One keepalive now (a CRLFCRLF ping, RFC 5626 §4.4.1): true when the pong comes back.
     * Without one the flow is closed, so the client reconnects. Used by the timer and by a
     * platform wake-up (an alarm in Doze, when the timer does not run).
     */
    suspend fun probe(): Boolean = pingLock.withLock {
        if (closed.isCompleted) return false
        val waiting = CompletableDeferred<Unit>().also { pong = it }
        try { write(PING) } catch (e: Exception) { close(e); return false }
        if (withTimeoutOrNull(pongTimeoutMs) { waiting.await() } == null) {
            close(FlowClosed("no keepalive pong within $pongTimeoutMs ms"))
            return false
        }
        true
    }

    fun close(cause: Throwable? = null) {
        if (closed.complete(cause)) {
            keepalive?.cancel()
            runCatching { connection.close() }
        }
    }

    class FlowClosed(message: String) : Exception(message)

    private companion object {
        val PING = "\r\n\r\n".toByteArray()
        val PONG = "\r\n".toByteArray()
    }
}
