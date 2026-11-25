//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.Xml
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

// The MCData message store client and message notification client (TS 24.282 clause 21.2, the OMA NMS REST API and
// the OMA notification channel), and the control of the storage of communications (clause 21.3.2), as the Linux
// client's message_store.cpp.

/** A stored object, as the message store described it (OMA NMS clause 5.2.2.3): its folder, flags and attributes. */
data class StoredObject(val id: String, val folder: String, val flags: List<String>, val modSeq: Long,
                        val attributes: Map<String, String>, val parts: Int, val notified: Boolean = false) {
    val text get() = attributes["Text"] ?: attributes["Subject"] ?: ""
    val from get() = attributes["From"]
    val direction get() = attributes["Direction"]
    val group get() = attributes["Group-Id"]
    val date get() = attributes["Date"]
    val seen get() = "\\Seen" in flags
}

/** The control of communications storage (TS 24.282 clause 21.3.2.2), and its outcome. */
data class StorageControlResult(val action: String, val status: Int, val warning: String?)

/**
 * The message store client of TS 24.282 clause 21.2 on the user's box `{root}/nms/v1/{store}/{MCData ID}` (clause
 * 21.1), each request with the MCData access token (clause 21.2), and the notification client (clauses 21.2.19.1,
 * 21.2.12A.1, 21.2.22.1, 21.2.20.1, 21.2.13A.1). The local copy ([objects]) follows the synchronisation of clause
 * 21.2.17.1 and the notifications of clause 21.2.22.1.
 */
