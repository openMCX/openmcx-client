//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.telephony

import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.net.IpSecTransformState
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Spike for the IMS AKA security associations on Android (TS 33.203 7.1: transport-mode ESP SAs between the UE's
 * protected ports and the P-CSCF's): which integrity/encryption pairs IpSecManager builds, and whether a TCP socket
 * accepted from a listening socket with transport-mode transforms sends through the SA (the P-CSCF connects to the UE's
 * protected server port). Loopback only, own SPIs and random keys; no network, no card. Results as instrumentation
 * status. Not a product test: kept with the IMS AKA work while the Android SAs are designed.
 */
@RunWith(AndroidJUnit4::class)
class IpSecTransportSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ipsec = instrumentation.targetContext.getSystemService(IpSecManager::class.java)
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val random = SecureRandom()

    private fun key(bits: Int) = ByteArray(bits / 8).also { random.nextBytes(it) }
    private fun report(vararg kv: Pair<String, String>) =
        instrumentation.sendStatus(0, Bundle().apply { kv.forEach { putString(it.first, it.second) } })

    /** Integrity / encryption pairs named after the P-CSCF's offer, built as a transport-mode transform. */
    private val pairs: Map<String, (IpSecTransform.Builder) -> Unit> = mapOf(
        "hmac-sha-1-96+aes-cbc" to { b -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA1, key(160), 96))
            .setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, key(128))) },
        "hmac-sha2-256-128+aes-cbc" to { b -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, key(256), 128))
            .setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, key(128))) },
        "hmac-sha2-256-128+null" to { b -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, key(256), 128)) },
        // RFC 4106: key + 4-octet salt, ICV 128 bits
        "aes-gcm-128" to { b -> b.setAuthenticatedEncryption(IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, key(160), 128)) },
        "aes-gcm-256" to { b -> b.setAuthenticatedEncryption(IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, key(288), 128)) },
        // RFC 4543 AES-GMAC: integrity only, key + 4-octet salt
        "aes-gmac-128" to { b -> b.setAuthentication(IpSecAlgorithm("rfc4543(gcm(aes))", key(160), 128)) },
    )

    private fun transform(pair: String, spi: IpSecManager.SecurityParameterIndex): IpSecTransform =
        IpSecTransform.Builder(instrumentation.targetContext).also { pairs.getValue(pair)(it) }
            .buildTransportModeTransform(loopback, spi)

    @Test
    fun algorithms() {
        report("supported" to IpSecAlgorithm.getSupportedAlgorithms().sorted().joinToString(","), "sdk" to Build.VERSION.SDK_INT.toString())
        for (name in pairs.keys) {
            val outcome = runCatching {
                ipsec.allocateSecurityParameterIndex(loopback).use { spi -> transform(name, spi).close() }
            }
            report("pair_$name" to (outcome.exceptionOrNull()?.let { "refused: ${it.javaClass.simpleName}: ${it.message}" } ?: "built"))
        }
    }

    private fun packets(t: IpSecTransform): Long {
        val f = CompletableFuture<IpSecTransformState>()
        t.requestIpSecTransformState(Runnable::run, object : OutcomeReceiver<IpSecTransformState, RuntimeException> {
            override fun onResult(result: IpSecTransformState) { f.complete(result) }
            override fun onError(error: RuntimeException) { f.completeExceptionally(error) }
        })
        return f.get(5, TimeUnit.SECONDS).packetCount
    }

    /**
     * Loopback TCP: the client socket and the listening socket each carry the transform pair (out with one SA,
     * in with the other). After the handshake and the client's request, the server writes only from the accepted
     * socket: the server-to-client SA's packet count rises only if the accepted socket sends through it.
     */
    @Test
    fun acceptedSocketUsesTheTransform() {
        assumeTrue("IpSecTransform state needs API 35", Build.VERSION.SDK_INT >= 35)
        val pair = "hmac-sha2-256-128+aes-cbc"
        val spiCs = ipsec.allocateSecurityParameterIndex(loopback)
        val spiSc = ipsec.allocateSecurityParameterIndex(loopback)
        val cs = transform(pair, spiCs) // client -> server
        val sc = transform(pair, spiSc) // server -> client
        val listener = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            Os.bind(listener, loopback, 0)
            Os.listen(listener, 1)
            val port = (Os.getsockname(listener) as InetSocketAddress).port
            ipsec.applyTransportModeTransform(listener, IpSecManager.DIRECTION_OUT, sc)
            ipsec.applyTransportModeTransform(listener, IpSecManager.DIRECTION_IN, cs)
            Socket().use { client ->
                ipsec.applyTransportModeTransform(client, IpSecManager.DIRECTION_OUT, cs)
                ipsec.applyTransportModeTransform(client, IpSecManager.DIRECTION_IN, sc)
                client.connect(InetSocketAddress(loopback, port), 5000)
                client.soTimeout = 5000
                val accepted = Os.accept(listener, null)
                try {
                    client.getOutputStream().write("ping".toByteArray())
                    val buf = ByteArray(4)
                    Os.read(accepted, buf, 0, 4)
                    Thread.sleep(300) // the ACKs of the request settle before the count
                    val before = packets(sc)
                    Os.write(accepted, "pong".toByteArray(), 0, 4)
                    val got = ByteArray(4).also { var n = 0; while (n < 4) n += client.getInputStream().read(it, n, 4 - n) }
                    Thread.sleep(300)
                    val after = packets(sc)
                    report("pair" to pair, "client_to_server_packets" to packets(cs).toString(),
                        "server_to_client_before_pong" to before.toString(), "server_to_client_after_pong" to after.toString(),
                        "accepted_socket_uses_sa" to (after > before).toString(), "pong" to String(got))
                    assertEquals("pong", String(got))
                } finally {
                    ipsec.removeTransportModeTransforms(accepted)
                    Os.close(accepted)
                }
                ipsec.removeTransportModeTransforms(client)
            }
        } finally {
            runCatching { ipsec.removeTransportModeTransforms(listener) }
            Os.close(listener)
            cs.close(); sc.close(); spiCs.close(); spiSc.close()
        }
    }
}
