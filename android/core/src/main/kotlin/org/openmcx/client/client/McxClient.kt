//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.PocSettings
import org.openmcx.client.body.SimpleFilter
import org.openmcx.client.documents.DocumentClient
import org.openmcx.client.documents.GroupDocument
import org.openmcx.client.documents.ServiceConfig
import org.openmcx.client.documents.UserProfile
import org.openmcx.client.http.HttpClient
import org.openmcx.client.idms.IdmsClient
import org.openmcx.client.idms.IdmsRefused
import org.openmcx.client.security.KeyClient
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.WireProfile
import org.openmcx.client.idms.LoopbackRedirect
import org.openmcx.client.idms.Tokens
import org.openmcx.client.mcdata.McdataInfo
import org.openmcx.client.mcptt.FunctionalAliasPidf
import org.openmcx.client.mcptt.FunctionalAliasRules
import org.openmcx.client.mcptt.FunctionalAliasState
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcvideo.McvideoInfo
import org.openmcx.client.sip.Connector
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.ServerTransaction
import org.openmcx.client.sip.SipFlow
import org.openmcx.client.sip.SipMessage
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import org.openmcx.client.sip.UaIdentity
import org.openmcx.client.sip.UserAgent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** ICSIs and feature tags (TS 24.379 §7.2.1, TS 24.282 §7.2.1). */
object Icsi {
    const val MCPTT = "urn:urn-7:3gpp-service.ims.icsi.mcptt"
    const val MCDATA = "urn:urn-7:3gpp-service.ims.icsi.mcdata"
    const val MCDATA_SDS = "urn:urn-7:3gpp-service.ims.icsi.mcdata.sds"
    const val MCDATA_FD = "urn:urn-7:3gpp-service.ims.icsi.mcdata.fd"
    const val MCVIDEO = "urn:urn-7:3gpp-service.ims.icsi.mcvideo"

    /** An ICSI as a feature tag value: percent-encoded with upper-case hex, as the servers match it. */
    fun encoded(icsi: String) = icsi.replace(":", "%3A")

    val mcpttContactTags = ";+g.3gpp.mcptt;+g.3gpp.icsi-ref=\"${encoded(MCPTT)}\""
    val mcvideoContactTags = ";+g.3gpp.mcvideo;+g.3gpp.icsi-ref=\"${encoded(MCVIDEO)}\""
    val mcdataContactTags = ";+g.3gpp.mcdata.sds;+g.3gpp.mcdata.fd;+g.3gpp.icsi-ref=\"${encoded(MCDATA)},${encoded(MCDATA_SDS)},${encoded(MCDATA_FD)}\""
}

/** Opens a URL in the user's browser, for the interactive login (TS 24.482, RFC 8252). */
fun interface BrowserLauncher {
    fun open(url: String)
}

/** Handles the requests of one MC service; returns false when a request is not its own. */
interface ServiceHandler {
    suspend fun handle(tx: ServerTransaction): Boolean
    /** The flow was replaced: calls and transfers on the old one are gone. */
    suspend fun flowLost() {}
    /** The user logs off (TS 24.379 §7.2.1A step 4): end the service's calls while the flow is still up. */
    suspend fun logOff() {}
}

data class ServiceState(val authorised: Boolean = false, val affiliations: Map<String, String> = emptyMap())

/** What the client reports: its start-up phase and the state of each service. */
data class ClientStatus(
    val phase: String = "idle",
    val registered: Boolean = false,
    val mcptt: ServiceState = ServiceState(),
    val mcdata: ServiceState = ServiceState(),
    val mcvideo: ServiceState = ServiceState(),
    val tokenExpiresAt: Long = 0,
    /** Access tokens obtained with the refresh token (TS 33.180 B.6), and with the password. */
    val tokenRefreshes: Int = 0,
    val passwordLogins: Int = 0,
    val lastError: String? = null,
    /** After [McxClient.logOff], until [McxClient.logOn]: the servers' answers to the log-off, and the de-REGISTER's. */
    val loggedOff: Boolean = false,
    val logOff: Map<String, Int> = emptyMap(),
    val deregistered: Int? = null,
)

/**
 * The MC UE's signalling engine: identity, documents, one outbound flow and its
 * registration, service authorisation and affiliation for MCPTT and MCData, and every
 * refresh that keeps them (docs/android/wire/phase1-startup.md).
 */
