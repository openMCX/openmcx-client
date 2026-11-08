//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The pre-provisioned TrK and InK of a provisioning file (TS 33.180 clause 5.3.2: "distributed via an out-of-band
 * mechanism along with their 32-bit identifiers"), the optional `transport_keys` member of an mcx-provisioning/1 file:
 *
 *     "transport_keys": {"<MC service user ID>": {"trk": {"key": "<hex>", "id": <XPK-ID>}, "ink": {...}}}
 *
 * The TrK is 256 bits, the InK 128 or 256 bits (clause 5.3.2, as the shared C++ core's transport_keys); the XPK-ID is
 * 32 bits (clause 9.3.3). A user has a TrK, an InK, or both.
 */
object TransportKeyEntries {
    class Entry(val user: String, val kind: String, key: ByteArray, val id: Long) {
        private val bytes = key.copyOf()
        val key: ByteArray get() = bytes.copyOf()
        fun clear() = java.util.Arrays.fill(bytes, 0)
    }

    private fun hex(text: String, what: String): ByteArray {
        if (text.isEmpty() || text.length % 2 != 0 || text.any { it !in "0123456789abcdefABCDEF" })
            throw IllegalArgumentException("$what: the key must be hexadecimal")
        return ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** The entries of a `transport_keys` object; throws IllegalArgumentException on anything malformed. */
    fun parse(root: JsonObject): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            for ((user, value) in root) {
                require(user.isNotEmpty() && user.length <= 65535) { "transport_keys: invalid MC service user ID" }
                val kinds = value as? JsonObject ?: throw IllegalArgumentException("transport_keys.$user must be an object")
                require(kinds.isNotEmpty() && kinds.keys.all { it == "trk" || it == "ink" }) { "transport_keys.$user: trk, ink or both" }
                for ((kind, entry) in kinds) {
                    val what = "transport_keys.$user.$kind"
                    val fields = entry as? JsonObject ?: throw IllegalArgumentException("$what must be an object")
                    val keyText = (fields["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw IllegalArgumentException("$what.key missing")
                    val id = (fields["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                        ?.takeIf { it in 0..0xffffffffL } ?: throw IllegalArgumentException("$what.id must be a 32-bit number")
                    val key = hex(keyText, what)
                    val sizes = if (kind == "trk") setOf(32) else setOf(16, 32)
                    if (key.size !in sizes) {
                        java.util.Arrays.fill(key, 0)
                        throw IllegalArgumentException(if (kind == "trk") "$what: the TrK is 256 bits" else "$what: the InK is 128 or 256 bits")
                    }
                    out += Entry(user, kind, key, id)
                    java.util.Arrays.fill(key, 0)
                }
            }
        } catch (e: Exception) {
            out.forEach { it.clear() }
            throw e
        }
        return out
    }
}
