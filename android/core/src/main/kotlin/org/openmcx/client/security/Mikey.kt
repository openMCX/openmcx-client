package org.openmcx.client.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeyError(message: String) : Exception(message)

private fun check(ok: Boolean, what: String) { if (!ok) throw KeyError(what) }

/** A KMS's public parameters (TS 33.180 D.3.2): its URI, key period and offset (seconds), KPAK and Z. */
data class KmsPublic(val kmsUri: String, val period: Long, val offset: Long, val kpak: EcPoint, val z: EcPoint) {
    fun periodNumber(ntpSeconds: Long): Long {
        check(period > 0 && offset < period && ntpSeconds >= offset, "invalid KMS key period")
        return (ntpSeconds - offset) / period
    }
}

/** A user's key material for one period: the ECCSI (SSK, PVT) pair and the SAKKE RSK. Never sent to anyone. */
class IdentityKeys(val uri: String, val periodNumber: Long, val ssk: BigInteger, val pvt: EcPoint, val rsk: EcPoint)

/** Key properties (TS 33.180 E.6.11): type 0 GMK, 1 PCK, 2 CSK; status bit 0 not revoked; Unix times. */
data class KeyProperties(val type: Int, val status: Long = 1, val activation: Long = 0, val expiry: Long = 0,
                         val text: String = "", val groups: List<String> = emptyList())

/** A key carried in a MIKEY-SAKKE I_MESSAGE: the 128-bit key, RAND, CSB-ID (the key ID) and the NTP time. */
class KeyMessage(val key: ByteArray, val random: ByteArray, val csbId: Long, val ntpSeconds: Long, val properties: KeyProperties? = null) {
    val purpose get() = (csbId ushr 28).toInt()
}

/**
 * The outer values TS 33.180 E.6 leaves unassigned (the Key Parameters IEI, and the Protected Payload
 * message type, algorithm and IEI), agreed per deployment; the lab's are in its security configuration.
 */
data class WireProfile(val messageType: Int, val algorithm: Int, val keyParametersIei: Int, val encryptedPayloadIei: Int)

/** MIKEY-SAKKE (RFC 6509) I_MESSAGEs as TS 33.180 Annex E profiles them, byte for byte as OpenMCX writes them. */
object Mikey {
    const val NTP_EPOCH = 2208988800L
    private val random = SecureRandom()

    private fun ByteArrayOutputStream.int(value: Long, width: Int) { for (n in width - 1 downTo 0) write((value ushr (8 * n)).toInt() and 0xff) }
    private fun ByteArrayOutputStream.bytes(b: ByteArray) = write(b, 0, b.size)

    /** TS 33.180 F.2.1: the UID of [uri] for the key period that [ntpSeconds] falls in, as its base64 text. */
    fun uid(uri: String, kms: KmsPublic, ntpSeconds: Long): String {
        val out = ByteArrayOutputStream()
        fun string(text: String) { val b = text.toByteArray(); check(b.isNotEmpty() && b.size <= 65535, "invalid identity"); out.bytes(b); out.int(b.size.toLong(), 2) }
        fun minimal(value: Long) { var width = 1; var rest = value ushr 8; while (rest != 0L) { width++; rest = rest ushr 8 }; out.int(value, width); out.int(width.toLong(), 2) }
        out.write(0)
        string("MIKEY-SAKKE-UID"); string(uri); string(kms.kmsUri)
        minimal(kms.period); minimal(kms.offset); minimal(kms.periodNumber(ntpSeconds))
        return Base64.getEncoder().encodeToString(Eccsi.sha256(out.toByteArray()))
    }

    private fun idPayload(out: ByteArrayOutputStream, next: Int, role: Int, identity: String) {
        val b = identity.toByteArray()
        check(b.isNotEmpty() && b.size <= 65535, "invalid MIKEY identity")
        out.write(next); out.write(role); out.write(1); out.int(b.size.toLong(), 2); out.bytes(b)
    }

