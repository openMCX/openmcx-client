package org.openmcx.client.provisioning

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.Inflater
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * An MCX profile from a QR code. A QR code holds at most about 2.9 kB, and a profile with
 * its CA, certificate and key is larger, so the code usually carries a one-time link:
 *
 *  - `https://host:port/path#pin=<base64url SHA-256 of the server's certificate>`: the
 *    profile file is fetched from the link. The phone does not trust the lab CA yet, so
 *    the server is trusted by the certificate named in the code, which the code's reader
 *    saw on the operator's screen; host names are not checked (the phone may reach the
 *    server by any address), the pin is.
 *  - `MCXPROV1:<base64url of the deflated profile file>`: the profile itself, when small.
 *
 * An MCX Admin activation link (`…/activate/…` without a pin) is recognised and refused
 * with the reason: this client activates from MCX Admin once device certificates come
 * from the Credential Service.
 */
object QrProfile {
    const val INLINE = "MCXPROV1:"
    private const val MAX_BYTES = 256 * 1024

    /** The profile file (the text [org.openmcx.client.app.Provisioning] imports) a scanned code names. */
    fun load(scanned: String, fetch: (URL, ByteArray) -> String = ::fetchPinned): String {
        val text = scanned.trim()
        if (text.startsWith(INLINE)) return inflate(Base64.getUrlDecoder().decode(text.removePrefix(INLINE)))
        if (!text.startsWith("https://")) throw IllegalArgumentException("not an openMCX profile QR code")
        val uri = URI(text)
        val pin = uri.rawFragment?.split('&')?.firstOrNull { it.startsWith("pin=") }?.removePrefix("pin=")
        if (pin == null) {
            if ("/activate/" in (uri.rawPath ?: ""))
                throw IllegalArgumentException("an MCX Admin activation code without the certificate pin (#pin=): ask for a new code")
            throw IllegalArgumentException("the link names no server certificate (#pin=…)")
        }
        val digest = Base64.getUrlDecoder().decode(pin)
        if (digest.size != 32) throw IllegalArgumentException("the certificate pin is not a SHA-256 digest")
        return fetch(URL(text.substringBefore('#')), digest)
    }

    fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = try { inflater.inflate(buffer) } catch (e: java.util.zip.DataFormatException) {
                throw IllegalArgumentException("a damaged profile code")
            }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("a damaged profile code")
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw IllegalArgumentException("the profile is too large")
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }

    /** SHA-256 of a certificate's DER encoding, the pin a code carries. */
    fun pinOf(certificate: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    /** GET [url] over TLS, trusting only a server whose certificate has the SHA-256 [pin]. */
    fun fetchPinned(url: URL, pin: ByteArray): String {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("server only")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !MessageDigest.isEqual(pinOf(chain[0]), pin))
                    throw java.security.cert.CertificateException("the server's certificate is not the one the QR code names")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val connection = url.openConnection() as HttpsURLConnection
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // the pin names the server
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) throw IllegalArgumentException("the profile link answered ${connection.responseCode}: used or expired")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw IllegalArgumentException("the profile is too large")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
