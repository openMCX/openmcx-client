package org.openmcx.client.mcvideo

import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.children
import org.openmcx.client.body.text

/** One participant of a conference-info document: its MC ID and endpoint status (`connected`, `disconnected`, …). */
data class ConferenceUser(val id: String, val status: String)

/**
 * A conference-info document (RFC 4575) as the MC servers send it for a group session
 * (TS 24.281 §9.2.3, TS 24.379 §10.1.3): the group as `entity`, a full or partial state, and
 * one `<user>` per participant with its endpoint status.
 */
data class ConferenceInfo(val group: String, val version: Long, val full: Boolean, val deleted: Boolean, val users: List<ConferenceUser>) {
    /** Apply this document to [participants] (MC ID to status): a full one replaces them, a partial one updates them. */
    fun applyTo(participants: Map<String, String>): Map<String, String> {
        if (deleted) return emptyMap()
        val next = if (full) linkedMapOf() else LinkedHashMap(participants)
        for (user in users) if (user.status == "disconnected") next.remove(user.id) else next[user.id] = user.status
        return next
    }

    companion object {
        const val CONTENT_TYPE = "application/conference-info+xml"
        const val NAMESPACE = "urn:ietf:params:xml:ns:conference-info"

        fun parse(text: String): ConferenceInfo {
            val root = Xml.parse(text)
            require(root.localName == "conference-info" && root.namespaceURI == NAMESPACE) { "not a conference-info document" }
            val state = root.getAttribute("state").ifEmpty { "full" }
            require(state in setOf("full", "partial", "deleted")) { "invalid conference state $state" }
            val users = root.child("users")?.children("user").orEmpty().map { user ->
                val status = user.child("endpoint")?.child("status")?.text() ?: user.child("status")?.text() ?: "connected"
                ConferenceUser(user.getAttribute("entity"), status)
            }.filter { it.id.isNotEmpty() }
            return ConferenceInfo(root.getAttribute("entity"), root.getAttribute("version").toLongOrNull() ?: 0,
                state == "full", state == "deleted", users)
        }
    }
}
