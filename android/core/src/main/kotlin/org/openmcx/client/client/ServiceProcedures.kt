//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.AffiliationCommand
import org.openmcx.client.body.GroupDynamicData
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.PocSettings
import org.openmcx.client.body.SimpleFilter
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcvideo.McvideoInfo
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The service settings of one client of this user, as a poc-settings NOTIFY gave them (TS 24.379 §7.2.4). */
data class ClientSettings(val answerMode: String?, val profileIndex: Int?, val multiplexSupport: Boolean?, val own: Boolean)

/** The outcome of a request of these procedures: what was asked, the final status and its Warning (0: no answer). */
data class ProcedureResult(val action: String, val status: Int, val warning: String? = null)

/** Another user's affiliation as its subscription notified it (§9.2.1.3): client ID to group to status. */
data class TargetAffiliation(val subscribed: Boolean, val status: Int?, val clients: Map<String, Map<String, String>> = emptyMap(),
                             val notifications: Int = 0)

/** A received negotiated-mode affiliation status change request (§9.2.1.5), until the user accepts or declines it. */
data class AffiliationRequest(val from: String?, val affiliate: List<String>, val deaffiliate: List<String>, val receivedAt: Long)

/** A group's dynamic data (§9.2.1.6, §9.3.1.2) and its subscription. */
data class GroupDynamic(val subscribed: Boolean, val status: Int?, val data: GroupDynamicData.Data? = null, val notifications: Int = 0)

data class ProceduresState(
    /** §7.2.4: by client ID, the settings notified; [settingsSubscribed] while the subscription lasts. */
    val serviceSettings: Map<String, ClientSettings> = emptyMap(),
    val settingsSubscribed: Boolean = false,
    val settingsSubscription: ProcedureResult? = null,
    /** §7.2.3: the last settings-only PUBLISH. */
    val settingsPublication: ProcedureResult? = null,
    /** §9.2.1.3: by MC service ID. */
    val targetAffiliations: Map<String, TargetAffiliation> = emptyMap(),
    /** §9.2.1.2 (mandatory mode) and §9.2.1.4 (negotiated mode): the last remote affiliation request sent. */
    val remoteAffiliation: ProcedureResult? = null,
    /** §9.2.1.5: the request awaiting the user's answer, and the last answer ("accepted", "declined"). */
    val affiliationRequest: AffiliationRequest? = null,
    val lastAffiliationAnswer: String? = null,
    /** §9.2.1.6: by group ID. */
    val groupDynamicData: Map<String, GroupDynamic> = emptyMap(),
)

/**
 * The service settings and the affiliation procedures towards other users of one MC service (as the Linux client's
 * settings_affiliation.cpp), on the client's registration:
 *  - the settings-only PUBLISH (TS 24.379 §7.2.3) and the determination of the service settings (§7.2.4);
 *  - the affiliation status determination of another user (§9.2.1.3), the affiliation status change for another user
 *    in mandatory mode (§9.2.1.2) and in negotiated mode, sent (§9.2.1.4) and received (§9.2.1.5);
 *  - the subscription to group dynamic data (§9.2.1.6).
 * MCVideo (TS 24.281 §7.2.3, §7.2.4, §8.2.1.2 to §8.2.1.6) has the same procedures with its own info body and PSI.
 */
class ServiceProcedures(private val client: McxClient, val service: McService) {
    private val ua get() = client.ua
    private val _state = MutableStateFlow(ProceduresState())
    val state: StateFlow<ProceduresState> = _state

    private enum class Kind { SETTINGS, TARGET, GDD }

    /** One subscription of these procedures: its SUBSCRIBE (known by Call-ID before the 2xx), its dialog once accepted. */
    private class Subscription(val kind: Kind, val subject: String, val request: SipRequest, val fetch: Boolean) {
        @Volatile var dialog: Dialog? = null
    }
    private val subscriptions = ConcurrentHashMap<String, Subscription>() // by Call-ID
    /** Subscriptions ended here whose final NOTIFY is still answered 200 (RFC 6665 §4.1.2.3). */
    private val ending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun event(text: String) = client.event("${service.name.lowercase()} procedures: $text")
    private val ownId get() = client.ownId(service)
    private val label get() = service.name

    private fun requireAuthorised(what: String) {
        val state = when (service) { McService.MCPTT -> client.status.value.mcptt; McService.MCDATA -> client.status.value.mcdata
            McService.MCVIDEO -> client.status.value.mcvideo }
        if (!state.authorised) throw IllegalStateException("$label $what requires authorisation")
    }

