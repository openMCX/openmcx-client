//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.security.KeyMessage
import org.openmcx.client.security.SecureStream
import org.openmcx.client.security.SecurityMaterial
import org.openmcx.client.security.SrtpError
import org.openmcx.client.security.SrtpKeys

/**
 * The keys of one MCVideo call's packets (TS 24.281 4.7, TS 33.180 7 and 9.4): transmission control as
 * SRTCP under the user's CSK (CS-ID 8), and the audio and video as SRTP: a private call's under its PCK
 * (the initiator's streams on CS-ID 2, the receiver's on 3), a group call's under the group's GMK
 * ([Group]). One SRTP context per stream; a packet that fails to authenticate is dropped and creates no
 * context. Null keys leave those packets in the clear.
 */
open class CallProtection(
    csk: KeyMessage?,
    private val sentMedia: SecurityMaterial?,
    private val receivedMedia: SecurityMaterial?,
) {
    private val floor: SecurityMaterial? = csk?.let { SrtpKeys.floorControl(it, mcvideo = true) }
    private val floors = HashMap<Long, SecureStream>()
    private val sent = HashMap<Long, SecureStream>()
    private val received = HashMap<Long, SecureStream>()
    @Volatile var failures = 0
        protected set

    init {
        // The first cipher of a process can take most of a second to set up on a phone: do it now, at call setup,
        // rather than on the first packets (which would pile up and be lost meanwhile).
        warmUp()
    }

    open val protectsMedia get() = sentMedia != null
    val protectsControl get() = floor != null

    protected fun u32(p: ByteArray, at: Int) = if (p.size < at + 4) -1L else
        ((p[at].toLong() and 0xff) shl 24) or ((p[at + 1].toLong() and 0xff) shl 16) or ((p[at + 2].toLong() and 0xff) shl 8) or (p[at + 3].toLong() and 0xff)

    /** Run [action] on the stream [key] of [map], made by [create] if new and kept only if the packet passes. */
    protected fun <T> use(map: HashMap<Long, SecureStream>, key: Long, create: () -> SecureStream, action: (SecureStream) -> T): T? = synchronized(map) {
        val known = map[key]
        val stream = known ?: create()
        try {
            action(stream).also { if (known == null && map.size < 64) map[key] = stream }
        } catch (e: SrtpError) {
            failures++; null
        }
    }

    /** [action] on the stream of the SSRC at [at] in [packet], under [material]. */
    private fun <T> on(map: HashMap<Long, SecureStream>, material: SecurityMaterial, packet: ByteArray, at: Int, action: (SecureStream) -> T): T? {
        val ssrc = u32(packet, at)
        return use(map, ssrc, { SecureStream(material, ssrc) }, action)
    }

    open fun protectRtp(packet: ByteArray): ByteArray? = sentMedia?.let { on(sent, it, packet, 8) { s -> s.protectRtp(packet) } } ?: packet.takeIf { sentMedia == null }

    open fun unprotectRtp(packet: ByteArray): ByteArray? = receivedMedia?.let { on(received, it, packet, 8) { s -> s.unprotectRtp(packet) } } ?: packet.takeIf { receivedMedia == null }

    fun protectControl(packet: ByteArray): ByteArray? = floor?.let { on(floors, it, packet, 4) { s -> s.protectRtcp(packet) } } ?: packet.takeIf { floor == null }

    fun unprotectControl(packet: ByteArray): ByteArray? = floor?.let { on(floors, it, packet, 4) { s -> s.unprotectRtcp(packet) } } ?: packet.takeIf { floor == null }

    /**
     * A group call under the group's GMK (TS 33.180 7.3, E.3.2), [gmk] carrying the GMK-ID: each sender's
     * streams keyed by its GUK-ID on CS-ID 5, MKI GMK-ID ‖ GUK-ID and the ROC in every packet. A received
     * packet's sender is the one of [senders] (the transmitter first) whose GUK-ID its MKI carries.
     */
    class Group(csk: KeyMessage?, private val gmk: KeyMessage, own: String, private val senders: () -> Collection<String>) :
        CallProtection(csk, null, null) {
        private val sentMaterial = SrtpKeys.groupCall(gmk, gmk.csbId, SrtpKeys.gukId(gmk.key, gmk.csbId, own), mcvideo = true)
        private val sent = HashMap<Long, SecureStream>()
        private val received = HashMap<Long, SecureStream>()
        private val gukOf = HashMap<String, Long>()
        private val materials = HashMap<Long, SecurityMaterial>()
        override val protectsMedia get() = true
        val gmkId get() = gmk.csbId

        override fun protectRtp(packet: ByteArray): ByteArray? {
            val ssrc = u32(packet, 8)
            return use(sent, ssrc, { SecureStream(sentMaterial, ssrc) }) { it.protectRtp(packet) }
        }

        override fun unprotectRtp(packet: ByteArray): ByteArray? {
            if (packet.size < 12 + 16 + 12) { failures++; return null }
            val mki = packet.size - 12
            if (u32(packet, mki) != gmk.csbId) { failures++; return null }
            val guk = u32(packet, mki + 4)
            val known = synchronized(gukOf) { senders().any { s -> gukOf.getOrPut(s) { SrtpKeys.gukId(gmk.key, gmk.csbId, s) } == guk } }
            if (!known) { failures++; return null }
            val material = synchronized(materials) { materials.getOrPut(guk) { SrtpKeys.groupCall(gmk, gmk.csbId, guk, mcvideo = true) } }
            val ssrc = u32(packet, 8)
            // One context per sender and SSRC.
            return use(received, ssrc xor (guk shl 32), { SecureStream(material, ssrc) }) { it.unprotectRtp(packet) }
        }
    }

    companion object {
        @Volatile private var warm = false

        /** Load and exercise AES-GCM and the key derivation once per process. */
        fun warmUp() {
            if (warm) return
            val material = SecurityMaterial(ByteArray(16), ByteArray(12), byteArrayOf(0, 0, 0, 1), media = true)
            val packet = byteArrayOf(0x80.toByte(), 0x60, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 2, 3, 4)
            SecureStream(material, 1).unprotectRtp(SecureStream(material, 1).protectRtp(packet))
            warm = true
        }

        /** A private (one-to-one) call under [pck], whose initiator we are or not. */
        fun privateCall(csk: KeyMessage?, pck: KeyMessage, localInitiator: Boolean) = CallProtection(csk,
            SrtpKeys.privateCall(pck, fromInitiator = localInitiator, mcvideo = true), SrtpKeys.privateCall(pck, fromInitiator = !localInitiator, mcvideo = true))
    }
}
