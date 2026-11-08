//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KMS exchange with pre-provisioned transport keys (TS 33.180 clause 5.3.2, annexes D.1, D.2.2, D.3.1), as the
 * shared C++ core does it: the SignedKmsRequest under the InK, else the TrK; a SignedKmsResponse verified under the key
 * its KeyName names; a signature refused without transport keys (TLS profile); the xsd:dateTime of the Time element.
 */
class KmsExchangeTest {
    private val v = Json.parseToJsonElement(javaClass.getResource("/keytool-vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val now = v["ntp"]!!.jsonPrimitive.long
    private val user = s("init_user")
    private val trk = Xpk(ByteArray(32) { (it * 7 + 1).toByte() }, 0x11223344)
    private val ink = Xpk(ByteArray(16) { (it * 5 + 3).toByte() }, 0x55667788)

    /** The vector's KmsResponse signed under [xpk] as a KMS with transport keys sends it (Id "kmsResponse"). */
    private fun signedInit(xpk: XpkKey, keyName: String = xpk.keyName): String {
        val payload = s("init_xml").substringAfter("?>").replaceFirst("<KmsResponse ", "<KmsResponse Id=\"kmsResponse\" ")
        val key = TransportKeys.protectionKey(xpk, user, TransportKeys.INTEGRITY)
        return KmsSignature.sign(payload, key, keyName)
    }

    private fun init(xml: String, keys: UserTransportKeys?) =
        KmsDocuments.parseInit(xml, user, s("kms_uri"), s("init_request"), now, keys)

    @Test fun signedResponseVerifiesUnderTheInkElseTheTrk() {
        assertEquals(s("kms_uri"), init(signedInit(ink), UserTransportKeys(trk, ink)).kmsUri)
        assertEquals(s("kms_uri"), init(signedInit(trk), UserTransportKeys(trk, null)).kmsUri)
        // Signed under the TrK while the user has an InK: the InK is the integrity key, so it is refused.
        assertThrows(KeyError::class.java) { init(signedInit(trk), UserTransportKeys(trk, ink)) }
    }

    @Test fun signatureRefusedWithoutTransportKeys() {
        assertThrows(KeyError::class.java) { init(signedInit(trk), null) }
        // The unsigned response stays accepted, with or without keys (the transport authenticates the KMS).
        init(s("init_xml"), null)
        init(s("init_xml"), UserTransportKeys(trk, ink))
    }

    @Test fun signatureUnderAnotherKeyOrTamperedIsRefused() {
        assertThrows(KeyError::class.java) { init(signedInit(trk, keyName = ink.keyName), UserTransportKeys(trk, null)) }
        assertThrows(KeyError::class.java) { init(signedInit(Xpk(ByteArray(32), trk.id)), UserTransportKeys(trk, null)) }
        val tampered = signedInit(trk).replace("<UserIdFormat>2</UserIdFormat>", "<UserIdFormat>2</UserIdFormat><!---->")
            .replace("https://kms:8443/keymanagement/identity/v1/init</ClientReqUrl>", "https://kms:8443/keymanagement/identity/v1/init </ClientReqUrl>")
        assertThrows(KeyError::class.java) { init(tampered, UserTransportKeys(trk, null)) }
        // A signature nested inside an unsigned response is refused, never skipped.
        val nested = s("init_xml").replace("<KmsMessage>",
            "<KmsMessage><ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"/>")
        assertThrows(KeyError::class.java) { init(nested, UserTransportKeys(trk, null)) }
    }

    @Test fun requestIsSignedUnderTheIntegrityKey() {
        val request = "https://kms:8443/keymanagement/identity/v1/init"
        val xml = KmsDocuments.requestXml(user, "https://kms:8443", request, now, UserTransportKeys(trk, ink))
        assertEquals("SignedKmsRequest", KmsSignature.parse(xml).documentElement.localName)
        assertEquals(ink.keyName, KmsSignature.keyName(xml))
        val payload = KmsSignature.verifyPayload(xml, TransportKeys.protectionKey(ink, user, TransportKeys.INTEGRITY))
        assertTrue(payload, payload.startsWith("<KmsRequest xmlns=\"urn:3gpp:ns:mcsecKMSInterface:1.0\" Id=\"xmldoc\" Version=\"1.1.0\">"))
        assertTrue(payload.contains("<UserUri>$user</UserUri><KmsUri>https://kms:8443</KmsUri><Time>${KmsDocuments.date(now)}</Time>" +
            "<ClientReqUrl>$request</ClientReqUrl>"))
        assertThrows(KeyError::class.java) {
            KmsSignature.verifyPayload(xml, TransportKeys.protectionKey(trk, user, TransportKeys.INTEGRITY))
        }
        // A TrK alone signs; no key, no signed request.
        assertEquals(trk.keyName, KmsSignature.keyName(
            KmsDocuments.requestXml(user, "https://kms:8443", request, now, UserTransportKeys(trk, null))))
        assertThrows(KeyError::class.java) { KmsDocuments.requestXml(user, "https://kms:8443", request, now, UserTransportKeys(null, null)) }
    }

    /** XML Schema Part 2 clause 3.2.7 with a time zone, as the shared core's timevalue. */
    @Test fun dateTimeNeedsAZone() {
        val base = KmsDocuments.time("2026-09-28T05:03:38Z")
        assertEquals("2026-09-28T05:03:38Z", KmsDocuments.date(base))
        assertEquals(base, KmsDocuments.time("2026-09-28T07:03:38+02:00"))
        assertEquals(base, KmsDocuments.time("2026-09-28T04:33:38.250-00:30"))
        assertEquals(base, KmsDocuments.time("2026-09-28T19:03:38+14:00"))
        for (bad in listOf("2026-09-28T05:03:38", "2026-09-28T05:03:38+14:30", "2026-09-28T05:03:38+02:60", "2026-02-30T05:03:38Z",
                "2026-09-28T24:00:00Z", "2026-09-28T05:03:38+0200", "2026-09-28 05:03:38Z", "2026-09-28T05:03:38.Z"))
            assertThrows(bad, KeyError::class.java) { KmsDocuments.time(bad) }
    }

    @Test fun profileTransportKeys() {
        val keys = Json.parseToJsonElement("""{"sip:alice.mcptt@mcx.test": {"trk": {"key": "${"ab".repeat(32)}", "id": 4294967295},
            "ink": {"key": "${"cd".repeat(16)}", "id": 7}}, "sip:bob.mcptt@mcx.test": {"trk": {"key": "${"01".repeat(32)}", "id": 0}}}""").jsonObject
        val entries = TransportKeyEntries.parse(keys)
        assertEquals(listOf("trk" to 4294967295L, "ink" to 7L, "trk" to 0L), entries.map { it.kind to it.id })
        assertEquals(listOf(32, 16, 32), entries.map { it.key.size })
        for (bad in listOf("""{"u": {"trk": {"key": "${"ab".repeat(16)}", "id": 1}}}""", // a 128-bit TrK
                """{"u": {"ink": {"key": "${"ab".repeat(24)}", "id": 1}}}""",              // a 192-bit InK
                """{"u": {"trk": {"key": "${"zz".repeat(32)}", "id": 1}}}""",
                """{"u": {"trk": {"key": "${"ab".repeat(32)}", "id": 4294967296}}}""",
                """{"u": {"trk": {"key": "${"ab".repeat(32)}", "id": "1"}}}""",
                """{"u": {"tk": {"key": "${"ab".repeat(32)}", "id": 1}}}""",
                """{"u": {}}"""))
            assertThrows(bad, IllegalArgumentException::class.java) { TransportKeyEntries.parse(Json.parseToJsonElement(bad).jsonObject) }
    }

    /** Clause 5.3.2: through the HTTP proxy "the TrK shall be used": without one the exchange is refused before any request. */
    @Test fun kmsThroughTheProxyNeedsATrk() {
        var requests = 0
        val http = object : org.openmcx.client.http.HttpClient {
            override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): org.openmcx.client.http.HttpResponse {
                requests++; throw IllegalStateException("no KMS here")
            }
        }
        fun client(keys: UserTransportKeys?) = KeyClient(http, "https://kms:8443", user, user, WireProfile(10, 1, 0x79, 0x78),
            transportKeys = { keys }, viaProxy = true)
        for (keys in listOf(null, UserTransportKeys(null, ink))) {
            val e = assertThrows(KeyError::class.java) { kotlinx.coroutines.runBlocking { client(keys).provision("token") } }
            assertTrue(e.message!!.contains("needs a TrK"))
        }
        assertEquals(0, requests)
        // With a TrK the exchange goes ahead (here to a KMS that cannot be reached).
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { client(UserTransportKeys(trk, ink)).provision("token") } }
        assertEquals(1, requests)
    }
}
