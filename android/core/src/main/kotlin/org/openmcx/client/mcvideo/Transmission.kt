package org.openmcx.client.mcvideo

import org.openmcx.client.media.RtcpApp
import org.openmcx.client.media.RtcpAppError
import java.nio.ByteBuffer

class TransmissionCodecError(message: String) : Exception(message)

/**
 * MCVideo transmission control message types (TS 24.581 §9.2.2.1): the APP name (MCV0 from the
 * participant, MCV1 from the server, MCV2 from either) and the subtype's low four bits.
 */
enum class TxType(val appName: String, val subtype: Int) {
    REQUEST("MCV0", 0), RELEASE("MCV0", 2), QUEUE_POSITION_REQUEST("MCV0", 3), RECEIVE_MEDIA_REQUEST("MCV0", 4),
    REMOTE_REQUEST("MCV0", 7), REMOTE_CANCEL_REQUEST("MCV0", 8),
    GRANTED("MCV1", 0), REJECTED("MCV1", 1), TAKEN("MCV1", 2), ARBITRATION_RELEASE("MCV1", 3), REVOKED("MCV1", 4),
    QUEUE_POSITION_INFO("MCV1", 5), MEDIA_TRANSMISSION_NOTIFICATION("MCV1", 6), RECEIVE_MEDIA_RESPONSE("MCV1", 7),
    MEDIA_RECEPTION_NOTIFICATION("MCV1", 8), CANCEL_REQUEST_NOTIFY("MCV1", 10), REMOTE_RESPONSE("MCV1", 11),
    REMOTE_CANCEL_RESPONSE("MCV1", 12), RECEPTION_OVERRIDE_NOTIFICATION("MCV1", 13), END_NOTIFY("MCV1", 14), IDLE("MCV1", 15),
    END_REQUEST("MCV2", 0), END_RESPONSE("MCV2", 1), RECEPTION_END_REQUEST("MCV2", 2), RECEPTION_END_RESPONSE("MCV2", 3),
    ACK("MCV2", 4);

    val fromServer get() = appName != "MCV0"

    companion object {
        fun of(appName: String, subtype: Int) = entries.firstOrNull { it.appName == appName && it.subtype == subtype }
    }
}

/** Field IDs (TS 24.581 table 9.2.3.1-1; the TS 24.380 numbers where shared, Video SSRC 23). */
object TxField {
    const val PRIORITY = 0
    const val DURATION = 1
    const val REJECT_CAUSE = 2
    const val QUEUE_INFO = 3
    const val TRANSMITTING_USER_ID = 4
    const val PERMISSION_TO_REQUEST = 5
    const val USER_ID = 6
    const val MESSAGE_SEQUENCE = 8
    const val SOURCE = 10
    const val MESSAGE_TYPE = 12
    const val INDICATOR = 13
    const val AUDIO_SSRC = 14
    const val RESULT = 15
    const val VIDEO_SSRC = 23
}