class McxClient(
    parent: CoroutineScope,
    val config: ClientConfig,
    private val http: HttpClient,
    private val connector: Connector,
    private val log: EventLog = EventLog(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val keepaliveMs: Long = 25_000,
    private val browser: BrowserLauncher? = null,
    /** The MCPTT functional aliases of the last user profile this user had, shown until the profile is read again. */
    knownAliases: List<String> = emptyList(),
) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
    private val _status = MutableStateFlow(ClientStatus())
    val status: StateFlow<ClientStatus> = _status

    val ua = UserAgent(scope, UaIdentity(config.sip.publicUserId, config.sip.privateUserId, config.sip.password,
        config.domain, config.clientId))
    private val idms = IdmsClient(http, config.idms.issuer, config.idms.clientId, config.idms.redirectUri,
        // TS 33.180 5.3: a protected client also asks for its key management services.
        scope = "openid 3gpp:mc:ptt_service 3gpp:mc:data_service" +
            (if (config.security != null) " 3gpp:mc:ptt_key_management_service 3gpp:mc:video_key_management_service" else ""),
        clock = clock)

    /**
     * MCVideo's key management client (TS 24.281 4.7, TS 33.180 5.3): it keys as the MCVideo ID, and
     * its CSK, uploaded at MCVideo service authorisation, protects transmission control (9.4).
     */
    @Volatile var videoKeys: KeyClient? = null
        private set
    @Volatile var videoCsk: KeyMessage? = null
        private set
    private var videoCskUpload: String? = null

    private suspend fun provisionVideoKeys(token: String) {
        val security = config.security ?: return
        val mcvideo = config.mcvideo ?: return
        val account = config.mcptt?.userId ?: mcvideo.userId
        val keys = videoKeys ?: KeyClient(http, security.kmsUri, mcvideo.userId, account,
            WireProfile(security.messageType, security.algorithm, security.keyParametersIei, security.encryptedPayloadIei)).also { videoKeys = it }
        keys.provision(token)
        if (videoCsk == null) {
            val (csk, upload) = keys.makeCsk(mcvideo.psi)
            videoCsk = csk; videoCskUpload = upload
            event("MCVideo keys: KMS ${security.kmsUri}, CSK %08x".format(csk.csbId))
        }
    }
    private val documents = DocumentClient(http, config.cms, config.gms)

    @Volatile var tokens: Tokens? = null
        private set
    @Volatile var profile: UserProfile? = null
    /** The Resource-Priority values of the service configuration documents, by service and level (TS 24.484 §8.4). */
    @Volatile var serviceResourcePriorities: Map<String, Map<String, String>> = emptyMap()
        private set
    /** The MCPTT service configuration has a floor request queue (TS 24.484 §8.4.2.1 `<floor-control-queue>`). */
    @Volatile var mcpttFloorQueue: Boolean = false
        private set
    /** The GMS group documents fetched at login, by group ID. */
    @Volatile var groupDocuments: Map<String, GroupDocument> = emptyMap()
        private set

    val handlers = mutableListOf<ServiceHandler>()
    private var registration: SipRequest? = null
    private val subscriptions = ConcurrentHashMap<McService, Subscription>()
    private val authEtags = ConcurrentHashMap<McService, String>()
    private val affiliationEtags = ConcurrentHashMap<McService, String>()
    private var housekeeping: Job? = null

    /**
     * Functional aliases (TS 24.379 §9A.2.1, TS 24.282 §22.2.1, TS 24.281 §20.2.1), one channel per service: the aliases
     * the user may use, the status subscription and the last status change.
     */
    private inner class AliasChannel(val service: McService, available: List<String>) {
        val state = MutableStateFlow(FunctionalAliasState(available = available))
        @Volatile var request: SipRequest? = null
        @Volatile var dialog: Dialog? = null
        @Volatile var publish: FunctionalAliasRules.Change? = null
        /** Aliases the user asked to take over that the server has not offered yet (§9A.2.1.2 step 6 d, NOTE 4). */
        val takeOverWanted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
    private val aliasChannels: Map<McService, AliasChannel> = buildMap {
        config.mcptt?.let { put(McService.MCPTT, AliasChannel(McService.MCPTT, it.functionalAliases.ifEmpty { knownAliases })) }
        config.mcdata?.let { put(McService.MCDATA, AliasChannel(McService.MCDATA, it.functionalAliases)) }
        config.mcvideo?.let { put(McService.MCVIDEO, AliasChannel(McService.MCVIDEO, it.functionalAliases)) }
    }
    private val noAliases = MutableStateFlow(FunctionalAliasState())
    /** The MCPTT functional aliases; [aliasesOf] for the other services. */
    val aliases: StateFlow<FunctionalAliasState> get() = aliasesOf(McService.MCPTT)
    fun aliasesOf(service: McService): StateFlow<FunctionalAliasState> = aliasChannels[service]?.state ?: noAliases

    private class Subscription(val service: McService, val request: SipRequest) {
        @Volatile var dialog: Dialog? = null
        val firstNotify = CompletableDeferred<Unit>()
        @Volatile var affiliations: Map<String, String> = emptyMap()
        @Volatile var changed = CompletableDeferred<Unit>()
    }

    fun event(text: String) = log.add(text)

    private fun setPhase(phase: String) { _status.update { it.copy(phase = phase) }; event("phase $phase") }

    /** Start and keep the client up: on any failure, back off and start again. */
    fun start() {
        scope.launch {
            var backoff = 2_000L
            while (isActive) {
                // Logged off: nothing starts again until the user logs on.
                if (loggedOff) logOnRequest.await()
                try {
                    run()
                    backoff = 2_000L
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (loggedOff) { setPhase("logged-off"); continue }
                    event("start-up failed: ${e.message}")
                    _status.update { it.copy(lastError = e.message, registered = false) }
                    ua.flow?.close(e)
                    // Back off, unless told to reconnect now (a new network).
                    if (!reconnectNow.isCompleted) withTimeoutOrNull(backoff) { reconnectNow.await() }
                    backoff = if (reconnectNow.isCompleted) 2_000L else (backoff * 2).coerceAtMost(60_000)
                    reconnectNow = CompletableDeferred()
                }
            }
        }
    }

    fun stop() {
        ua.flow?.close()
        scope.cancel()
    }

    @Volatile private var reconnectNow = CompletableDeferred<Unit>()
    @Volatile private var loggedOff = false
    @Volatile private var logOnRequest = CompletableDeferred<Unit>()

    /**
     * Log off every MC service (TS 24.379, TS 24.281 and TS 24.282 §7.2.1A step 4 and NOTE 3): the calls end and the
     * subscriptions held with the authorisations end (RFC 6665 §4.1.2.3, SUBSCRIBE with Expires 0); each server is
     * sent a PUBLISH for poc-settings with Expires 0 that names the publication in SIP-If-Match and has no body
     * (RFC 3903 §4.5), and removes the affiliations and the binding (§7.3.5). Then the dynamic keys go (the MCVideo CSK,
     * TS 33.180 §9; tokens and KMS key material stay), the client de-registers (no MC service is left, TS 24.229
     * §5.1.1.6) and the flow closes. Nothing authorises a service again before [logOn].
     */
    suspend fun logOff() {
        if (loggedOff) throw IllegalStateException("already logged off")
        loggedOff = true
        logOnRequest = CompletableDeferred()
        housekeeping?.cancel()
        setPhase("logging-off")
        handlers.forEach { runCatching { it.logOff() } }
        for (channel in aliasChannels.values) {
            channel.dialog?.let { unsubscribe(it, channel.service, FunctionalAliasPidf.CONTENT_TYPE) }
            channel.dialog = null; channel.request = null
            channel.state.update { it.copy(subscribed = false) }
        }
        for ((service, subscription) in subscriptions) subscription.dialog?.let { unsubscribe(it, service, Affiliation.CONTENT_TYPE) }
        subscriptions.clear()
        val answers = mutableMapOf<String, Int>()
        for (service in listOf(McService.MCVIDEO, McService.MCDATA, McService.MCPTT)) {
            val etag = authEtags.remove(service) ?: continue
            val request = ua.newRequest("PUBLISH", psi(service), NameAddr(psi(service)))
            request.headers.add("P-Preferred-Service", icsi(service)).add("Event", "poc-settings")
                .add("Expires", "0").add("SIP-If-Match", etag)
            val status = runCatching { withTimeoutOrNull(10_000) { ua.send(request) }?.status }.getOrNull() ?: 0
            answers[service.name.lowercase()] = status
            updateService(service) { ServiceState() }
            event("${service.name} logged off: $status")
        }
        affiliationEtags.clear()
        videoCsk = null; videoCskUpload = null
        val deregistered = runCatching { withTimeoutOrNull(10_000) { deregister() } }.getOrNull()
        registration = null
        _status.update { it.copy(loggedOff = true, logOff = answers, deregistered = deregistered, registered = false) }
        event("logged off: $answers, de-REGISTER $deregistered")
        ua.flow?.close()
    }

    /** After [logOff]: register again and authorise the services with the tokens kept (new CSKs), as at start-up. */
    fun logOn() {
        if (!loggedOff) throw IllegalStateException("not logged off")
        loggedOff = false
        _status.update { it.copy(loggedOff = false, logOff = emptyMap(), deregistered = null) }
        event("logging on")
        logOnRequest.complete(Unit)
    }

    /** RFC 6665 §4.1.2.3: end a subscription with a SUBSCRIBE in its dialog, Expires 0. */
    private suspend fun unsubscribe(dialog: Dialog, service: McService, accept: String) {
        val request = ua.inDialog(dialog, "SUBSCRIBE")
        request.headers.add("Contact", contactFor(service)).add("Event", "presence").add("Accept", accept).add("Expires", "0")
        runCatching { withTimeoutOrNull(5_000) { ua.send(request, dialog) } }
    }

    /** Drop the flow and start again at once (a network change, or a test). */
    fun reconnect(reason: String) {
        event("reconnect: $reason")
        reconnectNow.complete(Unit)
        ua.flow?.close(IllegalStateException(reason))
    }

    /** One keepalive on the current flow now; false (and a reconnect under way) when it is dead. */
    suspend fun probeFlow(): Boolean = ua.flow?.probe() ?: false

    /** Run a refresh now instead of at its timer: `register`, `subscription` or `token`. */
    suspend fun refreshNow(what: String) {
        when (what) {
            "register" -> register(config.sip.registerExpires)
            "subscription" -> refreshSubscription(McService.MCPTT)
            "token" -> renewToken()
            else -> throw IllegalArgumentException("refresh register|subscription|token")
        }
        setPhase("ready")
    }

    private suspend fun renewToken() {
        event("renewing the access token")
        refreshTokens()
        config.mcptt?.let { authorise(McService.MCPTT) }
        config.mcdata?.let { authorise(McService.MCDATA) }
        config.mcvideo?.let { authorise(McService.MCVIDEO) }
    }

    private suspend fun refreshTokens() {
        // TS 33.180 B.6: with the refresh token, not the password. The password sign-in is
        // the fallback when IdMS refuses the refresh token and the profile has a password.
        val refreshed = tokens?.let { current ->
            current.refreshToken?.let { token ->
                try {
                    idms.refresh(token, current.nonce)
                } catch (e: IdmsRefused) {
                    event("IdMS refused the refresh token")
                    if (config.idms.username == null || config.idms.password.isNullOrEmpty()) throw e
                    null
                }
            }
        }
        if (refreshed != null) {
            adopt(refreshed)
            _status.update { it.copy(tokenRefreshes = it.tokenRefreshes + 1) }
            event("access token renewed with the refresh token; expires in ${refreshed.expiresAt - clock()} s")
        } else {
            login()
        }
    }

    /** One life of the client: up to "ready", then held until the flow fails. */
    private suspend fun run() {
        when {
            tokens == null -> login()
            tokens!!.expiresAt - clock() < 600 -> refreshTokens()
        }
        setPhase("connecting")
        val connection = connector.connect(config.sip.proxy, config.sip.proxyPort)
        val flow = SipFlow(scope, connection, ::receive, { dir, m -> trace(dir, m) }, keepaliveMs = keepaliveMs)
        ua.flow = flow
        flow.start()
        event("flow ${connection.localHost}:${connection.localPort} → ${config.sip.proxy}:${config.sip.proxyPort} ${connection.transport}")
        register(config.sip.registerExpires)
        _status.update { it.copy(registered = true) }
        config.mcptt?.let { bringUp(McService.MCPTT, it.groups.ifEmpty { profile?.groups ?: emptyList() }) }
        config.mcdata?.let { bringUp(McService.MCDATA, it.groups) }
        config.mcvideo?.let { bringUp(McService.MCVIDEO, it.groups) }
        setPhase("ready")
        _status.update { it.copy(lastError = null) }
        housekeeping?.cancel()
        housekeeping = scope.launch { housekeeping() }
        val cause = flow.closed.await()
        housekeeping?.cancel()
        _status.update { it.copy(registered = false) }
        setPhase("flow-lost")
        subscriptions.clear()
        handlers.forEach { runCatching { it.flowLost() } }
        throw IllegalStateException("flow closed: ${cause?.message ?: "locally"}")
    }

    private fun trace(direction: String, message: SipMessage) {
        val line = message.startLine()
        val cseq = message.headers["CSeq"] ?: ""
        event("sip $direction $line ($cseq)")
        log.trace(direction, message)
    }

    // Identity and documents (TS 24.482, TS 24.484, TS 24.481).

    private suspend fun login() {
        setPhase("login")
        val username = config.idms.username
        val t = if (username != null) idms.loginDirect(username, config.idms.password ?: "") else interactiveLogin()
        adopt(t)
        _status.update { it.copy(passwordLogins = it.passwordLogins + 1) }
        event("logged in; token expires in ${t.expiresAt - clock()} s")
        config.mcptt?.let { mcptt ->
            runCatching { documents.userProfile(mcptt.userId, mcptt.profileIndex, t.accessToken) }
                .onSuccess { up ->
                    profile = up
                    event("user profile: groups ${up.groups}, private call targets ${up.privateCallTargets}, functional aliases ${up.functionalAliases}")
                    // The provisioning names the aliases, or else the CMS user profile does (TS 24.484 §8.3.2.1 <FunctionalAliasList>):
                    // the profile read now replaces the one remembered from before.
                    aliasChannels[McService.MCPTT]?.let { channel ->
                        if (mcptt.functionalAliases.isEmpty()) channel.state.update { it.copy(available = up.functionalAliases) }
                    }
                }
                .onFailure { event("user profile: ${it.message}") }
        }
        // TS 24.379, TS 24.281 and TS 24.282 §6.2.8.1.15: the Resource-Priority values of each service's service
        // configuration document; without them, emergency and imminent peril requests cannot be made.
        for (service in listOfNotNull("mcptt".takeIf { config.mcptt != null }, "mcvideo".takeIf { config.mcvideo != null },
                "mcdata".takeIf { config.mcdata != null })) {
            runCatching { documents.serviceConfiguration(service, t.accessToken) }
                .mapCatching { text ->
                    if (service == "mcptt") mcpttFloorQueue = ServiceConfig.floorQueue(text)
                    ServiceConfig.resourcePriorities(text)
                }
                .onSuccess { serviceResourcePriorities = serviceResourcePriorities + (service to it)
                    event("service configuration $service: $it" + if (service == "mcptt") ", floor queue $mcpttFloorQueue" else "") }
                .onFailure { event("service configuration $service: ${it.message}") }
        }
        for (group in ((config.mcptt?.groups ?: emptyList()) + (config.mcdata?.groups ?: emptyList())).distinct()) {
            runCatching { documents.group(group, t.accessToken) }
                .onSuccess {
                    groupDocuments = groupDocuments + (group to it)
                    event("group $group: services ${it.services.map { s -> s.substringAfterLast('.') }}")
                }
                .onFailure { event("group $group: ${it.message}") }
        }
    }

    /** Take new tokens, once they name this client and user. */
    private fun adopt(t: Tokens) {
        if (t.clientId != null && t.clientId != config.clientId) throw IllegalStateException("token client_id ${t.clientId} is not ${config.clientId}")
        config.mcptt?.let { if (t.mcpttId != null && t.mcpttId != it.userId) throw IllegalStateException("token MCPTT ID ${t.mcpttId}") }
        tokens = t
        tokenLifetime = (t.expiresAt - clock()).coerceAtLeast(0)
        _status.update { it.copy(tokenExpiresAt = t.expiresAt) }
    }

    /** The lifetime of the current access token when it was issued, seconds. */
    private var tokenLifetime = 0L

    /** Renew 5 minutes before expiry, or halfway for a token that lives less than 10 minutes. */
    private fun renewalLead(): Long = minOf(300L, tokenLifetime / 2)

    /**
     * The user signs in in the browser; the IdMS redirects to the loopback redirect URI
     * (RFC 8252 §7.3), where this client receives the authorization code.
     */
    private suspend fun interactiveLogin(): Tokens {
        val launcher = browser ?: throw ConfigError("no IdMS credentials and no browser for an interactive login")
        return LoopbackRedirect(config.idms.redirectUri).use { loopback ->
            val request = idms.authorizationUrl()
            setPhase("login-browser")
            launcher.open(request.url)
            idms.complete(request, loopback.await())
        }
    }

    // Registration (TS 24.379 §7.2.1, TS 24.282 §7.2.1, RFC 5626).

    private fun registerContact(): String =
        "<${ua.contactUri()}>;+sip.instance=\"<${config.clientId}>\";reg-id=1;+g.3gpp.mcptt;+g.3gpp.mcdata.sds;" +
            (if (config.mcdata != null) "+g.3gpp.mcdata.fd;" else "") +
            (if (config.mcvideo != null) "+g.3gpp.mcvideo;" else "") +
            "+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)},${Icsi.encoded(Icsi.MCDATA)},${Icsi.encoded(Icsi.MCDATA_SDS)}" +
            (if (config.mcdata != null) ",${Icsi.encoded(Icsi.MCDATA_FD)}" else "") +
            (if (config.mcvideo != null) ",${Icsi.encoded(Icsi.MCVIDEO)}" else "") + "\""

    private suspend fun register(expires: Int): Int {
        setPhase("registering")
        val previous = registration
        val request = if (previous == null) ua.newRequest("REGISTER", "sip:${config.domain}", NameAddr(config.sip.publicUserId))
        else ua.newRequest("REGISTER", "sip:${config.domain}", NameAddr(config.sip.publicUserId), from = previous.from,
            callId = previous.callId, cseq = (ua.lastSent[previous.callId] ?: previous).cseq.number + 1)
        request.headers.add("Contact", registerContact())
            .add("Supported", "outbound, path")
            .add("Expires", expires.toString())
        val response = ua.send(request)
        if (!response.isSuccess) throw IllegalStateException("REGISTER: ${response.status} ${response.reason}")
        registration = request
        val mine = response.headers.values("Contact").map(NameAddr::parse)
            .firstOrNull { it.params["+sip.instance"]?.contains(config.clientId) == true }
        val granted = mine?.params?.get("expires")?.toIntOrNull() ?: response.headers["Expires"]?.toIntOrNull() ?: expires
        event("registered for $granted s")
        return granted
    }

    /** RFC 3261 §10.2.2: remove this registration (its Contact, Expires 0); the final status. */
    private suspend fun deregister(): Int? {
        val previous = registration ?: return null
        val request = ua.newRequest("REGISTER", "sip:${config.domain}", NameAddr(config.sip.publicUserId), from = previous.from,
            callId = previous.callId, cseq = (ua.lastSent[previous.callId] ?: previous).cseq.number + 1)
        request.headers.add("Contact", registerContact()).add("Expires", "0")
        return ua.send(request).status
    }

    // Service authorisation: PUBLISH poc-settings (TS 24.379 §7.2.2, TS 24.282 §7.2.2).

    private fun psi(service: McService) = when (service) {
        McService.MCPTT -> config.mcptt!!.psi; McService.MCDATA -> config.mcdata!!.psi; McService.MCVIDEO -> config.mcvideo!!.psi
    }
    private fun icsi(service: McService) = when (service) { McService.MCPTT -> Icsi.MCPTT; McService.MCDATA -> Icsi.MCDATA; McService.MCVIDEO -> Icsi.MCVIDEO }
    private fun ownId(service: McService) = when (service) {
        McService.MCPTT -> config.mcptt!!.userId; McService.MCDATA -> config.mcdata!!.userId; McService.MCVIDEO -> config.mcvideo!!.userId
    }
    fun contactFor(service: McService) = "<${ua.contactUri()}>" + when (service) {
        McService.MCPTT -> Icsi.mcpttContactTags; McService.MCDATA -> Icsi.mcdataContactTags; McService.MCVIDEO -> Icsi.mcvideoContactTags
    }

    private fun infoPart(service: McService, token: String? = null, requestUri: String? = null, withClientId: Boolean = true): Part {
        val clientId = if (withClientId) config.clientId else null
        return when (service) {
            McService.MCPTT -> Part(McpttInfo.CONTENT_TYPE, McpttInfo(accessToken = token, requestUri = requestUri, clientId = clientId).encode().toByteArray())
            McService.MCDATA -> Part(McdataInfo.CONTENT_TYPE, McdataInfo(accessToken = token, requestUri = requestUri, clientId = clientId).encode().toByteArray())
            McService.MCVIDEO -> Part(McvideoInfo.CONTENT_TYPE, McvideoInfo(accessToken = token, requestUri = requestUri, clientId = clientId).encode().toByteArray())
        }
    }

    private fun serviceRequest(service: McService, method: String, event: String, parts: List<Part>): SipRequest {
        val request = ua.newRequest(method, psi(service), NameAddr(psi(service)))
        val (type, body) = Multipart.encode(parts)
        request.headers.add("P-Preferred-Service", icsi(service)).add("Event", event)
        request.headers.add("Content-Type", type)
        return SipRequest(request.method, request.uri, request.headers, body)
    }

    private suspend fun authorise(service: McService) {
        setPhase("authorising-${service.name.lowercase()}")
        val token = tokens?.accessToken ?: throw IllegalStateException("no access token")
        val answerMode = when (service) {
            McService.MCPTT -> config.mcptt!!.answerMode; McService.MCVIDEO -> config.mcvideo!!.answerMode; McService.MCDATA -> null
        }
        if (service == McService.MCVIDEO && config.security != null) provisionVideoKeys(token)
        // TS 24.281 7.2.5: the MCVideo CSK goes to the MCVideo server with the authorisation, in application/mikey.
        val parts = listOfNotNull(infoPart(service, token = token), Part(PocSettings.CONTENT_TYPE, PocSettings.encode(config.clientId, answerMode).toByteArray()),
            videoCskUpload?.takeIf { service == McService.MCVIDEO }?.let { Part("application/mikey", it.toByteArray()) })
        var request = serviceRequest(service, "PUBLISH", "poc-settings", parts).also { it.headers.add("Expires", "4294967295") }
        authEtags[service]?.let { request.headers.add("SIP-If-Match", it) }
        var response = ua.send(request)
        if (response.status == 412) {
            // The server lost our publication (a restart): authorise afresh, then everything after it again.
            authEtags.remove(service); affiliationEtags.remove(service)
            request = serviceRequest(service, "PUBLISH", "poc-settings", parts).also { it.headers.add("Expires", "4294967295") }
            response = ua.send(request)
        }
        // §7.2.2: not authorised; the start-up is tried again after a growing delay. A 486 (§7.3.3 steps 3a and 3b,
        // warning 164, MCVideo 166, MCData 228) means the user is authorised on another client: the retry succeeds
        // once that one logs off or its authorisation ends.
        if (!response.isSuccess) throw IllegalStateException("${service.name} service authorisation: ${response.status} ${response.reason}" +
            (response.headers["Warning"]?.let { " ($it)" } ?: ""))
        response.headers["SIP-ETag"]?.let { authEtags[service] = it }
        updateService(service) { it.copy(authorised = true) }
        event("${service.name} authorised")
    }

    // Affiliation (TS 24.379 §9.2.1, TS 24.282 §8.2).

    private suspend fun subscribe(service: McService): Subscription {
        val parts = listOf(infoPart(service, requestUri = ownId(service), withClientId = false),
            Part(SimpleFilter.CONTENT_TYPE, SimpleFilter.forClient(service, config.clientId).toByteArray()))
        val request = serviceRequest(service, "SUBSCRIBE", "presence", parts)
        request.headers.add("Contact", contactFor(service)).add("Accept", Affiliation.CONTENT_TYPE).add("Expires", "4294967295")
        val subscription = Subscription(service, request)
        subscriptions[service] = subscription
        val response = ua.send(request)
        if (!response.isSuccess) throw IllegalStateException("${service.name} SUBSCRIBE presence: ${response.status} ${response.reason}")
        subscription.dialog = Dialog.fromResponse(ua.lastSent[request.callId] ?: request, response)
        event("${service.name} affiliation status subscribed for ${response.headers["Expires"]} s")
        return subscription
    }

    private suspend fun refreshSubscription(service: McService) {
        val subscription = subscriptions[service] ?: return
        val dialog = subscription.dialog ?: return
        val request = ua.inDialog(dialog, "SUBSCRIBE")
        request.headers.add("Contact", contactFor(service)).add("Event", "presence")
            .add("Accept", Affiliation.CONTENT_TYPE).add("Expires", "4294967295")
        val response = ua.send(request, dialog)
        event("${service.name} subscription refreshed: ${response.status}")
        if (!response.isSuccess) throw IllegalStateException("subscription refresh: ${response.status}")
    }

    private suspend fun affiliate(service: McService, groups: Collection<String>) {
        setPhase("affiliating-${service.name.lowercase()}")
        val subscription = subscriptions[service]!!
        withTimeoutOrNull(5_000) { subscription.firstNotify.await() }
        val body = Affiliation.publish(service, ownId(service), config.clientId, groups)
        val parts = listOf(infoPart(service, requestUri = ownId(service)), Part(Affiliation.CONTENT_TYPE, body.toByteArray()))
        val request = serviceRequest(service, "PUBLISH", "presence", parts).also { it.headers.add("Expires", "4294967295") }
        affiliationEtags[service]?.let { request.headers.add("SIP-If-Match", it) }
        subscription.changed = CompletableDeferred()
        var response = ua.send(request)
        if (response.status == 412) {
            affiliationEtags.remove(service)
            val retry = serviceRequest(service, "PUBLISH", "presence", parts).also { it.headers.add("Expires", "4294967295") }
            response = ua.send(retry)
        }
        if (!response.isSuccess) throw IllegalStateException("${service.name} affiliation: ${response.status} ${response.reason}")
        response.headers["SIP-ETag"]?.let { affiliationEtags[service] = it }
        // Affiliated when a NOTIFY says so for every group (§9.2.1.3); the p-id is not reliable.
        val deadline = System.currentTimeMillis() + 10_000
        while (groups.any { subscription.affiliations[it] != "affiliated" }) {
            val wait = deadline - System.currentTimeMillis()
            if (wait <= 0 || withTimeoutOrNull(wait) { subscription.changed.await() } == null) break
            subscription.changed = CompletableDeferred()
        }
        val missing = groups.filter { subscription.affiliations[it] != "affiliated" }
        if (missing.isNotEmpty()) throw IllegalStateException("${service.name} not affiliated to $missing")
        event("${service.name} affiliated to $groups")
    }

    private suspend fun bringUp(service: McService, groups: List<String>) {
        authorise(service)
        subscribe(service)
        if (groups.isNotEmpty()) affiliate(service, groups)
        // §9A.2.1.1: the status determination of the aliases this user may use, before any status change.
        val channel = aliasChannels[service]
        if (channel != null && channel.state.value.available.isNotEmpty())
            runCatching { subscribeAliases(channel) }.onFailure { event("functional alias status subscription: ${it.message}") }
    }

    // Functional aliases (TS 24.379 §9A.2.1, TS 24.282 §22.2.1, TS 24.281 §20.2.1).

    /** The service's info body naming [user] in its request URI, with the <request-type> or <req-type> of [type]. */
    private fun aliasInfo(service: McService, user: String, type: String? = null): Part = when (service) {
        McService.MCPTT -> Part(McpttInfo.CONTENT_TYPE, McpttInfo(requestUri = user, clientId = config.clientId, requestType = type).encode().toByteArray())
        // TS 24.282 §22.2.1.3 step 2 b: <request-type> in <mcdata-Params>.
        McService.MCDATA -> Part(McdataInfo.CONTENT_TYPE, McdataInfo(requestUri = user, clientId = config.clientId, requestType = type).encode().toByteArray())
        // TS 24.281 §20.2.1.3 step 2: <req-type> in <anyExt>.
        McService.MCVIDEO -> Part(McvideoInfo.CONTENT_TYPE, McvideoInfo(requestUri = user, clientId = config.clientId, reqType = type).encode().toByteArray())
    }

    /**
     * §9A.2.1.3 steps 1 to 4 (TS 24.282 §22.2.1.3, TS 24.281 §20.2.1.3): subscribe to this user's functional alias
     * status: the info body names the user with `functional-alias-status-determination`, Expires 4294967295 for the
     * state and its changes.
     */
    private suspend fun subscribeAliases(channel: AliasChannel) {
        val service = channel.service
        val request = serviceRequest(service, "SUBSCRIBE", "presence", listOf(aliasInfo(service, ownId(service), "functional-alias-status-determination")))
        request.headers.add("Contact", contactFor(service)).add("Accept", FunctionalAliasPidf.CONTENT_TYPE).add("Expires", "4294967295")
        channel.request = request
        val response = ua.send(request)
        if (!response.isSuccess) {
            channel.request = null
            channel.state.update { it.copy(subscribed = false) }
            throw IllegalStateException("${service.name} functional alias status: ${response.status} ${response.reason}")
        }
        channel.dialog = Dialog.fromResponse(ua.lastSent[request.callId] ?: request, response)
        channel.state.update { it.copy(subscribed = true) }
        event("${service.name} functional alias status subscribed")
    }

    private suspend fun refreshAliasSubscriptions() {
        for (channel in aliasChannels.values) {
            val dialog = channel.dialog ?: continue
            val request = ua.inDialog(dialog, "SUBSCRIBE")
            request.headers.add("Contact", contactFor(channel.service)).add("Event", "presence")
                .add("Accept", FunctionalAliasPidf.CONTENT_TYPE).add("Expires", "4294967295")
            val response = runCatching { ua.send(request, dialog) }.getOrNull()
            event("${channel.service.name} functional alias subscription refreshed: ${response?.status}")
        }
    }

    /**
     * §9A.2.1.2 (TS 24.282 §22.2.1.2, TS 24.281 §20.2.1.2): the functional alias status change of [service]: [action]
     * `activate`, `takeover` or `deactivate` for [aliases]. The answer only says the request was taken; the outcome per
     * alias comes in the status NOTIFY. A take-over carries <take-over> only for the aliases offered as
     * `take-over-possible` (step 6 d); the others are asked for as activations, and taken over once the NOTIFY offers them.
     */
    suspend fun aliasRequest(action: String, aliases: List<String>, service: McService = McService.MCPTT) {
        require(action in setOf("activate", "takeover", "deactivate") && aliases.isNotEmpty()) { "usage: activate|takeover|deactivate <alias>..." }
        val channel = aliasChannels[service] ?: throw IllegalStateException("${service.name} not provisioned")
        if (action == "takeover" && profile?.allows("allow-takeover-functional-alias-other-user") == false && service == McService.MCPTT)
            throw IllegalStateException("the user profile does not allow taking over a functional alias")
        if (channel.dialog == null) subscribeAliases(channel) // §9A.2.1.1
        if (action != "takeover") {
            aliases.forEach { channel.takeOverWanted.remove(it) }
            publishAliases(channel, action, aliases, false)
            return
        }
        val (offered, others) = FunctionalAliasRules.splitTakeOver(aliases, channel.state.value.takeOverPossible)
        if (offered.isNotEmpty()) publishAliases(channel, action, offered, true)
        if (others.isNotEmpty()) { channel.takeOverWanted += others; publishAliases(channel, action, others, false) }
    }

    /** The alias presented on this user's outgoing requests of [service] (§11.1.1.2.1.1 step 14c), or none. */
    fun useAlias(alias: String?, service: McService = McService.MCPTT) {
        val channel = aliasChannels[service] ?: throw IllegalStateException("${service.name} not provisioned")
        if (alias != null && alias !in channel.state.value.active) throw IllegalStateException("$alias is not active for this user")
        channel.state.update { it.copy(inUse = alias) }
    }

    /**
     * §9A.2.1.2 steps 4 to 6: the PUBLISH of one status change: Expires 4294967295 to activate, 0 to deactivate, the
     * per-user PIDF with a new <p-id-fa> and <take-over> when [takeOver].
     */
    private suspend fun publishAliases(channel: AliasChannel, action: String, aliases: List<String>, takeOver: Boolean) {
        val service = channel.service
        val user = ownId(service)
        val publishId = UUID.randomUUID().toString()
        channel.publish = FunctionalAliasRules.Change(action, aliases, publishId)
        val parts = listOf(aliasInfo(service, user),
            Part(FunctionalAliasPidf.CONTENT_TYPE, FunctionalAliasPidf.request(user, config.clientId, aliases, takeOver, publishId, service).toByteArray()))
        val request = serviceRequest(service, "PUBLISH", "presence", parts)
            .also { it.headers.add("Expires", if (action == "deactivate") "0" else "4294967295") }
        channel.state.update { it.copy(lastAction = action, lastStatus = null, lastWarning = null, lastOutcomes = emptyMap()) }
        event("${service.name} functional alias $action $aliases${if (takeOver) " (take-over)" else ""}")
        val response = ua.send(request)
        val warning = response.headers["Warning"]
        channel.state.update { if (channel.publish?.publishId == publishId) it.copy(lastStatus = response.status, lastWarning = warning) else it }
        if (!response.isSuccess) {
            aliases.forEach { channel.takeOverWanted.remove(it) }
            throw IllegalStateException("${service.name} functional alias $action: ${response.status} ${response.reason}${warning?.let { " ($it)" } ?: ""}")
        }
    }

    /**
     * §9A.2.1.3 and §9A.3.1.2 (TS 24.282 §22.2.1.3, TS 24.281 §20.2.1.3): the NOTIFY of this user's functional alias
     * status. It replaces the aliases known active (`activated`, or `deactivating` while still active) and those that may
     * be taken over; the <p-id-fa> of the last status change gives its outcome per alias, and a take-over the user asked
     * for follows once it is offered.
     */
    private fun aliasNotify(channel: AliasChannel, request: SipRequest) {
        val service = channel.service
        val body = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == FunctionalAliasPidf.CONTENT_TYPE }?.text ?: return
        val status = runCatching { FunctionalAliasPidf.parseStatus(body, service) }.getOrNull() ?: return
        // Computed once (its take-overs leave the wanted set), then set: NOTIFYs of one subscription arrive in order.
        val result = FunctionalAliasRules.notified(channel.state.value, status, channel.publish, channel.takeOverWanted)
        channel.state.value = result.state
        event("${service.name} functional alias status: active ${result.state.active}, take-over possible ${result.state.takeOverPossible}")
        if (result.takeOver.isNotEmpty()) scope.launch {
            runCatching { publishAliases(channel, "takeover", result.takeOver, true) }.onFailure { event("functional alias take-over: ${it.message}") }
        }
    }

    /** Change the groups of [service] (full state, TS 24.379 §9.2.1.2). */
    suspend fun setAffiliations(service: McService, groups: List<String>) = affiliate(service, groups)

    private fun updateService(service: McService, change: (ServiceState) -> ServiceState) = _status.update {
        when (service) {
            McService.MCPTT -> it.copy(mcptt = change(it.mcptt))
            McService.MCDATA -> it.copy(mcdata = change(it.mcdata))
            McService.MCVIDEO -> it.copy(mcvideo = change(it.mcvideo))
        }
    }

    // Refreshes.

    private suspend fun housekeeping() {
        val started = clock()
        var nextRegister = started + config.sip.registerExpires * 2 / 3
        var nextSubscription = started + 3000 // the MCPTT server grants 3600 s (contract §5.1)
        while (scope.isActive) {
            delay(5_000)
            val now = clock()
            val t = tokens
            if (t != null && t.expiresAt - now < renewalLead()) {
                renewToken()
                setPhase("ready")
            }
            if (now >= nextRegister) {
                val granted = register(config.sip.registerExpires)
                nextRegister = now + granted * 2 / 3
                setPhase("ready")
            }
            if (now >= nextSubscription) {
                refreshSubscription(McService.MCPTT)
                if (config.mcvideo != null) refreshSubscription(McService.MCVIDEO)
                refreshAliasSubscriptions()
                nextSubscription = now + 3000
            }
        }
    }

    // Incoming requests.

    private suspend fun receive(message: SipMessage) = ua.receive(message)

    init {
        ua.onRequest = { tx -> dispatch(tx) }
    }

    private suspend fun dispatch(tx: ServerTransaction) {
        val request = tx.request
        val aliasChannel = aliasChannels.values.firstOrNull { it.request?.callId == request.callId }
        if (request.method == "NOTIFY" && request.headers["Event"]?.substringBefore(';')?.trim() == "presence" && aliasChannel != null) {
            tx.respond(200)
            if (request.body.isNotEmpty()) aliasNotify(aliasChannel, request)
            if ((request.headers["Subscription-State"] ?: "").startsWith("terminated")) {
                aliasChannel.dialog = null; aliasChannel.request = null
                aliasChannel.state.update { it.copy(subscribed = false) }
            }
            return
        }
        if (request.method == "NOTIFY" && request.headers["Event"]?.substringBefore(';')?.trim() == "presence") {
            val subscription = subscriptions.values.firstOrNull { it.request.callId == request.callId }
            if (subscription == null) { tx.respond(481); return }
            tx.respond(200)
            if (request.body.isNotEmpty()) {
                val body = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == Affiliation.CONTENT_TYPE }?.text
                if (body != null) {
                    subscription.affiliations = Affiliation.parse(body, config.clientId)
                    updateService(subscription.service) { it.copy(affiliations = subscription.affiliations) }
                    event("${subscription.service.name} affiliation status ${subscription.affiliations}")
                }
            }
            val state = request.headers["Subscription-State"] ?: ""
            if (state.startsWith("terminated")) { event("${subscription.service.name} subscription terminated: $state"); subscriptions.remove(subscription.service) }
            subscription.firstNotify.complete(Unit)
            subscription.changed.complete(Unit)
            return
        }
        for (handler in handlers) if (handler.handle(tx)) return
        when (request.method) {
            "OPTIONS" -> tx.respond(200)
            "INVITE", "MESSAGE" -> tx.respond(488)
            else -> tx.respond(405)
        }
    }
}

/** A bounded log of client events and SIP messages, for the status view and the debug endpoint. */
class EventLog(private val capacity: Int = 500) {
    private val events = ArrayDeque<String>()
    var tracer: ((String, SipMessage) -> Unit)? = null

    @Synchronized fun add(text: String) {
        events.addLast("${java.time.Instant.now()} $text")
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized fun last(n: Int): List<String> = events.toList().takeLast(n)

    @Synchronized fun clear() = events.clear()

    fun trace(direction: String, message: SipMessage) { tracer?.invoke(direction, message) }
}
