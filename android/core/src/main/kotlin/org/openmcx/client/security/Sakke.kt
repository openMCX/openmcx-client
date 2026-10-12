//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SAKKE (RFC 6508) with parameter set 1 of RFC 6509 (a 1024-bit p, SHA-256, n = 128), as MIKEY-SAKKE
 * uses it to carry a 128-bit key to one identity. [z] is the KMS Public Key Z; identities are the
 * octets of a user's UID, read as a big-endian integer where the algorithm needs one.
 */
class Sakke(val z: EcPoint) {
    init { require(E.isOnCurve(z)) { "invalid KMS encryption key" } }

    /** §6.2.1: the Encapsulated Data (R ‖ H, 273 octets) carrying [ssv] to [id]. */
    fun encapsulate(id: ByteArray, ssv: ByteArray): ByteArray {
        require(ssv.size == 16) { "the SSV is 128 bits" }
        val r = hashToRange(ssv + id, Q)
        val rb = E.multiply(E.add(E.multiply(P, BigInteger(1, id).mod(Q)), z), r)
        val mask = Curve.fixed(hashToRange(pfEncode(pfPow(G_REPR, r)), TWO_128), 16)
        return E.encode(rb) + ByteArray(16) { (ssv[it].toInt() xor mask[it].toInt()).toByte() }
    }

    /** §6.2.2: the SSV that [data] carries to [id], whose Receiver Secret Key is [rsk]; null if it fails the check. */
    fun decapsulate(id: ByteArray, rsk: EcPoint, data: ByteArray): ByteArray? {
        if (data.size != 273) return null
        val rb = try { E.decode(data.copyOfRange(0, 257)) } catch (e: IllegalArgumentException) { return null }
        val w = pairing(rb, rsk)
        val mask = Curve.fixed(hashToRange(pfEncode(w), TWO_128), 16)
        val ssv = ByteArray(16) { (data[257 + it].toInt() xor mask[it].toInt()).toByte() }
        val r = hashToRange(ssv + id, Q)
        val test = E.multiply(E.add(E.multiply(P, BigInteger(1, id).mod(Q)), z), r)
        return if (test == rb) ssv else null
    }

    /** §6.1.2: the RSK a KMS provisioned for [id] is genuine: <[a]P + Z, K> = g. */
    fun validate(id: ByteArray, rsk: EcPoint): Boolean =
        E.isOnCurve(rsk) && pairing(E.add(E.multiply(P, BigInteger(1, id).mod(Q)), z), rsk) == G_REPR

