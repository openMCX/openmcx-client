//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.openmcx.client.media.RtcpApp
import org.openmcx.client.media.RtcpAppError

// Call setup control over a pre-established session, media plane (TS 24.380 §8.3, §9.2.2): the "MCPC" messages
// Connect, Disconnect and Acknowledge, RTCP APP packets coded as floor control is (§8.1), on the session's media plane
// control channel; and the MCPTT client's state machine of §9.2.2 ('Start-stop', 'U: Pre-established session not in
// use', 'U: Pre-established session in use').
//
// The field IDs and values are those of TS 24.380 tables 8.3.3.1-2, 8.3.3.3, 8.3.3.6, 8.3.3.8 and 8.3.3.11, as the
// OpenMCX server codes them (include/mcx/pre_established.hpp).
//
// Specifications: TS 24.380 V19.2.0 (Rel-19), RFC 3550.

class McpcCodecError(message: String) : Exception(message)

/** TS 24.380 table 8.3.2-1: the message types, by subtype (without the first, "acknowledgement required", bit). */
object McpcType {
    const val CONNECT = 0
    const val DISCONNECT = 1
    const val ACKNOWLEDGE = 2
    fun name(type: Int) = when (type) { CONNECT -> "Connect"; DISCONNECT -> "Disconnect"; ACKNOWLEDGE -> "Acknowledge"; else -> "type $type" }
}

/** TS 24.380 table 8.3.3.1-2: the field IDs. */
object McpcField {
    const val MEDIA_STREAMS = 0
    const val SESSION_IDENTITY = 1
    const val WARNING_TEXT = 2
    const val GROUP_IDENTITY = 3
    const val ANSWER_STATE = 4
    const val INVITING_USER = 5
    const val REASON_CODE = 6
    const val REASON_CAUSE = 7
    const val INVITED_USER = 8
    const val PCK_I_MESSAGE = 192
}

/** TS 24.380 §8.3.3.3: the <Session Type> of the MCPTT Session Identity field. */
object McpcSessionType {
    const val NONE = 0
    const val PRIVATE = 1
    const val PREARRANGED = 3
    const val CHAT = 4
}

/** TS 24.380 §8.3.3.8: the <Reason Code> of an Acknowledge. */
object McpcReasonCode {
    const val ACCEPTED = 0
    const val BUSY = 1
    const val NOT_ACCEPTED = 2
    const val MIKEY_AUTHENTICATION_FAILED = 3
}

/**
 * One pre-established session call control message (TS 24.380 §8.3.4 to §8.3.6). Connect: the MCPTT Session Identity
 * (mandatory), MCPTT Group Identity, Media Streams (the m-line numbers of speech and of the control channel, 0 without
 * floor control), Warning Text, Answer State (0 unconfirmed, 1 confirmed), Inviting and Invited MCPTT User Identity,
 * PCK I_MESSAGE; Disconnect: the Session Identity and a Reason Cause; Acknowledge: a Reason Code.
 */
