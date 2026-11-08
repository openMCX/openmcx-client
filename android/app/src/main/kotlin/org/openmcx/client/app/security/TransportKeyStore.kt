//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.security

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.openmcx.client.security.TransportKeyEntries
import org.openmcx.client.security.UserTransportKeys
import org.openmcx.client.security.XpkKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The pre-provisioned TrK and InK of the MC service users (TS 33.180 clause 5.3.2) in the Android Keystore: imported
 * once from the provisioning file as HMAC-SHA256 keys that cannot be read back out, so the annex F.1.4 derivation (an
 * HMAC keyed by the XPK) runs in the Keystore. Only the derived key of one KMS exchange is ever in the app's memory,
 * and it is wiped after use (clause 5.11.1). The XPK-IDs and key lengths, not secret, are kept in [meta].
 */
class TransportKeyStore(private val meta: File) {
    companion object {
        private const val PREFIX = "mcx-xpk-"
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        /** Removes every XPK from the Keystore (a new provisioning replaces them all). */
        fun clearKeystore() {
            val store = keyStore()
            store.aliases().toList().filter { it.startsWith(PREFIX) }.forEach(store::deleteEntry)
        }
    }

    private class KeystoreXpk(private val alias: String, override val id: Long, override val size: Int) : XpkKey {
        override fun hmac(data: ByteArray): ByteArray {
            val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IllegalStateException("transport key $alias is gone")
            return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)
        }
    }

    /** Imports [entries] (replacing any earlier XPKs) and records their identifiers; the entries are wiped. */
    fun import(entries: List<TransportKeyEntries.Entry>) {
        clearKeystore()
        meta.delete()
        val store = keyStore()
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build()
        try {
            val record = buildJsonObject {
                for ((index, entry) in entries.withIndex()) {
                    val alias = "$PREFIX$index-${entry.kind}"
                    val key = entry.key
                    val size = key.size
                    try {
                        store.setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(key, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)), protection)
                    } finally { java.util.Arrays.fill(key, 0) }
                    putJsonObject("$index") {
                        put("user", entry.user); put("kind", entry.kind); put("alias", alias)
                        put("id", entry.id); put("size", size)
                    }
                }
            }
            meta.writeText(record.toString())
        } finally { entries.forEach { it.clear() } }
    }

    private fun records(): List<JsonObject> =
        meta.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject.values.map { v -> v.jsonObject } } ?: emptyList()

    /** The TrK and InK of [user] in the Keystore, or null when it has none. */
    fun keysOf(user: String): UserTransportKeys? {
        val mine = records().filter { it["user"]!!.jsonPrimitive.content == user }
        if (mine.isEmpty()) return null
        fun of(kind: String) = mine.firstOrNull { it["kind"]!!.jsonPrimitive.content == kind }?.let {
            KeystoreXpk(it["alias"]!!.jsonPrimitive.content, it["id"]!!.jsonPrimitive.long, it["size"]!!.jsonPrimitive.int)
        }
        return UserTransportKeys(of("trk"), of("ink"))
    }

    /** Which keys each user has ("trk", "ink"): what Settings and the debug status show, never the keys. */
    fun summary(): Map<String, List<String>> =
        records().groupBy({ it["user"]!!.jsonPrimitive.content }, { it["kind"]!!.jsonPrimitive.content })
}
