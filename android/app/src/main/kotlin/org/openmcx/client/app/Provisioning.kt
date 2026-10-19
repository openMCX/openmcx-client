//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmcx.client.app.security.ClientCredentials
import org.openmcx.client.security.Pem
import org.openmcx.client.client.ClientConfig
import org.openmcx.client.client.ServerAddress
import java.io.File
import javax.net.ssl.SSLContext

/**
 * The provisioning bundle in the app's files: `provisioning/bundle.json`, the lab CA
 * (`ca.crt`) and, until imported into the Keystore, the client certificate and key.
 */
class Provisioning(context: Context) {
    companion object {
        const val FORMAT = "mcx-provisioning/1"

        fun validHost(host: String) = ServerAddress.valid(host)
        fun hostOf(config: ClientConfig) = ServerAddress.of(config)
    }

    private val dir = File(context.filesDir, "provisioning")

    val present: Boolean get() = File(dir, "bundle.json").exists()

    /** The profile as imported, without the user's server address. */
    fun importedConfig(): ClientConfig = ClientConfig.parse(File(dir, "bundle.json").readText())

    /** The profile with the server address the user set, if any: only the addresses the servers are reached at change. */
    fun config(): ClientConfig = importedConfig().let { c -> serverAddress?.let { ServerAddress.apply(c, it) } ?: c }

    private val addressFile get() = File(dir, "server-address")

    /** The server address the user set in place of the profile's, or null. Importing a profile clears it. */
    val serverAddress: String? get() = addressFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Set (or with null, reset to the profile's) the address every server is reached at. */
    fun setServerAddress(host: String?) {
        if (host == null) { addressFile.delete(); return }
        val h = host.trim()
        require(validHost(h)) { "\"$h\" is not an IP address or host name" }
        addressFile.writeText(h)
    }

    /**
     * Install a provisioning file: one JSON document with the bundle and its PEM material
     * (`{"format": "mcx-provisioning/1", "bundle": {…}, "ca": "…", "cert": "…", "key": "…"}`), as
     * `lab/androidlab.py` writes it for a phone. The key moves into the Keystore on the next start.
     */
    fun import(text: String): ClientConfig {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "not an $FORMAT file" }
        val bundle = root["bundle"]?.jsonObject ?: throw IllegalArgumentException("no bundle")
        val config = ClientConfig.parse(bundle.toString())
        val pem = { name: String -> root[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("no $name") }
        Pem.certificates(pem("ca")); Pem.certificates(pem("cert")); Pem.privateKey(pem("key"))
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "bundle.json").writeText(bundle.toString())
        File(dir, "ca.crt").writeText(pem("ca"))
        File(dir, "client.crt").writeText(pem("cert"))
        File(dir, "client.key").writeText(pem("key"))
        return config
    }

    /**
     * Install what an MCX Admin activation gave (slice F2): the profile and the CA. The key
     * was made in the Keystore and holds its certificate already, so no key file is written.
     */
    fun installActivation(result: org.openmcx.client.provisioning.Activation.Result) {
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "bundle.json").writeText(result.bundle.toString())
        File(dir, "ca.crt").writeText(result.caPem)
    }

    /** The TLS context, importing a newly provisioned client key into the Keystore first. */
    fun sslContext(): SSLContext {
        val key = File(dir, "client.key")
        val cert = File(dir, "client.crt")
        if (key.exists() && cert.exists()) {
            ClientCredentials.import(key.readText(), cert.readText())
            key.delete()
        }
        return ClientCredentials.sslContext(File(dir, "ca.crt").readText())
    }

    /** The TLS context for IdMS: the provisioned CA and the system's (Pem.sslContextWithSystem). */
    fun idmsSslContext(): SSLContext = Pem.sslContextWithSystem(Pem.certificates(File(dir, "ca.crt").readText()))
}
