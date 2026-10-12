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
 * period), in plain KeyContentType, bound to the request and at most five minutes old.
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

    private fun time(text: String): Long = try { Instant.parse(text).epochSecond + Mikey.NTP_EPOCH } catch (e: Exception) { throw KeyError("invalid timestamp") }

    private fun bounds(e: Element, nowNtp: Long) {
        e.optional("Revoked")?.text()?.let { if (it != "false" && it != "0") throw KeyError("revoked security key") }
        e.optional("ValidFrom")?.text()?.let { if (nowNtp < time(it)) throw KeyError("key not yet valid") }
        e.optional("ValidTo")?.text()?.let { if (nowNtp >= time(it)) throw KeyError("expired security key") }
    }

    /** The KmsMessage of a response for [user] from [kms] to [request], checked. */
    private fun envelope(xml: String, user: String, kms: String, request: String, nowNtp: Long): Element {
        if (xml.contains("<!DOCTYPE")) throw KeyError("forbidden DTD")
        var root = Xml.parse(xml)
        if (root.localName == "SignedKmsResponse") root = root.one("KmsResponse")
        if (root.localName != "KmsResponse" || root.namespaceURI != NS || root.getAttribute("Version") != "1.0.0") throw KeyError("invalid KMS response")
        if (root.field("UserUri") != user || root.field("KmsUri") != kms || root.field("ClientReqUrl") != request) throw KeyError("KMS response binding mismatch")
        if (kotlin.math.abs(time(root.field("Time")) - nowNtp) > 300) throw KeyError("stale KMS response")
        if (root.optional("KmsError") != null) throw KeyError("KMS returned an error")
        return root.one("KmsMessage")
    }

    fun parseInit(xml: String, user: String, kms: String, request: String, nowNtp: Long): KmsPublic {
        val cert = envelope(xml, user, kms, request, nowNtp).one("KmsInit").one("KmsCertificate")
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

    private fun keyField(set: Element, name: String): ByteArray {
        val e = set.one(name)
        val type = e.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type")
        val prefix = type.substringBefore(':', "")
        if (type.substringAfter(':') != "KeyContentType" || e.lookupNamespaceURI(prefix.ifEmpty { null }) != NS) throw KeyError("unsupported encrypted or invalid key content")
        return unhex(e.text())
    }

    /** The key set for [user] under [kms], validated (§5.1.2 of RFC 6507, §6.1.2 of RFC 6508). */
    fun parseKeyProv(xml: String, kms: KmsPublic, user: String, request: String, nowNtp: Long): IdentityKeys {
        val set = envelope(xml, user, kms.kmsUri, request, nowNtp).one("KmsKeyProv").one("KmsKeySet")
        val uid = Mikey.uid(user, kms, nowNtp)
        if (set.getAttribute("Version") != "1.1.0" || set.field("KmsUri") != kms.kmsUri || set.field("UserUri") != user ||
            set.field("UserID") != uid) throw KeyError("KMS keyset identity mismatch")
        bounds(set, nowNtp)
        val keys = try {
            IdentityKeys(user, set.field("KeyPeriodNo").toLong(), BigInteger(1, keyField(set, "UserSigningKeySSK").also { if (it.size != 32) throw KeyError("invalid SSK size") }),
                Eccsi.P256.decode(keyField(set, "UserPubTokenPVT")), Sakke.E.decode(keyField(set, "UserDecryptKey")))
        } catch (e: IllegalArgumentException) { throw KeyError("invalid key material") }
        if (keys.periodNumber != kms.periodNumber(nowNtp)) throw KeyError("identity key period mismatch")
        val id = uid.toByteArray()
        if (!Eccsi(kms.kpak).validate(id, keys.ssk, keys.pvt)) throw KeyError("invalid ECCSI identity key")
        if (!Sakke(kms.z).validate(id, keys.rsk)) throw KeyError("invalid SAKKE identity key")
        return keys
    }

    /** The XCAP selector of [group]'s GKTPs (TS 24.481, TS 33.180 5.7), as a GKTP subscription names it. */
    fun gktpSelector(group: String): String =
        "org.3gpp.MCPTT-GKTP/global/byGroupID/" + KeyClient.urlEncode(group) + "/~~/group/list-service/mgktp:GKTPs?xmlns(mgktp=urn:3gpp:ns:mcpttGKTP:1.0)"

    /** The resource list of a GKTP subscription for [group]. */
    fun gktpResourceList(group: String): String = Xml.DECLARATION +
        "<resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-lists\"><list><entry uri=\"" + Xml.escape(gktpSelector(group)) + "\"/></list></resource-lists>"

    /** The I_MESSAGE in an xcap-diff NOTIFY (RFC 5874) of [group]'s GKTPs under [xcapRoot]. */
    fun parseGktpXcapDiff(xml: String, group: String, xcapRoot: String, nowNtp: Long): ByteArray {
        if (xml.contains("<!DOCTYPE")) throw KeyError("forbidden DTD")
        val root = Xml.parse(xml)
        if (root.localName != "xcap-diff" || root.getAttribute("xcap-root") != xcapRoot) throw KeyError("XCAP diff root binding mismatch")
        val element = root.child("element") ?: throw KeyError("no GKTP element")
        val exists = element.getAttribute("exists")
        if (element.getAttribute("sel") != gktpSelector(group) || (exists.isNotEmpty() && exists != "true" && exists != "1")) throw KeyError("XCAP diff selector mismatch")
        val gktp = element.child("GKTPs")?.child("GMK-GKTPs")?.child("GKTP") ?: throw KeyError("missing GKTP")
        if (gktp.getAttribute("id").isEmpty()) throw KeyError("missing GKTP id")
        gktp.getAttribute("activationtime").takeIf { it.isNotEmpty() }?.let { if (time(it) > nowNtp) throw KeyError("GKTP not active") }
        return unhex(gktp.text())
    }

    /** The GKTP document's I_MESSAGE for [group] (TS 33.180 5.7), its activation time passed. */
    fun parseGktp(xml: String, group: String, nowNtp: Long): ByteArray {
        val root = Xml.parse(xml)
        val list = root.child("list-service") ?: throw KeyError("invalid GKTP document")
        if (root.localName != "group" || list.getAttribute("uri") != group) throw KeyError("GKTP group mismatch")
        val gktp = list.child("GKTPs")?.child("GMK-GKTPs")?.child("GKTP") ?: throw KeyError("missing GKTP")
        if (gktp.getAttribute("id").isEmpty()) throw KeyError("missing GKTP id")
        gktp.getAttribute("activationtime").takeIf { it.isNotEmpty() }?.let { if (time(it) > nowNtp) throw KeyError("GKTP not active") }
        return unhex(gktp.text())
    }
}

