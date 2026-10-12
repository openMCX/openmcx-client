//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.media.AmrWb
import org.openmcx.client.mcptt.SdpNegotiationError
import org.openmcx.client.sdp.MediaDescription
import org.openmcx.client.sdp.SessionDescription
import java.security.SecureRandom

/** Our side of an MCVideo call: where we receive, and the SSRCs we announce. */
data class LocalVideoMedia(
    val address: String,
    val audioPort: Int,
    val videoPort: Int,
    val controlPort: Int?,
    /** Our `mc_transmission_ssrc`: the server's transmission control messages to us carry it. */
    val controlSsrc: Long,
    val audioSsrc: Long,
    val videoSsrc: Long,
    /** The implicit transmission request (§6.4, TS 24.581 §14.2.5): the sender of a push, the pulled user. */
    val implicitRequest: Boolean = false,
    /** RFC 3264 direction of the audio and video: `recvonly` for the viewing user of an ambient viewing call, `sendonly` for the viewed-to user. */
    val direction: String = "sendrecv",
    /** Protected media (TS 24.281 4.7): audio and video as RTP/SAVP. */
    val protected: Boolean = false,
    /** A private call's PCK, the base64 MIKEY I_MESSAGE of `a=key-mgmt:mikey` (RFC 4567), in an offer. */
    val keyMgmt: String? = null,
)

/** What the MCVideo server negotiated. */
data class RemoteVideoMedia(
    val address: String,
    val audioPort: Int,
    val audioPayloadType: Int,
    val octetAligned: Boolean,
    val videoPort: Int,
    val videoPayloadType: Int,
    /** The transmission control port, or null in a call without transmission control. */
    val controlPort: Int?,
    /** The server's `mc_transmission_ssrc`: our transmission control messages carry it. */
    val controlSsrc: Long?,
    val priority: Int?,
    /** The direction of the video stream (`sendrecv` unless the offer or answer says otherwise). */
    val direction: String = "sendrecv",
    /** Audio and video offered or answered as RTP/SAVP. */
    val protected: Boolean = false,
    /** The PCK of `a=key-mgmt:mikey`, if the offer carries one. */
    val keyMgmt: String? = null,
    /** The peer's AMR-WB `mode-set` (RFC 4867 §8.1); empty: every mode. */
    val modeSet: List<Int> = emptyList(),
) {
    val transmissionControl get() = controlPort != null
}

/**
 * SDP for an MCVideo call (TS 24.281 §6.2.1–6.2.2, TS 24.581 §14) as the OpenMCX server takes it:
 * one AMR-WB audio stream, one H.264 video stream (RFC 6184 packetization mode 1, Constrained
 * Baseline level 3.1), each with a single payload type, `a=rtcp` and `a=ssrc`, and, under
 * transmission control, `m=application <port> udp MCVideo`. No queueing is offered.
 */
object McvideoSdp {
    const val AUDIO_PT = 96
    const val VIDEO_PT = 97
    const val PROFILE_LEVEL_ID = "42e01f"
    private val random = SecureRandom()

    fun newSsrc(): Long = (random.nextInt().toLong() and 0xffffffffL).let { if (it == 0L) 1L else it }
    private fun sessionId() = (random.nextInt() and 0x7fffffff).toLong()

    private fun audio(local: LocalVideoMedia, pt: Int, octetAligned: Boolean) = MediaDescription(
        "audio", local.audioPort, if (local.protected) "RTP/SAVP" else "RTP/AVP", listOf(pt.toString()), info = "audio component of MCVideo",
        attributes = listOf(
            "rtpmap" to "$pt AMR-WB/16000/1",
            // TS 26.179 §4.1.3: mode-change-capability 2, max-red 0, ptime 20 and maxptime 240 offered, no mode-set.
            "fmtp" to "$pt octet-align=${if (octetAligned) 1 else 0};mode-change-capability=2;max-red=0",
            "ptime" to "20",
            "maxptime" to "240",
            local.direction to null,
            "rtcp" to (local.audioPort + 1).toString(),
            "ssrc" to "${local.audioSsrc} cname:mcvideo",
        ),
    )

    private fun video(local: LocalVideoMedia, pt: Int) = MediaDescription(
        "video", local.videoPort, if (local.protected) "RTP/SAVP" else "RTP/AVP", listOf(pt.toString()), info = "video component of MCVideo",
        attributes = listOf(
            "rtpmap" to "$pt H264/90000",
            "fmtp" to "$pt profile-level-id=$PROFILE_LEVEL_ID;packetization-mode=1",
            local.direction to null,
            "rtcp" to (local.videoPort + 1).toString(),
            "ssrc" to "${local.videoSsrc} cname:mcvideo",
        ),
    )

