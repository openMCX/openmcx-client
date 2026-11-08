//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.openmcx.client.sip.ImsAkaConfig
import org.openmcx.client.sip.ImsAkaPlatform
import org.openmcx.client.sip.ImsAkaRegistrar
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.PocSettings
import org.openmcx.client.body.RegInfo
import org.openmcx.client.body.SimpleFilter
import org.openmcx.client.documents.DocumentClient
import org.openmcx.client.documents.DocumentSubscriptions
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.documents.GroupDocument
import org.openmcx.client.documents.ServiceConfig
import org.openmcx.client.documents.UserProfile
import org.openmcx.client.http.HttpClient
import org.openmcx.client.idms.IdmsClient
import org.openmcx.client.idms.IdmsRefused
import org.openmcx.client.security.UserTransportKeys
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
import java.math.BigInteger
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

/**
 * The MC service a request belongs to: by its ICSI (P-Preferred-Service, P-Asserted-Service) or the MC service's media
 * feature tags in its Accept-Contact (TS 24.379, TS 24.281 and TS 24.282 clause 6.2 and the request procedures). Null
 * for a request of no single MC service (REGISTER) and for requests inside a dialog, which carry neither.
 */
fun mcServiceOf(request: SipRequest): McService? {
    val icsis = listOfNotNull(request.headers["P-Preferred-Service"], request.headers["P-Asserted-Service"]).map { it.trim() }
    val accept = request.headers.all("Accept-Contact").joinToString(";")
    val tags = Regex("""g\.3gpp\.(mcptt|mcvideo|mcdata)(?![\w-])""").findAll(accept).map { it.groupValues[1] }.toSet()
    return when {
        Icsi.MCPTT in icsis || accept.contains(Icsi.encoded(Icsi.MCPTT)) || "mcptt" in tags -> McService.MCPTT
        Icsi.MCVIDEO in icsis || accept.contains(Icsi.encoded(Icsi.MCVIDEO)) || "mcvideo" in tags -> McService.MCVIDEO
        icsis.any { it.startsWith(Icsi.MCDATA) } || accept.contains(Icsi.encoded(Icsi.MCDATA)) || "mcdata" in tags -> McService.MCDATA
        else -> null
    }
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

/** [refused]: why the client does not start the service (the system requires what the client does not support). */
data class ServiceState(val authorised: Boolean = false, val affiliations: Map<String, String> = emptyMap(),
    val refused: String? = null)

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
    /** Since when (seconds since the epoch) the client has had no service, and when it tries again; null when up. */
    val offlineSince: Long? = null,
    val nextRetryAt: Long? = null,
    /** Why IdMS signed the user out (it refused the refresh token, and there is no password): null when signed in. */
    val signedOut: String? = null,
    /** TS 33.180 §5.1.1: registered, but the user is not (yet) signed in, so no MC service is authorised. */
    val limitedService: Boolean = false,
    /**
     * TS 24.229 §5.1.1.7: the network deregistered this client ("unregistered", "rejected" or "deactivated" in its reg
     * event), until it registers again ("deactivated": at once) or the user logs on.
     */
    val networkDeregistered: String? = null,
    /** TS 24.229 §5.1.1.3: the client holds its reg event subscription. */
    val regSubscribed: Boolean = false,
    /** TS 24.484 §6.3.13.2.2: the final answer to the configuration management SUBSCRIBE, and its NOTIFYs so far. */
    val configurationSubscription: Int? = null,
    val configurationNotifications: Int = 0,
    /** TS 24.481 §6.3.13.2.1: the final answer to the group document SUBSCRIBE, and its NOTIFYs so far. */
    val groupDocumentSubscription: Int? = null,
    val groupDocumentNotifications: Int = 0,
)

/**
 * How the client reaches the SIP core at its next initial registration, decided then (TS 33.180 clause 6.2.1): SIP
 * digest over the configured connection, IMS AKA through the P-CSCF with the card, or no registration (e.g. mobile
 * data without a usable card, where SIP digest "shall not apply", TS 33.203 clauses N.1 and O.1.1), with the reason.
 */
