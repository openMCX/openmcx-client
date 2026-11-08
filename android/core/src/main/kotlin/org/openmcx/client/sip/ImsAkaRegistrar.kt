//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.sip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openmcx.client.security.AkaAlgorithm
import org.openmcx.client.security.AkaOutcome
import org.openmcx.client.security.CardAka
import org.openmcx.client.security.EspKeys
import org.openmcx.client.security.ImsAka
import org.openmcx.client.security.SecAgree
import org.openmcx.client.security.SecMechanism
import java.io.Closeable
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64

/** One set of the four security associations of TS 33.203 clause 7.1, as the UE installs them. */
data class SaSetParams(
    val local: InetAddress, val pcscf: InetAddress,
    val portUc: Int, val portUs: Int, val portPc: Int, val portPs: Int,
    val spiUc: Long, val spiUs: Long, val spiPc: Long, val spiPs: Long,
    val alg: String, val ealg: String, val keys: EspKeys,
)

/** The UE's inbound SPIs (spi_uc, spi_us) and its protected client port (port_uc) for one new set. */
interface SaReservation : Closeable {
    val spiUc: Long
    val spiUs: Long
    val portUc: Int
}

/** An installed set: its SAs and the connection from port_uc to the P-CSCF's port_ps through them. */
interface SaSet : Closeable {
    val params: SaSetParams
    val client: Connection
}

/** What the platform gives IMS AKA: the card, the UE's address, SPIs and ports, and the IPsec SAs. */
interface ImsAkaPlatform {
    val card: CardAka
    /** The UE's address on the network in use, which the SAs bind (TS 33.203 clause 7.1). */
    fun localAddress(): InetAddress
    /** New inbound SPIs and a new protected client port (TS 24.229 clause 5.1.1.5.3: "new values"). */
    fun reserve(local: InetAddress): SaReservation
    /** The unprotected TCP connection of the initial REGISTER to the P-CSCF (TS 24.229 clause 5.1.1.2.1). */
    suspend fun connectUnprotected(local: InetAddress, pcscf: InetAddress, port: Int): Connection
    /**
     * Installs the set's SAs, connects port_uc to port_ps through them, and gives the listening socket of port_us
     * the set's transforms; connections the P-CSCF opens to port_us (from port_pc) go to [onIncoming].
     */
    suspend fun open(reservation: SaReservation, params: SaSetParams, onIncoming: (Connection) -> Unit): SaSet
    /** The registration is over: what outlives a set (the listening socket of port_us) goes. */
    fun release() {}
}

/** The identities and the P-CSCF of the IMS AKA registration. */
data class ImsAkaConfig(
    /** The ISIM's private user identity (EF_IMPI, TS 31.103 clause 4.2.2). */
    val impi: String,
    /** The ISIM's public user identity for the REGISTERs (EF_IMPU, TS 31.103 clause 4.2.4), possibly barred. */
    val impu: String,
    /** The home network domain (EF_DOMAIN, TS 31.103 clause 4.2.3): the REGISTER's Request-URI and realm. */
    val domain: String,
    /** The preconfigured P-CSCF (TS 24.229 clause 9.2.1) and its unprotected port. */
    val pcscf: InetAddress,
    val pcscfPort: Int = 5060,
    /** The protected server port port_us, which stays fixed (TS 33.203 clause 7.1 NOTE 10). */
    val portUs: Int,
)

class ImsAkaFailure(message: String) : Exception(message)

/**
 * The UE's registration with IMS AKA (TS 24.229 clauses 5.1.1.2.2, 5.1.1.4.2, 5.1.1.5.1, 5.1.1.5.3, 5.1.1.6.2;
 * TS 33.203 clauses 6.1, 7): the unprotected initial REGISTER with the security agreement, the challenge answered by
 * the card, the new set of SAs and the protected REGISTER over it, the reregistrations each over the set in use with a
 * new set, the old set kept until its transactions end (TS 33.203 clause 7.4), and the deregistration. After the
 * agreement every request carries Security-Verify and the sec-agree option-tag (RFC 3329 section 2.3.1), new dialogs
 * and standalone requests the preloaded Route (TS 24.229 clause 5.1.2A.1.1).
 *
 * The life of the registration is [closed]: it completes when the set in use fails (its connection closes).
 */
