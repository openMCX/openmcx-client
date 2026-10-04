package org.openmcx.client.provisioning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.openmcx.client.http.Endpoints
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.SocketHttpClient
import org.openmcx.client.idms.IdmsClient
import org.openmcx.client.security.Pem
import java.net.URI
import java.net.URL
import java.util.Base64

/**
 * Activation from an MCX Admin activation code (slice F2): the one-time link names the
 * bootstrap package (format openmcx-activation-1) and pins MCX Admin's certificate. With
 * the user's password the device signs in at IdMS, gets its SIP credential and a
 * certificate for a key it made from the Credential Service, and writes its profile.
 * No password or key comes from the package.
 */
object Activation {
    const val FORMAT = "openmcx-activation-1"

    /** An MCX Admin activation link (…/activate/…), as opposed to a profile link. */
    fun isLink(text: String): Boolean = runCatching {
        val uri = URI(text.trim())
        uri.scheme == "https" && (uri.rawPath ?: "").contains("/activate/")
    }.getOrDefault(false)

    class Result(val bundle: JsonObject, val caPem: String, val certificatePem: String)

    /** The device's key: its SubjectPublicKeyInfo, and signing with SHA-256 and ECDSA. */
    interface DeviceKey {
        val subjectPublicKeyInfo: ByteArray
        fun sign(data: ByteArray): ByteArray
    }

    /**
     * Run the activation: [link] (with #pin=), [password], and [key], which makes the device's
     * key only once sign-in has succeeded (a mistyped password leaves the old key). [http]
     * builds the client that reaches the servers, trusting the package's CA and following its
     * address map; tests pass their own.
     */
    suspend fun activate(
        link: String,
        password: String,
        key: () -> DeviceKey,
        fetchPackage: (URL, ByteArray) -> String = QrProfile::fetchPinned,
        http: (String, Map<String, String>) -> HttpClient = { caPem, map ->
            SocketHttpClient(Pem.sslContext(Pem.trustStore(Pem.certificates(caPem)), null), Endpoints.of(map))
        },
    ): Result {
        val text = link.trim()
        val pin = URI(text).rawFragment?.split('&')?.firstOrNull { it.startsWith("pin=") }?.removePrefix("pin=")
            ?: throw IllegalArgumentException("the activation code names no certificate pin")
        val pkg = Json.parseToJsonElement(fetchPackage(URL(text.substringBefore('#')), Base64.getUrlDecoder().decode(pin))).jsonObject
        if (pkg.str("format") != FORMAT) throw IllegalArgumentException("not an $FORMAT package")
        val ca = pkg.str("trust_root")
        val addressMap = pkg["address_map"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val client = http(ca, addressMap)
        val idms = pkg.obj("idms")
        val tokens = IdmsClient(client, idms.str("issuer"), idms.str("client_id"), idms.str("redirect_uri"))
            .loginDirect(pkg.str("login"), password)
        val auth = mapOf("Authorization" to "Bearer ${tokens.accessToken}")
        val sip = client.request("POST", pkg.str("credentials_url").trimEnd('/') + "/v1/sip-credential", auth, ByteArray(0))
        if (sip.status != 200) throw IllegalStateException("the Credential Service refused the SIP credential (${sip.status})")
        val credential = Json.parseToJsonElement(sip.text).jsonObject
        val device = key()
        val request = Csr.pem(Csr.build("mcx-device", device.subjectPublicKeyInfo, device::sign))
        val cert = client.request("POST", pkg.str("certificate_url"), auth + ("Content-Type" to "application/pkcs10"), request.toByteArray())
        if (cert.status != 200) throw IllegalStateException("the Credential Service refused the device certificate (${cert.status})")
        val certificate = Json.parseToJsonElement(cert.text).jsonObject.str("certificate")
        return Result(bundle(pkg, password, credential.str("username"), credential.str("password")), ca, certificate)
    }

    /** The Android client's profile (as lab/androidlab.py writes it) from a bootstrap package. */
    fun bundle(pkg: JsonObject, password: String, sipUsername: String, sipPassword: String): JsonObject {
        val sip = pkg.obj("sip")
        // sip:host[:port][;params], e.g. sip:sip-core:5061;transport=tls
        val coreUri = sip.str("core_uri").removePrefix("sips:").removePrefix("sip:")
        val hostPort = coreUri.substringBefore(';')
        val tls = !coreUri.substringAfter(';', "").lowercase().split(';').contains("transport=tcp")
        val coreHost = if (hostPort.contains(':')) hostPort.substringBeforeLast(':') else hostPort
        val corePort = if (hostPort.contains(':')) hostPort.substringAfterLast(':').toInt() else if (tls) 5061 else 5060
        val idms = pkg.obj("idms")
        val mcptt = pkg.obj("mcptt")
        val endpoints = pkg.obj("endpoints")
        val groups = { o: JsonObject -> JsonArray(listOfNotNull(o.opt("group_id")?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) })) }
        return buildJsonObject {
            put("name", pkg.str("login"))
            put("domain", pkg.str("domain"))
            put("client_id", mcptt.str("client_id"))
            putJsonObject("sip") {
                put("public_user_id", sip.str("identity"))
                put("private_user_id", sipUsername)
                put("password", sipPassword)
                put("proxy", coreHost)
                put("proxy_port", corePort)
                put("transport", if (tls) "TLS" else "TCP")
                put("register_expires", 3600)
            }
            putJsonObject("tls") { put("ca", "ca.crt"); put("cert", "client.crt"); put("key", "client.key") }
            putJsonObject("idms") {
                put("issuer", idms.str("issuer")); put("client_id", idms.str("client_id")); put("redirect_uri", idms.str("redirect_uri"))
                put("username", pkg.str("login")); put("password", password)
            }
            put("cms", endpoints.str("cms"))
            put("gms", endpoints.str("gms"))
            putJsonObject("mcptt") {
                put("user_id", mcptt.str("user_id")); put("psi", mcptt.str("service_uri")); put("groups", groups(mcptt))
                // TS 24.484 §7.2.2.1: the profile index of <Default-user-profile>, 1 when the package has none.
                put("profile_index", mcptt.opt("profile_index")?.toIntOrNull() ?: 1)
                put("answer_mode", "automatic"); put("session_expires", 1800); put("manual_answer_timeout", 20)
                putJsonObject("resource_priority") {}
            }
            putJsonObject("media") { putJsonArray("port_range") { add(JsonPrimitive(20000)); add(JsonPrimitive(20400)) }; put("amr_wb_mode", 2) }
            pkg["mcdata"]?.jsonObject?.let { d ->
                putJsonObject("mcdata") {
                    put("user_id", d.str("user_id")); put("psi", d.str("service_uri")); put("groups", groups(d))
                    put("max_payload_size_sds_cplane_bytes", 1000); put("tdu1_seconds", 10)
                }
            }
            pkg["mcvideo"]?.jsonObject?.let { v ->
                putJsonObject("mcvideo") {
                    put("user_id", v.str("user_id")); put("psi", v.str("service_uri")); put("groups", groups(v))
                    putJsonArray("chat_groups") {}; put("answer_mode", "automatic"); putJsonObject("resource_priority") {}
                }
            }
            pkg["address_map"]?.jsonObject?.let { put("endpoints", it) }
        }
    }

    private fun JsonObject.str(key: String) = opt(key) ?: throw IllegalArgumentException("the package has no $key")
    private fun JsonObject.opt(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: throw IllegalArgumentException("the package has no $key")
}
