//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Xml
import org.openmcx.client.body.children
import org.openmcx.client.body.text

/**
 * A KMS Redirect Response (TS 33.180 clause 5.2.8.2 and annex D.4, namespace urn:3gpp:ns:mcsecKMSKRR:1.0): the server's
 * 488 to a PCK keyed to a KMS that does not key the callee names the KMSs that do ([receiverList], or any with
 * [receiverAny]). TS 33.180 clause 5.2.8.2.5: the initiator keys the PCK again to the first of them it holds a
 * certificate for.
 */
data class KmsRedirect(val creator: String, val initiator: String, val initiatorKms: String, val receiver: String,
                       val receiverKms: String, val initiatorList: List<String>, val receiverList: List<String>,
                       val initiatorAny: Boolean = false, val receiverAny: Boolean = false) {
    /** Clause 5.2.8.2.5: the receiver KMS to key to: the first listed that [known] holds (any: the one it names). */
    fun choose(known: (String) -> Boolean): String? = receiverList.firstOrNull(known) ?: receiverKms.takeIf { receiverAny && known(it) }

    companion object {
        const val CONTENT_TYPE = "application/vnd.3gpp.kmsredirectresponse+xml"
        const val NS = "urn:3gpp:ns:mcsecKMSKRR:1.0"

        fun parse(text: String): KmsRedirect {
            val root = Xml.parse(text)
            require(root.localName == "KmsRedirectResponse" && root.namespaceURI == NS && root.getAttribute("Version") == "1.0.0") { "not a KMS Redirect Response" }
            fun one(name: String) = root.children(name).filter { it.namespaceURI == NS }.singleOrNull() ?: throw IllegalArgumentException("one <$name> needed")
            fun list(name: String): Pair<List<String>, Boolean> {
                val nodes = one(name).children().filter { it.namespaceURI == NS }
                val uris = nodes.filter { it.localName == "KmsUri" }.map { it.text() }
                val any = nodes.any { it.localName == "ANY" }
                // Annex D.4.4 KmsUriListType: <ANY> or KMS URIs, not both; at most 16.
                require(!(any && uris.isNotEmpty()) && uris.size <= 16) { "invalid KMS URI list" }
                return uris to any
            }
            val (initiators, initiatorAny) = list("InitiatorKmsList")
            val (receivers, receiverAny) = list("ReceiverKmsList")
            return KmsRedirect(one("KRRCreatorUri").text(), one("InitiatorUri").text(), one("InitiatorKmsUri").text(),
                one("ReceiverUri").text(), one("ReceiverKmsUri").text(), initiators, receivers, initiatorAny, receiverAny)
        }

        /** The KRR a response carries: its body, or one of its parts. */
        fun of(contentType: String?, body: ByteArray): KmsRedirect? {
            if (contentType == null || body.isEmpty()) return null
            val text = if (contentType.substringBefore(';').trim().equals(CONTENT_TYPE, true)) String(body)
                else Multipart.parse(contentType, body).firstOrNull { it.mediaType == CONTENT_TYPE }?.text ?: return null
            return runCatching { parse(text) }.getOrNull()
        }
    }
}
