//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.documents.ServiceConfig
import org.openmcx.client.mcdata.McdataInfo
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcvideo.McvideoInfo
import org.openmcx.client.security.KeyError
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KeyProperties
import org.openmcx.client.security.Mikey
import org.openmcx.client.security.XmlProtection
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipFlow
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import org.openmcx.client.sip.SipTransmission
import org.openmcx.client.sip.UaIdentity
import org.openmcx.client.sip.UserAgent

/**
 * The gates and the protection of [SignallingProtection] (TS 24.379 / TS 24.281 clauses 6.6, TS 24.282 clauses 6.5 and
 * 6.6, TS 33.180 clauses 9.3 and 9.5): nothing protectable before the service configuration is read, required
 * protection never dropped, the authorisation upload encrypted and signed, CMC/GMC/GKTP subscriptions unprotected,
 * incoming verified then decrypted (403 with warning 139 or 140), MCData signalling sealed and opened under the CSK.
 */
class SignallingProtectionTest {
    private val config = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8901/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20},
        "mcdata": {"user_id": "sip:alice.mcdata@mcx.test", "psi": "sip:mcdata@mcx.test", "groups": [], "protect_signalling": true},
        "mcvideo": {"user_id": "sip:alice.mcvideo@mcx.test", "psi": "sip:mcvideo@mcx.test", "groups": []},
        "security": {"kms_uri": "https://kms:8443", "key_parameter_profile": {}},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val documents = mutableMapOf<String, Pair<Boolean, Boolean>>()
    private val csks = mutableMapOf<McService, KeyMessage>()
    private val held = mutableSetOf<McService>()
    private var now = 1_800_000_000L
    private val protection = SignallingProtection(config, { documents[it] }, { csks[it] }, { it in held }, { now })

    private fun csk(id: Long, expiry: Long = 0) = KeyMessage(ByteArray(16) { (id + it).toByte() }, ByteArray(16), id,
        Mikey.NTP_EPOCH + now, KeyProperties(2, 1, now - 10, expiry))
    private fun key(service: McService, user: String) = csks[service]!!.let { XmlProtection.Key(it.key, it.csbId, user) }

    private fun request(method: String, uri: String, vararg headers: Pair<String, String>) = SipRequest(method, uri,
        Headers().add("From", "<sip:alice@mcx.test>;tag=a").add("To", "<$uri>").add("Call-ID", "c1@x").add("CSeq", "1 $method")
            .also { h -> headers.forEach { (n, v) -> h.add(n, v) } })

    private fun publish(icsi: String = Icsi.MCPTT, uri: String = "sip:mcptt@mcx.test") =
        request("PUBLISH", uri, "P-Preferred-Service" to icsi, "Event" to "poc-settings")

    private val mcpttInfo = Part(McpttInfo.CONTENT_TYPE, McpttInfo(accessToken = "SECRET-TOKEN", clientId = "urn:uuid:1").encode().toByteArray())
    private val pocSettings = Part("application/poc-settings+xml", "<?xml version=\"1.0\"?><poc-settings xmlns=\"urn:oma:xml:poc:poc-settings\"/>".toByteArray())
    private val mikey = Part("application/mikey", "AQID".toByteArray())

    private fun out(message: SipRequest, parts: List<Part>) = protection.outgoing(SipTransmission(message, null), parts)
    private fun incoming(message: SipRequest, parts: List<Part>) = protection.incoming(SipTransmission(message, null), parts)
    private fun signatures(parts: List<Part>) = parts.single { it.mediaType == SignallingProtection.SIGNED }.text
    private fun bodies(parts: List<Part>) = parts.filter { it.mediaType != SignallingProtection.SIGNED }
        .mapNotNull { part -> SignallingProtection.contentId(part)?.let { XmlProtection.SignedBody(it, part.text) } }

    @Test fun nothingProtectableBeforeTheDocumentIsRead() {
        val error = assertThrows(KeyError::class.java) { out(publish(), listOf(mcpttInfo)) }
        assertTrue(error.message!!.contains("has not been read"))
        assertEquals(1, protection.notSent.get())
        // A body that is not XML passes.
        val sdp = listOf(Part("application/sdp", "v=0\r\n".toByteArray()))
        assertEquals(sdp, out(request("INVITE", "sip:mcptt@mcx.test", "P-Preferred-Service" to Icsi.MCPTT), sdp))
    }

    /** "true" or no element: enabled; then a missing or expired CSK leaves it required and the message is not sent. */
    @Test fun requiredProtectionWithoutAUsableCskIsNotSent() {
        documents["mcptt"] = ServiceConfig.signallingProtection(
            """<?xml version="1.0"?><service-configuration-info xmlns="urn:3gpp:mcptt:mcpttServiceConfig:1.0"/>""")
        assertEquals(true to true, documents["mcptt"])
        assertTrue(assertThrows(KeyError::class.java) { out(publish(), listOf(mcpttInfo, pocSettings)) }.message!!.contains("without the MCPTT CSK"))
        csks[McService.MCPTT] = csk(0x20000001, expiry = now)
        assertTrue(assertThrows(KeyError::class.java) { out(publish(), listOf(mcpttInfo, pocSettings, mikey)) }.message!!.contains("expired"))
        // Off in the document: sent as it is.
        documents["mcptt"] = false to false
        csks.clear()
        assertEquals(listOf(mcpttInfo), out(publish(), listOf(mcpttInfo)))
    }

    @Test fun theAuthorisationUploadIsEncryptedAndSigned() {
        documents["mcptt"] = true to true
        csks[McService.MCPTT] = csk(0x20000001)
        val parts = out(publish(), listOf(mcpttInfo, pocSettings, mikey))
        assertEquals(4, parts.size)
        val info = parts[0].text
        assertFalse(info.contains("SECRET-TOKEN"))
        assertEquals(0x20000001L, XmlProtection.keyId(info))
        // Both XML bodies (not the MIKEY one) carry a Content-ID and are signed under the XPK.
        assertNotNull(SignallingProtection.contentId(parts[1]))
        assertNull(SignallingProtection.contentId(parts[2]))
        val key = key(McService.MCPTT, "sip:alice.mcptt@mcx.test")
        assertEquals(setOf(SignallingProtection.contentId(parts[0]), SignallingProtection.contentId(parts[1])),
            XmlProtection.verify(signatures(parts), bodies(parts), key))
        assertTrue(XmlProtection.decrypt(info, key).contains("SECRET-TOKEN"))
        assertEquals(1, protection.xmlEncrypted.get()); assertEquals(1, protection.xmlSigned.get())
    }

    @Test fun untilTheServerHoldsTheCskOnlyTheUploadIsProtected() {
        documents["mcptt"] = true to true
        csks[McService.MCPTT] = csk(0x20000001)
        assertEquals(listOf(mcpttInfo, pocSettings), out(publish(), listOf(mcpttInfo, pocSettings)))
        held += McService.MCPTT
        assertEquals(3, out(publish(), listOf(mcpttInfo, pocSettings)).size)
    }

    /** No identity hiding: the configuration management, group document and GKTP subscriptions go without protection. */
    @Test fun documentSubscriptionsAreNotProtected() {
        documents["mcptt"] = true to true
        val list = { entry: String -> Part("application/resource-lists+xml", ("<?xml version=\"1.0\"?><resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-lists\">" +
            "<list><entry uri=\"$entry\"/></list></resource-lists>").toByteArray()) }
        val subscribe = request("SUBSCRIBE", "sip:mcptt@mcx.test", "P-Preferred-Service" to Icsi.MCPTT, "Event" to "xcap-diff")
        for (entry in listOf("org.3gpp.mcptt.user-profile/users/sip:alice.mcptt@mcx.test/mcptt-user-profile-1.xml",
                "org.openmobilealliance.groups/global/byGroupID/sip:g@mcx.test",
                "org.3gpp.MCPTT-GKTP/global/byGroupID/sip:g@mcx.test/~~/group/list-service/mgktp:GKTPs"))
            assertEquals(listOf(mcpttInfo, list(entry)), out(subscribe, listOf(mcpttInfo, list(entry))))
        // Any other xcap-diff subscription is not one of them: required protection, no CSK.
        assertThrows(KeyError::class.java) { out(subscribe, listOf(mcpttInfo, list("org.example.other/x"))) }
    }

    @Test fun mcvideoIsProtectedUnderItsOwnCsk() {
        documents["mcvideo"] = true to true
        csks[McService.MCPTT] = csk(0x20000001); csks[McService.MCVIDEO] = csk(0x20000002)
        held += McService.MCVIDEO
        val info = Part(McvideoInfo.CONTENT_TYPE, McvideoInfo(requestUri = "sip:g@mcx.test", clientId = "urn:uuid:1").encode().toByteArray())
        val parts = out(request("INVITE", "sip:mcvideo@mcx.test", "P-Preferred-Service" to Icsi.MCVIDEO), listOf(info))
        assertEquals(0x20000002L, XmlProtection.keyId(parts[0].text))
        assertEquals(0x20000002L, XmlProtection.signatureKeyId(signatures(parts)))
    }

    /** What the server sends under this client's CSK: verified first, then decrypted; the signatures body removed. */
    private fun serverParts(key: XmlProtection.Key, tamper: Boolean = false): List<Part> {
        val body = XmlProtection.encrypt(String(mcpttInfo.content), key, XmlProtection.signallingProfile())
        val signatures = XmlProtection.sign(listOf(XmlProtection.SignedBody("i@s", body)), key, SignallingProtection.SIGNED_NAMESPACE)
        val sent = if (tamper) body.replace("Encrypted", "Encrypted ") else body
        return listOf(Part(McpttInfo.CONTENT_TYPE, sent.toByteArray(), listOf("Content-ID" to "<i@s>")),
            Part(SignallingProtection.SIGNED, signatures.toByteArray()))
    }

    @Test fun incomingIsVerifiedThenDecrypted() {
        csks[McService.MCPTT] = csk(0x20000001)
        val opened = incoming(request("NOTIFY", "sip:alice@x"), serverParts(key(McService.MCPTT, "sip:alice.mcptt@mcx.test")))
        assertEquals(1, opened.size)
        assertTrue(opened[0].text.contains("SECRET-TOKEN"))
        assertEquals(1, protection.xmlVerified.get()); assertEquals(1, protection.xmlDecrypted.get())
    }

    @Test fun integrityFailureIs139AndUndecryptableIs140() {
        csks[McService.MCPTT] = csk(0x20000001)
        val key = key(McService.MCPTT, "sip:alice.mcptt@mcx.test")
        val tampered = assertThrows(KeyError::class.java) { incoming(request("NOTIFY", "sip:alice@x"), serverParts(key, tamper = true)) }
        assertTrue(tampered.message!!.startsWith("139 "))
        // Under this client's XPK-ID, but not its key: cannot be decrypted.
        val other = XmlProtection.Key(ByteArray(16) { 7 }, key.id, key.user)
        val wrong = Part(McpttInfo.CONTENT_TYPE, XmlProtection.encrypt(String(mcpttInfo.content), other, XmlProtection.signallingProfile()).toByteArray())
        assertTrue(assertThrows(KeyError::class.java) { incoming(request("NOTIFY", "sip:alice@x"), listOf(wrong)) }.message!!.startsWith("140 "))
        assertEquals(2, protection.xmlRefused.get())
    }

    private fun sds(vararg parts: Part) = parts.toList()
    private val signallingMessage = byteArrayOf(0x01, 0x11, 0x22, 0x33)
    private val sdsRequest get() = request("MESSAGE", "sip:mcdata@mcx.test", "P-Preferred-Service" to Icsi.MCDATA_SDS)
    private val fromServer get() = request("MESSAGE", "sip:alice@x", "P-Asserted-Service" to Icsi.MCDATA_SDS)

    @Test fun mcdataSignallingIsSealedAndOpenedUnderTheCsk() {
        documents["mcdata"] = false to false
        csks[McService.MCDATA] = csk(0x20000003)
        val payload = Part("application/vnd.3gpp.mcdata-payload", byteArrayOf(0x03, 1, 2))
        val parts = sds(Part(SignallingProtection.SIGNALLING, signallingMessage), payload)
        // Not held yet: in the clear (TS 24.282 clause 6.6.1: once the server accepted the CSK).
        assertEquals(parts, out(sdsRequest, parts))
        held += McService.MCDATA
        val sealed = out(sdsRequest, parts)
        assertEquals(0x41, sealed[0].content[0].toInt() and 0xff)
        assertArrayEquals(payload.content, sealed[1].content)
        val profile = config.security!!.wireProfile
        assertArrayEquals(signallingMessage, Mikey.openPayload(sealed[0].content, csks[McService.MCDATA]!!.key, profile, cskSignalling = true).plain)
        // From the server: opened; clear signalling is refused without a 139 or 140 warning.
        assertArrayEquals(signallingMessage, incoming(fromServer, sealed).first().content)
        assertEquals(1, protection.signallingSealed.get()); assertEquals(1, protection.signallingOpened.get())
        val clear = assertThrows(KeyError::class.java) { incoming(fromServer, parts) }
        assertFalse(clear.message!!.startsWith("139") || clear.message!!.startsWith("140"))
        // Past the CSK's Expiry time nothing is sealed: not sent.
        csks[McService.MCDATA] = csk(0x20000003, expiry = now)
        assertThrows(KeyError::class.java) { out(sdsRequest, parts) }
    }

    @Test fun mcdataXmlUnderTheMcdataProfile() {
        documents["mcdata"] = true to true
        assertThrows(KeyError::class.java) {
            out(sdsRequest, listOf(Part(McdataInfo.CONTENT_TYPE, McdataInfo(requestUri = "sip:g@mcx.test").encode().toByteArray())))
        }
        csks[McService.MCDATA] = csk(0x20000003)
        held += McService.MCDATA
        val info = Part(McdataInfo.CONTENT_TYPE, McdataInfo(requestType = "group-sds", requestUri = "sip:g@mcx.test", clientId = "urn:uuid:1").encode().toByteArray())
        val parts = out(sdsRequest, listOf(info, Part(SignallingProtection.SIGNALLING, signallingMessage)))
        assertFalse(parts[0].text.contains("sip:g@mcx.test"))
        val key = key(McService.MCDATA, "sip:alice.mcdata@mcx.test")
        assertTrue(XmlProtection.decrypt(parts[0].text, key).contains("sip:g@mcx.test"))
        assertEquals(setOf(SignallingProtection.contentId(parts[0])),
            XmlProtection.verify(signatures(parts), listOf(XmlProtection.SignedBody(SignallingProtection.contentId(parts[0])!!, parts[0].text)), key))
        // From the server: signatures verified under the MCData CSK, signalling opened, XML decrypted.
        val opened = incoming(fromServer, parts)
        assertEquals(2, opened.size)
        assertTrue(opened[0].text.contains("sip:g@mcx.test"))
        assertArrayEquals(signallingMessage, opened[1].content)
    }

    // Through the user agent.

    private fun withUa(test: suspend (UserAgent, FakeSipCore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ua = UserAgent(scope, UaIdentity("sip:alice@mcx.test", "alice", "s", "mcx.test", "urn:uuid:1"))
        val core = FakeSipCore()
        ua.flow = SipFlow(scope, core, { ua.receive(it) }).also { it.start() }
        ua.outgoingBodies = protection::outgoing
        ua.incomingBodies = protection::incoming
        try { test(ua, core) } finally { core.close(); scope.cancel() }
    }

    @Test fun theUserAgentSendsProtectedOrNothing() = withUa { ua, core ->
        documents["mcptt"] = true to true
        val (type, body) = Multipart.encode(listOf(mcpttInfo, pocSettings))
        fun publish() = ua.newRequest("PUBLISH", "sip:mcptt@mcx.test", org.openmcx.client.sip.NameAddr("sip:mcptt@mcx.test")).also {
            it.headers.add("P-Preferred-Service", Icsi.MCPTT).add("Event", "poc-settings").add("Content-Type", type)
        }.let { SipRequest(it.method, it.uri, it.headers, body) }
        assertThrows(KeyError::class.java) { runBlocking { ua.send(publish()) } }
        assertTrue(core.requests.isEmpty())
        csks[McService.MCPTT] = csk(0x20000001); held += McService.MCPTT
        assertEquals(200, ua.send(publish()).status)
        val parts = Multipart.parse(core.requests.single().contentType, core.requests.single().body)
        assertEquals(listOf(McpttInfo.CONTENT_TYPE, "application/poc-settings+xml", SignallingProtection.SIGNED), parts.map { it.mediaType })
        assertFalse(parts[0].text.contains("SECRET-TOKEN"))
    }

    @Test fun theUserAgentRefusesWith139Or140() = withUa { ua, core ->
        csks[McService.MCPTT] = csk(0x20000001)
        val key = key(McService.MCPTT, "sip:alice.mcptt@mcx.test")
        suspend fun deliver(parts: List<Part>, branch: String): String? {
            val (type, body) = Multipart.encode(parts)
            val headers = Headers().add("Via", "SIP/2.0/TLS 127.0.0.1:5061;branch=$branch").add("From", "<sip:mcptt@mcx.test>;tag=s")
                .add("To", "<sip:alice@mcx.test>").add("Call-ID", "$branch@s").add("CSeq", "1 MESSAGE").add("Content-Type", type)
            ua.receive(SipRequest("MESSAGE", "sip:alice@x", headers, body))
            val answer = core.answer()
            assertEquals(403, answer.status)
            return answer.headers["Warning"]
        }
        assertTrue(deliver(serverParts(key, tamper = true), "z9hG4bK1")!!.contains("139"))
        val other = XmlProtection.Key(ByteArray(16) { 7 }, key.id, key.user)
        val wrong = Part(McpttInfo.CONTENT_TYPE, XmlProtection.encrypt(String(mcpttInfo.content), other, XmlProtection.signallingProfile()).toByteArray())
        assertTrue(deliver(listOf(wrong, pocSettings), "z9hG4bK2")!!.contains("140"))
    }

    /** A request under an XPK-ID this client does not hold: 139 for signatures, 140 for encrypted elements (TS 24.379
     * clauses 6.6.3.4.2 steps 4-5 and 6.6.2.4.2 steps 2-3); a response is left as before (no outcome in the specs). */
    @Test fun unknownXpkIdIs139Or140InARequest() {
        csks[McService.MCPTT] = csk(0x20000001)
        val unknown = XmlProtection.Key(ByteArray(16) { 9 }, 0x2000000AL, "sip:alice.mcptt@mcx.test")
        val signedOnly = String(mcpttInfo.content).let { info ->
            listOf(Part(McpttInfo.CONTENT_TYPE, info.toByteArray(), listOf("Content-ID" to "<i@s>")),
                Part(SignallingProtection.SIGNED, XmlProtection.sign(listOf(XmlProtection.SignedBody("i@s", info)), unknown,
                    SignallingProtection.SIGNED_NAMESPACE).toByteArray()))
        }
        val signed = assertThrows(KeyError::class.java) { incoming(request("NOTIFY", "sip:alice@x"), signedOnly) }
        assertTrue(signed.message!!.startsWith("139 "))
        val encrypted = Part(McpttInfo.CONTENT_TYPE,
            XmlProtection.encrypt(String(mcpttInfo.content), unknown, XmlProtection.signallingProfile()).toByteArray())
        assertTrue(assertThrows(KeyError::class.java) { incoming(request("NOTIFY", "sip:alice@x"), listOf(encrypted)) }.message!!.startsWith("140 "))
        // Without any CSK at all, the same.
        csks.clear()
        assertTrue(assertThrows(KeyError::class.java) { incoming(request("NOTIFY", "sip:alice@x"), listOf(encrypted)) }.message!!.startsWith("140 "))
        // A response: no outcome in the specs, left as it was.
        csks[McService.MCPTT] = csk(0x20000001)
        val response = SipResponse(200, "OK", Headers().add("CSeq", "1 PUBLISH").add("Call-ID", "c1@x"))
        val passed = protection.incoming(SipTransmission(response, null), listOf(encrypted))
        assertEquals(listOf(encrypted.text), passed.map { it.text })
    }

    @Test fun theUserAgentRefusesAnUnknownXpkIdWith140() = withUa { ua, core ->
        csks[McService.MCPTT] = csk(0x20000001)
        val unknown = XmlProtection.Key(ByteArray(16) { 9 }, 0x2000000AL, "sip:alice.mcptt@mcx.test")
        val encrypted = Part(McpttInfo.CONTENT_TYPE,
            XmlProtection.encrypt(String(mcpttInfo.content), unknown, XmlProtection.signallingProfile()).toByteArray())
        val (type, body) = Multipart.encode(listOf(encrypted, pocSettings))
        val headers = Headers().add("Via", "SIP/2.0/TLS 127.0.0.1:5061;branch=z9hG4bKu1").add("From", "<sip:mcptt@mcx.test>;tag=s")
            .add("To", "<sip:alice@mcx.test>").add("Call-ID", "u1@s").add("CSeq", "1 MESSAGE").add("Content-Type", type)
        ua.receive(SipRequest("MESSAGE", "sip:alice@x", headers, body))
        val answer = core.answer()
        assertEquals(403, answer.status)
        assertTrue(answer.headers["Warning"]!!.contains("140"))
    }
}
