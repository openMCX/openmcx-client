//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.body

import org.w3c.dom.Element
import java.math.BigInteger

/**
 * The registration information document of the reg event package (RFC 3680 §5, application/reginfo+xml), which the
 * client reads from the NOTIFYs of its subscription to its own registration state (TS 24.229 §5.1.1.3, §5.1.1.5A and
 * §5.1.1.7): its version, whether it is the full state, and per registration its address-of-record and state with its
 * contacts (state, event, expires when given, URI).
 */
data class RegInfo(val version: BigInteger, val full: Boolean, val registrations: List<Registration>) {
    data class Registration(val aor: String, val state: String, val contacts: List<Contact>)
    data class Contact(val uri: String, val state: String, val event: String, val expires: Long?)

    companion object {
        const val CONTENT_TYPE = "application/reginfo+xml"
        const val NAMESPACE = "urn:ietf:params:xml:ns:reginfo"

        /**
         * RFC 3680 §5.1: the <reginfo> root with its version and state ("full" or "partial"), its <registration>
         * elements (aor, state "init", "active" or "terminated") and their <contact> elements (state "active" or
         * "terminated", the event of §4.7.1, the optional expires, and the one <uri>). Elements and attributes of other
         * namespaces are ignored (§5.1). Throws IllegalArgumentException for a document that is not one.
         */
        fun parse(text: String): RegInfo {
            val root = Xml.parse(text)
            if (root.localName != "reginfo" || root.namespaceURI != NAMESPACE) throw IllegalArgumentException("not a reginfo document")
            val state = root.getAttribute("state")
            if (!root.hasAttribute("version") || (state != "full" && state != "partial"))
                throw IllegalArgumentException("reginfo version or state missing")
            val registrations = own(root, "registration").map { registration ->
                val aor = registration.getAttribute("aor")
                val registrationState = registration.getAttribute("state")
                if (aor.isEmpty() || registrationState !in setOf("init", "active", "terminated"))
                    throw IllegalArgumentException("reginfo registration without aor or state")
                Registration(aor, registrationState, own(registration, "contact").map { contact ->
                    val contactState = contact.getAttribute("state")
                    val event = contact.getAttribute("event")
                    if (contactState !in setOf("active", "terminated") || event.isEmpty())
                        throw IllegalArgumentException("reginfo contact without state or event")
                    val uris = own(contact, "uri")
                    if (uris.size != 1) throw IllegalArgumentException("reginfo contact without one uri")
                    Contact(uris[0].text(), contactState, event,
                        contact.getAttribute("expires").takeIf { it.isNotEmpty() }?.let(::seconds))
                })
            }
            return RegInfo(nonNegative(root.getAttribute("version")), state == "full", registrations)
        }

        /** The child elements [localName] of the reginfo namespace. */
        private fun own(parent: Element, localName: String) = parent.children(localName).filter { it.namespaceURI == NAMESPACE }

        /**
         * RFC 3680 §5.4: the version is an xs:nonNegativeInteger (no upper bound). XML Schema Part 2 §3.3.20: an optional
         * sign, "+" or, for zero only, "-", then decimal digits; white space collapsed.
         */
        private fun nonNegative(value: String): BigInteger {
            val lexical = value.trim()
            if (!lexical.matches(Regex("[+-]?[0-9]+"))) throw IllegalArgumentException("reginfo number $value")
            val number = BigInteger(lexical.removePrefix("+").removePrefix("-"))
            if (lexical.startsWith("-") && number.signum() != 0) throw IllegalArgumentException("reginfo number $value")
            return number
        }

        /**
         * RFC 3680 §5.4: expires is an xs:unsignedLong (up to 2^64 - 1); seconds beyond Long.MAX_VALUE (about 2.9e11
         * years) are held as Long.MAX_VALUE, which no timer reaches.
         */
        private fun seconds(value: String): Long {
            val number = nonNegative(value)
            if (number.bitLength() > 64) throw IllegalArgumentException("reginfo expires $value")
            return if (number.bitLength() > 63) Long.MAX_VALUE else number.toLong()
        }
    }
}
