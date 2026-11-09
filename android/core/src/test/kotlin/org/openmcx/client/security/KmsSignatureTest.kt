//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verification and creation of the XML signature of a signed KMS message (TS 33.180 V19.5.0 annexes D.1, D.2.2, D.3.1
 * and D.3.5.1; XMLDSig Second Edition clauses 3.2, 4.3.3 and 6), at parity with the shared C++ core: every case of the
 * server repository's tests/test_kms_xml_signature.cpp with the same inputs and the same accept/refuse outcome and
 * reason, then cases of this port. As there, the fixtures are written in canonical form by hand, so each digest and
 * signature value comes from the JCA over text this test states, not from the code under test. The canonical forms in
 * [canonicalFormsOfLibxml2] and the signed text in [signMatchesTheSharedCore] were produced by the shared core
 * (libxml2 2.9.14, OpenSSL 3.0.13 in the openmcx-dev image) over the inputs given here.
 */
class KmsSignatureTest {
    private val kmsns = "urn:3gpp:ns:mcsecKMSInterface:1.0"
    private val dsig = "http://www.w3.org/2000/09/xmldsig#"
    private val c14n10 = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"
    private val c14n11 = "http://www.w3.org/2006/12/xml-c14n11"
    private val exc = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private val hmacSha256 = "http://www.w3.org/2001/04/xmldsig-more#hmac-sha256"
    private val sha256 = "http://www.w3.org/2001/04/xmlenc#sha256"
    private val enveloped = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"

    private val key = ByteArray(32) { 0x5a }
    private val otherKey = ByteArray(32) { 0x5b }

    /** Fails unless verification throws KeyError with an error naming the reason. */
    private fun refused(xml: String, key: ByteArray, what: String, reason: String) {
        try {
            KmsSignature.verifyPayload(xml, key)
        } catch (e: KeyError) {
            assertTrue("$what: refused for another reason: ${e.message}", e.message!!.contains(reason))
            return
        }
        fail("accepted: $what")
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    private fun digest(octets: String) = b64(MessageDigest.getInstance("SHA-256").digest(octets.toByteArray(Charsets.UTF_8)))
    private fun mac(key: ByteArray, octets: String) =
        b64(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(octets.toByteArray(Charsets.UTF_8)))

    /** A Reference: its URI, its Transforms (empty for none), the octets it digests and its DigestMethod. */
    private data class Ref(val uri: String, val transforms: String, val octets: String, val method: String = "http://www.w3.org/2001/04/xmlenc#sha256")

    private fun reference(ref: Ref) = "<Reference URI=\"${ref.uri}\">${ref.transforms}<DigestMethod Algorithm=\"${ref.method}\">" +
        "</DigestMethod><DigestValue>${digest(ref.octets)}</DigestValue></Reference>"

    /**
     * A signed KMS message: root (open tag text), payload (as written, in canonical form), the Signature with its
     * SignedInfo signed under the key (canonical SignedInfo: the dsig default namespace declared on it and, Canonical
     * XML 1.0 section 2.4, the xml: attributes of its ancestors, given as inherited).
     */
    private fun signedMessage(rootOpen: String, rootClose: String, payload: String, refs: List<Ref>, key: ByteArray,
                              methodParameter: String = "", inherited: String = "", signedInfoMethod: String = c14n10): String {
        var body = "<CanonicalizationMethod Algorithm=\"$signedInfoMethod\"></CanonicalizationMethod><SignatureMethod Algorithm=\"" +
            "$hmacSha256\">$methodParameter</SignatureMethod>"
        for (ref in refs) body += reference(ref)
        val canonical = "<SignedInfo xmlns=\"$dsig\"$inherited>$body</SignedInfo>"
        return "$rootOpen$payload<Signature xmlns=\"$dsig\"><SignedInfo>$body</SignedInfo><SignatureValue>${mac(key, canonical)}" +
            "</SignatureValue><KeyInfo><KeyName>AAAAAQ==</KeyName></KeyInfo></Signature>$rootClose"
    }

    private val payloadBody =
        "<UserUri>sip:a@example.org</UserUri><KmsUri>https://kms.example.org</KmsUri><Time>2026-10-30T10:00:00</Time>" +
            "<ClientReqUrl>https://kms.example.org/keymanagement/identity/v1/init</ClientReqUrl>" +
            "<KmsMessage xml:id=\"msg\"><KmsInit Version=\"1.0.0\"></KmsInit></KmsMessage>"
    private val rootOpen = "<SignedKmsResponse xmlns=\"$kmsns\">"
    private val rootClose = "</SignedKmsResponse>"
    // The payload as written inside the root (the default namespace inherited), and its canonical form on its own
    // (Canonical XML 1.0: the in-scope namespace declared on it; attributes without a namespace before xml:id).
    private val payload = "<KmsResponse Version=\"1.0.0\" xml:id=\"resp\">$payloadBody</KmsResponse>"
    private val canonicalPayload = "<KmsResponse xmlns=\"$kmsns\" Version=\"1.0.0\" xml:id=\"resp\">$payloadBody</KmsResponse>"
    private val transform = "<Transforms><Transform Algorithm=\"$enveloped\"></Transform></Transforms>"
    private val documentMinusSignature = rootOpen + payload + rootClose
    private val byId = signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", "", canonicalPayload)), key)
    private val canonicalMessage = "<KmsMessage xmlns=\"$kmsns\" xml:id=\"msg\"><KmsInit Version=\"1.0.0\"></KmsInit></KmsMessage>"
    private val canonicalBySchemaId = "<KmsResponse xmlns=\"$kmsns\" Id=\"resp\" Version=\"1.0.0\">$payloadBody</KmsResponse>"

    private fun String.insertAt(marker: String, text: String) = indexOf(marker).let { substring(0, it) + text + substring(it) }

    // ---------------------------------------------------------------------------------------------------------------
    // The cases of the shared core's test (tests/test_kms_xml_signature.cpp), in its order.

    @Test fun kmsResponseByXmlId() = assertEquals(canonicalPayload, KmsSignature.verifyPayload(byId, key))

    @Test fun nullUriWithTheEnvelopedTransform() {
        val whole = signedMessage(rootOpen, rootClose, payload, listOf(Ref("", transform, documentMinusSignature)), key)
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(whole, key))
    }

