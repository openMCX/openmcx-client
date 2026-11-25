//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.children
import org.openmcx.client.body.text
import org.openmcx.client.client.Icsi
import org.openmcx.client.client.McxClient
import org.openmcx.client.client.ProcedureResult
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest

/**
 * A regroup document (TS 24.379 annex F.7.2, `application/vnd.3gpp.mcptt-regroup+xml`): the preconfigured group, the
 * regroup's identity, the groups (a group regroup, §16.2) or the users (a user regroup, §16.3), and the action.
 */
data class Regroup(val action: String, val uri: String, val preconfigured: String?, val groups: List<String> = emptyList(),
                   val users: List<String> = emptyList()) {
    init {
        require(action == "create" || action == "remove") { "unsupported regroup action $action" }
        require(uri.isNotBlank()) { "a regroup needs its identity" }
        require(groups.isEmpty() || users.isEmpty()) { "a regroup names groups or users, not both" }
        if (action == "create") {
            require(!preconfigured.isNullOrBlank()) { "a regroup needs its preconfigured group" }
            require(groups.isNotEmpty() || users.isNotEmpty()) { "a regroup names groups or users" }
        }
    }

    /** A temporary group formed by the GMS (TS 24.481 §6.3.14), which the OpenMCX servers notify as its own preconfigured group. */
    val temporary: Boolean get() = preconfigured == uri

    /** Annex F.7.2, the elements in schema order; a removal may leave out the preconfigured group (§16.2.1.2 step 9). */
    fun encode(): String = Xml.document("mcpttregroup", NAMESPACE) {
        element("mcpttregroup-Params") {
            element("preconfig-group-id") { leaf("preconfigured-group", preconfigured) }
            element("mcptt-regroup-uri") { leaf("mcptt-regroup-uri", uri) }
            if (groups.isNotEmpty()) element("groups-for-regroup") { groups.forEach { leaf("group", it) } }
            if (users.isNotEmpty()) element("users-for-regroup") { users.forEach { leaf("user", it) } }
            leaf("regroup-action", action)
        }
    }

    companion object {
        const val NAMESPACE = "urn:3gpp:ns:preconfiguredRegroup:1.0"
        const val CONTENT_TYPE = "application/vnd.3gpp.mcptt-regroup+xml"

        fun parse(text: String): Regroup {
            val root = Xml.parse(text)
            require(root.localName == "mcpttregroup" && root.namespaceURI == NAMESPACE) { "not a regroup document" }
            val params = root.child("mcpttregroup-Params") ?: throw IllegalArgumentException("no mcpttregroup-Params")
            fun list(container: String, item: String) = params.child(container)?.children(item)?.map { it.text() }?.filter { it.isNotEmpty() } ?: emptyList()
            return Regroup(
                action = params.child("regroup-action")?.text() ?: throw IllegalArgumentException("no regroup-action"),
                uri = params.child("mcptt-regroup-uri")?.let { it.child("mcptt-regroup-uri") ?: it }?.text() ?: "",
                preconfigured = params.child("preconfig-group-id")?.child("preconfigured-group")?.text()?.ifEmpty { null },
                groups = list("groups-for-regroup", "group"),
                users = list("users-for-regroup", "user"),
            )
        }
    }
}

/**
 * TS 24.481 §6.3.14.2 with §7.3.4.3: the GMOP group regroup creation request of a temporary group: its group document
 * (`application/vnd.3gpp.GMOP+xml`) lists the constituent groups in <on-network-temporary>; [chat] a chat temporary
 * group, in the OpenMCX configuration extension (the GMS's type of the group, as the Linux client sends it).
 */
object TemporaryGroups {
    const val GMOP_TYPE = "application/vnd.3gpp.GMOP+xml"
    private const val GROUP_INFO = "urn:3gpp:ns:mcpttGroupInfo:1.0"

