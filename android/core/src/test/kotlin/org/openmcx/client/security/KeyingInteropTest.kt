//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against key material and messages made by the OpenMCX server's own implementation (mcx-keytool,
 * wolfCrypt): its KMS documents, and a GMK I_MESSAGE with key properties sent to bob by a GMS.
 */
class KeyingInteropTest {
    private val v = Json.parseToJsonElement(javaClass.getResource("/keytool-vectors.json")!!.readText()).jsonObject
    private fun JsonObject.getString(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.getLong(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.getInt(k: String) = this[k]!!.jsonPrimitive.int
    private fun JsonObject.getJSONObject(k: String) = this[k]!!.jsonObject
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val now = v.getLong("ntp")
    private val kmsUri = v.getString("kms_uri")
    private val profile = v.getJSONObject("profile").let {
        WireProfile(it.getInt("message_type"), it.getInt("algorithm"), it.getInt("key_parameters_iei"), it.getInt("encrypted_payload_iei"))
    }
    private val kms = KmsDocuments.parseInit(v.getString("init_xml"), v.getString("init_user"), kmsUri, v.getString("init_request"), now)
    private fun keys(name: String, user: String) =
        KmsDocuments.parseKeyProv(v.getString("${name}_keyprov_xml"), kms, user, v.getString("${name}_keyprov_request"), now)

    @Test fun kmsDocumentsParseAndKeysValidate() {
        val pub = v.getJSONObject("public")
        assertArrayEquals(hex(pub.getString("authentication_key")), Eccsi.P256.encode(kms.kpak))
        assertArrayEquals(hex(pub.getString("encryption_key")), Sakke.E.encode(kms.z))
        assertEquals(2592000L, kms.period)
        keys("alice", "sip:alice.mcvideo@mcx.test") // validates the ECCSI pair and the SAKKE RSK
        assertEquals("https://kms:8443/keymanagement/identity/v1/keyprov/sip%3Abob.mcvideo%40mcx.test", v.getString("bob_keyprov_request"))
        assertEquals(v.getString("bob_keyprov_request").substringAfterLast('/'), KeyClient.urlEncode("sip:bob.mcvideo@mcx.test"))
    }

    @Test fun opensTheServersGmkMessage() {
        val bob = keys("bob", "sip:bob.mcvideo@mcx.test")
        val message = KmsDocuments.parseGktp(v.getString("gktp_document"), v.getString("group"), now)
        val key = Mikey.open(kms, bob, "sip:gms@mcx.test", message, now, profile)
        assertArrayEquals(hex(v.getString("gmk_hex")), key.key)
        assertArrayEquals(hex(v.getString("gmk_random_hex")), key.random)
        assertEquals(0, key.purpose)
        assertNotNull(key.properties)
        val p = key.properties!!
        assertEquals(0, p.type)
        assertEquals(listOf(v.getString("group")), p.groups)
        assertTrue(p.expiry > p.activation)
    }

    /** The GKTP as the MCVideo server notifies it (an RFC 5874 xcap-diff); the CSB-ID turns into the GMK-ID (F.1.3). */
    @Test fun gktpInAnXcapDiff() {
        val bob = keys("bob", "sip:bob.mcvideo@mcx.test")
        val group = v.getString("group")
        val doc = v.getString("gktp_document")
        val gktps = doc.substring(doc.indexOf("<mgktp:GKTPs>"), doc.indexOf("</mgktp:GKTPs>") + "</mgktp:GKTPs>".length)
            .replaceFirst("<mgktp:GKTPs>", "<mgktp:GKTPs xmlns:mgktp=\"urn:3gpp:ns:mcpttGKTP:1.0\">")
        val root = "https://gms:8443/xcap-root/"
        val diff = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><xcap-diff xmlns=\"urn:ietf:params:xml:ns:xcap-diff\" xcap-root=\"$root\">" +
            "<element sel=\"" + KmsDocuments.gktpSelector(group).replace("&", "&amp;") + "\" exists=\"true\">$gktps</element></xcap-diff>"
        val message = KmsDocuments.parseGktpXcapDiff(diff, group, root, now)
        val key = Mikey.open(kms, bob, "sip:gms@mcx.test", message, now, profile)
        assertEquals(v.getLong("gmk_id"), SrtpKeys.gukId(key.key, key.csbId, bob.uri))
        assertTrue(runCatching { KmsDocuments.parseGktpXcapDiff(diff, "sip:other@mcx.test", root, now) }.isFailure)
        assertEquals("org.3gpp.MCPTT-GKTP/global/byGroupID/sip%3Aoperations%40mcx.test/~~/group/list-service/mgktp:GKTPs?xmlns(mgktp=urn:3gpp:ns:mcpttGKTP:1.0)",
            KmsDocuments.gktpSelector(group))
    }

    @Test fun ourPckRoundTrips() {
        val alice = keys("alice", "sip:alice.mcvideo@mcx.test")
        val bob = keys("bob", "sip:bob.mcvideo@mcx.test")
        val pck = Mikey.newKey(1, now)
        val message = Mikey.create(kms, alice, bob.uri, pck)
        val env = Mikey.envelope(message)
        assertEquals(alice.uri, env.initiator); assertEquals(bob.uri, env.receiver); assertEquals(pck.csbId, env.csbId)
        val opened = Mikey.open(kms, bob, alice.uri, message, now + 10)
        assertArrayEquals(pck.key, opened.key)
        assertArrayEquals(pck.random, opened.random)
        val tampered = message.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        assertTrue(runCatching { Mikey.open(kms, bob, alice.uri, tampered, now) }.isFailure)
        assertTrue(runCatching { Mikey.open(kms, bob, alice.uri, message, now + 400) }.exceptionOrNull() is KeyError)
    }

    @Test fun ourCskCarriesProperties() {
        val alice = keys("alice", "sip:alice.mcvideo@mcx.test")
        val server = keys("bob", "sip:bob.mcvideo@mcx.test")
        val csk = Mikey.newKey(2, now, KeyProperties(2, 1, now - Mikey.NTP_EPOCH, now - Mikey.NTP_EPOCH + 3600))
        val opened = Mikey.open(kms, server, alice.uri, Mikey.create(kms, alice, server.uri, csk, profile), now, profile)
        assertEquals(csk.properties, opened.properties)
    }

    /** A GMK from alice to the GMS identity, as a GKTP document: tools feed it to mcx-keytool gktp-open (the reverse check). */
    @Test fun writesAMessageForTheServerToOpen() {
        val alice = keys("alice", "sip:alice.mcvideo@mcx.test")
        val group = v.getString("group")
        val gmk = Mikey.newKey(0, now, KeyProperties(0, 1, now - Mikey.NTP_EPOCH - 60, now - Mikey.NTP_EPOCH + 3600, "", listOf(group)))
        val message = Mikey.create(kms, alice, "sip:gms@mcx.test", gmk, profile)
        val hex = message.joinToString("") { "%02x".format(it) }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><group xmlns=\"urn:oma:xml:poc:list-service\" xmlns:mgktp=\"urn:3gpp:ns:mcpttGKTP:1.0\">" +
            "<list-service uri=\"$group\"><mgktp:GKTPs><mgktp:GMK-GKTPs><mgktp:GKTP id=\"1\">$hex</mgktp:GKTP></mgktp:GMK-GKTPs></mgktp:GKTPs></list-service></group>"
        java.io.File("build/interop").mkdirs()
        java.io.File("build/interop/ours.json").writeText("{\"document\":\"" + doc.replace("\"", "\\\"") +
            "\",\"key_hex\":\"" + gmk.key.joinToString("") { "%02x".format(it) } + "\"}")
    }
}