    /** The I_MESSAGE carrying [key] from [sender] to [recipient], SAKKE-encapsulated under [recipientKms] and signed under [kms]. */
    fun create(kms: KmsPublic, sender: IdentityKeys, recipient: String, key: KeyMessage,
               profile: WireProfile? = null, recipientKms: KmsPublic = kms): ByteArray {
        check(key.ntpSeconds in 0..0xffffffffL, "NTP era unsupported")
        check(sender.periodNumber == kms.periodNumber(key.ntpSeconds), "identity key period mismatch")
        val encapsulated = Sakke(recipientKms.z).encapsulate(uid(recipient, recipientKms, key.ntpSeconds).toByteArray(), key.key)
        val out = ByteArrayOutputStream()
        out.bytes(byteArrayOf(1, 26, 5, 1)); out.int(key.csbId, 4); out.write(0); out.write(1)
        out.write(11); out.write(0); out.int(key.ntpSeconds, 4); out.int(0, 4)
        out.write(14); out.write(16); out.bytes(key.random)
        idPayload(out, 14, 1, sender.uri); idPayload(out, 14, 2, recipient)
        idPayload(out, 14, 6, kms.kmsUri); idPayload(out, 26, 7, recipientKms.kmsUri)
        val properties = key.properties
        check(properties == null || profile != null, "key properties require an explicit wire profile")
        out.write(if (properties != null) 21 else 4); out.write(1); out.write(2); out.int(encapsulated.size.toLong(), 2); out.bytes(encapsulated)
        if (properties != null) {
            val sealed = sealProperties(key, properties, profile!!)
            out.write(4); out.write(7); out.int(sealed.size.toLong(), 2); out.bytes(sealed)
        }
        out.int(0x2081, 2) // RFC 6507 signature: r32 ‖ s32 ‖ PVT65
        val signed = out.toByteArray()
        val id = uid(sender.uri, kms, key.ntpSeconds).toByteArray()
        return signed + Eccsi(kms.kpak).sign(id, sender.ssk, sender.pvt, signed)
    }

    private class Reader(val data: ByteArray) {
        var position = 0
        fun number(n: Int): Long {
            check(n <= 8 && n <= data.size - position, "truncated MIKEY")
            var v = 0L
            repeat(n) { v = (v shl 8) or (data[position++].toLong() and 0xff) }
            return v
        }
        fun take(n: Int): ByteArray { check(n >= 0 && n <= data.size - position, "truncated MIKEY"); return data.copyOfRange(position, position + n).also { position += n } }
        fun identity(role: Int, next: Int): String {
            check(number(1) == next.toLong() && number(1) == role.toLong() && number(1) == 1L, "unsupported MIKEY identity payload")
            return String(take(number(2).toInt()))
        }
        fun header(): Long {
            check(number(1) == 1L && number(1) == 26L && number(1) == 5L && number(1) == 1L, "unsupported MIKEY header")
            val csb = number(4)
            check(number(1) == 0L && number(1) == 1L, "unsupported MIKEY session map")
            return csb
        }
    }

    /** What an I_MESSAGE says before it is authenticated: its CSB-ID, time, initiator and KMSs. */
    class Envelope(val csbId: Long, val ntpSeconds: Long, val initiator: String, val receiver: String, val initiatorKms: String, val receiverKms: String)

    fun envelope(message: ByteArray): Envelope {
        check(message.size <= 65535, "oversized MIKEY message")
        val r = Reader(message)
        val csb = r.header()
        check(r.number(1) == 11L && r.number(1) == 0L, "unsupported MIKEY timestamp")
        val ntp = r.number(4); r.number(4)
        check(r.number(1) == 14L && r.number(1) == 16L, "unsupported MIKEY RAND")
        r.take(16)
        return Envelope(csb, ntp, r.identity(1, 14), r.identity(2, 14), r.identity(6, 14), r.identity(7, 26))
    }

