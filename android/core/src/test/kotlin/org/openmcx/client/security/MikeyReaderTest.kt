//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/**
 * The I_MESSAGE reader accepts what the specifications allow: NTP timestamps of every era (RFC 5905 section 6), the
 * V bit ignored (RFC 6509 4.1), a RAND longer than 16 octets (RFC 3830 6.11), SAKKE-to-self and the RFC 3830 general
 * extensions (TS 33.180 E.5, RFC 3830 6.15), every key type, Reserved IEs and any Protected Payload Date and Time in
 * the key parameters (TS 33.180 E.6), and a PCK with key parameters (E.1.2). The keys come from a test KMS that issues
 * them as RFC 6507 5.1.1 and RFC 6508 6.1.1 describe.
 */
class MikeyReaderTest {
    private val random = SecureRandom()
    private val ksak = BigInteger(256, random).mod(Eccsi.Q)
    private val zSecret = BigInteger(1024, random).mod(Sakke.Q)
    private val kms = KmsPublic("kms.example.org", 2592000, 0, Eccsi.P256.multiply(Eccsi.G, ksak, 256),
        Sakke.E.multiply(Sakke.P, zSecret))
    private val after = (1L shl 32) + 10 // 2036-02-07 06:28:26 UTC, NTP era 1
    private val profile = WireProfile(10, 1, 0x79, 0x78)

    /** RFC 6507 5.1.1 and RFC 6508 6.1.1: the SSK, PVT and RSK of [uri] for the key period of [ntp]. */
    private fun issue(uri: String, ntp: Long): IdentityKeys {
        val id = Mikey.uid(uri, kms, ntp).toByteArray()
        val v = BigInteger(256, random).mod(Eccsi.Q)
        val pvt = Eccsi.P256.multiply(Eccsi.G, v, 256)
        val ssk = ksak.add(Eccsi(kms.kpak).identityHash(id, pvt).multiply(v)).mod(Eccsi.Q)
        val rsk = Sakke.E.multiply(Sakke.P, BigInteger(1, id).mod(Sakke.Q).add(zSecret).modInverse(Sakke.Q))
        return IdentityKeys(uri, kms.periodNumber(ntp), ssk, pvt, rsk)
    }

    private val alice = issue("sip:alice@example.org", after)
    private val bob = issue("sip:bob@example.org", after)

    /** RFC 3830 5.2: the message with its signed part changed by [change], signed again by alice. */
    private fun resign(message: ByteArray, ntp: Long, change: (MutableList<Byte>) -> Unit): ByteArray {
        val body = message.copyOfRange(0, message.size - 129).toMutableList().also(change).toByteArray()
        return body + Eccsi(kms.kpak).sign(Mikey.uid(alice.uri, kms, ntp).toByteArray(), alice.ssk, alice.pvt, body)
    }

    /** RFC 3830 6.15: a general extension payload. */
    private fun extension(next: Int, type: Int, data: ByteArray): List<Byte> =
        (byteArrayOf(next.toByte(), type.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data).toList()

    /** Adds chained general extensions (the last with next payload SIGN) after the SAKKE payload (5 + 273 octets). */
    private fun addExtensions(body: MutableList<Byte>, extensions: List<Byte>) {
        body[body.size - 2 - 5 - 273] = 21
        body.addAll(body.size - 2, extensions)
    }

    /** Table E.6.1-1: Key Type, Status 1, Activation and Expiry 0, Text "k", the group IDs when given, then [extra]. */
    private fun keyParameters(type: Int, groups: List<String>?, extra: ByteArray = ByteArray(0)): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(type); content.write(byteArrayOf(0, 0, 0, 1)); content.write(ByteArray(10))
        content.write(byteArrayOf(0, 1, 'k'.code.toByte()))
        if (groups != null) {
            val list = ByteArrayOutputStream().apply {
                write(groups.size)
                for (g in groups) { write(0x7b); write(0); write(g.length); write(g.toByteArray()) }
            }.toByteArray()
            content.write(0); content.write(list.size); content.write(list)
        }
        content.write(extra)
        val bytes = content.toByteArray()
        return byteArrayOf(0x79, (bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }

    /** E.6.1: the key parameters sealed with a Date and Time, Payload ID and sequence unlike the MIKEY header's. */
    private fun sealed(key: KeyMessage, parameters: ByteArray) =
        Mikey.sealPayload(10, 12345, 7, 3, key.key, key.csbId, parameters, profile)

    private fun pck(ntp: Long, csb: Long = 0x10000042L, rand: ByteArray = ByteArray(16) { 0x27 }) =
        KeyMessage(ByteArray(16) { 0x31 }, rand, csb, ntp)

    private fun refused(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is KeyError)

    @Test fun ntpEraFromTheLocalClock() {
        assertEquals(0xfffffff6L, Mikey.ntpFromWire(0xfffffff6L, (1L shl 32) + 10))
        assertEquals((1L shl 32) + 5, Mikey.ntpFromWire(5, 0xfffffff0L))
        assertEquals(0xd6b43232L, Mikey.ntpFromWire(0xd6b43232L, 0xd6b43232L + 7)) // TS 33.180 D.2.4 example
        assertEquals((2L shl 32) + 10, Mikey.ntpFromWire(10, (2L shl 32) + 20))
    }

    @Test fun messagesAcrossTheEraBoundary() {
        val crossing = Mikey.create(kms, alice, bob.uri, pck((1L shl 32) - 10))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -10), crossing.copyOfRange(12, 16))
        assertEquals((1L shl 32) - 10, Mikey.open(kms, bob, alice.uri, crossing, after).ntpSeconds)
        val inEra1 = Mikey.create(kms, alice, bob.uri, pck((1L shl 32) + 5))
        assertEquals((1L shl 32) + 5, Mikey.open(kms, bob, alice.uri, inEra1, after).ntpSeconds)
        assertEquals((1L shl 32) + 5, Mikey.envelope(inEra1, after).ntpSeconds)
        refused { Mikey.open(kms, bob, alice.uri, inEra1, after + 400) }
    }

