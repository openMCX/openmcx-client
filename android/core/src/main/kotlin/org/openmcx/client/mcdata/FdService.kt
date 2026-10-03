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
    fun token(): String?
    suspend fun refreshToken()
    fun groupPolicy(group: String): GroupFdPolicy?
    fun event(text: String)
}

/** [FdPeer] on the running [McxClient]. */
class ClientFdPeer(private val client: McxClient) : FdPeer {
    private val mcdata = client.config.mcdata!!
    override val userId get() = mcdata.userId
    override val clientId get() = client.config.clientId
    override suspend fun send(parts: List<Part>): SipResponse {
        val request = client.ua.newRequest("MESSAGE", mcdata.psi, NameAddr(mcdata.psi))
        val (type, body) = Multipart.encode(parts)
        request.headers.add("Accept-Contact", "*;+g.3gpp.mcdata.fd;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCDATA_FD)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCDATA_FD)
            .add("Content-Type", type)
        return client.ua.send(SipRequest(request.method, request.uri, request.headers, body))
    }
    override fun token() = client.tokens?.accessToken
    override suspend fun refreshToken() = client.refreshNow("token")
    override fun groupPolicy(group: String) = client.groupDocuments[group]?.fd
    override fun event(text: String) = client.event("fd: $text")
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
        } catch (e: IllegalArgumentException) { throw FdError(e.message ?: "file not allowed") }
        val record = FdRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FdDirection.OUTGOING, target,
            if (group) target else null, metadata, localPath = file.path, state = FdState.STAGED, completionRequested = true)
        add(record)
        return try {
            update(record.id) { it.copy(state = FdState.UPLOADING, transferred = 0, error = null) }
            val base = contentServer(if (group) target else null)
            val info = McdataInfo(requestType = if (group) "group-fd" else "one-to-one-fd", requestUri = if (group) target else null,
                callingUserId = peer.userId)
            val upload = authorised { headers ->
                files.upload("$base/mcdata/files", headers, info.encode().toByteArray(), file, uploadName(metadata.name), progress(record.id, size))
            }
            if (upload.status != 201) throw FdError("upload refused: HTTP ${upload.status}${httpReason(upload)}")
            val url = upload.header("Location")?.trim() ?: throw FdError("upload answered without a file URL")
            if (!trusted(url, base)) throw FdError("upload answered with an untrusted file URL")
            update(record.id) { it.copy(state = FdState.SENDING, url = url, transferred = size) }
            val signalling = FdSignalling(clock(), UUID.fromString(record.conversationId), UUID.fromString(record.id), url,
                metadata.encode(), completionRequested = true)
            val requestInfo = if (group) McdataInfo(requestType = "group-fd", requestUri = target, clientId = peer.clientId)
            else McdataInfo(requestType = "one-to-one-fd")
            val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, requestInfo.encode().toByteArray()))
            if (!group) parts += Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(target)).toByteArray())
            parts += Part(McdataService.SIGNALLING, signalling.encode())
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
            if (!partial.renameTo(target)) throw FdError("cannot store the file")
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
        val status = receive(tx.request) ?: return false
        tx.respond(status)
        return true
    }

    /** Handle an incoming request; null when it is not for file distribution, else the status to answer. */
    suspend fun receive(request: SipRequest): Int? {
        if (request.method != "MESSAGE" || !isFd(request)) return null
        val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrElse { return 400 }
        val info = parts.firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }?.let { runCatching { McdataInfo.parse(it.text) }.getOrNull() }
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
