//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.children
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TS 24.282 §14.2.1 with TS 24.481 §7.2.4.2: the <mcdata-allow-enhanced-status> flag of a group document and the
 * operational values of its <mcdata-enhanced-status-operational-values>, each a <status> with an <id> and a
 * <shortText>.
 */
data class EnhancedStatusValues(val allowed: Boolean, val values: List<Pair<String, String>>) {
    fun value(id: String) = values.firstOrNull { it.first == id }?.second

    companion object {
        fun parse(groupDocument: String): EnhancedStatusValues {
            val root = Xml.parse(groupDocument)
            val service = root.descendants("list-service").firstOrNull() ?: root
            val flag = service.descendants("mcdata-allow-enhanced-status").firstOrNull()?.text()
            val list = service.descendants("mcdata-enhanced-status-operational-values").firstOrNull()
            val values = list?.children("status")?.mapNotNull { s ->
                val id = s.child("id")?.text()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                id to (s.child("shortText")?.child("langText")?.text() ?: s.child("shortText")?.text() ?: "")
            }?.distinctBy { it.first } ?: emptyList()
            return EnhancedStatusValues(flag == "true" || flag == "1", values)
        }
    }
}

/** An enhanced status rendered to the user (TS 24.282 §14.2.1.2 step 3). */
data class EnhancedStatus(val messageId: UUID, val from: String, val group: String?, val id: String, val value: String,
                          val at: Long = System.currentTimeMillis())

/**
 * Enhanced status (TS 24.282 §14.2): [send] the id of a group's operational value as a group standalone SDS whose
 * payload is of type ENHANCED STATUS (§14.2.1.1, §15.2.13), and the terminating side ([render], the filter of
 * [McdataService.accept]): a received value is rendered when it matches an operational value of the group, the
 * message discarded otherwise (§14.2.1.2). [groupDocument] reads a group document (the GMS, TS 24.481 §6.3.3).
 */
class EnhancedStatusService(private val sds: McdataService, private val groupDocument: suspend (String) -> String) {
    private val cache = ConcurrentHashMap<String, EnhancedStatusValues>()
    private val _received = MutableStateFlow<List<EnhancedStatus>>(emptyList())
    val received: StateFlow<List<EnhancedStatus>> = _received
    /** The messages discarded because their value matched no operational value (§14.2.1.2 step 3). */
    @Volatile var discarded = 0
        private set
    @Volatile var event: (String) -> Unit = {}

    init { sds.accept = ::render }

    /** The operational values of [group], read again with [refresh] (else from the last read). */
    suspend fun values(group: String, refresh: Boolean = false): EnhancedStatusValues {
        if (!refresh) cache[group]?.let { return it }
        return EnhancedStatusValues.parse(groupDocument(group)).also { cache[group] = it }
    }

    /**
     * §14.2.1.1: with <mcdata-allow-enhanced-status> "true", the [id] of a selected operational value goes to [group]
     * as a group standalone SDS (§9.2.2.2.1). [force] skips both checks (a test aid, as the reference client's).
     */
    suspend fun send(group: String, id: String, force: Boolean = false, disposition: DispositionRequest? = null): SentSds {
        val values = values(group, refresh = true)
        if (!force && !values.allowed) throw SdsError("enhanced status is not allowed on $group")
        if (!force && values.value(id) == null) throw SdsError("no operational value with id $id on $group")
        event("enhanced status $id (${values.value(id)}) to $group")
        return sds.send(group, id, group = true, disposition = disposition, payloadType = PayloadType.ENHANCED_STATUS)
    }

    /** §14.2.1.2 steps 2 and 3: true to deliver [message]; an enhanced status is rendered, or the message discarded. */
    suspend fun render(message: ReceivedSds): Boolean {
        val parts = message.payloads.filter { it.type == PayloadType.ENHANCED_STATUS }
        if (parts.isEmpty()) return true
        for (part in parts) {
            val id = String(part.data, Charsets.UTF_8).trim()
            val value = message.group?.let { g ->
                runCatching { values(g).value(id) ?: values(g, refresh = true).value(id) }
                    .onFailure { event("enhanced status values of $g unavailable: ${it.message}") }.getOrNull()
            }
            if (value == null) {
                discarded++
                event("enhanced status $id from ${message.sender} on ${message.group} discarded: no such operational value")
                return false
            }
            _received.value = (_received.value + EnhancedStatus(message.messageId, message.sender, message.group, id, value)).takeLast(50)
            event("enhanced status from ${message.sender} on ${message.group}: $value")
        }
        return true
    }
}
