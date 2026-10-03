package org.openmcx.client.security

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ECCSI signatures (RFC 6507) on NIST P-256 with SHA-256, as TS 33.180 uses them to sign MIKEY-SAKKE
 * I_MESSAGEs. [kpak] is the KMS Public Authentication Key; identities are the octets of a user's UID.
 */
class Eccsi(val kpak: EcPoint) {
    init { require(P256.isOnCurve(kpak)) { "invalid KMS authentication key" } }

    /** HS = hash(G ‖ KPAK ‖ ID ‖ PVT), as an integer. */
    fun identityHash(id: ByteArray, pvt: EcPoint): BigInteger =
        BigInteger(1, sha256(P256.encode(G), P256.encode(kpak), id, P256.encode(pvt)))

    /** §5.1.2: the (SSK, PVT) pair a KMS provisioned for [id] is genuine: KPAK = [SSK]G − [HS]PVT. */
    fun validate(id: ByteArray, ssk: BigInteger, pvt: EcPoint): Boolean {
        if (!P256.isOnCurve(pvt) || ssk.signum() <= 0 || ssk >= Q) return false
        val hs = identityHash(id, pvt)
        return P256.add(P256.multiply(G, ssk, 256), P256.negate(P256.multiply(pvt, hs.mod(Q), 256))) == kpak
    }

    /** §5.2.1: r ‖ s ‖ PVT, 129 octets. */
    fun sign(id: ByteArray, ssk: BigInteger, pvt: EcPoint, message: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val hs = identityHash(id, pvt)
        while (true) {
            val j = BigInteger(256, random).mod(Q)
            if (j.signum() == 0) continue
            val r = P256.multiply(G, j, 256).x!!
            val rBytes = Curve.fixed(r, 32)
            val he = BigInteger(1, sha256(Curve.fixed(hs, 32), rBytes, message))
            val t = he.add(r.multiply(ssk)).mod(Q)
            if (t.signum() == 0) continue
            var s = t.modInverse(Q).multiply(j).mod(Q)
            if (s.bitLength() > 256) s = Q.subtract(s)
            return rBytes + Curve.fixed(s, 32) + P256.encode(pvt)
        }
    }

    /** §5.2.2, for the signer [id]. */
    fun verify(id: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 129) return false
        return try {
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            val pvt = P256.decode(signature.copyOfRange(64, 129))
            val hs = identityHash(id, pvt)
            val he = BigInteger(1, sha256(Curve.fixed(hs, 32), signature.copyOfRange(0, 32), message))
            val y = P256.add(P256.multiply(pvt, hs.mod(Q), 256), kpak)
            val j = P256.multiply(P256.add(P256.multiply(G, he.mod(Q), 256), P256.multiply(y, r.mod(Q), 256)), s.mod(Q), 256)
            !j.infinite && j.x!!.signum() != 0 && j.x == r.mod(P256.p)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    companion object {
        val P256 = Curve(
            Curve.hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"),
            Curve.hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"), 32)
        val Q: BigInteger = Curve.hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
        val G = EcPoint(Curve.hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
            Curve.hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"))

        fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    }
}
