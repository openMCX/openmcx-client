//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.client.ClientConfig
import org.openmcx.client.security.GroupKeys
import org.openmcx.client.security.IdentityKeys
import org.openmcx.client.security.KeyClient
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KeyProperties
import org.openmcx.client.security.KmsDocuments
import org.openmcx.client.security.KmsPublic
import org.openmcx.client.security.Mikey
import org.openmcx.client.security.WireProfile
import java.util.Base64
import kotlin.random.Random

/**
 * MCData payload protection (TS 24.282 clause 6.6.3, TS 33.180 clauses 8.3 to 8.5) between two clients keyed by the
 * OpenMCX KMS (the keytool vectors): one-to-one SDS under a PCK whose I_MESSAGE travels with the first message, group
 * SDS under the GMK, files as a series of Protected Payload messages, and payload authentication (ECCSI).
 */
class McdataProtectionTest {
    private val v = Json.parseToJsonElement(javaClass.getResource("/keytool-vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val now = v["ntp"]!!.jsonPrimitive.long
    private val profile = v["profile"]!!.jsonObject.let {
        WireProfile(it["message_type"]!!.jsonPrimitive.int, it["algorithm"]!!.jsonPrimitive.int,
            it["key_parameters_iei"]!!.jsonPrimitive.int, it["encrypted_payload_iei"]!!.jsonPrimitive.int)
    }
    private val kms = KmsDocuments.parseInit(s("init_xml"), s("init_user"), s("kms_uri"), s("init_request"), now)
    private fun keys(name: String, user: String) = KmsDocuments.parseKeyProv(s("${name}_keyprov_xml"), kms, user, s("${name}_keyprov_request"), now)
    private val aliceId = "sip:alice.mcvideo@mcx.test"
    private val bobId = "sip:bob.mcvideo@mcx.test"
    private val group = s("group")

    /** The key operations of one MCData ID at the vectors' time, on the real MIKEY-SAKKE and ECCSI code. */
    private class Keys(val kms: KmsPublic, val own: IdentityKeys, val now: Long, val profile: WireProfile) : PayloadKeys {
        override suspend fun makePck(recipient: String): Pair<KeyMessage, String> {
            val key = Mikey.newKey(1, now)
            return key to Base64.getEncoder().encodeToString(Mikey.create(kms, own, recipient, key))
        }
        override suspend fun acceptPck(expected: String, encoded: String) = Mikey.open(kms, own, expected, KeyClient.decode(encoded), now, profile)
        override suspend fun authenticate(original: ByteArray) = Mikey.authenticatePayload(kms, own, now, original[0].toInt() and 0xff, original)
        override suspend fun openAuthenticated(sender: String, message: ByteArray) = Mikey.openAuthenticated(kms, sender, message, now)
    }

    private fun config(payload: Boolean = true, authenticate: Boolean = false) = ClientConfig.Mcdata("sip:x@mcx.test", "sip:mcdata@mcx.test",
        emptyList(), 1000, 10, protectPayload = payload, authenticatePayload = authenticate)

    private val gmks = GroupKeys()
    private fun client(name: String, user: String, authenticate: Boolean = false) =
        McdataProtection(config(authenticate = authenticate), profile, { Keys(kms, keys(name, user), now, profile) }, { gmks })

    private val payload = DataPayload(listOf(Payload.text("hello, protected"))).encode()

    @Test fun oneToOneUnderAPck() = runBlocking {
        val alice = client("alice", aliceId)
        val bob = client("bob", bobId)
        val (pck, message) = alice.pckFor(bobId)
        assertNotNull(message)
        assertEquals(1L, pck.csbId ushr 28)
        val sealed = alice.seal(payload, pck)
        assertEquals(0x43, sealed[0].toInt() and 0xff)
        assertEquals(pck.csbId, alice.keyId(sealed))
        bob.acceptPck(message!!, aliceId)
        assertArrayEquals(payload, bob.open(sealed, aliceId, null))
        // The context is kept: the next message has no I_MESSAGE; a redelivery repeats the one accepted.
        val (again, none) = alice.pckFor(bobId)
        assertEquals(pck.csbId, again.csbId)
        assertNull(none)
        bob.acceptPck(message, aliceId)
        // Only the peer the PCK is shared with, and only one-to-one.
        assertTrue(runCatching { bob.open(sealed, "sip:eve@mcx.test", null) }.isFailure)
        assertTrue(runCatching { bob.open(sealed, aliceId, group) }.isFailure)
        // Tampered: refused.
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(runCatching { bob.open(tampered, aliceId, null) }.isFailure)
        // An I_MESSAGE claimed by another sender is refused.
        val (_, other) = client("alice", aliceId).pckFor(bobId)
        assertTrue(runCatching { bob.acceptPck(other!!, "sip:eve@mcx.test") }.isFailure)
        assertEquals(1, alice.sealed.get()); assertEquals(1, bob.opened.get())
    }

    @Test fun groupUnderTheGmk() {
        val unix = System.currentTimeMillis() / 1000
        val gmk = KeyMessage(Random.nextBytes(16), Random.nextBytes(16), 0x0123456L, unix + Mikey.NTP_EPOCH,
            KeyProperties(0, 1, unix - 60, unix + 3600, "", listOf(group)))
        gmks.add(group, gmk)
        val alice = client("alice", aliceId)
        val bob = client("bob", bobId)
        val sealed = alice.seal(payload, alice.gmkFor(group))
        assertArrayEquals(payload, bob.open(sealed, aliceId, group))
        assertTrue(runCatching { bob.open(sealed, aliceId, "sip:other@mcx.test") }.isFailure)
        assertTrue(runCatching { bob.open(sealed, aliceId, null) }.isFailure)
        assertTrue(runCatching { alice.gmkFor("sip:other@mcx.test") }.isFailure)
    }

    @Test fun fileAsASeriesOfProtectedPayloads() = runBlocking {
        val alice = client("alice", aliceId)
        val bob = client("bob", bobId)
        val (pck, message) = alice.pckFor(bobId)
        bob.acceptPck(message!!, aliceId)
        val file = Random.nextBytes(150_000)
        val sealed = alice.sealFile(file, pck)
        assertEquals(McdataProtection.PROTECTED_FILE_TYPE, sealed[0].toInt() and 0xff)
        assertArrayEquals(file, bob.openFile(sealed, aliceId, null))
        // A series missing its last message, or empty, is refused.
        val first = 35 + (((sealed[33].toInt() and 0xff) shl 8) or (sealed[34].toInt() and 0xff))
        assertTrue(runCatching { bob.openFile(sealed.copyOfRange(0, first), aliceId, null) }.isFailure)
        assertArrayEquals(ByteArray(0), bob.openFile(alice.sealFile(ByteArray(0), pck), aliceId, null))
    }

    @Test fun payloadAuthentication() = runBlocking {
        val alice = client("alice", aliceId, authenticate = true)
        val bob = client("bob", bobId)
        val signed = alice.sign(payload)
        assertEquals(0x83, signed[0].toInt() and 0xff)
        assertArrayEquals(payload, bob.verify(signed, aliceId))
        assertTrue(runCatching { bob.verify(signed, bobId) }.isFailure)
        val tampered = signed.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertTrue(runCatching { bob.verify(tampered, aliceId) }.isFailure)
        // Over the protected payload when both apply (TS 33.180 clause 8.5.5): bits 8 and 7.
        val (pck, message) = alice.pckFor(bobId)
        bob.acceptPck(message!!, aliceId)
        val both = alice.sign(alice.seal(payload, pck))
        assertEquals(0xC3, both[0].toInt() and 0xff)
        assertArrayEquals(payload, bob.open(bob.verify(both, aliceId), aliceId, null))
        // Off: unchanged.
        assertArrayEquals(payload, bob.sign(payload))
    }

    /**
     * TS 33.180 clause 8.3 with TS 24.282 clauses 10.2.2.1 and 10.2.3.1: a one-to-one file is sealed with the PCK before
     * the upload (its metadata describing the stored object), the FD request carries the I_MESSAGE, and the recipient
     * opens the downloaded file after checking its hash; a refused I_MESSAGE is answered 606 with warning 136.
     */
    @Test fun protectedFileDistribution() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val dir = java.nio.file.Files.createTempDirectory("fd").toFile()
        val stored = mutableMapOf<String, ByteArray>()
        val files = object : org.openmcx.client.http.FileHttpClient {
            override suspend fun upload(url: String, headers: Map<String, String>, info: ByteArray, file: java.io.File,
                                        filename: String, progress: (Long) -> Unit): org.openmcx.client.http.HttpResponse {
                val location = "https://content/mcdata/files/${stored.size}/$filename"
                stored[location] = file.readBytes()
                return org.openmcx.client.http.HttpResponse(201, mapOf("Location" to listOf(location)), ByteArray(0))
            }
            override suspend fun download(url: String, headers: Map<String, String>, destination: java.io.File, maxBytes: Long?,
                                          progress: (Long) -> Unit): org.openmcx.client.http.HttpResponse {
                destination.parentFile.mkdirs(); destination.writeBytes(stored[url]!!)
                return org.openmcx.client.http.HttpResponse(200, emptyMap(), ByteArray(0))
            }
        }
        class Peer(override val userId: String, override val protection: McdataProtection) : FdPeer {
            override val clientId = "urn:uuid:1"
            val sent = mutableListOf<List<org.openmcx.client.body.Part>>()
            override suspend fun send(parts: List<org.openmcx.client.body.Part>): org.openmcx.client.sip.SipResponse {
                sent += parts; return org.openmcx.client.sip.SipResponse(202, "Accepted")
            }
            override fun token() = "t"
            override suspend fun refreshToken() {}
            override fun groupPolicy(group: String): GroupFdPolicy? = null
            override fun event(text: String) {}
        }
        val alice = Peer(aliceId, client("alice", aliceId))
        val bob = Peer(bobId, client("bob", bobId))
        val config = FdConfig(contentServer = "https://content", tdu2Seconds = 60)
        val sender = FdService(scope, alice, config, files, java.io.File(dir, "a"))
        val receiver = FdService(scope, bob, config, files, java.io.File(dir, "b"))
        val plain = Random.nextBytes(70_000)
        val file = java.io.File(dir, "plain.bin").apply { writeBytes(plain) }
        val record = sender.send(bobId, false, file, FileMetadata("plain.bin", "application/octet-stream", file.length(), FdService.sha256(file)))
        assertEquals(FdState.SENT, record.state)
        val parts = alice.sent.single()
        val stored0 = stored.values.single()
        assertTrue(!stored0.contentEquals(plain))
        val offered = FileMetadata.parse(FdSignalling.decode(parts.single { it.mediaType == McdataService.SIGNALLING }.content).metadata!!)
        assertEquals(stored0.size.toLong(), offered.size)
        val mikey = parts.single { it.mediaType == McdataProtection.MIKEY }
        fun delivered(from: String, key: org.openmcx.client.body.Part): org.openmcx.client.sip.SipRequest {
            val (type, body) = org.openmcx.client.body.Multipart.encode(listOf(
                org.openmcx.client.body.Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingUserId = from).encode().toByteArray()),
                parts.single { it.mediaType == McdataService.SIGNALLING }, key))
            return org.openmcx.client.sip.SipRequest("MESSAGE", "sip:bob@x", org.openmcx.client.sip.Headers()
                .add("Accept-Contact", "*;+g.3gpp.mcdata.fd;require;explicit").add("Call-ID", "f@x").add("CSeq", "1 MESSAGE")
                .add("Content-Type", type), body)
        }
        // From someone the I_MESSAGE does not name: refused.
        assertEquals(606, receiver.receive(delivered("sip:eve@mcx.test", mikey)))
        assertEquals(200, receiver.receive(delivered(aliceId, mikey)))
        val got = receiver.download(receiver.records.value.single { it.incoming }.id)
        assertEquals(FdState.COMPLETED, got.state)
        assertArrayEquals(plain, java.io.File(got.localPath!!).readBytes())
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        dir.deleteRecursively()
        Unit
    }
}
