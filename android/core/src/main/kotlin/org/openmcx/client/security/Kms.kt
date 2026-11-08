//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.text
import org.openmcx.client.http.HttpClient
import org.w3c.dom.Element
import java.math.BigInteger
import java.time.Instant

/**
 * The KMS interface of TS 33.180 Annex D: the KMS init response (the KMS certificate: its URI, key
 * period and public keys) and the key provisioning response (a user's SSK, PVT and RSK for the current
 * period), bound to the request and at most five minutes old. Without transport keys they are the TLS profile: the
 * transport authenticates the KMS, and XML signatures and encrypted keys are refused. With the user's pre-provisioned
 * TrK and InK (clause 5.3.2, annex D.1): requests signed under the InK, else the TrK (annex D.2.2), signed responses
 * verified under it (annex D.3.1), key sets wrapped under the TrK (clause 9.3.4.2, annex D.3.3.2). No Signature-ID or
 * TrK-ID element is written or read (annex D.3.5.3 gives no text form, a spec defect), as in the shared C++ core.
 */
object KmsDocuments {
    private const val NS = "urn:3gpp:ns:mcsecKMSInterface:1.0"

    private fun unhex(text: String): ByteArray {
        val s = text.filterNot { it.isWhitespace() }
        if (s.isEmpty() || s.length % 2 != 0) throw KeyError("invalid hexBinary")
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun Element.field(name: String): String = one(name).text()

    private fun Element.one(name: String): Element {
        val found = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
            .filter { it.localName == name && it.namespaceURI == NS }
        if (found.size != 1) throw KeyError(if (found.isEmpty()) "missing $name" else "duplicate $name")
        return found[0]
    }

    private fun Element.optional(name: String): Element? =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.localName == name && it.namespaceURI == NS }