    companion object {
        val PRIME = Curve.hex("""
            997ABB1F0A563FDA65C61198DAD0657A416C0CE19CB48261BE9AE358B3E01A2EF40AAB27E2FC0F1B228730D531A59CB0
            E791B39FF7C88A19356D27F4A666A6D0E26C6487326B4CD4512AC5CD65681CE1B6AFF4A831852A82A7CF3C521C3C09AA
            9F94D6AF56971F1FFCE3E82389857DB080C5DF10AC7ACE87666D807AFEA85FEB""")
        val Q = Curve.hex("""
            265EAEC7C2958FF699718466 36B4195E905B0338672D20986FA6B8D62CF8068BBD02AAC9F8BF03C6C8A1CC354C69672C
            39E46CE7FDF222864D5B49FD2999A9B4389B1921CC9AD335144AB173595A07386DABFD2A0C614AA0A9F3CF14870F026A
            A7E535ABD5A5C7C7FF38FA08E2615F6C203177C42B1EB3A1D99B601EBFAA17FB""")
        val E = Curve(PRIME, BigInteger.ZERO, 128)
        val P = EcPoint(Curve.hex("""
            53FC09EE332C29AD0A7990053ED9B52A2B1A2FD60AEC69C698B2F204B6FF7CBFB5EDB6C0F6CE2308AB10DB9030B09E10
            43D5F22CDB9DFA55718BD9E7406CE8909760AF765DD5BCCB337C86548B72F2E1A702C3397A60DE74A7C1514DBA66910D
            D5CFB4CC80728D87EE9163A5B63F73EC80EC46C4967E0979880DC8ABEAE63895"""), Curve.hex("""
            0A8249063F6009F1F9F1F0533634A135D3E82016029906963D778D821E141178F5EA69F4654EC2B9E7F7F5E5F0DE55F6
            6B598CCF9A140B2E416CFF0CA9E032B970DAE117AD547C6CCAD696B5B7652FE0AC6F1E80164AA989492D979FC5A4D5F2
            13515AD7E9CB99A980BDAD5AD5BB4636ADB9B5706A67DCDE75573FD71BEF16D7"""))
        /** g = <P,P>, as its representative in F_p. */
        val G_REPR = Curve.hex("""
            66FC2A432B6EA392148F15867D623068C6A87BD1FB94C41E27FABE658E015A87371E94744C96FEDA449AE9563F8BC446
            CBFDA85D5D00EF577072DA8F541721BEEE0FAED1828EAB90B99DFB0138C7843355DF0460B4A9FD74B4F1A32BCAFA1FFA
            D682C033A7942BCCE3720F20B9B7B0403C8CAE87B7A0042ACDE0FAB36461EA46""")
        private val TWO_128 = BigInteger.ONE.shiftLeft(128)
        private val THREE = BigInteger.valueOf(3)

        /** An element a + ib of F_p² = F_p[i], i² = −1. */
        private class Fp2(val a: BigInteger, val b: BigInteger) {
            fun times(o: Fp2) = Fp2(a.multiply(o.a).subtract(b.multiply(o.b)).mod(PRIME), a.multiply(o.b).add(b.multiply(o.a)).mod(PRIME))
            fun square() = times(this)
            fun pow(e: BigInteger): Fp2 {
                var result = Fp2(BigInteger.ONE, BigInteger.ZERO)
                for (i in e.bitLength() - 1 downTo 0) { result = result.square(); if (e.testBit(i)) result = result.times(this) }
                return result
            }
            /** §3.2: the representative b/a of this element of PF_p. */
            fun representative(): BigInteger = b.multiply(a.modInverse(PRIME)).mod(PRIME)
        }

        /** §3.2: the Tate-Lichtenbaum pairing <R,Q>, as its representative in F_p. */
        fun pairing(r: EcPoint, q: EcPoint): BigInteger {
            val p = PRIME
            var v = Fp2(BigInteger.ONE, BigInteger.ZERO)
            var cx = r.x!!; var cy = r.y!!
            val rx = r.x; val ry = r.y!!
            val qx = q.x!!; val qy = q.y!!
            val qm1 = Q.subtract(BigInteger.ONE)
            for (i in qm1.bitLength() - 2 downTo 0) {
                // Tangent at C, evaluated at [i]Q = (−Q_x, iQ_y).
                var l = THREE.multiply(cx.multiply(cx).subtract(BigInteger.ONE)).multiply(cy.shiftLeft(1).modInverse(p)).mod(p)
                v = v.square().times(Fp2(l.multiply(qx.add(cx)).subtract(cy).mod(p), qy))
                val x2 = l.multiply(l).subtract(cx.shiftLeft(1)).mod(p)
                cy = l.multiply(cx.subtract(x2)).subtract(cy).mod(p); cx = x2
                if (qm1.testBit(i)) {
                    // The line through C and R.
                    l = cy.subtract(ry).multiply(cx.subtract(rx).modInverse(p)).mod(p)
                    v = v.times(Fp2(l.multiply(qx.add(cx)).subtract(cy).mod(p), qy))
                    val x3 = l.multiply(l).subtract(cx).subtract(rx).mod(p)
                    cy = l.multiply(cx.subtract(x3)).subtract(cy).mod(p); cx = x3
                }
            }
            return v.pow(p.add(BigInteger.ONE).divide(Q)).representative()
        }

        /** g^r in PF_p[q], for g represented by [g]: (1 + ig)^r, then its representative. */
        fun pfPow(g: BigInteger, r: BigInteger): BigInteger = Fp2(BigInteger.ONE, g).pow(r).representative()

        private fun pfEncode(value: BigInteger) = Curve.fixed(value, 128)

        /** §5.1: HashToIntegerRange(s, n, SHA-256). */
        fun hashToRange(s: ByteArray, n: BigInteger): BigInteger {
            val sha = { data: ByteArray -> MessageDigest.getInstance("SHA-256").digest(data) }
            val a = sha(s)
            var h = ByteArray(32)
            val blocks = (n.bitLength() + 255) / 256
            val v = java.io.ByteArrayOutputStream()
            repeat(maxOf(blocks, 1)) { h = sha(h); v.write(sha(h + a)) }
            return BigInteger(1, v.toByteArray()).mod(n)
        }
    }
}