    private fun result(action: String, response: SipResponse?) = ProcedureResult(action, response?.status ?: 0, response?.headers?.get("Warning"))

    // §7.2.3: the settings-only PUBLISH.

    /**
     * TS 24.379 §7.2.3 (`settings-publish [remove]`): to set, update or refresh the service settings (or remove them):
     * 1) the common PUBLISH of §7.2.1A: the PSI, P-Preferred-Service, Event poc-settings, Expires 4294967295 (0 to
     * remove them, which logs the client off the service, §7.2.1A NOTE 3); 3) the info body with this user's ID and the
     * client ID (its protection, steps 2) and 5), that of every request of the service); 4) the poc-settings with the
     * current answer mode, the selected user profile's index and <multiplex-support>. RFC 3903: it modifies the
     * service authorisation's publication (SIP-If-Match), a 412 publishes anew (section 5). A 3xx to 5xx: the settings
     * are taken as not set, and the user is told with the Warning.
     */
    suspend fun settingsPublish(remove: Boolean = false): ProcedureResult {
        requireAuthorised("service settings")
        val action = if (remove) "remove" else "publish"
        var response = sendSettings(remove, client.settingsEtag(service))
        if (response?.status == 412 && !remove && client.settingsEtag(service) != null) {
            client.settingsEtag(service, null)
            response = sendSettings(false, null)
        }
        val outcome = result(action, response)
        _state.update { it.copy(settingsPublication = outcome) }
        if (response == null || !response.isSuccess) {
            event("settings $action refused: ${outcome.status}${outcome.warning?.let { " ($it)" } ?: ""}")
            return outcome
        }
        if (remove) client.settingsRemoved(service)
        else { response.headers["SIP-ETag"]?.let { client.settingsEtag(service, it) }; event("settings published") }
        return outcome
    }

    private suspend fun sendSettings(remove: Boolean, etag: String?): SipResponse? {
        val parts = listOf(client.infoPart(service, requestUri = ownId),
            Part(PocSettings.CONTENT_TYPE, client.pocSettings(service).toByteArray()))
        val request = client.serviceRequest(service, "PUBLISH", "poc-settings", parts)
            .also { it.headers.add("Expires", if (remove) "0" else FOREVER) }
        etag?.let { request.headers.add("SIP-If-Match", it) }
        return runCatching { ua.send(request) }.getOrElse { event("settings PUBLISH: ${it.message}"); null }
    }

    // §7.2.4: the subscription to the service settings.

    /**
     * TS 24.379 §7.2.4 (`settings-subscribe [fetch]`): 1) to the PSI, 2) the info body naming this user, 3)
     * P-Preferred-Service, 4) Event poc-settings, 5) Accept application/poc-settings+xml, 6) Expires 4294967295, or 7)
     * 0 to fetch the current state only.
     */
    suspend fun settingsSubscribe(fetch: Boolean = false): ProcedureResult {
        requireAuthorised("service settings")
        if (!fetch && subscription(Kind.SETTINGS, SETTINGS) != null) throw IllegalStateException("already subscribed to the $label settings")
        val response = subscribe(Kind.SETTINGS, SETTINGS, "poc-settings", PocSettings.CONTENT_TYPE,
            listOf(client.infoPart(service, requestUri = ownId)), fetch)
        val outcome = result(if (fetch) "fetch" else "subscribe", response)
        _state.update { it.copy(settingsSubscription = outcome, settingsSubscribed = !fetch && response?.isSuccess == true) }
        return outcome
    }

    /** §7.2.4, in the dialog: 1) Event poc-settings, 2) the Accept, 3) Expires 4294967295 (re-subscribe) or 4) 0. */
    suspend fun settingsResubscribe(end: Boolean = false): ProcedureResult {
        val subscription = subscription(Kind.SETTINGS, SETTINGS) ?: throw IllegalStateException("no $label settings subscription")
        val response = inDialog(subscription, "poc-settings", PocSettings.CONTENT_TYPE, end)
        val outcome = result(if (end) "unsubscribe" else "resubscribe", response)
        _state.update { it.copy(settingsSubscription = outcome, settingsSubscribed = !end) }
        return outcome
    }

