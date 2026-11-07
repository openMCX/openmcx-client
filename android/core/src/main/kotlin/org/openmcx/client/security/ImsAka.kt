//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * IMS AKA for SIP core access (TS 33.180 clause 6.2.1, TS 33.203, TS 24.229 clause 5.1.1.5): the HTTP Digest AKA
 * passwords and responses (RFC 3310, RFC 4169), the challenge's nonce, the security agreement (RFC 3329, TS 33.203
 * clause 7 and annex H) and the ESP keys (TS 33.203 annex I). The card runs AKA itself ([CardAka]); this code never
 * sees K or OPc. The Linux client's shared C++ core (src/security/ims_aka.cpp of openmcx-server) is the reference.
 */
enum class AkaAlgorithm(val wire: String) {
    AKAV1_MD5("AKAv1-MD5"),
    AKAV2_SHA256("AKAv2-SHA-256");

    companion object {
        /** RFC 3310 section 3.1, RFC 4169 section 3: the algorithm of a challenge, case-insensitive. */
        fun of(name: String): AkaAlgorithm? = entries.firstOrNull { it.wire.equals(name.trim(), ignoreCase = true) }
    }
}

/** RFC 3310 section 3.2: RAND and AUTN, the first 32 octets of the decoded nonce. */
class AkaNonce(val rand: ByteArray, val autn: ByteArray)

/** The card's answer to an AUTHENTICATE in the IMS AKA context (TS 31.103 clause 7.1.2, TS 33.102 clause 6.3.3). */
sealed interface AkaOutcome {
    class Success(val res: ByteArray, val ck: ByteArray, val ik: ByteArray) : AkaOutcome
    /** SQN out of range at the card: AUTS for the resynchronisation (TS 33.102 clause 6.3.5). */
    class SyncFailure(val auts: ByteArray) : AkaOutcome
    /** MAC failure: the card does not accept the network (TS 24.229 clause 5.1.1.5.3). */
    data object MacFailure : AkaOutcome
}

/** The card behind the UE's IMS AKA: a UICC application (ISIM) that the app reaches through carrier privileges. */
fun interface CardAka {
    suspend fun authenticate(rand: ByteArray, autn: ByteArray): AkaOutcome
}

object ImsAka {
    /** RFC 3310 section 3.2: RAND (16 octets) and AUTN (16 octets) from the base64 nonce; null when it is shorter. */
    fun parseNonce(nonce: String): AkaNonce? {
        val decoded = try { Base64.getDecoder().decode(nonce.trim()) } catch (e: IllegalArgumentException) { return null }
        if (decoded.size < 32) return null
        return AkaNonce(decoded.copyOfRange(0, 16), decoded.copyOfRange(16, 32))
    }

    /**
     * RFC 3310 section 3.3: AKAv1 takes RES as the password, as octets; RFC 4169 section 3: AKAv2 takes
     * base64(PRF(RES || IK || CK, "http-digest-akav2-password")), the PRF of AKAv2-SHA-256 being HMAC-SHA-256.
     */
    fun password(algorithm: AkaAlgorithm, res: ByteArray, ck: ByteArray, ik: ByteArray): ByteArray = when (algorithm) {
        AkaAlgorithm.AKAV1_MD5 -> res.copyOf()
        AkaAlgorithm.AKAV2_SHA256 -> Base64.getEncoder().encode(
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(res + ik + ck, "HmacSHA256")) }
                .doFinal("http-digest-akav2-password".toByteArray(Charsets.US_ASCII)))
    }

    /**
     * RFC 2617 section 3.2.2.1 and RFC 7616 section 3.4.1: KD(H(A1), nonce:nc:cnonce:qop:H(A2)) with qop, else
     * KD(H(A1), nonce:H(A2)); A1 = username:realm:password, A2 = method:uri. MD5 for AKAv1-MD5, SHA-256 for
     * AKAv2-SHA-256 (RFC 4169 section 3, with RFC 7616). The password goes in as octets (RES is binary).
     */
    fun response(
        algorithm: AkaAlgorithm, username: String, realm: String, password: ByteArray, method: String, uri: String,
        nonce: String, nc: String, cnonce: String, qop: String,
    ): String {
        val md = if (algorithm == AkaAlgorithm.AKAV1_MD5) "MD5" else "SHA-256"
        fun h(bytes: ByteArray) = MessageDigest.getInstance(md).digest(bytes).joinToString("") { "%02x".format(it) }
        val ha1 = h("$username:$realm:".toByteArray() + password)
        val ha2 = h("$method:$uri".toByteArray())
        return if (qop.isEmpty()) h("$ha1:$nonce:$ha2".toByteArray())
        else h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2".toByteArray())
    }
}

