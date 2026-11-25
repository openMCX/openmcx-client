//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.msrp.MsrpUri
import org.openmcx.client.sdp.SessionDescription

/**
 * The "m=message" MSRP media of an MCData SDP offer or answer (TS 24.282 §9.2.3.2.1, §9.2.3.2.2, §9.2.4.2.1, §10.2.5.2.1;
 * RFC 4975 §8, RFC 4145 / RFC 6135 a=setup, RFC 5547 file attributes, RFC 4567 a=key-mgmt).
 */
data class MsrpMedia(
    val address: String,
    val port: Int,
    val path: String,
    val setup: String,
    val direction: String,
    val acceptTypes: List<String> = listOf(McdataService.SIGNALLING, McdataService.PAYLOAD),
    val secure: Boolean = false,
    val keyMgmt: String? = null,
    val fileSelector: String? = null,
    val fileTransferId: String? = null,
    val fileDate: String? = null,
    val fileDescription: String? = null,
    /** ICE attribute lines of the media section, each ending with CRLF (TS 24.282 §18.3.2.1 step 10, RFC 8839). */
    val ice: String? = null,
) {
    val uri: MsrpUri get() = MsrpUri.parse(path)

    fun encode(sessionId: Long = System.currentTimeMillis() / 1000): String = buildString {
        append("v=0\r\no=- $sessionId 1 IN IP4 $address\r\ns=MCData\r\nc=IN IP4 $address\r\nt=0 0\r\n")
        keyMgmt?.let { append("a=key-mgmt:mikey $it\r\n") }
        append("m=message $port ${if (secure) "TCP/TLS/MSRP" else "TCP/MSRP"} *\r\n")
        append("a=path:$path\r\n")
        append("a=setup:$setup\r\n")
        append("a=accept-types:${acceptTypes.joinToString(" ")}\r\n")
        append("a=$direction\r\n")
        ice?.let { append(it) }
        fileSelector?.let { append("a=file-selector:$it\r\n") }
        fileTransferId?.let { append("a=file-transfer-id:$it\r\n") }
        fileDate?.let { append("a=file-date:$it\r\n") }
        fileDescription?.let { append("a=file-description:$it\r\n") }
    }

    companion object {
        /**
         * This client's own media: it never listens (RFC 6135 §4, the outbound-only client), so the port is 9 (RFC 4145
         * §4: discard, for an endpoint that does not accept) and the path names the session only.
         */
        fun local(address: String, session: String, setup: String, direction: String, acceptTypes: List<String> =
            listOf(McdataService.SIGNALLING, McdataService.PAYLOAD)) =
            MsrpMedia(address, 9, MsrpUri(address, 9, session).text(), setup, direction, acceptTypes)

        /** The first "m=message" section of [sdp] with TCP/MSRP or TCP/TLS/MSRP, or an [McdataCodecError]. */
        fun parse(sdp: String): MsrpMedia {
            val description = SessionDescription.parse(sdp)
            val m = description.media.firstOrNull { it.media == "message" } ?: throw McdataCodecError("SDP without an MSRP media stream")
            if (m.proto != "TCP/MSRP" && m.proto != "TCP/TLS/MSRP") throw McdataCodecError("unsupported MSRP transport ${m.proto}")
            val path = m.attribute("path") ?: throw McdataCodecError("MSRP media without a=path")
            runCatching { MsrpUri.parse(path.trim().split(' ').last()) }.getOrElse { throw McdataCodecError("invalid a=path: $path") }
            val setup = m.attribute("setup")?.trim() ?: "passive" // RFC 6135 §4.1 with RFC 4145 §4: the default
            if (setup !in setOf("active", "passive", "actpass")) throw McdataCodecError("invalid a=setup:$setup")
            val keyMgmt = (m.attribute("key-mgmt") ?: description.attributes.firstOrNull { it.first == "key-mgmt" }?.second)
                ?.trim()?.takeIf { it.startsWith("mikey ") }?.removePrefix("mikey ")?.trim()
            return MsrpMedia(description.address(m) ?: throw McdataCodecError("SDP without a connection address"), m.port,
                path.trim().split(' ').last(), setup, m.direction, m.attribute("accept-types")?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList(),
                m.proto == "TCP/TLS/MSRP", keyMgmt, m.attribute("file-selector"), m.attribute("file-transfer-id"),
                m.attribute("file-date"), m.attribute("file-description"))
        }
    }
}