    /** §7.2.4: a poc-settings document cached per client: its answer mode and its active user profile. */
    private fun settingsNotify(body: String?) {
        body ?: return
        val entities = runCatching { PocSettings.parse(body) }.getOrElse { event("settings NOTIFY unread: ${it.message}"); return }
        _state.update { s ->
            s.copy(serviceSettings = s.serviceSettings + entities.associate { e ->
                e.id to ClientSettings(e.answerMode, e.profileIndex, e.multiplexSupport, e.id == client.config.clientId)
            })
        }
        event("settings notified for ${entities.map { it.id }}")
    }

    // §9.2.1.3: another user's affiliation.

    /**
     * TS 24.379 §9.2.1.3 (`affiliation-subscribe <ID> [fetch]`): 1) to the PSI, 2) the info body naming the targeted
     * user, 3) P-Preferred-Service, 4) Expires 4294967295 or 5) 0 (fetch), 6) Accept application/pidf+xml; 7) for this
     * user's own groups at this client, the per-client simple-filter (§9.3.2).
     */
    suspend fun affiliationSubscribe(target: String, fetch: Boolean = false): ProcedureResult {
        requireAuthorised("affiliation")
        if (!fetch && subscription(Kind.TARGET, target) != null) throw IllegalStateException("already subscribed to the affiliation of $target")
        val parts = listOfNotNull(client.infoPart(service, requestUri = target),
            if (target == ownId) Part(SimpleFilter.CONTENT_TYPE, SimpleFilter.forClient(service, client.config.clientId).toByteArray()) else null)
        val response = subscribe(Kind.TARGET, target, "presence", Affiliation.CONTENT_TYPE, parts, fetch)
        val outcome = result(if (fetch) "fetch" else "subscribe", response)
        _state.update { s ->
            val known = s.targetAffiliations[target] ?: TargetAffiliation(false, null)
            s.copy(targetAffiliations = s.targetAffiliations + (target to known.copy(subscribed = !fetch && response?.isSuccess == true, status = outcome.status)))
        }
        return outcome
    }

    /** §9.2.1.3, in the dialog (`affiliation-unsubscribe <ID>`): 2) Expires 0 and 3) the Accept. */
    suspend fun affiliationUnsubscribe(target: String): ProcedureResult {
        val subscription = subscription(Kind.TARGET, target) ?: throw IllegalStateException("no affiliation subscription to $target")
        val response = inDialog(subscription, "presence", Affiliation.CONTENT_TYPE, end = true)
        _state.update { s ->
            val known = s.targetAffiliations[target] ?: TargetAffiliation(false, null)
            s.copy(targetAffiliations = s.targetAffiliations + (target to known.copy(subscribed = false)))
        }
        return result("unsubscribe", response)
    }

    private fun targetNotify(target: String, body: String?) {
        body ?: return
        val clients = runCatching { Affiliation.parseAll(body) }.getOrElse { event("affiliation of $target unread: ${it.message}"); return }
        _state.update { s ->
            val known = s.targetAffiliations[target] ?: TargetAffiliation(true, null)
            s.copy(targetAffiliations = s.targetAffiliations + (target to known.copy(clients = clients, notifications = known.notifications + 1)))
        }
        event("affiliation of $target: $clients")
    }

    // §9.2.1.2 (mandatory mode) and §9.2.1.4 (negotiated mode): another user's affiliation changed.

