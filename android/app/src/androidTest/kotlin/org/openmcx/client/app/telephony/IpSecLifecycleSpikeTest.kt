//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.telephony

import android.net.IpSecManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openmcx.client.app.security.AndroidImsAkaPlatform
import org.openmcx.client.security.CardAka
import org.openmcx.client.security.EspKeys
import org.openmcx.client.sip.Connection
import org.openmcx.client.sip.SaSet
import org.openmcx.client.sip.SaSetParams
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Spike: which step of the IMS AKA set lifecycle leaves a P-CSCF SPI held in the kernel after everything was closed
 * (seen on a Moto, Android 16: the pc SPI of the set in use at the de-REGISTER). The app's own AndroidImsAkaPlatform
 * against a Linux xfrm peer standing in for the P-CSCF (internal tutorial-tools/pysim/esplifecycle.py), driven over a
 * plain TCP control connection: set A (initial registration), traffic both ways, set B on the same port_us listener
 * (reregistration), traffic, A retired, B closed and the listener released (de-REGISTER); then every P-CSCF SPI probed.
 * Throwaway keys from the peer; no card. Not a product test.
 *   -e peer 10.232.66.40 -e control 7070 -e variant <name>
 */
@RunWith(AndroidJUnit4::class)
class IpSecLifecycleSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val ipsec = context.getSystemService(IpSecManager::class.java)

    private fun report(key: String, value: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun free(peer: InetAddress, spi: Long): String =
        runCatching { ipsec.allocateSecurityParameterIndex(peer, spi.toInt()).close(); "free" }.getOrElse { "HELD" }

    private class Set(val set: SaSet, val incoming: CopyOnWriteArrayList<Connection>, val spiPc: Long, val spiPs: Long)

    @Test
    fun lifecycle() = runBlocking {
        val peerArg = args.getString("peer"); val controlPort = args.getString("control")?.toInt()
        assumeTrue("needs -e peer -e control", peerArg != null && controlPort != null)
        val variant = args.getString("variant") ?: "app"
        val peer = InetAddress.getByName(peerArg)
        val platform = AndroidImsAkaPlatform(context, CardAka { _, _ -> error("no card in the spike") })
        val local = platform.localAddress()
        val control = Socket(peer, controlPort!!)
        val toPeer = control.getOutputStream().bufferedWriter(); val fromPeer = control.getInputStream().bufferedReader()
        fun ask(line: String): String { toPeer.write(line + "\n"); toPeer.flush(); return fromPeer.readLine() ?: error("peer closed") }

        // name -> the P-CSCF's answer "spi_pc spi_ps port_pc port_ps ck ik" for the UE's "spi_uc spi_us port_uc port_us"
        suspend fun open(name: String): Set {
            val r = platform.reserve(local)
            val w = ask("SET $name ${local.hostAddress} ${r.spiUc} ${r.spiUs} ${r.portUc} 5064").split(" ")
            val pc = w[0]; val ps = w[1]; val portPc = w[2]; val portPs = w[3]; val ck = w[4]; val ik = w[5]
            val keys = EspKeys.derive("null", "aes-gcm-us", hex(ck), hex(ik))
            val p = SaSetParams(local, peer, r.portUc, 5064, portPc.toInt(), portPs.toInt(), r.spiUc, r.spiUs, pc.toLong(), ps.toLong(),
                "null", "aes-gcm-us", keys)
            val incoming = CopyOnWriteArrayList<Connection>()
            val set = platform.open(r, p) { c ->
                incoming += c
                thread(isDaemon = true) { runCatching {
                    val line = c.input.bufferedReader().readLine(); c.output.write("ue-got $line\n".toByteArray()); c.output.flush()
                } }
            }
            report("${name}_spis", "pc=$pc ps=$ps uc=${r.spiUc} us=${r.spiUs} port_uc=${r.portUc}")
            return Set(set, incoming, pc.toLong(), ps.toLong())
        }

        fun traffic(name: String, s: Set) {
            s.set.client.output.write("ue-to-ps $name\n".toByteArray()); s.set.client.output.flush()
            report("${name}_client_reply", s.set.client.input.bufferedReader().readLine() ?: "none")
            report("${name}_peer_to_us", ask("CONNECT $name"))
            Thread.sleep(500)
            report("${name}_incoming", s.incoming.size.toString())
        }

        // As ImsAkaRegistrar.closeSet: the accepted connections, the client flow, the set.
        fun closeSet(s: Set) {
            if (variant != "keep-incoming") s.incoming.forEach { runCatching { it.close() } }
            runCatching { s.set.close() }
        }

        val a = open("A"); traffic("A", a)
        val b = open("B"); traffic("B", b)
        // TS 33.203 7.4: the old set goes once its transactions ended.
        closeSet(a)
        report("after_retire_A", "pcA=${free(peer, a.spiPc)} psA=${free(peer, a.spiPs)}")
        if (variant == "reconnect-after-retire" || variant == "answer-after-dereg") {
            // The P-CSCF's connection over B was filed under A and closed with it; it connects again (as Kamailio does).
            report("B_reconnect", ask("CONNECT B"))
            Thread.sleep(500)
            report("incoming_after_reconnect", "A=${a.incoming.size} B=${b.incoming.size}")
        }
        if (variant == "peer-closes-first") ask("CLOSE")
        // De-REGISTER: the set in use closes, then the listener goes.
        closeSet(b)
        if (variant == "release-first") Unit
        platform.release()
        report("after_dereg", "pcB=${free(peer, b.spiPc)} psB=${free(peer, b.spiPs)}")
        if (variant == "answer-after-dereg") {
            // The answer to a request that came in on a connection no set closed, sent after the set's SAs are gone.
            val stray = a.incoming.lastOrNull()
            report("stray", if (stray == null) "none" else runCatching { stray.output.write("SIP/2.0 200 OK late\n".toByteArray()); stray.output.flush(); "written" }.getOrElse { "write failed ${it.message}" })
            for (t in listOf(1, 5, 35, 65, 125)) {
                Thread.sleep(if (t == 1) 1000L else (t - listOf(1, 5, 35, 65, 125)[listOf(1, 5, 35, 65, 125).indexOf(t) - 1]) * 1000L)
                report("stray_${t}s", "pcB=${free(peer, b.spiPc)} psB=${free(peer, b.spiPs)}")
            }
        }
        if (variant != "peer-closes-first") ask("CLOSE")
        Thread.sleep(3000)
        report("after_peer_close", "pcA=${free(peer, a.spiPc)} psA=${free(peer, a.spiPs)} pcB=${free(peer, b.spiPc)} psB=${free(peer, b.spiPs)}")
        ask("END")
        control.close()
        Thread.sleep(2000)
        report("final", "pcA=${free(peer, a.spiPc)} psA=${free(peer, a.spiPs)} pcB=${free(peer, b.spiPc)} psB=${free(peer, b.spiPs)}")
    }
}
