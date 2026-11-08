//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmcx.client.app.security.ClientCredentials
import org.openmcx.client.app.security.TransportKeyStore
import org.openmcx.client.security.TransportKeyEntries
import org.openmcx.client.security.UserTransportKeys
import kotlinx.serialization.json.JsonObject
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
        private const val PENDING_TRANSPORT_KEYS = "transport-keys.json"

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
     * `lab/androidlab.py` writes it for a phone. The key moves into the Keystore on the next start. An optional
     * `"transport_keys"` member carries the users' pre-provisioned TrK and InK ([TransportKeyEntries]); they move into
     * the Keystore on the next start too ([transportKeys]), and a profile without them removes any earlier ones.
     */
    fun import(text: String): ClientConfig {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "not an $FORMAT file" }
        val bundle = root["bundle"]?.jsonObject ?: throw IllegalArgumentException("no bundle")
        val config = ClientConfig.parse(bundle.toString())
        val pem = { name: String -> root[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("no $name") }
        Pem.certificates(pem("ca")); Pem.certificates(pem("cert")); Pem.privateKey(pem("key"))
        val transport = root["transport_keys"]?.let {
            (it as? JsonObject ?: throw IllegalArgumentException("transport_keys must be an object"))
                .also { keys -> TransportKeyEntries.parse(keys).forEach { e -> e.clear() } }
        }
        dir.deleteRecursively()
        TransportKeyStore.clearKeystore()
        dir.mkdirs()
        transport?.let { File(dir, PENDING_TRANSPORT_KEYS).writeText(it.toString()) }
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
        // MCX Admin holds no keys: an activation carries no TrK or InK, and earlier ones go.
        TransportKeyStore.clearKeystore()
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

    private val transportStore get() = TransportKeyStore(File(dir, "transport-keys.meta.json"))

    /**
     * The users' TrK and InK (TS 33.180 clause 5.3.2), importing newly provisioned ones into the Keystore first (the
     * file of [import], or `transport-keys.json` that `lab/androidlab.py` puts into the bundle); the file is deleted
     * after the import.
     */
    fun transportKeys(): (String) -> UserTransportKeys? {
        val pending = File(dir, PENDING_TRANSPORT_KEYS)
        if (pending.exists()) {
            val text = pending.readText()
            pending.delete()
            transportStore.import(TransportKeyEntries.parse(Json.parseToJsonElement(text).jsonObject))
        }
        val store = transportStore
        return { user -> store.keysOf(user) }
    }

    /** Which users have which transport keys ("trk", "ink"), never the keys. */
    fun transportKeySummary(): Map<String, List<String>> = transportStore.summary()

    /** The TLS context for IdMS: the provisioned CA and the system's (Pem.sslContextWithSystem). */
    fun idmsSslContext(): SSLContext = Pem.sslContextWithSystem(Pem.certificates(File(dir, "ca.crt").readText()))
}
