//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.sip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openmcx.client.security.AkaAlgorithm
import org.openmcx.client.security.AkaOutcome
import org.openmcx.client.security.CardAka
import org.openmcx.client.security.EspKeys
import org.openmcx.client.security.ImsAka
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * The UE's IMS AKA registration (TS 24.229 clauses 5.1.1.2.2, 5.1.1.4.2, 5.1.1.5.1, 5.1.1.5.3, 5.1.1.5.12) against
 * a fake P-CSCF and S-CSCF, a fake card and a platform without IPsec (the SAs are recorded, the connections are in
 * memory).
 */
class ImsAkaRegistrarTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun tearDown() = scope.cancel()

    private val res = ByteArray(8) { (0x50 + it).toByte() }
    private val ck = ByteArray(16) { it.toByte() }
    private val ik = ByteArray(16) { (16 + it).toByte() }
    private val local: InetAddress = InetAddress.getByName("10.0.0.5")
    private val pcscf: InetAddress = InetAddress.getByName("10.0.0.1")
    private val cfg = ImsAkaConfig("999700000190126@mcx.test", "sip:999700000190126@mcx.test", "mcx.test", pcscf, 5060, 5064)
    private val securityServer = "ipsec-3gpp;q=0.9;alg=aes-gmac-us;prot=esp;mod=trans;spi-c=7001;spi-s=7002;port-c=6100;port-s=6101, " +
        "ipsec-3gpp;q=0.8;alg=null;ealg=aes-gcm-us;prot=esp;mod=trans;spi-c=7001;spi-s=7002;port-c=6100;port-s=6101"

    /** A peer on an in-memory connection: [answer] gives the response to each request (null: none). */
    private class Peer(override val localPort: Int, val answer: (SipRequest) -> SipResponse?) : Connection {
        val requests = CopyOnWriteArrayList<SipRequest>()
        val responses = LinkedBlockingQueue<SipResponse>()
        private val toClient = LinkedBlockingQueue<ByteArray>()
        private val framer = StreamFramer()
        override val localHost = "10.0.0.5"
        override val transport = "TCP"
        override val input: InputStream = object : InputStream() {
            private var current = ByteArray(0)
            private var at = 0
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (at == current.size) { current = toClient.take(); at = 0 }
                if (current.isEmpty()) { toClient.put(current); return -1 }
                val count = minOf(length, current.size - at)
                System.arraycopy(current, at, buffer, offset, count)
                at += count
                return count
            }
        }
        override val output: OutputStream = object : OutputStream() {
            private val pending = ByteArrayOutputStream()
            override fun write(byte: Int) = pending.write(byte)
            override fun write(buffer: ByteArray, offset: Int, length: Int) = pending.write(buffer, offset, length)
            override fun flush() {
                val bytes = pending.toByteArray().also { pending.reset() }
                for (item in framer.feed(bytes, bytes.size)) when (val m = (item as? Framed.Message)?.message) {
                    is SipRequest -> { requests += m; answer(m)?.let { toClient.put(it.encode()) } }
                    is SipResponse -> responses.put(m)
                    else -> {}
                }
            }
        }
        fun inject(request: SipRequest) = toClient.put(request.encode())
        override fun close() { toClient.put(ByteArray(0)) }
    }

    private fun reply(request: SipRequest, status: Int, reason: String, vararg extra: Pair<String, String>): SipResponse {
        val headers = Headers()
        request.headers.all("Via").forEach { headers.add("Via", it) }
        headers.add("From", request.headers["From"]!!).add("To", request.headers["To"]!! + ";tag=scscf")
            .add("Call-ID", request.callId).add("CSeq", request.headers["CSeq"]!!)
        extra.forEach { headers.add(it.first, it.second) }
        return SipResponse(status, reason, headers)
    }

    private val rand = ByteArray(16) { 0x11 }
    private val autn = ByteArray(16) { 0x22 }
    private val nonce = Base64.getEncoder().encodeToString(rand + autn + ByteArray(8))

    private fun challenge(request: SipRequest) = reply(request, 401, "Unauthorized",
        "WWW-Authenticate" to "Digest realm=\"mcx.test\", nonce=\"$nonce\", algorithm=AKAv2-SHA-256, qop=\"auth\"",
        "WWW-Authenticate" to "Digest realm=\"mcx.test\", nonce=\"$nonce\", algorithm=AKAv1-MD5, qop=\"auth\"",
        "Security-Server" to securityServer)

    /** The S-CSCF's check of the AKAv2-SHA-256 response (RFC 4169 with the card's RES, CK, IK). */
    private fun responseValid(request: SipRequest): Boolean {
        val auth = DigestChallenge.authParams(request.headers["Authorization"]!!.removePrefix("Digest"))
        val expected = ImsAka.response(AkaAlgorithm.AKAV2_SHA256, cfg.impi, "mcx.test",
            ImsAka.password(AkaAlgorithm.AKAV2_SHA256, res, ck, ik), "REGISTER", "sip:mcx.test", nonce,
            auth["nc"]!!, auth["cnonce"]!!, "auth")
        return auth["response"] == expected && auth["algorithm"] == "AKAv2-SHA-256"
    }

    private inner class Platform(val outcomes: MutableList<AkaOutcome>) : ImsAkaPlatform {
        val opened = CopyOnWriteArrayList<SaSetParams>()
        val clients = CopyOnWriteArrayList<Peer>()
        var unprotected: Peer? = null
        var nextPort = 40000
        var nextSpi = 1000L
        var onIncoming: ((Connection) -> Unit)? = null
        val reservationsClosed = CopyOnWriteArrayList<Long>()
        /** The platform refusing the next set (Android: the P-CSCF's SPI unavailable). */
        var refuseOpen = false
        var protectedAnswer: (SipRequest) -> SipResponse? = { request ->
            when (request.method) {
                "REGISTER" -> if (responseValid(request) || request.headers["Expires"] == "0")
                    reply(request, 200, "OK", "Contact" to (request.headers["Contact"]!! + ";expires=600000"),
                        "P-Associated-URI" to "<sip:alice@mcx.test>", "Service-Route" to "<sip:orig@scscf:5060;lr>")
                    else reply(request, 403, "Forbidden")
                else -> reply(request, 200, "OK")
            }
        }
        var unprotectedAnswer: (SipRequest) -> SipResponse? = { challenge(it) }

        override val card = CardAka { r, a ->
            assertArrayEquals(rand, r); assertArrayEquals(autn, a)
            if (outcomes.isEmpty()) AkaOutcome.Success(res, ck, ik) else outcomes.removeAt(0)
        }
        override fun localAddress() = local
        override fun reserve(local: InetAddress) = object : SaReservation {
            override val spiUc = nextSpi++
            override val spiUs = nextSpi++
            override val portUc = nextPort++
            override fun close() { reservationsClosed += spiUc }
        }
        override suspend fun connectUnprotected(local: InetAddress, pcscf: InetAddress, port: Int): Connection =
            Peer(nextPort++) { unprotectedAnswer(it) }.also { unprotected = it }
        override suspend fun open(reservation: SaReservation, params: SaSetParams, onIncoming: (Connection) -> Unit): SaSet {
            if (refuseOpen) throw IllegalStateException("Requested SPI is unavailable (spi: ${params.spiPc})")
            opened += params
            this.onIncoming = onIncoming
            val client = Peer(params.portUc) { protectedAnswer(it) }.also { clients += it }
            return object : SaSet {
                override val params = params
                override val client = client
                override fun close() { client.close() }
            }
        }
    }

    private fun registrar(platform: Platform): Pair<UserAgent, ImsAkaRegistrar> {
        val ua = UserAgent(scope, UaIdentity("sip:alice@mcx.test", "", "", "mcx.test", "urn:uuid:1"), transactionTimeoutMs = 5_000)
        return ua to ImsAkaRegistrar(scope, ua, cfg, platform, { ";+sip.instance=\"<urn:uuid:1>\";reg-id=1" }, keepaliveMs = 0)
    }

    @Test fun initialRegistrationSetsUpTheSetAndUsesIt() = runBlocking {
        val platform = Platform(mutableListOf())
        val (ua, registrar) = registrar(platform)
        val granted = withTimeout(10_000) { registrar.register(600000) }
        assertEquals(600000, granted)
        // The unprotected REGISTER (clause 5.1.1.2.2): the ISIM's identities, empty nonce and response, the offer.
        val first = platform.unprotected!!.requests.single()
        assertEquals("sip:999700000190126@mcx.test", first.to.uri)
        assertTrue(first.headers["Authorization"]!!.contains("username=\"999700000190126@mcx.test\"") &&
            first.headers["Authorization"]!!.contains("nonce=\"\", response=\"\""))
        assertTrue(first.headers["Security-Client"]!!.startsWith("ipsec-3gpp;q=0.9;alg=null;ealg=aes-gcm-us;prot=esp;mod=trans"))
        assertNull(first.headers["Security-Verify"])
        // Via: the UE's address and the connection's port, never a name the P-CSCF cannot map to the UE.
        assertTrue(first.headers.values("Via").first().contains("10.0.0.5:${platform.unprotected!!.localPort};"))
        // The set: the P-CSCF's first pair the UE supports (AES-GMAC skipped), its SPIs and ports, the annex I keys.
        val set = platform.opened.single()
        assertEquals("null" to "aes-gcm-us", set.alg to set.ealg)
        assertEquals(listOf(7001L, 7002L), listOf(set.spiPc, set.spiPs))
        assertEquals(listOf(6100, 6101), listOf(set.portPc, set.portPs))
        assertArrayEquals(EspKeys.derive("null", "aes-gcm-us", ck, ik).salt, set.keys.salt)
        // The protected REGISTER over it (clause 5.1.1.5.1): same Call-ID, next CSeq, Security-Verify, port_us.
        val second = platform.clients.single().requests.single()
        assertEquals(first.callId, second.callId)
        assertEquals(first.cseq.number + 1, second.cseq.number)
        assertEquals(securityServer, second.headers["Security-Verify"])
        assertTrue(second.headers["Contact"]!!.contains("@10.0.0.5:5064;"))
        // The protected REGISTER over TCP: the address and port_uc in Via (TS 24.229 clause 5.1.1.2.2 c)).
        assertTrue(second.headers.values("Via").first().contains("10.0.0.5:${set.portUc};"))
        assertEquals(listOf("<sip:alice@mcx.test>"), registrar.associatedUris)
        // A later request: over the set, Via with the protected server port, sec-agree, the preloaded Route.
        val message = ua.newRequest("MESSAGE", "sip:mcptt@mcx.test", NameAddr("sip:mcptt@mcx.test"))
        assertEquals(200, withTimeout(5_000) { ua.send(message).status })
        val sent = platform.clients.single().requests.last()
        assertTrue(sent.headers.values("Via").first().contains("10.0.0.5:5064"))
        assertEquals(securityServer, sent.headers["Security-Verify"])
        assertTrue(sent.headers.values("Require").contains("sec-agree"))
        assertNull(sent.headers["Proxy-Require"])
        assertEquals(listOf("<sip:10.0.0.1:6101;transport=tcp;lr>", "<sip:orig@scscf:5060;lr>"), sent.headers.values("Route"))
    }

    @Test fun aRequestOfThePcscfToPortUsIsAnsweredOnItsConnection() = runBlocking {
        val platform = Platform(mutableListOf())
        val (ua, registrar) = registrar(platform)
        ua.onRequest = { it.respond(200) }
        withTimeout(10_000) { registrar.register(600000) }
        val incoming = Peer(5064) { null }
        platform.onIncoming!!(incoming)
        val headers = Headers().add("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bKpc1").add("From", "<sip:mcptt@mcx.test>;tag=a")
            .add("To", "<sip:alice@mcx.test>").add("Call-ID", "pc1").add("CSeq", "1 MESSAGE").add("Max-Forwards", "70")
        incoming.inject(SipRequest("MESSAGE", "sip:alice@10.0.0.5:5064", headers))
        val answer = withTimeout(5_000) { kotlinx.coroutines.withContext(Dispatchers.IO) { incoming.responses.take() } }
        assertEquals(200, answer.status)
        assertTrue(platform.clients.single().responses.isEmpty())
    }

    @Test fun anSqnFailureSendsAutsOverTheUnprotectedConnectionThenRegisters() = runBlocking {
        val auts = ByteArray(14) { 0x33 }
        val platform = Platform(mutableListOf(AkaOutcome.SyncFailure(auts)))
        val (_, registrar) = registrar(platform)
        withTimeout(10_000) { registrar.register(600000) }
        val requests = platform.unprotected!!.requests
        assertEquals(2, requests.size)
        val withAuts = requests[1].headers["Authorization"]!!
        assertTrue(withAuts.contains("auts=\"${Base64.getEncoder().encodeToString(auts)}\""))
        // Clause 5.1.1.5.3: new spi_uc, spi_us and port_uc, no temporary set.
        assertTrue(requests[0].headers["Security-Client"] != requests[1].headers["Security-Client"])
        assertEquals(1, platform.opened.size)
        assertTrue(registrar.registered)
    }

    @Test fun aMacFailureEndsTheAttemptWithTheReason() = runBlocking {
        val platform = Platform(mutableListOf(AkaOutcome.MacFailure))
        platform.unprotectedAnswer = { r -> if (r.headers["Authorization"]!!.contains("nonce=\"\"")) challenge(r) else reply(r, 403, "Forbidden") }
        val (_, registrar) = registrar(platform)
        try {
            withTimeout(10_000) { registrar.register(600000) }
            fail("registered")
        } catch (e: ImsAkaFailure) {
            assertTrue(e.message!!.contains("not accepted by the card"))
        }
        val empty = platform.unprotected!!.requests[1].headers["Authorization"]!!
        assertTrue(empty.contains("response=\"\"") && !empty.contains("auts"))
        assertTrue(platform.opened.isEmpty())
        assertTrue(!registrar.registered)
    }

    @Test fun aReregistrationGoesOverTheSetInUseAndMovesToANewSet() = runBlocking {
        val platform = Platform(mutableListOf())
        // The S-CSCF challenges every reregistration (TS 33.203 clause 7.4).
        platform.protectedAnswer = { request ->
            if (request.method == "REGISTER" && request.headers["Authorization"]!!.let { !it.contains("cnonce") || platform.clients.size == 1 && platform.clients[0].requests.size > 1 })
                challenge(request)
            else if (request.method == "REGISTER")
                reply(request, 200, "OK", "Contact" to (request.headers["Contact"]!! + ";expires=600000"), "P-Associated-URI" to "<sip:alice@mcx.test>")
            else reply(request, 200, "OK")
        }
        val (ua, registrar) = registrar(platform)
        withTimeout(10_000) { registrar.register(600000) }
        val firstSet = platform.clients.single()
        withTimeout(10_000) { registrar.register(600000) }
        // Clause 5.1.1.4.2: the REGISTER over the set in use, with the last authorization and Security-Verify.
        val rereg = firstSet.requests.last()
        assertEquals("REGISTER", rereg.method)
        assertEquals(securityServer, rereg.headers["Security-Verify"])
        assertNotNull(rereg.headers["Authorization"]!!.let { if (it.contains("cnonce")) it else null })
        assertEquals(2, platform.opened.size)
        assertTrue(platform.opened[0].portUc != platform.opened[1].portUc)
        assertEquals(platform.opened[0].portUs, platform.opened[1].portUs)
        assertTrue(ua.flow!!.connection === platform.clients[1])
        assertEquals(platform.opened[1].portUc, registrar.portUc)
        assertEquals(2, registrar.setsUsed)
        // TS 33.203 clause 7.4: the old set closes once its transactions have ended (polled every 500 ms).
        withTimeout(10_000) { while (registrar.oldSetsClosed < 1) kotlinx.coroutines.delay(100) }
    }

    /**
     * A new set the platform cannot set up (Android refused the P-CSCF's SPI): the reregistration fails with the
     * reason, its reservation is closed, the set in use stays in use (the client then registers anew).
     */
    @Test fun aSetThatCannotBeSetUpFailsTheReregistrationAndKeepsTheSetInUse() = runBlocking {
        val platform = Platform(mutableListOf())
        platform.protectedAnswer = { request ->
            if (request.method == "REGISTER" && platform.clients.size == 1 && platform.clients[0].requests.size > 1) challenge(request)
            else if (request.method == "REGISTER")
                reply(request, 200, "OK", "Contact" to (request.headers["Contact"]!! + ";expires=600000"), "P-Associated-URI" to "<sip:alice@mcx.test>")
            else reply(request, 200, "OK")
        }
        val (ua, registrar) = registrar(platform)
        withTimeout(10_000) { registrar.register(600000) }
        val firstSet = platform.clients.single()
        val closedBefore = platform.reservationsClosed.size
        platform.refuseOpen = true
        val failure = runCatching { withTimeout(10_000) { registrar.register(600000) } }.exceptionOrNull()
        assertTrue(failure is ImsAkaFailure)
        assertTrue(failure!!.message!!.contains("security associations not set up"))
        assertEquals(closedBefore + 1, platform.reservationsClosed.size)
        assertTrue(ua.flow!!.connection === firstSet)
        assertEquals(1, registrar.setsUsed)
        assertTrue(registrar.registered)
    }
}