    /**
     * Authenticate [message] as from [sender] (its signature under the initiator's KMS, found by [lookup]),
     * addressed to [recipient], within [skew] seconds of [nowNtp], and open the key it carries.
     */
    fun open(kms: KmsPublic, recipient: IdentityKeys, sender: String, message: ByteArray, nowNtp: Long,
             profile: WireProfile? = null, skew: Long = 300, lookup: (String) -> KmsPublic? = { null }): KeyMessage {
        check(message.size <= 65535, "oversized MIKEY message")
        val r = Reader(message)
        val csb = r.header()
        check(r.number(1) == 11L && r.number(1) == 0L, "unsupported MIKEY timestamp")
        val ntp = r.number(4); r.number(4)
        check(kotlin.math.abs(nowNtp - ntp) <= skew, "stale or future MIKEY timestamp")
        check(recipient.periodNumber == kms.periodNumber(ntp), "identity key period mismatch")
        check(r.number(1) == 14L && r.number(1) == 16L, "unsupported MIKEY RAND")
        val rand = r.take(16)
        check(r.identity(1, 14) == sender, "unexpected MIKEY sender")
        check(r.identity(2, 14) == recipient.uri, "unexpected MIKEY recipient")
        val signingKmsUri = r.identity(6, 14)
        val signingKms = if (signingKmsUri == kms.kmsUri) kms else lookup(signingKmsUri) ?: throw KeyError("unexpected signing KMS")
        check(r.identity(7, 26) == kms.kmsUri, "unexpected recipient KMS")
        val next = r.number(1).toInt()
        check((next == 4 || next == 21) && r.number(1) == 1L && r.number(1) == 2L, "unsupported SAKKE profile")
        val ciphertext = r.take(r.number(2).toInt())
        var sealed: ByteArray? = null
        if (next == 21) {
            check(profile != null, "key properties require an explicit wire profile")
            check(r.number(1) == 4L && r.number(1) == 7L, "unsupported MIKEY extension")
            sealed = r.take(r.number(2).toInt())
        }
        check(r.number(2) == 0x2081L, "unsupported ECCSI SIGN")
        val signedLength = r.position
        val signature = r.take(129)
        check(r.position == message.size, "trailing MIKEY data")
        val signerId = uid(sender, signingKms, ntp).toByteArray()
        check(Eccsi(signingKms.kpak).verify(signerId, message.copyOfRange(0, signedLength), signature), "MIKEY signature invalid")
        val key = Sakke(kms.z).decapsulate(uid(recipient.uri, kms, ntp).toByteArray(), recipient.rsk, ciphertext)
            ?: throw KeyError("SAKKE decapsulation failed")
        val opened = KeyMessage(key, rand, csb, ntp)
        return if (sealed == null) opened else KeyMessage(key, rand, csb, ntp, openProperties(opened, sealed, profile!!))
    }

    // Key properties in a Protected Payload (TS 33.180 E.6.11, 8.5.4).

    private fun encodeProperties(p: KeyProperties, profile: WireProfile): ByteArray {
        check((p.type == 0 || p.type == 2) && (p.type == 0 || p.groups.isEmpty()), "unsupported key properties")
        val content = ByteArrayOutputStream()
        content.write(p.type); content.int(p.status, 4); content.int(p.activation, 5); content.int(p.expiry, 5)
        val text = p.text.toByteArray(); content.int(text.size.toLong(), 2); content.bytes(text)
        if (p.type == 0) {
            val groups = ByteArrayOutputStream()
            groups.write(p.groups.size)
            for (g in p.groups) { val b = g.toByteArray(); groups.write(0x7b); groups.int(b.size.toLong(), 2); groups.bytes(b) }
            content.int(groups.size().toLong(), 2); content.bytes(groups.toByteArray())
        }
        val out = ByteArrayOutputStream()
        out.write(profile.keyParametersIei); out.int(content.size().toLong(), 2); out.bytes(content.toByteArray())
        return out.toByteArray()
    }

    private fun decodeProperties(data: ByteArray, profile: WireProfile): KeyProperties {
        val r = Reader(data)
        check(r.number(1) == profile.keyParametersIei.toLong(), "unexpected Key Parameters IEI")
        check(r.number(2) == (data.size - r.position).toLong(), "invalid Key Parameters length")
        val type = r.number(1).toInt()
        check(type == 0 || type == 2, "unsupported key type")
        val status = r.number(4); val activation = r.number(5); val expiry = r.number(5)
        val text = String(r.take(r.number(2).toInt()))
        val groups = mutableListOf<String>()
        if (type == 0) {
            val g = Reader(r.take(r.number(2).toInt()))
            repeat(g.number(1).toInt()) {
                check(g.number(1) == 0x7bL, "unexpected MC group ID IEI")
                groups += String(g.take(g.number(2).toInt())).also { check(it.isNotEmpty(), "empty group ID") }
            }
            check(g.position == g.data.size, "trailing MC group IDs")
        }
        check(r.position == data.size, "unsupported trailing key parameters")
        return KeyProperties(type, status, activation, expiry, text, groups)
    }