    /**
     * `affiliate-remote <ID> <group> affiliate|deaffiliate mandatory|negotiated`.
     *
     * Negotiated mode, TS 24.379 §9.2.1.4: a MESSAGE (RFC 3428) to the PSI (step 1) with the info body naming the target
     * user (2), P-Preferred-Service (3) and the affiliation command of annex F.4 (4); a 2xx means the request reached a
     * client of the target user.
     *
     * Mandatory mode, §9.2.1.2: a PUBLISH for the targeted user's client, with every group of its interest (step 5 c),
     * the group added or removed), the targeted client's ID (d)), no "status" or "expires" (e)) and a new <p-id> (f));
     * Expires 0 without any group (step 4). The client and its groups come from the targeted user's affiliation
     * notification ([affiliationSubscribe] first); with several clients, the first one notified.
     */
    suspend fun affiliateRemote(target: String, group: String, affiliate: Boolean, negotiated: Boolean): ProcedureResult {
        requireAuthorised("affiliation")
        val operation = "${if (affiliate) "affiliate" else "deaffiliate"} ${if (negotiated) "negotiated" else "mandatory"}"
        val info = client.infoPart(service, requestUri = target)
        val response = if (negotiated) {
            val request = ua.newRequest("MESSAGE", client.psi(service), NameAddr(client.psi(service)))
            val command = Part(AffiliationCommand.contentType(service),
                AffiliationCommand.encode(if (affiliate) listOf(group) else emptyList(), if (affiliate) emptyList() else listOf(group)).toByteArray())
            val (type, body) = Multipart.encode(listOf(info, command))
            request.headers.add("P-Preferred-Service", client.icsi(service)).add("Content-Type", type)
            runCatching { ua.send(SipRequest(request.method, request.uri, request.headers, body)) }.getOrNull()
        } else {
            val known = _state.value.targetAffiliations[target]?.clients?.entries?.firstOrNull()
                ?: throw IllegalStateException("the affiliation of $target is not known: affiliation-subscribe first")
            val groups = (Affiliation.interested(known.value) - group).let { if (affiliate) it + group else it }
            val parts = listOfNotNull(info, if (groups.isEmpty()) null
                else Part(Affiliation.CONTENT_TYPE, Affiliation.publish(service, target, known.key, groups).toByteArray()))
            val request = client.serviceRequest(service, "PUBLISH", "presence", parts)
                .also { it.headers.add("Expires", if (groups.isEmpty()) "0" else FOREVER) }
            runCatching { ua.send(request) }.getOrNull()
        }
        val outcome = result("$operation $group for $target", response)
        _state.update { it.copy(remoteAffiliation = outcome) }
        event("remote affiliation $operation $group for $target: ${outcome.status}${outcome.warning?.let { " ($it)" } ?: ""}")
        return outcome
    }

    // §9.2.1.5: a negotiated-mode request received.

    /**
     * TS 24.379 §9.2.1.5: a MESSAGE with the service's ICSI in P-Asserted-Service (when the core asserts one) and the
     * affiliation command: 1) 200 (OK) at once, then 2) the user's confirmation is sought: the request waits
     * ([AffiliationRequest]) for [answerAffiliationRequest].
     */
    private suspend fun affiliationCommand(tx: ServerTransaction, body: String) {
        val (affiliate, leave) = runCatching { AffiliationCommand.parse(body) }.getOrElse { tx.respond(400); return }
        tx.respond(200)
        val request = AffiliationRequest(requester(service, tx.request), affiliate, leave, System.currentTimeMillis())
        _state.update { it.copy(affiliationRequest = request) }
        event("affiliation request from ${request.from}: affiliate $affiliate, de-affiliate $leave")
        listener?.invoke(request)
    }

    /** Told of each received negotiated-mode request (the app prompts the user). */
    @Volatile var listener: ((AffiliationRequest) -> Unit)? = null

    /**
     * TS 24.379 §9.2.1.5 steps 2) and 3) (`affiliation-accept [<group>...]`, `affiliation-decline`): accepted, the
     * accepted lists (the groups named in [only], when some are) are applied as in §9.2.1.2: a) affiliation to each
     * group not affiliated, b) de-affiliation from each affiliated ([McxClient.changeAffiliation]); declined, nothing.
     */
    suspend fun answerAffiliationRequest(accept: Boolean, only: Set<String> = emptySet()) {
        val request = _state.value.affiliationRequest ?: throw IllegalStateException("no $label affiliation request")
        _state.update { it.copy(affiliationRequest = null, lastAffiliationAnswer = if (accept) "accepted" else "declined") }
        event("affiliation request ${if (accept) "accepted" else "declined"}")
        if (!accept) return
        fun accepted(groups: List<String>) = if (only.isEmpty()) groups else groups.filter { it in only }
        client.changeAffiliation(service, accepted(request.affiliate), accepted(request.deaffiliate))
    }

    // §9.2.1.6: group dynamic data.

    /**
     * TS 24.379 §9.2.1.6 (`gdd-subscribe <group> [fetch] [items]`): 1) to the PSI, 2) the info body naming the group, 3)
     * P-Preferred-Service, 4) Expires 4294967295 or 5) 0 (fetch), 6) Accept application/pidf+xml, 7) the per-group
     * simple-filter of §9.3.2.2 with the group and the [items] asked for (none: all).
     */
    suspend fun gddSubscribe(group: String, fetch: Boolean = false, items: List<String> = emptyList()): ProcedureResult {
        requireAuthorised("group dynamic data")
        if (!fetch && subscription(Kind.GDD, group) != null) throw IllegalStateException("already subscribed to $group")
        val filter = GroupDynamicData.filter(service, group, items, "gdd-" + UUID.randomUUID().toString().take(8))
        val parts = listOf(client.infoPart(service, requestUri = group), Part(SimpleFilter.CONTENT_TYPE, filter.toByteArray()))
        val response = subscribe(Kind.GDD, group, "presence", Affiliation.CONTENT_TYPE, parts, fetch)
        val outcome = result(if (fetch) "fetch" else "subscribe", response)
        _state.update { s ->
            val known = s.groupDynamicData[group] ?: GroupDynamic(false, null)
            s.copy(groupDynamicData = s.groupDynamicData + (group to known.copy(subscribed = !fetch && response?.isSuccess == true, status = outcome.status)))
        }
        return outcome
    }

