//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ServiceHandler
import org.openmcx.client.http.FileHttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FdError(message: String) : Exception(message)

/** What the FD service needs from the MCX client: its identity, the MCData PSI, tokens and group policy. */
interface FdPeer {
    val userId: String
    val clientId: String
    /** Send a MESSAGE with the FD feature tag and ICSI (TS 24.282 §6.2.4.1) to the MCData PSI. */
    suspend fun send(parts: List<Part>): SipResponse
    /** [send] with header fields (a Reason, TS 24.282 §13.2.6.3.2.1 step 3). */
    suspend fun send(parts: List<Part>, headers: List<Pair<String, String>>): SipResponse = send(parts)
    fun token(): String?
    suspend fun refreshToken()
    fun groupPolicy(group: String): GroupFdPolicy?
    fun event(text: String)
    /** TS 24.282 §6.6.3 with TS 33.180 §8.3: end-to-end payload protection, null without. */
    val protection: McdataProtection? get() = null
    /** TS 24.282 §10.2.4.2.1 1)b)ii): the functional alias in use, while it is active for the user; null otherwise. */
    fun aliasInUse(): String? = null
    /** [aliasInUse], once the activation of the alias the user chose to use is in (McxClient.awaitAlias). */
    suspend fun awaitAlias(): String? = aliasInUse()
    /** TS 24.282 §10.2.1.1 1) -> §11.1 (McdataTransmission.fd): throws when the file of [size] octets is not allowed. */
    suspend fun transmission(target: String, group: Boolean, size: Long) {}
}

