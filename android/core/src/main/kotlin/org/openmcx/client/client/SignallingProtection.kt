//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.openmcx.client.body.McService
import org.openmcx.client.body.Part
import org.openmcx.client.security.KeyError
import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.KeyUse
import org.openmcx.client.security.Mikey
import org.openmcx.client.security.WireProfile
import org.openmcx.client.security.XmlProtection
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipTransmission
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate of required XML protection, kept apart so that the tests can check it (the Linux client's xml_gate.hpp):
 * whether a message's bodies fall under the confidentiality or integrity protection a service requires, the refusal to
 * send such a message without the service's CSK or past its Expiry time, and the subscriptions sent without XML
 * protection.
 *
 * Specifications: TS 24.379 V19.8.0 (Rel-19), TS 24.281 V19.7.0 (Rel-19), TS 24.282 V19.8.0 (Rel-19), TS 24.481,
 * TS 24.484, TS 33.180 V19.5.0 (Rel-19).
 */
object XmlGate {
    /**
     * TS 24.379 clauses 6.6.2.3.1 and 6.6.3.3.1 (TS 24.281 the same clauses, TS 24.282 clauses 6.5.2.3.1 and
     * 6.5.3.3.1): whether required protection applies to a message: a body the service encrypts while confidentiality
     * protection is required, or an XML body while integrity protection is required (the requirement alone, whatever
     * the keys).
     */
    fun applies(parts: List<Part>, confidentiality: Boolean, integrity: Boolean, encrypted: (String) -> Boolean,
                signable: (String) -> Boolean): Boolean =
        parts.any { (confidentiality && encrypted(it.mediaType)) || (integrity && signable(it.mediaType)) }

    /**
     * TS 33.180 annex E.6.10 and annex E.6.9: required protection is never dropped. Without the service's CSK, or once
     * it is expired or revoked, the message is not sent: throws, and the user agent refuses it.
     */
    fun requireKey(csk: KeyMessage?, service: String, nowUnix: Long) {
        if (csk == null) throw KeyError("XML protection required without the $service CSK")
        KeyUse.requireInUse(csk, nowUnix)
    }

    /**
     * TS 24.484 clause 6.3.13.2.2 and TS 24.481 clauses 6.3.13.2.1 and 7.7: whether an initial SUBSCRIBE (Event
     * xcap-diff) starts a configuration management, group document or GKTP subscription: its
     * application/resource-lists+xml body lists configuration management documents (TS 24.484 clause 7: the
     * org.3gpp.mcptt/mcvideo/mcdata application usages), group documents (org.openmobilealliance.groups) or the
     * <GKTPs> of a GKTP document (org.3gpp.MCPTT-GKTP).
     */
    fun subscriptionProcedure(parts: List<Part>): Boolean {
        val auids = listOf("org.3gpp.mcptt.", "org.3gpp.mcvideo.", "org.3gpp.mcdata.", "org.openmobilealliance.groups/", "org.3gpp.MCPTT-GKTP/")
        return parts.any { part ->
            part.mediaType == RESOURCE_LISTS &&
                Regex("uri=\"([^\"]*)").findAll(part.text).any { m -> auids.any { m.groupValues[1].contains(it) } }
        }
    }

    const val RESOURCE_LISTS = "application/resource-lists+xml"
}

/**
 * XML integrity and confidentiality protection of SIP bodies under the client-server key (CSK) of each MC service, and
 * the protection of MCData signalling with the MCData server: a port of the Linux client (core/xml_protection.cpp and
 * mcdata/protection.cpp) behind the user agent's body filters ([org.openmcx.client.sip.UserAgent.outgoingBodies],
 * [org.openmcx.client.sip.UserAgent.incomingBodies]).
 *
 * The protection a service requires comes from its service configuration document ([protectionOf], the
 * <confidentiality-protection> and <integrity-protection> elements, "true" or absent: enabled), never from the keys:
 * a required protection without a usable CSK means the message is not sent, never sent in the clear. Every XML MIME
 * body is signed (maintainer decision M11), the signatures body excepted. The CMC, GMC and GKTP subscriptions are sent
 * without XML protection (identity hiding not applied, as the Linux client).
 *
 * Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19), TS 33.180
 * V19.5.0 (Rel-19).
 */
class SignallingProtection(
    private val config: ClientConfig,
    /** The (confidentiality, integrity) of a service's service configuration document ("mcptt", ...), once read. */
    private val protectionOf: (String) -> Pair<Boolean, Boolean>?,
    /** The service's CSK, if this client has made one. */
    private val csk: (McService) -> KeyMessage?,
    /** Whether the server holds the service's CSK (its upload was answered with success). */
    private val cskHeld: (McService) -> Boolean,
    /** Unix seconds. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** What the protection did, for the status (debug status `keys.xml_protection`). */
    val xmlEncrypted = AtomicInteger()
    val xmlDecrypted = AtomicInteger()
    val xmlSigned = AtomicInteger()
    val xmlVerified = AtomicInteger()
    /** Received messages refused (integrity check failed, undecryptable, MCData signalling refused). */
    val xmlRefused = AtomicInteger()
    /** Messages not sent: their required protection had no usable key, or the document was not read. */
    val notSent = AtomicInteger()
    val signallingSealed = AtomicInteger()
    val signallingOpened = AtomicInteger()

    private val random = SecureRandom()
    private val profile: WireProfile? = config.security?.wireProfile

    private fun now() = clock()
    private fun nameOf(service: McService) = service.name.lowercase()
    private fun configured(service: McService) = when (service) {
        McService.MCPTT -> config.mcptt != null; McService.MCDATA -> config.mcdata != null; McService.MCVIDEO -> config.mcvideo != null
    }
    private fun psiOf(service: McService) = when (service) {
        McService.MCPTT -> config.mcptt?.psi; McService.MCDATA -> config.mcdata?.psi; McService.MCVIDEO -> config.mcvideo?.psi
    }
    private fun userOf(service: McService) = when (service) {
        McService.MCPTT -> config.mcptt?.userId; McService.MCDATA -> config.mcdata?.userId; McService.MCVIDEO -> config.mcvideo?.userId
    }

    /**
     * TS 24.379 clause 6.6.2.3.1 (TS 24.281 the same clause, TS 24.282 clause 6.5.2.3.1): "If the
     * <confidentiality-protection> element in the Service Configuration document [...] is set to "true" or no
     * <confidentiality-protection> element is present [...], then sending confidentiality protected content [...] is
     * enabled", independently of the keys. False until the service configuration document has been read
     * ([requireProtectionKnown]).
     */
    fun confidentiality(service: McService): Boolean = configured(service) && protectionOf(nameOf(service))?.first == true

    /** TS 24.379 clause 6.6.3.3.1 (TS 24.281 the same, TS 24.282 clause 6.5.3.3.1): integrity protection, as above. */
    fun integrity(service: McService): Boolean = configured(service) && protectionOf(nameOf(service))?.second == true

    /** Whether the service configuration document of [service] asks for either protection. */
    fun required(service: McService): Boolean = confidentiality(service) || integrity(service)

    /**
     * Before the service configuration document is read, whether its protection applies to a message is not known: an
     * XML body the client would protect is not sent then (throws), never in the clear instead.
     */
    private fun requireProtectionKnown(service: McService, parts: List<Part>) {
        if (protectionOf(nameOf(service)) != null) return
        if (parts.any { XmlProtection.isXmlMime(it.contentType) })
            throw KeyError("the ${nameOf(service)} service configuration document has not been read: signalling protection unknown")
    }

    /** TS 33.180 clause 9.3.3 and annex F.1.4: the XPK of [service]: its CSK and CSK-ID, with its MC service user ID. */
    private fun xmlKey(service: McService): XmlProtection.Key? {
        val key = csk(service) ?: return null
        return XmlProtection.Key(key.key, key.csbId, userOf(service) ?: return null)
    }

    /** Helper: the service's CSK, required not to be past its Expiry time (TS 33.180 annex E.6.10). */
    private fun requireInUse(service: McService) { csk(service)?.let { KeyUse.requireInUse(it, now()) } }

    // Outgoing.

    /**
     * TS 24.379 clause 6.6.1.2 and clause 6.6.1.3 (TS 24.281 the same, TS 24.282 clause 6.5.1.2): just before
     * sending, the client encrypts the XML elements and signs the XML bodies of a SIP request or response under the
     * CSK of its service (TS 33.180 clauses 9.3.4.2 and 9.3.5); MCData signalling is sealed under the MCData CSK first
     * (TS 33.180 clause 9.5.3). Throws when a required protection cannot be applied: the message is not sent.
     */
    fun outgoing(transmission: SipTransmission, parts: List<Part>): List<Part> = try {
        if (unprotectedSubscription(transmission, parts)) parts
        else when (val service = serviceOf(transmission, parts)) {
            McService.MCDATA -> mcdataOutgoing(parts)
            else -> protectOutgoing(service, parts)
        }
    } catch (e: Exception) {
        notSent.incrementAndGet()
        throw e
    }

    /**
     * TS 24.484 clause 6.3.13.2.2 step d and TS 24.481 clause 6.3.13.2.1 step d (which the GKTP subscription of
     * TS 24.481 clause 7.7 follows): the CMC and the GMC protect the mcptt-info and resource-lists bodies of their
     * subscription "if identity hiding is required", including the CSK in an application/mikey body; this client does
     * not apply identity hiding (as the Linux client), so the requests of these subscriptions are sent without XML
     * protection, before and after the service authorisation alike. A message belongs to one of them by its event
     * package xcap-diff (the only one these subscriptions, and only they, use here) and, for the initial SUBSCRIBE, by
     * the documents its resource list subscribes to ([XmlGate.subscriptionProcedure]). The GMK of a GKTP document
     * stays protected by its MIKEY-SAKKE I_MESSAGE.
     */
    private fun unprotectedSubscription(transmission: SipTransmission, parts: List<Part>): Boolean {
        val request = transmission.message as? SipRequest ?: transmission.request ?: return false
        if (request.headers["Event"]?.substringBefore(';')?.trim() != "xcap-diff") return false
        val initial = request.method == "SUBSCRIBE" && request.to.tag == null && transmission.message is SipRequest
        return !initial || XmlGate.subscriptionProcedure(parts)
    }

    /**
     * The MC service a message belongs to: the service its request names (ICSI, feature tags: [mcServiceOf]); else the
     * service whose info body it carries; else the service whose PSI is its Request-URI, To or From (the Linux client's
     * test); else MCPTT, as the Linux client.
     */
    internal fun serviceOf(transmission: SipTransmission, parts: List<Part>): McService {
        val request = transmission.message as? SipRequest ?: transmission.request
        request?.let(::mcServiceOf)?.let { return it }
        for (part in parts) when (part.mediaType) {
            MCDATA_INFO -> return McService.MCDATA
            MCVIDEO_INFO -> return McService.MCVIDEO
            MCPTT_INFO -> return McService.MCPTT
        }
        val uris = listOfNotNull(request?.uri, runCatching { transmission.message.to.uri }.getOrNull(),
            runCatching { transmission.message.from.uri }.getOrNull()).map { it.substringBefore(';') }
        for (service in listOf(McService.MCDATA, McService.MCVIDEO, McService.MCPTT))
            if (psiOf(service)?.let { it in uris } == true) return service
        return McService.MCPTT
    }

    /**
     * TS 24.379 clauses 6.6.2.3.1 and 6.6.3.3.1 (TS 24.281 the same, under the MCVideo CSK, which the MCPTT server
     * does not hold): the XML elements of the service's profile encrypted and the XML bodies signed. An XML body whose
     * protection is not known is not sent, the first service authorisation included: an unread service configuration
     * document is not one without protection. TS 24.379 clause 7.2.1: until the server holds the CSK, only the request
     * uploading it is protected.
     */
    private fun protectOutgoing(service: McService, parts: List<Part>): List<Part> {
        if (!configured(service)) return parts
        requireProtectionKnown(service, parts)
        val xmlPart = if (service == McService.MCVIDEO) ::mcvideoXmlPart else ::mcpttXmlPart
        val confidentiality = confidentiality(service)
        val integrity = integrity(service)
        if (!XmlGate.applies(parts, confidentiality, integrity, xmlPart, ::signable)) return parts
        XmlGate.requireKey(csk(service), if (service == McService.MCVIDEO) "MCVideo" else "MCPTT", now())
        val uploads = parts.any { it.mediaType == MIKEY }
        if (!cskHeld(service) && !uploads) return parts
        val key = xmlKey(service) ?: throw KeyError("no XPK for ${nameOf(service)}")
        var out = parts
        if (confidentiality) {
            val profile = XmlProtection.signallingProfile(if (service == McService.MCVIDEO) "mcvideo" else "mcptt")
            out = out.map { part ->
                if (!xmlPart(part.mediaType) || XmlProtection.isProtected(part.text)) part
                else part.copy(content = XmlProtection.encrypt(part.text, key, profile).toByteArray()).also { xmlEncrypted.incrementAndGet() }
            }
        }
        if (integrity) out = signed(out, key)
        return out
    }

    /**
     * TS 24.379 clause 6.6.3.3.3 (TS 24.281 and TS 24.282 the same clause) with TS 33.180 clause 9.3.5: every XML MIME
     * body but the signatures body signed, each named by its Content-ID (one made for a body without), the signatures
     * in an application/vnd.3gpp.mcptt-signed+xml body (TS 24.379 annex F.6); unchanged when one is already there.
     */
    private fun signed(parts: List<Part>, key: XmlProtection.Key): List<Part> {
        if (parts.any { it.mediaType == SIGNED }) return parts
        val bodies = mutableListOf<XmlProtection.SignedBody>()
        val out = parts.map { part ->
            if (!signable(part.mediaType)) part
            else {
                val id = contentId(part) ?: (ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) } + "@mcx")
                bodies += XmlProtection.SignedBody(id, part.text)
                if (contentId(part) == null) part.copy(headers = part.headers + ("Content-ID" to "<$id>")) else part
            }
        }
        if (bodies.isEmpty()) return parts
        xmlSigned.incrementAndGet()
        return out + Part(SIGNED, XmlProtection.sign(bodies, key, SIGNED_NAMESPACE).toByteArray())
    }

    /**
     * TS 24.282 clauses 6.5.2, 6.5.3 and 6.6.3 with TS 33.180 clause 9.5: MCData signalling with the MCData server.
     * Required MCData protection without the MCData CSK (or past its Expiry time): not sent (clauses 6.5.2.3.1 and
     * 6.5.3.3.1). Then, as the Linux client's MCData service and its signing filter: the MCData Data signalling
     * messages sealed under the CSK (clause 6.6.3.2, TS 33.180 clauses 8.5.4 and 9.5.3; bit 7 of the message type set),
     * the XML content of clause 4.6 encrypted (clause 6.5.2.3.1) and every XML body signed (clause 6.5.3.3.1). Until the
     * server holds the CSK only the request uploading it is encrypted and signed (clause 7.2.2 steps 3, 4 and 7), and
     * signalling is sealed only once it holds it (clause 6.6.1).
     */
    private fun mcdataOutgoing(parts: List<Part>): List<Part> {
        val service = McService.MCDATA
        if (!configured(service)) return parts
        requireProtectionKnown(service, parts)
        val confidentiality = confidentiality(service)
        val integrity = integrity(service)
        if (XmlGate.applies(parts, confidentiality, integrity, ::mcdataXmlPart, ::signable))
            XmlGate.requireKey(csk(service), "MCData", now())
        val uploads = parts.any { it.mediaType == MIKEY }
        val usable = csk(service) != null && (cskHeld(service) || uploads)
        var out = parts
        signallingKey()?.let { key ->
            // TS 33.180 annex E.6.10: nothing is sealed under the CSK past its Expiry time (the message is not sent).
            KeyUse.requireInUse(key, now())
            out = out.map { part ->
                if (part.mediaType != SIGNALLING || part.content.isEmpty()) part
                else part.copy(content = sealSignalling(part.content, key)).also { signallingSealed.incrementAndGet() }
            }
        }
        if (confidentiality && usable) {
            val key = xmlKey(service)!!
            out = out.map { part ->
                if (!mcdataXmlPart(part.mediaType) || XmlProtection.isProtected(part.text)) part
                else {
                    requireInUse(service)
                    part.copy(content = XmlProtection.encrypt(part.text, key, MCDATA_PROFILE).toByteArray()).also { xmlEncrypted.incrementAndGet() }
                }
            }
        }
        if (integrity && usable && out.none { it.mediaType == SIGNED } && out.any { signable(it.mediaType) }) {
            requireInUse(service)
            out = signed(out, xmlKey(service)!!)
        }
        return out
    }

    /** TS 24.282 clause 6.6.1: the CSK protecting signalling with the MCData server, once it holds it. */
    fun signallingKey(): KeyMessage? =
        if (config.mcdata?.protectSignalling == true && cskHeld(McService.MCDATA)) csk(McService.MCDATA) else null

    /**
     * TS 33.180 clause 8.5.1: the Protected Payload message of an MCData signalling message under the CSK; it inherits
     * the message type with bit 7 set. DP_AES_128_GCM: the default of table 8.5.4.1-1 NOTE 6, as no key download
     * indicates another (TS 24.282 clause 7.2.5 step 1f; the key download has no coding for it, TS 33.180 clause 5.8.1).
     */
    private fun sealSignalling(message: ByteArray, key: KeyMessage): ByteArray {
        val wire = profile ?: throw KeyError("no key parameter profile")
        return Mikey.sealPayload((message[0].toInt() and 0xff) or 0x40, now(), 0, 0, key.key, key.csbId, message, wire)
    }

    // Incoming.

    /**
     * TS 24.379 clause 6.6.1.4 and TS 24.281 clause 6.6.1.4 (TS 24.282 clause 6.5.1.4): before any other procedure,
     * verify the signatures of a received SIP message (clause 6.6.3.4.2; failure raises "139 ...") and decrypt its XML
     * elements (clause 6.6.2.4.2; failure raises "140 ...") under the MCPTT or MCVideo CSK; MCData signalling and XML
     * from the MCData server are opened under the MCData CSK (TS 24.282 clauses 6.6.3.2 and 6.5.2.4).
     */
    fun incoming(transmission: SipTransmission, parts: List<Part>): List<Part> = try {
        openIncoming(transmission, parts)
    } catch (e: Exception) {
        xmlRefused.incrementAndGet()
        throw e
    }

    private fun openIncoming(transmission: SipTransmission, parts: List<Part>): List<Part> {
        // A request naming an XPK-ID this client does not hold is refused first: the MCPTT, MCVideo and MCData CSKs are
        // held (the MCData CSK also while it is uploaded, below).
        if (!transmission.isResponse) refuseUnknownKeys(parts) { id -> McService.entries.any { csk(it)?.csbId == id } }
        if (McService.entries.none { csk(it) != null }) return parts
        // The XPK a body names: this client's MCPTT CSK or its MCVideo CSK (TS 24.281 clause 6.6.2.2).
        fun keyFor(id: Long?): XmlProtection.Key? = when {
            id == null -> null
            csk(McService.MCPTT)?.csbId == id -> xmlKey(McService.MCPTT)
            csk(McService.MCVIDEO)?.csbId == id -> xmlKey(McService.MCVIDEO)
            else -> null
        }
        var out = parts
        // TS 24.379 clause 6.6.1.4 step 1 (TS 24.282 clause 6.5.1.4): the signatures under this client's CSK are
        // verified, then removed. TS 24.282 clause 6.5.3.2: those of the MCData server are under the MCData CSK (also
        // while it is uploaded: the server's first request can overtake its 200 (OK) to the service authorisation).
        out.firstOrNull { it.mediaType == SIGNED }?.let { signatures ->
            val id = try { XmlProtection.signatureKeyId(signatures.text) } catch (e: Exception) { throw KeyError("139 ${e.message}") }
            val key = if (id != null && csk(McService.MCDATA)?.csbId == id) {
                try { requireInUse(McService.MCDATA) } catch (e: Exception) { throw KeyError("139 ${e.message}") }
                xmlKey(McService.MCDATA)
            } else keyFor(id)
            if (key != null) {
                val bodies = out.filter { it.mediaType != SIGNED }.mapNotNull { part -> contentId(part)?.let { XmlProtection.SignedBody(it, part.text) } }
                try { XmlProtection.verify(signatures.text, bodies, key) } catch (e: Exception) { throw KeyError("139 ${e.message}") }
                xmlVerified.incrementAndGet()
                out = out - signatures
            }
        }
        // Content under the MCData CSK is opened below.
        out = out.map { part ->
            if ((!mcpttXmlPart(part.mediaType) && !mcvideoXmlPart(part.mediaType)) || !XmlProtection.isProtected(part.text)) part
            else {
                val key = keyFor(XmlProtection.keyId(part.text))
                if (key == null) part
                else part.copy(content = decryptOr140(part.text, key).toByteArray()).also { xmlDecrypted.incrementAndGet() }
            }
        }
        if (serviceOf(transmission, out) == McService.MCDATA && configured(McService.MCDATA)) out = mcdataIncoming(transmission, out)
        return out
    }

    /**
     * TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.4: the MCData Data signalling of a request from the MCData
     * server opened under the CSK (clear signalling while signalling protection is in use is refused: 403); then
     * clause 6.5.2.4: its protected XML content decrypted (failure: 403 with warning 140). The CSK being uploaded in the
     * service authorisation (clause 7.2.2 step 3) also opens them: the server's first protected request can overtake its
     * 200 (OK) to the authorisation.
     */
    private fun mcdataIncoming(transmission: SipTransmission, parts: List<Part>): List<Part> {
        var out = parts
        val signalling = signallingKey()
        if (signalling != null && !transmission.isResponse) {
            out = out.map { part ->
                if (part.mediaType != SIGNALLING) part
                else part.copy(content = openSignalling(part.content, signalling)).also { signallingOpened.incrementAndGet() }
            }
        }
        val key = if (confidentiality(McService.MCDATA)) xmlKey(McService.MCDATA) else null
        return out.map { part ->
            if (!mcdataXmlPart(part.mediaType) || !XmlProtection.isProtected(part.text)) part
            else {
                if (key == null) throw KeyError("140 encrypted XML without an XPK")
                try { requireInUse(McService.MCDATA) } catch (e: Exception) { throw KeyError("140 ${e.message}") }
                part.copy(content = decryptOr140(part.text, key).toByteArray()).also { xmlDecrypted.incrementAndGet() }
            }
        }
    }

    /**
     * TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.4: a signalling message under the CSK, opened; TS 33.180 annex
     * F.1.5 (last paragraph): CSK signalling, the one use of DP_AES_256_GCM, accepted as well. The failure is not a
     * "139" or "140" one: the request is refused with 403 without a warning, as the Linux client.
     */
    private fun openSignalling(body: ByteArray, key: KeyMessage): ByteArray {
        val wire = profile ?: throw KeyError("MCData signalling refused: no key parameter profile")
        try {
            // TS 33.180 annex E.6.10: nothing is opened under the CSK past its Expiry time (the message is refused).
            KeyUse.requireInUse(key, now())
            if (body.isEmpty() || (body[0].toInt() and 0x40) == 0) throw KeyError("clear signalling while signalling protection is in use")
            if (Mikey.payloadKeyId(body) != key.csbId) throw KeyError("signalling under another key")
            val opened = Mikey.openPayload(body, key.key, wire, cskSignalling = true)
            if (opened.plain.isEmpty() || (opened.type and 0x3f) != (opened.plain[0].toInt() and 0x3f) || (opened.plain[0].toInt() and 0xc0) != 0)
                throw KeyError("protected signalling type mismatch")
            return opened.plain
        } catch (e: Exception) {
            throw KeyError("MCData signalling refused: ${e.message}")
        }
    }

    private fun decryptOr140(xml: String, key: XmlProtection.Key): String =
        try { XmlProtection.decrypt(xml, key) } catch (e: Exception) { throw KeyError("140 ${e.message}") }

    companion object {
        const val SIGNED = "application/vnd.3gpp.mcptt-signed+xml"
        /** TS 24.379 annex F.6: the namespace of the signatures document (TS 24.281 and TS 24.282 use the same). */
        const val SIGNED_NAMESPACE = "urn:3gpp:ns:mcpttSignedDoc:1.0"
        const val MIKEY = "application/mikey"
        const val SIGNALLING = "application/vnd.3gpp.mcdata-signalling"
        const val MCPTT_INFO = "application/vnd.3gpp.mcptt-info+xml"
        const val MCVIDEO_INFO = "application/vnd.3gpp.mcvideo-info+xml"
        const val MCDATA_INFO = "application/vnd.3gpp.mcdata-info+xml"
        private const val PIDF = "application/pidf+xml"
        private const val CONFERENCE_INFO = "application/conference-info+xml"

        /**
         * TS 33.180 clause 9.3.5, TS 24.379 clause 6.6.3.1, TS 24.281 clause 6.6.3.1 and TS 24.282 clause 6.5.3.1: "When
         * integrity protection is enabled, all XML MIME bodies transported in SIP requests and responses are integrity
         * protected" (maintainer decision M11): every XML MIME body is signed, not only the types each clause lists, the
         * signatures body excepted.
         */
        fun signable(type: String): Boolean = type != SIGNED && XmlProtection.isXmlMime(type)

        /** TS 24.379 clause 4.8: the MCPTT XML MIME bodies that carry sensitive application data to encrypt. */
        fun mcpttXmlPart(type: String): Boolean = type == MCPTT_INFO || type == XmlGate.RESOURCE_LISTS || type == PIDF ||
            type == "application/vnd.3gpp.mcptt-location-info+xml" || type == CONFERENCE_INFO

        /** TS 24.281 clause 6.6.2.1: the MCVideo XML MIME bodies that carry sensitive application data to encrypt. */
        fun mcvideoXmlPart(type: String): Boolean = type == MCVIDEO_INFO || type == XmlGate.RESOURCE_LISTS || type == PIDF ||
            type == "application/vnd.3gpp.mcvideo-location-info+xml" || type == CONFERENCE_INFO

        /** TS 24.282 clause 4.6: the MIME bodies whose XML content is confidentiality protected. */
        fun mcdataXmlPart(type: String): Boolean = type == MCDATA_INFO || type == XmlGate.RESOURCE_LISTS || type == PIDF ||
            type == "application/vnd.3gpp.mcdata-location-info+xml"

        /** TS 24.282 clause 4.6: the XML elements and URI attributes that confidentiality protection encrypts. */
        val MCDATA_PROFILE = XmlProtection.Profile(
            setOf("mcdata-access-token", "mcdata-request-uri", "mcdata-calling-user-id", "mcdata-called-party-id",
                "mcdata-calling-group-id", "alert-ind", "originated-by", "mcdata-client-id", "functional-alias-URI",
                "called-functional-alias-URI",
                // TS 24.282 annex D.4: the location reported, when confidentiality protection is required.
                "CurrentServingEcgi", "NeighbouringEcgi", "MbmsSaId", "MbsfnArea", "CL_5GMbsfsaArea", "CurrentServingNcgi",
                "CL_NeighbouringNcgi", "CL_BearingAndSpeed", "longitude", "latitude", "altitude", "horizontalaccuracy",
                "verticalaccuracy"),
            setOf("entry" to "uri", "presence" to "entity", "affiliation" to "group", "functionalAlias" to "functionalAliasID"))

        /**
         * TS 24.379 clause 6.6.1.4 (TS 24.281 clause 6.6.1.4, TS 24.282 clause 6.5.1.4): a received SIP request whose
         * signatures or encrypted XML elements name an XPK-ID (ds:KeyName) the receiver does not hold is refused. The XPK
         * of clause 6.6.3.4.2 step 4 cannot be obtained, so the signature validation of XMLDSig clause 3.2.2 fails and
         * step 5 sends 403 with "139 integrity protection check failed"; the decryption of clause 6.6.2.4.2 step 2 (XML
         * Encryption 1.1 clause 4.4 needs the key) fails and step 3 sends 403 with "140 unable to decrypt XML content".
         * Neither clause names an unknown XPK-ID in so many words (spec text defect, reported), but no reading lets the
         * request be processed unverified or still encrypted (clause 6.6.1.4 steps 1 b) and 2 b)). Throws a [KeyError]
         * with the warning code first, which the user agent answers with that 403.
         * Not covered, as the specs give no outcome there (kept as they were and reported): a SIP response (the only
         * failure action, a 403, cannot answer a response), encrypted URI attributes (clause 6.6.2.4.3 has no failure
         * step) and a body without a ds:KeyName (TS 33.180 clauses 9.3.4.2 and 9.3.5: the key is then "known ... through
         * other means"). The same check as the Linux client's xml_gate::refuse_unknown_keys.
         */
        fun refuseUnknownKeys(parts: List<Part>, held: (Long) -> Boolean) {
            for (part in parts) {
                if (part.mediaType == SIGNED) {
                    val id = try { XmlProtection.signatureKeyId(part.text) } catch (e: Exception) { throw KeyError("139 ${e.message}") }
                    if (id != null && !held(id)) throw KeyError("139 signed under an XPK-ID this client does not hold")
                } else if ((mcpttXmlPart(part.mediaType) || mcvideoXmlPart(part.mediaType) || mcdataXmlPart(part.mediaType)) &&
                    part.text.contains("EncryptedData")) {
                    // The element half of XmlProtection.isProtected; XmlProtection.keyId names the first encrypted URI or element.
                    val id = try { XmlProtection.keyId(part.text) } catch (e: Exception) { throw KeyError("140 ${e.message}") }
                    if (id != null && !held(id)) throw KeyError("140 XML content encrypted under an XPK-ID this client does not hold")
                }
            }
        }

        /** RFC 2392: the Content-ID of a body part, without its angle brackets; null without one. */
        fun contentId(part: Part): String? = part.headers.firstOrNull { it.first.equals("Content-ID", true) }
            ?.second?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotEmpty() }
    }
}