/**
 * One identity's key management client (TS 33.180 5.3): fetches the KMS certificate and the identity's
 * keys with the user's access token, and creates and opens the keys it sends and receives. The MCVideo
 * client keys as its MCVideo ID; the KMS init names the account ([account], the MCPTT ID).
 */
class KeyClient(private val http: HttpClient, kmsUri: String, val identity: String, private val account: String, val profile: WireProfile) {
    private val kmsUri = kmsUri.trimEnd('/')
    @Volatile var kms: KmsPublic? = null
        private set
    @Volatile private var keys: IdentityKeys? = null
    private var token: String? = null
    private val received = mutableMapOf<String, Long>()

    init { require(this.kmsUri.startsWith("https://")) { "the KMS must be an HTTPS URI" } }

    private fun url(operation: String) =
        if (operation == "keyprov") "$kmsUri/keymanagement/identity/v1/keyprov/" + urlEncode(identity)
        else "$kmsUri/keymanagement/identity/v1/$operation"

    private suspend fun fetch(operation: String): String {
        val response = http.request("POST", url(operation), mapOf("Accept" to "application/xml", "Cache-Control" to "no-store",
            "Authorization" to "Bearer ${token ?: throw KeyError("no access token")}"))
        if (response.status != 200) throw KeyError("KMS $operation refused: ${response.status}")
        if (response.body.isEmpty() || response.body.size > 262144) throw KeyError("invalid KMS response size")
        return String(response.body)
    }

    /** Fetch the KMS certificate and this identity's keys with [accessToken]. */
    suspend fun provision(accessToken: String) {
        token = accessToken
        refresh()
    }

    private suspend fun refresh() {
        val public = KmsDocuments.parseInit(fetch("init"), account, kmsUri, url("init"), Mikey.nowNtp())
        keys = KmsDocuments.parseKeyProv(fetch("keyprov"), public, identity, url("keyprov"), Mikey.nowNtp())
        kms = public
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

    /** Open a PCK offered by [expected] (its initiator, authenticated), refusing a replay. */
    suspend fun acceptPck(expected: String, encoded: String): KeyMessage {
        val message = decode(encoded)
        val (k, id) = current()
        val key = Mikey.open(k, id, expected, message, Mikey.nowNtp())
        if (key.purpose != 1 || key.properties != null) throw KeyError("received key is not a PCK")
        synchronized(received) {
            val now = Mikey.nowNtp()
            received.entries.removeAll { it.value <= now }
            val tag = "pck:$expected:${key.csbId}"
            if (tag in received) throw KeyError("duplicate or replayed PCK")
            received[tag] = key.ntpSeconds + 600
        }
        return key
    }

    /** Open the GMK of [group] from the GMS [gms] in a GKTP document (TS 33.180 5.7). */
    suspend fun openGroup(gms: String, group: String, xml: String): KeyMessage =
        openGroupMessage(gms, group, KmsDocuments.parseGktp(xml, group, Mikey.nowNtp()))

    /**
     * Open [group]'s GMK from the GMS [gms] (TS 33.180 5.7). The I_MESSAGE's CSB-ID is this user's GUK-ID;
     * the key returned carries the GMK-ID instead (F.1.3).
     */
    suspend fun openGroupMessage(gms: String, group: String, message: ByteArray): KeyMessage {
        val (k, id) = current()
        val now = Mikey.nowNtp()
        val key = Mikey.open(k, id, gms, message, now, profile)
        val p = key.properties ?: throw KeyError("missing key properties")
        if (key.purpose != 0 || p.type != 0) throw KeyError("received key is not a GMK")
        val unix = now - Mikey.NTP_EPOCH
        val activation = if (p.activation != 0L) p.activation else key.ntpSeconds - Mikey.NTP_EPOCH
        if (p.status and 1L == 0L || unix < activation || (p.expiry != 0L && unix >= p.expiry)) throw KeyError("key inactive, expired or revoked")
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

        fun decode(encoded: String): ByteArray = try { java.util.Base64.getDecoder().decode(encoded) } catch (e: IllegalArgumentException) { throw KeyError("invalid MIKEY base64") }
    }
}