    @Test fun wrapperByXmlIdEnveloped() {
        val wrapperOpen = "<SignedKmsResponse xmlns=\"$kmsns\" xml:id=\"doc\">"
        val wrapper = signedMessage(wrapperOpen, rootClose, payload, listOf(Ref("#doc", transform, wrapperOpen + payload + rootClose)),
            key, "", " xml:id=\"doc\"")
        assertEquals("<KmsResponse xmlns=\"$kmsns\" Version=\"1.0.0\" xml:id=\"resp\">$payloadBody</KmsResponse>",
            KmsSignature.verifyPayload(wrapper, key))
    }

    @Test fun shortKeyAndHmacOutputLength256() {
        val shortKey = ByteArray(16) { 0x33 }
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, payload,
            listOf(Ref("#resp", "", canonicalPayload)), shortKey, "<HMACOutputLength>256</HMACOutputLength>"), shortKey))
    }

    @Test fun signedKmsRequest() {
        val request = "<KmsRequest Version=\"1.1.0\" xml:id=\"req\">$payloadBody</KmsRequest>"
        val signed = signedMessage("<SignedKmsRequest xmlns=\"$kmsns\">", "</SignedKmsRequest>", request,
            listOf(Ref("#req", "", "<KmsRequest xmlns=\"$kmsns\" Version=\"1.1.0\" xml:id=\"req\">$payloadBody</KmsRequest>")), key)
        assertTrue(KmsSignature.verifyPayload(signed, key).startsWith("<KmsRequest xmlns="))
    }

    @Test fun commentsOmitted() =
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(byId.insertAt("<UserUri>", "<!-- note -->"), key))

    /** Clauses 6.1 and 6.5: the payload under each canonicalization (the shortname node-set has no comments). */
    @Test fun payloadUnderEachCanonicalization() {
        // The first three are the shared core's; the other two add the remaining algorithms of the list.
        for (method in listOf(c14n11, "$c14n10#WithComments", exc, "$c14n11#WithComments", "${exc}WithComments")) {
            val chain = "<Transforms><Transform Algorithm=\"$method\"></Transform></Transforms>"
            val variant = signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", chain, canonicalPayload)), key)
                .insertAt("<UserUri>", "<!-- note -->")
            assertEquals("payload under $method", canonicalPayload, KmsSignature.verifyPayload(variant, key))
        }
    }

    /**
     * A namespace declaration added to the wrapper after signing: outside an exclusive canonicalization of the
     * payload, so the signature still verifies, and it must not appear in what is returned; under the inclusive
     * default it is part of the payload's canonical form and breaks the digest.
     */
    @Test fun unsignedNamespaceDeclaration() {
        val exclusive = "<Transforms><Transform Algorithm=\"$exc\"></Transform></Transforms>"
        val injected = signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", exclusive, canonicalPayload)), key, "", "", exc)
            .insertAt(">", " xmlns:p=\"urn:example:unsigned\"")
        val returned = KmsSignature.verifyPayload(injected, key)
        assertEquals(canonicalPayload, returned)
        assertFalse(returned.contains("urn:example:unsigned"))
        refused(byId.insertAt(">", " xmlns:p=\"urn:example:unsigned\""), key, "unsigned namespace under the inclusive default",
            "reference digest mismatch")
    }

    @Test fun changedPayload() = refused(byId.replace("sip:a@", "sip:b@"), key, "changed payload", "reference digest mismatch")

    @Test fun wrongKey() = refused(byId, otherKey, "wrong key", "signature value mismatch")

    @Test fun alteredSignatureValue() {
        val at = byId.indexOf("<SignatureValue>") + 16
        val altered = byId.substring(0, at) + (if (byId[at] == 'A') 'B' else 'A') + byId.substring(at + 1)
        refused(altered, key, "altered SignatureValue", "signature value mismatch")
    }

    @Test fun badDigest() = refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", "", "$canonicalPayload ")), key),
        key, "bad digest", "reference digest mismatch")

    @Test fun partialCoverage() = refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("#msg", "", canonicalMessage)), key),
        key, "partial coverage (KmsMessage only)", "does not cover the whole KMS message")

    @Test fun invalidSecondReference() = refused(signedMessage(rootOpen, rootClose, payload,
        listOf(Ref("#resp", "", canonicalPayload), Ref("#msg", "", canonicalMessage + "x")), key),
        key, "invalid second Reference", "reference digest mismatch")

    @Test fun duplicateXmlId() {
        val twin = "<KmsResponse Version=\"1.0.0\" xml:id=\"resp\">$payloadBody<Extra xmlns=\"urn:example\" xml:id=\"resp\"></Extra></KmsResponse>"
        refused(signedMessage(rootOpen, rootClose, twin, listOf(Ref("#resp", "", canonicalPayload)), key), key, "duplicate xml:id", "ambiguous ID")
    }

    @Test fun kmsResponseBySchemaId() {
        val bySchemaId = "<KmsResponse Id=\"resp\" Version=\"1.0.0\">$payloadBody</KmsResponse>"
        assertEquals(canonicalBySchemaId, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, bySchemaId,
            listOf(Ref("#resp", "", canonicalBySchemaId)), key), key))
    }

    @Test fun duplicateIdAndMixedId() {
        val twinId = "<KmsResponse Id=\"resp\" Version=\"1.0.0\">$payloadBody<Extra xmlns=\"urn:example\" Id=\"resp\"></Extra></KmsResponse>"
        refused(signedMessage(rootOpen, rootClose, twinId, listOf(Ref("#resp", "", canonicalBySchemaId)), key), key, "duplicate Id", "ambiguous ID")
        val mixedId = "<KmsResponse Id=\"resp\" Version=\"1.0.0\">$payloadBody<Extra xmlns=\"urn:example\" xml:id=\"resp\"></Extra></KmsResponse>"
        refused(signedMessage(rootOpen, rootClose, mixedId, listOf(Ref("#resp", "", canonicalBySchemaId)), key), key,
            "Id and xml:id with the same value", "ambiguous ID")
    }

    @Test fun qualifiedIdIsNotAnId() {
        val prefixedId = "<KmsResponse xmlns:p=\"urn:example\" p:Id=\"resp\" Version=\"1.0.0\">$payloadBody</KmsResponse>"
        refused(signedMessage(rootOpen, rootClose, prefixedId, listOf(Ref("#resp", "",
            "<KmsResponse xmlns=\"$kmsns\" xmlns:p=\"urn:example\" Version=\"1.0.0\" p:Id=\"resp\">$payloadBody</KmsResponse>")), key),
            key, "a qualified Id is not an ID", "Reference to no element")
    }

    @Test fun nullUriWithoutTheEnvelopedTransform() = refused(signedMessage(rootOpen, rootClose, payload,
        listOf(Ref("", "", documentMinusSignature)), key), key, "null URI without the enveloped transform", "reference digest mismatch")

    @Test fun unsupportedTransformsDigestTruncationXpointer() {
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("",
            "<Transforms><Transform Algorithm=\"http://www.w3.org/TR/1999/REC-xpath-19991116\">" +
                "<XPath>not(ancestor-or-self::*[local-name()='KmsMessage'])</XPath></Transform></Transforms>", documentMinusSignature)), key),
            key, "XPath transform excluding part of the payload", "unsupported Reference transform")
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", "", canonicalPayload, "http://www.w3.org/2000/09/xmldsig#sha1")), key),
            key, "SHA-1 digest", "unsupported digest method")
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", "", canonicalPayload)), key,
            "<HMACOutputLength>128</HMACOutputLength>"), key, "truncated HMAC", "signature length is 256")
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("#xpointer(id('resp'))", "", canonicalPayload)), key),
            key, "scheme-based XPointer", "unsupported Reference URI")
    }

    @Test fun structure() {
        refused("<!DOCTYPE SignedKmsResponse [<!ATTLIST KmsResponse Id ID #IMPLIED>]>$byId", key, "DTD", "forbidden DTD")
        val signature = byId.substring(byId.indexOf("<Signature"))
        val inside = rootOpen + "<KmsResponse Version=\"1.0.0\" xml:id=\"resp\">" + payloadBody +
            signature.substring(0, signature.length - rootClose.length) + "</KmsResponse>" + rootClose
        refused(inside, key, "Signature inside the KmsResponse", "one ds:Signature")
        refused(rootOpen + payload + rootClose, key, "no Signature", "one ds:Signature")
        refused(byId, ByteArray(24) { 1 }, "24-octet key", "128 or 256 bits")
    }
    // The shared core's last case (parse_kms_init stops at a signed response) belongs to its KMS documents, not to the
    // signature code; the Android KMS client has no such parser path, so it is not ported here.

    // ---------------------------------------------------------------------------------------------------------------
    // Further cases of this port (each also run against the shared core when written: same outcome and reason).

    @Test fun canonicalizationOfSignedInfo() {
        // SignedInfo under Canonical XML 1.1 and Exclusive XML Canonicalization (its canonical form is the same text).
        for (method in listOf(c14n11, exc, "$c14n10#WithComments", "${exc}WithComments"))
            assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, payload,
                listOf(Ref("#resp", "", canonicalPayload)), key, signedInfoMethod = method), key))
        // A comment in SignedInfo is signed under #WithComments ...
        val withComments = signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", "", canonicalPayload)), key,
            "<!--c-->", signedInfoMethod = "$c14n10#WithComments")
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(withComments, key))
        // ... and left out of it otherwise: the same MAC over a comment-bearing SignedInfo then does not verify.
        refused(withComments.replace("$c14n10#WithComments", c14n10).let { text ->
            val body = text.substring(text.indexOf("<SignedInfo>") + 12, text.indexOf("</SignedInfo>"))
            text.replace(Regex("<SignatureValue>[^<]*"), "<SignatureValue>" + mac(key, "<SignedInfo xmlns=\"$dsig\">$body</SignedInfo>"))
        }, key, "comment signed under a method without comments", "signature value mismatch")
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("#resp", "", canonicalPayload)), key,
            signedInfoMethod = "http://www.w3.org/2006/12/xml-c14n2"), key, "Canonical XML 2.0", "unsupported canonicalization method")
    }

    /** Exclusive XML Canonicalization clause 3: a prefix of the InclusiveNamespaces PrefixList is rendered as in C14N. */
    @Test fun inclusiveNamespacesPrefixList() {
        val open = "<SignedKmsResponse xmlns=\"$kmsns\" xmlns:p=\"urn:example:listed\" xmlns:q=\"urn:example:not-listed\">"
        val withP = "<KmsResponse xmlns=\"$kmsns\" xmlns:p=\"urn:example:listed\" Version=\"1.0.0\" xml:id=\"resp\">$payloadBody</KmsResponse>"
        fun chain(list: String) = "<Transforms><Transform Algorithm=\"$exc\"><InclusiveNamespaces xmlns=\"$exc\" PrefixList=\"$list\">" +
            "</InclusiveNamespaces></Transform></Transforms>"
        val listed = signedMessage(open, rootClose, payload, listOf(Ref("#resp", chain("p"), withP)), key, signedInfoMethod = exc)
        assertEquals(withP, KmsSignature.verifyPayload(listed, key))
        refused(signedMessage(open, rootClose, payload, listOf(Ref("#resp", chain("p"), canonicalPayload)), key, signedInfoMethod = exc),
            key, "listed prefix left out of the digest", "reference digest mismatch")
        assertEquals(withP, KmsSignature.verifyPayload(signedMessage(open, rootClose, payload,
            listOf(Ref("#resp", chain("  #default p  unknown "), withP)), key, signedInfoMethod = exc), key))
        refused(signedMessage(open, rootClose, payload, listOf(Ref("#resp",
            "<Transforms><Transform Algorithm=\"$exc\"><Other xmlns=\"$exc\"></Other></Transform></Transforms>", canonicalPayload)), key,
            signedInfoMethod = exc), key, "parameter other than InclusiveNamespaces", "unexpected canonicalization parameter")
    }

    @Test fun severalReferences() {
        // Every Reference valid, the covering one need not be the first.
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, payload,
            listOf(Ref("#msg", "", canonicalMessage), Ref("", transform, documentMinusSignature)), key), key))
        // The payload is returned by the canonicalization of the first covering Reference (here exclusive).
        val open = "<SignedKmsResponse xmlns=\"$kmsns\" xmlns:p=\"urn:example:unsigned\">"
        val inclusiveForm = "<KmsResponse xmlns=\"$kmsns\" xmlns:p=\"urn:example:unsigned\" Version=\"1.0.0\" xml:id=\"resp\">$payloadBody</KmsResponse>"
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(open, rootClose, payload, listOf(
            Ref("#resp", "<Transforms><Transform Algorithm=\"$exc\"></Transform></Transforms>", canonicalPayload),
            Ref("#resp", "", inclusiveForm)), key, signedInfoMethod = exc), key))
    }

    @Test fun transformRules() {
        val twice = "<Transforms><Transform Algorithm=\"$enveloped\"></Transform><Transform Algorithm=\"$enveloped\"></Transform></Transforms>"
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("", twice, documentMinusSignature)), key),
            key, "two enveloped transforms", "repeated enveloped signature transform")
        val after = "<Transforms><Transform Algorithm=\"$c14n10\"></Transform><Transform Algorithm=\"$enveloped\"></Transform></Transforms>"
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("", after, documentMinusSignature)), key),
            key, "transform after canonicalization", "unsupported transform after canonicalization")
        val parameter = "<Transforms><Transform Algorithm=\"$enveloped\"><X></X></Transform></Transforms>"
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("", parameter, documentMinusSignature)), key),
            key, "enveloped transform with a parameter", "unexpected transform parameter")
        refused(signedMessage(rootOpen, rootClose, payload, listOf(Ref("", "<Transforms><X></X></Transforms>", documentMinusSignature)), key),
            key, "other element in Transforms", "unexpected element in Transforms")
        // The null URI, enveloped transform then Canonical XML 1.1 with comments: comments are still not in the node-set.
        val c11 = "<Transforms><Transform Algorithm=\"$enveloped\"></Transform><Transform Algorithm=\"$c14n11#WithComments\"></Transform></Transforms>"
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, payload,
            listOf(Ref("", c11, documentMinusSignature)), key).insertAt("<KmsUri>", "<!--x-->"), key))
        // An empty Transforms element is no transform.
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, payload,
            listOf(Ref("#resp", "<Transforms></Transforms>", canonicalPayload)), key), key))
    }

    @Test fun signatureStructure() {
        refused(byId.replace("<Reference URI=\"#resp\">", "<Reference>").replace(Regex("<SignatureValue>[^<]*"), "<SignatureValue>AAAA"),
            key, "Reference without URI", "Reference without URI is not supported")
        refused(byId.replace(hmacSha256, "http://www.w3.org/2000/09/xmldsig#hmac-sha1"), key, "HMAC-SHA1", "unsupported signature method")
        refused(byId.replace("</SignatureMethod>", "<Other></Other></SignatureMethod>"), key, "other signature method parameter",
            "unexpected signature method parameter")
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(signedMessage(rootOpen, rootClose, payload,
            listOf(Ref("#resp", "", canonicalPayload)), key, "<HMACOutputLength>\n 256\t</HMACOutputLength>"), key))
        refused(byId.replace("<SignedInfo>", "<SignedInfo>text"), key, "text in SignedInfo", "unexpected text")
        refused(byId.replace("<KeyInfo><KeyName>AAAAAQ==</KeyName></KeyInfo>", "<Object></Object><KeyInfo></KeyInfo>"), key,
            "KeyInfo after an Object", "invalid Signature")
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(byId.replace("</KeyInfo>", "</KeyInfo><Object></Object><Object></Object>"), key))
        refused(byId.replace("SignedKmsResponse", "SignedKmsResult"), key, "other root", "not a signed KMS message")
        refused(byId.substring(0, byId.length - 5), key, "truncated document", "malformed XML")
        refused("", key, "empty document", "empty or oversized XML")
        // The SignatureValue's base64 may be split over lines.
        val split = byId.replace(Regex("<SignatureValue>([^<]{20})([^<]*)"), "<SignatureValue>\n\$1\r\n \$2\t")
        assertEquals(canonicalPayload, KmsSignature.verifyPayload(split, key))
        refused(byId.replace(Regex("<SignatureValue>[^<]*"), "<SignatureValue>!!!!"), key, "invalid base64", "invalid base64")
    }

    @Test fun keyName() {
        assertEquals("AAAAAQ==", KmsSignature.keyName(byId))
        assertEquals("", KmsSignature.keyName(rootOpen + payload + rootClose))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Signing.

    @Test fun signRoundTripAndTampering() {
        val response = "<KmsResponse xmlns:x=\"urn:example\" Id=\"resp-1\" Version=\"1.0.0\" x:a=\"1\"><UserUri>sip:a@example.org</UserUri>" +
            "<!-- c --><KmsMessage><KmsInit Version=\"1.0.0\"/></KmsMessage></KmsResponse>"
        val signed = KmsSignature.sign(response, key, "AAAAAQ==")
        assertTrue(signed.contains("<SignedKmsResponse "))
        assertEquals("<KmsResponse xmlns=\"$kmsns\" xmlns:x=\"urn:example\" Id=\"resp-1\" Version=\"1.0.0\" x:a=\"1\">" +
            "<UserUri>sip:a@example.org</UserUri><KmsMessage><KmsInit Version=\"1.0.0\"></KmsInit></KmsMessage></KmsResponse>",
            KmsSignature.verifyPayload(signed, key))
        assertEquals("AAAAAQ==", KmsSignature.keyName(signed))
        refused(signed, otherKey, "wrong key", "signature value mismatch")
        refused(signed.replace("sip:a@", "sip:b@"), key, "changed payload", "reference digest mismatch")
        val digestAt = signed.indexOf("<ds:DigestValue>") + 16
        refused(signed.substring(0, digestAt) + (if (signed[digestAt] == 'A') 'B' else 'A') + signed.substring(digestAt + 1),
            key, "changed DigestValue", "reference digest mismatch")
        val valueAt = signed.indexOf("<ds:SignatureValue>") + 19
        refused(signed.substring(0, valueAt) + (if (signed[valueAt] == 'A') 'B' else 'A') + signed.substring(valueAt + 1),
            key, "changed SignatureValue", "signature value mismatch")
        val shortKey = ByteArray(16) { 0x33 }
        val request = KmsSignature.sign("<KmsRequest Id=\"r\" Version=\"1.1.0\"><UserUri>sip:a@example.org</UserUri></KmsRequest>", shortKey, "a2V5")
        assertTrue(request.contains("<SignedKmsRequest ") && KmsSignature.verifyPayload(request, shortKey).startsWith("<KmsRequest xmlns="))
    }

    @Test fun signRefusals() {
        fun signRefused(payload: String, key: ByteArray, keyName: String, reason: String) {
            try { KmsSignature.sign(payload, key, keyName) } catch (e: KeyError) {
                assertTrue("refused for another reason: ${e.message}", e.message!!.contains(reason)); return
            }
            fail("signed: $payload")
        }
        signRefused("<KmsResponse Id=\"a\"/>", ByteArray(24), "k", "128 or 256 bits")
        signRefused("<Other Id=\"a\"/>", key, "k", "not a KmsRequest or KmsResponse")
        signRefused("<KmsResponse Version=\"1.0.0\"/>", key, "k", "needs an Id")
        signRefused("<KmsResponse Id=\"a b\"/>", key, "k", "needs an Id")
        signRefused("<KmsResponse Id=\"a\"/>", key, "", "invalid KeyName")
        signRefused("<KmsResponse Id=\"a\"/>", key, "a\"b", "invalid KeyName")
    }

    /** The signed text equals the shared core's sign_payload output for the same payload, key and KeyName. */
    @Test fun signMatchesTheSharedCore() {
        val response = "<KmsResponse xmlns:x=\"urn:example\" Id=\"resp-1\" Version=\"1.0.0\" x:a=\"1\"><UserUri>sip:a@example.org</UserUri>" +
            "<!-- c --><KmsMessage><KmsInit Version=\"1.0.0\"/></KmsMessage></KmsResponse>"
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><SignedKmsResponse xmlns=\"$kmsns\">$response<ds:Signature " +
            "xmlns:ds=\"$dsig\"><ds:SignedInfo><ds:CanonicalizationMethod Algorithm=\"$c14n10\"/><ds:SignatureMethod Algorithm=\"$hmacSha256\"/>" +
            "<ds:Reference URI=\"#resp-1\"><ds:DigestMethod Algorithm=\"$sha256\"/><ds:DigestValue>nnC+ghSHnGu0p+KN5NQ2/sYj9pmsqa4uFLhpTmLkMO8=" +
            "</ds:DigestValue></ds:Reference></ds:SignedInfo><ds:SignatureValue>mf1Qum8OffcSKZEjATBUsdoeOcOvCMW8FRKnJCIei5U=</ds:SignatureValue>" +
            "<ds:KeyInfo><ds:KeyName>AAAAAQ==</ds:KeyName></ds:KeyInfo></ds:Signature></SignedKmsResponse>",
            KmsSignature.sign(response, key, "AAAAAQ=="))
        assertTrue(KmsSignature.sign("<KmsRequest Id=\"r\" Version=\"1.1.0\"><UserUri>sip:a@example.org</UserUri></KmsRequest>",
            ByteArray(16) { 0x33 }, "a2V5").contains("<ds:DigestValue>08mazcR/wS1cLkb6PEez1SD0GAG1XC8GUsxObZX8tRo=</ds:DigestValue>" +
            "</ds:Reference></ds:SignedInfo><ds:SignatureValue>lZLzKoX2OSkVz/GjkeMz2WIy5uhKJ9bng55gaDHs5mQ=</ds:SignatureValue>"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Canonical forms of document subsets, as the shared core's libxml2 computes them.

    private fun find(doc: Document, id: String?): Element? {
        if (id == null) return null
        var found: Element? = null
        fun walk(e: Element) {
            if (e.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id") == id || e.getAttributeNodeNS(null, "Id")?.value == id) found = e
            val c = e.childNodes
            for (i in 0 until c.length) (c.item(i) as? Element)?.let(::walk)
        }
        walk(doc.documentElement)
        return found
    }

    private fun canonical(xml: String, apex: String?, excluded: String?, comments: Boolean, method: String, prefixes: List<String> = emptyList()): String {
        val doc = KmsSignature.parse(xml)
        return String(KmsSignature.c14n(doc, KmsSignature.NodeSet(find(doc, apex), find(doc, excluded), comments),
            KmsSignature.canonicalizationOf(method)!!, prefixes), Charsets.UTF_8)
    }

    @Test fun canonicalFormsOfLibxml2() {
        val ns = "<?xml version=\"1.0\"?>\n<?pi before?>\n<!-- c before -->\n<r xmlns=\"urn:d\" xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" " +
            "xml:lang=\"en\" xml:space=\"preserve\" xml:base=\"http://e/x/\" Id=\"root\">\n <!-- in --><a:x xml:id=\"x\" b:at=\"1\" at=\"2\" " +
            "a:z=\"3\"><y xmlns=\"\" Id=\"y\"><z xmlns=\"urn:d\" xml:id=\"z\" xmlns:a=\"urn:a\">t<?p i?></z><w xmlns:c=\"urn:c\" c:q=\"\" " +
            "xml:id=\"w\"/></y></a:x>\n <s xml:id=\"sig\"><k xml:id=\"k\">ignored</k></s>\n</r>\n<?pi after?>\n<!-- c after -->"
        // The whole document less an element, with comments: document-level nodes on their own lines.
        assertEquals("<?pi before?>\n<!-- c before -->\n<r xmlns=\"urn:d\" xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" Id=\"root\" " +
            "xml:base=\"http://e/x/\" xml:lang=\"en\" xml:space=\"preserve\">\n <!-- in --><a:x at=\"2\" xml:id=\"x\" a:z=\"3\" b:at=\"1\">" +
            "<y xmlns=\"\" Id=\"y\"><z xmlns=\"urn:d\" xml:id=\"z\">t<?p i?></z><w xmlns:c=\"urn:c\" xml:id=\"w\" c:q=\"\"></w></y></a:x>\n \n" +
            "</r>\n<?pi after?>\n<!-- c after -->", canonical(ns, null, "sig", true, "$c14n11#WithComments"))
        // Canonical XML 1.0: the apex inherits every xml: attribute (also xml:id) of its omitted ancestors; xmlns="" not
        // rendered without a rendered default namespace above.
        assertEquals("<y xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" Id=\"y\" xml:base=\"http://e/x/\" xml:id=\"x\" xml:lang=\"en\" " +
            "xml:space=\"preserve\"><z xmlns=\"urn:d\" xml:id=\"z\">t<?p i?></z><w xmlns:c=\"urn:c\" xml:id=\"w\" c:q=\"\"></w></y>",
            canonical(ns, "y", null, false, c14n10))
        val xmlattrs = "<r xmlns=\"urn:d\" xml:lang=\"fr\" xml:space=\"default\" xml:base=\"http://h/a/\" xml:id=\"r\"><m xml:lang=\"de\">" +
            "<e xml:id=\"e\" a=\"1\"><f xml:base=\"b/\" xml:id=\"f\"/></e></m><g xml:base=\"\" xml:id=\"g\"/></r>"
        val inherited = "<e xmlns=\"urn:d\" a=\"1\" xml:base=\"http://h/a/\" xml:id=\"e\" xml:lang=\"de\" xml:space=\"default\">" +
            "<f xml:base=\"b/\" xml:id=\"f\"></f></e>"
        assertEquals(inherited, canonical(xmlattrs, "e", null, false, c14n10))
        assertEquals(inherited, canonical(xmlattrs, "e", null, false, c14n11))
        // Escaping (clause 2.3), CDATA as text, PIs and comments.
        val esc = "<r xmlns=\"urn:d\" Id=\"r\"><e Id=\"e\" t=\"a&#9;b&#10;c&#13;d&quot;&amp;&lt;&gt;'\" u=\"&#x20AC;\">x &amp; &lt; &gt; " +
            "&#13; &#x20AC; <![CDATA[<c>&]]> \n<?p  data &#13;?><!--x&#13;y--></e></r>"
        assertEquals("<e xmlns=\"urn:d\" Id=\"e\" t=\"a&#x9;b&#xA;c&#xD;d&quot;&amp;&lt;>'\" u=\"€\">x &amp; &lt; &gt; &#xD; € " +
            "&lt;c&gt;&amp; \n<?p data &#13;?><!--x&#13;y--></e>", canonical(esc, "e", null, true, "$c14n10#WithComments"))
        // Exclusive XML Canonicalization: only visibly utilized namespaces, xmlns="" only where a default was rendered.
        val exclusive = "<r xmlns=\"urn:d\" xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" xmlns:u=\"urn:unused\"><a:e xml:id=\"e\" b:x=\"1\">" +
            "<f xml:id=\"f\"/><g xmlns=\"\" xml:id=\"g\"><h xml:id=\"h\"/></g><u:i xml:id=\"i\"/></a:e><n xmlns=\"\" xml:id=\"n\">" +
            "<o xmlns=\"urn:o\" xml:id=\"o\"/></n></r>"
        assertEquals("<g xml:id=\"g\"><h xml:id=\"h\"></h></g>", canonical(exclusive, "g", null, false, exc))
        assertEquals("<n xml:id=\"n\"><o xmlns=\"urn:o\" xml:id=\"o\"></o></n>", canonical(exclusive, "n", null, false, exc))
        assertEquals("<a:e xmlns=\"urn:d\" xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" xmlns:u=\"urn:unused\" xml:id=\"e\" b:x=\"1\"><f xml:id=\"f\">" +
            "</f><g xmlns=\"\" xml:id=\"g\"><h xml:id=\"h\"></h></g><u:i xml:id=\"i\"></u:i></a:e>",
            canonical(exclusive, "e", null, false, exc, listOf("#default", "a", "b", "u")))
        // libxml2 keeps an '&' of a namespace URI as "&#38;" (see Ns in KmsSignature).
        assertEquals("<e xmlns=\"urn:d\" xmlns:p=\"http://x/?a=1&#38;b=2\" xml:id=\"e\" p:a=\"v\"></e>",
            canonical("<r xmlns=\"urn:d\"><e xml:id=\"e\" xmlns:p=\"http://x/?a=1&amp;b=2\" p:a=\"v\"/></r>", "e", null, false, c14n10))
    }

    @Test fun canonicalizationFailures() {
        // Canonical XML 1.0 clause 2.1: a relative namespace URI anywhere in the document fails, as in libxml2.
        for (uri in listOf("rel/path", "http://x/a b", "http://x/%zz", "http://x/é")) {
            val doc = "<r xmlns=\"urn:d\"><bad xmlns:p=\"$uri\"/><e xml:id=\"e\"/></r>"
            try { canonical(doc, "e", null, false, c14n10); fail("canonicalized with $uri") } catch (e: KeyError) {
                assertEquals("XML canonicalization failed", e.message)
            }
        }
    }

    /** Canonical XML 1.1 clause 2.4: the xml:base fix-up over the omitted ancestors of the apex. */
    @Test fun xmlBaseFixUp() {
        fun e(xml: String, id: String = "e") = canonical(xml, id, null, false, c14n11)
        // Joined values (libxml2 gives the same).
        assertEquals("<e xmlns=\"urn:d\" xml:base=\"http://h/a/b/\" xml:id=\"e\"></e>",
            e("<r xmlns=\"urn:d\" xml:base=\"http://h/a/\"><m xml:base=\"b/\"><e xml:id=\"e\"/></m></r>"))
        assertEquals("<d xmlns=\"urn:d\" xml:base=\"../x\" xml:id=\"d\"></d>",
            e("<a xmlns=\"urn:d\" xml:base=\"foo/bar\"><b xml:base=\"..\"><c xml:base=\"..\"><d xml:base=\"x\" xml:id=\"d\"/></c></b></a>", "d"))
        // A fixed-up value that is empty is not rendered.
        assertEquals("<e xmlns=\"urn:d\" xml:id=\"e\"></e>", e("<r xmlns=\"urn:d\"><m xml:base=\"abc/\"><e xml:id=\"e\" xml:base=\"../\"/></m></r>"))
        // The fragment of a joined value is dropped (libxml2 keeps "#x").
        assertEquals("<e xmlns=\"urn:d\" xml:base=\"http://h/c\" xml:id=\"e\"></e>",
            e("<r xmlns=\"urn:d\" xml:base=\"http://h/a?q\"><m xml:base=\"#f\"><e xml:id=\"e\" xml:base=\"../../c#x\"/></m></r>"))
        // No fix-up: the omitted ancestor's single xml:base is taken as it is; an element's own xml:base, also empty,
        // is an ordinary attribute of the node-set (libxml2 drops the empty one).
        assertEquals("<e xmlns=\"urn:d\" xml:base=\"http://h/a/\" xml:id=\"e\"></e>",
            e("<r xmlns=\"urn:d\" xml:base=\"http://h/a/\"><e xml:id=\"e\"/></r>"))
        assertEquals("<e xmlns=\"urn:d\" xml:base=\"a/../b\" xml:id=\"e\"></e>", e("<r xmlns=\"urn:d\"><e xml:id=\"e\" xml:base=\"a/../b\"/></r>"))
        assertEquals("<r xmlns=\"urn:d\" xml:base=\"\"><e xml:id=\"e\"></e></r>",
            canonical("<r xmlns=\"urn:d\" xml:base=\"\"><e xml:id=\"e\"/></r>", null, null, false, c14n11))
        // Canonical XML 1.0 has no fix-up: the nearest omitted ancestor's xml:base is inherited.
        assertEquals("<e xmlns=\"urn:d\" xml:base=\"b/\" xml:id=\"e\"></e>",
            canonical("<r xmlns=\"urn:d\" xml:base=\"http://h/a/\"><m xml:base=\"b/\"><e xml:id=\"e\"/></m></r>", "e", null, false, c14n10))
    }
}