    /** Annex F.1.5: the DPCK, HMAC-SHA-256(DPPK, 0x53 ‖ DPPK-ID ‖ 0x0004), its last 16 octets. */
    fun payloadKey(dppk: ByteArray, dppkId: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(dppk, "HmacSHA256")) }
        val input = ByteArrayOutputStream().apply { write(0x53); int(dppkId, 4); int(4, 2) }.toByteArray()
        return mac.doFinal(input).copyOfRange(16, 32)
    }

    /** TS 33.180 8.5.4.1: a Protected Payload message, AES-128-GCM under the DPCK of [dppk]. */
    fun sealPayload(type: Int, unixSeconds: Long, payloadId: Long, sequence: Int, dppk: ByteArray, dppkId: Long,
                    plain: ByteArray, profile: WireProfile): ByteArray {
        check(profile.algorithm == 1, "only DP_AES_128_GCM is supported")
        val header = ByteArrayOutputStream()
        header.write(type); header.int(unixSeconds, 5); header.int(payloadId, 4); header.write(sequence); header.write(profile.algorithm)
        val iv = ByteArray(16).also { random.nextBytes(it) }
        header.bytes(iv); header.int(dppkId, 4)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey(dppk, dppkId), "AES"), GCMParameterSpec(128, iv))
            updateAAD(header.toByteArray())
        }.doFinal(plain)
        header.write(profile.encryptedPayloadIei); header.int(cipher.size.toLong(), 2); header.bytes(cipher)
        return header.toByteArray()
    }

    class Payload(val type: Int, val unixSeconds: Long, val payloadId: Long, val sequence: Int, val dppkId: Long, val plain: ByteArray)

    fun openPayload(data: ByteArray, dppk: ByteArray, profile: WireProfile): Payload {
        val r = Reader(data)
        val type = r.number(1).toInt(); val time = r.number(5); val id = r.number(4); val seq = r.number(1).toInt()
        check(r.number(1) == profile.algorithm.toLong(), "unsupported payload algorithm")
        val iv = r.take(16); val dppkId = r.number(4)
        val associated = data.copyOfRange(0, r.position)
        check(r.number(1) == profile.encryptedPayloadIei.toLong(), "unexpected protected payload IEI")
        val cipher = r.take(r.number(2).toInt())
        check(r.position == data.size && cipher.size >= 16, "invalid protected payload")
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(payloadKey(dppk, dppkId), "AES"), GCMParameterSpec(128, iv))
                updateAAD(associated)
            }.doFinal(cipher)
        } catch (e: javax.crypto.AEADBadTagException) { throw KeyError("protected payload authentication failed") }
        return Payload(type, time, id, seq, dppkId, plain)
    }

    private fun sealProperties(key: KeyMessage, p: KeyProperties, profile: WireProfile): ByteArray {
        check(key.ntpSeconds >= NTP_EPOCH && p.type == key.purpose, "key type disagrees with CSB-ID purpose")
        return sealPayload(profile.messageType, key.ntpSeconds - NTP_EPOCH, 0, 0, key.key, key.csbId, encodeProperties(p, profile), profile)
    }

    private fun openProperties(key: KeyMessage, data: ByteArray, profile: WireProfile): KeyProperties {
        check(data.isNotEmpty() && (data[0].toInt() and 0xff) == profile.messageType, "unexpected protected payload type")
        val payload = openPayload(data, key.key, profile)
        check(payload.dppkId == key.csbId, "key properties CSB-ID mismatch")
        check(payload.unixSeconds + NTP_EPOCH == key.ntpSeconds, "key properties timestamp mismatch")
        return decodeProperties(payload.plain, profile).also { check(it.type == key.purpose, "key type disagrees with CSB-ID purpose") }
    }

    /** A fresh key of [purpose] (0 GMK, 1 PCK, 2 CSK): 128 random bits, RAND, and an ID with that purpose tag. */
    fun newKey(purpose: Int, ntpSeconds: Long, properties: KeyProperties? = null): KeyMessage {
        val key = ByteArray(16).also { random.nextBytes(it) }
        val rand = ByteArray(16).also { random.nextBytes(it) }
        val id = (purpose.toLong() shl 28) or (random.nextInt().toLong() and 0x0fffffffL)
        return KeyMessage(key, rand, id, ntpSeconds, properties)
    }

    fun nowNtp(): Long = System.currentTimeMillis() / 1000 + NTP_EPOCH
}