/** One sec-mechanism of Security-Client, Security-Server or Security-Verify (RFC 3329 section 2.2, TS 33.203 annex H). */
data class SecMechanism(
    val name: String = "ipsec-3gpp",
    val q: Double? = null,
    val alg: String = "",
    val ealg: String? = null,
    val prot: String? = null,
    val mod: String? = null,
    val spiC: Long? = null,
    val spiS: Long? = null,
    val portC: Int? = null,
    val portS: Int? = null,
) {
    /** TS 33.203 annex H, in the parameter order of TS 37.579-1 table 5.5.2.13-1. */
    fun format(): String = buildString {
        append(name)
        q?.let { append(";q=").append(String.format(Locale.ROOT, "%.1f", it)) }
        if (alg.isNotEmpty()) append(";alg=").append(alg)
        ealg?.let { append(";ealg=").append(it) }
        prot?.let { append(";prot=").append(it) }
        mod?.let { append(";mod=").append(it) }
        spiC?.let { append(";spi-c=").append(it) }
        spiS?.let { append(";spi-s=").append(it) }
        portC?.let { append(";port-c=").append(it) }
        portS?.let { append(";port-s=").append(it) }
    }
}

object SecAgree {
    /**
     * The integrity and encryption algorithm pairs this UE supports, its preference first: those of TS 33.203 annex H
     * and annex I that Android's IpSecManager provides. Not offered: aes-gmac and aes-gmac-us, which TS 33.203 clause
     * 7.1 with TS 33.210 clause 5.3.4 ("AES-GMAC with AES-128 shall be supported") requires but IpSecAlgorithm has no
     * algorithm for (an own deviation of the Android app, Known limitations).
     */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "null" to "aes-gcm-us",
        "null" to "aes-gcm",
        "hmac-sha2-256-128" to "aes-cbc",
        "hmac-sha-1-96" to "aes-cbc",
        "hmac-sha2-256-128" to "null",
        "hmac-sha-1-96" to "null",
    )

    /** RFC 3329 section 2.2: comma-separated sec-mechanisms; unknown parameters are skipped. */
    fun parse(value: String): List<SecMechanism> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
        val parts = item.split(';').map { it.trim() }
        var m = SecMechanism(name = parts[0])
        require(m.name.isNotEmpty()) { "sec-mechanism without a name" }
        for (part in parts.drop(1)) {
            val name = part.substringBefore('=').trim().lowercase(Locale.ROOT)
            val v = part.substringAfter('=', "").trim()
            m = when (name) {
                "q" -> m.copy(q = v.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: throw IllegalArgumentException("invalid q"))
                "alg" -> m.copy(alg = v)
                "ealg" -> m.copy(ealg = v)
                "prot" -> m.copy(prot = v)
                "mod" -> m.copy(mod = v)
                "spi-c" -> m.copy(spiC = v.toLongOrNull()?.takeIf { it in 0..0xffffffffL } ?: throw IllegalArgumentException("invalid spi-c"))
                "spi-s" -> m.copy(spiS = v.toLongOrNull()?.takeIf { it in 0..0xffffffffL } ?: throw IllegalArgumentException("invalid spi-s"))
                "port-c" -> m.copy(portC = v.toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("invalid port-c"))
                "port-s" -> m.copy(portS = v.toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("invalid port-s"))
                else -> m
            }
        }
        m
    }

    /** TS 33.203 clause 7.2 (SM1), RFC 3329 section 2.2: one mechanism per supported pair, q from 0.9 down by 0.1. */
    fun offers(spiC: Long, spiS: Long, portC: Int, portS: Int): List<SecMechanism> = SUPPORTED.mapIndexed { i, (alg, ealg) ->
        SecMechanism(q = (9 - i) / 10.0, alg = alg, ealg = ealg, prot = "esp", mod = "trans", spiC = spiC, spiS = spiS, portC = portC, portS = portS)
    }

    fun format(mechanisms: List<SecMechanism>): String = mechanisms.joinToString(", ") { it.format() }

    /**
     * TS 33.203 clause 7.2: "the UE selects the first integrity and encryption algorithm combination on the list
     * received from the P-CSCF ... which is also supported by the UE. If the P-CSCF did not include any
     * confidentiality algorithm ... the UE shall select the NULL encryption algorithm"; the list is ordered by q
     * (RFC 3329 section 2.2), ESP in transport mode only.
     */
    fun select(server: List<SecMechanism>): SecMechanism? = server.sortedByDescending { it.q ?: 0.001 }.firstOrNull { m ->
        m.name.equals("ipsec-3gpp", true) && (m.prot == null || m.prot.equals("esp", true)) &&
            (m.mod == null || m.mod.equals("trans", true)) &&
            SUPPORTED.contains(m.alg.lowercase(Locale.ROOT) to (m.ealg ?: "null").lowercase(Locale.ROOT)) &&
            m.spiC != null && m.spiS != null && m.portC != null && m.portS != null
    }
}

