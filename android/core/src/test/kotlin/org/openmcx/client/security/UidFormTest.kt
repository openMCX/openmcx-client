//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * The MIKEY-SAKKE UID form (TS 33.180 annex F.2.1.1 does not say whether the 32 octets or their base64 text enters
 * ECCSI and SAKKE; spec defect, Known limitations "MIKEY-SAKKE UID form") and key sets wrapped under a TrK: key sets
 * issued for either form by a test KMS (RFC 6507 5.1.1, RFC 6508 6.1.1) in a KMS KeyProvision document of the server's
 * keytool, found by the detection (raw first, then text), refused without it for the raw form; the same key sets
 * wrapped under a TrK with the test suite's padding.
 */
class UidFormTest {
    private val v = Json.parseToJsonElement(javaClass.getResource("/keytool-vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val now = v["ntp"]!!.jsonPrimitive.long
    private val user = "sip:alice.mcvideo@mcx.test" // the UserUri of the vectors' alice key set
    private val random = SecureRandom()

    // A test KMS: KSAK and KPAK (RFC 6507 5.1.1), z and Z_T (RFC 6508 6.1.1); the KMS URI and key period of the vectors.
    private val ksak = BigInteger(256, random).mod(Eccsi.Q)
    private val zSecret = BigInteger(256, random).mod(Sakke.Q)
    private val vectorKms = KmsDocuments.parseInit(s("init_xml"), s("init_user"), s("kms_uri"), s("init_request"), now)
    private val kms = vectorKms.copy(kpak = Eccsi.P256.multiply(Eccsi.G, ksak), z = Sakke.E.multiply(Sakke.P, zSecret))

    private class Issued(val ssk: BigInteger, val pvt: EcPoint, val rsk: EcPoint)

    /** RFC 6507 5.1.1: PVT = [v]G, SSK = KSAK + HS v; RFC 6508 6.1.1: RSK = [(b + z)^-1]P, both for the identifier [id]. */
    private fun issue(id: ByteArray): Issued {
        val v = BigInteger(256, random).mod(Eccsi.Q)
        val pvt = Eccsi.P256.multiply(Eccsi.G, v)
        val ssk = ksak.add(Eccsi(kms.kpak).identityHash(id, pvt).multiply(v)).mod(Eccsi.Q)
        val rsk = Sakke.E.multiply(Sakke.P, BigInteger(1, id).mod(Sakke.Q).add(zSecret).modInverse(Sakke.Q))
        return Issued(ssk, pvt, rsk)
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    /** The vectors' KeyProvision document for [user] with [keys], in clear or wrapped under [trk]. */
    private fun document(keys: Issued, trk: Xpk? = null, keyName: String? = trk?.keyName): String {
        var xml = s("alice_keyprov_xml")
        val values = mapOf("UserSigningKeySSK" to Curve.fixed(keys.ssk, 32), "UserPubTokenPVT" to Eccsi.P256.encode(keys.pvt),
            "UserDecryptKey" to Sakke.E.encode(keys.rsk))
        for ((name, value) in values) {
            val element = Regex("<$name xsi:type=\"KeyContentType\">[^<]*</$name>")
            val replacement = if (trk == null) "<$name xsi:type=\"KeyContentType\">${value.hex()}</$name>" else {
                val kek = TransportKeys.protectionKey(trk, user, TransportKeys.CONFIDENTIALITY)
                val wrapped = Base64.getEncoder().encodeToString(TransportKeys.wrapKeyMaterial(kek, value))
                "<$name xsi:type=\"EncKeyContentType\"><xenc:EncryptedKey xmlns:xenc=\"http://www.w3.org/2001/04/xmlenc#\">" +
                    "<xenc:EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#kw-aes256\"/>" +
                    "<ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:KeyName>$keyName</ds:KeyName></ds:KeyInfo>" +
                    "<xenc:CipherData><xenc:CipherValue>$wrapped</xenc:CipherValue></xenc:CipherData></xenc:EncryptedKey></$name>"
            }
            assertTrue(element.containsMatchIn(xml))
            xml = element.replace(xml, replacement)
        }
        return xml
    }

    private fun parse(xml: String, detect: Boolean, trk: Xpk? = null) =
        KmsDocuments.parseKeyProv(xml, kms, user, s("alice_keyprov_request"), now, trk?.let { UserTransportKeys(it, null) }, detect)

    private val rawId get() = Mikey.uidDigest(user, kms, now)
    private val textId get() = Mikey.uid(user, kms, now).toByteArray()

    @Test fun textKeysAreFoundAsText() {
        val doc = document(issue(textId))
        assertEquals(UidForm.TEXT, parse(doc, detect = true).uidForm)
        assertEquals(UidForm.TEXT, parse(doc, detect = false).uidForm)
    }

    @Test fun rawKeysAreFoundOnlyWithDetection() {
        val doc = document(issue(rawId))
        assertEquals(UidForm.RAW, parse(doc, detect = true).uidForm)
        assertThrows(KeyError::class.java) { parse(doc, detect = false) }
        // A key set for neither form is refused.
        assertThrows(KeyError::class.java) { parse(document(issue(ByteArray(32) { 7 })), detect = true) }
    }

    /** Under a RAW KMS every ECCSI and SAKKE operation takes the 32 octets (Mikey.identifier); under TEXT the text. */
    @Test fun identifierFollowsTheForm() {
        assertArrayEquals(rawId, Mikey.identifier(user, kms.copy(uidForm = UidForm.RAW), now))
        assertArrayEquals(textId, Mikey.identifier(user, kms, now))
        assertEquals(Base64.getEncoder().encodeToString(rawId), Mikey.uid(user, kms, now))
        val keys = issue(rawId)
        val id = Mikey.identifier(user, kms.copy(uidForm = UidForm.RAW), now)
        val signature = Eccsi(kms.kpak).sign(id, keys.ssk, keys.pvt, "message".toByteArray())
        assertTrue(Eccsi(kms.kpak).verify(id, "message".toByteArray(), signature))
        val ssv = ByteArray(16) { it.toByte() }
        assertArrayEquals(ssv, Sakke(kms.z).decapsulate(id, keys.rsk, Sakke(kms.z).encapsulate(id, ssv)))
    }

    /** TS 33.180 D.3.3.2: a key set wrapped under the TrK (F.1.4 FC 0x51, the test suite's padding). */
    @Test fun keySetWrappedUnderTheTrk() {
        val trk = Xpk(ByteArray(32) { (it * 3).toByte() }, 0x0a0b0c0d)
        val doc = document(issue(rawId), trk)
        assertEquals(UidForm.RAW, parse(doc, detect = true, trk = trk).uidForm)
        assertThrows(KeyError::class.java) { parse(doc, detect = true) }                                   // no TrK
        assertThrows(KeyError::class.java) { parse(doc, detect = true, trk = Xpk(trk.key, 0x01020304)) }   // another TrK-ID
        assertThrows(KeyError::class.java) { parse(doc, detect = true, trk = Xpk(ByteArray(32), 0x0a0b0c0d)) } // another key
        assertThrows(KeyError::class.java) { parse(document(issue(rawId), trk, keyName = "AAAAAA=="), detect = true, trk = trk) }
    }
}