/** [FdPeer] on the running [McxClient]. */
class ClientFdPeer(private val client: McxClient) : FdPeer {
    private val mcdata = client.config.mcdata!!
    override val userId get() = mcdata.userId
    override val clientId get() = client.config.clientId
    override suspend fun send(parts: List<Part>): SipResponse = send(parts, emptyList())
    override suspend fun send(parts: List<Part>, headers: List<Pair<String, String>>): SipResponse {
        val request = client.ua.newRequest("MESSAGE", mcdata.psi, NameAddr(mcdata.psi))
        // TS 24.282 §6.4: one MIME body goes as it is, several in a multipart/mixed (the msf-disc-req has one).
        val (type, body) = if (parts.size == 1) parts[0].contentType to parts[0].content else Multipart.encode(parts)
        request.headers.add("Accept-Contact", "*;+g.3gpp.mcdata.fd;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCDATA_FD)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCDATA_FD)
            .add("Content-Type", type)
        headers.forEach { (k, v) -> request.headers.add(k, v) }
        return client.ua.send(SipRequest(request.method, request.uri, request.headers, body))
    }
    override fun token() = client.tokens?.accessToken
    override suspend fun refreshToken() = client.refreshNow("token")
    override fun groupPolicy(group: String) = client.groupDocuments[group]?.fd
    override fun event(text: String) = client.event("fd: $text")
    override val protection get() = client.mcdataProtection
    override fun aliasInUse() = client.aliasesOf(org.openmcx.client.body.McService.MCDATA).value.let { a -> a.inUse?.takeIf { it in a.active } }
    override suspend fun awaitAlias() = client.awaitAlias(org.openmcx.client.body.McService.MCDATA)
    override suspend fun transmission(target: String, group: Boolean, size: Long) = McdataTransmission.fd(client, target, group, size)
}

/**
 * MCData file distribution over HTTP (TS 24.282 §10.2): discovery of the media storage function
 * (§10.2.1.3), upload (§10.2.2), the FD request (§10.2.4), reception with TDU2 (§10.2.1.2), download
 * (§10.2.3), FD notifications (§12.2) and network notifications (§12.4). File bytes stay on disk.
 */
class FdService(
    private val scope: CoroutineScope,
    private val peer: FdPeer,
    private val config: FdConfig,
    private val files: FileHttpClient,
    private val directory: File,
    private val persistence: FdPersistence = FdPersistence.None,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : ServiceHandler {
    private val _records = MutableStateFlow<List<FdRecord>>(emptyList())
    val records: StateFlow<List<FdRecord>> = _records
    /** Called for each new incoming offer (for a user notification). */
    var onOffer: ((FdRecord) -> Unit)? = null

    @Volatile private var discovered: String? = null
    @Volatile private var discovery: CompletableDeferred<String>? = null
    private val discoveryLock = Mutex()
    private val transfers = ConcurrentHashMap<String, Job>()
    private val tdu2 = ConcurrentHashMap<String, Job>()

    init { restore(persistence.load()) }

    private fun event(text: String) = peer.event(text)
    fun record(id: String): FdRecord? = _records.value.firstOrNull { it.id == id }

    /**
     * The user cleared the messages: forget every file that is not being transferred, with its stored record and
     * its copy on this phone (only copies in this service's own directory). An unanswered offer is dropped too.
     */
    fun clear() {
        val gone = _records.value.filter { !it.state.active && !transfers.containsKey(it.id) }
        if (gone.isEmpty()) return
        val ids = gone.map { it.id }.toSet()
        _records.update { list -> list.filter { it.id !in ids } }
        val own = directory.canonicalFile
        for (r in gone) {
            tdu2.remove(r.id)?.cancel()
            persistence.remove(r.id)
            r.localPath?.let { File(it).canonicalFile }?.takeIf { it.path.startsWith(own.path + File.separator) }?.delete()
        }
        event("cleared ${gone.size} file(s)")
    }

    /** Take records back after a restart: a transfer that was running is now interrupted. */
    fun restore(records: List<FdRecord>) {
        val restored = records.map { if (it.state.active || it.state == FdState.STAGED) it.copy(state = FdState.INTERRUPTED, error = "interrupted") else it }
        restored.zip(records).filter { (a, b) -> a != b }.forEach { persistence.put(it.first) }
        _records.value = (restored + _records.value.filter { r -> restored.none { it.id == r.id } }).sortedBy { it.at }.takeLast(MAX_RECORDS)
    }

    private fun add(record: FdRecord) {
        _records.update { (it + record).takeLast(MAX_RECORDS) }
        persistence.put(record)
    }

    private fun update(id: String, persist: Boolean = true, change: (FdRecord) -> FdRecord): FdRecord? {
        var result: FdRecord? = null
        _records.update { list -> list.map { if (it.id == id) change(it).also { r -> result = r } else it } }
        if (persist) result?.let(persistence::put)
        return result
    }

    private fun progress(id: String, size: Long): (Long) -> Unit {
        val step = maxOf(64L * 1024, size / 100)
        var reported = 0L
        return { n -> if (n - reported >= step || n == size) { reported = n; update(id, persist = false) { it.copy(transferred = n) } } }
    }

    // Media storage function (§10.2.1.3, §10.2.2.1).

    /** The absolute URI of the media storage function: provisioned, or discovered. */
    suspend fun contentServer(group: String? = null): String = config.contentServer ?: discovered ?: discover(group)

    /** Ask the MCData server for the media storage function's URI (msf-disc-req, §10.2.1.3.1). */
    suspend fun discover(group: String? = null): String = discoveryLock.withLock {
        val answer = CompletableDeferred<String>()
        discovery = answer
        try {
            val info = McdataInfo(requestType = "msf-disc-req", callingGroupId = group, clientId = peer.clientId)
            val response = peer.send(listOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray())))
            if (!response.isSuccess) throw FdError("media storage discovery: ${status(response)}")
            val uri = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { answer.await() } ?: throw FdError("media storage discovery: no answer")
            val parsed = runCatching { URI(uri) }.getOrNull()
            if (parsed == null || parsed.scheme !in setOf("https", "http") || parsed.host == null) throw FdError("media storage discovery: invalid URI")
            discovered = uri.trimEnd('/')
            event("media storage function $uri")
            discovered!!
        } finally { discovery = null }
    }

    /**
     * Whether [url] is a file of the trusted media storage function, so that the access token
     * may be sent to it: same scheme, host and port, under its `/mcdata/files/` path.
     */
    fun trusted(url: String, base: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val b = runCatching { URI(base.trimEnd('/')) }.getOrNull() ?: return false
        fun port(x: URI) = if (x.port > 0) x.port else if (x.scheme == "https") 443 else 80
        val path = u.rawPath ?: return false
        return u.scheme == b.scheme && u.scheme in setOf("https", "http") && u.userInfo == null && u.fragment == null &&
            u.host != null && u.host.equals(b.host, true) && port(u) == port(b) &&
            path.startsWith((b.rawPath ?: "") + "/mcdata/files/") && path.split('/').none { it == ".." || it == "." }
    }

