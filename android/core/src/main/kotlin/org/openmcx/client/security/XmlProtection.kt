//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.SAXParserFactory

/**
 * Application signalling security: confidentiality of sensitive XML content under an XML Protection Key (XPK), and
 * integrity of whole XML MIME bodies. Between a client and its server the XPK is the client's CSK, and the keys derive
 * from it and the client's MC service user ID (TS 33.180 annex F.1.4).
 *
 * A port of the shared C++ core (include/mcx/xml_security.hpp and src/security/xml_security.cpp of the server
 * repository) that the servers and the Linux client use, with the same wire format: an encrypted document is
 * serialised as libxml2's xmlDocDumpMemory writes it (XML declaration on its own line, the namespace declarations of an
 * element before its attributes, both in document order, empty elements as "<a/>", the same character escaping), and
 * the signatures document is the same text. The canonicalization is [KmsSignature]'s (libxml2's, on plain DOM).
 * Differences from the shared core, all refusals of input it accepts: a DOCTYPE (no DTD is read, as [KmsSignature]),
 * an XML declaration naming an encoding other than UTF-8 (the documents here are Kotlin strings), an unbound namespace
 * prefix (libxml2 only warns). An empty processing instruction is written "<?pi?>" (SAX does not tell "<?pi ?>").
 * The IS proxy's re-encryption (TS 33.180 clause 11.1.3) is a server function and not here.
 *
 * Specifications: TS 33.180 V19.5.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19), TS 24.281
 * V19.6.0 (Rel-19), RFC 4648, RFC 5288, RFC 2104, RFC 2392, RFC 3986, RFC 6839, RFC 7303, W3C XML Signature Syntax and
 * Processing (Second Edition) and Version 1.1, W3C Canonical XML 1.0 and 1.1, W3C Exclusive XML Canonicalization 1.0.
 */
object XmlProtection {
    private const val XENC = "http://www.w3.org/2001/04/xmlenc#"
    private const val DSIG = "http://www.w3.org/2000/09/xmldsig#"
    private const val GCM = "http://www.w3.org/2009/xmlenc11#aes128-gcm"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"

    private fun need(condition: Boolean, what: String) { if (!condition) throw KeyError(what) }

    // ------------------------------------------------------------------------------------------------------------
    // Keys (TS 33.180 clause 9.3.3, annex F.1.4).

    /** TS 33.180 clause 9.3.3 and annex F.1.4: an XML Protection Key with its ID and the MC service user ID it is used for. */
    data class Key(
        /** The 128-bit XPK (the CSK). */
        val xpk: ByteArray,
        /** XPK-ID: the CSK-ID (32 bits). */
        val id: Long,
        /** MC service user ID of the client (P0 of TS 33.180 annex F.1.4). */
        val user: String,
    ) {
        init {
            require(xpk.size == 16) { "the XPK of signalling protection is 128 bits" }
            require(id in 0..0xffffffffL) { "the XPK-ID is 32 bits" }
        }
        override fun equals(other: Any?) = other is Key && xpk.contentEquals(other.xpk) && id == other.id && user == other.user
        override fun hashCode() = (xpk.contentHashCode() * 31 + id.hashCode()) * 31 + user.hashCode()
        override fun toString() = "Key(id=%08x, user=%s)".format(id, user)
    }

    /**
     * TS 33.180 annex F.1.4 with TS 33.220 annex B: the key derived from an XPK of 128 bits (the CSK) or 256 bits (the
     * TrK of KMS traffic, clause 9.3.3) with FC 0x51 (confidentiality) or 0x52 (integrity), P0 the MC service user ID
     * and P1 the XPK-ID; as long as the XPK. The derivation is [TransportKeys.protectionKey]: KDF of TS 33.220 annex B,
     * FC = 0x51 or 0x52, P0 = MC service user ID, P1 = XPK-ID (4 octets, L1 = 0x00 0x04); "Where the XPK is 128-bits
     * ... the 128 least significant bits of the 256 bits of the KDF output", "Where the XPK is 256-bits ... the entire
     * output of the KDF". L0 and L1 are the actual lengths (TS 33.220 annex B.2.0): F.1.4's "(i.e. 0x00 0x17)" for both
     * is a spec defect (reported); for the 4-octet XPK-ID, L1 = 0x00 0x04.
     */
    fun protectionKey(xpk: ByteArray, xpkId: Long, user: String, fc: Int): ByteArray {
        val key = Xpk(xpk, xpkId)
        return try { TransportKeys.protectionKey(key, user, fc) } finally { key.clear() }
    }

    /** TS 33.180 annex F.1.4: the signalling plane confidentiality key (FC = 0x51), the 128 least significant bits. */
    fun confidentialityKey(key: Key): ByteArray = protectionKey(key.xpk, key.id, key.user, TransportKeys.CONFIDENTIALITY)

    /** TS 33.180 annex F.1.4: the signalling plane integrity key (FC = 0x52), the 128 least significant bits. */
    fun integrityKey(key: Key): ByteArray = protectionKey(key.xpk, key.id, key.user, TransportKeys.INTEGRITY)

