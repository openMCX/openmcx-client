//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.sip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/** SipFlow.probe: the keepalive a Doze alarm runs (RFC 5626 §4.4.1). */
class FlowProbeTest {
    /** A connection whose far end is a thread: it reads the ping and, if [answer], sends the pong. */
    private fun peer(answer: Boolean): Connection {
        val toPeer = PipedOutputStream()
        val peerIn = PipedInputStream(toPeer)
        val fromPeer = PipedOutputStream()
        val flowIn = PipedInputStream(fromPeer)
        thread(isDaemon = true) {
            val ping = ByteArray(4)
            var n = 0
            while (n < 4) n += peerIn.read(ping, n, 4 - n).also { if (it < 0) return@thread }
            if (answer) { fromPeer.write("\r\n".toByteArray()); fromPeer.flush() }
            // Stay alive until the flow closes: a piped stream whose writer thread has ended reads as broken.
            runCatching { while (peerIn.read() >= 0) Unit }
        }
        return object : Connection {
            override val input = flowIn
            override val output = toPeer
            override val localHost = "ue.invalid"
            override val localPort = 5061
            override val transport = "TLS"
            override fun close() { runCatching { toPeer.close() }; runCatching { fromPeer.close() } }
        }
    }

    @Test
    fun probeSucceedsWhenThePongComesBack() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val flow = SipFlow(scope, peer(answer = true), { }, keepaliveMs = 0, pongTimeoutMs = 2_000).also { it.start() }
        assertTrue(flow.probe())
        assertFalse(flow.closed.isCompleted)
        scope.cancel()
    }

    @Test
    fun probeClosesTheFlowWithoutAPong() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val flow = SipFlow(scope, peer(answer = false), { }, keepaliveMs = 0, pongTimeoutMs = 300).also { it.start() }
        assertFalse(flow.probe())
        assertTrue(flow.closed.isCompleted)
        assertFalse(flow.probe())
        scope.cancel()
    }
}
