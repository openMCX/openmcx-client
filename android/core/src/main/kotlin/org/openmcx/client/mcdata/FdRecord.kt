//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.serialization.json.*

enum class FdDirection { OUTGOING, INCOMING }

/**
 * Where one file distribution stands. Outgoing: STAGED → UPLOADING → SENDING → SENT (or FAILED).
 * Incoming: OFFERED (or DEFERRED after TDU2) → DOWNLOADING → COMPLETED, or REJECTED, EXPIRED
 * (the network withdrew the file), FAILED (a retry is possible) or INTERRUPTED (the process stopped).
 */
enum class FdState {
    STAGED, UPLOADING, SENDING, SENT, OFFERED, DEFERRED, DOWNLOADING, COMPLETED, REJECTED, EXPIRED, FAILED, INTERRUPTED;

    val active get() = this == UPLOADING || this == SENDING || this == DOWNLOADING
}

/** One file sent or offered, keyed by its FD Message ID. */
data class FdRecord(
    val id: String,
    val conversationId: String,
    val direction: FdDirection,
    /** The other MCData user: the target of an outgoing file, the sender of an incoming one. */
    val peer: String,
    val group: String?,
    val metadata: FileMetadata,
    val url: String? = null,
    /** The staged file (outgoing) or the completed download (incoming); never shown to peers. */
    val localPath: String? = null,
    val state: FdState,
    val transferred: Long = 0,
    val error: String? = null,
    /** Recipient MCData ID to the latest FD notification from them (outgoing). */
    val notifications: Map<String, FdNotificationType> = emptyMap(),
    val completionRequested: Boolean = false,
    val mandatory: Boolean = false,
    val applicationId: Int? = null,
    /** Whether ACCEPTED has been sent for an incoming file. */
    val accepted: Boolean = false,
    val at: Long = System.currentTimeMillis(),
) {
    val incoming get() = direction == FdDirection.INCOMING
    /** An incoming file the user can still fetch. */
    val downloadable get() = incoming && state in setOf(FdState.OFFERED, FdState.DEFERRED, FdState.FAILED, FdState.INTERRUPTED) && url != null

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("conversation_id", conversationId); put("direction", direction.name); put("peer", peer)
        group?.let { put("group", it) }
        put("name", metadata.name); put("type", metadata.mediaType); put("size", metadata.size)
        metadata.sha256?.let { put("sha256", it) }; metadata.availableUntil?.let { put("available_until", it) }
        url?.let { put("url", it) }; localPath?.let { put("local_path", it) }
        put("state", state.name); put("transferred", transferred); error?.let { put("error", it) }
        put("notifications", buildJsonObject { notifications.forEach { (k, v) -> put(k, v.name) } })
        put("completion_requested", completionRequested); put("mandatory", mandatory)
        applicationId?.let { put("application_id", it) }
        put("accepted", accepted); put("at", at)
    }

    companion object {
        fun fromJson(o: JsonObject): FdRecord {
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull
            fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
            return FdRecord(
                id = s("id")!!, conversationId = s("conversation_id")!!, direction = FdDirection.valueOf(s("direction")!!),
                peer = s("peer")!!, group = s("group"),
                metadata = FileMetadata(s("name")!!, s("type")!!, l("size")!!, s("sha256"), l("available_until")),
                url = s("url"), localPath = s("local_path"), state = FdState.valueOf(s("state")!!),
                transferred = l("transferred") ?: 0, error = s("error"),
                notifications = (o["notifications"] as? JsonObject)?.mapValues { FdNotificationType.valueOf(it.value.jsonPrimitive.content) } ?: emptyMap(),
                completionRequested = b("completion_requested"), mandatory = b("mandatory"),
                applicationId = l("application_id")?.toInt(), accepted = b("accepted"), at = l("at") ?: 0,
            )
        }
    }
}

/** Durable storage of FD records (the app keeps them across restarts). */
interface FdPersistence {
    fun load(): List<FdRecord>
    fun put(record: FdRecord)
    /** Forget a record (the user cleared the messages). */
    fun remove(id: String) {}

    object None : FdPersistence {
        override fun load() = emptyList<FdRecord>()
        override fun put(record: FdRecord) {}
    }
}
