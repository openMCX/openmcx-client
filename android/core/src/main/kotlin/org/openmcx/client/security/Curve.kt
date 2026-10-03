package org.openmcx.client.security

import java.math.BigInteger

/** An affine point; [x] and [y] null for the point at infinity. */
data class EcPoint(val x: BigInteger?, val y: BigInteger?) {
    val infinite get() = x == null

    companion object {
        val INFINITY = EcPoint(null, null)
    }
}

/**
 * A short Weierstrass curve y² = x³ − 3x + [b] over F_p, as both ECCSI (NIST P-256, RFC 6507) and SAKKE
 * (the supersingular curve of RFC 6508, b = 0) use. Arithmetic in Jacobian coordinates; scalar
 * multiplication is a Montgomery ladder, the same sequence of operations for every scalar of a size.
 */
class Curve(val p: BigInteger, val b: BigInteger, private val octets: Int) {
    private val three = BigInteger.valueOf(3)

    fun isOnCurve(pt: EcPoint): Boolean {
        if (pt.infinite) return false
        val x = pt.x!!; val y = pt.y!!
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        return y.multiply(y).mod(p) == x.pow(3).subtract(three.multiply(x)).add(b).mod(p)
    }

    /** RFC 5480 uncompressed: 0x04 ‖ x ‖ y, each [octets] long. */
    fun encode(pt: EcPoint): ByteArray {
        require(!pt.infinite) { "the point at infinity has no encoding" }
        return byteArrayOf(4) + fixed(pt.x!!, octets) + fixed(pt.y!!, octets)
    }

    fun decode(bytes: ByteArray): EcPoint {
        require(bytes.size == 2 * octets + 1 && bytes[0].toInt() == 4) { "invalid EC point octet encoding" }
        val pt = EcPoint(BigInteger(1, bytes.copyOfRange(1, 1 + octets)), BigInteger(1, bytes.copyOfRange(1 + octets, bytes.size)))
        require(isOnCurve(pt)) { "point not on the curve" }
        return pt
    }

    private class Jacobian(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val infinite get() = z.signum() == 0
    }

    private fun jacobian(pt: EcPoint) = if (pt.infinite) Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO) else Jacobian(pt.x!!, pt.y!!, BigInteger.ONE)

    private fun affine(j: Jacobian): EcPoint {
        if (j.infinite) return EcPoint.INFINITY
        val zi = j.z.modInverse(p)
        val zi2 = zi.multiply(zi).mod(p)
        return EcPoint(j.x.multiply(zi2).mod(p), j.y.multiply(zi2).multiply(zi).mod(p))
    }

    /** Doubling with a = −3 (dbl-2001-b). */
    private fun double(a: Jacobian): Jacobian {
        if (a.infinite || a.y.signum() == 0) return Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val delta = a.z.multiply(a.z).mod(p)
        val gamma = a.y.multiply(a.y).mod(p)
        val beta = a.x.multiply(gamma).mod(p)
        val alpha = three.multiply(a.x.subtract(delta)).multiply(a.x.add(delta)).mod(p)
        val x3 = alpha.multiply(alpha).subtract(beta.shiftLeft(3)).mod(p)
        val z3 = a.y.add(a.z).pow(2).subtract(gamma).subtract(delta).mod(p)
        val y3 = alpha.multiply(beta.shiftLeft(2).subtract(x3)).subtract(gamma.multiply(gamma).shiftLeft(3)).mod(p)
        return Jacobian(x3, y3, z3)
    }

    private fun add(a: Jacobian, c: Jacobian): Jacobian {
        if (a.infinite) return c
        if (c.infinite) return a
        val z1z1 = a.z.multiply(a.z).mod(p)
        val z2z2 = c.z.multiply(c.z).mod(p)
        val u1 = a.x.multiply(z2z2).mod(p)
        val u2 = c.x.multiply(z1z1).mod(p)
        val s1 = a.y.multiply(c.z).multiply(z2z2).mod(p)
        val s2 = c.y.multiply(a.z).multiply(z1z1).mod(p)
        if (u1 == u2) return if (s1 == s2) double(a) else Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val h = u2.subtract(u1).mod(p)
        val i = h.shiftLeft(1).pow(2).mod(p)
        val j = h.multiply(i).mod(p)
        val r = s2.subtract(s1).shiftLeft(1).mod(p)
        val v = u1.multiply(i).mod(p)
        val x3 = r.multiply(r).subtract(j).subtract(v.shiftLeft(1)).mod(p)
        val y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(j).shiftLeft(1)).mod(p)
        val z3 = a.z.add(c.z).pow(2).subtract(z1z1).subtract(z2z2).multiply(h).mod(p)
        return Jacobian(x3, y3, z3)
    }

    fun add(a: EcPoint, c: EcPoint): EcPoint = affine(add(jacobian(a), jacobian(c)))

    fun negate(a: EcPoint): EcPoint = if (a.infinite) a else EcPoint(a.x, p.subtract(a.y!!).mod(p))

    /** [k]pt by a Montgomery ladder over [bits] bits (at least k's length). */
    fun multiply(pt: EcPoint, k: BigInteger, bits: Int = maxOf(k.bitLength(), 1)): EcPoint {
        require(k.signum() >= 0) { "negative scalar" }
        var r0 = Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var r1 = jacobian(pt)
        for (i in maxOf(bits, k.bitLength()) - 1 downTo 0) {
            if (k.testBit(i)) { r0 = add(r0, r1); r1 = double(r1) } else { r1 = add(r0, r1); r0 = double(r0) }
        }
        return affine(r0)
    }

    companion object {
        /** [value] as exactly [length] big-endian octets. */
        fun fixed(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            return when {
                raw.size == length -> raw
                raw.size == length + 1 && raw[0].toInt() == 0 -> raw.copyOfRange(1, raw.size)
                raw.size < length -> ByteArray(length - raw.size) + raw
                else -> throw IllegalArgumentException("integer longer than $length octets")
            }
        }

        fun hex(text: String): BigInteger = BigInteger(text.filterNot { it.isWhitespace() }, 16)
    }
}
