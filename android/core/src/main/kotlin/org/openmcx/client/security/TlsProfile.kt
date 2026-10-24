//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The 3GPP TLS profile of every TLS socket of the app (HTTP, SIP): TS 33.210 V19.3.0 clause 6.2, which TS 33.180
 * V19.5.0 clause 6.1.2 refers to through TS 33.310 annex E. TLS 1.2 and TLS 1.3 (clause 6.2.1); for TLS 1.2 only ECDHE
 * with AEAD (clause 6.2.3); TLS 1.3 with the platform's AEAD suites (clause 6.2.2, RFC 8446 section 9.1). Conscrypt
 * offers no finite field Diffie-Hellman groups.
 */
object TlsProfile {
    val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    /** Clause 6.2.3: the two mandatory suites. */
    val TLS12_MANDATORY = listOf("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")

    /** Clause 6.2.3: the mandatory ones, then the recommended AES-256 ones and ChaCha20-Poly1305. */
    val TLS12 = TLS12_MANDATORY + listOf(
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    val TLS13 = listOf("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")

    /** The profile's suites the platform supports, in the profile's order; fails without a mandatory TLS 1.2 suite. */
    fun suites(supported: Array<String>): Array<String> {
        val available = supported.toSet()
        val chosen = (TLS13 + TLS12).filter { it in available }
        check(TLS12_MANDATORY.any { it in chosen }) {
            "no mandatory TLS 1.2 cipher suite of the 3GPP profile on this platform"
        }
        return chosen.toTypedArray()
    }

    /** Applies the profile to a socket before its handshake. */
    fun apply(socket: SSLSocket): SSLSocket = socket.apply {
        enabledProtocols = PROTOCOLS.filter { it in supportedProtocols }.toTypedArray()
        enabledCipherSuites = suites(supportedCipherSuites)
    }

    /** A socket factory of [context] whose sockets carry the profile (for HttpsURLConnection). */
    fun socketFactory(context: SSLContext): SSLSocketFactory = Factory(context.socketFactory)

    private class Factory(private val inner: SSLSocketFactory) : SSLSocketFactory() {
        private fun profiled(socket: Socket): Socket = if (socket is SSLSocket) apply(socket) else socket
        override fun getDefaultCipherSuites(): Array<String> = suites(inner.supportedCipherSuites)
        override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites
        override fun createSocket(): Socket = profiled(inner.createSocket())
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            profiled(inner.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = profiled(inner.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            profiled(inner.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = profiled(inner.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            profiled(inner.createSocket(address, port, localAddress, localPort))
    }
}
