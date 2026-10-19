//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** PEM certificates and PKCS#8 keys, and TLS contexts built from them. */
object Pem {
    fun certificates(pem: String): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
            .map { it as X509Certificate }

    /** An unencrypted PKCS#8 private key (`BEGIN PRIVATE KEY`), RSA or EC. */
    fun privateKey(pem: String): PrivateKey {
        val der = block(pem, "PRIVATE KEY")
        val spec = PKCS8EncodedKeySpec(der)
        return listOf("RSA", "EC").firstNotNullOfOrNull { runCatching { KeyFactory.getInstance(it).generatePrivate(spec) }.getOrNull() }
            ?: throw IllegalArgumentException("unsupported private key")
    }

    fun block(pem: String, label: String): ByteArray {
        val body = pem.substringAfter("-----BEGIN $label-----", "").substringBefore("-----END $label-----", "")
        require(body.isNotBlank()) { "no $label block" }
        return Base64.getMimeDecoder().decode(body)
    }

    /** A trust store holding [ca] as the only anchors. */
    fun trustStore(ca: List<X509Certificate>): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        ca.forEachIndexed { i, cert -> setCertificateEntry("ca$i", cert) }
    }

    /** A TLS context trusting only [trust] and presenting [keys] (null: no client certificate). */
    fun sslContext(trust: KeyStore, keys: KeyStore?, password: CharArray = CharArray(0)): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val kmf = keys?.let { KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(it, password) } }
        return SSLContext.getInstance("TLS").apply { init(kmf?.keyManagers, tmf.trustManagers, null) }
    }

    /**
     * A TLS context trusting [ca] and the system's CAs, for IdMS: an IdMS of another vendor may have a certificate of a
     * public CA, which the system browser that shows its login page trusts too (TS 33.180 annex B.4.2).
     */
    fun sslContextWithSystem(ca: List<X509Certificate>): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustWithSystem(ca)), null) }

    /** The trust of [sslContextWithSystem]: a server certificate [ca] or the system's CAs issued. */
    fun trustWithSystem(ca: List<X509Certificate>): X509TrustManager {
        fun manager(store: KeyStore?) = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val own = manager(trustStore(ca))
        val system = manager(null)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw java.security.cert.CertificateException("no client certificates here")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    own.checkServerTrusted(chain, authType)
                } catch (e: java.security.cert.CertificateException) {
                    system.checkServerTrusted(chain, authType)
                }
            }
            override fun getAcceptedIssuers() = own.acceptedIssuers + system.acceptedIssuers
        }
    }

    /** A key store holding the client key and its chain, in memory. */
    fun keyStore(key: PrivateKey, chain: List<X509Certificate>, password: CharArray = CharArray(0)): KeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", key, password, chain.toTypedArray())
        }
}
