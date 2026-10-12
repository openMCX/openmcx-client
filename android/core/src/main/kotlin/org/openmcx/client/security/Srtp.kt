//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SrtpError(message: String) : Exception(message)

/** An SRTP master key and salt (AEAD_AES_128_GCM: 16 + 12 octets) with its MKI, from a MIKEY key (TS 33.180 E.3). */
class SecurityMaterial(val masterKey: ByteArray, val masterSalt: ByteArray, val mki: ByteArray, val media: Boolean, val transmitRoc: Boolean = false)

/** TS 33.180 E.3 key derivation: the MIKEY PRF and the crypto sessions of each kind of key. */
object SrtpKeys {
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 3830 §4.1.2 PRF with HMAC-SHA-256 (RFC 6043), over 256-bit blocks of [key]. */
    fun mikeyPrf(key: ByteArray, label: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var start = 0
        while (start < key.size) {
            val block = key.copyOfRange(start, minOf(start + 32, key.size))
            var a = label
            var pos = 0
            while (pos < length) {
                a = hmac(block, a)
                val digest = hmac(block, a + label)
                for (i in 0 until minOf(32, length - pos)) output[pos + i] = (output[pos + i].toInt() xor digest[i].toInt()).toByte()
                pos += 32
            }
            start += 32
        }
        return output
    }

    private fun int(value: Long) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun derive(key: ByteArray, random: ByteArray, id: Long, cs: Int): Pair<ByteArray, ByteArray> {
        fun part(constant: Long, length: Int) = mikeyPrf(key, int(constant) + byteArrayOf(cs.toByte()) + int(id) + random, length)
        return part(0x2ad01c64, 16) to part(0x39a2c14b, 12)
    }

    /** A private call's streams (TS 33.180 7.4.1, E.3.2): CS-ID 0/1 (MCPTT) or 2/3 (MCVideo) by who sends; MKI the PCK-ID. */
    fun privateCall(pck: KeyMessage, fromInitiator: Boolean, mcvideo: Boolean): SecurityMaterial {
        require(pck.purpose == 1) { "PCK purpose tag must be one" }
        val (k, s) = derive(pck.key, pck.random, pck.csbId, (if (fromInitiator) 0 else 1) + (if (mcvideo) 2 else 0))
        return SecurityMaterial(k, s, int(pck.csbId), media = true)
    }

    /** Transmission (floor) control under a CSK (TS 33.180 9.4): CS-ID 6 (MCPTT) or 8 (MCVideo), SRTCP only; MKI the CSK-ID. */
    fun floorControl(csk: KeyMessage, mcvideo: Boolean): SecurityMaterial {
        require(csk.purpose == 2 || csk.purpose == 3) { "floor key purpose tag must be two or three" }
        val (k, s) = derive(csk.key, csk.random, csk.csbId, if (mcvideo) 8 else 6)
        return SecurityMaterial(k, s, int(csk.csbId), media = false)
    }

    /** The GUK-ID of [user] under [gmk] (TS 33.180 F.1.3): the GMK-ID XORed with 28 bits of HMAC-SHA-256(GMK, 0x50 ‖ user ‖ length). */
    fun gukId(gmk: ByteArray, gmkId: Long, user: String): Long {
        val u = user.toByteArray()
        val digest = hmac(gmk, byteArrayOf(0x50) + u + byteArrayOf((u.size shr 8).toByte(), u.size.toByte()))
        val tail = ((digest[28].toLong() and 0xff) shl 24) or ((digest[29].toLong() and 0xff) shl 16) or ((digest[30].toLong() and 0xff) shl 8) or (digest[31].toLong() and 0xff)
        return gmkId xor (tail and 0x0fffffffL)
    }

    /** A group call's stream from one sender (TS 33.180 7.3, E.3.2): CS-ID 4 (MCPTT) or 5 (MCVideo) keyed by the sender's GUK-ID; MKI GMK-ID ‖ GUK-ID, ROC in every packet. */
    fun groupCall(gmk: KeyMessage, gmkId: Long, gukId: Long, mcvideo: Boolean): SecurityMaterial {
        val (k, s) = derive(gmk.key, gmk.random, gukId, if (mcvideo) 5 else 4)
        return SecurityMaterial(k, s, int(gmkId) + int(gukId), media = true, transmitRoc = true)
    }
}

/**
 * One SSRC's SRTP and SRTCP under one key, AEAD_AES_128_GCM with a 16-octet tag (RFC 7714) and an MKI,
 * the session keys derived from the master key and salt (RFC 3711 §4.3, key derivation rate 0).
 * Separate send and receive state; received packets are checked against a 128-packet replay window.
 */
class SecureStream internal constructor(private val material: SecurityMaterial, val ssrc: Long, keys: SessionKeys) {
    constructor(material: SecurityMaterial, ssrc: Long) : this(material, ssrc, SessionKeys.derive(material))