    /** §9.2.1.6, in the dialog (`gdd-resubscribe <group>`, `gdd-unsubscribe <group>`): Expires 4294967295 or 0, the Accept. */
    suspend fun gddResubscribe(group: String, end: Boolean = false): ProcedureResult {
        val subscription = subscription(Kind.GDD, group) ?: throw IllegalStateException("no group dynamic data subscription to $group")
        val response = inDialog(subscription, "presence", Affiliation.CONTENT_TYPE, end)
        _state.update { s ->
            val known = s.groupDynamicData[group] ?: GroupDynamic(false, null)
            s.copy(groupDynamicData = s.groupDynamicData + (group to known.copy(subscribed = !end, status = response?.status ?: 0)))
        }
        return result(if (end) "unsubscribe" else "resubscribe", response)
    }

    private fun gddNotify(group: String, body: String?) {
        body ?: return
        val data = runCatching { GroupDynamicData.parse(body) }.getOrElse { event("dynamic data of $group unread: ${it.message}"); return }
        if (data.group.isNotEmpty() && data.group != group) { event("dynamic data of ${data.group} on the subscription to $group: ignored"); return }
        _state.update { s ->
            val known = s.groupDynamicData[group] ?: GroupDynamic(true, null)
            s.copy(groupDynamicData = s.groupDynamicData + (group to known.copy(data = data, notifications = known.notifications + 1)))
        }
        event("dynamic data of $group: status ${data.groupStatus}, call ongoing ${data.callOngoing}, ${data.members.size} members")
    }

    // Subscriptions.

    private fun subscription(kind: Kind, subject: String) =
        subscriptions.values.firstOrNull { it.kind == kind && it.subject == subject && !it.fetch }

    private suspend fun subscribe(kind: Kind, subject: String, eventPackage: String, accept: String, parts: List<Part>, fetch: Boolean): SipResponse? {
        val request = client.serviceRequest(service, "SUBSCRIBE", eventPackage, parts)
        request.headers.add("Contact", client.contactFor(service)).add("Accept", accept).add("Expires", if (fetch) "0" else FOREVER)
        val subscription = Subscription(kind, subject, request, fetch)
        subscriptions[request.callId] = subscription
        val response = runCatching { ua.send(request) }.getOrElse { event("$eventPackage SUBSCRIBE for $subject: ${it.message}"); null }
        event("$eventPackage ${if (fetch) "fetch" else "subscription"} for $subject: ${response?.status}")
        if (response == null || !response.isSuccess) { subscriptions.remove(request.callId); return response }
        subscription.dialog = runCatching { Dialog.fromResponse(ua.lastSent[request.callId] ?: request, response) }.getOrNull()
        // A fetch stays known until its one NOTIFY ("terminated"), which carries the state asked for.
        return response
    }

    private suspend fun inDialog(subscription: Subscription, eventPackage: String, accept: String, end: Boolean): SipResponse? {
        val dialog = subscription.dialog ?: throw IllegalStateException("the subscription has no dialog")
        val request = ua.inDialog(dialog, "SUBSCRIBE")
        request.headers.add("Contact", client.contactFor(service)).add("Event", eventPackage).add("Accept", accept)
            .add("Expires", if (end) "0" else FOREVER)
        if (end) { subscriptions.remove(subscription.request.callId); ending.add(subscription.request.callId) }
        val response = runCatching { withTimeoutOrNull(10_000) { ua.send(request, dialog) } }.getOrNull()
        event("$eventPackage ${if (end) "unsubscribe" else "resubscribe"} for ${subscription.subject}: ${response?.status}")
        return response
    }