    private fun control(port: Int, ssrc: Long, priority: Int?, implicit: Boolean) = MediaDescription(
        "application", port, "udp", listOf("MCVideo"),
        attributes = listOf("fmtp" to "MCVideo mc_transmission_ssrc=$ssrc" + (priority?.let { ";mc_priority=$it" } ?: "") +
            (if (implicit) ";mc_implicit_request" else "")),
    )

    private fun session(local: LocalVideoMedia, media: List<MediaDescription>) = SessionDescription(
        origin = "- ${sessionId()} 1 IN IP4 ${local.address}", sessionName = "MCVideo",
        connection = "IN IP4 ${local.address}", media = media,
        attributes = listOfNotNull(local.keyMgmt?.let { "key-mgmt" to "mikey $it" }),
    )

    /** An offer; without [LocalVideoMedia.controlPort] the call has no transmission control. */
    fun offer(local: LocalVideoMedia): String = session(local, listOfNotNull(
        audio(local, AUDIO_PT, false), video(local, VIDEO_PT),
        local.controlPort?.let { control(it, local.controlSsrc, null, local.implicitRequest) },
    )).encode()

    /** Answer [offer] with its payload types; transmission control only if it was offered. */
    fun answer(offer: RemoteVideoMedia, local: LocalVideoMedia): String = session(local, listOfNotNull(
        audio(local, offer.audioPayloadType, offer.octetAligned), video(local, offer.videoPayloadType),
        if (offer.transmissionControl) control(local.controlPort ?: throw SdpNegotiationError("no local control port"),
            local.controlSsrc, offer.priority, local.implicitRequest) else null,
    )).encode()

    /** The remote side of [sdp] (an offer or an answer). */
    fun remote(sdp: String): RemoteVideoMedia {
        val session = SessionDescription.parse(sdp)
        val audio = session.media.firstOrNull { it.media == "audio" && it.port != 0 } ?: throw SdpNegotiationError("no audio stream")
        val video = session.media.firstOrNull { it.media == "video" && it.port != 0 } ?: throw SdpNegotiationError("no video stream")
        val control = session.media.firstOrNull { it.media == "application" && "MCVideo" in it.formats && it.port != 0 }
        val audioPt = audio.formats.firstOrNull { audio.rtpmap[it.toIntOrNull()]?.startsWith("AMR-WB/16000", true) == true }?.toInt()
            ?: throw SdpNegotiationError("no AMR-WB payload type")
        val videoPt = video.formats.firstOrNull { video.rtpmap[it.toIntOrNull()]?.startsWith("H264/90000", true) == true }?.toInt()
            ?: throw SdpNegotiationError("no H.264 payload type")
        val videoFmtp = params(video.fmtp(videoPt.toString()) ?: "")
        if ((videoFmtp["packetization-mode"] ?: "0") != "1") throw SdpNegotiationError("H.264 packetization mode ${videoFmtp["packetization-mode"]} is not 1")
        val audioFmtp = params(audio.fmtp(audioPt.toString()) ?: "")
        val controlParams = control?.let { params(it.fmtp("MCVideo") ?: "") }
        val controlSsrc = controlParams?.let { it["mc_transmission_ssrc"]?.toLongOrNull() ?: throw SdpNegotiationError("no mc_transmission_ssrc") }
        val address = session.address(audio) ?: throw SdpNegotiationError("no connection address")
        return RemoteVideoMedia(address, audio.port, audioPt, audioFmtp["octet-align"] == "1", video.port, videoPt,
            control?.port, controlSsrc, controlParams?.get("mc_priority")?.toIntOrNull(),
            DIRECTIONS.firstOrNull { video.has(it) } ?: DIRECTIONS.firstOrNull { d -> session.attributes.any { it.first == d } } ?: "sendrecv",
            protected = audio.proto == "RTP/SAVP" || video.proto == "RTP/SAVP",
            keyMgmt = (session.attributes.firstOrNull { it.first == "key-mgmt" }?.second ?: audio.attribute("key-mgmt"))
                ?.takeIf { it.startsWith("mikey ") }?.removePrefix("mikey ")?.trim(),
            modeSet = try { AmrWb.modeSet(audioFmtp["mode-set"]) } catch (e: IllegalArgumentException) { throw SdpNegotiationError(e.message ?: "mode-set") })
    }

    private val DIRECTIONS = listOf("sendrecv", "sendonly", "recvonly", "inactive")

    /** RFC 3264 §6.1: a `sendonly` offer is answered `recvonly` and a `recvonly` one `sendonly`. */
    fun answerDirection(offered: String): String = when (offered) { "sendonly" -> "recvonly"; "recvonly" -> "sendonly"; else -> offered }

    /** fmtp parameters, separated by `;` (or `:`, which the server also accepts between parameters). */
    private fun params(text: String): Map<String, String?> = text.split(';', ':').map { it.trim() }.filter { it.isNotEmpty() }
        .associate { p -> p.substringBefore('=').trim() to p.substringAfter('=', "").trim().ifEmpty { null } }
}