    fun creationRequest(groupId: String, constituents: List<String>, chat: Boolean, protect: Boolean): String {
        require(constituents.size >= 2) { "a temporary group combines two or more groups" }
        val flag = protect.toString()
        return Xml.document("document", "urn:3gpp:ns:mcpttGMOP:1.0") {
            element("request") {
                element("group-regroup-creation") {
                    element("group") {
                        attr("xmlns", "urn:oma:xml:poc:list-service"); attr("xmlns:mcpttgi", GROUP_INFO)
                        element("list-service") {
                            attr("uri", groupId)
                            leaf("mcpttgi:protect-media", flag)
                            leaf("mcpttgi:protect-floor-control-signalling", flag)
                            element("mcpttgi:on-network-temporary") {
                                element("mcpttgi:constituent-MCPTT-group-IDs") { constituents.forEach { leaf("mcpttgi:constituent-MCPTT-group-ID", it) } }
                            }
                            if (chat) element("configuration") { attr("xmlns", "urn:openmcx:ns:groupConfiguration:1.0"); text("{\"type\":\"chat\"}") }
                        }
                    }
                }
            }
        }
    }
}

/** What the client knows of regroups (TS 24.379 §16) and temporary groups (TS 24.481 §6.3.14). */
data class RegroupsState(
    /** The regroups this user's groups are in (or this user is in), by identity, as last notified. */
    val regroups: Map<String, Regroup> = emptyMap(),
    val notifications: Int = 0,
    /** The last regroup request (§16.2.1.1, §16.2.1.2, §16.3.1.1) and the last temporary group request (TS 24.481 §6.3.14). */
    val lastRequest: ProcedureResult? = null,
    val lastTemporary: ProcedureResult? = null,
)

/**
 * The MCPTT client's regroup procedures (TS 24.379 §16.2.1, §16.3.1, as the Linux client's mcptt/regroup.cpp) and the
 * group management client's temporary groups (TS 24.481 §6.3.14.2, §6.3.15.2):
 *  - the requests to create or remove a group or user regroup: a SIP MESSAGE to the participating function's PSI with
 *    the client ID and the regroup document;
 *  - the notices of the server (§16.2.1.3, §16.2.1.4, §16.3.1.3): the regroup is stored or forgotten; the user is
 *    considered affiliated with it (§16.2.2.4 step 3 h)) and calls it at once with its preconfigured group's
 *    configuration (§16.1);
 *  - the formation and tear down of a temporary group by the GMS, which the OpenMCX servers then notify as a regroup.
 */
class Regroups(private val client: McxClient) {
    private val config = client.config.mcptt!!
    private val _state = MutableStateFlow(RegroupsState())
    val state: StateFlow<RegroupsState> = _state

    private fun event(text: String) = client.event("mcptt regroup: $text")

    /** §16.2.1.1 (create, groups), §16.3.1.1 (create, users) and §16.2.1.2 (remove: only the identity and the action). */
    suspend fun request(action: String, uri: String, preconfigured: String? = null, groups: List<String> = emptyList(),
                        users: List<String> = emptyList()): ProcedureResult {
        if (!client.status.value.mcptt.authorised) throw IllegalStateException("MCPTT not authorised")
        val regroup = Regroup(action, uri, preconfigured ?: if (action == "remove") _state.value.regroups[uri]?.preconfigured else null,
            groups, users)
        val ua = client.ua
        val request = ua.newRequest("MESSAGE", config.psi, NameAddr(config.psi))
        request.headers.add("Accept-Contact", "*;+g.3gpp.mcptt;require;explicit")
            .add("Accept-Contact", "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)}\";require;explicit")
            .add("P-Preferred-Service", Icsi.MCPTT)
        val (contentType, body) = Multipart.encode(listOf(
            Part(McpttInfo.CONTENT_TYPE, McpttInfo(clientId = client.config.clientId).encode().toByteArray()),
            Part(Regroup.CONTENT_TYPE, regroup.encode().toByteArray())))
        request.headers.add("Content-Type", contentType)
        val response = runCatching { ua.send(SipRequest(request.method, request.uri, request.headers, body)) }.getOrNull()
        val result = ProcedureResult("regroup $action $uri", response?.status ?: 0, response?.headers?.get("Warning"))
        _state.update { it.copy(lastRequest = result) }
        event("${result.action}: ${result.status}${result.warning?.let { " ($it)" } ?: ""}")
        return result
    }

