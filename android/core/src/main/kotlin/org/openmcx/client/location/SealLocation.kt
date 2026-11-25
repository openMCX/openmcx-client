//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.text
import org.w3c.dom.Element

/**
 * The location-info documents of the SEAL location management protocol over HTTP (TS 24.545 clause 7,
 * application/vnd.3gpp.seal-location-info+xml) that the location management client (SLM-C) exchanges with the location
 * management server (SLM-S). Ported from the OpenMCX server library (src/location/seal_location.cpp), which the Linux
 * client uses.
 */
object SealLocation {
    const val MIME = "application/vnd.3gpp.seal-location-info+xml"
    const val NS = "urn:3gpp:ns:sealLocationInfo:1.0"
    /** The OpenMCX extension naming where the client reports (`<report-uri>`, `<history-uri>` in `<anyExt>`). */
    const val EXT_NS = "urn:openmcx:ns:sealLocation:1.0"

    /** TS 23.032 §6.1: a position as `<threebytes>` codes, and when it was taken (RFC 3339). */
    data class Fix(val latitude: Int, val longitude: Int, val timestamp: String)

    /** TS 24.545 §6.2.2.3.1: the configuration a client reads, and where it reports. */
    data class Configuration(val periodic: Int = 0, val minimumInterval: Int = 0, val reportUri: String = "", val historyUri: String = "")

    class SealError(message: String) : Exception("seal location-info: $message")

    private fun el(name: String, inner: String) = "<$name>$inner</$name>"
    private fun root(inner: String) = "${Xml.DECLARATION}<location-info xmlns=\"$NS\">$inner</location-info>"
    private fun identity(user: String) = el("identity", el("VAL-user-id", el("sealURI", Xml.escape(user))))
    private fun point(name: String, fix: Fix) =
        el(name, el("longitude", el("threebytes", fix.longitude.toString())) + el("latitude", el("threebytes", fix.latitude.toString())))

    /** TS 24.545 §6.2.2.2.2: a client's location report, with the trigger that caused it. */
    fun reportXml(user: String, trigger: String, fix: Fix): String {
        var report = if (trigger.isEmpty()) "" else el("trigger-id", Xml.escape(trigger))
        report += el("current-location", point("current-geographical-coordinate", fix))
        if (fix.timestamp.isNotEmpty()) report += el("anyExt", el("timestamp", Xml.escape(fix.timestamp)))
        return root(identity(user) + el("report", report))
    }

    /** TS 24.545 §6.2.20.2: the positions a client stored while it could not report (`<history-loc-result-report>`). */
    fun historyXml(subscription: String, fixes: List<Fix>): String {
        val inner = el("subscription-identifier", Xml.escape(subscription)) + fixes.joinToString("") {
            el("reports", el("current-location", point("current-geographical-coordinate", it)) + el("anyExt", el("timestamp", Xml.escape(it.timestamp))))
        }
        // <history-loc-result-report> is an element of <anyExt> in the schema (TS 24.545 §7.4.2).
        return root(el("anyExt", el("history-loc-result-report", inner)))
    }

    private fun Element.kids(name: String, ns: String = NS) = children(name).filter { it.namespaceURI == ns }
    private fun Element.one(name: String, ns: String = NS) = kids(name, ns).firstOrNull()
    private fun Element.oneExt(name: String, ns: String = NS) = one(name, ns) ?: kids("anyExt").firstNotNullOfOrNull { it.one(name, ns) }

    private fun number(e: Element?, min: Int, max: Int): Int {
        val digits = e?.text() ?: throw SealError("missing number")
        return digits.toIntOrNull()?.takeIf { it in min..max } ?: throw SealError("invalid number '$digits'")
    }

    /**
     * TS 24.545 §6.2.2.3.1: the location reporting configuration a client receives (`<periodic-report>` of
     * `<triggering-criteria>`, `<minimum-interval-length>`) and, in the OpenMCX `<anyExt>`, where it reports.
     */
    fun parseConfiguration(text: String): Configuration {
        if (text.length > 256 * 1024) throw SealError("oversized body")
        val top = runCatching { Xml.parse(text) }.getOrElse { throw SealError("invalid XML document") }
        if (top.localName != "location-info" || top.namespaceURI != NS) throw SealError("not a location-info document")
        val config = top.one("configuration") ?: throw SealError("missing <configuration>")
        val periodic = config.one("triggering-criteria")?.one("periodic-report")?.let { number(it, 1, 86400) } ?: 0
        val minimum = config.one("minimum-interval-length")?.let { number(it, 1, 86400) } ?: 0
        return Configuration(periodic, minimum, top.oneExt("report-uri", EXT_NS)?.text().orEmpty(), top.oneExt("history-uri", EXT_NS)?.text().orEmpty())
    }
}
