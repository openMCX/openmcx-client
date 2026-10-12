//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.http.FileHttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.io.File
import java.util.UUID

class FdServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = "https://http-proxy:8443/content"
    private val log = mutableListOf<String>()

    /** The MCData server side of the client: records requests, answers with [answer]. */
    private inner class FakePeer(override val userId: String = "sip:alice@mcx.test") : FdPeer {
        override val clientId = "urn:uuid:00000000-0000-0000-0000-00000000000a"
        val sent = mutableListOf<List<Part>>()
        var answer: (List<Part>) -> SipResponse = { SipResponse(202, "Accepted") }
        var token = "t1"
        var refreshes = 0
        var policies = mapOf<String, GroupFdPolicy>()
        override suspend fun send(parts: List<Part>): SipResponse { sent += parts; log += "sip"; return answer(parts) }
        override fun token() = token
        override suspend fun refreshToken() { refreshes++; token = "t${refreshes + 1}" }
        override fun groupPolicy(group: String) = policies[group]
        override fun event(text: String) {}
    }

    /** The media storage function behind the proxy, on disk. */
    private inner class FakeFiles : FileHttpClient {
        val stored = mutableMapOf<String, File>()
        val tokens = mutableListOf<String?>()
        var uploads = 0
        var downloads = 0
        var status: Int? = null
        var unauthorised = 0
        override suspend fun upload(url: String, headers: Map<String, String>, info: ByteArray, file: File,
                                    filename: String, progress: (Long) -> Unit): HttpResponse {
            uploads++; log += "upload"; tokens += headers["Authorization"]
            if (unauthorised-- > 0) return HttpResponse(401, emptyMap(), ByteArray(0))
            status?.let { return HttpResponse(it, emptyMap(), "{\"error\":\"file exceeds the configured maximum\"}".toByteArray()) }
            assertEquals("$base/mcdata/files", url)
            val id = UUID.randomUUID().toString().replace("-", "")
            val copy = tmp.newFile().also { file.copyTo(it, overwrite = true) }
            val location = "$base/mcdata/files/$id/$filename"
            stored[location] = copy
            progress(file.length())
            return HttpResponse(201, mapOf("Location" to listOf(location)), ByteArray(0))
        }
        override suspend fun download(url: String, headers: Map<String, String>, destination: File, maxBytes: Long?,
                                      progress: (Long) -> Unit): HttpResponse {
            downloads++; tokens += headers["Authorization"]; yield()
            if (unauthorised-- > 0) return HttpResponse(401, emptyMap(), ByteArray(0))
            val file = stored[url] ?: return HttpResponse(404, emptyMap(), ByteArray(0))
            destination.parentFile.mkdirs()
            file.copyTo(destination, overwrite = true)
            progress(file.length())
            return HttpResponse(200, emptyMap(), ByteArray(0))
        }
    }

    private fun service(scope: CoroutineScope, peer: FdPeer, files: FileHttpClient, config: FdConfig = FdConfig(contentServer = base, tdu2Seconds = 5),
                        persistence: FdPersistence = FdPersistence.None) =
        FdService(scope, peer, config, files, tmp.newFolder(), persistence, clock = { 1_790_000_000 })

    private fun staged(text: String = "hello attachment", name: String = "photo.jpg"): Pair<File, FileMetadata> {
        val file = tmp.newFile().apply { writeText(text) }
        return file to FileMetadata(name, "image/jpeg", file.length(), FdService.sha256(file))
    }

    private fun request(parts: List<Part>): SipRequest {
        val (type, body) = Multipart.encode(parts)
        val headers = Headers().add("Call-ID", "x@y").add("CSeq", "1 MESSAGE")
            .add("Accept-Contact", "*;+g.3gpp.mcdata.fd;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCDATA_FD)}\";require;explicit")
            .add("Content-Type", type)
        return SipRequest("MESSAGE", "sip:bob@mcx.test", headers, body)
    }

    private fun offer(url: String, metadata: FileMetadata, from: String = "sip:alice@mcx.test", group: String? = null,
                      id: UUID = UUID.randomUUID(), mandatory: Boolean = false) = request(listOf(
        Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingUserId = from, callingGroupId = group).encode().toByteArray()),
        Part(McdataService.SIGNALLING, FdSignalling(1_790_000_000, UUID.randomUUID(), id, url, metadata.encode(),
            completionRequested = true, mandatoryDownload = mandatory).encode())))

    private fun signalling(parts: List<Part>) = parts.single { it.mediaType == McdataService.SIGNALLING }.content
    private fun notices(peer: FakePeer) = peer.sent.map { signalling(it) }.filter { MessageType.of(it) == 6 }.map { FdNotification.decode(it).type }

    @Test fun uploadThenOneToOneOfferHasFdIcsiAndUrl() = runTest {
        val peer = FakePeer(); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged(name = "Ünïcode \"photo\".jpg")
        val sent = fd.send("sip:bob@mcx.test", false, file, metadata)
        assertEquals(FdState.SENT, sent.state)
        assertEquals(listOf("upload", "sip"), log)
        val parts = peer.sent.single()
        assertEquals("one-to-one-fd", McdataInfo.parse(parts.first { it.mediaType == McdataInfo.CONTENT_TYPE }.text).requestType)
        assertEquals(listOf("sip:bob@mcx.test"), ResourceLists.parse(parts.first { it.mediaType == ResourceLists.CONTENT_TYPE }.text))
        val request = FdSignalling.decode(signalling(parts))
        assertEquals(sent.url, request.fileUrl)
        assertTrue(request.fileUrl.endsWith("/_n_code__photo_.jpg"))
        assertTrue(request.completionRequested)
        val offered = FileMetadata.parse(request.metadata!!)
        assertEquals(metadata.size, offered.size); assertEquals(metadata.sha256, offered.sha256)
        assertEquals(sent.id, request.messageId.toString())
        assertEquals("Bearer t1", files.tokens.single())
    }

    @Test fun groupOfferUsesGroupAndClientId() = runTest {
        val peer = FakePeer(); val files = FakeFiles()
        peer.policies = mapOf("sip:ops@mcx.test" to GroupFdPolicy(true, true, 1_000))
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged()
        assertEquals(FdState.SENT, fd.send("sip:ops@mcx.test", true, file, metadata).state)
        val info = McdataInfo.parse(peer.sent.single().first { it.mediaType == McdataInfo.CONTENT_TYPE }.text)
        assertEquals("group-fd", info.requestType); assertEquals("sip:ops@mcx.test", info.requestUri); assertEquals(peer.clientId, info.clientId)
        assertTrue(peer.sent.single().none { it.mediaType == ResourceLists.CONTENT_TYPE })
        // The group limit and switch apply before any upload.
        peer.policies = mapOf("sip:ops@mcx.test" to GroupFdPolicy(true, true, 4))
        assertTrue(runCatching { fd.send("sip:ops@mcx.test", true, file, metadata) }.exceptionOrNull() is FdError)
        peer.policies = mapOf("sip:ops@mcx.test" to GroupFdPolicy(false, true, null))
        assertTrue(runCatching { fd.send("sip:ops@mcx.test", true, file, metadata) }.exceptionOrNull() is FdError)
        assertEquals(1, files.uploads)
    }

    @Test fun refusedUploadAndRequestAreFailures() = runTest {
        val peer = FakePeer(); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged()
        files.status = 413
        val refused = fd.send("sip:bob@mcx.test", false, file, metadata)
        assertEquals(FdState.FAILED, refused.state); assertTrue(refused.error!!.contains("413")); assertTrue(peer.sent.isEmpty())
        files.status = null
        peer.answer = { SipResponse(403, "Forbidden", Headers().add("Warning", "399 mcdata \"230 not authorised\"")) }
        val rejected = fd.send("sip:bob@mcx.test", false, file, metadata)
        assertEquals(FdState.FAILED, rejected.state); assertTrue(rejected.error!!.contains("230"))
    }

    @Test fun discoveryCorrelatesResponse() = runTest {
        val peer = FakePeer(); val files = FakeFiles()
        lateinit var fd: FdService
        peer.answer = { parts ->
            if (McdataInfo.parse(parts.first().text).requestType == "msf-disc-req") backgroundScope.launch {
                fd.receive(request(listOf(Part(McdataInfo.CONTENT_TYPE,
                    McdataInfo(requestType = "msf-disc-res", controllerPsi = base).encode().toByteArray()))))
            }
            SipResponse(if (parts.size == 1) 200 else 202, "OK")
        }
        fd = service(backgroundScope, peer, files, FdConfig(contentServer = null))
        val (file, metadata) = staged()
        assertEquals(FdState.SENT, fd.send("sip:bob@mcx.test", false, file, metadata).state)
        assertEquals(base, fd.contentServer())
        assertEquals(2, peer.sent.size)
    }

    @Test fun offerRespondsBeforeDownloadStartsAndCompletes() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged("binary\u0000bytesÿ")
        val url = "$base/mcdata/files/abc/photo.jpg"; files.stored[url] = file
        val id = UUID.randomUUID()
        assertEquals(200, fd.receive(offer(url, metadata, from = "sip:alice@mcx.test", id = id)))
        assertEquals(FdState.OFFERED, fd.record(id.toString())!!.state)
        assertEquals(0, files.downloads)
        val done = fd.download(id.toString())
        assertEquals(FdState.COMPLETED, done.state)
        assertEquals(file.readBytes().toList(), File(done.localPath!!).readBytes().toList())
        assertEquals(listOf(FdNotificationType.ACCEPTED, FdNotificationType.COMPLETED), notices(peer))
        assertEquals(listOf("sip:alice@mcx.test"), ResourceLists.parse(peer.sent.last().first { it.mediaType == ResourceLists.CONTENT_TYPE }.text))
    }

    @Test fun duplicateOfferAndDoubleDownloadAreIdempotent() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged()
        val url = "$base/mcdata/files/abc/photo.jpg"; files.stored[url] = file
        val id = UUID.randomUUID()
        val request = offer(url, metadata, id = id)
        assertEquals(200, fd.receive(request)); assertEquals(200, fd.receive(request))
        assertEquals(1, fd.records.value.size)
        val results = listOf(async { fd.download(id.toString()) }, async { fd.download(id.toString()) }).awaitAll()
        assertTrue(results.all { it.state == FdState.COMPLETED })
        assertEquals(1, files.downloads)
        assertEquals(listOf(FdNotificationType.ACCEPTED, FdNotificationType.COMPLETED), notices(peer))
    }

    @Test fun completionRequiresMatchingBytesAndHash() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged("original")
        val url = "$base/mcdata/files/abc/photo.jpg"; files.stored[url] = tmp.newFile().apply { writeText("tampered") }
        val id = UUID.randomUUID()
        fd.receive(offer(url, metadata, id = id))
        val failed = fd.download(id.toString())
        assertEquals(FdState.FAILED, failed.state); assertTrue(failed.error!!.contains("hash"))
        assertNull(failed.localPath)
        assertEquals(listOf(FdNotificationType.ACCEPTED), notices(peer))
        // Retry once the right file is there: completion without a second ACCEPTED.
        files.stored[url] = file
        assertEquals(FdState.COMPLETED, fd.download(id.toString()).state)
        assertEquals(listOf(FdNotificationType.ACCEPTED, FdNotificationType.COMPLETED), notices(peer))
    }

    @Test fun expiredDeletedAndDeferredOffersUpdateState() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (_, metadata) = staged()
        val deferred = UUID.randomUUID(); val expired = UUID.randomUUID()
        fd.receive(offer("$base/mcdata/files/a/x", metadata, id = deferred))
        fd.receive(offer("$base/mcdata/files/b/x", metadata, id = expired))
        advanceTimeBy(5_001); runCurrent()
        assertEquals(FdState.DEFERRED, fd.record(deferred.toString())!!.state)
        assertEquals(listOf(FdNotificationType.DEFERRED, FdNotificationType.DEFERRED), notices(peer))
        val conversation = UUID.randomUUID()
        fd.receive(request(listOf(Part(McdataInfo.CONTENT_TYPE, McdataInfo(requestType = "notify").encode().toByteArray()),
            Part(McdataService.SIGNALLING, FdNetworkNotification(FdNetworkNotificationType.EXPIRED, 1, conversation, expired).encode()))))
        assertEquals(FdState.EXPIRED, fd.record(expired.toString())!!.state)
        assertFalse(fd.record(expired.toString())!!.downloadable)
        // A download of a file already removed is shown as expired too.
        assertEquals(FdState.EXPIRED, fd.download(deferred.toString()).state)
        // Rejection of a pending offer.
        val third = UUID.randomUUID()
        fd.receive(offer("$base/mcdata/files/c/x", metadata, id = third))
        assertEquals(FdState.REJECTED, fd.reject(third.toString()).state)
        assertEquals(FdNotificationType.REJECTED, notices(peer).last())
    }

    @Test fun mandatoryDownloadStartsAtOnce() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged()
        val url = "$base/mcdata/files/abc/photo.jpg"; files.stored[url] = file
        val id = UUID.randomUUID()
        fd.receive(offer(url, metadata, id = id, mandatory = true))
        runCurrent()
        assertEquals(FdState.COMPLETED, fd.record(id.toString())!!.state)
    }

    @Test fun groupNotificationsTrackEachRecipient() = runTest {
        val peer = FakePeer(); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged()
        val sent = fd.send("sip:ops@mcx.test", true, file, metadata)
        fun notice(type: FdNotificationType, from: String) = Part(McdataService.SIGNALLING,
            FdNotification(type, 1, UUID.fromString(sent.conversationId), UUID.fromString(sent.id), sender = from).encode())
        // One aggregated MESSAGE (TDC1), one body per notification.
        assertEquals(200, fd.receive(request(listOf(
            Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingGroupId = "sip:ops@mcx.test").encode().toByteArray()),
            notice(FdNotificationType.COMPLETED, "sip:bob@mcx.test"), notice(FdNotificationType.REJECTED, "sip:carol@mcx.test")))))
        assertEquals(mapOf("sip:bob@mcx.test" to FdNotificationType.COMPLETED, "sip:carol@mcx.test" to FdNotificationType.REJECTED),
            fd.record(sent.id)!!.notifications)
    }

    @Test fun unauthorizedHttpRetriesOnce() = runTest {
        val peer = FakePeer(); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (file, metadata) = staged()
        files.unauthorised = 1
        assertEquals(FdState.SENT, fd.send("sip:bob@mcx.test", false, file, metadata).state)
        assertEquals(listOf("Bearer t1", "Bearer t2"), files.tokens); assertEquals(1, peer.refreshes)
        files.unauthorised = 2
        val failed = fd.send("sip:bob@mcx.test", false, file, metadata)
        assertEquals(FdState.FAILED, failed.state); assertTrue(failed.error!!.contains("401"))
        assertEquals(2, peer.refreshes)
    }

    @Test fun untrustedUrlNeverReceivesToken() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val files = FakeFiles()
        val fd = service(backgroundScope, peer, files)
        val (_, metadata) = staged()
        for (url in listOf("https://evil.example/content/mcdata/files/a/x", "http://http-proxy:8443/content/mcdata/files/a/x",
                           "https://http-proxy:9443/content/mcdata/files/a/x", "https://http-proxy:8443/other/mcdata/files/a/x",
                           "https://user@http-proxy:8443/content/mcdata/files/a/x", "https://http-proxy:8443/content/mcdata/files/../../idms")) {
            val id = UUID.randomUUID()
            fd.receive(offer(url, metadata, id = id))
            val result = fd.download(id.toString())
            assertEquals(url, FdState.FAILED, result.state)
        }
        assertEquals(0, files.downloads)
        assertTrue(fd.trusted("https://HTTP-PROXY:8443/content/mcdata/files/a/x.jpg", base))
    }

    @Test fun malformedOffersAreRejectedAndForeignRequestsIgnored() = runTest {
        val peer = FakePeer("sip:bob@mcx.test"); val fd = service(backgroundScope, peer, FakeFiles())
        val bad = request(listOf(Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingUserId = "sip:alice@mcx.test").encode().toByteArray()),
            Part(McdataService.SIGNALLING, byteArrayOf(2, 0, 0))))
        assertEquals(400, fd.receive(bad))
        val sds = SipRequest("MESSAGE", "sip:bob@mcx.test", Headers().add("Accept-Contact", "*;+g.3gpp.mcdata.sds;require;explicit"))
        assertNull(fd.receive(sds))
        // An application's file is discarded, not offered.
        val app = request(listOf(Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingUserId = "sip:alice@mcx.test").encode().toByteArray()),
            Part(McdataService.SIGNALLING, FdSignalling(1, UUID.randomUUID(), UUID.randomUUID(), "$base/mcdata/files/a/x",
                staged().second.encode(), applicationId = 7).encode())))
        assertEquals(200, fd.receive(app))
        assertTrue(fd.records.value.isEmpty())
        // The sender's own group offer, should it come back, is not offered to it.
        assertEquals(200, fd.receive(offer("$base/mcdata/files/a/x", staged().second, from = "sip:bob@mcx.test", group = "sip:ops@mcx.test")))
        assertTrue(fd.records.value.isEmpty())
    }

    @Test fun restartMarksUnfinishedTransfersInterrupted() = runTest {
        val saved = mutableMapOf<String, FdRecord>()
        val (_, metadata) = staged()
        fun rec(state: FdState) = FdRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FdDirection.INCOMING,
            "sip:alice@mcx.test", null, metadata, url = "$base/mcdata/files/a/x", state = state)
        listOf(rec(FdState.DOWNLOADING), rec(FdState.COMPLETED), rec(FdState.OFFERED)).forEach { saved[it.id] = it }
        val store = object : FdPersistence {
            override fun load() = saved.values.toList()
            override fun put(record: FdRecord) { saved[record.id] = record }
        }
        val fd = service(backgroundScope, FakePeer(), FakeFiles(), persistence = store)
        assertEquals(listOf(FdState.INTERRUPTED, FdState.COMPLETED, FdState.OFFERED), fd.records.value.map { it.state })
        assertEquals(FdState.INTERRUPTED, saved[fd.records.value.first().id]!!.state)
        assertTrue(fd.records.value.first().downloadable)
        // Records survive a JSON round trip.
        fd.records.value.forEach { assertEquals(it, FdRecord.fromJson(it.toJson())) }
    }

    @Test fun clearForgetsFilesAndOnlyOwnCopies() = runTest {
        val saved = mutableMapOf<String, FdRecord>()
        val own = tmp.newFolder()
        val inside = File(own, "received.jpg").apply { writeText("x") }
        val outside = tmp.newFile().apply { writeText("y") }
        val (_, metadata) = staged()
        fun rec(path: File) = FdRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FdDirection.INCOMING,
            "sip:alice@mcx.test", null, metadata, localPath = path.path, state = FdState.COMPLETED)
        listOf(rec(inside), rec(outside)).forEach { saved[it.id] = it }
        val store = object : FdPersistence {
            override fun load() = saved.values.toList()
            override fun put(record: FdRecord) { saved[record.id] = record }
            override fun remove(id: String) { saved.remove(id) }
        }
        val fd = FdService(backgroundScope, FakePeer(), FdConfig(contentServer = base), FakeFiles(), own, store, clock = { 1_790_000_000 })
        assertEquals(2, fd.records.value.size)
        fd.clear()
        assertTrue(fd.records.value.isEmpty() && saved.isEmpty())
        assertTrue(!inside.exists() && outside.exists()) // only the service's own copies are deleted
    }

    @Test fun namesAreSafeForHeadersAndPaths() {
        assertEquals("_etc_passwd", FdService.uploadName("../etc/passwd"))
        assertEquals("a__b_c.pdf", FdService.uploadName("a\r\nb\"c.pdf"))
        assertEquals("file", FdService.uploadName("日本"))
        assertEquals("_etc_passwd", FdService.localName("/etc/passwd"))
        assertEquals("日本.pdf", FdService.localName("日本.pdf"))
        assertEquals("file", FdService.localName(".."))
    }
}
