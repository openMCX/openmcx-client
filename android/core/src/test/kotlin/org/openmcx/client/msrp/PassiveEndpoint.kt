//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.msrp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A passive endpoint (the MCData server's role): records what arrives, answers SENDs 200 unless [refuse]. */
internal class PassiveEndpoint(val refuse: (MsrpMessage) -> Int? = { null }) : AutoCloseable {
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