class ImsAkaRegistrar(
    private val scope: CoroutineScope,
    private val ua: UserAgent,
    private val cfg: ImsAkaConfig,
    private val platform: ImsAkaPlatform,
    /** The Contact's header field parameters after its URI (+sip.instance, reg-id, the feature tags). */
    private val contactParams: () -> String,
    private val trace: (String, SipMessage) -> Unit = { _, _ -> },
    private val event: (String) -> Unit = {},
    private val keepaliveMs: Long = 25_000,
) {
    private class Active(val set: SaSet, val flow: SipFlow, val securityServer: String, val reservation: SaReservation,
                         val incoming: MutableList<SipFlow> = mutableListOf())

    @Volatile private var current: Active? = null
    private var unprotected: SipFlow? = null
    private val random = SecureRandom()
    private var callId: String? = null
    private var from: NameAddr? = null
    private var cseq = 0L
    /** TS 24.229 clause 5.1.1.4.2 a): the last nonce and the last calculated response, for the reregistrations. */
    private var authorization: String? = null

    /** Completes when the registration's set in use fails, with the cause. */
    val closed = CompletableDeferred<Throwable?>()
    /** TS 24.229 clause 5.1.1.2.1: the P-Associated-URI of the last 200 (OK), the default public user identity first. */
    @Volatile var associatedUris: List<String> = emptyList(); private set
    /** TS 24.229 clause 5.1.1.2.1: the Service-Route of the last 200 (OK). */
    @Volatile var serviceRoutes: List<String> = emptyList(); private set
    /** The last authentication: when (seconds since the epoch) and its outcome ("ok", "card resynchronised",
     *  "network not accepted by the card"). */
    @Volatile var lastAuthentication: Pair<Long, String>? = null; private set
    private fun authenticated(outcome: String) { lastAuthentication = System.currentTimeMillis() / 1000 to outcome }

    /** The algorithm pair of the set in use. */
    @Volatile var selected: SecMechanism? = null; private set
    val registered: Boolean get() = current != null
    /** The set in use's protected client port, port_uc (TS 33.203 clause 7.1). */
    val portUc: Int? get() = current?.set?.params?.portUc
    /** The sets that came into use with a 200 (OK), and the old ones closed after their transactions (clause 7.4). */
    @Volatile var setsUsed = 0; private set
    @Volatile var oldSetsClosed = 0; private set

    init {
        // TS 24.229 clause 5.1.2A.1.1 b): every request but REGISTER names the protected server port in Via; REGISTER
        // over TCP its connection's own address and port (clauses 5.1.1.2.2 c), 5.1.1.4.2 c): the protected server port
        // only for UDP), by which the P-CSCF finds the UE's contact and security associations.
        ua.viaSentBy = { request, flow ->
            if (request.method == "REGISTER") flow.connection.let { c -> hostport(InetAddress.getByName(c.localHost), c.localPort) }
            else current?.let { hostport(it.set.params.local, it.set.params.portUs) }
        }
        ua.outboundHeaders = { request -> current?.let { secAgree(request, it) } }
    }

    private fun hostport(address: InetAddress, port: Int): String =
        (if (address.hostAddress!!.contains(':')) "[${address.hostAddress}]" else address.hostAddress!!) + ":$port"

    /**
     * RFC 3329 section 2.3.1 with the result of the second check of the option-tag: Security-Verify and
     * `Require: sec-agree` in every request after the agreement but ACK, CANCEL and PRACK, `Proxy-Require: sec-agree`
     * only in INVITE and OPTIONS (TS 24.229 annex A); REGISTER sets its own. TS 24.229 clause 5.1.2A.1.1: the
     * preloaded Route of a request outside a dialog, the P-CSCF with its protected server port, then Service-Route.
     */
    private fun secAgree(request: SipRequest, active: Active) {
        val method = request.method
        if (method == "REGISTER" || method == "ACK" || method == "CANCEL" || method == "PRACK") return
        request.headers.set("Security-Verify", active.securityServer)
        if (request.headers.values("Require").none { it.equals("sec-agree", true) }) request.headers.add("Require", "sec-agree")
        if ((method == "INVITE" || method == "OPTIONS") && request.headers.values("Proxy-Require").none { it.equals("sec-agree", true) })
            request.headers.add("Proxy-Require", "sec-agree")
        if (request.to.tag == null && request.headers.all("Route").isEmpty()) {
            val p = active.set.params
            request.headers.add("Route", "<sip:${hostport(p.pcscf, p.portPs)};transport=tcp;lr>")
            serviceRoutes.forEach { request.headers.add("Route", it) }
        }
    }

    private fun contact(host: String): String {
        val user = SipUri.parse(ua.identity.publicUserId).user
        return "<sip:$user@$host;transport=tcp;ob>${contactParams()}"
    }

    private fun newCnonce(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * Registers (no set in use: the initial registration, TS 24.229 clause 5.1.1.2) or reregisters over the set in
     * use (clause 5.1.1.4); the expiry granted. A failure throws; after it no set is in use when there was none.
     */
    suspend fun register(expires: Int): Int {
        require(expires > 0)
        val active = current
        val local = active?.set?.params?.local ?: platform.localAddress()
        val over: SipFlow = active?.flow ?: openUnprotected(local)
        if (active == null) {
            // Clause 5.1.1.2.1: a new Call-ID for an initial registration.
            callId = ua.newCallId(); from = NameAddr(cfg.impu).withTag(ua.newTag()); cseq = 0
        }
        // TS 24.229 clause 5.1.1.2.2 a): username, realm and uri, nonce and response empty; clause 5.1.1.4.2 a): the
        // last nonce and response.
        var auth = if (active == null) "Digest username=\"${cfg.impi}\", realm=\"${cfg.domain}\", uri=\"sip:${cfg.domain}\", nonce=\"\", response=\"\""
                   else authorization!!
        var invalid = 0
        var lastInvalid: AkaOutcome? = null
        while (true) {
            val reservation = platform.reserve(local)
            val offer = SecAgree.format(SecAgree.offers(reservation.spiUc, reservation.spiUs, reservation.portUc, cfg.portUs))
            val contactHost = if (active == null) over.connection.let { "${it.localHost}:${it.localPort}" } else hostport(local, cfg.portUs)
            val request = registerRequest(expires, contact(contactHost), auth, offer, active)
            val response = ua.send(request, authenticate = false, over = over)
            if (response.isSuccess) {
                // An answer without a challenge (clause 5.4.1.2.2 step 2 at the S-CSCF does not apply to expires > 0).
                reservation.close()
                return accept(response, expires, active, active, request)
            }
            if (response.status != 401) {
                reservation.close()
                // After the answer to a challenge the card deemed invalid, the network's refusal ends the attempt.
                if (lastInvalid == AkaOutcome.MacFailure) {
                    authenticated("network not accepted by the card")
                    fail("network not accepted by the card (${response.status} ${response.reason})", active)
                }
                fail("REGISTER: ${response.status} ${response.reason}", active)
            }
            // TS 24.229 clause 5.1.1.5.1: the AKA challenge; the topmost one with an algorithm of IMS AKA.
            val challenge = response.headers.all("WWW-Authenticate").map(DigestChallenge::parse)
                .firstOrNull { AkaAlgorithm.of(it.algorithm) != null }
                ?: run { reservation.close(); fail("401 without an IMS AKA challenge", active) }
            val algorithm = AkaAlgorithm.of(challenge.algorithm)!!
            val nonce = ImsAka.parseNonce(challenge.nonce) ?: run { reservation.close(); fail("401 with an invalid nonce", active) }
            val serverValue = response.headers.all("Security-Server").joinToString(", ")
            val mechanism = SecAgree.select(SecAgree.parse(serverValue))
                ?: run { reservation.close(); fail("no common IPsec algorithm pair in Security-Server: $serverValue", active) }
            val qop = if (challenge.qop.any { it.equals("auth", true) }) "auth" else ""
            when (val outcome = platform.card.authenticate(nonce.rand, nonce.autn)) {
                is AkaOutcome.Success -> {
                    lastInvalid = null
                    val password = ImsAka.password(algorithm, outcome.res, outcome.ck, outcome.ik)
                    val cnonce = newCnonce()
                    val digest = ImsAka.response(algorithm, cfg.impi, challenge.realm, password, "REGISTER", "sip:${cfg.domain}",
                        challenge.nonce, "00000001", cnonce, qop)
                    auth = authorizationHeader(challenge, algorithm, digest, qop, cnonce)
                    val keys = EspKeys.derive(mechanism.alg, mechanism.ealg ?: "null", outcome.ck, outcome.ik)
                    val params = SaSetParams(local, cfg.pcscf, reservation.portUc, cfg.portUs, mechanism.portC!!, mechanism.portS!!,
                        reservation.spiUc, reservation.spiUs, mechanism.spiC!!, mechanism.spiS!!,
                        mechanism.alg, mechanism.ealg ?: "null", keys)
                    // A set that cannot be set up locally (e.g. the platform refuses the P-CSCF's SPIs) fails the
                    // attempt; its reservation goes, the set in use (if any) stays until the caller decides.
                    val fresh = try { openSet(reservation, params, serverValue) }
                                catch (e: Exception) { reservation.close(); fail("security associations not set up: ${e.message}", active) }
                    // Clause 5.1.1.5.1 / 5.1.1.4.2: the REGISTER over the new set, with Security-Verify.
                    val protectedRequest = registerRequest(expires, contact(hostport(local, cfg.portUs)), auth, offer, fresh)
                    val answer = try { ua.send(protectedRequest, authenticate = false, over = fresh.flow) }
                                 catch (e: Exception) { closeSet(fresh); throw e }
                    if (!answer.isSuccess) { closeSet(fresh); fail("protected REGISTER: ${answer.status} ${answer.reason}", active) }
                    selected = mechanism.copy(ealg = mechanism.ealg ?: "null")
                    event("IMS AKA: security associations ${selected!!.alg}/${selected!!.ealg}, port_uc ${params.portUc} port_us ${params.portUs} " +
                        "port_pc ${params.portPc} port_ps ${params.portPs}")
                    authorization = auth
                    authenticated(if (invalid > 0) "card resynchronised" else "ok")
                    return accept(answer, expires, active, fresh, protectedRequest)
                }
                is AkaOutcome.SyncFailure, AkaOutcome.MacFailure -> {
                    reservation.close()
                    // Clause 5.1.1.5.12: "A UE shall only respond to two consecutive invalid challenges."
                    if (++invalid > 2) fail("two consecutive invalid challenges", active)
                    // Clause 5.1.1.5.3: MAC failure: no auts, empty response; SQN failure: auts, and a response from an
                    // empty password (RFC 3310 section 3.4); over the set in use, if any, with new SPIs and port_uc.
                    auth = if (outcome is AkaOutcome.SyncFailure) {
                        val cnonce = newCnonce()
                        val digest = ImsAka.response(algorithm, cfg.impi, challenge.realm, ByteArray(0), "REGISTER",
                            "sip:${cfg.domain}", challenge.nonce, "00000001", cnonce, qop)
                        authorizationHeader(challenge, algorithm, digest, qop, cnonce) +
                            ", auts=\"${Base64.getEncoder().encodeToString(outcome.auts)}\""
                    } else {
                        "Digest username=\"${cfg.impi}\", realm=\"${challenge.realm}\", nonce=\"${challenge.nonce}\", " +
                            "uri=\"sip:${cfg.domain}\", response=\"\", algorithm=${algorithm.wire}"
                    }
                    event(if (outcome is AkaOutcome.SyncFailure) "IMS AKA: card out of sync, AUTS sent" else "IMS AKA: MAC failure, the card does not accept the network")
                    lastInvalid = outcome
                }
            }
        }
    }

    private fun authorizationHeader(challenge: DigestChallenge, algorithm: AkaAlgorithm, digest: String, qop: String, cnonce: String): String =
        buildString {
            append("Digest username=\"${cfg.impi}\", realm=\"${challenge.realm}\", nonce=\"${challenge.nonce}\", ")
            append("uri=\"sip:${cfg.domain}\", response=\"$digest\", algorithm=${algorithm.wire}")
            if (qop.isNotEmpty()) append(", qop=auth, nc=00000001, cnonce=\"$cnonce\"")
            challenge.opaque?.let { append(", opaque=\"$it\"") }
        }

    /** TS 24.229 clauses 5.1.1.2.1, 5.1.1.2.2, 5.1.1.4.2: a REGISTER of the procedure; over [over] with its Security-Verify. */
    private fun registerRequest(expires: Int, contact: String, auth: String, offer: String, over: Active?): SipRequest {
        cseq += 1
        val request = ua.newRequest("REGISTER", "sip:${cfg.domain}", NameAddr(cfg.impu), from = from!!, callId = callId!!, cseq = cseq)
        request.headers.add("Contact", contact)
            .add("Expires", expires.toString())
            .add("Authorization", auth)
            .add("Security-Client", offer)
            .add("Require", "sec-agree").add("Proxy-Require", "sec-agree")
            .add("Supported", "outbound, path")
        over?.let { request.headers.add("Security-Verify", it.securityServer) }
        return request
    }

    private suspend fun openUnprotected(local: InetAddress): SipFlow {
        unprotected?.close()
        val connection = platform.connectUnprotected(local, cfg.pcscf, cfg.pcscfPort)
        val flow = SipFlow(scope, connection, { ua.receive(it) }, trace, keepaliveMs = 0)
        flow.start()
        unprotected = flow
        ua.flow = flow
        event("IMS AKA: unprotected connection ${connection.localHost}:${connection.localPort} → ${cfg.pcscf.hostAddress}:${cfg.pcscfPort}")
        return flow
    }

    private suspend fun openSet(reservation: SaReservation, params: SaSetParams, securityServer: String): Active {
        lateinit var active: Active
        val set = platform.open(reservation, params) { connection ->
            // A connection of the P-CSCF to port_us (from port_pc): its requests are answered on it.
            lateinit var incoming: SipFlow
            incoming = SipFlow(scope, connection, { ua.receive(it, incoming) }, trace, keepaliveMs = 0)
            synchronized(active.incoming) { active.incoming += incoming }
            incoming.start()
        }
        val flow = SipFlow(scope, set.client, { ua.receive(it) }, trace, keepaliveMs = keepaliveMs)
        active = Active(set, flow, securityServer, reservation)
        flow.start()
        return active
    }

    /** The 200 (OK): the new set (if any) becomes the one in use, the old one ends with its transactions. */
    private fun accept(response: SipResponse, expires: Int, old: Active?, now: Active?, request: SipRequest): Int {
        if (now == null) throw ImsAkaFailure("registered without a security association set")
        associatedUris = response.headers.values("P-Associated-URI")
        serviceRoutes = response.headers.values("Service-Route")
        if (now !== old) {
            current = now
            setsUsed++
            ua.flow = now.flow
            ua.contactHost = hostport(now.set.params.local, now.set.params.portUs)
            unprotected?.let { it.close(); unprotected = null }
            scope.launch { watch(now) }
            old?.let { retire(it) }
        }
        val mine = response.headers.values("Contact").map(NameAddr::parse)
            .firstOrNull { it.uri.contains(ua.contactHost ?: "\u0000") }
        return mine?.params?.get("expires")?.toIntOrNull() ?: response.headers["Expires"]?.toIntOrNull() ?: expires
    }

    /** The set in use failing ends the registration ([closed]); a retired set's end does not. */
    private suspend fun watch(active: Active) {
        val cause = active.flow.closed.await()
        if (current === active) {
            current = null
            closeSet(active)
            platform.release()
            closed.complete(cause ?: IllegalStateException("security association set closed"))
        }
    }

    /**
     * TS 33.203 clause 7.4: the old set stays until the transactions on it have ended, then it goes (the UE's
     * transactions on it are polled, an implementation detail that leaves that condition unchanged).
     */
    private fun retire(old: Active) {
        scope.launch {
            while (ua.pendingOn(old.flow) > 0 || synchronized(old.incoming) { old.incoming.any { ua.pendingOn(it) > 0 } }) delay(500)
            closeSet(old)
            oldSetsClosed++
            event("IMS AKA: old security association set closed (port_uc ${old.set.params.portUc})")
        }
    }

    private fun closeSet(active: Active) {
        synchronized(active.incoming) { active.incoming.forEach { it.close() } }
        active.flow.close()
        runCatching { active.set.close() }
        runCatching { active.reservation.close() }
    }

    private fun fail(reason: String, active: Active?): Nothing {
        event("IMS AKA: $reason")
        if (active == null) { unprotected?.close(); unprotected = null }
        throw ImsAkaFailure(reason)
    }

    /**
     * TS 24.229 clause 5.1.1.6.2: the deregistration over the set in use (Expires 0, the last authorization), then
     * every set goes (TS 33.203 clause 7.4.1a); the final status, or null without a registration.
     */
    suspend fun deregister(): Int? {
        val active = current ?: return null
        val reservation = platform.reserve(active.set.params.local)
        val offer = SecAgree.format(SecAgree.offers(reservation.spiUc, reservation.spiUs, reservation.portUc, cfg.portUs))
        reservation.close()
        val request = registerRequest(0, contact(hostport(active.set.params.local, cfg.portUs)) + ";expires=0",
            authorization ?: "", offer, active)
        val status = runCatching { ua.send(request, authenticate = false, over = active.flow).status }.getOrNull()
        current = null
        closeSet(active)
        platform.release()
        closed.complete(null)
        return status
    }

    fun close() {
        current?.let { current = null; closeSet(it) }
        unprotected?.close()
        platform.release()
        if (!closed.isCompleted) closed.complete(null)
    }
}
