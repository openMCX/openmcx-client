//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.ClientConfig
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.msrp.ByteRange
import org.openmcx.client.msrp.MsrpMessage
import org.openmcx.client.msrp.PassiveEndpoint
import org.openmcx.client.msrp.SocketMsrpConnector
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCData media plane procedures (TS 24.282 §9.2.3, §9.2.4, §6.2.8; TS 24.582 §6.1) against a fake MCData server:
 * its SIP side records the requests and answers them, its media side is a passive MSRP endpoint on loopback.
 */
class McdataMediaServiceTest {
    private val alice = "sip:alice.mcdata@mcx.test"
    private val bob = "sip:bob.mcdata@mcx.test"
    private val group = "sip:operations@mcx.test"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpoints = CopyOnWriteArrayList<PassiveEndpoint>()

    @After fun close() { endpoints.forEach { it.close() } }

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcdata": {"user_id": "$alice", "psi": "sip:mcdata@mcx.test", "groups": ["$group"], "max_payload_size_sds_cplane_bytes": 100},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val noHttp = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            HttpResponse(404, emptyMap(), ByteArray(0))
    }

    /** The MCData server: answers INVITEs 2xx with a passive MSRP stream of its own ([status] otherwise). */
    private inner class FakeServer(var status: Int = 200, var answerSetup: String = "passive") : McdataMediaPeer {
        val invites = CopyOnWriteArrayList<Pair<List<Part>, List<Pair<String, String>>>>()
        val reinvites = CopyOnWriteArrayList<List<Part>>()
        val byes = CopyOnWriteArrayList<List<Pair<String, String>>>()
        var endpoint: PassiveEndpoint? = null
        override val userId = alice
        override val clientId = "urn:uuid:1"
        override fun localAddress() = "127.0.0.1"
        override fun contact(feature: String) = "<sip:alice@127.0.0.1;transport=tls>;+g.3gpp.mcdata.$feature"
        override fun resourcePriority(level: String) = "mcpttp.$level"
        override suspend fun invite(parts: List<Part>, headers: List<Pair<String, String>>): Pair<SipResponse, Dialog?> {
            invites += parts to headers
            if (status >= 300) return SipResponse(status, "Refused", Headers().add("Warning", "399 mcdata \"198 no users\"")) to null
            val e = PassiveEndpoint().also { endpoints += it; endpoint = it }
            val answer = MsrpMedia("127.0.0.1", e.uri.port, e.uri.text(), answerSetup, "recvonly").encode()
            val (type, body) = Multipart.encode(listOf(Part("application/sdp", answer.toByteArray())))
            return SipResponse(200, "OK", Headers().add("Content-Type", type), body) to dialog()
        }
        override suspend fun reinvite(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
            reinvites += parts; return SipResponse(status, "OK")
        }
        override suspend fun bye(dialog: Dialog, headers: List<Pair<String, String>>) { byes += headers }
        val infos = CopyOnWriteArrayList<Pair<List<Part>, List<Pair<String, String>>>>()
        val fdMessages = CopyOnWriteArrayList<List<Part>>()
        override suspend fun info(dialog: Dialog, parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
            infos += parts to headers; return SipResponse(200, "OK")
        }
        override suspend fun fdMessage(parts: List<Part>): SipResponse { fdMessages += parts; return SipResponse(200, "OK") }
        override fun event(text: String) { println(text) }
    }

    private fun dialog() = Dialog("c1@x", "lt", "rt", NameAddr(alice), NameAddr("sip:mcdata@mcx.test"), "sip:mcdata@mcx.test", emptyList(), 1)

    private fun services(server: FakeServer): Pair<McdataService, McdataMediaService> {
        val client = McxClient(scope, config(), noHttp, connector = { _, _ -> throw IllegalStateException("offline") })
        val sds = McdataService(client)
        val media = McdataMediaService(scope, server, sds, SocketMsrpConnector())
        sds.media = media
        return sds to media
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what"); Thread.sleep(20) }
    }

    private fun sdp(parts: List<Part>) = MsrpMedia.parse(parts.first { it.mediaType == "application/sdp" }.text)
    private fun info(parts: List<Part>) = McdataInfo.parse(parts.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text)
    private fun msrpParts(m: MsrpMessage) = Multipart.parse(m.contentType, m.body)

    @Test fun standaloneSdsAboveTheSignallingLimitGoesOverTheMediaPlane() = runBlocking {
        val server = FakeServer()
        val (sds, _) = services(server)
        val text = "x".repeat(3000)
        val sent = sds.send(bob, text, group = false, disposition = DispositionRequest.DELIVERY)
        // TS 24.282 §9.2.3.2.1: "m=message", TCP/MSRP, *, a=sendonly, a=path, the two accept-types, a=setup:actpass.
        val (parts, headers) = server.invites.single()
        val offer = sdp(parts)
        assertEquals("actpass", offer.setup)
        assertEquals("sendonly", offer.direction)
        assertEquals(listOf(McdataService.SIGNALLING, McdataService.PAYLOAD), offer.acceptTypes)
        assertFalse(offer.secure)
        assertEquals("one-to-one-sds", info(parts).requestType)
        assertEquals(listOf(bob), ResourceLists.parse(parts.first { it.mediaType == ResourceLists.CONTENT_TYPE }.text))
        assertTrue(headers.contains("P-Preferred-Service" to Icsi.MCDATA_SDS))
        assertTrue(headers.any { it.first == "Accept-Contact" && it.second.contains("+g.3gpp.mcdata.sds;require") })
        // TS 24.582 §6.1.1.2.1: the binding SEND, then one message with the signalling and data payloads in chunks.
        val e = server.endpoint!!
        assertEquals(0, e.next().body.size)
        val chunks = generateSequence { e.received.poll(2, java.util.concurrent.TimeUnit.SECONDS) }.takeWhile { true }.take(2).toList()
        assertEquals(ByteRange(1, 2048, null).start, chunks[0].byteRange!!.start)
        val whole = MsrpMessage("t", method = "SEND", toPath = chunks[0].toPath, fromPath = chunks[0].fromPath,
            contentType = chunks[0].contentType, body = chunks.map { it.body }.reduce { a, b -> a + b })
        val bodies = msrpParts(whole)
        assertEquals(listOf(McdataService.SIGNALLING, McdataService.PAYLOAD), bodies.map { it.mediaType })
        val signalling = SdsSignalling.decode(bodies[0].content)
        assertEquals(sent.messageId, signalling.messageId)
        assertEquals(DispositionRequest.DELIVERY, signalling.disposition)
        assertEquals(text, DataPayload.decode(bodies[1].content).payloads.single().text)
        // TS 24.282 §9.2.3.2.3: released once delivered, with the Reason of a successful transmission.
        waitFor("BYE") { server.byes.isNotEmpty() }
        assertEquals(listOf("Reason" to "SIP;cause=200;text=\"transmission succeeded\""), server.byes.single())
        assertEquals("media", sds.outbox.value.single().plane)
    }

    @Test fun groupStandaloneSdsNamesTheGroupWithoutResourceLists() = runBlocking {
        val server = FakeServer()
        val (sds, _) = services(server)
        sds.send(group, "y".repeat(500), group = true)
        val parts = server.invites.single().first
        assertEquals("group-sds", info(parts).requestType)
        assertEquals(group, info(parts).requestUri)
        assertEquals("urn:uuid:1", info(parts).clientId)
        assertNull(parts.firstOrNull { it.mediaType == ResourceLists.CONTENT_TYPE })
    }

    @Test fun refusedInviteFailsTheSds() = runBlocking {
        val server = FakeServer(status = 403)
        val (sds, media) = services(server)
        val error = runCatching { sds.send(group, "y".repeat(500), group = true) }.exceptionOrNull() as? SdsError
        assertEquals(403, error?.status)
        assertEquals("refused", media.communications.value.single().state)
    }

    @Test fun anActiveAnswerIsRefusedSinceTheClientDoesNotListen() = runBlocking {
        val server = FakeServer(answerSetup = "active")
        val (sds, media) = services(server)
        assertTrue(runCatching { sds.send(bob, "y".repeat(500), group = false) }.exceptionOrNull() is SdsError)
        assertEquals("failed", media.communications.value.single().state)
        assertEquals(1, server.byes.size)
    }

    @Test fun groupSessionWithEmergencyThenCancelAndClose() = runBlocking {
        val server = FakeServer()
        val (sds, media) = services(server)
        val c = media.openSession(group, group = true, priority = "emergency", alert = false)
        val (parts, headers) = server.invites.single()
        // TS 24.282 §9.2.4.2.1: "a=sendrecv"; §6.2.8.1.1: <emergency-ind> true; §6.2.8.1.2: the emergency priority.
        assertEquals("sendrecv", sdp(parts).direction)
        assertEquals("group-sds-session", info(parts).requestType)
        assertEquals(true, info(parts).emergency)
        assertTrue(headers.contains("Resource-Priority" to "mcpttp.emergency"))
        assertTrue(c.open && c.emergency)
        val e = server.endpoint!!
        e.next()
        media.sessionSend("hello", DispositionRequest.READ)
        val signalling = SdsSignalling.decode(msrpParts(e.next())[0].content)
        // TS 24.582 §6.1.2.4: any participant may send, so the Sender IE names this user.
        assertEquals(alice, signalling.sender)
        assertEquals(c.conversationId, signalling.conversationId)
        assertEquals("session", sds.outbox.value.single().plane)
        // TS 24.282 §6.2.8.1.3: the emergency state cancelled by re-INVITE, with the media as established.
        media.setPriority(emergency = true, enable = false)
        val reinvite = server.reinvites.single()
        assertEquals(false, info(reinvite).emergency)
        assertEquals("active", sdp(reinvite).setup)
        assertFalse(c.emergency)
        media.close()
        assertEquals("released", c.state)
        assertEquals(1, server.byes.size)
    }

    @Test fun imminentPerilNeedsAGroup() = runBlocking {
        val (_, media) = services(FakeServer())
        assertTrue(runCatching { media.openSession(bob, group = false, priority = "imminent") }.exceptionOrNull() is SdsError)
    }

    /** An incoming INVITE the way the MCData server sends it (TS 24.282 §9.2.3.4.3, §9.2.4.4.3). */
    private fun invite(e: PassiveEndpoint, requestType: String, from: String = bob, groupId: String? = null, service: String = Icsi.MCDATA_SDS,
                       extraParts: List<Part> = emptyList(), setup: String = "passive", sdpExtra: (MsrpMedia) -> MsrpMedia = { it }): SipRequest {
        val offer = sdpExtra(MsrpMedia("127.0.0.1", e.uri.port, e.uri.text(), setup, "recvonly"))
        val info = McdataInfo(requestType = requestType, requestUri = alice, callingUserId = from, callingGroupId = groupId)
        val (type, body) = Multipart.encode(listOf(Part("application/sdp", offer.encode().toByteArray()),
            Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())) + extraParts)
        val headers = Headers().add("Via", "SIP/2.0/TLS 10.0.0.1:5061;branch=z9hG4bKin${UUID.randomUUID()}")
            .add("From", "<sip:mcdata@mcx.test>;tag=srv").add("To", "<$alice>").add("Call-ID", "in-${UUID.randomUUID()}")
            .add("CSeq", "1 INVITE").add("Contact", "<sip:mcdata@10.0.0.1:5061;transport=tls>;isfocus")
            .add("P-Asserted-Service", service).add("Session-Expires", "1800").add("Content-Type", type)
        return SipRequest("INVITE", "sip:alice@127.0.0.1", headers, body)
    }

    private class Answer(override val request: SipRequest) : MediaInvite {
        override val localTag = "ours"
        val responses = CopyOnWriteArrayList<Triple<Int, String?, List<Pair<String, String>>>>()
        override suspend fun respond(status: Int, body: ByteArray, contentType: String?, extra: List<Pair<String, String>>) {
            responses += Triple(status, if (body.isEmpty()) null else String(body), extra)
        }
    }

    private fun bye(request: SipRequest) = SipRequest("BYE", "sip:alice@127.0.0.1", Headers()
        .add("Via", "SIP/2.0/TLS 10.0.0.1:5061;branch=z9hG4bKbye").add("From", "<sip:mcdata@mcx.test>;tag=srv")
        .add("To", "<$alice>;tag=ours").add("Call-ID", request.callId).add("CSeq", "2 BYE"))

    private fun sdsSend(e: PassiveEndpoint, signalling: SdsSignalling, text: String) {
        val (type, body) = Multipart.encode(listOf(Part(McdataService.SIGNALLING, signalling.encode()),
            Part(McdataService.PAYLOAD, DataPayload(listOf(Payload.text(text))).encode())))
        e.send(MsrpMessage(MsrpMessage.newIdent(), method = "SEND", toPath = listOf(e.clientUri!!), fromPath = listOf(e.uri),
            messageId = MsrpMessage.newIdent(), byteRange = ByteRange(1, body.size.toLong(), body.size.toLong()), contentType = type, body = body))
    }

    @Test fun incomingStandaloneSdsIsAnsweredActiveAndDelivered() = runBlocking {
        val server = FakeServer()
        val (sds, media) = services(server)
        val e = PassiveEndpoint().also { endpoints += it }
        val request = invite(e, "one-to-one-sds")
        val tx = Answer(request)
        assertTrue(media.handle(tx))
        val (status, body, extra) = tx.responses.single()
        assertEquals(200, status)
        // TS 24.282 §9.2.3.2.2: the answer is "active", "a=recvonly"; §9.2.3.2.4: "timer" in Require.
        val answer = MsrpMedia.parse(body!!)
        assertEquals("active", answer.setup)
        assertEquals("recvonly", answer.direction)
        assertEquals(9, answer.port)
        assertTrue(extra.contains("Require" to "timer"))
        assertTrue(extra.any { it.first == "Contact" && it.second.contains("+g.3gpp.mcdata.sds") })
        assertEquals(0, e.next().body.size) // the binding SEND
        val signalling = SdsSignalling(1, UUID.randomUUID(), UUID.randomUUID(), disposition = DispositionRequest.DELIVERY)
        sdsSend(e, signalling, "z".repeat(2000))
        waitFor("delivered") { sds.inbox.value.isNotEmpty() }
        val received = sds.inbox.value.single()
        assertEquals(bob, received.sender)
        assertEquals("media", received.plane)
        assertEquals(signalling.messageId, received.messageId)
        // The server releases the leg once delivered.
        assertTrue(media.handle(Answer(bye(request))))
        assertEquals("released", media.communications.value.single().state)
    }

    @Test fun incomingGroupSessionNotifiesInBand() = runBlocking {
        val (sds, media) = services(FakeServer())
        val e = PassiveEndpoint().also { endpoints += it }
        val tx = Answer(invite(e, "group-sds-session", groupId = group))
        media.handle(tx)
        assertEquals("sendrecv", MsrpMedia.parse(tx.responses.single().second!!).direction)
        e.next()
        waitFor("open") { media.session != null }
        val carol = "sip:carol.mcdata@mcx.test"
        val signalling = SdsSignalling(1, UUID.randomUUID(), UUID.randomUUID(), disposition = DispositionRequest.DELIVERY, sender = carol)
        sdsSend(e, signalling, "hello group")
        e.next() // the 200 to that SEND is ours; next is the in-band notification
        // TS 24.582 §6.1.2.5: the DELIVERED notification goes in the session, a SEND of the signalling body alone.
        val notice = e.next()
        assertEquals(McdataService.SIGNALLING, notice.contentType)
        val decoded = SdsNotification.decode(notice.body)
        assertEquals(DispositionNotification.DELIVERED, decoded.type)
        assertEquals(signalling.messageId, decoded.messageId)
        val received = sds.inbox.value.single()
        assertEquals(carol, received.sender)
        assertEquals(group, received.group)
        assertEquals("session", received.plane)
    }

    @Test fun incomingOffersThisClientCannotTakeAreRefused() = runBlocking {
        val (_, media) = services(FakeServer())
        val e = PassiveEndpoint().also { endpoints += it }
        // An offer that would make this client listen.
        val active = Answer(invite(e, "one-to-one-sds", setup = "active"))
        media.handle(active)
        assertEquals(488, active.responses.single().first)
        // Not MCData SDS or FD: another handler's.
        assertFalse(media.handle(Answer(invite(e, "one-to-one-sds", service = Icsi.MCPTT))))
    }

    @Test fun theServersReinviteChangesTheSessionPriority() = runBlocking {
        val (_, media) = services(FakeServer())
        val e = PassiveEndpoint().also { endpoints += it }
        val request = invite(e, "one-to-one-sds-session")
        media.handle(Answer(request))
        waitFor("open") { media.session != null }
        val info = McdataInfo(requestType = "one-to-one-sds-session", callingUserId = bob, emergency = true)
        val (type, body) = Multipart.encode(listOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())))
        val reinvite = SipRequest("INVITE", "sip:alice@127.0.0.1", Headers().add("Via", "SIP/2.0/TLS 10.0.0.1;branch=z9hG4bKre")
            .add("From", "<sip:mcdata@mcx.test>;tag=srv").add("To", "<$alice>;tag=ours").add("Call-ID", request.callId)
            .add("CSeq", "2 INVITE").add("Content-Type", type), body)
        val tx = Answer(reinvite)
        assertTrue(media.handle(tx))
        assertEquals(200, tx.responses.single().first)
        assertTrue(media.session!!.emergency)
        assertNotNull(MsrpMedia.parse(tx.responses.single().second!!))
    }

    // FD over the media plane (TS 24.282 §10.2.5, TS 24.582 §7).

    @Test fun fileOverTheMediaPlaneIsOfferedWithRfc5547AttributesAndSent() = runBlocking {
        val server = FakeServer()
        val (_, media) = services(server)
        val contents = ByteArray(5000) { (it * 7).toByte() }
        val c = media.sendFile(bob, group = false, name = "photo.jpg", contents = contents, type = "image/jpeg", mandatory = true)
        val (parts, headers) = server.invites.single()
        val offer = sdp(parts)
        // TS 24.282 §10.2.5.2.1: the signalling type only, the file selector with size and hash, sendonly.
        assertEquals(listOf(McdataService.SIGNALLING), offer.acceptTypes)
        assertEquals("sendonly", offer.direction)
        val metadata = FileMetadata.parse("file-selector:" + offer.fileSelector)
        assertEquals(5000L, metadata.size)
        assertEquals(FdService.sha256Bytes(contents), metadata.sha256)
        assertNotNull(offer.fileTransferId)
        assertTrue(offer.fileDate!!.startsWith("creation:\""))
        assertEquals("photo.jpg", offer.fileDescription)
        assertEquals("one-to-one-fd", info(parts).requestType)
        assertTrue(headers.contains("P-Preferred-Service" to Icsi.MCDATA_FD))
        // TS 24.282 §6.2.2.3: the FD SIGNALLING PAYLOAD has no Payload IE.
        val signalling = FdSignalling.decode(parts.first { it.mediaType == McdataService.SIGNALLING }.content, mediaPlane = true)
        assertEquals("", signalling.fileUrl)
        assertTrue(signalling.mandatoryDownload)
        assertTrue(runCatching { FdSignalling.decode(parts.first { it.mediaType == McdataService.SIGNALLING }.content) }.isFailure)
        val e = server.endpoint!!
        e.next()
        val chunks = (1..3).map { e.next() }
        assertTrue(chunks.all { it.contentType == McdataMediaService.FILE })
        assertEquals(contents.toList(), chunks.map { it.body }.reduce { a, b -> a + b }.toList())
        assertEquals("delivered", c.state)
        waitFor("BYE") { server.byes.size == 1 }
    }

    private fun fdInvite(e: PassiveEndpoint, contents: ByteArray, mandatory: Boolean, completion: Boolean = false): SipRequest {
        val signalling = FdSignalling(1, UUID.randomUUID(), UUID.randomUUID(), "", completionRequested = completion, mandatoryDownload = mandatory)
        val selector = FileMetadata("report.pdf", "application/pdf", contents.size.toLong(), FdService.sha256Bytes(contents)).encode()
            .removePrefix("file-selector:")
        return invite(e, "one-to-one-fd", service = Icsi.MCDATA_FD,
            extraParts = listOf(Part(McdataService.SIGNALLING, signalling.encode(mediaPlane = true)))) {
            it.copy(acceptTypes = listOf(McdataService.SIGNALLING), fileSelector = selector, fileTransferId = "t1")
        }
    }

    @Test fun mandatoryFileIsReceivedCheckedAndCompletionNotified() = runBlocking {
        val server = FakeServer()
        val (_, media) = services(server)
        val dir = java.nio.file.Files.createTempDirectory("fd").toFile()
        media.fileDirectory = dir
        val contents = ByteArray(4000) { it.toByte() }
        val e = PassiveEndpoint().also { endpoints += it }
        val tx = Answer(fdInvite(e, contents, mandatory = true, completion = true))
        media.handle(tx)
        val answer = MsrpMedia.parse(tx.responses.single().second!!)
        // TS 24.282 §10.2.5.2.2: the answer names the signalling type and repeats the file selector.
        assertEquals(listOf(McdataService.SIGNALLING), answer.acceptTypes)
        assertNotNull(answer.fileSelector)
        assertEquals("t1", answer.fileTransferId)
        assertTrue(tx.responses.single().third.any { it.first == "Contact" && it.second.contains("+g.3gpp.mcdata.fd") })
        e.next()
        e.send(MsrpMessage("f1", method = "SEND", toPath = listOf(e.clientUri!!), fromPath = listOf(e.uri), messageId = "file",
            byteRange = ByteRange(1, 4000, 4000), contentType = McdataMediaService.FILE, body = contents))
        waitFor("received") { media.communications.value.single().state == "received" }
        assertEquals(contents.toList(), java.io.File(dir, "report.pdf").readBytes().toList())
        // TS 24.282 §12.2.1.1: the completion notification goes on the signalling plane.
        waitFor("COMPLETED") { server.fdMessages.isNotEmpty() }
        val notice = FdNotification.decode(server.fdMessages.single().first { it.mediaType == McdataService.SIGNALLING }.content)
        assertEquals(FdNotificationType.COMPLETED, notice.type)
        assertEquals(alice, notice.sender)
    }

    @Test fun nonMandatoryFileIsDeclinedOrDeferredByTheUser() = runBlocking {
        val (_, media) = services(FakeServer())
        val e = PassiveEndpoint().also { endpoints += it }
        media.fileDecision = { "decline" }
        val declined = Answer(fdInvite(e, ByteArray(10), mandatory = false))
        media.handle(declined)
        assertEquals(480, declined.responses.single().first)
        assertTrue(declined.responses.single().third.contains("Warning" to FdMedia.DECLINED))
        media.fileDecision = { "ask" }
        val asked = Answer(fdInvite(e, ByteArray(10), mandatory = false))
        val job = kotlinx.coroutines.GlobalScope.launch { media.handle(asked) }
        waitFor("ringing") { asked.responses.isNotEmpty() }
        assertEquals(180, asked.responses.single().first)
        assertTrue(media.answerFile("defer"))
        job.join()
        assertEquals(480, asked.responses.last().first)
        assertTrue(asked.responses.last().third.contains("Warning" to FdMedia.DEFERRED))
        assertEquals("deferred", media.communications.value.last().state)
    }

    @Test fun corruptFileIsNotKept() = runBlocking {
        val (_, media) = services(FakeServer())
        val e = PassiveEndpoint().also { endpoints += it }
        media.handle(Answer(fdInvite(e, ByteArray(100) { 1 }, mandatory = true)))
        e.next()
        e.send(MsrpMessage("f1", method = "SEND", toPath = listOf(e.clientUri!!), fromPath = listOf(e.uri), messageId = "file",
            byteRange = ByteRange(1, 100, 100), contentType = McdataMediaService.FILE, body = ByteArray(100) { 2 }))
        waitFor("corrupt") { media.communications.value.single().state == "corrupt" }
    }

    // Communication release (TS 24.282 §13.2).

    @Test fun communicationReleaseCodec() {
        for (m in listOf(CommunicationRelease(ReleaseType.INTENT_TO_RELEASE, remainingDataQuery = true),
                         CommunicationRelease(ReleaseType.EXTENSION_RESPONSE, extensionAccepted = false),
                         CommunicationRelease(ReleaseType.AUTH_USER_RELEASE_REQUEST)))
            assertEquals(m, CommunicationRelease.decode(m.encode()))
        // Table 15.1.10.1-1: message type, the type octet, then the half-octet IEs.
        assertEquals(listOf(0x0a, 1, 0xb1), CommunicationRelease(ReleaseType.INTENT_TO_RELEASE, true).encode().map { it.toInt() and 0xff })
    }

    private fun releaseInfo(request: SipRequest, message: CommunicationRelease, reason: String? = null): SipRequest {
        val headers = Headers().add("Via", "SIP/2.0/TLS 10.0.0.1;branch=z9hG4bKinfo${UUID.randomUUID()}")
            .add("From", "<sip:mcdata@mcx.test>;tag=srv").add("To", "<$alice>;tag=ours").add("Call-ID", request.callId)
            .add("CSeq", "3 INFO").add("Info-Package", CommunicationRelease.PACKAGE).add("Content-Type", McdataService.SIGNALLING)
        reason?.let { headers.add("Reason", "SIP;cause=200;text=\"$it\"") }
        return SipRequest("INFO", "sip:alice@127.0.0.1", headers, message.encode())
    }

    @Test fun releaseIntentWithQueryIsAnsweredWithTheRemainingData() = runBlocking {
        val server = FakeServer()
        val (_, media) = services(server)
        val e = PassiveEndpoint().also { endpoints += it }
        val request = invite(e, "one-to-one-sds-session")
        media.handle(Answer(request))
        waitFor("open") { media.session != null }
        val tx = Answer(releaseInfo(request, CommunicationRelease(ReleaseType.INTENT_TO_RELEASE, remainingDataQuery = true), "data limit"))
        assertTrue(media.handle(tx))
        assertEquals(200, tx.responses.single().first)
        assertEquals("release pending: data limit", media.session!!.release)
        // TS 24.282 §13.2.4.2.1 step 2c: the remaining amount in an INFO of its own, a TEXT payload.
        val (parts, _) = server.infos.single()
        assertEquals("0", DataPayload.decode(parts.single { it.mediaType == McdataService.PAYLOAD }.content).payloads.single().text)
        // An authorised user's release request is the server's to make, not to receive: 403.
        val refused = Answer(releaseInfo(request, CommunicationRelease(ReleaseType.AUTH_USER_RELEASE_REQUEST)))
        media.handle(refused)
        assertEquals(403, refused.responses.single().first)
    }

    @Test fun releaseRequestsOfThisUser() = runBlocking {
        val server = FakeServer()
        val (_, media) = services(server)
        media.openSession(group, group = true)
        assertEquals(200, media.release("intent", reason = "done", query = true))
        val (parts, headers) = server.infos.single()
        // TS 24.282 §13.2.6.2.2.1: mcdata-info with the client ID, then the COMMUNICATION RELEASE; the Reason.
        assertEquals("urn:uuid:1", info(parts).clientId)
        assertEquals(CommunicationRelease(ReleaseType.INTENT_TO_RELEASE, true),
            CommunicationRelease.decode(parts.single { it.mediaType == McdataService.SIGNALLING }.content))
        assertTrue(headers.contains("Reason" to "SIP;cause=200;text=\"done\""))
        media.release("extend")
        assertEquals(CommunicationRelease(ReleaseType.EXTENSION_REQUEST),
            CommunicationRelease.decode(server.infos.last().first.single().content))
    }
}