    @Test fun headerAndRand() {
        val ntp = (1L shl 32) + 5
        val message = Mikey.create(kms, alice, bob.uri, pck(ntp))
        val vSet = resign(message, ntp) { it[3] = (it[3].toInt() or 0x80).toByte() }
        assertArrayEquals(pck(ntp).key, Mikey.open(kms, bob, alice.uri, vSet, after).key)
        assertEquals(alice.uri, Mikey.envelope(vSet, after).initiator)
        refused { Mikey.open(kms, bob, alice.uri, resign(message, ntp) { it[3] = 2 }, after) }
        val long = pck(ntp, rand = ByteArray(32) { 0x5a })
        val longMessage = Mikey.create(kms, alice, bob.uri, long)
        assertArrayEquals(long.random, Mikey.open(kms, bob, alice.uri, longMessage, after).random)
        // RFC 3830 6.11: "RAND len ... SHOULD be at least 16": a 15-octet RAND is accepted and reported; an empty one is
        // refused.
        val warnings = mutableListOf<String>()
        val short = resign(message, ntp) { it[21] = 15; it.removeAt(22) }
        assertEquals(15, Mikey.open(kms, bob, alice.uri, short, after, warn = { warnings += it }).random.size)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("15 octets"))
        Mikey.open(kms, bob, alice.uri, message, after, warn = { warnings += it })
        assertEquals(1, warnings.size)
        val empty = resign(message, ntp) { it[21] = 0; repeat(16) { _ -> it.removeAt(22) } }
        refused { Mikey.open(kms, bob, alice.uri, empty, after) }
    }

    @Test fun clockSkew() {
        // RFC 3830 5.4: the allowable clock skew is "set by the local policy"; 300 s by default.
        val ntp = (1L shl 32) + 5
        val message = Mikey.create(kms, alice, bob.uri, pck(ntp))
        assertEquals(ntp, Mikey.open(kms, bob, alice.uri, message, ntp + 100).ntpSeconds)
        refused { Mikey.open(kms, bob, alice.uri, message, ntp + 100, skew = 60) }
        refused { Mikey.open(kms, bob, alice.uri, message, ntp + Mikey.DEFAULT_CLOCK_SKEW_S + 1) }
    }

    @Test fun generalExtensions() {
        val ntp = (1L shl 32) + 5
        val message = Mikey.create(kms, alice, bob.uri, pck(ntp))
        val self = ByteArray(278)
        val skipped = resign(message, ntp) {
            addExtensions(it, extension(21, 6, self) + extension(4, 0, byteArrayOf(1, 2, 3)))
        }
        assertArrayEquals(pck(ntp).key, Mikey.open(kms, bob, alice.uri, skipped, after).key)
        val unknown = resign(message, ntp) { addExtensions(it, extension(4, 9, byteArrayOf(1))) }
        refused { Mikey.open(kms, bob, alice.uri, unknown, after) }
    }

    @Test fun keyParameters() {
        val ntp = (1L shl 32) + 5
        val musik = pck(ntp, csb = 0x60000007L)
        val groups = listOf("sip:g@example.org")
        val base = Mikey.create(kms, alice, bob.uri, musik)
        val withReserved = resign(base, ntp) {
            val reserved = byteArrayOf(0x55, 0, 2, 0xaa.toByte(), 0xbb.toByte())
            val parameters = sealed(musik, keyParameters(6, groups, reserved))
            addExtensions(it, extension(21, 7, parameters) + extension(4, 6, ByteArray(278)))
        }
        val p = Mikey.open(kms, bob, alice.uri, withReserved, after, profile).properties!!
        assertEquals(6, p.type); assertEquals(groups, p.groups); assertEquals("k", p.text)
        refused {
            Mikey.open(kms, bob, alice.uri, resign(base, ntp) {
                val parameters = sealed(musik, keyParameters(6, groups))
                addExtensions(it, extension(21, 7, parameters) + extension(4, 7, parameters))
            }, after, profile)
        }
        val plain = pck(ntp)
        val pckMessage = resign(Mikey.create(kms, alice, bob.uri, plain), ntp) {
            addExtensions(it, extension(4, 7, sealed(plain, keyParameters(1, null))))
        }
        val opened = Mikey.open(kms, bob, alice.uri, pckMessage, after, profile)
        assertEquals(1, opened.properties!!.type)
        assertTrue(opened.properties!!.groups.isEmpty())
    }
}
