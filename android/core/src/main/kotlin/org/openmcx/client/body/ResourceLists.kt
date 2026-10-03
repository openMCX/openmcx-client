package org.openmcx.client.body

/** A resource-lists document (RFC 4826 §3.4, as used by RFC 5366 URI lists). */
object ResourceLists {
    const val NAMESPACE = "urn:ietf:params:xml:ns:resource-lists"
    const val CONTENT_TYPE = "application/resource-lists+xml"

    fun encode(uris: List<String>): String = Xml.document("resource-lists", NAMESPACE) {
        element("list") { uris.forEach { uri -> element("entry") { attr("uri", uri) } } }
    }

    fun parse(text: String): List<String> = Xml.parse(text).descendants("entry").mapNotNull { it.getAttribute("uri").ifEmpty { null } }
}