/** One transmission control message (TS 24.581 §9.2): an RTCP APP packet. */
data class TransmissionMessage(
    val type: TxType,
    val ssrc: Long,
    val ackRequired: Boolean = false,
    val fields: List<Pair<Int, ByteArray>> = emptyList(),
) {
    fun field(id: Int): ByteArray? = fields.firstOrNull { it.first == id }?.second
    private fun u16(id: Int): Int? = field(id)?.takeIf { it.size >= 2 }?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
    private fun ssrcField(id: Int): Long? = field(id)?.takeIf { it.size >= 4 }?.let { ByteBuffer.wrap(it, 0, 4).int.toLong() and 0xffffffffL }

    val durationSeconds: Int? get() = u16(TxField.DURATION)
    val rejectCause: Int? get() = u16(TxField.REJECT_CAUSE)
    val rejectText: String? get() = field(TxField.REJECT_CAUSE)?.takeIf { it.size > 2 }?.let { String(it, 2, it.size - 2) }
    val sequence: Int? get() = u16(TxField.MESSAGE_SEQUENCE)
    val audioSsrc: Long? get() = ssrcField(TxField.AUDIO_SSRC)
    val videoSsrc: Long? get() = ssrcField(TxField.VIDEO_SSRC)
    val transmittingUser: String? get() = field(TxField.TRANSMITTING_USER_ID)?.let { String(it, Charsets.UTF_8) }
    val userId: String? get() = field(TxField.USER_ID)?.let { String(it, Charsets.UTF_8) }
    /** The Result field of a remote transmission response: true when done (§9.2.3.17). */
    val result: Boolean? get() = field(TxField.RESULT)?.firstOrNull()?.let { it.toInt() == 1 }

    fun encode(): ByteArray {
        if (ackRequired && type == TxType.ACK) throw TransmissionCodecError("an ack cannot ask for an ack")
        return RtcpApp((if (ackRequired) 0x10 else 0) or type.subtype, ssrc, type.appName, fields).encode()
    }

    override fun toString(): String = "${type.name}${if (ackRequired) " (ack)" else ""} ssrc=%08x %s".format(ssrc,
        fields.joinToString(" ") { (id, v) -> "$id=" + v.joinToString("") { "%02x".format(it) } })

    override fun equals(other: Any?) = other is TransmissionMessage && type == other.type && ssrc == other.ssrc &&
        ackRequired == other.ackRequired && fields.size == other.fields.size &&
        fields.zip(other.fields).all { (a, b) -> a.first == b.first && a.second.contentEquals(b.second) }
    override fun hashCode() = type.hashCode() * 31 + ssrc.hashCode()

    companion object {
        fun decode(data: ByteArray, length: Int = data.size): TransmissionMessage {
            val app = try { RtcpApp.decode(data, length) } catch (e: RtcpAppError) { throw TransmissionCodecError(e.message ?: "bad packet") }
            if (app.name !in setOf("MCV0", "MCV1", "MCV2")) throw TransmissionCodecError("APP name ${app.name} is not MCVideo transmission control")
            val type = TxType.of(app.name, app.subtype and 0x0f) ?: throw TransmissionCodecError("unknown or void subtype ${app.subtype and 0x0f} of ${app.name}")
            val ack = app.subtype and 0x10 != 0
            if (ack && type == TxType.ACK) throw TransmissionCodecError("an ack cannot ask for an ack")
            for ((id, value) in app.fields) when (id) {
                TxField.AUDIO_SSRC, TxField.VIDEO_SSRC ->
                    if (value.size != 6 || value[4].toInt() != 0 || value[5].toInt() != 0) throw TransmissionCodecError("invalid SSRC field")
                TxField.DURATION, TxField.MESSAGE_SEQUENCE, TxField.SOURCE, TxField.INDICATOR ->
                    if (value.size != 2) throw TransmissionCodecError("invalid field $id")
                TxField.TRANSMITTING_USER_ID, TxField.USER_ID -> if (value.isEmpty()) throw TransmissionCodecError("empty identity field")
            }
            return TransmissionMessage(type, app.ssrc, ack, app.fields)
        }

        fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        /** An SSRC field: four octets of SSRC and two spare (§9.2.3.15, §9.2.3.24). */
        fun ssrcValue(ssrc: Long) = byteArrayOf((ssrc shr 24).toByte(), (ssrc shr 16).toByte(), (ssrc shr 8).toByte(), ssrc.toByte(), 0, 0)

        /** Transmission Request (§9.2.4), optionally with a priority. */
        fun request(ssrc: Long, priority: Int? = null) =
            TransmissionMessage(TxType.REQUEST, ssrc, fields = listOfNotNull(priority?.let { TxField.PRIORITY to byteArrayOf(it.toByte(), 0) }))

        fun release(ssrc: Long) = TransmissionMessage(TxType.RELEASE, ssrc)

        /** Remote Transmission (cancel) request (§9.2.22, §9.2.24) naming [user]; the Remote ID has no field and is left out. */
        fun remote(ssrc: Long, user: String, start: Boolean) = TransmissionMessage(
            if (start) TxType.REMOTE_REQUEST else TxType.REMOTE_CANCEL_REQUEST, ssrc, fields = listOf(TxField.USER_ID to user.toByteArray()))

        /** Receive Media Request (§9.2.14): accept a notified transmission. */
        fun receiveMedia(ssrc: Long) = TransmissionMessage(TxType.RECEIVE_MEDIA_REQUEST, ssrc)

        /** Transmission control ack (§9.2.29) for [type], from the participant (source 0). */
        fun ack(ssrc: Long, type: TxType) = TransmissionMessage(TxType.ACK, ssrc,
            fields = listOf(TxField.SOURCE to u16(0), TxField.MESSAGE_TYPE to byteArrayOf(type.subtype.toByte(), 0)))
    }
}
