//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openmcx.client.body.McService
import org.openmcx.client.body.Part
import org.openmcx.client.security.KeyError
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KeyProperties
import org.openmcx.client.security.Mikey

/** TS 24.282 §6.6.3.2 with TS 33.180 §9.5.3 over the media plane: MCData signalling bodies of MSRP SENDs under the CSK. */
class MsrpSignallingProtectionTest {
    private fun config(protect: Boolean) = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8901/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcdata": {"user_id": "sip:alice.mcdata@mcx.test", "psi": "sip:mcdata@mcx.test", "groups": [], "protect_signalling": $protect},
        "security": {"kms_uri": "https://kms:8443", "key_parameter_profile": {}},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private var now = 1_800_000_000L
    private fun csk(expiry: Long = 0) = KeyMessage(ByteArray(16) { (3 + it).toByte() }, ByteArray(16), 0x20000003,
        Mikey.NTP_EPOCH + now, KeyProperties(2, 1, now - 10, expiry))
    private val signalling = byteArrayOf(0x05, 0x11, 0x22)
    private val payload = Part("application/vnd.3gpp.mcdata-payload", byteArrayOf(0x03, 1, 2))

    @Test fun sealedAndOpenedUnderTheCsk() {
        var key = csk()
        val config = config(true)
        val protection = SignallingProtection(config, { false to false }, { if (it == McService.MCDATA) key else null }, { true }, { now })
        val parts = listOf(Part(SignallingProtection.SIGNALLING, signalling), payload)
        val sealed = protection.sealMsrp(parts)
        // Bit 7 of the message type set; the DATA PAYLOAD (end-to-end, not the server's) untouched.
        assertEquals(0x45, sealed[0].content[0].toInt() and 0xff)
        assertArrayEquals(payload.content, sealed[1].content)
        assertArrayEquals(signalling, Mikey.openPayload(sealed[0].content, key.key, config.security!!.wireProfile, cskSignalling = true).plain)
        assertArrayEquals(signalling, protection.openMsrp(sealed)[0].content)
        // Clear signalling while it is protected is refused; past the Expiry time nothing is sealed.
        assertThrows(KeyError::class.java) { protection.openMsrp(parts) }
        key = csk(expiry = now)
        assertThrows(KeyError::class.java) { protection.sealMsrp(parts) }
    }

    @Test fun clearWithoutSignallingProtection() {
        val protection = SignallingProtection(config(false), { false to false }, { csk() }, { true }, { now })
        val parts = listOf(Part(SignallingProtection.SIGNALLING, signalling))
        assertEquals(parts, protection.sealMsrp(parts))
        assertEquals(parts, protection.openMsrp(parts))
    }
}