    /** The SRTP and SRTCP session keys and salts. */
    internal class SessionKeys(val rtpKey: ByteArray, val rtpSalt: ByteArray, val rtcpKey: ByteArray, val rtcpSalt: ByteArray) {
        companion object {
            fun derive(material: SecurityMaterial): SessionKeys {
                require(material.masterKey.size == 16 && material.masterSalt.size == 12) { "AEAD_AES_128_GCM needs a 16-octet key and 12-octet salt" }
                val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(material.masterKey, "AES")) }
                fun kdf(label: Int, length: Int): ByteArray {
                    // x = (salt, padded to 112 bits) XOR (label ‖ index 0) at bit 48; the counter block is x · 2^16.
                    val block = ByteArray(16)
                    material.masterSalt.copyInto(block)
                    block[7] = (block[7].toInt() xor label).toByte()
                    val out = ByteArrayOutputStream()
                    var counter = 0
                    while (out.size() < length) {
                        block[14] = (counter shr 8).toByte(); block[15] = counter.toByte()
                        out.write(ecb.doFinal(block)); counter++
                    }
                    return out.toByteArray().copyOf(length)
                }
                return SessionKeys(kdf(0, 16), kdf(2, 12), kdf(3, 16), kdf(5, 12))
            }
        }
    }

    private val rtpKey = SecretKeySpec(keys.rtpKey, "AES")
    private val rtpSalt = keys.rtpSalt
    private val rtcpKey = SecretKeySpec(keys.rtcpKey, "AES")
    private val rtcpSalt = keys.rtcpSalt

    private var sendRoc = 0L
    private var lastSentSeq = -1
    internal var sendRtcpIndex = 0L
    private var receivedHighest = -1L
    private var receiveWindow = java.math.BigInteger.ZERO
    private var rtcpHighest = -1L
    private var rtcpWindow = java.math.BigInteger.ZERO

    private fun u32(b: ByteArray, at: Int) = ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
        ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun iv(salt: ByteArray, a: Long, b: Long, low: Int) = ByteArray(12).also {
        it[2] = (ssrc ushr 24).toByte(); it[3] = (ssrc ushr 16).toByte(); it[4] = (ssrc ushr 8).toByte(); it[5] = ssrc.toByte()
        it[6] = (a ushr 8).toByte(); it[7] = a.toByte()
        it[8] = (b ushr 8).toByte(); it[9] = b.toByte()
        it[10] = (low ushr 8).toByte(); it[11] = low.toByte()
        for (i in 0 until 12) it[i] = (it[i].toInt() xor salt[i].toInt()).toByte()
    }

    /** One cipher object for the stream's packets (each with its own IV); the caller serialises use. */
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun gcm(mode: Int, key: SecretKeySpec, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray = synchronized(cipher) {
        try {
            cipher.init(mode, key, GCMParameterSpec(128, iv)); cipher.updateAAD(aad); cipher.doFinal(data)
        } catch (e: AEADBadTagException) { throw SrtpError("SRTP authentication failure") }
    }

    private fun headerLength(packet: ByteArray): Int {
        if (packet.size < 12 || (packet[0].toInt() and 0xc0) != 0x80) throw SrtpError("invalid RTP header")
        var length = 12 + 4 * (packet[0].toInt() and 0x0f)
        if (packet[0].toInt() and 0x10 != 0) {
            if (packet.size < length + 4) throw SrtpError("truncated RTP extension")
            length += 4 + 4 * (((packet[length + 2].toInt() and 0xff) shl 8) or (packet[length + 3].toInt() and 0xff))
        }
        if (packet.size < length) throw SrtpError("truncated RTP header")
        return length
    }

    private fun checkSsrc(packet: ByteArray, at: Int) { if (u32(packet, at) != ssrc) throw SrtpError("SSRC mismatch") }

    /** RTP → SRTP: header ‖ AES-GCM(payload) ‖ tag ‖ MKI (‖ ROC in a group call). */
    fun protectRtp(packet: ByteArray): ByteArray {
        if (!material.media) throw SrtpError("this key protects SRTCP only")
        val header = headerLength(packet)
        checkSsrc(packet, 8)
        val seq = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        if (lastSentSeq >= 0 && seq < lastSentSeq && lastSentSeq - seq > 0x8000) sendRoc = (sendRoc + 1) and 0xffffffffL
        lastSentSeq = seq
        val aad = packet.copyOfRange(0, header)
        val sealed = gcm(Cipher.ENCRYPT_MODE, rtpKey, iv(rtpSalt, sendRoc ushr 16, sendRoc and 0xffff, seq), aad, packet.copyOfRange(header, packet.size))
        val roc = if (material.transmitRoc) byteArrayOf((sendRoc ushr 24).toByte(), (sendRoc ushr 16).toByte(), (sendRoc ushr 8).toByte(), sendRoc.toByte()) else ByteArray(0)
        return aad + sealed + material.mki + roc
    }

    /** SRTP → RTP, refusing a wrong MKI, a failed tag or a replay. */
    fun unprotectRtp(packet: ByteArray): ByteArray {
        if (!material.media) throw SrtpError("this key protects SRTCP only")
        val header = headerLength(packet)
        checkSsrc(packet, 8)
        val trailer = material.mki.size + (if (material.transmitRoc) 4 else 0)
        if (packet.size < header + 16 + trailer) throw SrtpError("truncated SRTP")
        val mkiAt = packet.size - trailer
        if (!packet.copyOfRange(mkiAt, mkiAt + material.mki.size).contentEquals(material.mki)) throw SrtpError("unknown MKI")
        val seq = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val roc = if (material.transmitRoc) u32(packet, packet.size - 4) else estimateRoc(seq)
        val index = (roc shl 16) or seq.toLong()
        if (!fresh(index, receivedHighest, receiveWindow)) throw SrtpError("SRTP replay")
        val aad = packet.copyOfRange(0, header)
        val plain = gcm(Cipher.DECRYPT_MODE, rtpKey, iv(rtpSalt, roc ushr 16, roc and 0xffff, seq), aad, packet.copyOfRange(header, mkiAt))
        accept(index, rtp = true)
        return aad + plain
    }

    /** RFC 3711 §3.3.1: the ROC of [seq], guessed from the highest index received. */
    private fun estimateRoc(seq: Int): Long {
        if (receivedHighest < 0) return 0
        val roc = receivedHighest ushr 16
        val sl = (receivedHighest and 0xffff).toInt()
        return when {
            sl < 0x8000 -> if (seq - sl > 0x8000 && roc > 0) roc - 1 else roc
            else -> if (sl - 0x8000 > seq) (roc + 1) and 0xffffffffL else roc
        }
    }

    private fun fresh(index: Long, highest: Long, window: java.math.BigInteger): Boolean =
        highest < 0 || index > highest || (highest - index < 128 && !window.testBit((highest - index).toInt()))

    private fun accept(index: Long, rtp: Boolean) {
        var highest = if (rtp) receivedHighest else rtcpHighest
        var window = if (rtp) receiveWindow else rtcpWindow
        if (highest < 0 || index > highest) {
            val shift = if (highest < 0) 128 else (index - highest).coerceAtMost(128).toInt()
            window = window.shiftLeft(shift).setBit(0).and(java.math.BigInteger.ONE.shiftLeft(128).subtract(java.math.BigInteger.ONE))
            highest = index
        } else window = window.setBit((highest - index).toInt())
        if (rtp) { receivedHighest = highest; receiveWindow = window } else { rtcpHighest = highest; rtcpWindow = window }
    }

    /** RTCP → SRTCP: first 8 octets ‖ AES-GCM(rest) ‖ tag ‖ E ‖ index ‖ MKI (RFC 7714 §9). */
    fun protectRtcp(packet: ByteArray): ByteArray {
        if (packet.size < 8 || (packet[0].toInt() and 0xc0) != 0x80) throw SrtpError("invalid RTCP header")
        checkSsrc(packet, 4)
        val index = sendRtcpIndex
        sendRtcpIndex = (sendRtcpIndex + 1) and 0x7fffffffL
        val eIndex = byteArrayOf((0x80 or (index ushr 24).toInt()).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
        val head = packet.copyOfRange(0, 8)
        val sealed = gcm(Cipher.ENCRYPT_MODE, rtcpKey, iv(rtcpSalt, 0, index ushr 16, (index and 0xffff).toInt()), head + eIndex, packet.copyOfRange(8, packet.size))
        return head + sealed + eIndex + material.mki
    }

    fun unprotectRtcp(packet: ByteArray): ByteArray {
        val mki = material.mki.size
        if (packet.size < 8 + 16 + 4 + mki || (packet[0].toInt() and 0xc0) != 0x80) throw SrtpError("truncated SRTCP")
        checkSsrc(packet, 4)
        if (!packet.copyOfRange(packet.size - mki, packet.size).contentEquals(material.mki)) throw SrtpError("unknown MKI")
        val eAt = packet.size - mki - 4
        val eIndex = packet.copyOfRange(eAt, eAt + 4)
        if (eIndex[0].toInt() and 0x80 == 0) throw SrtpError("unencrypted SRTCP")
        val index = u32(eIndex, 0) and 0x7fffffffL
        if (!fresh(index, rtcpHighest, rtcpWindow)) throw SrtpError("SRTCP replay")
        val head = packet.copyOfRange(0, 8)
        val plain = gcm(Cipher.DECRYPT_MODE, rtcpKey, iv(rtcpSalt, 0, index ushr 16, (index and 0xffff).toInt()), head + eIndex, packet.copyOfRange(8, eAt))
        accept(index, rtp = false)
        return head + plain
    }

}
