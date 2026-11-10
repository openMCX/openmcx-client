//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.client.ClientConfig
import org.openmcx.client.security.GroupKeys
import org.openmcx.client.security.KeyClient
import org.openmcx.client.security.KeyError
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KeyUse
import org.openmcx.client.security.Mikey
import org.openmcx.client.security.WireProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The key operations of MCData payload protection, as the MCData ID ([KeyClient] implements them). */
interface PayloadKeys {
    /** A new PCK for [recipient] (TS 33.180 clause 7.4), and its I_MESSAGE in base64. */
    suspend fun makePck(recipient: String): Pair<KeyMessage, String>
    /** Open a PCK offered by [expected], its initiator (authenticated). */
    suspend fun acceptPck(expected: String, encoded: String): KeyMessage
    /** TS 33.180 clause 8.5.5: [original] signed as an Authenticated Payload. */
    suspend fun authenticate(original: ByteArray): ByteArray
    /** TS 33.180 clause 8.5.5: an Authenticated Payload verified as signed by [sender]. */
    suspend fun openAuthenticated(sender: String, message: ByteArray): Mikey.AuthenticatedPayload
}

/**
 * MCData payload protection, MCData client side: DATA PAYLOADs and files protected end to end under a PCK (one-to-one)
 * or the group's GMK, and payload authentication; a port of the Linux client (mcdata/protection.cpp).
 *
 * Specifications: TS 24.282 V19.7.0 (Rel-19), TS 33.180 V19.5.0 (Rel-19).
 */
