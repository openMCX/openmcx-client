//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.telephony

import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Spike: when is a P-CSCF SPI (reserved with allocateSecurityParameterIndex for the P-CSCF's address) free again for
 * the next set? The P-CSCF numbers its SPIs from a fixed start and reuses them, so a reservation that outlives its set
 * refuses a later set (SpiUnavailableException). No traffic: the address is only the SAs' destination. Results as
 * instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class IpSecSpiReuseSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ipsec = instrumentation.targetContext.getSystemService(IpSecManager::class.java)
    private val peer: InetAddress = InetAddress.getByName("192.0.2.40")
    private val local: InetAddress = InetAddress.getByName("127.0.0.1")
    private val random = SecureRandom()

    private fun report(key: String, value: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })

    private fun gcm(source: InetAddress, spi: IpSecManager.SecurityParameterIndex): IpSecTransform =
        IpSecTransform.Builder(instrumentation.targetContext)
            .setAuthenticatedEncryption(IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, ByteArray(20).also(random::nextBytes), 128))
            .buildTransportModeTransform(source, spi)

    private fun again(spi: Int): String = runCatching { ipsec.allocateSecurityParameterIndex(peer, spi).close(); "free" }
        .getOrElse { "held: ${it.javaClass.simpleName}" }

    @Test
    fun reuse() {
        // A: reserved, closed.
        ipsec.allocateSecurityParameterIndex(peer, 9100).close()
        report("A_spi_closed", again(9100))
        // B: as a set closes: transform, then the SPI.
        ipsec.allocateSecurityParameterIndex(peer, 9101).let { s -> gcm(local, s).close(); s.close() }
        report("B_transform_then_spi_closed", again(9101))
        // C: transform applied to a socket; socket closed, transform and SPI closed.
        ipsec.allocateSecurityParameterIndex(peer, 9102).let { s ->
            val t = gcm(local, s); val socket = Socket()
            ipsec.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, t)
            socket.close(); t.close(); s.close()
        }
        report("C_socket_transform_spi_closed", again(9102))
        // D: SPI closed, the transform not (a set left open).
        val t = ipsec.allocateSecurityParameterIndex(peer, 9103).let { s -> gcm(local, s).also { s.close() } }
        report("D_transform_open", again(9103))
        t.close()
        report("D_after_transform_close", again(9103))
        // E: transform not closed, its objects dropped and collected (CloseGuard: "A resource failed to call close").
        ipsec.allocateSecurityParameterIndex(peer, 9104).let { s -> gcm(local, s) }
        report("E_dropped_before_gc", again(9104))
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(500) }
        report("E_dropped_after_gc", again(9104))
        // F: the SPI only reserved, dropped without close.
        ipsec.allocateSecurityParameterIndex(peer, 9105)
        report("F_spi_dropped_before_gc", again(9105))
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(500) }
        report("F_spi_dropped_after_gc", again(9105))
    }

    /** G, first process: a transform left open when the process ends (run [afterProcess] in a new process). */
    @Test
    fun leaveOpen() {
        val s = ipsec.allocateSecurityParameterIndex(peer, 9200)
        held += gcm(local, s)
        report("G_held_in_process", again(9200))
    }

    /** G, second process: is the SPI of the ended process free? */
    @Test
    fun afterProcess() = report("G_after_process_end", again(9200))

    /** Probe: which SPIs are free for a given peer now (arguments "peer", "spis" comma-separated). */
    @Test
    fun probe() {
        val args = InstrumentationRegistry.getArguments()
        val to = InetAddress.getByName(args.getString("peer") ?: return)
        for (spi in (args.getString("spis") ?: return).split(",").map { it.trim().toInt() }) {
            report("probe_$spi", runCatching { ipsec.allocateSecurityParameterIndex(to, spi).close(); "free" }
                .getOrElse { "held: ${it.javaClass.simpleName}" })
        }
    }

    /**
     * H: as the set in use closed at the de-REGISTER: the transform closed while still applied to an open socket (the
     * port_us listener), the socket closed after. I: the transforms removed from the socket first, then closed.
     */
    @Test
    fun closeWhileApplied() {
        ipsec.allocateSecurityParameterIndex(peer, 9300).let { s ->
            val t = gcm(local, s); val fd = android.system.Os.socket(android.system.OsConstants.AF_INET, android.system.OsConstants.SOCK_STREAM, 0)
            ipsec.applyTransportModeTransform(fd, IpSecManager.DIRECTION_OUT, t)
            t.close(); s.close()
            report("H_closed_while_applied", again(9300))
            android.system.Os.close(fd)
            report("H_after_socket_close", again(9300))
        }
        ipsec.allocateSecurityParameterIndex(peer, 9301).let { s ->
            val t = gcm(local, s); val fd = android.system.Os.socket(android.system.OsConstants.AF_INET, android.system.OsConstants.SOCK_STREAM, 0)
            ipsec.applyTransportModeTransform(fd, IpSecManager.DIRECTION_OUT, t)
            ipsec.removeTransportModeTransforms(fd); t.close(); s.close()
            report("I_removed_then_closed", again(9301))
            android.system.Os.close(fd)
        }
    }

    companion object { val held = mutableListOf<IpSecTransform>() }
}
