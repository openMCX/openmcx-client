//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.telephony

import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.net.IpSecTransformState
import android.os.Bundle
import android.os.OutcomeReceiver
import android.system.Os
import android.system.OsConstants
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * P0b spike: the UE end of the four IMS AKA SAs (TS 33.203 7.1) against a Linux xfrm peer standing in for the P-CSCF.
 * CK/IK from the card (getIccAuthentication, ISIM, IMS AKA context) for the caller's RAND/AUTN, ESP keys by TS 33.203
 * annex I, SPIs and ports fixed as in the peer script (internal tutorial-tools/pysim/esppeer.py), then TCP both ways:
 * the UE's protected client port to the peer's protected server port, and the peer's protected client port to the
 * UE's protected server port. Not a product test.
 *   -e rand R -e autn A -e pair hmac-sha2-256-128/aes-cbc -e peer 10.232.66.40
 */
@RunWith(AndroidJUnit4::class)
class IpSecInteropSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val ipsec = context.getSystemService(IpSecManager::class.java)

    private fun report(vararg kv: Pair<String, String>) =
        instrumentation.sendStatus(0, Bundle().apply { kv.forEach { putString(it.first, it.second) } })

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** TS 33.220 annex B: HMAC-SHA-256(Key, FC || P0 || L0). */
    private fun kdf(key: ByteArray, fc: Int, p0: String): ByteArray {
        val p = p0.toByteArray()
        val s = byteArrayOf(fc.toByte()) + p + byteArrayOf((p.size shr 8).toByte(), p.size.toByte())
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(s)
    }

    private fun packets(t: IpSecTransform): Long {
        val f = CompletableFuture<IpSecTransformState>()
        t.requestIpSecTransformState(Runnable::run, object : OutcomeReceiver<IpSecTransformState, RuntimeException> {
            override fun onResult(result: IpSecTransformState) { f.complete(result) }
            override fun onError(error: RuntimeException) { f.completeExceptionally(error) }
        })
        return f.get(5, TimeUnit.SECONDS).packetCount
    }

    @Test
    fun tcpBothWaysThroughTheSas() {
        val rand = args.getString("rand"); val autn = args.getString("autn")
        val pair = args.getString("pair"); val peerArg = args.getString("peer")
        assumeTrue("needs -e rand -e autn -e pair -e peer", rand != null && autn != null && pair != null && peerArg != null)
        val (alg, ealg) = pair!!.split("/")
        val peer = InetAddress.getByName(peerArg)
        val ue: InetAddress = NetworkInterface.getByName("wlan0").inetAddresses.toList().first { it is Inet4Address }

        // The card: RES, CK, IK (TS 31.102 7.1.2.1 response: DB, L RES, L CK, L IK)
        val tm = context.getSystemService(TelephonyManager::class.java)
        val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        val card = subs.map { tm.createForSubscriptionId(it.subscriptionId) }.first { it.hasCarrierPrivileges() }
        val data = byteArrayOf(16) + hex(rand!!) + byteArrayOf(16) + hex(autn!!)
        val r = Base64.decode(card.getIccAuthentication(TelephonyManager.APPTYPE_ISIM, TelephonyManager.AUTHTYPE_EAP_AKA,
            Base64.encodeToString(data, Base64.NO_WRAP)), Base64.DEFAULT)
        assertEquals("card answer tag", 0xDB, r[0].toInt() and 0xFF)
        var i = 1
        val res = r.copyOfRange(i + 1, i + 1 + r[i]); i += 1 + r[i]
        val ck = r.copyOfRange(i + 1, i + 1 + r[i]); i += 1 + r[i]
        val ik = r.copyOfRange(i + 1, i + 1 + r[i])
        report("res_len" to res.size.toString())

        // TS 33.203 annex I
        val ikEsp = when (alg) { "hmac-sha-1-96" -> ik + ByteArray(4); "hmac-sha2-256-128" -> kdf(ck + ik, 0x5A, "HMAC-SHA2-256-128"); else -> ByteArray(0) }
        val gcm = ealg == "aes-gcm" || ealg == "aes-gcm-us"
        val salt = if (gcm) kdf(ck + ik, 0x59, "AES_GCM_SALT").copyOfRange(28, 32) else ByteArray(0)
        fun saSalt(toUe: Boolean, fromServer: Boolean) = if (ealg != "aes-gcm-us") salt
            else salt.copyOf().also { it[3] = (it[3].toInt() xor ((if (toUe) 1 else 0) or (if (fromServer) 2 else 0))).toByte() }

        fun transform(source: InetAddress, spi: IpSecManager.SecurityParameterIndex, toUe: Boolean, fromServer: Boolean): IpSecTransform {
            val b = IpSecTransform.Builder(context)
            if (gcm) b.setAuthenticatedEncryption(IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, ck + saSalt(toUe, fromServer), 128))
            else {
                when (alg) {
                    "hmac-sha-1-96" -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA1, ikEsp, 96))
                    "hmac-sha2-256-128" -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, ikEsp, 128))
                }
                if (ealg == "aes-cbc") b.setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, ck))
            }
            return b.buildTransportModeTransform(source, spi)
        }

        // SPIs and ports as esppeer.py; inbound SPIs are reserved for the UE's address, outbound for the peer's
        val spiUc = ipsec.allocateSecurityParameterIndex(ue, 0x1000A001)
        val spiUs = ipsec.allocateSecurityParameterIndex(ue, 0x1000A002)
        val spiPc = ipsec.allocateSecurityParameterIndex(peer, 0x2000B001)
        val spiPs = ipsec.allocateSecurityParameterIndex(peer, 0x2000B002)
        val ueToPs = transform(ue, spiPs, toUe = false, fromServer = false)   // port_uc -> port_ps
        val psToUe = transform(peer, spiUc, toUe = true, fromServer = true)   // port_ps -> port_uc
        val pcToUs = transform(peer, spiUs, toUe = true, fromServer = false)  // port_pc -> port_us
        val usToPc = transform(ue, spiPc, toUe = false, fromServer = true)    // port_us -> port_pc
        report("pair" to pair, "ue" to ue.hostAddress!!, "peer" to peer.hostAddress!!, "transforms" to "built")

        val listener = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            Os.setsockoptInt(listener, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
            ipsec.applyTransportModeTransform(listener, IpSecManager.DIRECTION_IN, pcToUs)
            ipsec.applyTransportModeTransform(listener, IpSecManager.DIRECTION_OUT, usToPc)
            Os.bind(listener, ue, 5102)
            Os.listen(listener, 1)
            // UE client port -> peer server port
            val clientReply = Socket().use { s ->
                s.reuseAddress = true
                ipsec.applyTransportModeTransform(s, IpSecManager.DIRECTION_OUT, ueToPs)
                ipsec.applyTransportModeTransform(s, IpSecManager.DIRECTION_IN, psToUe)
                s.bind(InetSocketAddress(ue, 5101))
                s.connect(InetSocketAddress(peer, 6102), 10000)
                s.soTimeout = 10000
                s.getOutputStream().write("ue-to-pcscf\n".toByteArray())
                s.getInputStream().bufferedReader().readLine().orEmpty().also { ipsec.removeTransportModeTransforms(s) }
            }
            report("ue_client_reply" to clientReply)
            // peer client port -> UE server port (the peer retries until we listen)
            Os.setsockoptTimeval(listener, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, android.system.StructTimeval.fromMillis(60000))
            val accepted = Os.accept(listener, null)
            val got = ByteArray(64).let { b -> String(b, 0, Os.read(accepted, b, 0, b.size)).trim() }
            Os.write(accepted, "ue-ack\n".toByteArray(), 0, 7)
            Thread.sleep(500)
            report("ue_server_got" to got, "packets_out_uc_ps" to packets(ueToPs).toString(), "packets_in_ps_uc" to packets(psToUe).toString(),
                "packets_in_pc_us" to packets(pcToUs).toString(), "packets_out_us_pc" to packets(usToPc).toString())
            ipsec.removeTransportModeTransforms(accepted); Os.close(accepted)
            assertEquals("pcscf-ack", clientReply.lines().last().trim().ifEmpty { clientReply })
            assertEquals("pcscf-to-ue", got)
        } finally {
            runCatching { ipsec.removeTransportModeTransforms(listener) }
            Os.close(listener)
            listOf(ueToPs, psToUe, pcToUs, usToPc).forEach { it.close() }
            listOf(spiUc, spiUs, spiPc, spiPs).forEach { it.close() }
        }
    }
}