data class McpcMessage(
    val type: Int,
    val ssrc: Long,
    val ackRequired: Boolean = false,
    val sessionType: Int = McpcSessionType.NONE,
    val sessionIdentity: String? = null,
    val groupIdentity: String? = null,
    val mediaStream: Int? = null,
    val controlChannel: Int? = null,
    val warningText: String? = null,
    val answerState: Int? = null,
    val invitingUser: String? = null,
    val invitedUser: String? = null,
    val pckIMessage: ByteArray? = null,
    val reasonCode: Int? = null,
    val reasonCause: Int? = null,
) {
    fun encode(): ByteArray {
        val fields = mutableListOf<Pair<Int, ByteArray>>()
        fun text(id: Int, value: String?) { if (value != null) fields += id to value.toByteArray(Charsets.UTF_8) }
        fun u16(id: Int, value: Int?) { if (value != null) fields += id to byteArrayOf((value shr 8).toByte(), value.toByte()) }
        if (type == McpcType.ACKNOWLEDGE) {
            if (ackRequired) throw McpcCodecError("an Acknowledge cannot ask for an acknowledgement (table 8.3.2-1)")
            u16(McpcField.REASON_CODE, reasonCode ?: throw McpcCodecError("Acknowledge without Reason Code (§8.3.6)"))
        } else {
            val identity = sessionIdentity ?: throw McpcCodecError("Connect or Disconnect without MCPTT Session Identity")
            fields += McpcField.SESSION_IDENTITY to (byteArrayOf(sessionType.toByte()) + identity.toByteArray(Charsets.UTF_8))
            if (type == McpcType.CONNECT) {
                text(McpcField.GROUP_IDENTITY, groupIdentity)
                if (mediaStream != null) fields += McpcField.MEDIA_STREAMS to byteArrayOf(mediaStream.toByte(), (controlChannel ?: 0).toByte())
                text(McpcField.WARNING_TEXT, warningText)
                u16(McpcField.ANSWER_STATE, answerState)
                text(McpcField.INVITING_USER, invitingUser)
                text(McpcField.INVITED_USER, invitedUser)
                pckIMessage?.let { fields += McpcField.PCK_I_MESSAGE to it }
            } else u16(McpcField.REASON_CAUSE, reasonCause)
        }
        return RtcpApp((if (ackRequired) 0x10 else 0) or type, ssrc, NAME, fields).encode()
    }

    override fun toString() = "${McpcType.name(type)}${if (ackRequired) " (ack)" else ""} ssrc=%08x".format(ssrc) +
        (sessionIdentity?.let { " session=$it" } ?: "") + (groupIdentity?.let { " group=$it" } ?: "") +
        (invitingUser?.let { " from=$it" } ?: "") + (reasonCode?.let { " reason-code=$it" } ?: "") +
        (reasonCause?.let { " reason-cause=$it" } ?: "") + (answerState?.let { " answer-state=$it" } ?: "") +
        (controlChannel?.let { " control-channel=$it" } ?: "")

    override fun equals(other: Any?) = other is McpcMessage && toString() == other.toString() &&
        (pckIMessage?.contentEquals(other.pckIMessage ?: ByteArray(0)) ?: (other.pckIMessage == null))
    override fun hashCode() = toString().hashCode()

    companion object {
        const val NAME = "MCPC"

        /** TS 24.380 §8.1.2, §8.3.1: an RTCP APP packet named "MCPC" (told from floor control, "MCPT", on the same channel). */
        fun isMcpc(data: ByteArray, length: Int = data.size): Boolean = length >= 12 && (data[0].toInt() and 0xc0) == 0x80 &&
            (data[1].toInt() and 0xff) == 204 && String(data, 8, 4, Charsets.US_ASCII) == NAME

        /**
         * TS 24.380 §8.3.2 to §8.3.6 with §8.3.3.9: an unknown subtype gives null (step 1, the message ignored); unknown
         * fields and malformed optional ones are skipped (steps 2 and 3); a missing mandatory field throws.
         */
        fun decode(data: ByteArray, length: Int = data.size): McpcMessage? {
            val app = try { RtcpApp.decode(data, length) } catch (e: RtcpAppError) { throw McpcCodecError(e.message ?: "bad packet") }
            if (app.name != NAME) throw McpcCodecError("APP name is not MCPC")
            val ack = app.subtype == McpcType.ACKNOWLEDGE
            val type = if (ack) McpcType.ACKNOWLEDGE else (app.subtype and 0x0f).takeIf { it <= McpcType.DISCONNECT } ?: return null
            fun u16(v: ByteArray) = v.takeIf { it.size == 2 }?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
            var m = McpcMessage(type, app.ssrc, ackRequired = !ack && app.subtype and 0x10 != 0)
            var mandatory = false
            for ((id, v) in app.fields) {
                when (id) {
                    McpcField.SESSION_IDENTITY -> if (!ack && v.size >= 2) {
                        m = m.copy(sessionType = v[0].toInt() and 0xff, sessionIdentity = String(v, 1, v.size - 1, Charsets.UTF_8)); mandatory = true
                    }
                    McpcField.REASON_CODE -> if (ack) u16(v)?.let { m = m.copy(reasonCode = it); mandatory = true }
                    McpcField.REASON_CAUSE -> if (type == McpcType.DISCONNECT) u16(v)?.let { m = m.copy(reasonCause = it) }
                    else -> if (type == McpcType.CONNECT) m = when (id) {
                        McpcField.MEDIA_STREAMS -> if (v.size == 2) m.copy(mediaStream = v[0].toInt() and 0xff, controlChannel = v[1].toInt() and 0xff) else m
                        McpcField.ANSWER_STATE -> u16(v)?.let { m.copy(answerState = it) } ?: m
                        McpcField.GROUP_IDENTITY -> m.copy(groupIdentity = String(v, Charsets.UTF_8))
                        McpcField.WARNING_TEXT -> m.copy(warningText = String(v, Charsets.UTF_8))
                        McpcField.INVITING_USER -> m.copy(invitingUser = String(v, Charsets.UTF_8))
                        McpcField.INVITED_USER -> m.copy(invitedUser = String(v, Charsets.UTF_8))
                        McpcField.PCK_I_MESSAGE -> m.copy(pckIMessage = v)
                        else -> m // §8.3.3.9 step 2: a field of a later version
                    }
                }
            }
            if (!mandatory) throw McpcCodecError("MCPC message without its mandatory field (§8.3.4 to §8.3.6)")
            return m
        }

        /** TS 24.380 §8.3.6: an Acknowledge, from the floor participant, with [reasonCode]. */
        fun acknowledge(ssrc: Long, reasonCode: Int = McpcReasonCode.ACCEPTED) = McpcMessage(McpcType.ACKNOWLEDGE, ssrc, reasonCode = reasonCode)
    }
}