    /** §16.2.1.3, §16.2.1.4: a regroup notice from this user's MCPTT server; false for any other MESSAGE. */
    suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        val part = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == Regroup.CONTENT_TYPE } ?: return false
        // The notice comes from the participating function (§16.2.2.4 step 3 f)).
        val from = request.headers["From"]?.let { runCatching { NameAddr.parse(it).uri }.getOrNull() }
        if (from != null && from != config.psi) { tx.respond(403); event("a regroup notice from $from refused"); return true }
        val regroup = try { Regroup.parse(part.text) } catch (e: Exception) { tx.respond(400); event("a regroup notice not read: ${e.message}"); return true }
        tx.respond(200)
        _state.update {
            it.copy(regroups = if (regroup.action == "create") it.regroups + (regroup.uri to regroup) else it.regroups - regroup.uri,
                notifications = it.notifications + 1)
        }
        event("${regroup.action} ${regroup.uri}" + (regroup.preconfigured?.let { " of $it" } ?: "") +
            (if (regroup.groups.isNotEmpty()) " groups ${regroup.groups}" else "") + (if (regroup.users.isNotEmpty()) " users ${regroup.users}" else ""))
        // §16.1: the regroup takes its configuration (its type, chat or not) from its preconfigured group; a temporary
        // group has a document of its own. Read it, unless known.
        if (regroup.action == "create") regroup.preconfigured?.takeIf { it !in client.groupDocuments }?.let { group ->
            client.scope.launch { runCatching { client.refreshGroupDocument(group) } }
        }
        return true
    }

    /** The regroup [group] is, if this client was told of it. */
    fun regroupOf(group: String): Regroup? = _state.value.regroups[group]

    /** The document whose configuration a call on [group] uses: its preconfigured group's for a regroup (§16.1). */
    fun configurationOf(group: String) = regroupOf(group)?.preconfigured?.let { client.groupDocuments[it] } ?: client.groupDocuments[group]

    /**
     * §10.1.1.2.1.1 step 14 d): the constituent group a call on [group] is placed from, null when [group] is no regroup
     * (or a user regroup, which has none). The constituents of a temporary group are in its document too.
     */
    fun callingConstituent(group: String): String? {
        val regroup = regroupOf(group) ?: return null
        val constituents = regroup.groups.ifEmpty { client.groupDocuments[group]?.temporaryConstituents ?: emptyList() }
        val affiliations = client.status.value.mcptt.affiliations
        val affiliated = affiliations.filterValues { Affiliation.counts(it) }.keys
        return GroupCallRules.callingConstituent(constituents, client.wantedGroups(McService.MCPTT).firstOrNull { it in constituents }, affiliated)
    }

    /**
     * TS 24.481 §6.3.14.2: form a temporary group of [constituents] (two or more groups) as [document] in the user's
     * tree of the GMS (POST, the GMOP group regroup creation request); 200 with its document's ETag when formed, 403
     * or 409 (an identity not free, with an alternative) when refused.
     */
    suspend fun temporaryCreate(document: String, groupId: String, constituents: List<String>, chat: Boolean = false,
                                protect: Boolean = false): ProcedureResult {
        val body = TemporaryGroups.creationRequest(groupId, constituents, chat, protect)
        val response = runCatching { client.gmsUserRequest("POST", document, TemporaryGroups.GMOP_TYPE, body.toByteArray()) }
            .onFailure { event("temporary group $groupId: ${it.message}") }.getOrNull()
        return ProcedureResult("temporary-create $groupId", response?.status ?: 0, response?.text?.take(300)?.ifEmpty { null })
            .also { r -> _state.update { it.copy(lastTemporary = r) }; event("${r.action}: ${r.status}") }
    }

    /** TS 24.481 §6.3.15.2: tear down the temporary group of [document] (DELETE of its document). */
    suspend fun temporaryDelete(document: String): ProcedureResult {
        val response = runCatching { client.gmsUserRequest("DELETE", document) }
            .onFailure { event("temporary group $document: ${it.message}") }.getOrNull()
        return ProcedureResult("temporary-delete $document", response?.status ?: 0, response?.text?.take(300)?.ifEmpty { null })
            .also { r -> _state.update { it.copy(lastTemporary = r) }; event("${r.action}: ${r.status}") }
    }

    /** The registration ended: what was notified is no longer known to be current. */
    fun drop() = _state.update { it.copy(regroups = emptyMap()) }
}
