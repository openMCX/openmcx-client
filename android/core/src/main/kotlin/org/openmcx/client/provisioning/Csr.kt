package org.openmcx.client.provisioning

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A PKCS#10 certification request (RFC 2986) for an EC key signed with ECDSA and SHA-256,
 * built without a library: the Credential Service certifies the key only (MCX Admin
 * slice F2), so the request carries a common name, the public key and no attributes.
 * [sign] signs the CertificationRequestInfo with the private key (in the Android
 * Keystore, which never gives it out) and returns the DER ECDSA signature.
 */
object Csr {
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03) // 2.5.4.3
    private val ECDSA_WITH_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02) // 1.2.840.10045.4.3.2

    fun build(commonName: String, subjectPublicKeyInfo: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        val name = tlv(0x30, tlv(0x31, tlv(0x30, tlv(0x06, COMMON_NAME) + tlv(0x0C, commonName.toByteArray()))))
        val info = tlv(0x30, tlv(0x02, byteArrayOf(0)) + name + subjectPublicKeyInfo + tlv(0xA0, ByteArray(0)))
        val signature = sign(info)
        return tlv(0x30, info + tlv(0x30, tlv(0x06, ECDSA_WITH_SHA256)) + tlv(0x03, byteArrayOf(0) + signature))
    }

    fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE REQUEST-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END CERTIFICATE REQUEST-----\n"

    /** A DER type-length-value. */
    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
