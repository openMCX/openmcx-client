//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A pre-provisioned transport key (TrK, 256 bits) or integrity key (InK, 128 or 256 bits) with its 32-bit identifier
 * (TS 33.180 clause 5.3.3: "The TrK and InK are initially distributed via an out-of-band mechanism along with their
 * 32-bit identifiers"; clause 9.3.3: the key is the XPK and its identifier the XPK-ID). Only its HMAC-SHA-256 is used
 * (the annex F.1.4 derivation), so the key may stay in a key store that never gives it out (the app's Android Keystore).
 */
interface XpkKey {
    val id: Long
    /** The key length in octets: 16 or 32. */
    val size: Int
    /** HMAC-SHA-256 keyed by the XPK. */
    fun hmac(data: ByteArray): ByteArray

    /** TS 33.180 clause 9.3.5: the KeyName of a key under this XPK, the base64 of its 4-octet XPK-ID. */
    val keyName: String get() = Base64.getEncoder().encodeToString(byteArrayOf(
        (id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()))
}

/** An XPK held in memory; [clear] wipes it (clause 5.11.1: it does not outlive its use in memory). */
class Xpk(key: ByteArray, override val id: Long) : XpkKey {
    private val bytes = key.copyOf()
    init {
        require(bytes.size == 16 || bytes.size == 32) { "the XPK must be 128 or 256 bits" }
        require(id in 0..0xffffffffL) { "the XPK-ID is 32 bits" }
    }
    val key: ByteArray get() = bytes.copyOf()
    override val size: Int get() = bytes.size
    fun clear() = Arrays.fill(bytes, 0)
    override fun hmac(data: ByteArray): ByteArray {
        val k = bytes.copyOf()
        return try { Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(data) }
        finally { Arrays.fill(k, 0) }
    }
}

/**
 * One MC service user's pre-provisioned keys (TS 33.180 clause 5.3.2): the TrK, the InK, either or both. KMS messages
 * are signed under the InK, else the TrK (annexes D.2.2 and D.3.1: "the InK, or if not present, the TrK"); key
 * material is wrapped under the TrK only (clause 9.3.4.2).
 */
class UserTransportKeys(val trk: XpkKey?, val ink: XpkKey?) {
    val integrity: XpkKey? get() = ink ?: trk
    val confidentiality: XpkKey? get() = trk
}

/**
 * The keys derived from a TrK or InK and the AES key wrap of KMS key material, as the shared C++ core
 * (transport_keys.hpp) and the Linux client do them. Specifications: TS 33.180 V19.5.0 clauses 5.3.2, 9.3.3, 9.3.4.2,
 * annexes D.3.3.2 and F.1.4; TS 33.220 annex B; RFC 3394.
 */
object TransportKeys {
    /** TS 33.180 annex F.1.4: FC 0x51, the confidentiality key (it unwraps the key set). */
    const val CONFIDENTIALITY = 0x51
    /** TS 33.180 annex F.1.4: FC 0x52, the integrity key (it signs and checks the KMS messages). */
    const val INTEGRITY = 0x52

    /**
     * TS 33.180 annex F.1.4: the key derived from the XPK with the KDF of TS 33.220 annex B: HMAC-SHA-256 over FC,
     * P0 = the MC service user ID with L0, P1 = the 4-octet XPK-ID with L1 = 0x00 0x04. "Where the XPK is 128-bits ...
     * the 128 least significant bits of the 256 bits of the KDF output", "Where the XPK is 256-bits ... the entire
     * output of the KDF". L0 and L1 are the actual lengths (TS 33.220 annex B.2.0): F.1.4's "(i.e. 0x00 0x17)" for both
     * is a spec defect (reported).
     */
    fun protectionKey(xpk: XpkKey, user: String, fc: Int): ByteArray {
        require(fc == CONFIDENTIALITY || fc == INTEGRITY) { "unknown FC for an XPK-derived key" }
        val p0 = user.toByteArray(Charsets.UTF_8)
        require(p0.isNotEmpty() && p0.size <= 65535) { "the derivation needs the MC service user ID" }
        val id = xpk.id
        val input = byteArrayOf(fc.toByte()) + p0 + byteArrayOf((p0.size ushr 8).toByte(), p0.size.toByte(),
            (id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte(), 0, 4)
        val out = xpk.hmac(input)
        val result = out.copyOfRange(out.size - xpk.size, out.size)
        Arrays.fill(out, 0)
        return result
    }

    private val IV = ByteArray(8) { 0xa6.toByte() }

    /**
     * RFC 3394 section 2.2.1, the AES-256 key wrap (kw-aes256) of n >= 2 64-bit blocks, with the default initial value
     * A6A6A6A6A6A6A6A6; built from AES in ECB mode, one block at a time.
     */
    fun wrap(kek: ByteArray, plain: ByteArray): ByteArray {
        require(kek.size == 32) { "kw-aes256 needs a 256-bit key" }
        require(plain.size >= 16 && plain.size % 8 == 0) { "kw-aes256 (RFC 3394) wraps only key material of at least 128 bits in 64-bit blocks" }
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES")) }
        val n = plain.size / 8
        val a = IV.copyOf()
        val r = plain.copyOf()
        val block = ByteArray(16)
        for (j in 0..5) for (i in 1..n) {
            a.copyInto(block, 0); r.copyInto(block, 8, (i - 1) * 8, i * 8)
            val b = aes.doFinal(block)
            val t = n.toLong() * j + i
            for (k in 0..7) a[k] = (b[k].toInt() xor ((t ushr (8 * (7 - k))).toInt() and 0xff)).toByte()
            b.copyInto(r, (i - 1) * 8, 8, 16)
            Arrays.fill(b, 0)
        }
        Arrays.fill(block, 0)
        val out = a + r
        Arrays.fill(r, 0)
        return out
    }

    /** RFC 3394 section 2.2.2, the unwrap; its integrity check (section 2.2.3) fails a wrong key or altered data. */
    fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray {
        require(kek.size == 32) { "kw-aes256 needs a 256-bit key" }
        require(wrapped.size >= 24 && wrapped.size % 8 == 0) { "wrapped key material must be 64-bit blocks (RFC 3394)" }
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES")) }
        val n = wrapped.size / 8 - 1
        val a = wrapped.copyOfRange(0, 8)
        val r = wrapped.copyOfRange(8, wrapped.size)
        val block = ByteArray(16)
        for (j in 5 downTo 0) for (i in n downTo 1) {
            val t = n.toLong() * j + i
            for (k in 0..7) block[k] = (a[k].toInt() xor ((t ushr (8 * (7 - k))).toInt() and 0xff)).toByte()
            r.copyInto(block, 8, (i - 1) * 8, i * 8)
            val b = aes.doFinal(block)
            b.copyInto(a, 0, 0, 8); b.copyInto(r, (i - 1) * 8, 8, 16)
            Arrays.fill(b, 0)
        }
        Arrays.fill(block, 0)
        if (!java.security.MessageDigest.isEqual(a, IV)) {
            Arrays.fill(r, 0)
            throw KeyError("AES key unwrap failed its integrity check")
        }
        return r
    }

    /**
     * The ETSI test suite's padding before kw-aes256 (f_MCX_XML_EncryptKey): n - 1 zero octets and one octet n,
     * 1 <= n <= 8, then RFC 3394. TS 33.180 clause 9.3.4.2 names only RFC 3394, which cannot wrap the 257-octet RSK or
     * the 65-octet PVT, and no other wrap: this is the test system's reading (as in the shared core and the Linux
     * client), not a 3GPP rule; reported as a spec defect.
     */
    fun wrapKeyMaterial(kek: ByteArray, material: ByteArray): ByteArray {
        require(material.size >= 8) { "key material to wrap must be at least 64 bits" }
        val n = 8 - material.size % 8
        val padded = material.copyOf(material.size + n)
        padded[padded.size - 1] = n.toByte()
        return try { wrap(kek, padded) } finally { Arrays.fill(padded, 0) }
    }

    /** RFC 3394 unwrap and its integrity check, then the padding length octet (1 to 8) and its removal. */
    fun unwrapKeyMaterial(kek: ByteArray, wrapped: ByteArray): ByteArray {
        val padded = unwrap(kek, wrapped)
        val n = padded[padded.size - 1].toInt() and 0xff
        if (n < 1 || n > 8 || n >= padded.size) {
            Arrays.fill(padded, 0)
            throw KeyError("unwrapped key material has an invalid padding length")
        }
        val out = padded.copyOfRange(0, padded.size - n)
        Arrays.fill(padded, 0)
        return out
    }
}
