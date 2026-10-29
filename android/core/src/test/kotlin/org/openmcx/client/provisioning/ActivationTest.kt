//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.provisioning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.client.ClientConfig
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class ActivationTest {
    private val pkg = Json.parseToJsonElement("""{"format":"openmcx-activation-1","domain":"mcx.test","login":"erin",
        "idms":{"issuer":"https://idms:8443","client_id":"mcx-lab-client","redirect_uri":"http://127.0.0.1:8900/callback"},
        "sip":{"core_uri":"sip:sip-core:5061;transport=tls","identity":"sip:erin@mcx.test"},
        "mcptt":{"user_id":"sip:erin.mcptt@mcx.test","client_id":"urn:uuid:5d3c9a1e-6f0b-4c1e-9a55-0000000000e5","service_uri":"sip:mcptt@mcx.test","group_id":"sip:operations@mcx.test","profile_index":"2"},
        "mcdata":{"user_id":"sip:erin.mcdata@mcx.test","service_uri":"sip:mcdata@mcx.test","group_id":"sip:operations@mcx.test"},
        "credentials_url":"https://credentials:8443","certificate_url":"https://credentials:8443/v1/client-certificate",
        "endpoints":{"cms":"https://http-proxy:8443/cms","gms":"https://http-proxy:8443/gms"},
        "address_map":{"sip-core:5061":"192.0.2.10:5061","http-proxy:8443":"192.0.2.10:8444"},
        "trust_root":"-----BEGIN CERTIFICATE-----\nX\n-----END CERTIFICATE-----\n"}""").jsonObject

    @Test fun bundleIsAValidProfile() {
        val bundle = Activation.bundle(pkg, "secret-password", "erin", "sip-secret")
        val config = ClientConfig.parse(bundle.toString())
        assertEquals("erin", config.name)
        assertEquals("sip:erin@mcx.test", config.sip.publicUserId)
        assertEquals("erin", config.sip.privateUserId)
        assertEquals("sip-secret", config.sip.password)
        assertEquals("sip-core", config.sip.proxy)
        assertEquals(5061, config.sip.proxyPort)
        assertEquals("urn:uuid:5d3c9a1e-6f0b-4c1e-9a55-0000000000e5", config.clientId)
        assertEquals("secret-password", config.idms.password)
        assertEquals(listOf("sip:operations@mcx.test"), config.mcptt!!.groups)
        // TS 24.484 §7.2.2.1: the <Default-user-profile> index names mcptt-user-profile-2.xml.
        assertEquals(2, config.mcptt!!.profileIndex)
        assertEquals("sip:erin.mcdata@mcx.test", config.mcdata!!.userId)
        assertEquals("192.0.2.10:8444", config.endpoints["http-proxy:8443"])
    }

    @Test fun activationLinks() {
        assertTrue(Activation.isLink("https://192.0.2.10:8446/activate/abc#pin=xyz"))
        assertFalse(Activation.isLink("https://192.0.2.10:8451/p/abc#pin=xyz"))
        assertFalse(Activation.isLink("MCXPROV1:abc"))
    }

    /** The request is DER whose CertificationRequestInfo the device key signed. */
    @Test fun certificateRequestIsSigned() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var signed: ByteArray? = null
        val der = Csr.build("mcx-device", pair.public.encoded) { info ->
            signed = info
            Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(info); sign() }
        }
        assertEquals(0x30, der[0].toInt() and 0xFF)
        // The info is the first element of the outer sequence; the signature verifies over it.
        val info = signed!!
        val start = if (der[1].toInt() and 0x80 != 0) 2 + (der[1].toInt() and 0x7F) else 2
        assertTrue(der.copyOfRange(start, start + info.size).contentEquals(info))
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(info) }
        // The signature is the BIT STRING's content after its unused-bits byte.
        val tail = der.copyOfRange(start + info.size, der.size)
        val algLen = tail[1].toInt() + 2
        val bits = tail.copyOfRange(algLen, tail.size)
        val bitsLenBytes = if (bits[1].toInt() and 0x80 != 0) 1 + (bits[1].toInt() and 0x7F) else 1
        val signature = bits.copyOfRange(1 + bitsLenBytes + 1, bits.size)
        assertTrue(verifier.verify(signature))
        assertTrue(Csr.pem(der).startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
    }

    /** A UE initial configuration (TS 24.484 §7.2.2.1) with [proxy] and [mutual] in <App-Server-Info>. */
    private fun initial(proxy: String, mutual: String) = """<mcptt-UE-initial-configuration xmlns="urn:3gpp:mcptt:mcpttUEinitConfig:1.0" domain="mcx.test"><on-network>""" +
        "<App-Server-Info><idms-auth-endpoint>https://idms/a</idms-auth-endpoint><idms-token-endpoint>https://idms/t</idms-token-endpoint>" +
        "$proxy<gms>https://gms</gms><cms>https://cms</cms><kms>https://kms</kms><tls-tunnel-auth-method>$mutual" +
        "</tls-tunnel-auth-method></App-Server-Info><GMS-URI>sip:mcptt@mcx.test</GMS-URI></on-network></mcptt-UE-initial-configuration>"

    /**
     * TS 24.484 §7.2.2.1 item 3 c), g): the package's <http-proxy> becomes http.proxy with one-way TLS tunnel
     * authentication; an empty one names none; mutual authentication is refused.
     */
    @Test fun homeHttpProxyFromTheInitialConfiguration() {
        val withProxy = Json.parseToJsonElement(pkg.toString().removeSuffix("}") +
            ",\"ue_initial_configuration\":" + Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(
                initial("<http-proxy>https://proxy.mcx.test:8443</http-proxy>", "<mutual-authentication>false</mutual-authentication>"))) + "}").jsonObject
        val config = ClientConfig.parse(Activation.bundle(withProxy, "secret-password", "erin", "sip-secret").toString())
        assertEquals("https://proxy.mcx.test:8443", config.httpProxy)
        assertEquals(null, ClientConfig.parse(Activation.bundle(pkg, "secret-password", "erin", "sip-secret").toString()).httpProxy)
        assertEquals(null, Activation.initialHttpProxy(initial("<http-proxy/>", "<mutual-authentication>0</mutual-authentication>")))
        assertThrows(IllegalArgumentException::class.java) {
            Activation.initialHttpProxy(initial("<http-proxy>https://p</http-proxy>", "<mutual-authentication>true</mutual-authentication><x509>MII</x509>"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Activation.initialHttpProxy(initial("", "<mutual-authentication>false</mutual-authentication>"))
        }
    }
}
