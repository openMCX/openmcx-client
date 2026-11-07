//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.telephony

import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's access to an IMS-capable UICC through carrier privileges (the card's ARA-M rule names the app's signing
 * certificate): carrier privileges on the card's subscription, the ISIM's EF_IMPI, EF_DOMAIN and EF_IMPU
 * (TS 31.103 4.2.2-4.2.4) over a logical channel, and the ISIM's AUTHENTICATE in the IMS AKA security context
 * (TS 31.103 7.1.2) with a challenge given by the caller. Only public APIs, in the app's process (its uid holds the
 * privileges). The card's answers are reported as instrumentation status (am instrument -r); the caller checks
 * RES/CK/IK or AUTS against its own vectors, so no key reaches the device.
 *
 * Needs a card with such a rule in the device; the tests are skipped on a device without carrier privileges.
 *   am instrument -r -w -e class org.openmcx.client.app.telephony.IsimCardTest \
 *       [-e rand <32 hex> -e autn <32 hex>] org.openmcx.client.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class IsimCardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    /** The subscription whose card gives this app carrier privileges, or null. */
    private fun privileged(): Pair<Int, TelephonyManager>? {
        val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        val tm = context.getSystemService(TelephonyManager::class.java)
        return subs.map { it.subscriptionId to tm.createForSubscriptionId(it.subscriptionId) }
            .firstOrNull { it.second.hasCarrierPrivileges() }
    }

    private fun report(vararg kv: Pair<String, String>) {
        instrumentation.sendStatus(0, Bundle().apply { kv.forEach { putString(it.first, it.second) } })
    }

    @Test
    fun carrierPrivileges() {
        val p = privileged()
        report("subscription" to (p?.first?.toString() ?: "none"), "carrier_privileges" to (p != null).toString())
        assertNotNull("no active subscription gives this app carrier privileges", p)
    }

    @Test
    fun isimFiles() {
        val (_, tm) = privileged().also { assumeTrue("no carrier privileges", it != null) }!!
        val channel = tm.iccOpenLogicalChannel(ISIM_AID, 0x04)
        report("open_channel_status" to channel.status.toString(), "channel" to channel.channel.toString())
        assertTrue("logical channel to the ISIM refused: status ${channel.status}",
            channel.status == android.telephony.IccOpenLogicalChannelResponse.STATUS_NO_ERROR)
        try {
            for ((name, fid, record) in listOf(Triple("impi", "6F02", false), Triple("domain", "6F03", false),
                                               Triple("impu1", "6F04", true))) {
                // SELECT by file identifier, no response data (P2 0x0C), then READ BINARY / READ RECORD 1 (absolute)
                val sel = tm.iccTransmitApduLogicalChannel(channel.channel, 0x00, 0xA4, 0x00, 0x0C, 0x02, fid)
                val read = if (record) tm.iccTransmitApduLogicalChannel(channel.channel, 0x00, 0xB2, 0x01, 0x04, 0x00, "")
                           else tm.iccTransmitApduLogicalChannel(channel.channel, 0x00, 0xB0, 0x00, 0x00, 0x00, "")
                report("${name}_select" to sel, "${name}_read" to read, "${name}_text" to tlv80(read))
            }
        } finally {
            tm.iccCloseLogicalChannel(channel.channel)
        }
    }

    @Test
    fun isimAuthentication() {
        val rand = args.getString("rand")
        val autn = args.getString("autn")
        assumeTrue("no -e rand/-e autn given", rand != null && autn != null)
        val (_, tm) = privileged().also { assumeTrue("no carrier privileges", it != null) }!!
        // TS 31.102 7.1.2.1 command data: length + RAND, length + AUTN
        val data = byteArrayOf(16) + hex(rand!!) + byteArrayOf(16) + hex(autn!!)
        val answer = tm.getIccAuthentication(TelephonyManager.APPTYPE_ISIM, TelephonyManager.AUTHTYPE_EAP_AKA,
            Base64.encodeToString(data, Base64.NO_WRAP))
        report("auth_response" to (answer?.let { Base64.decode(it, Base64.DEFAULT).toHex() } ?: "null"))
        assertNotNull("getIccAuthentication returned null", answer)
    }

    private companion object {
        const val ISIM_AID = "A0000000871004FFFFFFFF8907090000"

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /** The text of a TS 31.103 4.2.2-4.2.4 TLV (tag 0x80, one length byte) in a response with its SW at the end. */
        fun tlv80(response: String?): String {
            val b = response?.takeIf { it.length >= 8 }?.let { hex(it.dropLast(4)) } ?: return ""
            if (b[0] != 0x80.toByte()) return ""
            val n = b[1].toInt() and 0xFF
            return if (n + 2 <= b.size) String(b, 2, n, Charsets.UTF_8) else ""
        }
    }
}