/** The ESP keys of one SA set (TS 33.203 annex I); salt is empty unless the encryption is AES-GCM. */
class EspKeys(val integrity: ByteArray, val encryption: ByteArray, val salt: ByteArray) {
    companion object {
        /** TS 33.220 annex B: HMAC-SHA-256(Key, FC || P0 || L0). */
        private fun kdf(key: ByteArray, fc: Int, p0: String): ByteArray {
            val p = p0.toByteArray(Charsets.US_ASCII)
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(byteArrayOf(fc.toByte()) + p + byteArrayOf((p.size shr 8).toByte(), p.size.toByte()))
        }

        /**
         * TS 33.203 annex I: HMAC-SHA-1-96 takes IK followed by 32 zero bits; HMAC-SHA2-256-128 the KDF of CK || IK with
         * FC 0x5A and P0 "HMAC-SHA2-256-128"; AES-CBC and AES-GCM take CK; the AES-GCM salt is the 32 least
         * significant bits of the KDF of CK || IK with FC 0x59 and P0 "AES_GCM_SALT".
         */
        fun derive(alg: String, ealg: String, ck: ByteArray, ik: ByteArray): EspKeys {
            val integrity = when (alg.lowercase(Locale.ROOT)) {
                "hmac-sha-1-96" -> ik + ByteArray(4)
                "hmac-sha2-256-128" -> kdf(ck + ik, 0x5A, "HMAC-SHA2-256-128")
                "null" -> ByteArray(0)
                else -> throw IllegalArgumentException("unsupported integrity algorithm $alg")
            }
            val e = ealg.lowercase(Locale.ROOT)
            val encryption = if (e == "null") ByteArray(0) else ck.copyOf()
            val salt = if (e == "aes-gcm" || e == "aes-gcm-us") kdf(ck + ik, 0x59, "AES_GCM_SALT").copyOfRange(28, 32) else ByteArray(0)
            return EspKeys(integrity, encryption, salt)
        }

        /**
         * TS 33.203 annex I, "-us": the salt of each SA, the direction bit ("0" UE to P-CSCF, "1" P-CSCF to UE) XOR'd
         * with its least significant bit and the role bit of the SA's source ("0" client, "1" server) with the second.
         */
        fun saSalt(salt: ByteArray, ealg: String, toUe: Boolean, fromServer: Boolean): ByteArray {
            if (salt.size != 4 || !ealg.equals("aes-gcm-us", true)) return salt
            return salt.copyOf().also { it[3] = (it[3].toInt() xor ((if (toUe) 1 else 0) or (if (fromServer) 2 else 0))).toByte() }
        }
    }
}