    /** TS 24.379 clause 6.6.2.3.4 step f): the base64 encoding of the 32-bit XPK-ID (the ds:KeyName and "key-id"). */
    private fun keyName(id: Long): String = Base64.getEncoder().encodeToString(
        byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()))

    /** The random source of the 96-bit IVs; tests may replace it. */
    internal var ivSource: (ByteArray) -> Unit = { random.nextBytes(it) }
    private val random = SecureRandom()

    private fun b64(data: ByteArray, from: Int = 0, to: Int = data.size): String =
        Base64.getEncoder().encodeToString(data.copyOfRange(from, to))

    /** Base64 decoding (RFC 4648 section 4), ignoring white space, as OpenSSL's EVP_DecodeBlock; at most 1 MiB. */
    private fun unbase64(text: String): ByteArray {
        val clean = text.count { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        need(clean in 1..(1 shl 20) && clean % 4 == 0, "invalid base64")
        return KmsSignature.unbase64(text)
    }

    /**
     * TS 33.180 clause 9.3.4.2: AES-128-GCM with a random 96-bit IV: IV (12) || ciphertext || tag (16), as xmlenc 1.1
     * lays out a CipherValue.
     */
    private fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(ivSource)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    /**
     * TS 33.180 clause 9.3.4.2: AES-128-GCM decryption of IV (12) || ciphertext || tag (16); throws if the tag does not
     * verify.
     */
    private fun open(key: ByteArray, data: ByteArray): ByteArray {
        need(data.size >= 28, "truncated XML ciphertext")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return try { cipher.doFinal(data, 12, data.size - 12) } catch (e: java.security.GeneralSecurityException) {
            throw KeyError("XML content authentication failed")
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Confidentiality (TS 33.180 clause 9.3.4; TS 24.379 clause 6.6.2; TS 24.282 clause 6.5.2).

    /**
     * TS 33.180 clauses 9.3.4.2 and 9.3.4.3: the XML elements whose content is encrypted (an element carries
     * type="Normal" in clear and type="Encrypted" around its <EncryptedData>), and the (element, attribute) pairs whose
     * URI value is encrypted; by local name.
     */
    data class Profile(val elements: Set<String>, val uriAttributes: Set<Pair<String, String>>)

    private val mcpttProfile = Profile(
        setOf("mcptt-access-token", "mcptt-request-uri", "mcptt-calling-user-id", "mcptt-called-party-id",
            "mcptt-calling-group-id", "mcptt-client-id", "originated-by", "associated-group-id",
            "location-of-functional-alias-URI", "emergency-ind", "alert-ind", "imminentperil-ind",
            // TS 24.379 annex F.3: the location reported.
            "CurrentServingEcgi", "NeighbouringEcgi", "MbmsSaId", "MbsfnArea", "CL_5GMbsfsaArea", "CurrentServingNcgi",
            "CL_NeighbouringNcgi", "CL_BearingAndSpeed", "longitude", "latitude", "altitude", "horizontalaccuracy",
            "verticalaccuracy"),
        setOf("entry" to "uri", "presence" to "entity", "affiliation" to "group", "functionalAlias" to "functionalAliasID",
            "user" to "entity"))

    private val mcvideoProfile = Profile(
        mcpttProfile.elements.map { if (it.startsWith("mcptt-")) "mcvideo-" + it.substring(6) else it }.toSet(),
        mcpttProfile.uriAttributes)

    /**
     * TS 24.379 clause 4.8 and TS 24.281 clause 4.8: the sensitive application data of MC service signalling, in the
     * service's element names: <mcptt-request-uri> or, with the info prefix "mcvideo", <mcvideo-request-uri> and so on;
     * location (TS 24.379 annex F.3) and the URI attributes of affiliation, private call, conference and functional
     * alias bodies.
     */
    fun signallingProfile(infoPrefix: String = "mcptt"): Profile = when (infoPrefix) {
        "mcptt" -> mcpttProfile
        "mcvideo" -> mcvideoProfile
        else -> throw IllegalArgumentException("no signalling protection profile for this service")
    }

    /**
     * TS 33.180 clause 9.3.4.3 and TS 24.282 clause 6.5.2.3.4: the confidentiality protection domain of an encrypted
     * URI, sip:<base64 ciphertext>;iv=<base64 IV>;key-id=<base64 XPK-ID>;alg=128-aes-gcm@<confidentiality protection
     * domain>.
     */
    const val ENCRYPTION_DOMAIN = "mc1-encryption.3gppnetwork.org"

    /**
     * TS 24.379 clause 6.6.2.4.1: a URI in an attribute is confidentiality protected if it has the domain name for
     * confidentiality protection (and the ";alg=128-aes-gcm" of clause 6.6.2.3.4).
     */
    fun isEncryptedUri(uri: String): Boolean =
        uri.startsWith("sip:") && uri.endsWith("@$ENCRYPTION_DOMAIN") && uri.contains(";alg=128-aes-gcm")

    /**
     * TS 24.379 clause 6.6.2.3.4 and TS 33.180 clause 9.3.4.3: encrypts a URI in an XML attribute: "sip:"
     * base64(ciphertext || tag) ";iv=" base64(IV) ";key-id=" base64(XPK-ID) ";alg=128-aes-gcm@" and the
     * confidentiality protection domain.
     */
    fun encryptUri(uri: String, key: Key): String {
        val contentKey = confidentialityKey(key)
        val sealed = try { seal(contentKey, uri.toByteArray(Charsets.UTF_8)) } finally { Arrays.fill(contentKey, 0) }
        return "sip:" + b64(sealed, 12) + ";iv=" + b64(sealed, 0, 12) + ";key-id=" + keyName(key.id) +
            ";alg=128-aes-gcm@" + ENCRYPTION_DOMAIN
    }

    /**
     * TS 24.379 clause 6.6.2.4.3 and TS 33.180 clause 9.3.4.3: decrypts an encrypted URI of an XML attribute; throws if
     * it was encrypted under another XPK or does not authenticate.
     */
    fun decryptUri(uri: String, key: Key): String {
        need(isEncryptedUri(uri), "not an encrypted URI")
        val body = uri.substring(4, uri.length - 1 - ENCRYPTION_DOMAIN.length)
        val first = body.indexOf(';')
        need(first >= 0, "encrypted URI without parameters")
        var iv = ""
        var id = ""
        for (param in body.substring(first + 1).split(';')) {
            if (param.startsWith("iv=")) iv = param.substring(3)
            else if (param.startsWith("key-id=")) id = param.substring(7)
        }
        need(id == keyName(key.id), "URI encrypted under another XPK")
        val ivOctets = unbase64(iv)
        need(ivOctets.size == 12, "invalid URI IV")
        val data = ivOctets + unbase64(body.substring(0, first))
        val contentKey = confidentialityKey(key)
        val plain = try { open(contentKey, data) } finally { Arrays.fill(contentKey, 0) }
        return String(plain, Charsets.UTF_8).also { Arrays.fill(plain, 0) }
    }

    /**
     * TS 24.379 clauses 6.6.2.3.3 and 6.6.2.3.4, TS 33.180 clause 9.3.4.2: encrypts the URI attributes and the content
     * of the elements of the profile whose "type" is "Normal", replacing each content by <EncryptedData> (xmlenc 1.1,
     * http://www.w3.org/2009/xmlenc11#aes128-gcm, the base64 XPK-ID as ds:KeyName) and setting "type" to "Encrypted".
     */
    fun encrypt(xml: String, key: Key, profile: Profile): String {
        val doc = Tree.parse(xml)
        val contentKey = confidentialityKey(key)
        try {
            eachElement(listOf(doc.root)) { element ->
                var at = 0
                while (at < element.attrs.size) {
                    val attribute = element.attrs[at++]
                    if ((element.local to attribute.local) !in profile.uriAttributes) continue
                    val value = attribute.value
                    if (value.isEmpty() || isEncryptedUri(value)) continue
                    element.setProp(attribute.local, encryptUri(value, key))
                }

                if (element.local !in profile.elements) return@eachElement false
                if (element.getProp("type") != "Normal") return@eachElement false

                // The content: every child serialised (as libxml2's xmlNodeDump), then replaced by <EncryptedData>.
                val plain = StringBuilder()
                for (node in element.children) Tree.Writer(doc, plain, document = false).node(node)
                val octets = plain.toString().toByteArray(Charsets.UTF_8)
                val sealed = try { seal(contentKey, octets) } finally { Arrays.fill(octets, 0) }
                element.children.clear()

                val encrypted = Tree.Element("EncryptedData", XENC, "EncryptedData").apply {
                    namespaces += "" to XENC
                    attrs += Tree.Attr("Type", null, "Type", "http://www.w3.org/2001/04/xmlenc#Content")
                }
                encrypted.add(Tree.Element("EncryptionMethod", XENC, "EncryptionMethod").apply {
                    attrs += Tree.Attr("Algorithm", null, "Algorithm", GCM)
                })
                encrypted.add(Tree.Element("ds:KeyInfo", DSIG, "KeyInfo").apply {
                    namespaces += "ds" to DSIG
                    add(Tree.Element("ds:KeyName", DSIG, "KeyName").apply { add(Tree.Text(keyName(key.id), false)) })
                })
                encrypted.add(Tree.Element("CipherData", XENC, "CipherData").apply {
                    add(Tree.Element("CipherValue", XENC, "CipherValue").apply { add(Tree.Text(b64(sealed), false)) })
                })
                element.add(encrypted)
                element.setProp("type", "Encrypted")
                true
            }
        } finally {
            Arrays.fill(contentKey, 0)
        }
        return doc.text()
    }

    /**
     * TS 24.379 clauses 6.6.2.4.1 to 6.6.2.4.3: decrypts every encrypted URI attribute and every <EncryptedData>
     * element content under the key and sets "type" back to "Normal"; throws on the first failure (TS 24.282 clause
     * 6.5.2.4.2: 403, warning 140).
     */
    fun decrypt(xml: String, key: Key): String {
        val doc = Tree.parse(xml)
        val contentKey = confidentialityKey(key)
        try {
            eachElement(listOf(doc.root)) { element ->
                var at = 0
                while (at < element.attrs.size) {
                    val attribute = element.attrs[at++]
                    if (isEncryptedUri(attribute.value)) element.setProp(attribute.local, decryptUri(attribute.value, key))
                }

                val encrypted = element.child("EncryptedData", XENC) ?: return@eachElement false
                encrypted.child("KeyInfo", DSIG)?.child("KeyName", DSIG)?.let {
                    need(it.content() == keyName(key.id), "XML content encrypted under another XPK")
                }
                encrypted.child("EncryptionMethod", XENC)?.let {
                    need(it.getProp("Algorithm") == GCM, "unsupported XML encryption algorithm")
                }
                val value = encrypted.child("CipherData", XENC)?.child("CipherValue", XENC)
                need(value != null, "EncryptedData without CipherValue")
                val plain = open(contentKey, unbase64(value!!.content()))
                element.children.remove(encrypted)
                if (plain.isNotEmpty()) {
                    val nodes = try { Tree.parseInContext(element, String(plain, Charsets.UTF_8)) } finally { Arrays.fill(plain, 0) }
                    for (node in nodes) element.add(node)
                }
                element.setProp("type", "Normal")
                true
            }
        } finally {
            Arrays.fill(contentKey, 0)
        }
        return doc.text()
    }

    /** TS 24.379 clause 6.6.2.4.1: whether a document holds any <EncryptedData> element or encrypted URI. */
    fun isProtected(xml: String): Boolean = xml.contains("EncryptedData") || xml.contains("@$ENCRYPTION_DOMAIN")

    /**
     * TS 24.379 clause 6.6.2.4.1: the XPK-ID (KeyName, or the key-id of an encrypted URI) of the first confidentiality
     * protected URI or element of a document, which selects the key before decryption; null when the content is in
     * clear.
     */
    fun keyId(xml: String): Long? {
        if (!isProtected(xml)) return null
        val doc = Tree.parse(xml)
        var name = ""
        eachElement(listOf(doc.root)) { element ->
            if (name.isNotEmpty()) return@eachElement true
            for (attribute in element.attrs) {
                val value = attribute.value
                if (!isEncryptedUri(value)) continue
                val at = value.indexOf(";key-id=")
                if (at >= 0) {
                    val end = value.indexOf(';', at + 8)
                    name = if (end < 0) value.substring(at + 8) else value.substring(at + 8, end)
                    return@eachElement true
                }
            }
            val encrypted = element.child("EncryptedData", XENC) ?: return@eachElement false
            encrypted.child("KeyInfo", DSIG)?.child("KeyName", DSIG)?.let { name = it.content() }
            true
        }
        return if (name.isEmpty()) null else xpkId(name)
    }

    private fun xpkId(name: String): Long {
        val bytes = unbase64(name)
        need(bytes.size == 4, "invalid XPK-ID")
        return bytes.fold(0L) { id, b -> (id shl 8) or (b.toLong() and 0xff) }
    }

    /**
     * Calls the visitor on each element of a sibling list and descends into the children of those for which it returns
     * false (an element that was replaced is not descended into).
     */
    private fun eachElement(nodes: List<Tree.Node>, visitor: (Tree.Element) -> Boolean) {
        var at = 0
        while (at < nodes.size) {
            val node = nodes[at++]
            if (node !is Tree.Element) continue
            val children = node.children.toList()
            if (!visitor(node)) eachElement(children, visitor)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Integrity (TS 33.180 clause 9.3.5; TS 24.379 clause 6.6.3; TS 24.282 clause 6.5.3).

    private const val C14N10 = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"
    private const val EXCLUSIVE = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private const val BASE64_TRANSFORM = "http://www.w3.org/2000/09/xmldsig#base64"
    private const val ENVELOPED_TRANSFORM = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"
    private const val HMAC_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#hmac-sha256"
    private const val SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"

    /**
     * TS 33.180 clause 9.3.5, TS 24.379 clause 6.6.3 and TS 24.282 clause 6.5.3: integrity of whole XML MIME bodies.
     * Each body, named by its Content-ID, gets an xmldsig <Signature> (a SHA-256 digest of the body's octets referenced
     * by its cid URL, and HMAC-SHA256 over the SignedInfo under the integrity key of TS 33.180 annex F.1.4, KeyName the
     * XPK-ID) in a <signatures> document in the given namespace (urn:3gpp:ns:mcpttSignedDoc:1.0).
     */
    data class SignedBody(val contentId: String, val body: String)

    /** RFC 2104 with SHA-256 (TS 33.180 clause 9.3.5: "The HMAC-SHA256 signature method shall be supported"). */
    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** TS 33.180 clause 9.3.5: the HMAC-SHA256 of the data under the integrity key from the XPK (annex F.1.4). */
    private fun signatureMac(key: Key, data: ByteArray): ByteArray {
        val integrity = integrityKey(key)
        return try { hmacSha256(integrity, data) } finally { Arrays.fill(integrity, 0) }
    }

    /**
     * A document for canonicalization ([KmsSignature.parse]: no network, no DTD, at most 1 MiB) at most as deep as the
     * shared core's libxml2 parses without XML_PARSE_HUGE (257 nested elements; its xmlParserMaxDepth).
     */
    private fun dom(xml: String): Document {
        val doc = KmsSignature.parse(xml)
        fun depth(node: Node, level: Int) {
            need(level <= Tree.MAX_DEPTH, "malformed XML")
            var child = node.firstChild
            while (child != null) { if (child is Element) depth(child, level + 1); child = child.nextSibling }
        }
        depth(doc.documentElement, 1)
        return doc
    }

    /** The first child element with the given local name and namespace. */
    private fun child(parent: Node, name: String, ns: String): Element? {
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.localName == name && node.namespaceURI == ns) return node
            node = node.nextSibling
        }
        return null
    }

    private fun childElements(parent: Node): List<Element> {
        val out = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) { if (node is Element) out += node; node = node.nextSibling }
        return out
    }

    /** The value of the first attribute of that local name in any namespace (libxml2 xmlGetProp), or empty. */
    private fun prop(element: Element, name: String): String {
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            if (a.namespaceURI != XMLNS && (a.localName ?: a.name) == name) return a.value
        }
        return ""
    }

    /**
     * XMLDSig Second Edition clause 4.3.1 "Implementations MUST support the REQUIRED canonicalization algorithms" and
     * clause 6.1: the REQUIRED Canonical XML 1.0 and 1.1 (omitting comments), their RECOMMENDED forms with comments,
     * and Exclusive XML Canonicalization 1.0 (clause 6.5: it "may be used"), with and without comments; null for any
     * other identifier.
     */
    private fun canonicalizationOf(algorithm: String) = KmsSignature.canonicalizationOf(algorithm)

    /**
     * XMLDSig Second Edition clause 4.3.1: the node-set given to an XML based canonicalisation is "originally formed
     * from the document containing the SignedInfo and currently indicating the SignedInfo, its descendants, and the
     * attribute and namespace nodes of SignedInfo and its descendant elements": the subtree of [apex] (the whole
     * document without one), taken in its document so that an inclusive canonicalisation carries the namespace
     * declarations and xml: attributes in scope there; for Exclusive XML Canonicalization, the InclusiveNamespaces
     * PrefixList.
     */
    private fun c14n(doc: Document, apex: Element?, method: KmsSignature.Canonicalization, prefixes: List<String>): ByteArray =
        KmsSignature.c14n(doc, KmsSignature.NodeSet(apex, null, true), method, prefixes)

    /**
     * Exclusive XML Canonicalization 1.0 clause 3: the prefixes of the ec:InclusiveNamespaces PrefixList of a
     * CanonicalizationMethod or Transform ("#default" names the default namespace); none for the other algorithms.
     */
    private fun inclusivePrefixes(method: Element, canonicalization: KmsSignature.Canonicalization): List<String> {
        if (canonicalization.mode != KmsSignature.Mode.EXCLUSIVE_1_0) return emptyList()
        val list = child(method, "InclusiveNamespaces", EXCLUSIVE) ?: return emptyList()
        return prop(list, "PrefixList").split(' ', '\t', '\r', '\n').filter { it.isNotEmpty() }
    }

    /**
     * RFC 2392 section 2: a "cid" URL is converted to the Content-ID by "removing the "cid:" prefix, converting the %
     * encoded character to their equivalent US-ASCII characters" (the angle brackets are not part of the Content-ID).
     * A malformed escape fails the reference.
     */
    private fun contentIdOf(url: String): String {
        need(url.startsWith("cid:"), "Reference is not a cid URL")
        val raw = url.substring(4).toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < raw.size) {
            val b = raw[at].toInt() and 0xff
            if (b != '%'.code) { out.write(b); at++; continue }
            need(at + 2 < raw.size && hexDigit(raw[at + 1]) >= 0 && hexDigit(raw[at + 2]) >= 0, "invalid % escape in a cid URL")
            out.write(hexDigit(raw[at + 1]) * 16 + hexDigit(raw[at + 2]))
            at += 3
        }
        need(out.size() > 0, "empty cid URL")
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexDigit(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * RFC 2392 section 2 and RFC 3986 section 2.1: the "cid" URL of a Content-ID, with every character that is not
     * unreserved, a sub-delimiter (other than the "&" an XML attribute would need to escape), ":" or "@" %-encoded.
     */
    private fun cidUrl(contentId: String): String {
        val plain = "-._~!$'()*+,;=:@"
        val digits = "0123456789ABCDEF"
        val out = StringBuilder("cid:")
        for (b in contentId.toByteArray(Charsets.UTF_8)) {
            val octet = b.toInt() and 0xff
            val c = octet.toChar()
            if (octet < 0x80 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || plain.indexOf(c) >= 0)) out.append(c)
            else out.append('%').append(digits[octet shr 4]).append(digits[octet and 15])
        }
        return out.toString()
    }

    /**
     * XMLDSig Second Edition clause 4.3.3.2 (the Reference Processing Model) and clause 6.6: the octets a Reference
     * digests: the referenced MIME body after the Reference's Transforms. Supported: the canonicalisation algorithms of
     * canonicalizationOf(), base64 (clause 6.6.2, over octets) and the enveloped signature (clause 6.6.4, REQUIRED; a
     * signature in the signatures body lies outside the body it references, so nothing is removed). An octet stream
     * that a transform needs as a node-set is parsed; a node-set left at the end becomes octets by Canonical XML 1.0
     * (clause 4.3.3.2). Any other transform fails the reference.
     */
    private fun referencedOctets(reference: Element, body: ByteArray): ByteArray {
        val transforms = child(reference, "Transforms", DSIG) ?: return body
        var octets = body
        var nodes: Document? = null
        for (transform in childElements(transforms)) {
            need(transform.localName == "Transform" && transform.namespaceURI == DSIG, "unexpected element in Transforms")
            val algorithm = prop(transform, "Algorithm")
            val method = canonicalizationOf(algorithm)
            if (method != null) {
                val parsed = nodes ?: dom(String(octets, Charsets.UTF_8))
                octets = c14n(parsed, null, method, inclusivePrefixes(transform, method))
                nodes = null
            } else if (algorithm == ENVELOPED_TRANSFORM) {
                if (nodes == null) nodes = dom(String(octets, Charsets.UTF_8))
            } else if (algorithm == BASE64_TRANSFORM) {
                need(nodes == null, "unsupported base64 transform of a node-set")
                octets = unbase64(String(octets, Charsets.UTF_8))
            } else {
                throw KeyError("unsupported Reference transform")
            }
        }
        if (nodes != null) octets = c14n(nodes, null, KmsSignature.Canonicalization(KmsSignature.Mode.C14N_1_0, false), emptyList())
        return octets
    }

    /**
     * XMLDSig Second Edition clause 6.3.1 and XMLDSig 1.1 clauses 4.4.2 and 6.3.1 (TS 33.180 cites the Second Edition,
     * which sets no lower bound; maintainer decision M10 applies the 1.1 rule): the octets of the HMAC-SHA256 value a
     * signature carries: all 32 without HMACOutputLength; otherwise its truncation length, which must be a multiple of
     * 8 ("this specification REQUIRES that the truncation length be a multiple of 8") and not "below the larger of (a)
     * half the underlying hash algorithm's output length, and (b) 80 bits" - for SHA-256 128 bits - nor above the hash.
     */
    private fun macOctets(signatureMethod: Element?): Int {
        val length = signatureMethod?.let { child(it, "HMACOutputLength", DSIG) } ?: return 32
        val text = (length.textContent ?: "").trim(' ', '\t', '\r', '\n')
        need(text.isNotEmpty(), "empty HMACOutputLength")
        need(text.length <= 4 && text.all { it in '0'..'9' }, "invalid HMACOutputLength")
        val bits = text.toInt()
        need(bits % 8 == 0, "HMACOutputLength is not a multiple of 8")
        need(bits >= maxOf(80, 256 / 2), "HMACOutputLength below half the hash output")
        need(bits <= 256, "HMACOutputLength above the hash output")
        return bits / 8
    }

    /**
     * TS 24.379 clause 6.6.3.3.3 step 2 a): the SignedInfo for one body: Exclusive XML Canonicalization 1.0 without
     * comments (TS 33.180 clause 9.3.5 and TS 24.379 "an appropriate algorithm"; XMLDSig Second Edition clause 6.5
     * permits it; clause 6.1 makes Canonical XML 1.0 mandatory to support, which canonicalizationOf does on receipt, not
     * to send), HMAC-SHA256, and its Reference to the body's cid URL with the SHA-256 digest of its octets.
     */
    private fun signedInfo(contentId: String, body: String): String =
        "<ds:SignedInfo><ds:CanonicalizationMethod Algorithm=\"$EXCLUSIVE\"/><ds:SignatureMethod Algorithm=\"$HMAC_SHA256\"/>" +
            "<ds:Reference URI=\"${cidUrl(contentId)}\"><ds:DigestMethod Algorithm=\"$SHA256\"/><ds:DigestValue>" +
            b64(sha256(body.toByteArray(Charsets.UTF_8))) + "</ds:DigestValue></ds:Reference></ds:SignedInfo>"

    /**
     * TS 24.379 clause 6.6.3.3.3 (TS 24.282 clause 6.5.3.3.3) and TS 33.180 clause 9.3.5: the signatures document of
     * TS 24.379 annex F.6 with one ds:Signature (HMAC-SHA256 under the XPK, the XPK-ID as ds:KeyName) per signed body.
     * XMLDSig Second Edition clause 3.1.2: each SignatureValue is the MAC of its SignedInfo canonicalised in the
     * document that carries it (clause 4.3.1), so the document is built first and the values filled in after.
     */
    fun sign(bodies: List<SignedBody>, key: Key, rootNamespace: String): String {
        need(bodies.isNotEmpty() && bodies.size <= 16, "invalid number of bodies to sign")
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><signatures xmlns=\"$rootNamespace\">"
        val open = "<ds:Signature xmlns:ds=\"$DSIG\">"
        val tail = "</ds:SignatureValue><ds:KeyInfo><ds:KeyName>${keyName(key.id)}</ds:KeyName></ds:KeyInfo></ds:Signature>"
        val infos = mutableListOf<String>()
        val draft = StringBuilder(head)
        for (body in bodies) {
            need(body.contentId.isNotEmpty() && body.contentId.none { it in "\"<>&" }, "invalid Content-ID")
            infos += signedInfo(body.contentId, body.body)
            draft.append(open).append(infos.last()).append("<ds:SignatureValue>").append(tail)
        }
        draft.append("</signatures>")

        val doc = dom(draft.toString())
        val out = StringBuilder(head)
        var index = 0
        for (signature in childElements(doc.documentElement)) {
            val info = child(signature, "SignedInfo", DSIG)
            need(info != null && index < infos.size, "signature document construction failed")
            val mac = signatureMac(key, c14n(doc, info, KmsSignature.Canonicalization(KmsSignature.Mode.EXCLUSIVE_1_0, false), emptyList()))
            out.append(open).append(infos[index++]).append("<ds:SignatureValue>").append(b64(mac)).append(tail)
        }
        need(index == infos.size, "signature document construction failed")
        return out.append("</signatures>").toString()
    }

    /** TS 24.379 clause 6.6.3.4.2 step 4: the XPK-ID named in a signatures document (its first ds:KeyName), if any. */
    fun signatureKeyId(signatures: String): Long? {
        val doc = dom(signatures)
        fun first(element: Element): Element? {
            if (element.localName == "KeyName" && element.namespaceURI == DSIG) return element
            for (e in childElements(element)) first(e)?.let { return it }
            return null
        }
        val name = first(doc.documentElement)?.textContent ?: ""
        return if (name.isEmpty()) null else xpkId(name)
    }

    /**
     * TS 24.379 clause 6.6.3.4.2 (TS 24.282 clause 6.5.3.4.2): verifies each ds:Signature against the bodies its
     * References name, reference validation of each of its References (XMLDSig Second Edition clause 3.2.1) then
     * signature validation (clause 3.2.2); throws on any failure (403, warning 139). The SignedInfo is canonicalised by
     * its CanonicalizationMethod (Canonical XML 1.0 or 1.1 or Exclusive XML Canonicalization 1.0, each with or without
     * comments); a SignedInfo without one fails, as does an HMACOutputLength below 128 bits. Returns the Content-IDs
     * verified.
     */
    fun verify(signatures: String, bodies: List<SignedBody>, key: Key): Set<String> {
        val doc = dom(signatures)
        val byId = HashMap<String, String>()
        for (body in bodies) byId[body.contentId] = body.body

        val verified = sortedSetOf<String>()
        for (signature in childElements(doc.documentElement)) {
            need(signature.localName == "Signature" && signature.namespaceURI == DSIG, "unexpected element in the signatures")
            val info = child(signature, "SignedInfo", DSIG)
            val value = child(signature, "SignatureValue", DSIG)
            need(info != null && value != null, "Signature without SignedInfo or SignatureValue")
            child(signature, "KeyInfo", DSIG)?.let { child(it, "KeyName", DSIG) }?.let {
                need(it.textContent == keyName(key.id), "signed under another XPK")
            }

            // TS 24.379 clause 6.6.3.4.2 step 1. TS 33.180 clause 9.3.5 lets CanonicalizationMethod be absent when
            // "known ... through other means" but names no such means (spec defect D4); XMLDSig Second Edition clause
            // 4.3.1: "CanonicalizationMethod is a required element", so a signature without it fails (maintainer
            // decision M8).
            val canonicalizationMethod = child(info!!, "CanonicalizationMethod", DSIG)
            need(canonicalizationMethod != null, "SignedInfo without CanonicalizationMethod")
            val canonicalization = canonicalizationOf(prop(canonicalizationMethod!!, "Algorithm"))
            need(canonicalization != null, "unsupported canonicalization method")
            // TS 24.379 annex F.6.3 NOTE 2: "it is assumed that HMAC-SHA256 is supported by the sender and the receiver".
            val method = child(info, "SignatureMethod", DSIG)
            need(method == null || prop(method, "Algorithm") == HMAC_SHA256, "unsupported signature method")
            val octets = macOctets(method)

            // Reference validation (XMLDSig Second Edition clause 3.2.1), for every Reference of the SignedInfo.
            val targets = mutableListOf<String>()
            for (reference in childElements(info)) {
                if (reference.localName != "Reference" || reference.namespaceURI != DSIG) continue
                val id = contentIdOf(prop(reference, "URI"))
                val target = byId[id]
                need(target != null, "Reference to a missing body")
                val digestMethod = child(reference, "DigestMethod", DSIG)
                need(digestMethod == null || prop(digestMethod, "Algorithm") == SHA256, "unsupported digest method")
                val digest = child(reference, "DigestValue", DSIG)
                need(digest != null, "Reference without DigestValue")
                val expected = sha256(referencedOctets(reference, target!!.toByteArray(Charsets.UTF_8)))
                val received = unbase64(digest!!.textContent ?: "")
                need(MessageDigest.isEqual(received, expected), "reference digest mismatch")
                targets += id
            }
            need(targets.isNotEmpty(), "Signature without Reference")

            // Signature validation (clause 3.2.2): the SignedInfo canonicalised in this document by its own algorithm.
            val expected = signatureMac(key, c14n(doc, info, canonicalization!!, inclusivePrefixes(canonicalizationMethod, canonicalization)))
            val received = unbase64(value!!.textContent ?: "")
            need(received.size == octets && MessageDigest.isEqual(expected.copyOf(octets), received), "signature value mismatch")
            verified += targets
        }
        return verified
    }

    /**
     * TS 33.180 clause 9.3.5 "all XML MIME bodies", with RFC 7303 sections 4.1, 4.2 and 9.2 (application/xml,
     * text/xml) and RFC 6839 section 4.1 (the "+xml" structured syntax suffix): whether a MIME type is an XML MIME
     * type.
     */
    fun isXmlMime(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim(' ', '\t').lowercase(java.util.Locale.ROOT)
        return type == "application/xml" || type == "text/xml" || (type.length > 4 && type.endsWith("+xml"))
    }

    /**
     * XMLDSig Second Edition clauses 4.3.1 and 6.5: the canonical form of a document by the algorithm identifier, or,
     * with an element name, of the first element of that local name with its subtree, taken in its document as a
     * SignedInfo is; for Exclusive XML Canonicalization, the InclusiveNamespaces prefixes. Throws for an algorithm not
     * supported.
     */
    internal fun canonicalize(xml: String, algorithm: String, element: String = "", inclusiveNamespaces: List<String> = emptyList()): ByteArray {
        val method = canonicalizationOf(algorithm) ?: throw KeyError("unsupported canonicalization method")
        val doc = dom(xml)
        var apex: Element? = null
        if (element.isNotEmpty()) {
            fun find(e: Element) {
                if (apex != null) return
                if (e.localName == element) { apex = e; return }
                for (c in childElements(e)) find(c)
            }
            find(doc.documentElement)
            need(apex != null, "no such element")
        }
        return c14n(doc, apex, method, if (method.mode == KmsSignature.Mode.EXCLUSIVE_1_0) inclusiveNamespaces else emptyList())
    }

    // ------------------------------------------------------------------------------------------------------------
    // The document tree the confidentiality protection edits and writes back. Plain DOM keeps no attribute order on
    // every platform (the JDK's sorts them), so the tree is built from SAX events, which keep document order.

    internal object Tree {
        /** libxml2's xmlParserMaxDepth without XML_PARSE_HUGE: the shared core parses at most 257 nested elements. */
        const val MAX_DEPTH = 257

        sealed class Node { var parent: Element? = null }

        class Attr(val qname: String, val uri: String?, val local: String, var value: String)

        class Element(val qname: String, val uri: String?, val local: String) : Node() {
            /** The namespace declarations of the element in document order: prefix ("" the default) and URI. */
            val namespaces = mutableListOf<Pair<String, String>>()
            /** The attributes in document order. */
            val attrs = mutableListOf<Attr>()
            val children = mutableListOf<Node>()

            fun add(node: Node) { node.parent = this; children += node }

            /** The first child element with the given local name and namespace. */
            fun child(name: String, ns: String): Element? =
                children.firstOrNull { it is Element && it.local == name && it.uri == ns } as Element?

            /** libxml2 xmlGetProp: the first attribute of that local name in any namespace. */
            fun getProp(name: String): String? = attrs.firstOrNull { it.local == name }?.value

            /** libxml2 xmlSetProp with an unprefixed name: the attribute without a namespace replaced, else appended. */
            fun setProp(name: String, value: String) {
                val existing = attrs.firstOrNull { it.uri == null && it.local == name }
                if (existing != null) existing.value = value else attrs += Attr(name, null, name, value)
            }

            /** libxml2 xmlNodeGetContent: the text of every text and CDATA descendant. */
            fun content(): String {
                val out = StringBuilder()
                fun walk(e: Element) { for (n in e.children) when (n) { is Text -> out.append(n.text); is Element -> walk(n); else -> {} } }
                walk(this)
                return out.toString()
            }
        }

        class Text(val text: String, val cdata: Boolean) : Node()
        class Comment(val text: String) : Node()
        class Pi(val target: String, val data: String) : Node()

        class Doc(val version: String, val encoding: String?, val standalone: String?, val nodes: List<Node>, val root: Element) {
            /** libxml2 xmlDocDumpMemory: the XML declaration, then each top-level node on its own line. */
            fun text(): String {
                val out = StringBuilder("<?xml version=").append(quoted(version))
                if (encoding != null) out.append(" encoding=").append(quoted(encoding))
                if (standalone != null) out.append(" standalone=\"").append(standalone).append('"')
                out.append("?>\n")
                val writer = Writer(this, out, document = true)
                for (node in nodes) { writer.node(node); out.append('\n') }
                return out.toString()
            }
        }

        /** libxml2 xmlBufWriteQuotedString. */
        fun quoted(value: String): String = when {
            !value.contains('"') -> "\"$value\""
            !value.contains('\'') -> "'$value'"
            else -> "\"" + value.replace("\"", "&quot;") + "\""
        }

        /**
         * libxml2's serialisation (xmlsave.c, format 0) of a node of [doc]: as xmlDocDumpMemory writes it ([document]
         * true) or as xmlNodeDump does (false). Text: '<' '>' '&' escaped; '\r' as "&#13;", or, in a document dump
         * without an encoding declaration, as "&#xD;" with every non-ASCII character a hexadecimal character reference
         * (xmlEscapeEntities). Attribute values: '\n' '\r' '\t' '"' '<' '>' '&' escaped, non-ASCII characters as
         * hexadecimal character references when the document declares no encoding (xmlBufAttrSerializeTxtContent).
         * Namespace URIs as they are, an '&' as "&#38;" (libxml2 keeps the character reference in namespace URIs).
         */
        class Writer(doc: Doc, private val out: StringBuilder, document: Boolean) {
            private val textReferences = document && doc.encoding == null
            private val attributeReferences = doc.encoding == null

            fun node(node: Node) {
                when (node) {
                    is Element -> element(node)
                    is Text -> if (node.cdata) out.append("<![CDATA[").append(node.text).append("]]>") else text(node.text)
                    is Comment -> out.append("<!--").append(node.text).append("-->")
                    is Pi -> {
                        out.append("<?").append(node.target)
                        if (node.data.isNotEmpty()) out.append(' ').append(node.data)
                        out.append("?>")
                    }
                }
            }

            private fun element(e: Element) {
                out.append('<').append(e.qname)
                for ((prefix, uri) in e.namespaces) {
                    if (prefix == "xml") continue
                    out.append(if (prefix.isEmpty()) " xmlns=" else " xmlns:$prefix=").append(quoted(uri.replace("&", "&#38;")))
                }
                for (a in e.attrs) { out.append(' ').append(a.qname).append("=\""); attribute(a.value); out.append('"') }
                if (e.children.isEmpty()) { out.append("/>"); return }
                out.append('>')
                for (child in e.children) node(child)
                out.append("</").append(e.qname).append('>')
            }

            private fun reference(codePoint: Int) { out.append("&#x").append(Integer.toHexString(codePoint).uppercase()).append(';') }

            private fun text(s: String) {
                var i = 0
                while (i < s.length) {
                    val cp = s.codePointAt(i)
                    i += Character.charCount(cp)
                    when {
                        cp == '<'.code -> out.append("&lt;")
                        cp == '>'.code -> out.append("&gt;")
                        cp == '&'.code -> out.append("&amp;")
                        cp == '\r'.code -> out.append(if (textReferences) "&#xD;" else "&#13;")
                        cp >= 0x80 && textReferences -> reference(cp)
                        else -> out.appendCodePoint(cp)
                    }
                }
            }

            private fun attribute(s: String) {
                var i = 0
                while (i < s.length) {
                    val cp = s.codePointAt(i)
                    i += Character.charCount(cp)
                    when {
                        cp == '\n'.code -> out.append("&#10;")
                        cp == '\r'.code -> out.append("&#13;")
                        cp == '\t'.code -> out.append("&#9;")
                        cp == '"'.code -> out.append("&quot;")
                        cp == '<'.code -> out.append("&lt;")
                        cp == '>'.code -> out.append("&gt;")
                        cp == '&'.code -> out.append("&amp;")
                        cp >= 0x80 && attributeReferences -> reference(cp)
                        else -> out.appendCodePoint(cp)
                    }
                }
            }
        }

        /** The XML declaration: version, encoding and standalone (XML 1.0 clause 2.8, productions 23-25, 32, 80). */
        private val DECLARATION = Regex(
            "^<\\?xml\\s+version\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')" +
                "(?:\\s+encoding\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'))?" +
                "(?:\\s+standalone\\s*=\\s*(?:\"(yes|no)\"|'(yes|no)'))?\\s*\\?>")

        /**
         * A document parsed without network access and without a DTD, at most 1 MiB, as the shared core's libxml2 reads
         * it (white space and comments kept, CDATA sections kept, no entity of a DTD); a version 1.0 declaration is
         * assumed when there is none.
         */
        fun parse(xml: String): Doc {
            val bytes = xml.toByteArray(Charsets.UTF_8)
            need(bytes.isNotEmpty() && bytes.size <= 1 shl 20, "empty or oversized XML")
            need(!KmsSignature.prologHasDoctype(xml), "forbidden DTD")
            val declaration = DECLARATION.find(xml.removePrefix("\uFEFF"))
            val encoding = declaration?.let { it.groupValues[3].ifEmpty { it.groups[4]?.value } }
            need(encoding == null || encoding.equals("UTF-8", ignoreCase = true), "only UTF-8 XML documents are supported")
            val standalone = declaration?.let { it.groups[5]?.value ?: it.groups[6]?.value }
            val version = declaration?.let { it.groups[1]?.value ?: it.groups[2]?.value } ?: "1.0"
            val handler = Builder()
            sax(bytes, handler)
            val root = handler.root
            need(root != null, "malformed XML")
            return Doc(version, encoding, standalone, handler.top, root!!)
        }

        /**
         * libxml2 xmlParseInNodeContext: the nodes of a well-balanced chunk parsed in the context of [context] (the
         * namespaces in scope there).
         */
        fun parseInContext(context: Element, chunk: String): List<Node> {
            val scope = LinkedHashMap<String, String>()
            var current: Element? = context
            while (current != null) {
                for ((prefix, uri) in current.namespaces) scope.putIfAbsent(prefix, uri)
                current = current.parent
            }
            val wrapper = StringBuilder("<w")
            for ((prefix, uri) in scope) {
                if (prefix == "xml") continue
                wrapper.append(if (prefix.isEmpty()) " xmlns=\"" else " xmlns:$prefix=\"")
                    .append(uri.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")).append('"')
            }
            wrapper.append('>').append(chunk).append("</w>")
            val handler = Builder(depthOffset = depthOf(context) - 1)
            sax(wrapper.toString().toByteArray(Charsets.UTF_8), handler)
            val nodes = handler.root?.children?.toList() ?: throw KeyError("decrypted XML content is malformed")
            for (node in nodes) node.parent = null
            return nodes
        }

        private fun depthOf(element: Element): Int {
            var depth = 0
            var current: Element? = element
            while (current != null) { depth++; current = current.parent }
            return depth
        }

        private fun sax(bytes: ByteArray, handler: Builder) {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            for (feature in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            )) try { factory.setFeature(feature.first, feature.second) } catch (_: Exception) {}
            try {
                val reader = factory.newSAXParser().xmlReader
                reader.contentHandler = handler
                reader.errorHandler = handler
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                reader.parse(InputSource(ByteArrayInputStream(bytes)))
            } catch (e: KeyError) {
                throw e
            } catch (e: Exception) {
                throw KeyError("malformed XML")
            }
        }

        /** Builds the tree from SAX events; refuses a DTD and a document deeper than [MAX_DEPTH]. */
        private class Builder(private val depthOffset: Int = 0) : DefaultHandler(), LexicalHandler {
            val top = mutableListOf<Node>()
            var root: Element? = null
            private var current: Element? = null
            private var depth = 0
            private val pending = mutableListOf<Pair<String, String>>()
            private var cdata = StringBuilder()
            private var inCdata = false
            private var text = StringBuilder()

            private fun place(node: Node) {
                val parent = current
                if (parent == null) { if (node is Element) { root = node; top += node } else top += node } else parent.add(node)
            }

            private fun flush() {
                if (text.isEmpty()) return
                if (current != null) current!!.add(Text(text.toString(), false))
                text = StringBuilder()
            }

            override fun startPrefixMapping(prefix: String?, uri: String?) { pending += (prefix ?: "") to (uri ?: "") }

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                flush()
                depth++
                need(depth + depthOffset <= MAX_DEPTH, "malformed XML")
                val element = Element(qName, uri?.ifEmpty { null }, localName?.ifEmpty { null } ?: qName.substringAfter(':'))
                element.namespaces += pending
                pending.clear()
                for (i in 0 until attributes.length) {
                    val q = attributes.getQName(i)
                    if (q == "xmlns" || q.startsWith("xmlns:")) continue
                    element.attrs += Attr(q, attributes.getURI(i)?.ifEmpty { null },
                        attributes.getLocalName(i)?.ifEmpty { null } ?: q.substringAfter(':'), attributes.getValue(i))
                }
                place(element)
                current = element
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                flush()
                depth--
                current = current?.parent
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (length == 0) return
                if (inCdata) cdata.append(ch, start, length)
                else if (current != null) text.append(ch, start, length)
            }

            override fun ignorableWhitespace(ch: CharArray, start: Int, length: Int) = characters(ch, start, length)

            override fun processingInstruction(target: String, data: String?) { flush(); place(Pi(target, data ?: "")) }

            override fun comment(ch: CharArray, start: Int, length: Int) { flush(); place(Comment(String(ch, start, length))) }

            override fun startCDATA() { flush(); inCdata = true; cdata = StringBuilder() }

            override fun endCDATA() {
                inCdata = false
                if (cdata.isNotEmpty()) place(Text(cdata.toString(), true))
                cdata = StringBuilder()
            }

            override fun startDTD(name: String?, publicId: String?, systemId: String?) = throw KeyError("forbidden DTD")
            override fun endDTD() {}
            override fun startEntity(name: String?) {}
            override fun endEntity(name: String?) {}

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw KeyError("forbidden DTD")
            override fun warning(e: SAXParseException) {}
            override fun error(e: SAXParseException) = throw e
            override fun fatalError(e: SAXParseException) = throw e
        }
    }
}
