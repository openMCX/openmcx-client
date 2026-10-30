//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.http

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** The test-only key and certificate of proxy.test (test resource proxy-test.p12), trusting that certificate. */
internal object TestTls {
    const val HOST = "proxy.test"

    val context: SSLContext by lazy {
        val store = KeyStore.getInstance("PKCS12").apply {
            TestTls::class.java.classLoader!!.getResourceAsStream("proxy-test.p12").use { load(it, "test".toCharArray()) }
        }
        SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test".toCharArray()) }.keyManagers,
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers, null)
        }
    }

    /** proxy.test:[port] mapped to the loopback server on [port]. */
    fun endpoints(port: Int) = Endpoints.of(mapOf("$HOST:$port" to "127.0.0.1:$port"))
}