sealed interface SipAccess {
    data object Digest : SipAccess
    class ImsAka(val platform: ImsAkaPlatform, val config: ImsAkaConfig) : SipAccess
    class Refused(val reason: String) : SipAccess
}

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
    /** HTTP to IdMS, which may trust more CAs than the other servers ([http] by default). */
    idmsHttp: HttpClient = http,
    /**
     * HTTP to the KMS: direct, also with a home HTTP proxy ([http] by default). TS 24.482 annex A.2.1.2
     * sends every request but the IdMS's through the proxy, TS 33.180 §5.3.2 lets a KMS be used without
     * it, and nothing configured tells the UE which applies: a spec conflict, direct by default (through the proxy
     * with security.kms_via_proxy, the test system's reading: [ClientConfig.Security.kmsViaProxy]).
     */
    private val kmsHttp: HttpClient = http,
    /** The SIP core access of each initial registration ([SipAccess]); SIP digest unless the app decides otherwise. */
    private val access: suspend () -> SipAccess = { SipAccess.Digest },
    /**
     * TS 33.180 clause 5.3.2: the pre-provisioned TrK and InK of an MC service user ID (null without), looked up for
     * each KMS exchange; the app's are held in the Android Keystore.
     */
    private val transportKeys: (String) -> UserTransportKeys? = { null },
) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
    private val _status = MutableStateFlow(ClientStatus())
    val status: StateFlow<ClientStatus> = _status

    val ua = UserAgent(scope, UaIdentity(config.sip.publicUserId, config.sip.privateUserId, config.sip.password,
        config.domain, config.clientId))
    private val idms = IdmsClient(idmsHttp, config.idms.issuer, config.idms.clientId, config.idms.redirectUri,
        // TS 33.180 5.3: a protected client also asks for its key management services (MCPTT and MCVideo here).
        scope = IdmsClient.scope(
            listOfNotNull("ptt".takeIf { config.mcptt != null }, "data".takeIf { config.mcdata != null },
                "video".takeIf { config.mcvideo != null }),
            if (config.security == null) emptyList()
            else listOfNotNull("ptt".takeIf { config.mcptt != null }, "video".takeIf { config.mcvideo != null })),
        clock = clock, endpoints = config.idms.endpoints)

    /**
     * The key management client of each service that uses one (TS 33.180 5.3; TS 24.281 4.7 for MCVideo): it keys as
     * the service's MC service ID; its CSK, uploaded with the service authorisation (TS 24.379 clause 7.2.2 step 3,
     * TS 24.281 clause 7.2.5), protects floor or transmission control (TS 33.180 clause 9.4).
     */
    private val keyClients = java.util.concurrent.ConcurrentHashMap<McService, KeyClient>()
    private val csks = java.util.concurrent.ConcurrentHashMap<McService, KeyMessage>()
    private val cskUploads = java.util.concurrent.ConcurrentHashMap<McService, String>()

    fun keysOf(service: McService): KeyClient? = keyClients[service]
    fun cskOf(service: McService): KeyMessage? = csks[service]
    val videoKeys: KeyClient? get() = keyClients[McService.MCVIDEO]
    val videoCsk: KeyMessage? get() = csks[McService.MCVIDEO]

    /**
     * Whether [service] has key management: MCVideo whenever the configuration names a KMS (as before); MCPTT when it
     * also protects media or floor control (TS 24.379 clause 7.2.2 step 3: the CSK with the service authorisation, as
     * the Linux client; this client has no XML signalling protection, the clause's other condition).
     */
    private fun keyed(service: McService): Boolean {
        val security = config.security ?: return false
        return when (service) {
            McService.MCVIDEO -> config.mcvideo != null
            McService.MCPTT -> config.mcptt != null && (security.protectMedia || security.protectFloor)
            McService.MCDATA -> false
        }
    }

    private suspend fun provisionKeys(service: McService, token: String) {
        val security = config.security ?: return
        val userId = ownId(service)
        val account = config.mcptt?.userId ?: userId
        val keys = keyClients[service] ?: KeyClient(kmsHttp, security.kmsUri, userId, account,
            WireProfile(security.messageType, security.algorithm, security.keyParametersIei,
                security.encryptedPayloadIei),
            security.mikeyClockSkewS, { event(it) }, security.kmsId, { transportKeys(userId) }, security.kmsViaProxy)
            .also { keyClients[service] = it }
        keys.provision(token)
        if (csks[service] == null) {
            val (csk, upload) = keys.makeCsk(psi(service))
            csks[service] = csk; cskUploads[service] = upload
            event("${service.name} keys: KMS ${security.kmsUri}, CSK %08x".format(csk.csbId) +
                if (keys.signedExchange) ", signed KMS exchange" else "")
        }
    }

    /** The CSKs go (TS 33.180 9.2.1: a new one with the next service authorisation). */
    private fun dropCsks() { csks.clear(); cskUploads.clear() }

    private val documents = DocumentClient(http, config.cms, config.gms)

    @Volatile var tokens: Tokens? = null
        internal set // set by the module's tests too
    @Volatile var profile: UserProfile? = null
    /** The Resource-Priority values of the service configuration documents, by service and level (TS 24.484 §8.4). */
    @Volatile var serviceResourcePriorities: Map<String, Map<String, String>> = emptyMap()
    /** The signalling protection (confidentiality, integrity) of each service configuration document read. */
    @Volatile var serviceProtection: Map<String, Pair<Boolean, Boolean>> = emptyMap()
        internal set // set by the module's tests too
    /** The MCPTT service configuration has a floor request queue (TS 24.484 §8.4.2.1 `<floor-control-queue>`). */
    @Volatile var mcpttFloorQueue: Boolean = false
        private set
    /** The GMS group documents fetched at login, by group ID. */
    @Volatile var groupDocuments: Map<String, GroupDocument> = emptyMap()
        private set

    val handlers = mutableListOf<ServiceHandler>()
    private var registration: SipRequest? = null
    /** The IMS AKA registration of this life of the client, when it registered with IMS AKA. */
    @Volatile var imsAka: ImsAkaRegistrar? = null
        private set
    /** The duration granted to the last registration, seconds (TS 24.229 §5.1.1.4.1 "the previous registration was for"). */
    @Volatile private var registrationDurationS = 0L

    /**
     * The reg event subscription (TS 24.229 §5.1.1.3): its SUBSCRIBE (by Call-ID: a NOTIFY may beat the 2xx), its dialog
     * once answered, the version of the last document (RFC 3680 §5.2), the duration of the initial subscription, whether
     * a NOTIFY gave the expiry since the last SUBSCRIBE, and until when it is valid (seconds since the epoch).
     */
    private class RegSubscription(val request: SipRequest) {
        @Volatile var dialog: Dialog? = null
        @Volatile var version: BigInteger? = null
        @Volatile var durationS = 0L
        @Volatile var notifyExpiry = false
        @Volatile var validUntil = 0L
        @Volatile var refreshToken = 0L
        @Volatile var refresh: Job? = null
    }
    @Volatile private var regSubscription: RegSubscription? = null
    /**
     * The Call-IDs of subscriptions this client ended (its unsubscribes at log-off, those dropped at a network
     * deregistration): their final NOTIFY is answered 200 (RFC 6665 §4.1.2.3: the subscription lasts until it). Cleared
     * with each new flow, which no NOTIFY of them reaches.
     */
    private val endingSubscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A subscription to configuration management documents (TS 24.484 §6.3.13.2.2) or group documents (TS 24.481
     * §6.3.13.2.1): its initial SUBSCRIBE (known by Call-ID before the 2xx, RFC 6665 §4.1.2.4: a NOTIFY may come
     * first), its dialog once accepted, and when it expires (seconds of [clock], 0: none known). No refresh timer: the
     * next initial registration subscribes anew (as the Linux client).
     */
    private class DocumentSubscription(val request: SipRequest) {
        @Volatile var dialog: Dialog? = null
        @Volatile var expiresAt: Long = 0
    }
    @Volatile private var configSubscription: DocumentSubscription? = null
    private val groupDocSubscriptions = ConcurrentHashMap<String, DocumentSubscription>()
    /** With `documents_on_notify`: completed once the documents of the first configuration NOTIFY have been read. */
    @Volatile private var configurationRead: CompletableDeferred<Unit>? = null
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
        /** The whole set the last PUBLISH listed, while its NOTIFY is awaited (the base of the next change). */
        @Volatile var wanted: Set<String>? = null
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
            var backoff = config.recovery.firstRetryMs
            while (isActive) {
                // Logged off: nothing starts again until the user logs on. Signed out by IdMS: until the user signs in.
                if (loggedOff) logOnRequest.await()
                if (signedOut) signInRequest.await()
                try {
                    run()
                    backoff = config.recovery.firstRetryMs
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (loggedOff) { setPhase("logged-off"); continue }
                    if (signedOut) { setPhase("signed-out"); continue }
                    event("start-up failed: ${e.message}")
                    // No service: the client keeps trying (a mission critical client recovers by itself when the
                    // network or the servers come back); the user sees since when and when the next attempt is.
                    val now = clock()
                    _status.update { it.copy(lastError = e.message, registered = false, offlineSince = it.offlineSince ?: now,
                        nextRetryAt = now + backoff / 1000) }
                    ua.flow?.close(e)
                    imsAka?.close()
                    // TS 24.229 §5.1.1.3: the next initial registration subscribes anew; the subscription of this one
                    // goes with its flow (a start-up that failed after the registration left it in place).
                    regCancel()
                    dropDocumentSubscriptions()
                    // Back off, unless told to reconnect now (a new network, or the user's Retry now).
                    if (!reconnectNow.isCompleted) withTimeoutOrNull(backoff) { reconnectNow.await() }
                    backoff = nextBackoff(backoff)
                    reconnectNow = CompletableDeferred()
                }
            }
        }
    }

    fun stop() {
        ua.flow?.close()
        imsAka?.close()
        scope.cancel()
    }

    @Volatile private var reconnectNow = CompletableDeferred<Unit>()
    @Volatile private var loggedOff = false
    @Volatile private var logOnRequest = CompletableDeferred<Unit>()
    @Volatile private var signedOut = false
    @Volatile private var signInRequest = CompletableDeferred<Unit>()

    /** Try to reach the servers now instead of at the end of the back-off (the user's Retry now). */
    fun retryNow() {
        reconnectNow.complete(Unit)
    }

    /**
     * IdMS refused the refresh token (account suspended, devices revoked, a system that no longer knows the device) and
     * there is no password: the client stops trying and waits for the user to sign in again ([signIn]), instead of
     * opening IdMS's page at every attempt.
     */
    private fun signedOutByIdms(reason: String) {
        signedOut = true
        signInRequest = CompletableDeferred()
        tokens = null
        housekeeping?.cancel()
        _status.update { it.copy(signedOut = reason, registered = false, offlineSince = null, nextRetryAt = null) }
        setPhase("signed-out")
        ua.flow?.close()
    }

    /** Sign in again after [signedOutByIdms]: IdMS's page opens once. */
    fun signIn() {
        if (!signedOut) throw IllegalStateException("not signed out")
        signedOut = false
        _status.update { it.copy(signedOut = null) }
        event("signing in again")
        signInRequest.complete(Unit)
    }

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
            channel.request?.callId?.let(endingSubscriptions::add)
            channel.dialog?.let { unsubscribe(it, channel.service, FunctionalAliasPidf.CONTENT_TYPE) }
            channel.dialog = null; channel.request = null
            channel.state.update { it.copy(subscribed = false) }
        }
        for ((service, subscription) in subscriptions) {
            endingSubscriptions.add(subscription.request.callId)
            subscription.dialog?.let { unsubscribe(it, service, Affiliation.CONTENT_TYPE) }
        }
        subscriptions.clear()
        endDocumentSubscriptions(send = true)
        val answers = mutableMapOf<String, Int>()
        for (service in listOf(McService.MCVIDEO, McService.MCDATA, McService.MCPTT)) {
            val etag = authEtags.remove(service) ?: continue
            val request = ua.newRequest("PUBLISH", psi(service), NameAddr(psi(service)))
            request.headers.add("P-Preferred-Service", icsi(service)).add("Event", "poc-settings")
                .add("Expires", "0").add("SIP-If-Match", etag)
            // RFC 3261 §17.1.2.2: the transaction ends with its final response or at Timer F (64*T1), as every request
            // of UserAgent; no other limit is set.
            val status = runCatching { ua.send(request).status }.getOrNull() ?: 0
            answers[service.name.lowercase()] = status
            updateService(service) { ServiceState() }
            event("${service.name} MC log-off (PUBLISH Expires 0): $status")
        }
        affiliationEtags.clear()
        dropCsks()
        // The SIP de-REGISTER ends likewise with its final response or at Timer F (RFC 3261 §17.1.2.2).
        val deregistered = runCatching { deregister() }.getOrNull()
        event("SIP de-REGISTER (REGISTER Expires 0): $deregistered")
        // TS 24.229 §5.1.1.6.1: with the de-registration the reg event subscription is "considered cancelled".
        regCancel()
        registration = null
        _status.update { it.copy(loggedOff = true, logOff = answers, deregistered = deregistered, registered = false,
            offlineSince = null, nextRetryAt = null) }
        event("logged off")
        ua.flow?.close()
        setPhase("logged-off")
    }

    /** After [logOff]: register again and authorise the services with the tokens kept (new CSKs), as at start-up. */
    fun logOn() {
        if (!loggedOff) throw IllegalStateException("not logged off")
        loggedOff = false
        _status.update { it.copy(loggedOff = false, logOff = emptyMap(), deregistered = null, networkDeregistered = null) }
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

    /** The protection of the SIP flow's own connection (e.g. "TLS 1.3"), null without one or for plain TCP. */
    val sipConnectionSecurity: String? get() = ua.flow?.connection?.security

    /** Run a refresh now instead of at its timer: `register`, `subscription` or `token`. */
    suspend fun refreshNow(what: String) {
        when (what) {
            "register" -> register(registerExpires())
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
            current.refreshToken?.let {
                try {
                    idms.refresh(current)
                } catch (e: IdmsRefused) {
                    event("IdMS refused the refresh token")
                    if (config.idms.username == null || config.idms.password.isNullOrEmpty()) {
                        signedOutByIdms("IdMS refused the refresh token: sign in again")
                        throw e
                    }
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

    /**
     * One life of the client: up to "ready", then held until the flow fails. TS 33.180 §5.1.3.2.1: "During initial SIP
     * registration, the SIP REGISTER message shall not be delayed for lack of an access token" (this client's
     * REGISTER carries none; the services are authorised by PUBLISH, TS 24.379 §7.2.2): the registration comes first,
     * then the user authentication ([authenticateUser]).
     */
    private suspend fun run() {
        setPhase("connecting")
        endingSubscriptions.clear()
        val lifeline: CompletableDeferred<Throwable?>
        when (val chosen = access()) {
            is SipAccess.Refused -> {
                event("no SIP registration: ${chosen.reason}")
                throw IllegalStateException(chosen.reason)
            }
            is SipAccess.ImsAka -> {
                val registrar = ImsAkaRegistrar(scope, ua, chosen.config, chosen.platform, ::contactParams,
                    { dir, m -> trace(dir, m) }, ::event, keepaliveMs)
                imsAka = registrar
                lifeline = registrar.closed
                // TS 24.229 §5.1.1.2.1: the UE asks for 600 000 seconds.
                register(IMS_AKA_REGISTER_EXPIRES)
            }
            SipAccess.Digest -> {
                imsAka = null
                val connection = connector.connect(config.sip.proxy, config.sip.proxyPort)
                val flow = SipFlow(scope, connection, ::receive, { dir, m -> trace(dir, m) }, keepaliveMs = keepaliveMs)
                ua.flow = flow
                flow.start()
                event("flow ${connection.localHost}:${connection.localPort} → ${config.sip.proxy}:${config.sip.proxyPort} ${connection.transport}")
                lifeline = flow.closed
                register(config.sip.registerExpires)
            }
        }
        _status.update { it.copy(registered = true, networkDeregistered = null) }
        // TS 24.229 §5.1.1.3: subscribe "upon receipt of a 2xx response to the initial registration" (and on a new
        // flow, whose registration binds a new flow, the subscription of the old one being gone); not awaited.
        scope.launch { regSubscribe() }
        authenticateUser(lifeline)
        if (documentsOnNotify()) configureFromNotify(lifeline)
        config.mcptt?.let { bringUp(McService.MCPTT, it.groups.ifEmpty { profile?.groups ?: emptyList() }) }
        config.mcdata?.let { bringUp(McService.MCDATA, it.groups) }
        config.mcvideo?.let { bringUp(McService.MCVIDEO, it.groups) }
        setPhase("ready")
        _status.update { it.copy(lastError = null, offlineSince = null, nextRetryAt = null) }
        housekeeping?.cancel()
        housekeeping = scope.launch { housekeeping() }
        val cause = lifeline.await()
        housekeeping?.cancel()
        _status.update { it.copy(registered = false) }
        setPhase("flow-lost")
        regCancel()
        dropDocumentSubscriptions()
        subscriptions.clear()
        handlers.forEach { runCatching { it.flowLost() } }
        throw IllegalStateException("flow closed: ${cause?.message ?: "locally"}")
    }

    /** When the registration is next refreshed (TS 24.229 §5.1.1.4.1, set by [register]), seconds since the epoch. */
    @Volatile private var nextRegisterAt = 0L

    /**
     * TS 33.180 §5.1.3.1 step A once registered, with the tokens kept or by refresh or password ([login]). Until it
     * succeeds the client is in the "limited service" state of §5.1.1: registered, no MC service authorised. A failed
     * attempt is made again after the same growing delay as a failed start-up ([start]), or at once on the user's
     * Retry now; the registration is kept and refreshed meanwhile. A lost flow, a log-off or IdMS's sign-out ends the
     * attempts ([start] takes over).
     */
    private suspend fun authenticateUser(lifeline: CompletableDeferred<Throwable?>) {
        var backoff = config.recovery.firstRetryMs
        while (true) {
            val refresher = scope.launch { keepRegistered() }
            try {
                when {
                    tokens == null -> login()
                    tokens!!.expiresAt - clock() < config.idms.tokenRefreshBeforeS -> refreshTokens()
                }
                _status.update { it.copy(limitedService = false) }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loggedOff || signedOut || lifeline.isCompleted) throw e
                event("limited service: sign-in failed: ${e.message}")
                _status.update { it.copy(limitedService = true, lastError = e.message, nextRetryAt = clock() + backoff / 1000) }
                setPhase("limited-service")
                withTimeoutOrNull(backoff) { select<Unit> { reconnectNow.onAwait {}; lifeline.onAwait {} } }
                if (lifeline.isCompleted) throw IllegalStateException("flow closed in the limited service state", e)
                backoff = nextBackoff(backoff)
                if (reconnectNow.isCompleted) reconnectNow = CompletableDeferred()
                _status.update { it.copy(nextRetryAt = null) }
            } finally {
                refresher.cancel()
            }
        }
    }

    /**
     * RFC 3261 §10.2.4: the registration refreshed at [nextRegisterAt] while the user authentication runs (polled every
     * 5 s, as [housekeeping] does); a failed refresh closes the flow, so that [start] connects again.
     */
    private suspend fun keepRegistered() {
        while (scope.isActive) {
            delay(5_000)
            if (clock() < nextRegisterAt) continue
            try {
                register(registerExpires())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                event("registration refresh failed: ${e.message}")
                ua.flow?.close(e)
                imsAka?.close()
                return
            }
        }
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
        // With documents_on_notify the MCPTT documents are read when their NOTIFY comes (TS 24.484 §6.3.13.2.2,
        // TS 24.481 §6.3.13.2.1), not now: the user profile, the MCPTT service configuration and the group documents.
        val onNotify = documentsOnNotify()
        if (!onNotify) config.mcptt?.let { readUserProfile(it, t.accessToken) }
        // TS 24.379, TS 24.281 and TS 24.282 §6.2.8.1.15: the Resource-Priority values of each service's service
        // configuration document; without them, emergency and imminent peril requests cannot be made.
        for (service in listOfNotNull("mcptt".takeIf { config.mcptt != null && !onNotify }, "mcvideo".takeIf { config.mcvideo != null },
                "mcdata".takeIf { config.mcdata != null })) readServiceConfiguration(service, t.accessToken)
        val subscribedGroups = if (onNotify && groupDocumentsSubscribed()) config.mcptt?.groups.orEmpty().toSet() else emptySet()
        for (group in ((config.mcptt?.groups ?: emptyList()) + (config.mcdata?.groups ?: emptyList())).distinct() - subscribedGroups)
            readGroupDocument(group, t.accessToken)
    }

    /** TS 24.484 §8.3.1A: the user profile; the functional aliases from it unless the provisioning names them. */
    private suspend fun readUserProfile(mcptt: ClientConfig.Mcptt, token: String) {
        runCatching { documents.userProfile(mcptt.userId, mcptt.profileIndex, token) }
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

    /** A service configuration document: the floor queue (MCPTT), signalling protection, Resource-Priority values. */
    private suspend fun readServiceConfiguration(service: String, token: String) {
        runCatching { documents.serviceConfiguration(service, token) }
            .mapCatching { text ->
                if (service == "mcptt") mcpttFloorQueue = ServiceConfig.floorQueue(text)
                serviceProtection = serviceProtection + (service to ServiceConfig.signallingProtection(text))
                ServiceConfig.resourcePriorities(text)
            }
            .onSuccess { serviceResourcePriorities = serviceResourcePriorities + (service to it)
                event("service configuration $service: $it" + if (service == "mcptt") ", floor queue $mcpttFloorQueue" else "") }
            .onFailure { event("service configuration $service: ${it.message}") }
    }

    private suspend fun readGroupDocument(group: String, token: String) {
        runCatching { documents.group(group, token) }
            .onSuccess {
                groupDocuments = groupDocuments + (group to it)
                event("group $group: services ${it.services.map { s -> s.substringAfterLast('.') }}")
            }
            .onFailure { event("group $group: ${it.message}") }
    }

    /** Take new tokens, once they name this client and user. */
    private fun adopt(t: Tokens) {
        if (t.clientId != null && t.clientId != config.clientId) throw IllegalStateException("token client_id ${t.clientId} is not ${config.clientId}")
        config.mcptt?.let { if (t.mcpttId != null && t.mcpttId != it.userId) throw IllegalStateException("token MCPTT ID ${t.mcpttId}") }
        config.mcdata?.let { if (t.mcdataId != null && t.mcdataId != it.userId) throw IllegalStateException("token MCData ID ${t.mcdataId}") }
        config.mcvideo?.let { if (t.mcvideoId != null && t.mcvideoId != it.userId) throw IllegalStateException("token MCVideo ID ${t.mcvideoId}") }
        tokens = t
        tokenLifetime = (t.expiresAt - clock()).coerceAtLeast(0)
        _status.update { it.copy(tokenExpiresAt = t.expiresAt) }
    }

    /** The lifetime of the current access token when it was issued, seconds. */
    private var tokenLifetime = 0L

    /**
     * Renew `idms.token_renewal_lead_s` before expiry (5 minutes), or halfway for a token that lives less than twice
     * that (TS 33.180 annex B.5.1 leaves the time to the client; default pending maintainer choice (no spec value)).
     */
    private fun renewalLead(): Long = minOf(config.idms.tokenRenewalLeadS, tokenLifetime / 2)

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

    private fun registerContact(): String = "<${ua.contactUri()}>" + contactParams()

    /** The expiry a REGISTER asks for: TS 24.229 §5.1.1.2.1's 600 000 s with IMS AKA, else the configured one. */
    private fun registerExpires(): Int = if (imsAka != null) IMS_AKA_REGISTER_EXPIRES else config.sip.registerExpires

    /** The Contact's header field parameters: +sip.instance, reg-id and the MC feature tags. */
    private fun contactParams(): String =
        ";+sip.instance=\"<${config.clientId}>\";reg-id=1;+g.3gpp.mcptt;+g.3gpp.mcdata.sds;" +
            (if (config.mcdata != null) "+g.3gpp.mcdata.fd;" else "") +
            (if (config.mcvideo != null) "+g.3gpp.mcvideo;" else "") +
            "+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)},${Icsi.encoded(Icsi.MCDATA)},${Icsi.encoded(Icsi.MCDATA_SDS)}" +
            (if (config.mcdata != null) ",${Icsi.encoded(Icsi.MCDATA_FD)}" else "") +
            (if (config.mcvideo != null) ",${Icsi.encoded(Icsi.MCVIDEO)}" else "") + "\""

    private suspend fun register(expires: Int): Int {
        setPhase("registering")
        imsAka?.let { aka ->
            val granted = aka.register(expires)
            registrationDurationS = granted.toLong()
            nextRegisterAt = clock() + RegEventRules.refreshDelayS(granted.toLong(), granted.toLong())
            event("registered with IMS AKA for $granted s, P-Associated-URI ${aka.associatedUris.joinToString(", ")}")
            return granted
        }
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
        // TS 24.229 §5.1.1.4.1: reregister "600 seconds before the expiration time if the previous registration was for
        // greater than 1200 seconds, or when half of the time has expired if the previous registration was for 1200
        // seconds or less" (keepRegistered and housekeeping poll the time every 5 s).
        registrationDurationS = granted.toLong()
        nextRegisterAt = clock() + RegEventRules.refreshDelayS(granted.toLong(), granted.toLong())
        event("registered for $granted s")
        return granted
    }

    /** RFC 3261 §10.2.2: remove this registration (its Contact, Expires 0); the final status. */
    private suspend fun deregister(): Int? {
        imsAka?.let { return it.deregister() }
        val previous = registration ?: return null
        val request = ua.newRequest("REGISTER", "sip:${config.domain}", NameAddr(config.sip.publicUserId), from = previous.from,
            callId = previous.callId, cseq = (ua.lastSent[previous.callId] ?: previous).cseq.number + 1)
        request.headers.add("Contact", registerContact()).add("Expires", "0")
        return ua.send(request).status
    }

    // The reg event package (TS 24.229 §5.1.1.3, §5.1.1.5A, §5.1.1.7; RFC 3680, RFC 6665).

    /** The event package of a request: its Event header field without parameters. */
    private fun eventPackage(request: SipRequest) = request.headers["Event"]?.substringBefore(';')?.trim()

    /**
     * TS 24.229 §5.1.1.3: subscribe to the reg event package for the default public user identity (this client has
     * one): Request-URI, From and To that identity, Event "reg", Expires 600 000 (items a) to e)), and Accept
     * application/reginfo+xml (RFC 3680 §4.3). A refused SUBSCRIBE leaves the client without the subscription until its
     * next initial registration (no retry is given).
     */
    private suspend fun regSubscribe() {
        if (regSubscription != null || loggedOff) return
        val aor = config.sip.publicUserId
        val request = runCatching { ua.newRequest("SUBSCRIBE", aor, NameAddr(aor)) }.getOrNull() ?: return
        request.headers.add("Contact", "<${ua.contactUri()}>").add("Event", RegEventRules.EVENT)
            .add("Accept", RegInfo.CONTENT_TYPE).add("Expires", RegEventRules.SUBSCRIPTION_EXPIRES_S.toString())
        val subscription = RegSubscription(request)
        regSubscription = subscription
        event("reg event subscription: SUBSCRIBE Expires ${RegEventRules.SUBSCRIPTION_EXPIRES_S}")
        val response = runCatching { ua.send(request) }.getOrElse {
            if (regSubscription === subscription) regSubscription = null
            event("reg event subscription: ${it.message}")
            return
        }
        if (regSubscription !== subscription) return
        if (!response.isSuccess) {
            regSubscription = null
            event("reg event subscription refused: ${response.status} ${response.reason}")
            return
        }
        subscription.dialog = Dialog.fromResponse(ua.lastSent[request.callId] ?: request, response)
        _status.update { it.copy(regSubscribed = true) }
        regGranted(subscription, response)
    }

    /**
     * TS 24.229 §5.1.1.3 item 2): the expiration time "as indicated in the "expires" header field parameter of the
     * Subscription-State header field, if present, of the NOTIFY request. Otherwise the expiration time is retrieved
     * from the Expires header field of the 2xx response": the 2xx counts only when no NOTIFY gave the time since the
     * SUBSCRIBE went out (the NOTIFY may come first).
     */
    private fun regGranted(subscription: RegSubscription, response: SipResponse) {
        val expires = response.headers["Expires"]?.trim()?.toLongOrNull() ?: return
        event("reg event subscription granted for $expires s")
        if (expires > 0 && !subscription.notifyExpiry) regScheduleRefresh(subscription, expires)
    }

    /**
     * TS 24.229 §5.1.1.3: the refresh, replacing any refresh scheduled before: "600 seconds before the expiration time
     * if the initial subscription was for greater than 1200 seconds, or when half of the time has expired if the initial
     * subscription was for 1200 seconds or less" ([RegEventRules.refreshDelayS]); the initial subscription's duration is
     * its first known expiry.
     */
    private fun regScheduleRefresh(subscription: RegSubscription, expires: Long) {
        if (subscription.durationS == 0L) subscription.durationS = expires
        subscription.validUntil = clock() + expires
        val token = ++subscription.refreshToken
        val delayS = RegEventRules.refreshDelayS(subscription.durationS, expires)
        subscription.refresh?.cancel()
        subscription.refresh = scope.launch {
            delay(delayS * 1000)
            if (token == subscription.refreshToken && regSubscription === subscription) regRefresh(subscription)
        }
    }

    /**
     * TS 24.229 §5.1.1.3: the refresh in the subscription's dialog. "If a SUBSCRIBE request to refresh a subscription
     * fails with a non-481 response, the UE shall still consider the original subscription valid for the duration of the
     * most recently known "Expires" value according to RFC 6665. Otherwise, the UE shall consider the subscription
     * invalid and start a new initial subscription"; no answer (RFC 3261 Timer F) is not a 481 either. Nothing once
     * logged off.
     */
    private suspend fun regRefresh(subscription: RegSubscription) {
        if (regSubscription !== subscription || loggedOff) return
        val dialog = subscription.dialog ?: return
        subscription.notifyExpiry = false
        val request = ua.inDialog(dialog, "SUBSCRIBE")
        request.headers.add("Contact", "<${ua.contactUri()}>").add("Event", RegEventRules.EVENT)
            .add("Accept", RegInfo.CONTENT_TYPE).add("Expires", RegEventRules.SUBSCRIPTION_EXPIRES_S.toString())
        val response = runCatching { ua.send(request, dialog) }.getOrNull()
        if (regSubscription !== subscription) return
        if (response != null && response.isSuccess) {
            event("reg event subscription refreshed")
            regGranted(subscription, response)
            return
        }
        event("reg event subscription refresh failed: ${response?.status ?: "no answer"}")
        if (response?.status == 481) {
            regSubscription = null
            _status.update { it.copy(regSubscribed = false) }
            regSubscribe()
            return
        }
        // Valid until its last known expiry; then a new initial subscription.
        val token = ++subscription.refreshToken
        val leftS = (subscription.validUntil - clock()).coerceAtLeast(0)
        subscription.refresh = scope.launch {
            delay(leftS * 1000)
            if (token != subscription.refreshToken || regSubscription !== subscription) return@launch
            regSubscription = null
            _status.update { it.copy(regSubscribed = false) }
            regSubscribe()
        }
    }

    /**
     * TS 24.229 §5.1.1.6.1: "If all public user identities are deregistered and all security association or TLS session
     * is removed, then the UE shall consider subscription to the reg event package cancelled": with the client's own
     * de-registration (log-off), the network's, or a lost flow. A final NOTIFY on its dialog is answered 200
     * ([endingSubscriptions]).
     */
    private fun regCancel() {
        val subscription = regSubscription ?: return
        regSubscription = null
        subscription.refreshToken++
        subscription.refresh?.cancel()
        endingSubscriptions.add(subscription.request.callId)
        _status.update { it.copy(regSubscribed = false) }
    }

    /**
     * A NOTIFY of the reg event (RFC 6665 §4.1.3, RFC 3680 §4.8 and §5.2): 200, or 400 for a document that is not a
     * reginfo document, or 481 for no subscription of this client. Then, after the response: the expiry of
     * Subscription-State, the document in version order (a version gap asks for a refresh, RFC 3680 §5.2) and what it
     * asks of this client ([regDocument]), and the end of the subscription.
     */
    private suspend fun regNotify(tx: ServerTransaction) {
        val request = tx.request
        val state = request.headers["Subscription-State"]?.trim() ?: ""
        val subscription = regSubscription?.takeIf { it.request.callId == request.callId }
        if (subscription == null) {
            val ended = request.callId in endingSubscriptions
            tx.respond(if (ended) 200 else 481)
            if (ended && state.startsWith("terminated")) endingSubscriptions.remove(request.callId)
            return
        }
        val text = if (request.body.isEmpty()) null else
            Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == RegInfo.CONTENT_TYPE }?.text
        val info = text?.let { body ->
            runCatching { RegInfo.parse(body) }.getOrElse {
                event("reg event NOTIFY refused: ${it.message}")
                tx.respond(400)
                return
            }
        }
        val expires = RegEventRules.stateExpires(state)
        if (expires != null) subscription.notifyExpiry = true
        tx.respond(200)
        if (info != null) {
            if (!RegEventRules.versionNew(subscription.version, info.version)) {
                event("reg event document discarded: version ${info.version}, local ${subscription.version}")
            } else {
                val gap = RegEventRules.versionGap(subscription.version, info.version)
                subscription.version = info.version
                if (gap) {
                    event("reg event version gap at ${info.version}: refresh")
                    scope.launch { regRefresh(subscription) }
                }
                regDocument(info)
            }
        }
        if (regSubscription !== subscription) return
        if (state.startsWith("terminated")) {
            // RFC 6665 §4.1.3: the subscriber "MUST consider the subscription terminated".
            regSubscription = null
            subscription.refreshToken++
            subscription.refresh?.cancel()
            _status.update { it.copy(regSubscribed = false) }
            event("reg event subscription ended: $state")
            return
        }
        if (expires != null) regScheduleRefresh(subscription, expires)
    }

    /**
     * TS 24.229 §5.1.1.5A and §5.1.1.7: what a reginfo document asks of this client's own contact
     * ([RegEventRules.action]). A shortened registration is renewed "at the appropriate time ... by initiating a
     * reregistration as described in subclause 5.1.1.4": at the §5.1.1.4.1 time for the adjusted expiration time, from
     * the duration of the registration before (the "previous registration"). Nothing during the client's own log-off,
     * whose de-REGISTER ends the registration.
     */
    private suspend fun regDocument(info: RegInfo) {
        if (loggedOff) return
        val own = runCatching { RegEventRules.contactAddress(ua.contactUri()) }.getOrNull() ?: return
        when (val action = RegEventRules.action(info, config.sip.publicUserId, own) ?: return) {
            is RegEventRules.Action.Shortened -> {
                val delayS = RegEventRules.refreshDelayS(registrationDurationS, action.expires)
                nextRegisterAt = clock() + delayS
                event("registration shortened to ${action.expires} s: reregistration in $delayS s")
            }
            is RegEventRules.Action.Deregistered -> networkDeregistration(action.event)
        }
    }

    /**
     * TS 24.229 §5.1.1.7: the network deregistered this client's public user identity: "the UE shall remove all
     * registration details relating to the respective public user identity" (the next REGISTER is an initial one, with
     * a new Call-ID), and the reg event subscription ends with it. "In case of a "rejected" event attribute, the UE
     * shall release all dialogs related to those public user identities": the calls end ([ServiceHandler.logOff]) while
     * the flow still carries their requests. The subscriptions and the service state go locally: the MC servers removed
     * the binding with the deregistration (TS 24.229 §5.4.1.7 f)). "The UE shall delete all security associations or
     * TLS sessions towards the P-CSCF ... after the server transaction ... pertaining to the received NOTIFY request
     * terminates": the flow closes, the NOTIFY already answered. "In case of a "deactivated" event attribute, the UE
     * shall start the initial registration procedure as described in subclause 5.1.1.2": at once, on a new flow
     * ([start]). After "rejected" or "unregistered" the client stays off until the user logs on ([logOn]).
     */
    private suspend fun networkDeregistration(reason: String) {
        event("deregistered by the network: $reason")
        housekeeping?.cancel()
        registration = null
        regCancel()
        val staysOff = reason != "deactivated"
        if (staysOff) {
            loggedOff = true
            logOnRequest = CompletableDeferred()
        }
        if (reason == "rejected") handlers.forEach { runCatching { it.logOff() } }
        dropServiceState()
        _status.update { it.copy(registered = false, networkDeregistered = reason, loggedOff = staysOff || it.loggedOff,
            offlineSince = null, nextRetryAt = null) }
        if (!staysOff) reconnectNow.complete(Unit)
        ua.flow?.close(IllegalStateException("deregistered by the network ($reason)"))
    }

    /**
     * The subscriptions and service state the MC servers dropped with a network deregistration: ended locally (their
     * final NOTIFYs answered 200), the publications forgotten (a new service authorisation follows a new
     * registration), the MCVideo CSK with them (TS 33.180 §9: a new CSK with the new authorisation).
     */
    private fun dropServiceState() {
        for (channel in aliasChannels.values) {
            channel.request?.callId?.let(endingSubscriptions::add)
            channel.dialog = null; channel.request = null
            channel.state.update { it.copy(subscribed = false) }
        }
        for (subscription in subscriptions.values) endingSubscriptions.add(subscription.request.callId)
        subscriptions.clear()
        dropDocumentSubscriptions()
        authEtags.clear(); affiliationEtags.clear()
        dropCsks()
        for (service in McService.entries) updateService(service) { ServiceState() }
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

    /**
     * TS 24.379 §6.6.2.3.1 and §6.6.3.3.1 (TS 24.281 the same, TS 24.282 §6.5.2.3.1 and §6.5.3.3.1): this client has no
     * XML signalling protection, so a service whose service configuration document enables it (or that could not be
     * read, so that whether it does is not known) is not started: its service authorisation would go out unprotected.
     * Null when the service may start.
     */
    /**
     * TS 33.180 clause 5.3.2: through the home HTTP proxy "the TrK shall be used" (`kms_via_proxy`): a keyed service whose
     * MC service user ID has no TrK cannot get its keys, so it is not started (the others are), with the reason.
     */
    private fun transportKeyRefusal(service: McService): String? {
        val security = config.security ?: return null
        if (!security.kmsViaProxy || !keyed(service) || transportKeys(ownId(service))?.trk != null) return null
        return "${service.name} not started: its key management server is reached through the HTTP proxy, which needs a " +
            "transport key this app does not hold for ${ownId(service)}"
    }

    /** Why [service] is not started (protection the app lacks, or no TrK for a KMS through the proxy); null if it may. */
    private fun notStarted(service: McService): String? = protectionRefusal(service) ?: transportKeyRefusal(service)

    private fun protectionRefusal(service: McService): String? {
        val protection = serviceProtection[service.name.lowercase()]
            ?: return "${service.name} not started: its service configuration could not be read"
        return if (protection.first || protection.second)
            "${service.name} not started: the system requires signalling protection, which this app does not support"
        else null
    }

    /** The service authorisation; false (and the reason in the service's state) when the service is not started. */
    private suspend fun authorise(service: McService): Boolean {
        notStarted(service)?.let { reason ->
            updateService(service) { it.copy(authorised = false, refused = reason) }
            event(reason)
            return false
        }
        setPhase("authorising-${service.name.lowercase()}")
        val token = tokens?.accessToken ?: throw IllegalStateException("no access token")
        val answerMode = when (service) {
            McService.MCPTT -> config.mcptt!!.answerMode; McService.MCVIDEO -> config.mcvideo!!.answerMode; McService.MCDATA -> null
        }
        if (keyed(service)) provisionKeys(service, token)
        // TS 24.281 7.2.5: the MCVideo CSK goes to the MCVideo server with the authorisation, in application/mikey.
        // TS 33.180 9.2.1.2 and 9.2.1.3: it is uploaded at the initial connection and stays in use for the session, so a
        // later authorisation (a renewed access token) carries none; the server keeps it with the publication.
        val parts = { listOfNotNull(infoPart(service, token = token), Part(PocSettings.CONTENT_TYPE, PocSettings.encode(config.clientId, answerMode).toByteArray()),
            cskUploads[service]?.let { Part("application/mikey", it.toByteArray()) }) }
        var request = serviceRequest(service, "PUBLISH", "poc-settings", parts()).also { it.headers.add("Expires", "4294967295") }
        authEtags[service]?.let { request.headers.add("SIP-If-Match", it) }
        var response = ua.send(request)
        if (response.status == 412) {
            // The server lost our publication (a restart): authorise afresh, then everything after it again. Its CSK
            // went with it, and an I_MESSAGE is accepted once only (RFC 3830 replay protection): upload a new CSK.
            authEtags.remove(service); affiliationEtags.remove(service)
            if (keyed(service)) { csks.remove(service); cskUploads.remove(service); provisionKeys(service, token) }
            request = serviceRequest(service, "PUBLISH", "poc-settings", parts()).also { it.headers.add("Expires", "4294967295") }
            response = ua.send(request)
        }
        // §7.2.2: not authorised; the start-up is tried again after a growing delay. A 486 (§7.3.3 steps 3a and 3b,
        // warning 164, MCVideo 166, MCData 228) means the user is authorised on another client: the retry succeeds
        // once that one logs off or its authorisation ends. A refused request may still have spent its CSK upload (the
        // server opens it first): the retry uploads a new CSK.
        if (!response.isSuccess && cskUploads[service] != null) csks.remove(service)
        if (!response.isSuccess) throw IllegalStateException("${service.name} service authorisation: ${response.status} ${response.reason}" +
            (response.headers["Warning"]?.let { " ($it)" } ?: ""))
        response.headers["SIP-ETag"]?.let { authEtags[service] = it }
        cskUploads.remove(service) // the server has the CSK
        updateService(service) { it.copy(authorised = true, refused = null) }
        event("${service.name} authorised")
        return true
    }

    // Configuration management and group document subscriptions (TS 24.484 §6.3.13.2.2, TS 24.481 §6.3.13.2.1).

    /**
     * A request of the configuration subscription (its SUBSCRIBE, a NOTIFY or answer in its dialog) while the MCPTT
     * service configuration is not known yet: it passes the protection gate ([protectionRefusal]), which would otherwise
     * stop the subscription that brings that document. TS 24.484 §6.3.13.2.2 d) asks for identity hiding "if identity
     * hiding is required", which the client learns from that document; once it is known and requires protection, the
     * gate refuses as for any MCPTT request (as the Linux client, which sends this SUBSCRIBE before it knows).
     */
    private fun configurationPending(request: SipRequest): Boolean =
        serviceProtection["mcptt"] == null && configSubscription?.request?.callId == request.callId

    /** The MCPTT documents are read when the configuration subscription's NOTIFY comes, not at sign-in. */
    private fun documentsOnNotify() = config.mcptt?.let { it.documentsOnNotify && it.subscribeConfiguration } == true

    /** The group documents of the MCPTT groups come with a group document subscription. */
    private fun groupDocumentsSubscribed() = config.mcptt?.let { it.subscribeGroupDocuments && it.gmsSubscriptionPsi != null } == true

    /**
     * The initial SUBSCRIBE to [uri] for [entries] (TS 24.484 §6.3.13.2.2, TS 24.481 §6.3.13.2.1, both "according
     * to ... IETF RFC 5875"): items b) 1) / a) the resource list, c) the mcptt-info body with the access token, e) the
     * MCPTT ICSI in P-Preferred-Service, f) the MCPTT ICSI in the Contact. Event xcap-diff, Expires of RFC 5875 §4.5.
     * Identity hiding (item d)) needs XML protection, which this client does not have: a service whose service
     * configuration asks for it is not started ([protectionRefusal]).
     */
    private fun documentSubscription(uri: String, entries: List<String>, token: String): DocumentSubscription {
        val request = ua.newRequest("SUBSCRIBE", uri, NameAddr(uri))
        val (type, body) = Multipart.encode(listOf(infoPart(McService.MCPTT, token = token),
            Part(ResourceLists.CONTENT_TYPE, DocumentSubscriptions.resourceList(entries).toByteArray())))
        request.headers.add("P-Preferred-Service", icsi(McService.MCPTT)).add("Event", DocumentSubscriptions.EVENT)
            .add("Accept", DocumentSubscriptions.ACCEPT).add("Contact", contactFor(McService.MCPTT))
            .add("Expires", DocumentSubscriptions.EXPIRES_SECONDS.toString()).add("Content-Type", type)
        return DocumentSubscription(SipRequest(request.method, request.uri, request.headers, body))
    }

    /**
     * Sends [subscription]'s SUBSCRIBE; the final status, the dialog and the expiry (RFC 6665 §4.1.2.1: the 2xx's
     * Expires, else the one asked for). A refused SUBSCRIBE creates no subscription (§4.1.2.1).
     */
    private suspend fun send(subscription: DocumentSubscription, what: String): Int {
        val response = runCatching { ua.send(subscription.request) }.getOrElse { event("$what: ${it.message}"); return 0 }
        if (!response.isSuccess) { event("$what refused: ${response.status} ${response.reason}"); return response.status }
        subscription.dialog = runCatching { Dialog.fromResponse(ua.lastSent[subscription.request.callId] ?: subscription.request, response) }.getOrNull()
        val granted = response.headers["Expires"]?.trim()?.toLongOrNull() ?: DocumentSubscriptions.EXPIRES_SECONDS
        subscription.expiresAt = if (granted > 0) clock() + granted else 0
        event("$what: ${response.status}, $granted s")
        return response.status
    }

    /**
     * TS 24.484 §6.3.13.2.2: one SUBSCRIBE for the user's UE configuration, user profile and MCPTT service configuration,
     * to the CMS subscription proxy PSI (b) 2), configured, else the MCPTT server's PSI). True when accepted.
     */
    private suspend fun subscribeConfiguration(token: String): Boolean {
        val mcptt = config.mcptt ?: return false
        val entries = DocumentSubscriptions.configurationEntries(mcptt.userId, mcptt.ueId ?: config.clientId, mcptt.profileIndex)
        val subscription = documentSubscription(mcptt.cmsSubscriptionPsi ?: mcptt.psi, entries, token)
        configSubscription = subscription
        val status = send(subscription, "configuration subscription")
        _status.update { it.copy(configurationSubscription = status) }
        val accepted = status in 200..299
        if (!accepted && configSubscription === subscription) configSubscription = null
        return accepted
    }

    /** TS 24.481 §6.3.13.2.1: one SUBSCRIBE for the group documents of the MCPTT groups, to the GMS-URI (b)). */
    private suspend fun subscribeGroupDocuments(token: String) {
        val mcptt = config.mcptt ?: return
        val uri = mcptt.gmsSubscriptionPsi ?: run { event("group document subscription: no GMS-URI configured"); return }
        val groups = mcptt.groups.ifEmpty { profile?.groups ?: emptyList() }.distinct()
        if (groups.isEmpty()) return
        val subscription = documentSubscription(uri, DocumentSubscriptions.groupEntries(groups), token)
        groupDocSubscriptions[subscription.request.callId] = subscription
        val status = send(subscription, "group document subscription for $groups")
        _status.update { it.copy(groupDocumentSubscription = status) }
        if (status !in 200..299) groupDocSubscriptions.remove(subscription.request.callId)
    }

    /**
     * With `documents_on_notify`: the configuration subscription, then its first NOTIFY and the documents it names,
     * before the service authorisation (TS 37.579-1 table 5.3.2.5.1-1 NOTE 1: the PUBLISH follows the user profile).
     * A refusal fails the start-up, which is tried again ([start]).
     */
    private suspend fun configureFromNotify(lifeline: CompletableDeferred<Throwable?>) {
        val token = tokens?.accessToken ?: throw IllegalStateException("no access token")
        val read = CompletableDeferred<Unit>()
        configurationRead = read
        try {
            setPhase("configuring")
            if (!subscribeConfiguration(token)) throw IllegalStateException("configuration subscription not accepted")
            select<Unit> { read.onAwait {}; lifeline.onAwait {} }
            if (lifeline.isCompleted && !read.isCompleted) throw IllegalStateException("flow closed while waiting for the configuration")
        } finally {
            configurationRead = null
        }
    }

    /** After the service authorisation: the subscriptions that are not made before it. */
    private suspend fun subscribeDocuments() {
        val mcptt = config.mcptt ?: return
        val token = tokens?.accessToken ?: return
        if (mcptt.subscribeConfiguration && configSubscription == null) subscribeConfiguration(token)
        if (mcptt.subscribeGroupDocuments && groupDocSubscriptions.isEmpty()) subscribeGroupDocuments(token)
    }

    /**
     * A NOTIFY of the configuration subscription (RFC 5875: handled by re-reading the subscribed documents): the UE
     * configuration, the user profile and the MCPTT service configuration, through the home HTTP proxy when one is
     * configured.
     */
    private suspend fun configurationNotified() {
        val mcptt = config.mcptt ?: return
        val token = tokens?.accessToken ?: return
        _status.update { it.copy(configurationNotifications = it.configurationNotifications + 1) }
        runCatching { documents.ueConfiguration(mcptt.userId, mcptt.ueId ?: config.clientId, token) }
            .onSuccess { event("UE configuration read") }.onFailure { event("UE configuration: ${it.message}") }
        readUserProfile(mcptt, token)
        readServiceConfiguration("mcptt", token)
        configurationRead?.complete(Unit)
    }

    /** A NOTIFY of a group document subscription (RFC 5874): each changed group document read again. */
    private suspend fun groupDocumentsNotified(request: SipRequest) {
        val token = tokens?.accessToken ?: return
        _status.update { it.copy(groupDocumentNotifications = it.groupDocumentNotifications + 1) }
        val diff = Multipart.parse(request.contentType, request.body).firstOrNull { it.mediaType == DocumentSubscriptions.ACCEPT }?.text ?: return
        val changed = runCatching { DocumentSubscriptions.parse(diff) }.getOrElse { event("group document NOTIFY: ${it.message}"); return }
        for (document in changed) {
            val group = DocumentSubscriptions.groupOf(document.selector) ?: continue
            if (document.newEtag == null) { groupDocuments = groupDocuments - group; event("group $group: document removed"); continue }
            readGroupDocument(group, token)
        }
    }

    /**
     * The document subscriptions end: at MC log-off each one with a SUBSCRIBE Expires 0 in its dialog (RFC 6665
     * §4.1.2.3; [send] false after the registration is gone), its final NOTIFY answered 200 ([endingSubscriptions]).
     */
    private suspend fun endDocumentSubscriptions(send: Boolean) {
        val all = listOfNotNull(configSubscription) + groupDocSubscriptions.values
        configSubscription = null
        groupDocSubscriptions.clear()
        for (subscription in all) {
            endingSubscriptions.add(subscription.request.callId)
            val dialog = subscription.dialog ?: continue
            if (!send) continue
            val request = ua.inDialog(dialog, "SUBSCRIBE")
            request.headers.add("P-Preferred-Service", icsi(McService.MCPTT)).add("Event", DocumentSubscriptions.EVENT).add("Expires", "0")
            val status = runCatching { ua.send(request).status }.getOrNull()
            event("document subscription ended (SUBSCRIBE Expires 0): $status")
        }
    }

    /** The same without sending: the flow or the registration is gone. */
    private fun dropDocumentSubscriptions() {
        for (subscription in listOfNotNull(configSubscription) + groupDocSubscriptions.values) endingSubscriptions.add(subscription.request.callId)
        configSubscription = null
        groupDocSubscriptions.clear()
    }

    /** A subscription whose expiry passed is gone (RFC 6665 §4.1.2.2); the next initial registration subscribes anew. */
    private fun expireDocumentSubscriptions() {
        val now = clock()
        configSubscription?.let { if (it.expiresAt in 1..now) { configSubscription = null; event("configuration subscription expired") } }
        for ((callId, subscription) in groupDocSubscriptions)
            if (subscription.expiresAt in 1..now) { groupDocSubscriptions.remove(callId); event("group document subscription expired") }
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
        // §9.2.1.2 steps 4 and 5: Expires 0 when no group is wanted.
        val expires = if (groups.isEmpty()) "0" else "4294967295"
        val request = serviceRequest(service, "PUBLISH", "presence", parts).also { it.headers.add("Expires", expires) }
        affiliationEtags[service]?.let { request.headers.add("SIP-If-Match", it) }
        subscription.changed = CompletableDeferred()
        var response = ua.send(request)
        if (response.status == 412) {
            affiliationEtags.remove(service)
            val retry = serviceRequest(service, "PUBLISH", "presence", parts).also { it.headers.add("Expires", expires) }
            response = ua.send(retry)
        }
        if (!response.isSuccess) throw IllegalStateException("${service.name} affiliation: ${response.status} ${response.reason}")
        response.headers["SIP-ETag"]?.let { affiliationEtags[service] = it }
        // Affiliated when a NOTIFY says so for every group (§9.2.1.3); the p-id is not reliable.
        val deadline = System.currentTimeMillis() + 10_000
        while (groups.any { !Affiliation.counts(subscription.affiliations[it]) }) {
            val wait = deadline - System.currentTimeMillis()
            if (wait <= 0 || withTimeoutOrNull(wait) { subscription.changed.await() } == null) break
            subscription.changed = CompletableDeferred()
        }
        val missing = groups.filter { !Affiliation.counts(subscription.affiliations[it]) }
        if (missing.isNotEmpty()) throw IllegalStateException("${service.name} not affiliated to $missing")
        event("${service.name} affiliated to $groups")
    }

    private suspend fun bringUp(service: McService, groups: List<String>) {
        if (!authorise(service)) return
        // Once MCPTT is authorised: the document subscriptions not made before it (as the Linux client, after the
        // service authorisation), next to the affiliation.
        if (service == McService.MCPTT)
            scope.launch { runCatching { subscribeDocuments() }.onFailure { event("document subscriptions: ${it.message}") } }
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
     * §9A.2.1.2 steps 4 to 6: the PUBLISH of one status change [aliases]: the per-user PIDF listing every alias wanted
     * active ([FunctionalAliasRules.wanted]), with a new <p-id-fa> and <take-over> when [takeOver]; Expires 4294967295,
     * or 0 when none is wanted.
     */
    private suspend fun publishAliases(channel: AliasChannel, action: String, aliases: List<String>, takeOver: Boolean) {
        val service = channel.service
        val user = ownId(service)
        val publishId = UUID.randomUUID().toString()
        val listed = FunctionalAliasRules.wanted(channel.wanted ?: channel.state.value.standing, action, aliases)
        channel.wanted = listed
        channel.publish = FunctionalAliasRules.Change(action, aliases, publishId)
        val parts = listOf(aliasInfo(service, user),
            Part(FunctionalAliasPidf.CONTENT_TYPE, FunctionalAliasPidf.request(user, config.clientId, listed.toList(), takeOver, publishId, service).toByteArray()))
        val request = serviceRequest(service, "PUBLISH", "presence", parts)
            .also { it.headers.add("Expires", FunctionalAliasRules.expires(listed)) }
        channel.state.update { it.copy(lastAction = action, lastStatus = null, lastWarning = null, lastOutcomes = emptyMap()) }
        event("${service.name} functional alias $action $aliases${if (takeOver) " (take-over)" else ""}")
        val response = ua.send(request)
        val warning = response.headers["Warning"]
        channel.state.update { if (channel.publish?.publishId == publishId) it.copy(lastStatus = response.status, lastWarning = warning) else it }
        if (!response.isSuccess) {
            if (channel.publish?.publishId == publishId) channel.wanted = null // the standing set again
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
        if (status.publishId != null && status.publishId == channel.publish?.publishId) channel.wanted = null
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
        var nextSubscription = started + 3000 // the MCPTT server grants 3600 s (contract §5.1)
        while (scope.isActive) {
            delay(5_000)
            val now = clock()
            expireDocumentSubscriptions()
            val t = tokens
            if (t != null && t.expiresAt - now < renewalLead()) {
                renewToken()
                setPhase("ready")
            }
            if (now >= nextRegisterAt) {
                // A failed reregistration ends the flow, so that [start] makes an initial registration (TS 24.229
                // §5.1.1.4.1: the UE can reregister "via an initial registration" at any time; it shall after a 403,
                // 408, 500 or 504). Uncaught, it would end this loop and leave the client "ready" without a registration.
                try {
                    register(registerExpires())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    event("registration refresh failed: ${e.message}")
                    ua.flow?.close(e)
                    imsAka?.close()
                    return
                }
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
        // A service that is not started (notStarted) sends nothing: no operation of it goes out unprotected.
        // Its requests inside a dialog need no check, as neither side can create one (outgoing: here; incoming:
        // dispatch).
        ua.outboundGate = { request ->
            if (!configurationPending(request))
                mcServiceOf(request)?.let { service -> notStarted(service)?.let { throw IllegalStateException(it) } }
        }
    }

    private suspend fun dispatch(tx: ServerTransaction) {
        val request = tx.request
        // A request of a service that is not started gets the answer of a service this client does not run.
        if (!configurationPending(request) && mcServiceOf(request)?.let { notStarted(it) } != null) { notHandled(tx); return }
        if (request.method == "NOTIFY" && eventPackage(request) == RegEventRules.EVENT) { regNotify(tx); return }
        // RFC 6665 §4.1.2.3: a subscription this client ended lasts until its final NOTIFY, which is answered 200.
        if (request.method == "NOTIFY" && request.callId in endingSubscriptions) {
            tx.respond(200)
            if ((request.headers["Subscription-State"] ?: "").trim().startsWith("terminated")) endingSubscriptions.remove(request.callId)
            return
        }
        // TS 24.484 §6.3.13.2.2, TS 24.481 §6.3.13.2.1: a NOTIFY of a document subscription, answered 200 (RFC 6665
        // §4.1.3), its documents read after the answer; "terminated" ends the subscription.
        if (request.method == "NOTIFY" && eventPackage(request) == DocumentSubscriptions.EVENT) {
            val configuration = configSubscription?.takeIf { it.request.callId == request.callId }
            val groups = groupDocSubscriptions[request.callId]
            if (configuration != null || groups != null) {
                tx.respond(200)
                val terminated = (request.headers["Subscription-State"] ?: "").trim().startsWith("terminated")
                if (configuration != null) {
                    if (terminated && configSubscription === configuration) { configSubscription = null; event("configuration subscription terminated") }
                    scope.launch { runCatching { configurationNotified() }.onFailure { event("configuration NOTIFY: ${it.message}") } }
                } else {
                    if (terminated) { groupDocSubscriptions.remove(request.callId); event("group document subscription terminated") }
                    scope.launch { runCatching { groupDocumentsNotified(request) }.onFailure { event("group document NOTIFY: ${it.message}") } }
                }
                return
            }
        }
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
        notHandled(tx)
    }

    private suspend fun notHandled(tx: ServerTransaction) {
        when (tx.request.method) {
            "OPTIONS" -> tx.respond(200)
            "INVITE", "MESSAGE" -> tx.respond(488)
            else -> tx.respond(405)
        }
    }

    /**
     * The delay before the next attempt after a failed start-up or sign-in ([ClientConfig.Recovery]): the first one
     * again when told to reconnect now, else twice the last one, up to `recovery.last_retry_ms`.
     */
    private fun nextBackoff(backoff: Long): Long =
        if (reconnectNow.isCompleted) config.recovery.firstRetryMs else (backoff * 2).coerceAtMost(config.recovery.lastRetryMs)
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

/** TS 24.229 §5.1.1.2.1: "the value of 600 000 seconds as the value desired for the duration of the registration". */
private const val IMS_AKA_REGISTER_EXPIRES = 600_000
