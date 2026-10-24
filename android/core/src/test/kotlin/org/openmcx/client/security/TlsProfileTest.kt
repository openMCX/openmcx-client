//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** TS 33.210 clause 6.2: TLS 1.2 only with ECDHE and AEAD, TLS 1.3, nothing else. */
class TlsProfileTest {
    private val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }

    @Test
    fun keepsOnlyTheProfilesSuites() {
        // The platform's suites and the kinds clause 6.2 forbids (RSA key exchange, CBC, DHE).
        val supported = context.supportedSSLParameters.cipherSuites + arrayOf(
            "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        )
        val chosen = TlsProfile.suites(supported)
        assertTrue(chosen.toList().containsAll(TlsProfile.TLS12_MANDATORY))
        for (suite in chosen) {
            assertFalse(suite, "_CBC_" in suite || suite.startsWith("TLS_RSA_") || suite.startsWith("TLS_DHE_"))
            assertTrue(suite, suite in TlsProfile.TLS12 || suite in TlsProfile.TLS13)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun refusesAPlatformWithoutTheMandatorySuites() {
        TlsProfile.suites(arrayOf("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"))
    }

    @Test
    fun profilesSocketsOfTheFactory() {
        val socket = TlsProfile.socketFactory(context).createSocket() as SSLSocket
        socket.use {
            assertArrayEquals(TlsProfile.PROTOCOLS, it.enabledProtocols)
            assertEquals(TlsProfile.suites(it.supportedCipherSuites).toList(), it.enabledCipherSuites.toList())
        }
    }
}