    /**
     * RFC 6665 §4.1.3: a NOTIFY of one of these subscriptions (200, then its body read: §7.2.4, §9.2.1.3, §9.2.1.6;
     * "terminated" ends it), or a MESSAGE with the service's affiliation command (§9.2.1.5); false for anything else.
     */
    suspend fun handle(tx: ServerTransaction): Boolean {
        val request = tx.request
        if (request.method == "MESSAGE") {
            val asserted = request.headers["P-Asserted-Service"]
            if (asserted != null && !asserted.contains(client.icsi(service))) return false
            val command = Multipart.parse(request.contentType, request.body)
                .firstOrNull { it.mediaType == AffiliationCommand.contentType(service) }?.text ?: return false
            affiliationCommand(tx, command)
            return true
        }
        if (request.method != "NOTIFY") return false
        val terminated = (request.headers["Subscription-State"] ?: "").trim().startsWith("terminated")
        val subscription = subscriptions[request.callId]
        if (subscription == null) {
            if (request.callId !in ending) return false
            tx.respond(200)
            if (terminated) ending.remove(request.callId)
            return true
        }
        tx.respond(200)
        read(subscription.kind, subscription.subject, request)
        if (terminated) {
            subscriptions.remove(request.callId)
            if (!subscription.fetch) event("subscription for ${subscription.subject} terminated")
            _state.update { s ->
                when (subscription.kind) {
                    Kind.SETTINGS -> s.copy(settingsSubscribed = false)
                    Kind.TARGET -> s.copy(targetAffiliations = s.targetAffiliations.mapValues { (k, v) -> if (k == subscription.subject) v.copy(subscribed = false) else v })
                    Kind.GDD -> s.copy(groupDynamicData = s.groupDynamicData.mapValues { (k, v) -> if (k == subscription.subject) v.copy(subscribed = false) else v })
                }
            }
        }
        return true
    }

    private fun read(kind: Kind, subject: String, request: SipRequest) {
        if (request.body.isEmpty()) return
        val parts = Multipart.parse(request.contentType, request.body)
        when (kind) {
            Kind.SETTINGS -> settingsNotify(parts.firstOrNull { it.mediaType == PocSettings.CONTENT_TYPE }?.text)
            Kind.TARGET -> targetNotify(subject, parts.firstOrNull { it.mediaType == Affiliation.CONTENT_TYPE }?.text)
            Kind.GDD -> gddNotify(subject, parts.firstOrNull { it.mediaType == Affiliation.CONTENT_TYPE }?.text)
        }
    }

    /** At MC log-off (RFC 6665 §4.1.2.3): each subscription ends in its dialog (Expires 0); its final NOTIFY answered 200. */
    suspend fun logOff() {
        for (subscription in subscriptions.values.filter { !it.fetch }) {
            val eventPackage = if (subscription.kind == Kind.SETTINGS) "poc-settings" else "presence"
            val accept = if (subscription.kind == Kind.SETTINGS) PocSettings.CONTENT_TYPE else Affiliation.CONTENT_TYPE
            runCatching { inDialog(subscription, eventPackage, accept, end = true) }
        }
        drop()
    }

    /** The flow or the registration is gone: so are the subscriptions (the next registration has none). */
    fun drop() {
        subscriptions.clear(); ending.clear()
        _state.update { s -> s.copy(settingsSubscribed = false, affiliationRequest = null,
            targetAffiliations = s.targetAffiliations.mapValues { it.value.copy(subscribed = false) },
            groupDynamicData = s.groupDynamicData.mapValues { it.value.copy(subscribed = false) }) }
    }

    companion object {
        private const val FOREVER = "4294967295"
        private const val SETTINGS = "settings"

        /**
         * The user asking for a negotiated-mode change: the server passes the request on from its PSI with the
         * requester in the service information's <mcptt-calling-user-id> (<mcvideo-calling-user-id>), TS 24.379
         * §9.2.2.2.8 (TS 24.281 §8.2.2.2.8); the From header only when the service information names nobody.
         */
        fun requester(service: McService, request: SipRequest): String? {
            val parts = runCatching { Multipart.parse(request.contentType, request.body) }.getOrDefault(emptyList())
            val calling = when (service) {
                McService.MCPTT -> parts.firstOrNull { it.mediaType == McpttInfo.CONTENT_TYPE }
                    ?.let { runCatching { McpttInfo.parse(it.text).callingUserId }.getOrNull() }
                McService.MCVIDEO -> parts.firstOrNull { it.mediaType == McvideoInfo.CONTENT_TYPE }
                    ?.let { runCatching { McvideoInfo.parse(it.text).callingUserId }.getOrNull() }
                McService.MCDATA -> null
            }
            return calling?.takeIf { it.isNotBlank() } ?: runCatching { request.from.uri }.getOrNull()
        }
    }
}