    /** An HTTP exchange with the access token, retried once after a 401 and a token renewal. */
    private suspend fun authorised(exchange: suspend (Map<String, String>) -> HttpResponse): HttpResponse {
        fun headers() = mapOf("Authorization" to "Bearer ${peer.token() ?: throw FdError("no access token")}")
        val first = exchange(headers())
        if (first.status != 401) return first
        event("HTTP 401: renewing the access token")
        peer.refreshToken()
        return exchange(headers())
    }

    // Sending (§10.2.2.1, §10.2.4.2.1).

    /**
     * Upload [file] and send its FD request to [target], a user or (with [group]) a group. Returns
     * the record, SENT when the MCData server accepted the request and FAILED otherwise.
     */
    suspend fun send(target: String, group: Boolean, file: File, metadata: FileMetadata): FdRecord {
        val size = file.length()
        if (!file.isFile || size != metadata.size) throw FdError("the staged file does not match its metadata")
        try {
            if (group) {
                val policy = peer.groupPolicy(target)
                if (policy != null) FdPolicy.requireAllowed(config, policy, size)
                else FdPolicy.requireAllowed(config.copy(userMaxBytes = null), null, size)
            } else FdPolicy.requireAllowed(config, null, size)
            // TS 24.282 §10.2.1.1 1) -> §11.1 2), 4), 6), §10.2.4.2.1 3)a): the service configuration and group document.
            peer.transmission(target, group, size)
        } catch (e: IllegalArgumentException) { throw FdError(e.message ?: "file not allowed") }
        catch (e: SdsError) { throw FdError(e.message ?: "file not allowed") }
        val record = FdRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FdDirection.OUTGOING, target,
            if (group) target else null, metadata, localPath = file.path, state = FdState.STAGED, completionRequested = true)
        add(record)
        var sealed: File? = null
        return try {
            update(record.id) { it.copy(state = FdState.UPLOADING, transferred = 0, error = null) }
            // TS 33.180 §8.3 with TS 24.282 §10.2.2.1 steps 7 and 8: with `protect_payload` a one-to-one file is protected
            // with the PCK shared with the recipient before it is uploaded (a series of Protected Payload messages); its
            // metadata then describes the stored object, as the media storage function holds it. A group file is
            // uploaded in clear, as the Linux client.
            var toUpload = file
            var described = metadata
            var keyMessage: String? = null
            val protection = peer.protection?.takeIf { it.protectsPayload && !group }
            if (protection != null) {
                val (key, message) = try { protection.pckFor(target) } catch (e: Exception) { throw FdError("file protection: ${e.message}") }
                keyMessage = message
                val bytes = protection.sealFile(file.readBytes(), key)
                val stored = File(directory, "sealed/${record.id}").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
                sealed = stored
                toUpload = stored
                described = metadata.copy(size = bytes.size.toLong(), sha256 = sha256(stored))
            }
            val base = contentServer(if (group) target else null)
            val info = McdataInfo(requestType = if (group) "group-fd" else "one-to-one-fd", requestUri = if (group) target else null,
                callingUserId = peer.userId)
            val uploaded = toUpload
            val sent = described
            val upload = authorised { headers ->
                files.upload("$base/mcdata/files", headers, info.encode().toByteArray(), uploaded, uploadName(metadata.name), progress(record.id, sent.size))
            }
            if (upload.status != 201) throw FdError("upload refused: HTTP ${upload.status}${httpReason(upload)}")
            val url = upload.header("Location")?.trim() ?: throw FdError("upload answered without a file URL")
            if (!trusted(url, base)) throw FdError("upload answered with an untrusted file URL")
            update(record.id) { it.copy(state = FdState.SENDING, url = url, transferred = size) }
            val signalling = FdSignalling(clock(), UUID.fromString(record.conversationId), UUID.fromString(record.id), url,
                described.encode(), completionRequested = true)
            // TS 24.282 §10.2.4.2.1 1)b)ii) (§22): the functional alias in use, while active; the client ID (as Linux).
            // An alias chosen while its activation is awaited is waited for (TS 37.579-7 6.2.14 step 2).
            val alias = peer.awaitAlias()
            val requestInfo = if (group) McdataInfo(requestType = "group-fd", requestUri = target, clientId = peer.clientId, functionalAlias = alias)
            else McdataInfo(requestType = "one-to-one-fd", clientId = peer.clientId, functionalAlias = alias)
            val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, requestInfo.encode().toByteArray()))
            if (!group) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray())
            parts += Part(McdataService.SIGNALLING, signalling.encode())
            keyMessage?.let { parts += Part(McdataProtection.MIKEY, it.toByteArray()) }
            val response = peer.send(parts)
            if (!response.isSuccess) throw FdError("FD request: ${status(response)}")
            event("sent ${metadata.name} (${metadata.size} bytes) to $target: ${response.status}")
            update(record.id) { it.copy(state = FdState.SENT) }!!
        } catch (e: CancellationException) {
            withContext(NonCancellable) { update(record.id) { it.copy(state = FdState.INTERRUPTED, error = "cancelled") } }
            throw e
        } catch (e: Exception) {
            event("send ${record.id} failed: ${e.message}")
            update(record.id) { it.copy(state = FdState.FAILED, error = e.message ?: e.toString()) }!!
        } finally {
            sealed?.delete()
        }
    }

    // Receiving (§10.2.1.2, §10.2.3.1).

    /** Download an offered file (accepting it), verify it, and report completion when asked. */
    suspend fun download(id: String): FdRecord {
        val current = record(id) ?: throw FdError("no file $id")
        if (current.state == FdState.COMPLETED) return current
        val job = scope.launch(start = CoroutineStart.LAZY) { fetch(id) }
        val running = transfers.putIfAbsent(id, job)
        if (running != null) { job.cancel(); running.join(); return record(id)!! }
        if (!current.downloadable) { transfers.remove(id, job); throw FdError("file ${current.metadata.name} is ${current.state.name.lowercase()}") }
        job.invokeOnCompletion { transfers.remove(id, job) }
        job.start()
        job.join()
        return record(id)!!
    }

    /** Stop a running download; the partial file is removed. */
    fun cancel(id: String) { transfers[id]?.cancel() }

    private suspend fun fetch(id: String) {
        tdu2.remove(id)?.cancel()
        val record = record(id)!!
        val partial = File(directory, "partial/$id")
        try {
            if (!record.accepted) {
                notify(record, FdNotificationType.ACCEPTED)
                update(id) { it.copy(accepted = true) }
            }
            update(id) { it.copy(state = FdState.DOWNLOADING, transferred = 0, error = null) }
            val url = record.url!!
            val base = contentServer(record.group)
            if (!trusted(url, base)) throw FdError("the file URL is not on the media storage function")
            val size = record.metadata.size
            val response = authorised { headers -> files.download(url, headers, partial, size, progress(id, size)) }
            if (response.status == 404) {
                update(id) { it.copy(state = FdState.EXPIRED, error = "the file is no longer available") }
                return
            }
            if (response.status != 200) throw FdError("download refused: HTTP ${response.status}${httpReason(response)}")
            if (partial.length() != size) throw FdError("received ${partial.length()} bytes, expected $size")
            record.metadata.sha256?.let { if (sha256(partial) != it) throw FdError("the file does not match its hash") }
            val target = File(directory, "files/$id/${localName(record.metadata.name)}")
            target.parentFile.mkdirs()
            // TS 24.282 §10.2.3.1: the hash describes the stored object; a protected one-to-one file is then opened with
            // the PCK shared with its sender (TS 33.180 §8.3), as the Linux client.
            val protection = peer.protection?.takeIf { it.protectsPayload && record.group == null }
            if (protection != null) {
                val plain = try { protection.openFile(partial.readBytes(), record.peer, null) }
                    catch (e: Exception) { protection.failures.incrementAndGet(); throw FdError("the protected file cannot be opened: ${e.message}") }
                target.writeBytes(plain)
                partial.delete()
            } else if (!partial.renameTo(target)) throw FdError("cannot store the file")
            val done = update(id) { it.copy(state = FdState.COMPLETED, localPath = target.path, transferred = size) }!!
            event("downloaded ${record.metadata.name} ($size bytes) from ${record.peer}")
            if (done.completionRequested) notify(done, FdNotificationType.COMPLETED)
        } catch (e: CancellationException) {
            partial.delete()
            withContext(NonCancellable) { update(id) { if (it.state == FdState.DOWNLOADING) it.copy(state = FdState.INTERRUPTED, error = "cancelled") else it } }
            throw e
        } catch (e: Exception) {
            partial.delete()
            event("download $id failed: ${e.message}")
            update(id) { it.copy(state = FdState.FAILED, error = e.message ?: e.toString()) }
        }
    }

    /** Decline an offered file (§10.2.1.2.3 step 6). */
    suspend fun reject(id: String): FdRecord {
        val record = record(id) ?: throw FdError("no file $id")
        if (!record.downloadable || transfers.containsKey(id)) throw FdError("file ${record.metadata.name} cannot be rejected now")
        tdu2.remove(id)?.cancel()
        val rejected = update(id) { it.copy(state = FdState.REJECTED) }!!
        notify(rejected, FdNotificationType.REJECTED)
        return rejected
    }

    // Release and extension (TS 24.282 §6.2.2.4, §13.2.1.2, §13.2.4.5, §13.2.5.3, §13.2.6.3).

    /**
     * An FD HTTP TERMINATION to the MCData server for the distribution [id]: "cancel" this user's own (§6.2.2.4,
     * termination request), "release" another user's as an authorised user (§13.2.5.3.2.1), "extend" after the server's
     * intent to release (§13.2.4.5.2.2, with the stored controller PSI), "release-intent" as the authorised user
     * (§13.2.6.3.2.1, with the Reason), "extension-response" to the originator's extension request (§13.2.6.3.2.3,
     * [accept]). mcdata-info carries the client ID, and the request type of an own distribution or the originator
     * (§13.2.1.2 step 5a); a one-to-one release names the recipient in resource-lists (§13.2.2.3.2.1.1 2)a)).
     */
    suspend fun terminate(id: String, action: String, accept: Boolean? = null, reason: String? = null): FdRecord {
        val record = record(id) ?: throw FdError("no file distribution $id")
        val type = when (action) {
            "cancel" -> TerminationType.REQUEST
            "release" -> TerminationType.AUTHORISED_USER_REQUEST
            "extend" -> TerminationType.EXTENSION_REQUEST
            "release-intent" -> TerminationType.INTENT_TO_RELEASE
            "extension-response" -> TerminationType.EXTENSION_RESPONSE
            else -> throw FdError("action: cancel, release, extend, release-intent or extension-response")
        }
        if (type == TerminationType.EXTENSION_RESPONSE && accept == null) throw FdError("accept or reject the extension")
        val own = !record.incoming
        val message = FdHttpTermination(UUID.fromString(record.conversationId), UUID.fromString(record.id), type,
            extensionAccepted = if (type == TerminationType.EXTENSION_RESPONSE) accept else null, fileUrl = record.url)
        val info = McdataInfo(
            requestType = if (own) (if (record.group != null) "group-fd" else "one-to-one-fd") else null,
            requestUri = if (own) record.group else record.peer,
            clientId = peer.clientId,
            controllerPsi = record.controllerPsi.takeIf { type == TerminationType.EXTENSION_REQUEST })
        val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (own && record.group == null) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(record.peer)).toByteArray())
        parts += Part(McdataService.SIGNALLING, message.encode())
        val response = peer.send(parts, listOfNotNull(reason?.let { "Reason" to "SIP;cause=200;text=\"$it\"" }))
        event("FD $action of $id: ${status(response)}")
        if (!response.isSuccess) throw FdError("FD $action: ${status(response)}")
        return update(id) { it.copy(release = if (type == TerminationType.REQUEST) "terminating" else "$action requested") }!!
    }

    /**
     * The server's FD HTTP TERMINATION: the answer to a release (§6.3.6.1), a transmission stopped by policy
     * (§13.2.3.3.4), the intent to release (§13.2.4.5.2.1, with the controller PSI for an extension request) and the
     * answer to an extension request (§13.2.4.5.2.3).
     */
    private fun termination(info: McdataInfo?, message: FdHttpTermination) {
        val id = message.messageId.toString()
        val state = when (message.type) {
            TerminationType.RESPONSE -> if (message.releaseSucceeded == true) "terminated" else "termination failed"
            TerminationType.TRANSMISSION_STOPPED -> "stopped"
            TerminationType.INTENT_TO_RELEASE -> "release pending"
            TerminationType.EXTENSION_RESPONSE -> if (message.extensionAccepted == true) "extended" else "extension refused"
            TerminationType.EXTENSION_REQUEST -> "extension requested"
            else -> message.type.name.lowercase()
        }
        event("FD $id: $state")
        update(id) { it.copy(release = state, controllerPsi = info?.controllerPsi ?: it.controllerPsi) }
    }

    /** An FD NOTIFICATION to the originator (§12.2.1.1). */
    private suspend fun notify(record: FdRecord, type: FdNotificationType) {
        val notice = FdNotification(type, clock(), UUID.fromString(record.conversationId), UUID.fromString(record.id),
            sender = peer.userId, applicationId = record.applicationId)
        val parts = listOf(Part(McdataInfo.CONTENT_TYPE, McdataInfo(callingGroupId = record.group).encode().toByteArray()),
            Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(record.peer)).toByteArray()),
            Part(McdataService.SIGNALLING, notice.encode()))
        val response = runCatching { peer.send(parts) }
        event("sent ${type.name} for ${record.id} to ${record.peer}: ${response.getOrNull()?.status ?: response.exceptionOrNull()?.message}")
    }

    override suspend fun handle(tx: ServerTransaction): Boolean {
        val (status, extra) = answer(tx.request) ?: return false
        tx.respond(status, extra = extra)
        return true
    }

    /** Handle an incoming request; null when it is not for file distribution, else the status to answer. */
    suspend fun receive(request: SipRequest): Int? = answer(request)?.first

    /** [receive], with the header fields of the answer. */
    private suspend fun answer(request: SipRequest): Pair<Int, List<Pair<String, String>>>? {
        if (request.method != "MESSAGE" || !isFd(request)) return null
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrElse { return 400 to emptyList() }
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
        // TS 33.180 §8.3: the PCK that protects a one-to-one file comes with the request (TS 24.282 §10.2.4.2.2); a
        // refused I_MESSAGE is answered 606 with warning 136, as the Linux client.
        for (part in parts.filter { it.mediaType == McdataProtection.MIKEY }) {
            val sender = info?.callingUserId ?: ""
            try {
                (peer.protection ?: throw FdError("MCData key management client unavailable")).acceptPck(part.text.trim(), sender)
            } catch (e: Exception) {
                peer.protection?.failures?.incrementAndGet()
                event("PCK from $sender refused: ${e.message}")
                return 606 to listOf("Warning" to McdataProtection.WARNING_136)
            }
        }
        return receiveParts(parts, info) to emptyList()
    }

    private fun receiveParts(parts: List<Part>, info: McdataInfo?): Int {
        if (info?.requestType == "msf-disc-res") {
            val uri = info.controllerPsi ?: return 400
            discovery?.complete(uri) ?: event("unsolicited discovery answer")
            return 200
        }
        val signalling = parts.filter { it.mediaType == McdataService.SIGNALLING }
        if (signalling.isEmpty()) return 400
        try {
            for (part in signalling) when (MessageType.of(part.content)) {
                FD_SIGNALLING -> offer(info ?: return 400, FdSignalling.decode(part.content))
                FD_NOTIFICATION -> notification(info, FdNotification.decode(part.content))
                FD_NETWORK_NOTIFICATION -> networkNotification(FdNetworkNotification.decode(part.content))
                FdHttpTermination.MESSAGE_TYPE -> termination(info, FdHttpTermination.decode(part.content))
                else -> event("ignored FD message type ${MessageType.of(part.content)}")
            }
        } catch (e: McdataCodecError) {
            event("bad FD message: ${e.message}"); return 400
        } catch (e: IllegalArgumentException) {
            event("bad FD message: ${e.message}"); return 400
        }
        return 200
    }

    private fun isFd(request: SipRequest) =
        request.headers.values("Accept-Contact").any { it.contains(Icsi.encoded(Icsi.MCDATA_FD)) || it.contains("+g.3gpp.mcdata.fd") } ||
            request.headers["P-Asserted-Service"]?.trim() == Icsi.MCDATA_FD

    private fun offer(info: McdataInfo, signalling: FdSignalling) {
        val id = signalling.messageId.toString()
        if (record(id) != null) { event("duplicate FD request $id"); return }
        // This client has no MCData applications: requests for one are discarded (§10.2.1.2.1).
        if (signalling.applicationId != null) { event("FD request $id for application ${signalling.applicationId} discarded"); return }
        val metadata = FileMetadata.parse(signalling.metadata ?: throw McdataCodecError("FD request without metadata"))
        val sender = info.callingUserId ?: signalling.sender ?: throw McdataCodecError("FD request without a sender")
        if (sender == peer.userId) return
        val record = FdRecord(id, signalling.conversationId.toString(), FdDirection.INCOMING, sender, info.callingGroupId, metadata,
            url = signalling.fileUrl, state = FdState.OFFERED, completionRequested = signalling.completionRequested,
            mandatory = signalling.mandatoryDownload)
        add(record)
        event("offered ${metadata.name} (${metadata.size} bytes) by $sender${record.group?.let { " on $it" } ?: ""}")
        onOffer?.invoke(record)
        if (record.mandatory) scope.launch { runCatching { download(id) } }
        else tdu2[id] = scope.launch {
            // §10.2.1.2.3: no decision before TDU2 expires defers the download.
            delay(config.tdu2Seconds * 1000L)
            tdu2.remove(id)
            val offered = record(id) ?: return@launch
            if (offered.state != FdState.OFFERED || transfers.containsKey(id)) return@launch
            notify(update(id) { it.copy(state = FdState.DEFERRED) }!!, FdNotificationType.DEFERRED)
        }
    }

    private fun notification(info: McdataInfo?, notice: FdNotification) {
        val id = notice.messageId.toString()
        val from = notice.sender ?: info?.callingUserId ?: "unknown"
        val sent = record(id)?.takeIf { !it.incoming }
        event("${notice.type.name} from $from for $id${if (sent == null) " (unknown file)" else ""}")
        if (sent != null) update(id) { it.copy(notifications = it.notifications + (from to notice.type)) }
    }

    private fun networkNotification(notice: FdNetworkNotification) {
        val id = notice.messageId.toString()
        val record = record(id) ?: return
        event("file $id ${notice.type.name.lowercase()} by the network")
        if (record.state == FdState.COMPLETED || !record.incoming) return
        tdu2.remove(id)?.cancel()
        transfers[id]?.cancel()
        update(id) { it.copy(state = FdState.EXPIRED, error = if (notice.type == FdNetworkNotificationType.EXPIRED) "the file expired" else "the file was deleted") }
    }

    companion object {
        const val FD_SIGNALLING = 0x02
        const val FD_NOTIFICATION = 0x06
        const val FD_NETWORK_NOTIFICATION = 0x09
        const val MAX_RECORDS = 500
        const val DISCOVERY_TIMEOUT_MS = 10_000L

        private fun status(response: SipResponse) = "${response.status} ${response.reason}" +
            (response.headers["Warning"]?.let { " ($it)" } ?: "")

        private fun httpReason(response: HttpResponse): String =
            Regex("\"error\"\\s*:\\s*\"([^\"]{1,200})\"").find(response.text)?.groupValues?.get(1)?.let { " ($it)" } ?: ""

        /** The name in the upload's Content-Disposition, and so in the file URL: ASCII letters, digits, `.`, `_`, `-`. */
        fun uploadName(name: String): String {
            val safe = name.map { if (it.code < 128 && (it.isLetterOrDigit() || it in "._-")) it else '_' }.joinToString("")
                .trimStart('.').takeLast(100)
            return if (safe.none { it.isLetterOrDigit() }) "file" else safe
        }

        /** A file name for local storage: no path separators or control characters. */
        fun localName(name: String): String {
            val safe = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").trim().trimStart('.').take(120)
            return safe.ifEmpty { "file" }
        }

        /** The SHA-256 of [data] in lower-case hex (RFC 5547 hash:sha-256, as [FileMetadata] holds it). */
        fun sha256Bytes(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