    private val DATE_TIME = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|([+-])(\d{2}):(\d{2}))?""")

    /**
     * The NTP time of an xsd:dateTime (XML Schema Part 2 clause 3.2.7, the type of the Time, ValidFrom and ValidTo
     * elements of TS 33.180 annex D.3.5.1): "YYYY-MM-DDThh:mm:ss", optional fractional seconds (dropped), and the time
     * zone "Z" or "+hh:mm"/"-hh:mm". A dateTime without a time zone is refused: it names no instant (clause 3.2.7.3), and
     * TS 33.180 does not say which zone it would be in. As the shared C++ core (key_documents.cpp timevalue).
     */
    internal fun time(text: String): Long {
        val m = DATE_TIME.matchEntire(text) ?: throw KeyError("invalid timestamp")
        val g = m.groupValues
        if (g[8].isEmpty()) throw KeyError("timestamp without a time zone")
        val local = try {
            java.time.LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        } catch (e: java.time.DateTimeException) { throw KeyError("invalid calendar timestamp") }
        var offset = 0L
        if (g[8] != "Z") {
            val hours = g[10].toInt(); val minutes = g[11].toInt()
            if (!(hours < 14 || (hours == 14 && minutes == 0)) || minutes >= 60) throw KeyError("invalid timestamp time zone")
            offset = (if (g[9] == "+") 1 else -1) * (hours * 3600L + minutes * 60L)
        }
        val unix = local.toEpochSecond(java.time.ZoneOffset.UTC) - offset
        if (unix < 0) throw KeyError("invalid timestamp")
        return unix + Mikey.NTP_EPOCH
    }

    /** The xsd:dateTime of an NTP time, in UTC ("Z"), as the shared core writes it. */
    internal fun date(ntp: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(ntp - Mikey.NTP_EPOCH))

    private fun bounds(e: Element, nowNtp: Long) {
        e.optional("Revoked")?.text()?.let { if (it != "false" && it != "0") throw KeyError("revoked security key") }
        e.optional("ValidFrom")?.text()?.let { if (nowNtp < time(it)) throw KeyError("key not yet valid") }
        e.optional("ValidTo")?.text()?.let { if (nowNtp >= time(it)) throw KeyError("expired security key") }
    }

    private fun Element.elements(): List<Element> = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** No ds:Signature anywhere under [e]: only a SignedKmsResponse's own Signature is read, and only when verified. */
    private fun noSignature(e: Element) {
        if (e.localName == "Signature" && e.namespaceURI == DSIG) throw KeyError("XML signatures require a verifying profile")
        e.elements().forEach(::noSignature)
    }

    /**
     * TS 33.180 annexes D.1 and D.3.1: the document of a KMS response. A SignedKmsResponse with a ds:Signature is
     * verified under the integrity key of the user's transport keys (the InK, else the TrK, clause 5.3.2; annex F.1.4
     * FC 0x52, P0 [user]), which its KeyName must name (clause 9.3.5, "base64 encoded" XPK-ID); the verified KmsResponse
     * is what is read. Without transport keys a signature is refused (TLS profile).
     */
    private fun open(xml: String, user: String, keys: UserTransportKeys?): Element {
        if (xml.contains("<!DOCTYPE")) throw KeyError("forbidden DTD")
        val root = Xml.parse(xml)
        val signed = root.localName == "SignedKmsResponse" && root.namespaceURI == NS &&
            root.elements().any { it.localName == "Signature" && it.namespaceURI == DSIG }
        if (!signed) return root
        val xpk = keys?.integrity ?: throw KeyError("XML signatures require a verifying profile")
        if (KmsSignature.keyName(xml) != xpk.keyName) throw KeyError("KMS response signed with another key")
        val key = TransportKeys.protectionKey(xpk, user, TransportKeys.INTEGRITY)
        val payload = try { KmsSignature.verifyPayload(xml, key) } finally { java.util.Arrays.fill(key, 0) }
        return Xml.parse(payload)
    }

    /**
     * The KmsMessage of a response for [user] from [kms] to [request], checked (table D.3.1-1): the KmsResponse (or the
     * KmsResponse of an unsigned SignedKmsResponse) is bound to the user, the KMS and the request, is at most 5 minutes
     * old or ahead and carries no KmsError. The Version attribute is optional (annex D.3.5.1: fixed="1.0.0", not
     * required).
     */
    private fun envelope(xml: String, user: String, kms: String, request: String, nowNtp: Long, keys: UserTransportKeys?): Element {
        var root = open(xml, user, keys)
        noSignature(root)
        if (root.localName == "SignedKmsResponse" && root.namespaceURI == NS) root = root.one("KmsResponse")
        if (root.localName != "KmsResponse" || root.namespaceURI != NS ||
            (root.hasAttribute("Version") && root.getAttribute("Version") != "1.0.0")) throw KeyError("invalid KMS response")
        if (root.field("UserUri") != user || root.field("KmsUri") != kms || root.field("ClientReqUrl") != request) throw KeyError("KMS response binding mismatch")
        if (kotlin.math.abs(time(root.field("Time")) - nowNtp) > 300) throw KeyError("stale KMS response")
        if (root.optional("KmsError") != null) throw KeyError("KMS returned an error")
        return root.one("KmsMessage")
    }

    fun parseInit(xml: String, user: String, kms: String, request: String, nowNtp: Long, keys: UserTransportKeys? = null): KmsPublic {
        val cert = envelope(xml, user, kms, request, nowNtp, keys).one("KmsInit").one("KmsCertificate")
        if (cert.getAttribute("Version") != "1.1.0" || cert.getAttribute("Role") != "Root" || cert.field("KmsUri") != kms ||
            cert.field("UserIdFormat") != "2") throw KeyError("unsupported KMS certificate")
        bounds(cert, nowNtp)
        cert.optional("ParameterSet")?.text()?.let { if (it != "1") throw KeyError("unsupported parameter set") }
        val period = cert.field("UserKeyPeriod").toLongOrNull() ?: throw KeyError("invalid key period")
        val offset = cert.field("UserKeyOffset").toLongOrNull() ?: throw KeyError("invalid key offset")
        if (period <= 0 || period > 0xffffffffL || offset < 0 || offset >= period) throw KeyError("invalid KMS key period")
        return try {
            KmsPublic(kms, period, offset, Eccsi.P256.decode(unhex(cert.field("PubAuthKey"))), Sakke.E.decode(unhex(cert.field("PubEncKey"))))
        } catch (e: IllegalArgumentException) { throw KeyError("invalid KMS public key") }
    }

    /**
     * TS 33.180 table D.3.3.2-1: the value of a key element: the unencrypted KeyContentType of the KMS interface
     * namespace, or with a TrK the EncKeyContentType (clause 9.3.4.2, annex D.3.3.2): one xenc:EncryptedKey with
     * kw-aes256, its KeyName the base64 TrK-ID, unwrapped under the TrK's confidentiality key (annex F.1.4 FC 0x51, P0
     * the user) with the test suite's padding ([TransportKeys.unwrapKeyMaterial]).
     */
    private fun keyField(set: Element, name: String, trk: XpkKey?, user: String): ByteArray {
        val e = set.one(name)
        val type = e.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type")
        val prefix = type.substringBefore(':', "")
        if (e.lookupNamespaceURI(prefix.ifEmpty { null }) != NS) throw KeyError("unsupported or invalid key content")
        when (type.substringAfter(':')) {
            "KeyContentType" -> return unhex(e.text())
            "EncKeyContentType" -> {}
            else -> throw KeyError("unsupported or invalid key content")
        }
        trk ?: throw KeyError("encrypted key material requires the TrK")
        val encrypted = e.only("EncryptedKey", XENC)
        if (encrypted.only("EncryptionMethod", XENC).getAttribute("Algorithm") != KW_AES256) throw KeyError("unsupported key encryption algorithm")
        if (encrypted.only("KeyInfo", DSIG).only("KeyName", DSIG).text().trim() != trk.keyName) throw KeyError("key encrypted under another TrK")
        val wrapped = try {
            java.util.Base64.getMimeDecoder().decode(encrypted.only("CipherData", XENC).only("CipherValue", XENC).text().trim())
        } catch (x: IllegalArgumentException) { throw KeyError("invalid CipherValue") }
        val kek = TransportKeys.protectionKey(trk, user, TransportKeys.CONFIDENTIALITY)
        return try { TransportKeys.unwrapKeyMaterial(kek, wrapped) } finally { java.util.Arrays.fill(kek, 0) }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun tag(name: String, value: String) = "<$name>${escape(value)}</$name>"

    /**
     * TS 33.180 annex D.2.2 (table D.2.2-1): the SignedKmsRequest a KM client posts with a KMS request when it holds an
     * InK or TrK: UserUri, KmsUri (the KMS URI, the security domain identifier of clause 3.1), Time and ClientReqUrl (the
     * request URI), signed under the integrity key (annex F.1.4 FC 0x52, P0 [user]); Id "xmldoc" (the Reference target)
     * and the Version of the KmsRequestType, 1.1.0, as the shared core (kms_request_xml).
     */
    fun requestXml(user: String, kms: String, request: String, nowNtp: Long, keys: UserTransportKeys): String {
        require(user.isNotEmpty() && kms.isNotEmpty() && request.isNotEmpty()) { "empty KMS request identity" }
        val xpk = keys.integrity ?: throw KeyError("a signed KMS request needs an InK or TrK")
        val payload = "<KmsRequest xmlns=\"$NS\" Id=\"xmldoc\" Version=\"1.1.0\">" + tag("UserUri", user) + tag("KmsUri", kms) +
            tag("Time", date(nowNtp)) + tag("ClientReqUrl", request) + "</KmsRequest>"
        val key = TransportKeys.protectionKey(xpk, user, TransportKeys.INTEGRITY)
        return try { KmsSignature.sign(payload, key, xpk.keyName) } finally { java.util.Arrays.fill(key, 0) }
    }

    private const val XENC = "http://www.w3.org/2001/04/xmlenc#"
    private const val DSIG = "http://www.w3.org/2000/09/xmldsig#"
    private const val KW_AES256 = "http://www.w3.org/2001/04/xmlenc#kw-aes256"

    /** The one child element [name] of the namespace [ns]. */
    private fun Element.only(name: String, ns: String): Element {
        val found = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
            .filter { it.localName == name && it.namespaceURI == ns }
        if (found.size != 1) throw KeyError(if (found.isEmpty()) "missing $name" else "duplicate $name")
        return found[0]
    }

    /**
     * The key set for [user] under [kms], validated (§5.1.2 of RFC 6507, §6.1.2 of RFC 6508); its key elements in clear,
     * or wrapped under the TrK of [keys] when there is one. With [detectUidForm] the key set is validated for the UID's 32 octets
     * first, then for its base64 text, and the form it is valid for is returned (IdentityKeys.uidForm); without, for the
     * text only.
     */
    fun parseKeyProv(xml: String, kms: KmsPublic, user: String, request: String, nowNtp: Long,
                     keys: UserTransportKeys? = null, detectUidForm: Boolean = false): IdentityKeys {
        val trk = keys?.confidentiality
        val set = envelope(xml, user, kms.kmsUri, request, nowNtp, keys).one("KmsKeyProv").one("KmsKeySet")
        val uid = Mikey.uid(user, kms, nowNtp)
        if (set.getAttribute("Version") != "1.1.0" || set.field("KmsUri") != kms.kmsUri || set.field("UserUri") != user ||
            set.field("UserID") != uid) throw KeyError("KMS keyset identity mismatch")
        bounds(set, nowNtp)
        // Table D.3.3.2-1: the SSK is an "OCTET STRING encoding of an integer as described in section 6 of [31]"
        // (RFC 6090 6.2, any length k): leading zero octets are allowed; the key validation checks its value.
        val keys = try {
            val ssk = BigInteger(1, keyField(set, "UserSigningKeySSK", trk, user))
            if (ssk.bitLength() > 256) throw KeyError("invalid SSK size")
            IdentityKeys(user, set.field("KeyPeriodNo").toLong(), ssk,
                Eccsi.P256.decode(keyField(set, "UserPubTokenPVT", trk, user)), Sakke.E.decode(keyField(set, "UserDecryptKey", trk, user)))
        } catch (e: IllegalArgumentException) { throw KeyError("invalid key material") }
        if (keys.periodNumber != kms.periodNumber(nowNtp)) throw KeyError("identity key period mismatch")
        // TS 33.180 annex F.2.1.1 does not say which UID form enters ECCSI and SAKKE (spec defect, reported, see
        // UidForm): with detection the 32 octets first (the ETSI test suite's reading, as the Linux client), then the
        // base64 text (OpenMCX's form). The RFC 6507 5.1.2 and RFC 6508 6.1.2 checks are made in either case; a key set
        // valid in neither form is refused.
        fun valid(id: ByteArray) = Eccsi(kms.kpak).validate(id, keys.ssk, keys.pvt) && Sakke(kms.z).validate(id, keys.rsk)
        if (detectUidForm && valid(Mikey.uidDigest(user, kms, nowNtp)))
            return IdentityKeys(keys.uri, keys.periodNumber, keys.ssk, keys.pvt, keys.rsk, UidForm.RAW)
        val id = uid.toByteArray()
        if (!Eccsi(kms.kpak).validate(id, keys.ssk, keys.pvt)) throw KeyError("invalid ECCSI identity key")
        if (!Sakke(kms.z).validate(id, keys.rsk)) throw KeyError("invalid SAKKE identity key")
        return keys
    }

    /** The XCAP selector of [group]'s GKTPs (TS 24.481, TS 33.180 5.7), as a GKTP subscription names it. */
    fun gktpSelector(group: String): String =
        "org.3gpp.MCPTT-GKTP/global/byGroupID/" + KeyClient.xcapSegment(group) + "/~~/group/list-service/mgktp:GKTPs?xmlns(mgktp=urn:3gpp:ns:mcpttGKTP:1.0)"

    /** The resource list of a GKTP subscription for [group]. */
    fun gktpResourceList(group: String): String = Xml.DECLARATION +
        "<resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-lists\"><list><entry uri=\"" + Xml.escape(gktpSelector(group)) + "\"/></list></resource-lists>"

    /** The I_MESSAGE in an xcap-diff NOTIFY (RFC 5874) of [group]'s GKTPs under [xcapRoot]. */
    fun parseGktpXcapDiff(xml: String, group: String, xcapRoot: String): ByteArray {
        if (xml.contains("<!DOCTYPE")) throw KeyError("forbidden DTD")
        val root = Xml.parse(xml)
        if (root.localName != "xcap-diff" || root.getAttribute("xcap-root") != xcapRoot) throw KeyError("XCAP diff root binding mismatch")
        val element = root.child("element") ?: throw KeyError("no GKTP element")
        val exists = element.getAttribute("exists")
        if (element.getAttribute("sel") != gktpSelector(group) || (exists.isNotEmpty() && exists != "true" && exists != "1")) throw KeyError("XCAP diff selector mismatch")
        val gktp = element.child("GKTPs")?.child("GMK-GKTPs")?.child("GKTP") ?: throw KeyError("missing GKTP")
        if (gktp.getAttribute("id").isEmpty()) throw KeyError("missing GKTP id")
        return unhex(gktp.text())
    }

    /**
     * The GKTP document's I_MESSAGE for [group] (TS 33.180 5.7). A GKTP whose activation time is still to come is
     * kept too (TS 24.481 7.7.8: the GMS may send the next key ahead of a key change): its GMK's own Activation Time
     * (TS 33.180 E.6.4) says from when it is used.
     */
    fun parseGktp(xml: String, group: String): ByteArray {
        val root = Xml.parse(xml)
        val list = root.child("list-service") ?: throw KeyError("invalid GKTP document")
        if (root.localName != "group" || list.getAttribute("uri") != group) throw KeyError("GKTP group mismatch")
        val gktp = list.child("GKTPs")?.child("GMK-GKTPs")?.child("GKTP") ?: throw KeyError("missing GKTP")
        if (gktp.getAttribute("id").isEmpty()) throw KeyError("missing GKTP id")
        return unhex(gktp.text())
    }
}

/**
 * One identity's key management client (TS 33.180 5.3): fetches the KMS certificate and the identity's
 * keys with the user's access token, and creates and opens the keys it sends and receives. The MCVideo
 * client keys as its MCVideo ID; the KMS init names the account ([account], the MCPTT ID). Received I_MESSAGEs are
 * fresh within [skew] seconds (RFC 3830 5.4, "set by the local policy"); warnings about accepted keys go to [warn].
 */
class KeyClient(private val http: HttpClient, kmsUri: String, val identity: String, private val account: String,
                val profile: WireProfile, private val skew: Long = Mikey.DEFAULT_CLOCK_SKEW_S,
                private val warn: (String) -> Unit = {}, kmsId: String? = null,
                private val transportKeys: () -> UserTransportKeys? = { null },
                /** The KMS is reached through the home HTTP proxy (`kms_via_proxy`): a TrK is then required. */
                private val viaProxy: Boolean = false) {
    private val kmsUri = kmsUri.trimEnd('/')
    /** The KMS URI its documents carry (clause 3.1, `kms_id`), by default the address. */
    private val kmsId = kmsId ?: this.kmsUri
    @Volatile var kms: KmsPublic? = null
        private set
    @Volatile private var keys: IdentityKeys? = null
    private var token: String? = null
    private val received = mutableMapOf<String, Long>()

    init { require(this.kmsUri.startsWith("https://")) { "the KMS must be an HTTPS URI" } }

    private fun url(operation: String) =
        if (operation == "keyprov") "$kmsUri/keymanagement/identity/v1/keyprov/" + urlEncode(identity)
        else "$kmsUri/keymanagement/identity/v1/$operation"

    /**
     * TS 33.180 clause 5.3.3 steps 1 and 2: a POST for key material with the access token, and the signed KMS request
     * when there is one (annex D.2.2: "accompanied with an XML payload MIME type").
     */
    private suspend fun fetch(operation: String, signed: String? = null): String {
        val headers = mutableMapOf("Accept" to "application/xml", "Cache-Control" to "no-store",
            "Authorization" to "Bearer ${token ?: throw KeyError("no access token")}")
        if (signed != null) headers["Content-Type"] = "application/xml"
        val response = http.request("POST", url(operation), headers, signed?.toByteArray(Charsets.UTF_8))
        if (response.status != 200) throw KeyError("KMS $operation refused: ${response.status}")
        if (response.body.isEmpty() || response.body.size > 262144) throw KeyError("invalid KMS response size")
        return String(response.body)
    }

    /** Fetch the KMS certificate and this identity's keys with [accessToken]. */
    suspend fun provision(accessToken: String) {
        token = accessToken
        refresh()
    }

    /** Whether the last exchange was signed under this identity's InK or TrK (clause 5.3.2). */
    @Volatile var signedExchange = false
        private set

    private suspend fun refresh() {
        // TS 33.180 clause 5.3.2 and annex D.2.2: with this identity's InK or TrK, signed requests, and the responses
        // verified and their keys unwrapped with them; the keys are looked up for this exchange only (clause 5.11.1).
        val transport = transportKeys()?.takeIf { it.integrity != null }
        // Clause 5.3.2: "If the KMS does connect to and employ the use of the HTTP proxy, then for public safety users the
        // TrK shall be used as defined in clause 9.3.3".
        if (viaProxy && transport?.trk == null)
            throw KeyError("the KMS is reached through the HTTP proxy, which needs a TrK for $identity")
        val init = fetch("init", transport?.let { KmsDocuments.requestXml(account, kmsId, url("init"), Mikey.nowNtp(), it) })
        // The init request names no identity: the KMS answers for the token's user.
        val public = KmsDocuments.parseInit(init, account, kmsId, url("init"), Mikey.nowNtp(), transport)
        val keyprov = fetch("keyprov", transport?.let { KmsDocuments.requestXml(identity, kmsId, url("keyprov"), Mikey.nowNtp(), it) })
        // The UID form of this KMS's keys decides the identifier of every ECCSI and SAKKE operation under it (UidForm).
        val provisioned = KmsDocuments.parseKeyProv(keyprov, public, identity, url("keyprov"), Mikey.nowNtp(), transport, detectUidForm = true)
        signedExchange = transport != null
        keys = provisioned
        kms = public.copy(uidForm = provisioned.uidForm)
    }

    /** The keys for now, fetched again when the key period has turned. */
    private suspend fun current(): Pair<KmsPublic, IdentityKeys> {
        var k = kms ?: throw KeyError("KMS identity has not been provisioned")
        if (keys!!.periodNumber != k.periodNumber(Mikey.nowNtp())) { refresh(); k = kms!! }
        return k to keys!!
    }

    /** When this identity's keys expire (Unix seconds): the end of the key period. */
    fun periodEnd(): Long { val k = kms!!; return (keys!!.periodNumber + 1) * k.period + k.offset - Mikey.NTP_EPOCH }

    /** A new CSK for the server [recipient] (TS 33.180 9.4), and its I_MESSAGE in base64. */
    suspend fun makeCsk(recipient: String): Pair<KeyMessage, String> {
        val (k, id) = current()
        val now = Mikey.nowNtp()
        val key = Mikey.newKey(2, now, KeyProperties(2, 1, now - Mikey.NTP_EPOCH, periodEnd()))
        return key to java.util.Base64.getEncoder().encodeToString(Mikey.create(k, id, recipient, key, profile))
    }

    /** A new PCK for [recipient] (TS 33.180 7.4), and its I_MESSAGE in base64 for a=key-mgmt. */
    suspend fun makePck(recipient: String): Pair<KeyMessage, String> {
        val (k, id) = current()
        val key = Mikey.newKey(1, Mikey.nowNtp())
        return key to java.util.Base64.getEncoder().encodeToString(Mikey.create(k, id, recipient, key))
    }

    /** Open a PCK offered by [expected] (its initiator, authenticated), refusing a replay; key parameters allowed. */
    suspend fun acceptPck(expected: String, encoded: String): KeyMessage {
        val message = decode(encoded)
        val (k, id) = current()
        val now = Mikey.nowNtp()
        val key = Mikey.open(k, id, expected, message, now, profile, skew, warn = warn)
        // TS 33.180 E.1.2: an I_MESSAGE may carry key parameters (E.6); a PCK's must allow its use now (E.6.4, E.6.9,
        // E.6.10).
        val p = key.properties
        if (key.purpose != 1 || (p != null && p.type != 1)) throw KeyError("received key is not a PCK")
        if (p != null) {
            val unix = now - Mikey.NTP_EPOCH
            val activation = if (p.activation != 0L) p.activation else key.ntpSeconds - Mikey.NTP_EPOCH
            if (p.status and 1L == 0L || unix < activation || (p.expiry != 0L && unix >= p.expiry))
                throw KeyError("key inactive, expired or revoked")
        }
        synchronized(received) {
            val now = Mikey.nowNtp()
            received.entries.removeAll { it.value <= now }
            val tag = "pck:$expected:${key.csbId}"
            if (tag in received) throw KeyError("duplicate or replayed PCK")
            // A replayed I_MESSAGE stays fresh for the timestamp window (the RFC 3830 5.4 clock skew either side).
            received[tag] = key.ntpSeconds + 2 * skew
        }
        return key
    }

    /** Open the GMK of [group] from the GMS [gms] in a GKTP document (TS 33.180 5.7). */
    suspend fun openGroup(gms: String, group: String, xml: String): KeyMessage =
        openGroupMessage(gms, group, KmsDocuments.parseGktp(xml, group))

    /**
     * Open [group]'s GMK from the GMS [gms] (TS 33.180 5.7). The I_MESSAGE's CSB-ID is this user's GUK-ID;
     * the key returned carries the GMK-ID instead (F.1.3).
     */
    suspend fun openGroupMessage(gms: String, group: String, message: ByteArray): KeyMessage {
        val (k, id) = current()
        val now = Mikey.nowNtp()
        val key = Mikey.open(k, id, gms, message, now, profile, skew, warn = warn)
        val p = key.properties ?: throw KeyError("missing key properties")
        if (key.purpose != 0 || p.type != 0) throw KeyError("received key is not a GMK")
        // A GMK not yet active is kept for its Activation Time (TS 33.180 E.6.4): GroupKeys picks the key to use.
        if (p.status and 1L == 0L || (p.expiry != 0L && now - Mikey.NTP_EPOCH >= p.expiry)) throw KeyError("key expired or revoked")
        if (group !in p.groups) throw KeyError("key not authorised for the group")
        return KeyMessage(key.key, key.random, SrtpKeys.gukId(key.key, key.csbId, identity), key.ntpSeconds, p)
    }

    companion object {
        /** Percent-encoding of all but the RFC 3986 unreserved characters, as the KMS expects. */
        fun urlEncode(text: String): String = buildString {
            for (b in text.toByteArray()) {
                val c = b.toInt() and 0xff
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.~") append(c.toChar())
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }

        /**
         * RFC 4825 section 6.2: a path segment of an XCAP document selector (an XUI, a group ID): as [urlEncode], ':' and
         * '@' kept (permitted in a path segment, RFC 3986 section 3.3), as in TS 24.481 clause 7.2.10.2 and its annex A.
         */
        fun xcapSegment(text: String): String =
            text.split(':').joinToString(":") { part -> part.split('@').joinToString("@") { urlEncode(it) } }

        fun decode(encoded: String): ByteArray = try { java.util.Base64.getDecoder().decode(encoded) } catch (e: IllegalArgumentException) { throw KeyError("invalid MIKEY base64") }
    }
}

/**
 * Each group's GMKs (TS 33.180 5.7): a client may hold several active GMKs for a group and uses the one with the most
 * recent Activation Time (E.6.4; 0 means the I_MESSAGE's timestamp); one still to become active waits for its time.
 */
class GroupKeys(private val clock: () -> Long = { Mikey.nowNtp() - Mikey.NTP_EPOCH }) {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, List<KeyMessage>>()

    /** Keep [key] for [group], dropping the expired ones and an earlier copy of the same GMK. */
    fun add(group: String, key: KeyMessage) {
        val now = clock()
        keys.compute(group) { _, held -> (held.orEmpty().filter { it.csbId != key.csbId && !expired(it, now) } + key) }
    }

    /** The GMK [group] uses now, or null when none is active yet. */
    fun active(group: String): KeyMessage? {
        val now = clock()
        return keys[group].orEmpty().filter { activation(it) <= now && !expired(it, now) }.maxByOrNull { activation(it) }
    }

    /** Whether a GMK of [group] is held, active or not. */
    fun held(group: String): Boolean = keys[group].orEmpty().any { !expired(it, clock()) }

    /** Each group's GMK-ID in use. */
    val ids: Map<String, Long> get() = keys.keys.mapNotNull { g -> active(g)?.let { g to it.csbId } }.toMap()

    private fun activation(key: KeyMessage): Long =
        key.properties?.activation?.takeIf { it != 0L } ?: (key.ntpSeconds - Mikey.NTP_EPOCH)

    private fun expired(key: KeyMessage, now: Long): Boolean = key.properties?.expiry?.let { it != 0L && now >= it } ?: false
}