class MessageStoreClient(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val root: String,
    private val store: String,
    private val user: String,
    private val notificationServer: String?,
    private val token: () -> String?,
    private val event: (String) -> Unit = {},
    private val pollWaitS: Int = 8,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _objects = MutableStateFlow<Map<String, StoredObject>>(emptyMap())
    val objects: StateFlow<Map<String, StoredObject>> = _objects
    @Volatile var folders: Map<String, String> = emptyMap()
        private set
    /** The last request: method, URL, status. */
    @Volatile var last: Triple<String, String, Int>? = null
        private set
    @Volatile var creationCursor: String = "0"
        private set
    @Volatile var modSeq: Long = 0
        private set
    @Volatile var events = 0
        private set
    @Volatile var channel: String? = null
        private set
    @Volatile var channelUrl: String? = null
        private set
    @Volatile var subscription: String? = null
        private set
    @Volatile var restartToken: String? = null
        private set
    /** The last payload read: object, status, octets, SHA-256, text. */
    @Volatile var payload: Map<String, Any?>? = null
        private set
    private var poller: Job? = null

    val boxUrl: String get() = "${root.trimEnd('/')}/nms/v1/${enc(store)}/${enc(user)}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun url(kind: String, id: String) = if (id.startsWith("https://")) id else "$boxUrl/$kind/$id"
    private fun lastId(url: String) = url.trimEnd('/').substringAfterLast('/')

    private fun bearer(): Map<String, String> = mapOf("Authorization" to "Bearer ${token() ?: throw SdsError("not signed in")}")

    /** An NMS request with the access token (TS 24.282 clause 21.2); a JSON body as application/json. */
    suspend fun request(method: String, url: String, body: JsonElement? = null, contentType: String = "application/json",
                        raw: ByteArray? = null): HttpResponse {
        val headers = bearer().toMutableMap()
        val payload = body?.toString()?.toByteArray() ?: raw
        if (payload != null) headers["Content-Type"] = contentType
        val response = http.request(method, url, headers, payload)
        last = Triple(method, url, response.status)
        return response
    }

    private fun parse(response: HttpResponse): JsonObject = json.parseToJsonElement(response.text).jsonObject

    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    /** The summary of a stored object (OMA NMS clause 5.2.2.3) from its JSON. */
    private fun remember(o: JsonObject, notified: Boolean = false) {
        val id = lastId(o.str("resourceURL") ?: return)
        val attributes = (o["attributes"] as? JsonObject)?.get("attribute")?.jsonArray?.mapNotNull { a ->
            val e = a.jsonObject
            val name = e.str("name") ?: return@mapNotNull null
            name to ((e["value"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "")
        }?.toMap() ?: emptyMap()
        val flags = ((o["flags"] as? JsonObject)?.get("flag") as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        val parts = (o["payloadPart"] as? JsonArray)?.size ?: 0
        val folder = o.str("parentFolderPath") ?: o.str("parentFolder")?.let(::lastId) ?: ""
        val mod = (o["lastModSeq"] as? JsonPrimitive)?.longOrNull ?: 0
        _objects.value = _objects.value + (id to StoredObject(id, folder, flags, mod, attributes, parts, notified))
    }

    private fun criteria(vararg criterion: JsonObject, and: Boolean = false) = buildJsonObject {
        putJsonObject("selectionCriteria") {
            putJsonObject("searchCriteria") {
                put("criterion", JsonArray(criterion.toList()))
                if (and) put("operator", "And")
            }
        }
    }

    private fun criterion(type: String, value: String, name: String? = null) = buildJsonObject {
        put("type", type); name?.let { put("name", it) }; put("value", value)
    }

    /**
     * TS 24.282 clause 21.2.17.1 step 1: the search-based synchronisation: the objects created since the last cursor,
     * those deleted since the last mod-seq, then the known objects read again (clause 21.2.1.1) for their flags.
     */
    suspend fun sync(): Int {
        val search = "$boxUrl/objects/operations/search"
        val created = request("POST", search, criteria(criterion("CreatedObjects", creationCursor)))
        if (created.status != 200) throw SdsError("message store search: HTTP ${created.status}", created.status)
        val list = parse(created)["objectList"]?.jsonObject
        (list?.get("object") as? JsonArray)?.forEach { remember(it.jsonObject) }
        list?.str("creationCursor")?.let { creationCursor = it }
        val vanished = request("POST", search, criteria(criterion("VanishedObjects", modSeq.toString())))
        if (vanished.status == 200) {
            val refs = parse(vanished)["objectReferenceList"]?.jsonObject
            (refs?.get("objectReference") as? JsonArray)?.forEach { r ->
                r.jsonObject.str("resourceURL")?.let { _objects.value = _objects.value - lastId(it) }
            }
            (refs?.get("lastModSeq") as? JsonPrimitive)?.longOrNull?.let { modSeq = it }
        }
        for (id in _objects.value.keys.toList()) get(id)
        event("message store synchronised: ${_objects.value.size} objects")
        return _objects.value.size
    }

    /** TS 24.282 clause 21.2.1.1: one stored object. */
    suspend fun get(id: String): StoredObject? {
        val r = request("GET", url("objects", id))
        if (r.status == 200) parse(r)["object"]?.jsonObject?.let { remember(it) }
        else if (r.status == 404) _objects.value = _objects.value - lastId(id)
        return _objects.value[lastId(id)]
    }

    /** TS 24.282 clause 21.2.1.1: the payload of an object (or one payload part), kept as its size, hash and text. */
    suspend fun payload(id: String, part: String? = null): Map<String, Any?> {
        val r = request("GET", url("objects", id) + if (part != null) "/payloadParts/$part" else "/payload")
        val sha = MessageDigest.getInstance("SHA-256").digest(r.body).joinToString("") { "%02x".format(it) }
        val readable = DataPayloadText.of(r.header("Content-Type"), r.body)
        return mapOf("object" to id, "status" to r.status, "bytes" to r.body.size, "sha256" to sha,
            "content_type" to r.header("Content-Type"), "text" to readable).also { payload = it }
    }

    /** TS 24.282 clause 21.2.2.1: the objects whose attributes match name=value, or flag=<flag>. */
    suspend fun search(terms: List<String>): List<String> {
        val list = terms.map { t ->
            val eq = t.indexOf('=').takeIf { it > 0 } ?: throw SdsError("a search criterion is name=value or flag=<flag>")
            if (t.substring(0, eq) == "flag") criterion("Flag", t.substring(eq + 1)) else criterion("Attribute", t.substring(eq + 1), t.substring(0, eq))
        }
        val r = request("POST", "$boxUrl/objects/operations/search", criteria(*list.toTypedArray(), and = true))
        if (r.status != 200) throw SdsError("message store search: HTTP ${r.status}", r.status)
        val found = (parse(r)["objectList"]?.jsonObject?.get("object") as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        found.forEach { remember(it) }
        return found.mapNotNull { it.str("resourceURL")?.let(::lastId) }
    }

    /** TS 24.282 clause 21.2.3.1: a flag (\Seen, \Flagged, ...) set or removed on one object. */
    suspend fun flag(id: String, flag: String, set: Boolean = true): Int {
        val r = request(if (set) "PUT" else "DELETE", url("objects", id) + "/flags/" + enc(flag))
        get(id)
        return r.status
    }

    /** TS 24.282 clause 21.2.4.1: one object deleted, or several in bulk. */
    suspend fun delete(ids: List<String>): Int {
        val r = if (ids.size == 1) request("DELETE", url("objects", ids[0]))
        else request("POST", "$boxUrl/objects/operations/bulkDelete", buildJsonObject {
            putJsonObject("objectReferenceList") {
                putJsonArray("objectReference") { ids.forEach { add(buildJsonObject { put("resourceURL", url("objects", it)) }) } }
            }
        })
        if (r.status in 200..299) _objects.value = _objects.value - ids.map(::lastId).toSet()
        return r.status
    }

    /** TS 24.282 clause 21.2.23.1: the folder hierarchy, path to folder ID. */
    suspend fun folders(): Map<String, String> {
        val r = request("POST", "$boxUrl/folders/operations/search", buildJsonObject { putJsonObject("selectionCriteria") {} })
        if (r.status == 200) folders = (parse(r)["folderReferenceList"]?.jsonObject?.get("folderReference") as? JsonArray)
            ?.associate { f -> (f.jsonObject.str("path") ?: "") to lastId(f.jsonObject.str("resourceURL") ?: "") } ?: emptyMap()
        return folders
    }

    /** TS 24.282 clause 21.2.8.1: a new folder [name] under [parent] (the root when null). */
    suspend fun createFolder(name: String, parent: String? = null): Int =
        request("POST", "$boxUrl/folders", buildJsonObject { putJsonObject("folder") { put("name", name); parent?.let { put("parentFolder", url("folders", it)) } } }).status

    /** TS 24.282 clause 21.2.7.1. */
    suspend fun deleteFolder(id: String): Int = request("DELETE", url("folders", id)).status

    /** TS 24.282 clauses 21.2.6.1 and 21.2.10.1: objects moved or copied to a folder. */
    suspend fun moveOrCopy(move: Boolean, folder: String, ids: List<String>): Int {
        val r = request("POST", "$boxUrl/objects/operations/${if (move) "moveToFolder" else "copyToFolder"}", buildJsonObject {
            putJsonObject("targetSourceRef") {
                put("targetFolder", url("folders", folder))
                putJsonObject("sourceObjects") {
                    putJsonArray("objectReference") { ids.forEach { add(buildJsonObject { put("resourceURL", url("objects", it)) }) } }
                }
            }
        })
        ids.forEach { get(it) }
        return r.status
    }

    // The notification channel.

    /**
     * TS 24.282 clauses 21.2.19.1, 21.2.12A.1 and 21.2.22.1: a LongPolling channel at the notification server, a
     * subscription of the box naming the channel's callback, and the long poll.
     */
    suspend fun subscribe(lifetimeS: Int = 3600) {
        if (poller?.isActive == true) throw SdsError("already subscribed")
        val server = notificationServer ?: throw SdsError("no MCData notification server is configured")
        val created = request("POST", "${server.trimEnd('/')}/notificationchannel/v1/${enc(user)}/channels", buildJsonObject {
            putJsonObject("notificationChannel") {
                put("channelType", "LongPolling"); put("clientCorrelator", UUID.randomUUID().toString())
                put("applicationTag", "mcdata-msgstore")
                putJsonObject("channelData") { put("maxNotifications", 20) }
                put("channelLifetime", lifetimeS)
            }
        })
        if (created.status != 201) throw SdsError("notification channel: HTTP ${created.status}", created.status)
        val c = parse(created)["notificationChannel"]!!.jsonObject
        channel = c.str("resourceURL")
        channelUrl = (c["channelData"] as JsonObject).str("channelURL")
        val subscribed = request("POST", "$boxUrl/subscriptions", buildJsonObject {
            putJsonObject("nmsSubscription") {
                putJsonObject("callbackReference") { put("notifyURL", c.str("callbackURL") ?: ""); put("callbackData", user) }
                put("duration", lifetimeS); put("clientCorrelator", UUID.randomUUID().toString())
            }
        })
        if (subscribed.status != 201) {
            runCatching { channel?.let { request("DELETE", it) } }
            channel = null; channelUrl = null
            throw SdsError("message store subscription: HTTP ${subscribed.status}", subscribed.status)
        }
        val s = parse(subscribed)["nmsSubscription"]!!.jsonObject
        subscription = s.str("resourceURL")
        restartToken = s.str("restartToken")
        val poll = channelUrl ?: throw SdsError("the channel has no channelURL")
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // TS 33.180 annex B.12: each poll with the current access token; none is sent without one.
                if (token() == null) { delay(1000); continue }
                val r = runCatching { request("POST", "$poll?wait=$pollWaitS", buildJsonObject {}) }.getOrNull()
                if (r?.status == 200) runCatching { notified(parse(r)) }.onFailure { event("notification unreadable: ${it.message}") }
                else delay(1000)
            }
        }
        event("message store: subscribed, channel $channel")
    }

    /** TS 24.282 clause 21.2.22.1, on the response, step 1: the notifications keep the local copy in step. */
    internal fun notified(body: JsonObject) {
        for (n in (body["notificationList"] as? JsonArray) ?: return) {
            val list = n.jsonObject["nmsEventList"]?.jsonObject ?: continue
            for (e in (list["nmsEvent"] as? JsonArray) ?: continue) {
                val change = e.jsonObject
                events++
                change["changedObject"]?.jsonObject?.let { o ->
                    val id = lastId(o.str("resourceURL") ?: return@let)
                    val known = _objects.value[id]
                    val flags = ((o["flags"] as? JsonObject)?.get("flag") as? JsonArray)?.map { it.jsonPrimitive.content } ?: known?.flags ?: emptyList()
                    val mod = (o["lastModSeq"] as? JsonPrimitive)?.longOrNull ?: known?.modSeq ?: 0
                    _objects.value = _objects.value + (id to (known?.copy(flags = flags, modSeq = mod, notified = true)
                        ?: StoredObject(id, "", flags, mod, emptyMap(), 0, notified = true)))
                    // A new object: its attributes are read (clause 21.2.1.1).
                    if (known == null) scope.launch { runCatching { get(id)?.let { g -> _objects.value = _objects.value + (id to g.copy(notified = true)) } } }
                }
                change["deletedObject"]?.jsonObject?.str("resourceURL")?.let { _objects.value = _objects.value - lastId(it) }
            }
        }
        event("message store: $events notified events")
    }

    /** TS 24.282 clauses 21.2.13A.1 and 21.2.20.1: the subscription and the channel deleted, the polling stopped. */
    suspend fun unsubscribe() {
        poller?.cancel(); poller = null
        subscription?.let { runCatching { request("DELETE", it) } }
        channel?.let { runCatching { request("DELETE", it) } }
        subscription = null; channel = null; channelUrl = null
    }

    /** TS 24.282 clause 21.2.14A.1 1): the subscription's lifetime extended. */
    suspend fun extendSubscription(durationS: Long): Int = request("POST", subscription ?: throw SdsError("not subscribed"),
        buildJsonObject { putJsonObject("nmsSubscriptionUpdate") { put("duration", durationS) } }).status

    /** TS 24.282 clause 21.2.21.1: the notification channel's new lifetime. */
    suspend fun channelLifetime(seconds: Long): Int = request("PUT", (channel ?: throw SdsError("no notification channel")) + "/channelLifetime",
        buildJsonObject { putJsonObject("notificationChannelLifetime") { put("channelLifetime", seconds) } }).status

    val subscribed get() = poller?.isActive == true

    fun stop() { poller?.cancel() }
}

/** The text of a stored payload: the TEXT payloads of a multipart MCData body, or plain text. */
internal object DataPayloadText {
    fun of(contentType: String?, body: ByteArray): String? = runCatching {
        if (contentType?.startsWith("multipart/") == true) {
            Multipart.parse(contentType, body).firstOrNull { it.mediaType == McdataService.PAYLOAD }?.let { p ->
                if (p.content.isNotEmpty() && (p.content[0].toInt() and 0x40) == 0)
                    DataPayload.decode(p.content).payloads.filter { it.type == PayloadType.TEXT }.joinToString("\n") { it.text }
                else null
            }
        } else if (contentType?.startsWith("text/") == true) String(body) else null
    }.getOrNull()
}

/**
 * TS 24.282 clause 21.3.2.2: the SIP MESSAGE that enables or disables the storage of this user's communications into
 * the message store: mcdata-info with request type "store-comms-in-msgstore-ctrl-req", the client ID, the alias in
 * use, and all private / all group, or the users and groups of an application/vnd.3gpp.mcdata-msgstore-ctrl-request+xml
 * body (Annex D.7). Without <allow-store-comms-in-msgstore> the server refuses it (403, warning 234).
 */
object StorageControl {
    const val CONTENT_TYPE = "application/vnd.3gpp.mcdata-msgstore-ctrl-request+xml"
    const val NAMESPACE = "urn:3gpp:ns:msgstoreControlRequest:1.0"

    /** The bodies of the request: [enable], all private / all group, or the users and groups named. */
    fun parts(enable: Boolean, allPrivate: Boolean, allGroup: Boolean, users: List<String>, groups: List<String>, clientId: String,
              alias: String?): List<Part> {
        val action = if (enable) "enable" else "disable"
        val info = McdataInfo(requestType = "store-comms-in-msgstore-ctrl-req", clientId = clientId, functionalAlias = alias,
            storeAllPrivate = if (allPrivate) enable else null, storeAllGroup = if (allGroup) enable else null,
            storeSpecificPrivate = action.takeIf { users.isNotEmpty() }, storeSpecificGroup = action.takeIf { groups.isNotEmpty() })
        val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
        if (users.isNotEmpty() || groups.isNotEmpty()) parts += Part(CONTENT_TYPE, controlXml(enable, users, groups).toByteArray())
        return parts
    }

    /** TS 24.282 Annex D.7: <msgstore-ctrl-command-list> with its <enable> or <disable> list of groups and users. */
    fun controlXml(enable: Boolean, users: List<String>, groups: List<String>): String =
        Xml.document("msgstore-ctrl-command-list", NAMESPACE) {
            element(if (enable) "enable" else "disable") {
                groups.forEach { leaf("group", it) }
                users.forEach { leaf("private", it) }
            }
        }

    /** The request: a MESSAGE to the MCData PSI with the MCData ICSI. */
    suspend fun send(client: McxClient, parts: List<Part>, action: String): StorageControlResult {
        val config = client.config.mcdata ?: throw SdsError("MCData not provisioned")
        val request = client.ua.newRequest("MESSAGE", config.psi, NameAddr(config.psi))
        request.headers.add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCDATA)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCDATA)
        val (type, body) = if (parts.size == 1) parts[0].contentType to parts[0].content else Multipart.encode(parts)
        request.headers.add("Content-Type", type)
        val response: SipResponse = client.ua.send(SipRequest(request.method, request.uri, request.headers, body))
        client.event("mcdata: storage control $action: ${response.status}")
        return StorageControlResult(action, response.status, response.headers["Warning"])
    }
}
