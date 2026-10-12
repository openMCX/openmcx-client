package org.openmcx.client.mcptt

import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.McService
import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text
import java.util.UUID

/**
 * The functional alias documents (TS 24.379 §9A.3.1.2, TS 24.282 §22.3.1, TS 24.281 §20.3.1): the PIDF extension of
 * the status change request and of the status notification, in the namespace of the service.
 */
object FunctionalAliasPidf {
    const val NAMESPACE = "urn:3gpp:ns:mcpttPresInfoFA:1.0"
    const val CONTENT_TYPE = Affiliation.CONTENT_TYPE

    /** The functional alias namespace of [service]. */
    fun namespace(service: McService) = when (service) {
        McService.MCPTT -> NAMESPACE
        McService.MCDATA -> "urn:3gpp:ns:mcdataPresInfoFA:1.0"
        McService.MCVIDEO -> "urn:3gpp:ns:mcvideoPresInfoFA:1.0"
    }

    /**
     * §9A.2.1.2 step 6: the status change request of [entity] (the MCPTT ID) from [clientId]: a new <p-id-fa>
     * ([publishId]), one <functionalAlias functionalAliasID> per alias, and <take-over> when [takeOver].
     */
    fun request(entity: String, clientId: String, aliases: List<String>, takeOver: Boolean,
                publishId: String = UUID.randomUUID().toString(), service: McService = McService.MCPTT): String =
        Xml.document("presence", Affiliation.PIDF) {
        attr("xmlns:fa", namespace(service))
        attr("entity", entity)
        leaf("fa:p-id-fa", publishId)
        element("tuple") {
            attr("id", clientId)
            element("status") {
                aliases.forEach { alias -> element("fa:functionalAlias") { attr("functionalAliasID", alias) } }
                if (takeOver) leaf("fa:take-over", "true")
            }
        }
    }

    /** One alias and its status: `activating`, `activated`, `deactivating` or `take-over-possible`. */
    data class Entry(val alias: String, val status: String)

    /** A status notification for one user (§9A.2.1.3): its entity, the <p-id-fa> it answers and the aliases. */
    data class Status(val entity: String, val publishId: String?, val entries: List<Entry>)

    /** Reads a per-user status notification; null when the document is not a functional alias one of [service]. */
    fun parseStatus(body: String, service: McService = McService.MCPTT): Status? {
        val ns = namespace(service)
        // A notification with no alias left still declares the namespace (the server writes xmlns:fa).
        if (!body.contains(ns)) return null
        val root = Xml.parse(body)
        if (root.localName != "presence") return null
        val aliases = root.descendants("functionalAlias").filter { it.namespaceURI == ns }
        val publishId = root.children("p-id-fa").firstOrNull { it.namespaceURI == ns }?.text()
        return Status(root.getAttribute("entity"), publishId,
            aliases.mapNotNull { e -> e.getAttribute("functionalAliasID").takeIf { it.isNotEmpty() }?.let { Entry(it, e.getAttribute("status")) } })
    }
}

/**
 * What this user knows of its functional aliases (§9A.2.1): the ones it may use ([available], from its provisioning),
 * those the server says are active for it or may be taken over, the one presented on outgoing calls ([inUse]) and the
 * outcome of the last status change.
 */
data class FunctionalAliasState(
    val available: List<String> = emptyList(),
    val active: Set<String> = emptySet(),
    val takeOverPossible: Set<String> = emptySet(),
    val inUse: String? = null,
    val subscribed: Boolean = false,
    val lastAction: String? = null,
    val lastStatus: Int? = null,
    val lastWarning: String? = null,
    /** The outcome per alias of the last status change: activated, take-over-possible, deactivated or refused. */
    val lastOutcomes: Map<String, String> = emptyMap(),
)

/**
 * The client side of the functional alias procedures as pure decisions (TS 24.379 §9A.2.1, TS 24.282 §22.2.1,
 * TS 24.281 §20.2.1, the same in the three services), so that they can be tested without a server.
 */
object FunctionalAliasRules {
    /** A status change sent: its action, its aliases and its <p-id-fa>. */
    data class Change(val action: String, val aliases: List<String>, val publishId: String)

    /**
     * §9A.2.1.2 step 6 d and NOTE 4: of a take-over the user asked for, the aliases already [offered] as
     * `take-over-possible` go with <take-over>; the others are asked for as activations first.
     */
    fun splitTakeOver(aliases: List<String>, offered: Set<String>): Pair<List<String>, List<String>> = aliases.partition { it in offered }

    /** What a status NOTIFY changes: the new state, and the take-overs to send now that they are offered. */
    data class Notified(val state: FunctionalAliasState, val takeOver: List<String>)

    /**
     * §9A.2.1.3 (TS 24.282 §22.2.1.3, TS 24.281 §20.2.1.3): a per-user status NOTIFY replaces the aliases known active
     * (`activated`, or `deactivating` while still active) and those offered for take-over. When its <p-id-fa> is that
     * of [last], each alias of that change gets its outcome: activated, take-over-possible, deactivated, or refused
     * (omitted from the notification); an alias the user wanted to take over and is now offered is taken over.
     * [wanted] loses the aliases of the answered change.
     */
    fun notified(state: FunctionalAliasState, status: FunctionalAliasPidf.Status, last: Change?, wanted: MutableSet<String>): Notified {
        val active = status.entries.filter { it.status == "activated" || it.status == "deactivating" }.map { it.alias }.toSet()
        val possible = status.entries.filter { it.status == "take-over-possible" }.map { it.alias }.toSet()
        val answered = last != null && status.publishId == last.publishId
        val outcomes = if (!answered) state.lastOutcomes else last!!.aliases.associateWith { alias ->
            when {
                alias in active -> "activated"
                alias in possible -> "take-over-possible"
                last.action == "deactivate" -> "deactivated"
                else -> "refused"
            }
        }
        val takeOver = if (answered) last!!.aliases.filter { wanted.remove(it) && it in possible } else emptyList()
        return Notified(state.copy(active = active, takeOverPossible = possible, available = (state.available + active + possible).distinct(),
            inUse = state.inUse?.takeIf { it in active }, lastOutcomes = outcomes), takeOver)
    }
}
