//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The XML signature of a signed KMS message (SignedKmsRequest, SignedKmsResponse) under an integrity key the caller has
 * already derived (TS 33.180 clause 9.3.3, annex F.1.4 FC 0x52), on plain DOM, without javax.xml.crypto (not on
 * Android). It accepts and refuses what the shared C++ core does (include/mcx/kms_xml_signature.hpp of the server
 * repository, on libxml2's canonicalization); anything else is refused, not skipped:
 * - SignatureMethod HMAC-SHA256 with a 256-bit output (TS 33.180 annex D.1), HMACOutputLength absent or 256.
 * - DigestMethod SHA-256 (TS 33.180 clause 9.3.5).
 * - CanonicalizationMethod and canonicalization Transform: Canonical XML 1.0 and 1.1 (REQUIRED, XMLDSig Second Edition
 *   clause 6.1), with comments (RECOMMENDED), and Exclusive XML Canonicalization 1.0 with its InclusiveNamespaces
 *   PrefixList (clause 6.5).
 * - Reference URI: the null URI (the whole document) and a shortname XPointer "#id" (clause 4.3.3.2), "#id" resolved
 *   through xml:id or the unqualified Id attribute of the KMS schema. The schema types Id xsd:string, not xsd:ID
 *   (TS 33.180 annex D.3.5.1, reported); treating it as an ID is the reading of the ETSI test suite for TS 37.579
 *   (maintainer decision 2026-11-01), as the shared core. A duplicate ID is refused.
 * - Transforms: the enveloped signature transform (REQUIRED, clause 6.6.4), then at most one canonicalization.
 * The signature must cover the whole KmsRequest or KmsResponse (TS 33.180 annexes D.1, D.2.2 and D.3.1): one of its
 * References must identify that element or an ancestor of it, and every Reference must validate.
 * The xml:base fix-up of Canonical XML 1.1 (clause 2.4) follows the Recommendation's text, as the shared core does
 * (libxml2's own fix-up, which it does not use, differs in edge cases: a trailing "..", the fragment of a joined value,
 * the dot segments of an absolute value, an empty xml:base when no fix-up is made). Example 3.8 of the Recommendation
 * is read as corrected by W3C erratum E11-01 (2008): only the contiguously omitted ancestors are joined. (The node-sets
 * here, a subtree less a subtree, have no rendered element between omitted ones, the case of that example.)
 * Specifications: TS 33.180 V19.5.0 (Rel-19) clauses 9.3.3 and 9.3.5, annexes D.1, D.2.2, D.3.1 and D.3.5.1; W3C XML
 * Signature Syntax and Processing (Second Edition, 2008) clauses 3.2, 4.3.3 and 6; W3C Canonical XML 1.0 and 1.1; W3C
 * Exclusive XML Canonicalization 1.0; W3C xml:id.
 */
object KmsSignature {
    private const val KMSNS = "urn:3gpp:ns:mcsecKMSInterface:1.0"
    private const val DSIG = "http://www.w3.org/2000/09/xmldsig#"
    private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"
    private const val C14N10 = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"
    private const val C14N11 = "http://www.w3.org/2006/12/xml-c14n11"
    private const val EXCLUSIVE = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private const val ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"
    private const val HMAC_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#hmac-sha256"
    private const val SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"

    private fun need(condition: Boolean, what: String) { if (!condition) throw KeyError(what) }

    // ------------------------------------------------------------------------------------------------------------
    // Parsing and element helpers.

    /**
     * A document parsed without network access, at most 1 MiB, without a DTD (no DTD-declared IDs, defaults or
     * entities). The DOCTYPE is found in the prolog before parsing, so a parser without the disallow feature (Android's)
     * refuses it the same way.
     */
    fun parse(xml: String): Document {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        need(bytes.isNotEmpty() && bytes.size <= 1 shl 20, "empty or oversized XML")
        need(!prologHasDoctype(xml), "forbidden DTD")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isCoalescing = false
            isIgnoringComments = false
        }
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )) try { factory.setFeature(feature.first, feature.second) } catch (_: Exception) {}
        val doc = try {
            factory.newDocumentBuilder().apply {
                setErrorHandler(object : ErrorHandler {
                    override fun warning(e: SAXParseException) {}
                    override fun error(e: SAXParseException) = throw e
                    override fun fatalError(e: SAXParseException) = throw e
                })
            }.parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw KeyError("malformed XML")
        }
        need(doc.documentElement != null, "malformed XML")
        need(doc.doctype == null, "forbidden DTD")
        return doc
    }

    /** Whether a DOCTYPE comes before the root element: after the XML declaration, white space, comments and PIs. */
    private fun prologHasDoctype(xml: String): Boolean {
        var at = if (xml.startsWith("﻿")) 1 else 0
        while (at < xml.length) {
            when {
                xml[at] == ' ' || xml[at] == '\t' || xml[at] == '\r' || xml[at] == '\n' -> at++
                xml.startsWith("<!--", at) -> at = xml.indexOf("-->", at + 4).let { if (it < 0) return false else it + 3 }
                xml.startsWith("<?", at) -> at = xml.indexOf("?>", at + 2).let { if (it < 0) return false else it + 2 }
                else -> return xml.startsWith("<!DOCTYPE", at)
            }
        }
        return false
    }

    private fun named(node: Node?, name: String, ns: String) =
        node is Element && node.localName == name && node.namespaceURI == ns

    /** The value of the attribute without a namespace (libxml2 xmlGetNoNsProp); empty when absent. */
    private fun attribute(element: Element, name: String): String = element.getAttributeNodeNS(null, name)?.value ?: ""

    private fun isBlank(text: String) = text.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

    /** The element children of a node; any text child other than white space is refused. */
    private fun elements(parent: Node): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) out += node
            else if (node.nodeType == Node.TEXT_NODE) need(isBlank(node.nodeValue ?: ""), "unexpected text")
        }
        return out
    }

    /**
     * The base64 octets of an element's text (RFC 4648 section 4), white space ignored, decoded as OpenSSL's
     * EVP_DecodeBlock does for the shared core ('=' read as zero bits, the padding octets then dropped).
     */
    private fun unbase64(text: String): ByteArray {
        val clean = text.filter { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        need(clean.isNotEmpty() && clean.length % 4 == 0, "invalid base64")
        val out = ByteArray(clean.length / 4 * 3)
        var o = 0
        for (q in clean.indices step 4) {
            var block = 0
            for (k in 0 until 4) {
                val c = clean[q + k]
                val value = when (c) {
                    in 'A'..'Z' -> c - 'A'
                    in 'a'..'z' -> c - 'a' + 26
                    in '0'..'9' -> c - '0' + 52
                    '+' -> 62
                    '/' -> 63
                    '=' -> 0
                    else -> throw KeyError("invalid base64")
                }
                block = (block shl 6) or value
            }
            out[o++] = (block shr 16).toByte(); out[o++] = (block shr 8).toByte(); out[o++] = block.toByte()
        }
        val drop = if (clean.endsWith("==")) 2 else if (clean.endsWith("=")) 1 else 0
        return out.copyOf(out.size - drop)
    }

    private fun sha256(octets: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(octets)

    private fun hmacSha256(key: ByteArray, octets: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(octets)

    // ------------------------------------------------------------------------------------------------------------
    // Canonicalization (XMLDSig Second Edition clauses 6.1 and 6.5).

    internal enum class Mode { C14N_1_0, C14N_1_1, EXCLUSIVE_1_0 }

    /** A canonicalization algorithm and whether it keeps comments. */
    internal data class Canonicalization(val mode: Mode, val comments: Boolean)

    /** XMLDSig Second Edition clauses 6.1 and 6.5: the algorithms supported; null for another identifier. */
    internal fun canonicalizationOf(algorithm: String): Canonicalization? = when (algorithm) {
        C14N10 -> Canonicalization(Mode.C14N_1_0, false)
        "$C14N10#WithComments" -> Canonicalization(Mode.C14N_1_0, true)
        C14N11 -> Canonicalization(Mode.C14N_1_1, false)
        "$C14N11#WithComments" -> Canonicalization(Mode.C14N_1_1, true)
        EXCLUSIVE -> Canonicalization(Mode.EXCLUSIVE_1_0, false)
        "${EXCLUSIVE}WithComments" -> Canonicalization(Mode.EXCLUSIVE_1_0, true)
        else -> null
    }

    /** Exclusive XML Canonicalization 1.0 clause 3: the InclusiveNamespaces PrefixList of a method element. */
    private fun inclusivePrefixes(method: Element, canonicalization: Canonicalization): List<String> {
        if (canonicalization.mode != Mode.EXCLUSIVE_1_0) return emptyList()
        val out = mutableListOf<String>()
        for (node in elements(method)) {
            need(named(node, "InclusiveNamespaces", EXCLUSIVE), "unexpected canonicalization parameter")
            out += attribute(node, "PrefixList").split(' ', '\t', '\r', '\n').filter { it.isNotEmpty() }
        }
        return out
    }

    /**
     * XMLDSig Second Edition clause 4.3.3.3: a node-set of the document: the subtree of [apex] (the whole document
     * without one), less the subtree of [excluded] (the Signature an enveloped signature transform removes, clause
     * 6.6.4), with or without comment nodes.
     */
    internal class NodeSet(val apex: Element?, val excluded: Element?, val comments: Boolean)

    /**
     * A namespace binding: prefix ("" the default) and URI ("" for xmlns=""), the URI as libxml2 keeps it for the
     * shared core: an '&' of the attribute value stays the character reference "&#38;" (libxml2 parses attribute values
     * without replacing entities and namespace URIs are not reparsed), so it is compared, checked and written so.
     */
    private data class Ns(val prefix: String, val href: String)

    private fun ns(prefix: String, uri: String) = Ns(prefix, uri.replace("&", "&#38;"))

    private val xmlNs = Ns("xml", XML_NAMESPACE)

    /** UTF-8 octet order (libxml2 xmlStrcmp). */
    private val utf8Order = Comparator<String> { a, b ->
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return@Comparator d
        }
        x.size - y.size
    }

    /** The canonical form of a node-set by the given algorithm (XMLDSig Second Edition clause 4.3.3.2). */
    internal fun c14n(doc: Document, set: NodeSet, method: Canonicalization, prefixes: List<String>): ByteArray =
        Canonicalizer(doc, NodeSet(set.apex, set.excluded, set.comments && method.comments), method, prefixes).run()

    /**
     * Canonical XML 1.0 (2001) and 1.1 (2008) and Exclusive XML Canonicalization 1.0 (2002) of a document subset,
     * walking the whole document as libxml2 does for the shared core, with its stack of rendered namespaces:
     * - Canonical XML 1.0 clause 2.3 / 1.1 clause 2.3: a namespace node is rendered unless the nearest output ancestor
     *   rendered the same; xmlns="" only where that ancestor has a non-empty default namespace.
     * - Exclusive clause 3: only visibly utilized namespaces (the element's and its attributes') plus those of the
     *   InclusiveNamespaces PrefixList, which are treated as in Canonical XML.
     * - Canonical XML 1.0 clause 2.4: an element whose parent is omitted inherits the xml: attributes of its
     *   ancestors; Canonical XML 1.1 clause 2.4: only xml:lang and xml:space are inherited, xml:id is not, xml:base is
     *   fixed up (an empty fixed-up value is not rendered).
     * - Characters and attribute values escaped as Canonical XML 1.0 clause 2.3 (namespace URIs written as libxml2
     *   writes them); comments and PIs before or after the document element on their own line.
     * Every element of the document must have an absolute namespace URI (Canonical XML 1.0 clause 2.1: "relative
     * namespace URIs ... MUST report an operation failure").
     */
    private class Canonicalizer(val doc: Document, val set: NodeSet, val method: Canonicalization, val prefixes: List<String>) {
        private val out = StringBuilder()
        private val stack = ArrayList<Ns>()
        private var curEnd = 0
        private var prevStart = 0
        private var prevEnd = 0
        private var position = 0 // 0 before, 1 inside, 2 after the document element

        fun run(): ByteArray {
            val children = doc.childNodes
            for (i in 0 until children.length) node(children.item(i))
            return out.toString().toByteArray(Charsets.UTF_8)
        }

        private fun visible(node: Node): Boolean {
            if (!set.comments && node.nodeType == Node.COMMENT_NODE) return false
            var inside = set.apex == null
            var current: Node? = if (node is Attr) node.ownerElement else node
            while (current != null) {
                if (current === set.excluded) return false
                if (current === set.apex) inside = true
                current = current.parentNode
            }
            return inside
        }

        // The rendered-namespace stack (libxml2 xmlC14NVisibleNsStack): entries of the output ancestors' levels.
        private fun add(ns: Ns) {
            while (stack.size > curEnd) stack.removeAt(stack.size - 1)
            stack += ns
            curEnd++
        }

        /** Canonical XML: whether the nearest output ancestor rendered [ns] (the empty default: whether no non-empty one). */
        private fun found(ns: Ns): Boolean {
            val emptyDefault = ns.prefix.isEmpty() && ns.href.isEmpty()
            for (i in curEnd - 1 downTo (if (emptyDefault) 0 else prevStart))
                if (stack[i].prefix == ns.prefix) return stack[i].href == ns.href
            return emptyDefault
        }

        /** Exclusive: whether an output ancestor rendered [ns] as its nearest binding of that prefix. */
        private fun foundExclusive(ns: Ns): Boolean {
            for (i in curEnd - 1 downTo 0) if (stack[i].prefix == ns.prefix) return stack[i].href == ns.href
            return ns.prefix.isEmpty() && ns.href.isEmpty()
        }

        private fun declarations(element: Element): List<Ns> {
            val out = mutableListOf<Ns>()
            val attrs = element.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i) as Attr
                if (a.namespaceURI == XMLNS) out += ns(if (a.prefix == "xmlns") a.localName else "", a.value)
            }
            return out
        }

        /** The nearest declaration of [prefix] in scope at [element] (libxml2 xmlSearchNs); "xml" is always bound. */
        private fun search(element: Element, prefix: String): Ns? {
            if (prefix == "xml") return xmlNs
            var current: Node? = element
            while (current is Element) {
                declarations(current).firstOrNull { it.prefix == prefix }?.let { return it }
                current = current.parentNode
            }
            return null
        }

        private fun isXmlNs(ns: Ns) = ns.prefix == "xml" && ns.href == XML_NAMESPACE

        private fun attributesOf(element: Element): List<Attr> {
            val attrs = element.attributes
            return (0 until attrs.length).map { attrs.item(it) as Attr }.filter { it.namespaceURI != XMLNS }
        }

        private fun isXmlAttr(a: Attr) = a.namespaceURI == XML_NAMESPACE && a.prefix == "xml"

        /** Canonical XML 1.0 clause 2.1: a namespace URI must be absolute (and parse as a URI reference). */
        private fun checkNamespaces(element: Element) {
            for (ns in declarations(element)) if (ns.href.isNotEmpty()) need(absoluteUri(ns.href), "XML canonicalization failed")
        }

        private fun node(node: Node) {
            when (node.nodeType) {
                Node.ELEMENT_NODE -> element(node as Element)
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> if (visible(node)) out.append(escape(node.nodeValue ?: "", TEXT))
                Node.PROCESSING_INSTRUCTION_NODE -> if (visible(node)) {
                    out.append(if (position == 2) "\n<?" else "<?").append(node.nodeName)
                    val data = node.nodeValue ?: ""
                    if (data.isNotEmpty()) out.append(' ').append(escape(data, OTHER))
                    out.append(if (position == 0) "?>\n" else "?>")
                }
                Node.COMMENT_NODE -> if (visible(node) && method.comments) {
                    out.append(if (position == 2) "\n<!--" else "<!--").append(escape(node.nodeValue ?: "", OTHER))
                    out.append(if (position == 0) "-->\n" else "-->")
                }
                Node.ENTITY_REFERENCE_NODE -> throw KeyError("XML canonicalization failed")
                else -> {}
            }
        }

        private fun element(element: Element) {
            val shown = visible(element)
            val saved = Triple(curEnd, prevStart, prevEnd)
            val documentElement = element.parentNode === doc
            if (documentElement) position = 1
            checkNamespaces(element)
            if (shown) out.append('<').append(element.tagName)
            if (method.mode == Mode.EXCLUSIVE_1_0) exclusiveNamespaces(element, shown) else namespaces(element, shown)
            if (shown) { prevStart = prevEnd; prevEnd = curEnd }
            attributes(element, shown)
            if (shown) out.append('>')
            val children = element.childNodes
            for (i in 0 until children.length) node(children.item(i))
            if (shown) out.append("</").append(element.tagName).append('>')
            curEnd = saved.first; prevStart = saved.second; prevEnd = saved.third
            if (documentElement) position = 2
        }

        private val nsOrder = Comparator<Ns> { a, b -> utf8Order.compare(a.prefix, b.prefix) }

        private fun printNamespaces(list: Collection<Ns>) {
            for (ns in list) {
                out.append(if (ns.prefix.isEmpty()) " xmlns=" else " xmlns:${ns.prefix}=")
                // libxml2 xmlBufWriteQuotedString: the URI as it is, in single quotes when it holds a double one.
                val v = ns.href
                if (v.contains('"')) {
                    if (v.contains('\'')) out.append('"').append(v.replace("\"", "&quot;")).append('"')
                    else out.append('\'').append(v).append('\'')
                } else out.append('"').append(v).append('"')
            }
        }

        /** Canonical XML 1.0 and 1.1 clause 2.3: the namespace axis. */
        private fun namespaces(element: Element, shown: Boolean) {
            val list = sortedSetOf(nsOrder)
            var hasDefault = false
            val seen = mutableSetOf<String>()
            var current: Node? = element
            while (current is Element) {
                for (ns in declarations(current)) {
                    if (!seen.add(ns.prefix) || ns.prefix == "xml" || isXmlNs(ns) || !shown) continue
                    val already = found(ns)
                    add(ns)
                    if (!already) list += ns
                    if (ns.prefix.isEmpty()) hasDefault = true
                }
                current = current.parentNode
            }
            if (shown && !hasDefault && !found(Ns("", ""))) printNamespaces(listOf(Ns("", "")))
            printNamespaces(list)
        }

        /** Exclusive XML Canonicalization 1.0 clause 3: visibly utilized namespaces and the InclusiveNamespaces list. */
        private fun exclusiveNamespaces(element: Element, shown: Boolean) {
            val list = sortedSetOf(nsOrder)
            var hasDefault = false
            var defaultInList = false
            var utilizesEmptyDefault = false
            for (token in prefixes) {
                val prefix = if (token == "#default") "".also { defaultInList = true } else token
                val ns = search(element, prefix) ?: continue
                if (isXmlNs(ns) || !shown) continue
                val already = found(ns)
                add(ns)
                if (!already) list += ns
                if (ns.prefix.isEmpty()) hasDefault = true
            }
            val own = if (element.namespaceURI != null) ns(element.prefix ?: "", element.namespaceURI)
            else search(element, "").also { utilizesEmptyDefault = true }
            if (own != null && !isXmlNs(own)) {
                if (shown && !foundExclusive(own)) list += own
                if (shown) add(own)
                if (own.prefix.isEmpty()) hasDefault = true
            }
            if (shown) for (a in attributesOf(element)) {
                val uri = a.namespaceURI ?: continue
                val used = ns(a.prefix ?: "", uri)
                if (isXmlNs(used)) continue
                val already = foundExclusive(used)
                add(used)
                if (!already) list += used
                if (used.prefix.isEmpty()) hasDefault = true
            }
            if (shown && utilizesEmptyDefault && !hasDefault && !defaultInList) {
                if (!foundExclusive(Ns("", ""))) printNamespaces(listOf(Ns("", "")))
            } else if (shown && !hasDefault && defaultInList) {
                if (!found(Ns("", ""))) printNamespaces(listOf(Ns("", "")))
            }
            printNamespaces(list)
        }

        /** An attribute to render: its qualified name, namespace URI (null for none), local name and value. */
        private inner class Item(val name: String, val uri: String?, val local: String, val value: String)

        private val attrOrder = Comparator<Item> { a, b ->
            when {
                a.uri == b.uri -> utf8Order.compare(a.local, b.local)
                a.uri == null -> -1
                b.uri == null -> 1
                else -> utf8Order.compare(a.uri, b.uri).let { if (it != 0) it else utf8Order.compare(a.local, b.local) }
            }
        }

        private fun item(a: Attr) = Item(a.name, a.namespaceURI, a.localName ?: a.name, a.value)

        /** The nearest xml:[local] on [start] and its omitted ancestors, up to the first ancestor in the node-set. */
        private fun hiddenParentAttr(start: Node?, local: String): Attr? {
            var current = start
            while (current != null && !visible(current)) {
                if (current is Element) current.getAttributeNodeNS(XML_NAMESPACE, local)?.let { return it }
                current = current.parentNode
            }
            return null
        }

        /** The attribute axis (Canonical XML 1.0 and 1.1 clause 2.4, Exclusive clause 3). */
        private fun attributes(element: Element, shown: Boolean) {
            if (!shown) return
            val list = mutableListOf<Item>()
            when (method.mode) {
                Mode.EXCLUSIVE_1_0 -> attributesOf(element).forEach { list += item(it) }
                Mode.C14N_1_0 -> {
                    attributesOf(element).forEach { list += item(it) }
                    val parent = element.parentNode
                    if (parent != null && !visible(parent)) {
                        var current: Node? = parent
                        while (current != null) {
                            if (current is Element) for (a in attributesOf(current)) {
                                if (isXmlAttr(a) && list.none { attrOrder.compare(it, item(a)) == 0 }) list += item(a)
                            }
                            current = current.parentNode
                        }
                    }
                }
                Mode.C14N_1_1 -> {
                    var lang: Attr? = null
                    var space: Attr? = null
                    var base: Attr? = null
                    for (a in attributesOf(element)) {
                        if (!isXmlAttr(a)) { list += item(a); continue }
                        when {
                            lang == null && a.localName == "lang" -> lang = a
                            space == null && a.localName == "space" -> space = a
                            base == null && a.localName == "base" -> base = a
                            else -> list += item(a)
                        }
                    }
                    (lang ?: hiddenParentAttr(element.parentNode, "lang"))?.let { list += item(it) }
                    (space ?: hiddenParentAttr(element.parentNode, "space"))?.let { list += item(it) }
                    fixedBase(element, base)?.let { (attr, value) -> list += Item(attr.name, attr.namespaceURI, "base", value) }
                }
            }
            for (a in list.sortedWith(attrOrder))
                out.append(' ').append(a.name).append("=\"").append(escape(a.value, ATTRIBUTE)).append('"')
        }

        /**
         * Canonical XML 1.1 clause 2.4, the xml:base of [element] (whose own is [own]): "only performed if at least one
         * of E1 ... En" (its contiguously omitted ancestors) "had an xml:base attribute"; then X1 ... Xm, the values on
         * E1 ... En+1, are "reduced in reverse document order to a single value by first combining Xm with Xm-1, then
         * the result with Xm-2, and so on" with join-URI-References; an empty or null result "MUST NOT be rendered".
         * Without a fix-up [own] is an ordinary attribute of the node-set. Returns the attribute naming the output
         * attribute and its value, or null when none is rendered.
         */
        private fun fixedBase(element: Element, own: Attr?): Pair<Attr, String>? {
            val values = ArrayList<String>() // Xm first (innermost)
            own?.let { values += it.value }
            var named = own
            var omitted = false
            var current: Node? = element.parentNode
            while (current != null && !visible(current)) {
                if (current is Element) current.getAttributeNodeNS(XML_NAMESPACE, "base")?.let {
                    values += it.value; omitted = true; if (named == null) named = it
                }
                current = current.parentNode
            }
            if (!omitted) return own?.let { it to it.value }
            var value = values[0]
            for (outer in values.drop(1)) value = XmlBase.join(outer, value)
            return if (value.isEmpty()) null else named!! to value
        }
    }

    private const val TEXT = 0
    private const val ATTRIBUTE = 1
    private const val OTHER = 2

    /** Canonical XML 1.0 clause 2.3: text, attribute values, and comments/PIs (only #xD). */
    private fun escape(s: String, mode: Int): String {
        val out = StringBuilder(s.length)
        for (c in s) when {
            c == '\r' -> out.append("&#xD;")
            mode == OTHER -> out.append(c)
            c == '&' -> out.append("&amp;")
            c == '<' -> out.append("&lt;")
            c == '>' && mode == TEXT -> out.append("&gt;")
            c == '"' && mode == ATTRIBUTE -> out.append("&quot;")
            c == '\t' && mode == ATTRIBUTE -> out.append("&#x9;")
            c == '\n' && mode == ATTRIBUTE -> out.append("&#xA;")
            else -> out.append(c)
        }
        return out.toString()
    }

    /** RFC 3986 clause 3: a scheme, then only URI characters (unreserved, reserved, percent-encoded octets). */
    private fun absoluteUri(uri: String): Boolean {
        val colon = uri.indexOf(':')
        if (colon < 1 || !uri[0].isAsciiLetter() ||
            !uri.substring(1, colon).all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return false
        var i = colon + 1
        while (i < uri.length) {
            val c = uri[i]
            if (c == '%') {
                if (i + 2 >= uri.length || !uri[i + 1].isHex() || !uri[i + 2].isHex()) return false
                i += 3; continue
            }
            if (!(c.isAsciiLetter() || c in '0'..'9' || c in "-._~:/?#[]@!$&'()*+,;=")) return false
            i++
        }
        return true
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // ------------------------------------------------------------------------------------------------------------
    // References and verification.

    /**
     * XMLDSig Second Edition clause 4.3.3.3: the element a shortname XPointer names by an ID attribute; exactly one
     * element may carry it. ID attributes: xml:id (W3C xml:id) and the unqualified Id attribute of the KMS schema
     * (TS 33.180 V19.5.0 annex D.3.5.1 types it xsd:string, a schema defect, reported; resolving it as an ID is the
     * reading of the ETSI test suite for TS 37.579, maintainer decision 2026-11-01, not a stated 3GPP rule).
     */
    private fun byId(doc: Document, id: String): Element {
        var found: Element? = null
        var count = 0
        fun walk(e: Element) {
            val attrs = e.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i) as Attr
                val isId = (a.localName == "id" && a.namespaceURI == XML_NAMESPACE) || (a.localName == "Id" && a.namespaceURI == null)
                if (isId && a.value == id) { found = e; count++ }
            }
            for (child in elements0(e)) walk(child)
        }
        walk(doc.documentElement)
        need(count == 1, if (count == 0) "Reference to no element" else "Reference to an ambiguous ID")
        return found!!
    }

    private fun elements0(parent: Node): List<Element> {
        val children = parent.childNodes
        return (0 until children.length).map { children.item(it) }.filterIsInstance<Element>()
    }

    private fun contains(ancestor: Node, node: Node): Boolean {
        var current: Node? = node
        while (current != null) { if (current === ancestor) return true; current = current.parentNode }
        return false
    }

    /** A validated Reference: the element whose subtree it covers and the canonicalization of its digested octets. */
    private class Covered(val apex: Element, val method: Canonicalization, val prefixes: List<String>)

    /** XMLDSig Second Edition clauses 3.2.1 and 4.3.3.2: reference validation of one Reference of the Signature. */
    private fun validateReference(doc: Document, signature: Element, reference: Element): Covered {
        val children = elements(reference)
        var at = 0
        // Clause 4.3.3.1: the null URI or a shortname XPointer. A URI attribute in another namespace counts as present
        // and its unqualified value as the null URI, as the shared core reads them (libxml2 xmlHasProp, xmlGetNoNsProp).
        val attrs = reference.attributes
        need((0 until attrs.length).any { (attrs.item(it) as Attr).let { a -> a.namespaceURI != XMLNS && (a.localName ?: a.name) == "URI" } },
            "Reference without URI is not supported")
        val uri = attribute(reference, "URI")
        var apex: Element? = null
        var excluded: Element? = null
        if (uri.isNotEmpty()) {
            need(uri.length > 1 && uri[0] == '#' && !uri.contains('(') && !uri.contains(')'), "unsupported Reference URI")
            apex = byId(doc, uri.substring(1))
        }
        // Clause 4.3.3.4 with 6.6.4 and 6.6.1: the enveloped signature transform, then at most one canonicalization.
        var output: Canonicalization? = null
        var prefixes = emptyList<String>()
        if (at < children.size && named(children[at], "Transforms", DSIG)) {
            var canonicalized = false
            for (transform in elements(children[at])) {
                need(named(transform, "Transform", DSIG), "unexpected element in Transforms")
                need(!canonicalized, "unsupported transform after canonicalization")
                val algorithm = attribute(transform, "Algorithm")
                val method = canonicalizationOf(algorithm)
                if (algorithm == ENVELOPED) {
                    need(excluded == null, "repeated enveloped signature transform")
                    need(elements(transform).isEmpty(), "unexpected transform parameter")
                    excluded = signature
                } else if (method != null) {
                    output = method
                    prefixes = inclusivePrefixes(transform, method)
                    canonicalized = true
                } else {
                    throw KeyError("unsupported Reference transform")
                }
            }
            at++
        }
        need(at < children.size && named(children[at], "DigestMethod", DSIG) && attribute(children[at], "Algorithm") == SHA256 &&
            elements(children[at]).isEmpty(), "unsupported digest method")
        at++
        need(at + 1 == children.size && named(children[at], "DigestValue", DSIG), "invalid Reference")
        // Clause 4.3.3.2: a node-set left at the end becomes octets by Canonical XML 1.0 (comments omitted).
        val method = output ?: Canonicalization(Mode.C14N_1_0, false)
        val octets = c14n(doc, NodeSet(apex, excluded, false), method, prefixes)
        val received = unbase64(children[at].textContent ?: "")
        need(received.size == 32 && MessageDigest.isEqual(received, sha256(octets)), "reference digest mismatch")
        return Covered(apex ?: doc.documentElement, method, prefixes)
    }

    /**
     * XMLDSig Second Edition clause 3.2 (core validation) of a SignedKmsRequest or SignedKmsResponse under the derived
     * integrity key (16 or 32 octets, TS 33.180 annex F.1.4): the ds:Signature after its KmsRequest or KmsResponse,
     * every Reference valid and one covering that element, and the HMAC-SHA256 of the canonical SignedInfo. Returns the
     * canonical form (UTF-8) of the authenticated KmsRequest or KmsResponse by the canonicalization of the Reference
     * that covers it (comments omitted; Canonical XML 1.0 when that Reference names none), for the caller to parse, so
     * that what is used carries nothing that Reference left out. Throws KeyError when the document is malformed,
     * carries a DTD, uses an unsupported algorithm, reference or transform, or does not verify.
     */
    fun verifyPayload(signedXml: String, key: ByteArray): String {
        need(key.size == 16 || key.size == 32, "the integrity key is 128 or 256 bits (TS 33.180 annex F.1.4)")
        val doc = parse(signedXml)
        val root = doc.documentElement
        val payloadName = when {
            named(root, "SignedKmsResponse", KMSNS) -> "KmsResponse"
            named(root, "SignedKmsRequest", KMSNS) -> "KmsRequest"
            else -> throw KeyError("not a signed KMS message")
        }
        val parts = elements(root)
        need(parts.size == 2 && named(parts[0], payloadName, KMSNS) && named(parts[1], "Signature", DSIG),
            "a signed KMS message is its KmsRequest or KmsResponse and one ds:Signature")
        val payload = parts[0]
        val signature = parts[1]

        // XMLDSig Second Edition clause 4: SignedInfo, SignatureValue, then the optional KeyInfo and Objects, which are
        // not read (the caller selected the key).
        val items = elements(signature)
        need(items.size >= 2 && named(items[0], "SignedInfo", DSIG) && named(items[1], "SignatureValue", DSIG), "invalid Signature")
        for (at in 2 until items.size)
            need(named(items[at], if (at == 2) "KeyInfo" else "Object", DSIG) || named(items[at], "Object", DSIG), "invalid Signature")
        val info = elements(items[0])
        need(info.size >= 3 && named(info[0], "CanonicalizationMethod", DSIG) && named(info[1], "SignatureMethod", DSIG),
            "invalid SignedInfo")
        val canonicalization = canonicalizationOf(attribute(info[0], "Algorithm")) ?: throw KeyError("unsupported canonicalization method")
        // TS 33.180 annex D.1: "HMAC-SHA256 with a signature length of 256".
        need(attribute(info[1], "Algorithm") == HMAC_SHA256, "unsupported signature method")
        for (parameter in elements(info[1])) {
            need(named(parameter, "HMACOutputLength", DSIG), "unexpected signature method parameter")
            need((parameter.textContent ?: "").trim(' ', '\t', '\r', '\n') == "256",
                "the HMAC-SHA256 signature length is 256 (TS 33.180 annex D.1)")
        }

        // Reference validation (clause 3.2.1) of every Reference; one must cover the whole KmsRequest or KmsResponse.
        var covering: Covered? = null
        for (at in 2 until info.size) {
            need(named(info[at], "Reference", DSIG), "invalid SignedInfo")
            val reference = validateReference(doc, signature, info[at])
            if (covering == null && contains(reference.apex, payload)) covering = reference
        }
        need(covering != null, "the signature does not cover the whole KMS message")

        // Signature validation (clause 3.2.2): the SignedInfo canonicalized in this document by its own algorithm.
        val canonical = c14n(doc, NodeSet(items[0], null, true), canonicalization, inclusivePrefixes(info[0], canonicalization))
        val expected = hmacSha256(key, canonical)
        val received = unbase64(items[1].textContent ?: "")
        need(received.size == expected.size && MessageDigest.isEqual(received, expected), "signature value mismatch")
        // The payload by the canonicalization of the Reference that covers it: an Exclusive XML Canonicalization leaves
        // out namespace declarations the payload does not visibly use, which an inclusive form would bring in.
        return String(c14n(doc, NodeSet(payload, null, false), covering!!.method, covering.prefixes), Charsets.UTF_8)
    }

    /**
     * XMLDSig Second Edition clause 4.5: the ds:KeyName of the KeyInfo of a signed KMS message's Signature (the
     * Signature after the payload), not verified: it names the key that signed it (TS 33.180 clause 9.3.5 and annex
     * D.3.1, the base64 XPK-ID); empty when there is none.
     */
    fun keyName(signedXml: String): String {
        val parts = elements(parse(signedXml).documentElement)
        if (parts.size != 2 || !named(parts[1], "Signature", DSIG)) return ""
        for (item in elements(parts[1])) if (named(item, "KeyInfo", DSIG))
            for (entry in elements(item)) if (named(entry, "KeyName", DSIG)) return entry.textContent ?: ""
        return ""
    }

    /**
     * TS 33.180 annexes D.1, D.2.2 and D.3.1: signs a KmsRequest or KmsResponse element ([payload], without an XML
     * declaration, its Id attribute naming it) under the derived integrity key (16 or 32 octets) and returns the
     * SignedKmsRequest or SignedKmsResponse: one Reference to "#<Id>" without transforms (digested as Canonical XML 1.0,
     * XMLDSig Second Edition clause 4.3.3.2), SHA-256, Canonical XML 1.0 for SignedInfo, HMAC-SHA256 and the KeyName
     * given (the base64 XPK-ID). The same text as the shared core's sign_payload. No Signature-ID or TrK-ID element is
     * added: annex D.3.5.3 types them xsd:string and no clause gives their text form (a spec defect, reported). The
     * result is verified with [verifyPayload] before it is returned.
     */
    fun sign(payload: String, key: ByteArray, keyName: String): String {
        need(key.size == 16 || key.size == 32, "the integrity key is 128 or 256 bits (TS 33.180 annex F.1.4)")
        val request = payload.startsWith("<KmsRequest")
        need(request || payload.startsWith("<KmsResponse"), "not a KmsRequest or KmsResponse element")
        val id = attribute(parse(payload).documentElement, "Id")
        need(id.isNotEmpty() && id.all { it.isAsciiLetter() || it in '0'..'9' || it in "_.-" }, "the payload needs an Id attribute")
        need(keyName.isNotEmpty() && keyName.none { it in "<&\"" }, "invalid KeyName")
        val root = if (request) "SignedKmsRequest" else "SignedKmsResponse"
        val digestMark = "DIGEST"
        val valueMark = "SIGNATUREVALUE"
        var text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><$root xmlns=\"$KMSNS\">$payload<ds:Signature xmlns:ds=\"$DSIG\">" +
            "<ds:SignedInfo><ds:CanonicalizationMethod Algorithm=\"$C14N10\"/><ds:SignatureMethod Algorithm=\"$HMAC_SHA256\"/>" +
            "<ds:Reference URI=\"#$id\"><ds:DigestMethod Algorithm=\"$SHA256\"/><ds:DigestValue>$digestMark</ds:DigestValue>" +
            "</ds:Reference></ds:SignedInfo><ds:SignatureValue>$valueMark</ds:SignatureValue><ds:KeyInfo><ds:KeyName>$keyName" +
            "</ds:KeyName></ds:KeyInfo></ds:Signature></$root>"
        val afterPayload = text.indexOf("<ds:Signature ")
        val inclusive = Canonicalization(Mode.C14N_1_0, false)
        run {
            val doc = parse(text)
            val digest = sha256(c14n(doc, NodeSet(byId(doc, id), null, false), inclusive, emptyList()))
            val at = text.indexOf(digestMark, afterPayload)
            text = text.substring(0, at) + Base64.getEncoder().encodeToString(digest) + text.substring(at + digestMark.length)
        }
        run {
            val doc = parse(text)
            val signature = elements(doc.documentElement)[1]
            val value = hmacSha256(key, c14n(doc, NodeSet(elements(signature)[0], null, true), inclusive, emptyList()))
            val at = text.indexOf(valueMark, afterPayload)
            text = text.substring(0, at) + Base64.getEncoder().encodeToString(value) + text.substring(at + valueMark.length)
        }
        verifyPayload(text, key)
        return text
    }
}