/** TS 24.380 §9.2.2: the MCPTT client's states of call setup control over a pre-established session. */
enum class PreSessionControlState { START_STOP, NOT_IN_USE, IN_USE }

/**
 * TS 24.380 §9.2.2: the client's state machine. It answers each Connect and Disconnect with the Acknowledge it asks
 * for, and says whether a call started or ended; messages with no procedure in the current state are discarded
 * (§9.2.2.1).
 */
class PreSessionControl(@Volatile var ssrc: Long) {
    @Volatile var state = PreSessionControlState.START_STOP
        private set

    /** What a received message leads to: the Acknowledge to send, and whether a call starts or ends on the session. */
    data class Outcome(val acknowledge: McpcMessage? = null, val callStarted: Boolean = false, val callEnded: Boolean = false)

    /** §9.2.2.2.2: the session's dialog is confirmed. */
    fun started() { state = PreSessionControlState.NOT_IN_USE }
    /** §9.2.2.3.3: the session is released. */
    fun stopped() { state = PreSessionControlState.START_STOP }
    /** §9.2.2.3.5: a 2xx to the REFER that starts a call on the session. */
    fun referAccepted() { if (state == PreSessionControlState.NOT_IN_USE) state = PreSessionControlState.IN_USE }
    /** §9.2.2.3.6: the 200 (OK) to the re-INVITE that starts a call on the session is sent. */
    fun reinviteAnswered() { if (state == PreSessionControlState.NOT_IN_USE) state = PreSessionControlState.IN_USE }
    /** §9.2.2.4.6: a 2xx to the REFER "BYE" that leaves the call and keeps the session. */
    fun leaveAccepted() { if (state == PreSessionControlState.IN_USE) state = PreSessionControlState.NOT_IN_USE }

    /**
     * §9.2.2.3.2 (not in use: 1) the Acknowledge with [answer] when asked; 'Accepted': the call starts, 'U:
     * Pre-established session in use') and §9.2.2.4.2 (in use: the Connect of this client's own call, acknowledged).
     */
    fun connect(message: McpcMessage, answer: Int = McpcReasonCode.ACCEPTED): Outcome = when (state) {
        PreSessionControlState.NOT_IN_USE -> {
            val ack = if (message.ackRequired) McpcMessage.acknowledge(ssrc, answer) else null
            if (answer == McpcReasonCode.ACCEPTED) { state = PreSessionControlState.IN_USE; Outcome(ack, callStarted = true) } else Outcome(ack)
        }
        PreSessionControlState.IN_USE -> Outcome(if (message.ackRequired) McpcMessage.acknowledge(ssrc) else null, callStarted = true)
        PreSessionControlState.START_STOP -> Outcome()
    }

    /** §9.2.2.3.4 (not in use: acknowledged) and §9.2.2.4.5 (in use: acknowledged, the call ends, not in use). */
    fun disconnect(message: McpcMessage): Outcome {
        val ack = if (message.ackRequired && state != PreSessionControlState.START_STOP) McpcMessage.acknowledge(ssrc) else null
        return if (state == PreSessionControlState.IN_USE) { state = PreSessionControlState.NOT_IN_USE; Outcome(ack, callEnded = true) } else Outcome(ack)
    }
}
