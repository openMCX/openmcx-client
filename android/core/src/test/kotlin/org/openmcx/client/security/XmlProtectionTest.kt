//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * XML signalling protection (TS 33.180 clause 9.3, TS 24.379 clause 6.6, TS 24.282 clause 4.6, TS 24.281 clause 4.8):
 * the cases of the shared C++ core's tests/test_xml_security.cpp, and known answers taken from the shared core (its
 * keys, a signatures document byte for byte, documents it encrypted) so that the wire format stays the server's.
 */
class XmlProtectionTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val key = XmlProtection.Key(ByteArray(16) { it.toByte() }, 0x20000001, "sip:alice@example.org")
    private fun key(xpk0: Int = 0, id: Long = key.id, user: String = key.user) =
        XmlProtection.Key(key.xpk.copyOf().also { it[0] = xpk0.toByte() }, id, user)
    private fun b64(data: ByteArray) = Base64.getEncoder().encodeToString(data)
    private fun digestOf(body: String) = b64(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))

    private val c14n10 = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"
    private val exclusive = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private val signedDoc = "urn:3gpp:ns:mcpttSignedDoc:1.0"

    /**
     * A signatures document with one Signature over the given SignedInfo, made as another implementation would: the
     * SignedInfo canonicalised in the document by the algorithm given, HMAC-SHA256 under the integrity key, the first
     * [octets] of the value kept.
     */
    private fun signatureDocument(info: String, algorithm: String, octets: Int = 32, prefixes: List<String> = emptyList()): String {
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><signatures xmlns=\"$signedDoc\">" +
            "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">$info<ds:SignatureValue>"
        val tail = "</ds:SignatureValue><ds:KeyInfo><ds:KeyName>IAAAAQ==</ds:KeyName></ds:KeyInfo></ds:Signature></signatures>"
        val canonical = XmlProtection.canonicalize(head + "AAAA" + tail, algorithm, "SignedInfo", prefixes)
        val mac = XmlProtection.hmacSha256(XmlProtection.integrityKey(key), canonical)
        return head + b64(mac.copyOf(octets)) + tail
    }

    /** A SignedInfo with the given CanonicalizationMethod (none when empty), SignatureMethod content and References. */
    private fun signedInfoOf(canonicalization: String, methodContent: String, references: String): String {
        var out = "<ds:SignedInfo>"
        if (canonicalization.isNotEmpty()) out += "<ds:CanonicalizationMethod Algorithm=\"$canonicalization\"/>"
        out += "<ds:SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#hmac-sha256\">$methodContent</ds:SignatureMethod>"
        return "$out$references</ds:SignedInfo>"
    }

    /** A Reference to a cid URL with optional Transforms and the SHA-256 digest of the octets given. */
    private fun referenceOf(url: String, digested: String, transforms: String = "") =
        "<ds:Reference URI=\"$url\">$transforms<ds:DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>" +
            "<ds:DigestValue>${digestOf(digested)}</ds:DigestValue></ds:Reference>"

    /** TS 33.180 annex F.1.4 with TS 33.220 annex B, calculated independently with Python hmac (the C++ known answers). */
    @Test fun keys() {
        assertEquals("cc31ea68d22862176f98a52e5a80ae68", XmlProtection.confidentialityKey(key).hex())
        assertEquals("91fdf6756fd52f67659100bbc7cb7bf6", XmlProtection.integrityKey(key).hex())
        assertArrayEquals(XmlProtection.integrityKey(key), XmlProtection.protectionKey(key.xpk, key.id, key.user, 0x52))
        assertThrows(IllegalArgumentException::class.java) { XmlProtection.protectionKey(key.xpk, key.id, key.user, 0x53) }
        assertThrows(IllegalArgumentException::class.java) { XmlProtection.protectionKey(key.xpk, key.id, "", 0x51) }
        assertThrows(IllegalArgumentException::class.java) { XmlProtection.Key(ByteArray(15), 1, "u") }
        // Shared core (scratch driver over xml_security.cpp): a 256-bit XPK gives the whole KDF output.
        val trk = ByteArray(32) { (0xa0 + it).toByte() }
        assertEquals(PARITY_TRK_CONFIDENTIALITY, XmlProtection.protectionKey(trk, 0x7f000001, "sip:mcptt-bob@example.org", 0x51).hex())
        assertEquals(PARITY_TRK_INTEGRITY, XmlProtection.protectionKey(trk, 0x7f000001, "sip:mcptt-bob@example.org", 0x52).hex())
    }

    /** TS 33.180 clause 9.3.4.2, TS 24.282 clause 4.6 EXAMPLE 5 and EXAMPLE 6: type="Normal" becomes type="Encrypted". */
    @Test fun elementEncryption() {
        val info = "<?xml version=\"1.0\"?><mcdatainfo xmlns=\"urn:3gpp:ns:mcdataInfo:1.0\"><mcdata-Params>" +
            "<request-type>group-sds</request-type>" +
            "<mcdata-request-uri type=\"Normal\"><mcdataURI>sip:group123@example.org</mcdataURI></mcdata-request-uri>" +
            "<mcdata-access-token type=\"Normal\"><mcdataString>secret-token</mcdataString></mcdata-access-token>" +
            "</mcdata-Params></mcdatainfo>"
        val profile = XmlProtection.Profile(setOf("mcdata-request-uri", "mcdata-access-token"), setOf("entry" to "uri"))
        val encrypted = XmlProtection.encrypt(info, key, profile)
        assertFalse(encrypted.contains("group123") || encrypted.contains("secret-token"))
        assertTrue(encrypted.contains("type=\"Encrypted\"") && encrypted.contains("http://www.w3.org/2009/xmlenc11#aes128-gcm") &&
            encrypted.contains("<ds:KeyName>IAAAAQ==</ds:KeyName>"))
        assertTrue(encrypted.contains("<request-type>group-sds</request-type>"))
        assertTrue(XmlProtection.isProtected(encrypted) && !XmlProtection.isProtected(info))
        assertEquals(key.id, XmlProtection.keyId(encrypted))
        assertNull(XmlProtection.keyId(info))
        val decrypted = XmlProtection.decrypt(encrypted, key)
        assertTrue(decrypted.contains("<mcdataURI>sip:group123@example.org</mcdataURI>") &&
            decrypted.contains("<mcdataString>secret-token</mcdataString>") && !decrypted.contains("type=\"Encrypted\""))
        assertEquals(XmlProtection.encrypt(info, key, XmlProtection.Profile(emptySet(), emptySet())), decrypted)
        assertNotEquals("a fresh IV for every encryption", XmlProtection.encrypt(info, key, profile), encrypted)

        assertThrows("another XPK", KeyError::class.java) { XmlProtection.decrypt(encrypted, key(xpk0 = 1)) }
        assertThrows("another XPK-ID", KeyError::class.java) { XmlProtection.decrypt(encrypted, key(id = 0x20000002)) }
        assertThrows("another user", KeyError::class.java) { XmlProtection.decrypt(encrypted, key(user = "sip:bob@example.org")) }
        val at = encrypted.indexOf("<CipherValue>") + 20
        val tampered = encrypted.substring(0, at) + (if (encrypted[at] == 'A') 'B' else 'A') + encrypted.substring(at + 1)
        assertThrows("altered ciphertext", KeyError::class.java) { XmlProtection.decrypt(tampered, key) }
        // The tag: the last octets of the CipherValue.
        val end = encrypted.indexOf("</CipherValue>")
        val value = encrypted.substring(encrypted.indexOf("<CipherValue>") + 13, end)
        val raw = Base64.getDecoder().decode(value).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows("altered tag", KeyError::class.java) { XmlProtection.decrypt(encrypted.replace(value, b64(raw)), key) }
        assertThrows("truncated", KeyError::class.java) {
            XmlProtection.decrypt(encrypted.replace(value, b64(Base64.getDecoder().decode(value).copyOf(27))), key)
        }
        assertThrows("another algorithm", KeyError::class.java) {
            XmlProtection.decrypt(encrypted.replace("xmlenc11#aes128-gcm", "xmlenc11#aes256-gcm"), key)
        }
        assertThrows("malformed XML", KeyError::class.java) { XmlProtection.decrypt("<a", key) }
        assertThrows("DTD", KeyError::class.java) { XmlProtection.decrypt("<!DOCTYPE a [<!ENTITY e \"v\">]><a>&e;</a>", key) }
    }

    /** TS 33.180 clause 9.3.4.3, TS 24.379 clause 6.6.2.3.4: URI attributes, as in a resource-lists body. */
    @Test fun uriEncryption() {
        val profile = XmlProtection.Profile(setOf("mcdata-request-uri"), setOf("entry" to "uri"))
        val list = "<?xml version=\"1.0\"?><resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-lists\"><list>" +
            "<entry uri=\"sip:bob@example.org\"/></list></resource-lists>"
        val hidden = XmlProtection.encrypt(list, key, profile)
        assertTrue(!hidden.contains("bob@example.org") && hidden.contains("@mc1-encryption.3gppnetwork.org"))
        assertTrue(XmlProtection.decrypt(hidden, key).contains("uri=\"sip:bob@example.org\""))
        assertEquals(key.id, XmlProtection.keyId(hidden))
        val uri = XmlProtection.encryptUri("sip:carol@example.org", key)
        assertTrue(uri.startsWith("sip:") && uri.contains(";iv=") && uri.contains(";key-id=IAAAAQ==") &&
            uri.endsWith(";alg=128-aes-gcm@mc1-encryption.3gppnetwork.org") && XmlProtection.isEncryptedUri(uri))
        assertEquals("sip:carol@example.org", XmlProtection.decryptUri(uri, key))
        assertThrows(KeyError::class.java) { XmlProtection.decryptUri(uri, key(id = 7)) }
        assertThrows(KeyError::class.java) { XmlProtection.decryptUri(uri, key(xpk0 = 9)) }
        val cipher = uri.substring(4, uri.indexOf(';'))
        val changed = Base64.getDecoder().decode(cipher).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(KeyError::class.java) { XmlProtection.decryptUri(uri.replace(cipher, b64(changed)), key) }
        assertThrows(KeyError::class.java) { XmlProtection.decryptUri("sip:bob@example.org", key) }
        assertFalse(XmlProtection.isEncryptedUri("sip:x;alg=128-aes-gcm@example.org"))
        // The URI layout with a fixed IV: ciphertext and tag, then the IV and the key-id.
        val saved = XmlProtection.ivSource
        try {
            XmlProtection.ivSource = { it.fill(7) }
            val fixed = XmlProtection.encryptUri("sip:carol@example.org", key)
            assertTrue(fixed.contains(";iv=BwcHBwcHBwcHBwcH;key-id=IAAAAQ==;alg=128-aes-gcm@"))
            assertEquals(fixed, XmlProtection.encryptUri("sip:carol@example.org", key))
        } finally {
            XmlProtection.ivSource = saved
        }
    }

    /** TS 33.180 clause 9.3.5, TS 24.379 clause 6.6.3.3.3: HMAC-SHA256 xmldsig signatures of whole bodies. */
    @Test fun signatures() {
        val bodies = listOf(
            XmlProtection.SignedBody("info@example.org", "<mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"/>"),
            XmlProtection.SignedBody("list@example.org", "<resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-lists\"/>"))
        val signatures = XmlProtection.sign(bodies, key, signedDoc)
        assertTrue(signatures.contains(signedDoc) && signatures.contains("URI=\"cid:info@example.org\"") &&
            signatures.contains("xmldsig-more#hmac-sha256"))
        assertEquals(key.id, XmlProtection.signatureKeyId(signatures))
        assertEquals(setOf("info@example.org", "list@example.org"), XmlProtection.verify(signatures, bodies, key))
        assertThrows("changed body", KeyError::class.java) {
            XmlProtection.verify(signatures, listOf(bodies[0], bodies[1].copy(body = bodies[1].body + " ")), key)
        }
        assertThrows("missing body", KeyError::class.java) { XmlProtection.verify(signatures, listOf(bodies[0]), key) }
        val at = signatures.indexOf("<ds:SignatureValue>") + 19
        val forged = signatures.substring(0, at) + (signatures[at].code xor 1).toChar() + signatures.substring(at + 1)
        assertThrows("changed signature value", KeyError::class.java) { XmlProtection.verify(forged, bodies, key) }
        val digest = signatures.indexOf("<ds:DigestValue>") + 16
        val badDigest = signatures.substring(0, digest) + (if (signatures[digest] == 'A') 'B' else 'A') + signatures.substring(digest + 1)
        assertThrows("changed digest", KeyError::class.java) { XmlProtection.verify(badDigest, bodies, key) }
        assertThrows("another XPK", KeyError::class.java) { XmlProtection.verify(signatures, bodies, key(xpk0 = 1)) }
        assertThrows("another XPK-ID", KeyError::class.java) { XmlProtection.verify(signatures, bodies, key(id = 0x20000002)) }
        // Another writer may declare the namespace once on the root: the exclusive canonical SignedInfo is unchanged.
        val declaration = " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
        val rewritten = signatures.replace(declaration, "").replaceFirst("<signatures ", "<signatures$declaration ")
        assertEquals(2, XmlProtection.verify(rewritten, bodies, key).size)
        assertThrows(KeyError::class.java) { XmlProtection.sign(emptyList(), key, signedDoc) }
        assertThrows(KeyError::class.java) { XmlProtection.sign(listOf(XmlProtection.SignedBody("a\"b", "<a/>")), key, signedDoc) }
    }

    /** RFC 4231 section 4: the HMAC-SHA-256 test cases 1 to 7 (case 5 truncated to 128 bits). */
    @Test fun rfc4231() {
        val key4 = ByteArray(25) { (it + 1).toByte() }
        val vectors = listOf(
            Triple(ByteArray(20) { 0x0b }, "Hi There".toByteArray(), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Triple("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray(), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            Triple(ByteArray(20) { 0xaa.toByte() }, ByteArray(50) { 0xdd.toByte() }, "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            Triple(key4, ByteArray(50) { 0xcd.toByte() }, "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            Triple(ByteArray(20) { 0x0c }, "Test With Truncation".toByteArray(), "a3b6167473100ee06e0c796c2955552b"),
            Triple(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray(),
                "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            Triple(ByteArray(131) { 0xaa.toByte() }, ("This is a test using a larger than block-size key and a larger than block-size data. " +
                "The key needs to be hashed before being used by the HMAC algorithm.").toByteArray(),
                "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2"))
        for ((k, data, mac) in vectors) assertEquals(mac, XmlProtection.hmacSha256(k, data).copyOf(mac.length / 2).hex())
    }

    /** W3C Canonical XML 1.0 section 3 examples 3.2, 3.3, 3.6 and Exclusive XML Canonicalization 1.0 section 2.2. */
    @Test fun canonicalization() {
        fun c(xml: String, algorithm: String, element: String = "", prefixes: List<String> = emptyList()) =
            String(XmlProtection.canonicalize(xml, algorithm, element, prefixes), Charsets.UTF_8)
        val example32 = "<doc>\n   <clean>   </clean>\n   <dirty>   A   B   </dirty>\n   <mixed>\n      A\n      <clean>   </clean>\n" +
            "      B\n      <dirty>   A   B   </dirty>\n      C\n   </mixed>\n</doc>"
        assertEquals(example32, c(example32, c14n10))
        val example33 = "<doc>\n   <e1   />\n   <e2   ></e2>\n   <e3   name = \"elem3\"   id=\"elem3\"   />\n   <e4   " +
            "name=\"elem4\"   id=\"elem4\"   ></e4>\n   <e5 a:attr=\"out\" b:attr=\"sorted\" attr2=\"all\" " +
            "attr=\"I'm\"\n      xmlns:b=\"http://www.ietf.org\"\n      xmlns:a=\"http://www.w3.org\"\n      " +
            "xmlns=\"http://example.org\"/>\n   <e6 xmlns=\"\" xmlns:a=\"http://www.w3.org\">\n      <e7 " +
            "xmlns=\"http://www.ietf.org\">\n         <e8 xmlns=\"\" xmlns:a=\"http://www.w3.org\">\n            <e9 " +
            "xmlns=\"\" xmlns:a=\"http://www.ietf.org\" attr=\"default\"/>\n         </e8>\n      </e7>\n   </e6>\n</doc>"
        assertEquals("<doc>\n   <e1></e1>\n   <e2></e2>\n   <e3 id=\"elem3\" name=\"elem3\"></e3>\n   <e4 id=\"elem4\" " +
            "name=\"elem4\"></e4>\n   <e5 xmlns=\"http://example.org\" xmlns:a=\"http://www.w3.org\" " +
            "xmlns:b=\"http://www.ietf.org\" attr=\"I'm\" attr2=\"all\" b:attr=\"sorted\" " +
            "a:attr=\"out\"></e5>\n   <e6 xmlns:a=\"http://www.w3.org\">\n      <e7 " +
            "xmlns=\"http://www.ietf.org\">\n         <e8 xmlns=\"\">\n            <e9 " +
            "xmlns:a=\"http://www.ietf.org\" attr=\"default\"></e9>\n         </e8>\n      </e7>\n   </e6>\n</doc>", c(example33, c14n10))
        assertEquals("<doc>©</doc>", c("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<doc>&#169;</doc>", c14n10))
        assertThrows(KeyError::class.java) { c(example32, "http://www.w3.org/TR/1999/REC-xpath-19991116") }

        val first = "<n0:local xmlns:n0=\"foo:bar\" xmlns:n3=\"ftp://example.org\">\n  <n1:elem2 " +
            "xmlns:n1=\"http://example.net\" xml:lang=\"en\">\n    <n3:stuff xmlns:n3=\"ftp://example.org\"/>\n  </n1:elem2>\n</n0:local>"
        val second = "<n2:pdu xmlns:n1=\"http://example.com\" xmlns:n2=\"http://foo.example\" xml:lang=\"fr\" xml:space=\"retain\">\n" +
            "  <n1:elem2 xmlns:n1=\"http://example.net\" xml:lang=\"en\">\n    <n3:stuff xmlns:n3=\"ftp://example.org\"/>\n  " +
            "</n1:elem2>\n</n2:pdu>"
        assertEquals("<n1:elem2 xmlns:n0=\"foo:bar\" xmlns:n1=\"http://example.net\" xmlns:n3=\"ftp://example.org\" " +
            "xml:lang=\"en\">\n    <n3:stuff></n3:stuff>\n  </n1:elem2>", c(first, c14n10, "elem2"))
        assertEquals("<n1:elem2 xmlns:n1=\"http://example.net\" xmlns:n2=\"http://foo.example\" xml:lang=\"en\" " +
            "xml:space=\"retain\">\n    <n3:stuff xmlns:n3=\"ftp://example.org\"></n3:stuff>\n  </n1:elem2>", c(second, c14n10, "elem2"))
        val excl = "<n1:elem2 xmlns:n1=\"http://example.net\" xml:lang=\"en\">\n    <n3:stuff " +
            "xmlns:n3=\"ftp://example.org\"></n3:stuff>\n  </n1:elem2>"
        assertEquals(excl, c(first, exclusive, "elem2"))
        assertEquals(excl, c(second, exclusive, "elem2"))
        assertTrue(c(first, exclusive, "elem2", listOf("n3")).startsWith("<n1:elem2 xmlns:n1=\"http://example.net\" xmlns:n3=\"ftp://example.org\""))
    }

    /**
     * XMLDSig Second Edition clause 4.3.1 / 6.5, TS 24.379 clause 6.6.3.4.2: signatures made with each canonicalisation
     * algorithm verify; the SignedInfo is canonicalised in its document.
     */
    @Test fun signatureVerification() {
        val body = "<mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"/>"
        val bodies = listOf(XmlProtection.SignedBody("info@example.org", body))
        val reference = referenceOf("cid:info@example.org", body)
        for (algorithm in listOf(c14n10, "$c14n10#WithComments", "http://www.w3.org/2006/12/xml-c14n11",
                "http://www.w3.org/2006/12/xml-c14n11#WithComments", exclusive, "${exclusive}WithComments"))
            assertEquals(setOf("info@example.org"), XmlProtection.verify(signatureDocument(signedInfoOf(algorithm, "", reference), algorithm), bodies, key))
        // A signature computed over the exclusive form but naming Canonical XML 1.0 fails.
        val exclusiveDocument = signatureDocument(signedInfoOf(exclusive, "", reference), exclusive)
        assertThrows(KeyError::class.java) { XmlProtection.verify(exclusiveDocument.replaceFirst(exclusive, c14n10), bodies, key) }
        assertTrue(String(XmlProtection.canonicalize(exclusiveDocument, c14n10, "SignedInfo"))
            .startsWith("<ds:SignedInfo xmlns=\"$signedDoc\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"))
        // Exclusive c14n with an InclusiveNamespaces PrefixList.
        val info = "<ds:SignedInfo><ds:CanonicalizationMethod Algorithm=\"$exclusive\"><ec:InclusiveNamespaces xmlns:ec=\"$exclusive\" " +
            "PrefixList=\"#default\"/></ds:CanonicalizationMethod><ds:SignatureMethod " +
            "Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#hmac-sha256\"/>$reference</ds:SignedInfo>"
        assertEquals(1, XmlProtection.verify(signatureDocument(info, exclusive, 32, listOf("#default")), bodies, key).size)
        assertThrows(KeyError::class.java) { XmlProtection.verify(signatureDocument(info, exclusive), bodies, key) }
        // Maintainer decision M8: a SignedInfo without CanonicalizationMethod fails; so does an unknown algorithm.
        assertThrows(KeyError::class.java) { XmlProtection.verify(signatureDocument(signedInfoOf("", "", reference), c14n10), bodies, key) }
        assertThrows(KeyError::class.java) {
            XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", reference), c14n10).replaceFirst(c14n10, "http://example.org/c14n"), bodies, key)
        }
        // OpenMCX signs with Exclusive XML Canonicalization 1.0 without comments.
        val own = XmlProtection.sign(bodies, key, signedDoc)
        assertTrue(own.contains("<ds:CanonicalizationMethod Algorithm=\"$exclusive\"/>"))
        XmlProtection.verify(own, bodies, key)

        // XMLDSig 1.1 clauses 4.4.2 and 6.3.1 (maintainer decision M10): HMACOutputLength.
        fun withLength(bits: String, octets: Int) =
            signatureDocument(signedInfoOf(c14n10, "<ds:HMACOutputLength>$bits</ds:HMACOutputLength>", reference), c14n10, octets)
        assertEquals(1, XmlProtection.verify(withLength("128", 16), bodies, key).size)
        assertEquals(1, XmlProtection.verify(withLength("256", 32), bodies, key).size)
        assertEquals(1, XmlProtection.verify(withLength(" 160 ", 20), bodies, key).size)
        for ((bits, octets) in listOf("120" to 15, "80" to 10, "40" to 5, "0" to 0, "132" to 16, "264" to 32, "-128" to 16, "128" to 32))
            assertThrows("HMACOutputLength $bits", KeyError::class.java) { XmlProtection.verify(withLength(bits, octets), bodies, key) }
        assertThrows("truncated without HMACOutputLength", KeyError::class.java) {
            XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", reference), c14n10, 16), bodies, key)
        }

        // RFC 2392 section 2: the cid URL is %-decoded before it is matched to a Content-ID.
        val escaped = listOf(XmlProtection.SignedBody("a@b@x", body))
        assertEquals(setOf("a@b@x"), XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", referenceOf("cid:a%40b@x", body)), c14n10), escaped, key))
        for (bad in listOf("cid:a%4", "cid:a%2", "cid:", "info@example.org"))
            assertThrows(bad, KeyError::class.java) {
                XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", referenceOf(bad, body)), c14n10), escaped, key)
            }
        val percent = listOf(XmlProtection.SignedBody("50%off@example.org", body))
        val signedPercent = XmlProtection.sign(percent, key, signedDoc)
        assertTrue(signedPercent.contains("URI=\"cid:50%25off@example.org\""))
        assertEquals(1, XmlProtection.verify(signedPercent, percent, key).size)

        // XMLDSig Second Edition clause 4.3.3.2: Reference Transforms.
        val loose = "<doc  b=\"2\" a=\"1\" ><e/></doc>"
        val looseBodies = listOf(XmlProtection.SignedBody("doc@example.org", loose))
        fun transform(algorithm: String) = "<ds:Transforms><ds:Transform Algorithm=\"$algorithm\"/></ds:Transforms>"
        val canonicalLoose = "<doc a=\"1\" b=\"2\"><e></e></doc>"
        fun transformed(url: String, digested: String, algorithm: String) =
            signatureDocument(signedInfoOf(c14n10, "", referenceOf(url, digested, transform(algorithm))), c14n10)
        assertEquals(1, XmlProtection.verify(transformed("cid:doc@example.org", canonicalLoose, c14n10), looseBodies, key).size)
        assertEquals(1, XmlProtection.verify(transformed("cid:doc@example.org", canonicalLoose,
            "http://www.w3.org/2000/09/xmldsig#enveloped-signature"), looseBodies, key).size)
        assertEquals(1, XmlProtection.verify(transformed("cid:b64@example.org", "<a/>", "http://www.w3.org/2000/09/xmldsig#base64"),
            listOf(XmlProtection.SignedBody("b64@example.org", "PGEvPg==")), key).size)
        assertThrows(KeyError::class.java) {
            XmlProtection.verify(transformed("cid:doc@example.org", loose, "http://www.w3.org/TR/1999/REC-xpath-19991116"), looseBodies, key)
        }

        // XMLDSig Second Edition clause 3.2.1: every Reference of a SignedInfo is validated.
        val two = listOf(XmlProtection.SignedBody("info@example.org", body), XmlProtection.SignedBody("doc@example.org", loose))
        assertEquals(setOf("info@example.org", "doc@example.org"), XmlProtection.verify(
            signatureDocument(signedInfoOf(c14n10, "", reference + referenceOf("cid:doc@example.org", loose)), c14n10), two, key))
        assertThrows(KeyError::class.java) {
            XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", reference + referenceOf("cid:doc@example.org", body)), c14n10), two, key)
        }
        // A Signature without Reference, an unsupported digest or signature method.
        assertThrows(KeyError::class.java) { XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", ""), c14n10), bodies, key) }
        assertThrows(KeyError::class.java) {
            XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", reference.replace("xmlenc#sha256", "xmldsig#sha1")), c14n10), bodies, key)
        }
        assertThrows(KeyError::class.java) {
            XmlProtection.verify(signatureDocument(signedInfoOf(c14n10, "", reference), c14n10).replace("hmac-sha256", "hmac-sha384"), bodies, key)
        }
    }

    /** TS 33.180 clause 9.3.5 "all XML MIME bodies": the XML MIME types (RFC 7303, RFC 6839). */
    @Test fun xmlMime() {
        for (type in listOf("application/vnd.3gpp.mcvideo-location-info+xml", "application/xml", "text/xml",
                "Application/PIDF+XML; charset=UTF-8", "application/vnd.3gpp.mcptt-signed+xml"))
            assertTrue(type, XmlProtection.isXmlMime(type))
        for (type in listOf("application/sdp", "application/mikey", "application/vnd.3gpp.mcdata-signalling", "+xml", "application/xml-dtd"))
            assertFalse(type, XmlProtection.isXmlMime(type))
    }

    /** TS 24.281 clause 4.8: the MCVideo signalling profile names the MCVideo elements. */
    @Test fun profiles() {
        val video = XmlProtection.signallingProfile("mcvideo")
        assertTrue(video.elements.contains("mcvideo-request-uri") && video.elements.contains("mcvideo-access-token") &&
            !video.elements.contains("mcptt-request-uri") && video.elements.contains("latitude"))
        assertEquals(XmlProtection.signallingProfile().uriAttributes, video.uriAttributes)
        assertTrue(XmlProtection.signallingProfile().elements.contains("mcptt-request-uri"))
        assertEquals(25, XmlProtection.signallingProfile().elements.size)
        assertEquals(5, XmlProtection.signallingProfile().uriAttributes.size)
        val videoInfo = "<?xml version=\"1.0\"?><mcvideoinfo xmlns=\"urn:3gpp:ns:mcvideoInfo:1.0\"><mcvideo-Params>" +
            "<mcvideo-request-uri type=\"Normal\"><mcvideoURI>sip:bob.mcvideo@example.org</mcvideoURI></mcvideo-request-uri>" +
            "</mcvideo-Params></mcvideoinfo>"
        val sealed = XmlProtection.encrypt(videoInfo, key, video)
        assertTrue(!sealed.contains("bob.mcvideo") && XmlProtection.decrypt(sealed, key).contains("bob.mcvideo"))
        assertThrows(IllegalArgumentException::class.java) { XmlProtection.signallingProfile("mcdata") }
    }

    /**
     * The shared core's serialisation (libxml2 xmlDocDumpMemory and xmlNodeDump), as the scratch driver over
     * xml_security.cpp printed it: a document re-written without encryption, character escaping with and without an
     * encoding declaration, namespace declarations before attributes, comments, PIs and CDATA kept.
     */
    @Test fun serialisation() {
        val none = XmlProtection.Profile(emptySet(), emptySet())
        val p1 = "<a x=\"1&#13;&#10;&#9;&quot; &lt;&gt;&amp;é😀\" xmlns:p=\"u:p\" xmlns=\"u:d\" p:y=\"2\">t&#13;é😀" +
            "&lt;&gt;&amp;\"'<![CDATA[c<&]]><!--c--><?pi  d d ?><b/><c></c></a>"
        assertEquals("<?xml version=\"1.0\"?>\n<a xmlns:p=\"u:p\" xmlns=\"u:d\" x=\"1&#13;&#10;&#9;&quot; &lt;&gt;&amp;&#xE9;&#x1F600;\" " +
            "p:y=\"2\">t&#xD;&#xE9;&#x1F600;&lt;&gt;&amp;\"'<![CDATA[c<&]]><!--c--><?pi d d ?><b/><c/></a>\n", XmlProtection.encrypt(p1, key, none))
        val p2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!--top-->\n<a x=\"1&#13;&#10;&quot;é\">t&#13;é<b xmlns=\"&amp;x\"/></a>\n<!--end--><?pi x?>"
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!--top-->\n<a x=\"1&#13;&#10;&quot;é\">t&#13;é<b xmlns=\"&#38;x\"/></a>\n" +
            "<!--end-->\n<?pi x?>\n", XmlProtection.encrypt(p2, key, none))
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>\n<a/>\n",
            XmlProtection.encrypt("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?><a/>", key, none))
        assertEquals("<?xml version=\"1.0\" standalone=\"yes\"?>\n<a xmlns:q=\"u\" xml:lang=\"en\" q:x=\"1\" y=\"2\"/>\n",
            XmlProtection.encrypt("<?xml version=\"1.0\" standalone=\"yes\"?><a xml:lang=\"en\" xmlns:q=\"u\" q:x=\"1\" y=\"2\"/>", key, none))
        assertThrows(KeyError::class.java) { XmlProtection.encrypt("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>x</a>", key, none) }
        // libxml2 parses at most 257 nested elements without XML_PARSE_HUGE.
        fun nested(n: Int) = "<a>".repeat(n) + "</a>".repeat(n)
        XmlProtection.encrypt(nested(257), key, none)
        assertThrows(KeyError::class.java) { XmlProtection.encrypt(nested(258), key, none) }
    }

    /** Known answers of the shared core: a signatures document byte for byte, and a document it encrypted. */
    @Test fun sharedCoreParity() {
        val bodies = listOf(
            XmlProtection.SignedBody("mcptt-info@example.org", PARITY_INFO),
            XmlProtection.SignedBody("resource-lists@example.org", PARITY_LIST))
        assertEquals(PARITY_SIGNATURES, XmlProtection.sign(bodies, key, signedDoc))
        assertEquals(bodies.map { it.contentId }.toSet(), XmlProtection.verify(PARITY_SIGNATURES, bodies, key))
        assertEquals(PARITY_DECRYPTED, XmlProtection.decrypt(PARITY_ENCRYPTED, key))
        assertEquals(PARITY_LIST_DECRYPTED, XmlProtection.decrypt(PARITY_LIST_ENCRYPTED, key))
        assertEquals(key.id, XmlProtection.keyId(PARITY_LIST_ENCRYPTED))
        // The same document encrypted here equals the shared core's once the CipherValues and encrypted URIs are masked.
        fun mask(xml: String) = xml.replace(Regex("<CipherValue>[^<]*</CipherValue>"), "<CipherValue/>")
            .replace(Regex("sip:[A-Za-z0-9+/=]+;iv=[A-Za-z0-9+/=]+;"), "sip:;iv=;")
        assertEquals(mask(PARITY_ENCRYPTED), mask(XmlProtection.encrypt(PARITY_INFO, key, XmlProtection.signallingProfile())))
        assertEquals(mask(PARITY_LIST_ENCRYPTED), mask(XmlProtection.encrypt(PARITY_LIST, key, XmlProtection.signallingProfile())))
    }

    private companion object {
        // The shared core's outputs (scratch driver over xml_security.cpp, libxml2 2.9.14, OpenSSL 3.0.13).
        const val PARITY_TRK_CONFIDENTIALITY = "cbb77945fb585a14f1eeb52c892a0ae99374dad2d8741aefd34f9c8250b1db57"
        const val PARITY_TRK_INTEGRITY = "f432ac8f9837805f184aac99589d187fc1974b187d0124be009f21f24cef67eb"
        const val PARITY_INFO =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\" xmlns:mcp" +
            "ttinfo=\"urn:3gpp:ns:mcpttInfo:1.0\">\n  <mcptt-Params>\n    <session-type>private</session-type>\n " +
            "   <mcptt-request-uri type=\"Normal\"><mcpttURI>sip:mcptt-bob@example.org</mcpttURI></mcptt-request-" +
            "uri>\n    <mcptt-calling-user-id type=\"Normal\"><mcpttURI>sip:mcptt-alice@example.org</mcpttURI></m" +
            "cptt-calling-user-id>\n    <mcptt-client-id type=\"Normal\"><mcpttString>urn:uuid:3f2504e0-4f89-11d3" +
            "-9a0c-0305e82c3301</mcpttString></mcptt-client-id>\n    <emergency-ind type=\"Normal\"><mcpttBoolean" +
            ">false</mcpttBoolean></emergency-ind>\n    <anyExt><call-to-functional-alias-ind>false</call-to-func" +
            "tional-alias-ind></anyExt>\n  </mcptt-Params>\n</mcpttinfo>\n"
        const val PARITY_LIST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-" +
            "lists\" xmlns:cp=\"urn:ietf:params:xml:ns:copycontrol\">\n  <list>\n    <entry uri=\"sip:mcptt-bob@e" +
            "xample.org\" cp:copyControl=\"to\"/>\n    <entry uri=\"sip:mcptt-carol@example.org\"><display-name>C" +
            "arol &amp; \"Co\" &#233;</display-name></entry>\n  </list>\n</resource-lists>\n"
        const val PARITY_SIGNATURES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><signatures xmlns=\"urn:3gpp:ns:mcpttSignedDoc:1.0\"><ds:S" +
            "ignature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:SignedInfo><ds:CanonicalizationMethod A" +
            "lgorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/><ds:SignatureMethod Algorithm=\"http://www.w3." +
            "org/2001/04/xmldsig-more#hmac-sha256\"/><ds:Reference URI=\"cid:mcptt-info@example.org\"><ds:DigestM" +
            "ethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/><ds:DigestValue>O83Qc1kzju3QNU5IcxuLBgz" +
            "uG4Io3HkF8yogXAIiKLo=</ds:DigestValue></ds:Reference></ds:SignedInfo><ds:SignatureValue>jxCXx2YIp8kV" +
            "Q/7DR7SQHqviEWetp2eG4VFum08JNpA=</ds:SignatureValue><ds:KeyInfo><ds:KeyName>IAAAAQ==</ds:KeyName></d" +
            "s:KeyInfo></ds:Signature><ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:SignedInf" +
            "o><ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/><ds:SignatureMet" +
            "hod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#hmac-sha256\"/><ds:Reference URI=\"cid:resour" +
            "ce-lists@example.org\"><ds:DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/><ds:D" +
            "igestValue>e5ty/rztwnvyNrwY624O4T198e2UCoD0lnOqiQxbrgk=</ds:DigestValue></ds:Reference></ds:SignedIn" +
            "fo><ds:SignatureValue>Keiwux3xBNp/8mR1/yOGbOZHsdBAF7VA1XdZlL772RE=</ds:SignatureValue><ds:KeyInfo><d" +
            "s:KeyName>IAAAAQ==</ds:KeyName></ds:KeyInfo></ds:Signature></signatures>"
        const val PARITY_ENCRYPTED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\" xmlns:mcp" +
            "ttinfo=\"urn:3gpp:ns:mcpttInfo:1.0\">\n  <mcptt-Params>\n    <session-type>private</session-type>\n " +
            "   <mcptt-request-uri type=\"Encrypted\"><EncryptedData xmlns=\"http://www.w3.org/2001/04/xmlenc#\" " +
            "Type=\"http://www.w3.org/2001/04/xmlenc#Content\"><EncryptionMethod Algorithm=\"http://www.w3.org/20" +
            "09/xmlenc11#aes128-gcm\"/><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:KeyName>IA" +
            "AAAQ==</ds:KeyName></ds:KeyInfo><CipherData><CipherValue>dac4CGyBOX9e+cq0wfDZqXdgcwPq0Q2H3I8Vaw2uCjj" +
            "D7ap4Hg//A2ykzSsRIpcpMXfLLjaI17mCfiBjNJGxngN51weEZHC7ksE=</CipherValue></CipherData></EncryptedData>" +
            "</mcptt-request-uri>\n    <mcptt-calling-user-id type=\"Encrypted\"><EncryptedData xmlns=\"http://ww" +
            "w.w3.org/2001/04/xmlenc#\" Type=\"http://www.w3.org/2001/04/xmlenc#Content\"><EncryptionMethod Algor" +
            "ithm=\"http://www.w3.org/2009/xmlenc11#aes128-gcm\"/><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/0" +
            "9/xmldsig#\"><ds:KeyName>IAAAAQ==</ds:KeyName></ds:KeyInfo><CipherData><CipherValue>Sozabbm4rIsJ/B5S" +
            "9ypM+XtRkueJHjJU9yANkHPsQ848IUl3Em5J3uSA5cxeAWWxFu9S2bi+WUXW3BqdpRhV51fGE85WWO5Yf7OPqg==</CipherValu" +
            "e></CipherData></EncryptedData></mcptt-calling-user-id>\n    <mcptt-client-id type=\"Encrypted\"><En" +
            "cryptedData xmlns=\"http://www.w3.org/2001/04/xmlenc#\" Type=\"http://www.w3.org/2001/04/xmlenc#Cont" +
            "ent\"><EncryptionMethod Algorithm=\"http://www.w3.org/2009/xmlenc11#aes128-gcm\"/><ds:KeyInfo xmlns:" +
            "ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:KeyName>IAAAAQ==</ds:KeyName></ds:KeyInfo><CipherData>" +
            "<CipherValue>j2UB6yguB7jCjJFtUPgJQ53VmujaIRPG/C7VZizbLYI5Lx5BTvWoxXmKX4v9YNxrDJ0e9NCE46KIM/9tRORbmke" +
            "G1Tqr88BgXgOIvzVFO5bEN7oWkH2VSyclfz/xoqZoihvH8w==</CipherValue></CipherData></EncryptedData></mcptt-" +
            "client-id>\n    <emergency-ind type=\"Encrypted\"><EncryptedData xmlns=\"http://www.w3.org/2001/04/x" +
            "mlenc#\" Type=\"http://www.w3.org/2001/04/xmlenc#Content\"><EncryptionMethod Algorithm=\"http://www." +
            "w3.org/2009/xmlenc11#aes128-gcm\"/><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:K" +
            "eyName>IAAAAQ==</ds:KeyName></ds:KeyInfo><CipherData><CipherValue>fdESnc5PWgoPk65jPcjLob7wYImv5TPGWH" +
            "REWjmw/s1QpRwnGADhtgCtgh16+xN85ccJRnGJ8kxvN6eEsf8=</CipherValue></CipherData></EncryptedData></emerg" +
            "ency-ind>\n    <anyExt><call-to-functional-alias-ind>false</call-to-functional-alias-ind></anyExt>\n" +
            "  </mcptt-Params>\n</mcpttinfo>\n"
        const val PARITY_DECRYPTED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\" xmlns:mcp" +
            "ttinfo=\"urn:3gpp:ns:mcpttInfo:1.0\">\n  <mcptt-Params>\n    <session-type>private</session-type>\n " +
            "   <mcptt-request-uri type=\"Normal\"><mcpttURI>sip:mcptt-bob@example.org</mcpttURI></mcptt-request-" +
            "uri>\n    <mcptt-calling-user-id type=\"Normal\"><mcpttURI>sip:mcptt-alice@example.org</mcpttURI></m" +
            "cptt-calling-user-id>\n    <mcptt-client-id type=\"Normal\"><mcpttString>urn:uuid:3f2504e0-4f89-11d3" +
            "-9a0c-0305e82c3301</mcpttString></mcptt-client-id>\n    <emergency-ind type=\"Normal\"><mcpttBoolean" +
            ">false</mcpttBoolean></emergency-ind>\n    <anyExt><call-to-functional-alias-ind>false</call-to-func" +
            "tional-alias-ind></anyExt>\n  </mcptt-Params>\n</mcpttinfo>\n"
        const val PARITY_LIST_ENCRYPTED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-" +
            "lists\" xmlns:cp=\"urn:ietf:params:xml:ns:copycontrol\">\n  <list>\n    <entry uri=\"sip:DzlusxdTM7k" +
            "kFM6vpmbW975RyhoJfkUgMzbr9MvkjyvyKw2MtnGnte0=;iv=/rTgDkkrcT0OiS6Q;key-id=IAAAAQ==;alg=128-aes-gcm@mc" +
            "1-encryption.3gppnetwork.org\" cp:copyControl=\"to\"/>\n    <entry uri=\"sip:K+qpDJJB+TwYgvtDyGtYnDA" +
            "b/MyiuUYy/ws2KOBbPUIHp2Zq9KHqa01OAg==;iv=/vIcJeXNq0J//4ci;key-id=IAAAAQ==;alg=128-aes-gcm@mc1-encryp" +
            "tion.3gppnetwork.org\"><display-name>Carol &amp; \"Co\" é</display-name></entry>\n  </list>\n</resou" +
            "rce-lists>\n"
        const val PARITY_LIST_DECRYPTED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-" +
            "lists\" xmlns:cp=\"urn:ietf:params:xml:ns:copycontrol\">\n  <list>\n    <entry uri=\"sip:mcptt-bob@e" +
            "xample.org\" cp:copyControl=\"to\"/>\n    <entry uri=\"sip:mcptt-carol@example.org\"><display-name>C" +
            "arol &amp; \"Co\" é</display-name></entry>\n  </list>\n</resource-lists>\n"
    }
}
