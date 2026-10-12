//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.openmcx.client.media.AmrWb
import org.openmcx.client.sdp.MediaDescription
import org.openmcx.client.sdp.SessionDescription
import java.security.SecureRandom

class SdpNegotiationError(message: String) : Exception(message)

/**
 * Our media endpoint: the address and ports we receive on, and our floor SSRC. Without [floorControl] (a private call
 * without floor control, TS 24.379 §11.1.2) there is no floor stream, and the SDP announces [audioSsrc], the SSRC
 * our speech is sent with, which the server checks.
 */
data class LocalMedia(val address: String, val audioPort: Int, val floorPort: Int, val floorSsrc: Long,
                      val floorControl: Boolean = true, val audioSsrc: Long = 0,
                      /** We support queued floor requests (`mc_queueing`, TS 24.380 §14.2.2). */
                      val queueing: Boolean = false)

/** What the other side (the MCPTT server) negotiated. */
data class RemoteMedia(
    val address: String,
    val audioPort: Int,
    val floorPort: Int,
    val payloadType: Int,
    val octetAligned: Boolean,
    /** The floor control server's `mc_floor_ssrc`: our floor messages carry it. */
    val floorSsrc: Long,
    val floorPriority: Int?,
    /** False for an offer or answer without `m=application` (§11.1.2): no floor, both parties speak at once. */
    val floorControl: Boolean = true,
    /** The peer's AMR-WB `mode-set` (RFC 4867 §8.1); empty: every mode. */
    val modeSet: List<Int> = emptyList(),
    /** The peer's `mc_queueing` (TS 24.380 §14.2.2): queued floor requests. */
    val queueing: Boolean = false,
)

/**
 * SDP for an MCPTT on-demand session (TS 24.379 §6.2.1, §6.2.2; TS 24.380 §14): one
 * AMR-WB audio stream and one floor control stream, `m=application <port> udp MCPTT`.
 * This client offers no implicit floor request; it offers queueing (`mc_queueing`) when the service configuration has a
 * floor request queue, since the server rejects an offer of queueing it does not support.
 */
object McpttSdp {
    const val PAYLOAD_TYPE = 96
    private val random = SecureRandom()

    fun newFloorSsrc(): Long = (random.nextInt().toLong() and 0xffffffffL).let { if (it == 0L) 1L else it }

    private fun sessionId() = (random.nextInt() and 0x7fffffff).toLong()

    private fun audio(local: LocalMedia, pt: Int, octetAligned: Boolean) = MediaDescription(
        "audio", local.audioPort, "RTP/AVP", listOf(pt.toString()), info = "speech",
        attributes = listOf(
            "rtpmap" to "$pt AMR-WB/16000/1",
            // TS 26.179 §4.1.3: mode-change-capability 2, max-red 0, ptime 20 and maxptime 240 offered, no mode-set.
            "fmtp" to "$pt octet-align=${if (octetAligned) 1 else 0};mode-change-capability=2;max-red=0",
            "ptime" to "20",
            "maxptime" to "240",
            "rtcp" to (local.audioPort + 1).toString(),
            "sendrecv" to null,
        ) + (if (!local.floorControl) listOf("ssrc" to "${local.audioSsrc} cname:mcptt") else emptyList()),
    )

    private fun floor(local: LocalMedia, priority: Int?, queueing: Boolean) = MediaDescription(
        "application", local.floorPort, "udp", listOf("MCPTT"),
        attributes = listOf("fmtp" to "MCPTT mc_floor_ssrc=${local.floorSsrc}" + (priority?.let { ";mc_priority=$it" } ?: "") +
            if (queueing) ";mc_queueing" else ""),
    )

    fun offer(local: LocalMedia): String = SessionDescription(
        origin = "- ${sessionId()} 1 IN IP4 ${local.address}",
        connection = "IN IP4 ${local.address}",
        media = listOfNotNull(audio(local, PAYLOAD_TYPE, false), if (local.floorControl) floor(local, null, local.queueing) else null),
    ).encode()

    /** Answer [offer]: its payload type and octet alignment, its floor priority echoed, queueing when both support it (§14.2.2). */
    fun answer(offer: RemoteMedia, local: LocalMedia): String = SessionDescription(
        origin = "- ${sessionId()} 1 IN IP4 ${local.address}",
        connection = "IN IP4 ${local.address}",
        media = listOfNotNull(audio(local, offer.payloadType, offer.octetAligned),
            if (offer.floorControl && local.floorControl) floor(local, offer.floorPriority, offer.queueing && local.queueing) else null),
    ).encode()

    /** The remote side of [sdp] (an offer or an answer). */
    fun remote(sdp: String): RemoteMedia {
        val session = SessionDescription.parse(sdp)
        val audio = session.media.firstOrNull { it.media == "audio" && it.port != 0 } ?: throw SdpNegotiationError("no audio stream")
        // No floor control stream: a private call without floor control (§11.1.2).
        val floor = session.media.firstOrNull { it.media == "application" && "MCPTT" in it.formats && it.port != 0 }
        val pt = audio.formats.firstOrNull { audio.rtpmap[it.toIntOrNull()]?.startsWith("AMR-WB/16000", true) == true }?.toInt()
            ?: throw SdpNegotiationError("no AMR-WB payload type")
        val fmtp = params(audio.fmtp(pt.toString()) ?: "")
        val floorParams = params(floor?.fmtp("MCPTT") ?: "")
        val ssrc = if (floor == null) 0L else floorParams["mc_floor_ssrc"]?.toLongOrNull() ?: throw SdpNegotiationError("no mc_floor_ssrc")
        val address = session.address(audio) ?: throw SdpNegotiationError("no connection address")
        val modeSet = try { AmrWb.modeSet(fmtp["mode-set"]) } catch (e: IllegalArgumentException) { throw SdpNegotiationError(e.message ?: "mode-set") }
        return RemoteMedia(address, audio.port, floor?.port ?: 0, pt, fmtp["octet-align"] == "1", ssrc, floorParams["mc_priority"]?.toIntOrNull(),
            floorControl = floor != null, modeSet = modeSet, queueing = floor != null && "mc_queueing" in floorParams)
    }

    private fun params(text: String): Map<String, String?> = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        .associate { p -> p.substringBefore('=').trim() to p.substringAfter('=', "").trim().ifEmpty { null } }

    /** A floor-priority check like the server's: an answer must echo an offered mc_priority. */
    fun checkAnswer(offered: Int?, answered: Int?) {
        if (offered != answered) throw SdpNegotiationError("mc_priority answered $answered, offered $offered")
    }
}

/** The media description helper for tests and diagnostics. */
internal fun MediaDescription.fmtpParams(format: String) = fmtp(format)
