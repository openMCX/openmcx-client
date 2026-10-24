//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import org.openmcx.client.security.TlsProfile

class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    val text: String get() = String(body, Charsets.UTF_8)
}

class HttpError(message: String) : Exception(message)

/** HTTPS requests. Redirects are not followed: the OIDC flow reads them. */
interface HttpClient {
    suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResponse

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()) = request("GET", url, headers)

    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()) =
        request("POST", url, headers + ("Content-Type" to "application/x-www-form-urlencoded"), formEncode(fields).toByteArray())

    companion object {
        fun formEncode(fields: Map<String, String>): String =
            fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

/** [HttpClient] on HttpURLConnection, with the TLS client certificate and trust of [ssl]. */
class UrlConnectionHttpClient(private val ssl: SSLContext?, private val timeoutMs: Int = 15_000) : HttpClient {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                if (connection is HttpsURLConnection) connection.sslSocketFactory = TlsProfile.socketFactory(ssl ?: SSLContext.getDefault())
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.useCaches = false
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val data = stream?.use { it.readBytes() } ?: ByteArray(0)
                HttpResponse(status, connection.headerFields.filterKeys { it != null }, data)
            } finally {
                connection.disconnect()
            }
        }
}