class McdataProtection(
    private val config: ClientConfig.Mcdata,
    /** The agreed wire profile of Protected Payload messages (TS 33.180 clause 8.5.4.1); null without a KMS. */
    private val profile: WireProfile?,
    /** The key management client of this user's MCData ID (TS 24.282 clause 6.6: it signs and receives PCKs as it). */
    private val keys: () -> PayloadKeys?,
    /** The GMKs of the MCPTT groups, which also protect MCData payloads for their MC group IDs (TS 33.180 clause 8.4). */
    private val groupKeys: () -> GroupKeys?,
    /** Unix seconds. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** TS 24.282 clause 6.6.3: DATA PAYLOADs protected end to end (`protect_payload`). */
    val protectsPayload: Boolean get() = config.protectPayload

    /** TS 33.180 clause 8.5.5 with TS 24.282 clause 6.6.3.2: DATA PAYLOADs signed (`authenticate_payload`). */
    val authenticates: Boolean get() = config.authenticatePayload

    /** What the protection did, for the status (debug status `keys.mcdata_protection`). */
    val sealed = AtomicInteger()
    val opened = AtomicInteger()
    val signed = AtomicInteger()
    val verified = AtomicInteger()
    val failures = AtomicInteger()

    /** A PCK shared with [peer] (its initiator or recipient), usable until [end] (Unix seconds). */
    class PckContext(val key: KeyMessage, val peer: String, val end: Long)
    private val pcks = ConcurrentHashMap<Long, PckContext>()
    val pckContexts: Int get() = pcks.size

    private fun keyClient(): PayloadKeys = keys() ?: throw KeyError("MCData key management client unavailable")
    private fun wire(): WireProfile = profile ?: throw KeyError("no key parameter profile")

    /** Helper: drop the PCK contexts whose lifetime has ended. */
    private fun expire() { val now = clock(); pcks.entries.removeIf { it.value.end <= now } }

    /**
     * TS 33.180 annex E.6.10 ("The 'Expiry time' element shall define the time in UTC at which the associated key shall
     * no longer be used ... A value of 0 shall imply the key shall not expire"): the end of an MCData PCK context: the
     * local lifetime `mcdata.pck_lifetime_s`, or the PCK's own Expiry time when it is earlier.
     */
    private fun pckEnd(key: KeyMessage): Long {
        val local = clock() + config.pckLifetimeS
        val expiry = key.properties?.expiry ?: 0L
        return if (expiry != 0L && expiry < local) expiry else local
    }

    /**
     * TS 24.282 clause 9.2.2.2.1 step 2c: an existing unexpired context with the peer, or a new PCK whose I_MESSAGE the
     * request carries in an application/mikey body (the second value, null when the context exists).
     */
    suspend fun pckFor(peer: String): Pair<KeyMessage, String?> {
        expire()
        pcks.values.firstOrNull { it.peer == peer }?.let { return it.key to null }
        val (key, message) = keyClient().makePck(peer)
        if (pcks.size >= MAX_PCK_CONTEXTS) throw KeyError("MCData PCK context capacity reached")
        pcks[key.csbId] = PckContext(key, peer, pckEnd(key))
        return key to message
    }

    /** Forget the PCK shared with [peer] (a test: the recipient will not share a tampered one). */
    fun forget(peer: String) { pcks.entries.removeIf { it.value.peer == peer } }

    /**
     * TS 24.282 clause 9.2.2.2.2 step 3: authenticate an I_MESSAGE from [sender] and keep its PCK. A redelivered or
     * retransmitted message repeats an I_MESSAGE already accepted.
     */
    suspend fun acceptPck(encoded: String, sender: String) {
        expire()
        val known = pcks[KeyClient.keyId(encoded)]
        if (known != null && known.peer == sender) return
        val key = keyClient().acceptPck(sender, encoded)
        if (pcks.size >= MAX_PCK_CONTEXTS) throw KeyError("MCData PCK context capacity reached")
        pcks[key.csbId] = PckContext(key, sender, pckEnd(key))
    }

    /** TS 33.180 clause 8.4 with TS 24.282 clause 6.6.3.2: the current GMK whose MC group IDs include [group]. */
    fun gmkFor(group: String): KeyMessage {
        val key = groupKeys()?.active(group) ?: throw KeyError("no active group key for $group")
        KeyUse.requireActive(key, group, clock())
        return key
    }

    /**
     * TS 33.180 clause 8.5.1: the Protected Payload message of an MCData message under a DPPK (a PCK or GMK); it
     * inherits the message type with bit 7 set. DP_AES_128_GCM: for a PCK or GMK the only algorithm with a key (annex
     * E.1.1, F.1.5). TS 24.282 clause 6.6.3.1: the whole DATA PAYLOAD message is protected.
     */
    fun seal(message: ByteArray, key: KeyMessage): ByteArray {
        if (message.isEmpty()) throw KeyError("empty payload to protect")
        return Mikey.sealPayload((message[0].toInt() and 0xff) or 0x40, clock(), 0, 0, key.key, key.csbId, message, wire())
            .also { sealed.incrementAndGet() }
    }

    /**
     * TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.2.1: the DPPK a received Protected Payload message names: the
     * PCK shared with the sender (PCK-ID, four most significant bits "0001", TS 24.282 clause 9.2.2.2.1 step 2c iii),
     * or the GMK of the group the message was sent to.
     */
    private fun dppk(id: Long, sender: String?, group: String?): KeyMessage = when (id ushr 28) {
        1L -> {
            expire()
            val found = pcks[id]
            if (found == null || found.peer != sender || !group.isNullOrEmpty()) throw KeyError("no PCK shared with the sender")
            found.key
        }
        0L -> {
            val found = groupKeys()?.byId(id)
            if (found == null || group.isNullOrEmpty()) throw KeyError("unknown group key")
            KeyUse.requireActive(found, group, clock())
            found
        }
        else -> throw KeyError("DPPK-ID is neither a PCK-ID nor a GMK-ID")
    }

    /**
     * TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.4: decrypt a Protected Payload message with its DPPK; it must
     * carry a DATA PAYLOAD. Returns the clear DATA PAYLOAD message.
     */
    fun open(body: ByteArray, sender: String?, group: String?): ByteArray {
        val key = dppk(Mikey.payloadKeyId(body), sender, group)
        val payload = Mikey.openPayload(body, key.key, wire())
        if (payload.type != (MessageType.DATA_PAYLOAD or 0x40)) throw KeyError("protected message is not a DATA PAYLOAD")
        opened.incrementAndGet()
        return payload.plain
    }

    /** The DPPK-ID of a Protected Payload message (TS 33.180 clause 8.5.4.1). */
    fun keyId(body: ByteArray): Long = Mikey.payloadKeyId(body)

    /**
     * TS 33.180 clause 8.5.5 with TS 24.282 clause 6.6.3.2: sign a payload with this user's KMS-provisioned key and set
     * bit 8 of its message type (TS 33.180 clause 8.5.1); unchanged when payload authentication is off.
     */
    suspend fun sign(message: ByteArray): ByteArray {
        if (!authenticates) return message
        return keyClient().authenticate(message).also { signed.incrementAndGet() }
    }

    /** TS 33.180 clauses 8.5.1 and 8.5.5: a signed payload (bit 8) from [sender]: verified, then its original returned. */
    suspend fun verify(message: ByteArray, sender: String): ByteArray {
        val opened = keyClient().openAuthenticated(sender, message)
        if ((if (opened.original.isEmpty()) 0 else (opened.original[0].toInt() and 0xff) or 0x80) != opened.messageType)
            throw KeyError("signed payload type mismatch")
        verified.incrementAndGet()
        return opened.original
    }

    /** TS 33.180 clauses 8.5.1 and 8.5.4: the protected binary data representing a file, as a series of protected payloads. */
    fun sealFile(contents: ByteArray, key: KeyMessage): ByteArray =
        Mikey.sealSeries(contents, PROTECTED_FILE_TYPE, key.key, key.csbId, wire(), unixSeconds = clock()).also { sealed.incrementAndGet() }

    /** TS 33.180 clauses 8.5.1 and 8.5.4: the clear file from the protected binary data representing it. */
    fun openFile(data: ByteArray, sender: String?, group: String?): ByteArray =
        Mikey.openSeries(data, PROTECTED_FILE_TYPE, dppk(Mikey.payloadKeyId(data), sender, group).key, wire()).also { opened.incrementAndGet() }

    companion object {
        /**
         * A file is protected as a series of Protected Payload messages sharing one Payload ID, numbered by the Payload
         * sequence number (TS 33.180 table 8.5.4.1-1 NOTE 2), since one message holds at most 65535 octets. TS 24.282
         * table 15.2.2-1 has no type for file data: FD SIGNALLING PAYLOAD with bit 7, as the Linux client.
         */
        const val PROTECTED_FILE_TYPE = 0x42
        /** The Linux client's bound of PCK contexts held at once. */
        const val MAX_PCK_CONTEXTS = 256
        const val MIKEY = "application/mikey"
        /** TS 24.282 clause 9.2.2.2.2 step 3: the warning of a refused I_MESSAGE (606), as the Linux client. */
        const val WARNING_136 = "399 mcx \"136 authentication of the MIKEY-SAKKE I_MESSAGE failed\""
    }
}
