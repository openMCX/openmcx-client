package org.openmcx.client.documents

import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text
import org.openmcx.client.http.HttpClient
import org.w3c.dom.Element
import java.net.URLEncoder
import kotlinx.serialization.json.*
import org.openmcx.client.mcdata.GroupFdPolicy

class DocumentError(message: String) : Exception(message)

/**
 * The MCPTT user profile document of TS 24.484 V19.4.0 clause 8.3 (namespace of 8.3.2.4), as the
 * OpenMCX CMS serves it. What the schema cannot express (client ID, …) is in the CMS's extension
 * namespace ([EXTENSION_NS]), which 8.3.2.1 item 12 permits.
 */
data class UserProfile(
    val mcpttId: String?,
    val clientId: String?,
    val groups: List<String>,
    /** Granted rights, by their TS 24.484 element names (`allow-private-call`, …). */
    val actions: Set<String>,
    val privateCallTargets: List<String>,
) {
    fun allows(action: String) = action in actions

    companion object {
        const val NS = "urn:3gpp:mcptt:user-profile:1.0"
        const val MCDATA_NS = "urn:3gpp:ns:mcdata:user-profile:1.0"
        const val EXTENSION_NS = "urn:openmcx:ns:userProfile:1.0"
        const val COMMON_POLICY_NS = "urn:ietf:params:xml:ns:common-policy"

        private fun Element.kids(name: String, ns: String = NS) = children(name).filter { it.namespaceURI == ns }
        private fun Element.kid(name: String, ns: String = NS) = kids(name, ns).firstOrNull()
        private fun Element.uriEntry() = kid("uri-entry")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        fun parse(text: String): UserProfile {
            val root = Xml.parse(text)
            if (root.localName != "mcptt-user-profile" || root.namespaceURI != NS) throw DocumentError("not a TS 24.484 user profile")
            val common = root.kids("Common")
            val extension = root.kid("profile-extension", EXTENSION_NS)
            return UserProfile(
                mcpttId = common.firstNotNullOfOrNull { it.kid("MCPTTUserID")?.uriEntry() }
                    ?: root.getAttribute("XUI-URI").takeIf { it.isNotEmpty() },
                clientId = extension?.kid("client-id", EXTENSION_NS)?.text()?.trim(),
                // <OnNetwork>/<MCPTTGroupInfo>/<entry>/<uri-entry>
                groups = root.kids("OnNetwork").flatMap { it.kids("MCPTTGroupInfo") }.flatMap { it.kids("entry") }.mapNotNull { it.uriEntry() },
                actions = rights(root),
                // <Common>/<PrivateCall>/<PrivateCallList>/<PrivateCallURI>/<uri-entry>
                privateCallTargets = common.flatMap { it.kids("PrivateCall") }.flatMap { it.kids("PrivateCallList") }
                    .flatMap { it.kids("PrivateCallURI") }.mapNotNull { it.uriEntry() },
            )
        }

        /**
         * 8.3.2.1 item 11: each right is a boolean element of the `<cp:ruleset>` actions, directly or in
         * their `<anyExt>`; the MCData rights are elements of the MCData user profile (10.3.2.3). A right
         * is granted when its element is true.
         */
        private fun rights(root: Element): Set<String> {
            val out = mutableSetOf<String>()
            fun collect(list: Element) {
                for (e in list.children()) {
                    if (e.localName == "anyExt" && e.namespaceURI == NS) { collect(e); continue }
                    if (e.namespaceURI !in setOf(NS, MCDATA_NS, EXTENSION_NS)) continue
                    if (e.text().trim() in setOf("true", "1")) out += e.localName
                }
            }
            root.kids("ruleset", COMMON_POLICY_NS).flatMap { it.kids("rule", COMMON_POLICY_NS) }
                .flatMap { it.kids("actions", COMMON_POLICY_NS) }.forEach(::collect)
            return out
        }
    }
}

/** A group document (TS 24.481 §7.2), the parts this client uses. */
data class GroupDocument(val uri: String, val members: List<String>, val services: Set<String>, val protectMedia: Boolean,
                         val fd: GroupFdPolicy? = null) {
    val mcptt get() = services.any { it.endsWith("icsi.mcptt") }
    val mcdata get() = services.any { it.endsWith("icsi.mcdata") }

    companion object {
        fun parse(text: String): GroupDocument {
            val root = Xml.parse(text)
            val service = root.descendants("list-service").firstOrNull() ?: throw DocumentError("no list-service")
            val extension = service.descendants("configuration")
                .firstOrNull { it.namespaceURI == "urn:openmcx:ns:groupConfiguration:1.0" }
                ?.let { Json.parseToJsonElement(it.text()).jsonObject["mcdata"] as? JsonObject }
            val fd = extension?.let {
                GroupFdPolicy(it["allow_fd"]?.jsonPrimitive?.booleanOrNull ?: false,
                    it["fd_supported"]?.jsonPrimitive?.booleanOrNull ?: false,
                    it["max_fd_bytes"]?.let { n -> n.jsonPrimitive.longOrNull ?: throw DocumentError("invalid group FD limit") })
            }
            return GroupDocument(
                uri = service.getAttribute("uri"),
                members = service.descendants("entry").map { it.getAttribute("uri") },
                services = service.descendants("service").map { it.getAttribute("enabler") }.toSet(),
                protectMedia = service.descendants("protect-media").firstOrNull()?.text() == "true",
                fd = fd,
            )
        }
    }
}

/** XCAP retrieval (RFC 4825 §7.3) of the profile and group documents, with the access token (TS 24.481 §6.3.3). */
class DocumentClient(private val http: HttpClient, private val cms: String, private val gms: String) {
    private val etags = mutableMapOf<String, Pair<String, String>>() // url -> (etag, body)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun fetch(url: String, token: String): String {
        val headers = mutableMapOf("Authorization" to "Bearer $token")
        etags[url]?.let { headers["If-None-Match"] = it.first }
        val r = http.get(url, headers)
        return when (r.status) {
            200 -> r.text.also { body -> r.header("ETag")?.let { etags[url] = it to body } }
            304 -> etags[url]!!.second
            else -> throw DocumentError("GET $url: HTTP ${r.status}")
        }
    }

    /** TS 24.484 §8.3.1A: the user's MCPTT user profile document, mcptt-user-profile-<index>.xml. */
    suspend fun userProfile(mcpttId: String, index: Int, token: String): UserProfile =
        UserProfile.parse(fetch("$cms/xcap-root/org.3gpp.mcptt.user-profile/users/${enc(mcpttId)}/mcptt-user-profile-$index.xml", token))

    suspend fun group(groupId: String, token: String): GroupDocument =
        GroupDocument.parse(fetch("$gms/xcap-root/org.openmobilealliance.groups/global/byGroupID/${enc(groupId)}", token))
}
