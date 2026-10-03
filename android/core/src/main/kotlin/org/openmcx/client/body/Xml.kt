package org.openmcx.client.body

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** An XML element tree for building documents; [toString] serialises it without indentation. */
class XmlElement(val name: String, private val attributes: MutableList<Pair<String, String>> = mutableListOf()) {
    private val children = mutableListOf<Any>() // XmlElement or String

    fun attr(name: String, value: String?) = apply { if (value != null) attributes += name to value }
    fun text(value: String?) = apply { if (value != null) children += value }
    fun element(name: String, build: XmlElement.() -> Unit = {}) = XmlElement(name).also { it.build(); children += it }
    /** A child element holding only [value], or nothing when [value] is null. */
    fun leaf(name: String, value: String?, vararg attrs: Pair<String, String>) {
        if (value != null) element(name) { attrs.forEach { attr(it.first, it.second) }; text(value) }
    }

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    private fun write(out: StringBuilder) {
        out.append('<').append(name)
        attributes.forEach { (k, v) -> out.append(' ').append(k).append("=\"").append(Xml.escape(v)).append('"') }
        if (children.isEmpty()) { out.append("/>"); return }
        out.append('>')
        children.forEach { if (it is XmlElement) it.write(out) else out.append(Xml.escape(it as String)) }
        out.append("</").append(name).append('>')
    }
}

object Xml {
    const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    fun document(root: String, namespace: String? = null, build: XmlElement.() -> Unit): String =
        DECLARATION + XmlElement(root).apply { attr("xmlns", namespace); build() }.toString()

    fun escape(text: String): String = buildString {
        for (ch in text) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** Parse a document namespace-aware, refusing DOCTYPEs (no entity expansion). */
    fun parse(text: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        if (text.contains("<!DOCTYPE", ignoreCase = true)) throw IllegalArgumentException("DOCTYPE not allowed")
        return factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    }
}

/** Element children of this element, by local name when given. */
fun Element.children(localName: String? = null): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n is Element && (localName == null || n.localName == localName || (n.localName == null && n.tagName == localName))) out += n
    }
    return out
}

fun Element.child(localName: String): Element? = children(localName).firstOrNull()

/** Every descendant element with [localName], in document order. */
fun Element.descendants(localName: String): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(e: Element) { e.children().forEach { if (it.localName == localName) out += it; walk(it) } }
    walk(this)
    return out
}

fun Element.text(): String = textContent.trim()
fun Element.childText(localName: String): String? = child(localName)?.text()
